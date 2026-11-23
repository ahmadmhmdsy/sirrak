package app.sirrak

import android.app.Application
import android.content.Context
import android.util.Log
import app.sirrak.core.runtime.AppConfig
import app.sirrak.core.runtime.AttachmentStore
import app.sirrak.core.runtime.AppConfigLoader
import app.sirrak.core.runtime.AppContext
import app.sirrak.core.runtime.AppNavigator
import app.sirrak.core.runtime.ComposerTextRequests
import app.sirrak.core.runtime.ContentRepository
import app.sirrak.core.runtime.ContextWindowProvider
import app.sirrak.core.runtime.ConversationLibrary
import app.sirrak.core.runtime.ModelIdentity
import app.sirrak.core.runtime.ModelSettingsStore
import app.sirrak.core.runtime.ModelStore
import app.sirrak.core.runtime.Plugin
import app.sirrak.core.models.DeviceProfileProvider
import app.sirrak.core.models.LicenceConsentStore
import app.sirrak.core.models.ModelCatalogueProvider
import app.sirrak.core.models.ModelDownloader
import app.sirrak.core.models.OnboardingStore
import app.sirrak.core.privacy.AiReportQueue
import app.sirrak.core.privacy.NetworkAudit
import app.sirrak.core.privacy.OfflineModeStore
import app.sirrak.core.rag.ExtractorRegistry
import app.sirrak.core.rag.RagService
import app.sirrak.core.rag.defaultExtractors
import app.sirrak.core.runtime.PresetRepository
import app.sirrak.core.runtime.LicenceRegistry
import app.sirrak.core.runtime.PrivacyConfig
import app.sirrak.core.runtime.SettingsService
import app.sirrak.engine.embeddings.LiteRtEmbeddingProviderFactory
import app.sirrak.engine.llamacpp.LlamaCppEngine
import app.sirrak.rag.PdfBoxExtractor
import app.sirrak.feature.chat.ChatPlugin
import app.sirrak.feature.models.ModelManagerPlugin
import app.sirrak.feature.privacy.PrivacyPlugin
import app.sirrak.feature.settings.SettingsPlugin
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The composition manifest: the one place that names feature plugins, plus the one place that picks
 * the engine implementation from config. Adding a feature is a Gradle module, one line here, and
 * (optionally) a line in assets/config/app.json. Removing it is the same in reverse.
 */
object AppComposition {

    private const val CONFIG_ASSET = "config/app.json"
    private const val TAG = "SirrakComposition"

    fun build(application: Application): AppContext {
        val config = loadConfig(application)
        val context = AppContext(config)

        // Host services: things no feature should have to know how to construct.
        context.provideService(Context::class.java, application.applicationContext)
        context.provideService(AppNavigator::class.java, AppNavigator(initial = null), owner = "host:navigation")
        val content = AssetContentRepository(application, config)
        context.provideService(ContentRepository::class.java, content, owner = "host:content")
        context.provideService(PresetRepository::class.java, content, owner = "host:presets")
        context.provideService(
            SettingsService::class.java,
            SharedPrefsSettingsService(application, config),
            owner = "host:settings",
        )
        context.provideService(
            ModelStore::class.java,
            FileModelStore(application),
            owner = "host:models",
        )
        // M6: the conversation library owns durability. The M3 single file is imported once on first
        // read and kept as conversation.json.migrated, so a rollback is a file rename (doc 20).
        context.provideService(
            ConversationLibrary::class.java,
            FileConversationLibrary(application.filesDir),
            owner = "host:conversations",
        )
        // The chat meter must know the window the engine was actually loaded with, not the model's
        // training maximum: config is the one owner of that number.
        context.provideService(
            ContextWindowProvider::class.java,
            ContextWindowProvider { config.engine.contextTokens },
            owner = "host:context-window",
        )
        context.provideService(
            ModelSettingsStore::class.java,
            FileModelSettingsStore(application.filesDir),
            owner = "host:model-settings",
        )
        context.provideService(
            ModelIdentity::class.java,
            FingerprintModelIdentity(File(application.filesDir, "model_keys.json")),
            owner = "host:model-identity",
        )
        // M7: attachment bytes and metadata live in app-private storage, one directory per
        // conversation. The chat feature only sees the AttachmentStore seam; the scheduler (M8) and
        // vision (M9) pipelines receive the same handle later.
        val attachmentStore = FileAttachmentStore(application.filesDir)
        context.provideService(AttachmentStore::class.java, attachmentStore, owner = "host:attachments")
        // M10: one host-owned channel from any feature to the chat composer. The voice feature
        // appends transcripts; the chat screen drains them. Neither feature imports the other.
        val composerTextRequests = ComposerTextRequests()
        context.provideService(
            ComposerTextRequests::class.java,
            composerTextRequests,
            owner = "host:composer-text",
        )
        // M11: model manager. The catalogue is an asset, the device report is measured, and one
        // host-owned downloader serialises downloads so two 1 GB files never race for storage. The
        // scope lives with the graph: leaving the app cancels an in-flight download, and the .part
        // file makes it resume on the next start.
        context.provideService(
            ModelCatalogueProvider::class.java,
            AssetModelCatalogue(application.assets, config.content.catalog),
            owner = "host:model-catalogue",
        )
        context.provideService(
            DeviceProfileProvider::class.java,
            AndroidDeviceProfiler(application),
            owner = "host:device-profile",
        )
        context.provideService(
            OnboardingStore::class.java,
            SharedPrefsOnboardingStore(application),
            owner = "host:onboarding",
        )
        context.provideService(
            LicenceConsentStore::class.java,
            SharedPrefsLicenceConsentStore(application),
            owner = "host:model-consent",
        )
        // P3 privacy surfaces. One SharedPreferences file backs the offline-only switch, the bounded
        // on-device network log and the local AI-output report queue; app.json declares the endpoints,
        // so the privacy screen is data-driven and the transport records what it actually contacted.
        context.provideService(
            PrivacyConfig::class.java,
            config.privacy,
            owner = "host:privacy-config",
        )
        val offlineModeStore = SharedPrefsOfflineModeStore(application, config.privacy.offlineByDefault)
        context.provideService(
            OfflineModeStore::class.java,
            offlineModeStore,
            owner = "host:offline-mode",
        )
        val networkAudit = NetworkAudit(SharedPrefsNetworkAuditStore(application))
        context.provideService(NetworkAudit::class.java, networkAudit, owner = "host:network-audit")
        val reportQueue = AiReportQueue(SharedPrefsAiReportStore(application))
        context.provideService(AiReportQueue::class.java, reportQueue, owner = "host:ai-reports")

        // P3.2: the licence/SBOM registry is an asset (app.json -> licences.assetPath). The About
        // screen renders it and the checked-in NOTICE is generated from the same entries, so a new
        // component is one JSON edit rather than a hardcoded licence row.
        context.provideService(
            LicenceRegistry::class.java,
            AssetLicenceRegistry(application.assets, config.licences.assetPath).registry(),
            owner = "host:licences",
        )

        val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        context.provideService(
            ModelDownloader::class.java,
            ModelDownloader(
                transport = HttpDownloadTransport(audit = networkAudit, endpointId = "model_download"),
                networkPolicy = AndroidNetworkPolicy(application, offlineModeStore),
                storage = AndroidStorageProbe(application.filesDir),
                scope = downloadScope,
                stagingDir = File(application.filesDir, "model_downloads"),
                // P3.4: while a transfer runs, keep the process alive with a dataSync foreground
                // service. The downloader stays pure Kotlin; this host lambda is the only Android tie.
                onActiveChanged = { active ->
                    if (active) DownloadService.start(application)
                },
            ),
            owner = "host:model-downloader",
        )
        context.registerEffect("host:model-downloader") { downloadScope.cancel() }

        // M8: document grounding. Originals, chunks and vectors live in app-private storage; the
        // embedding model is user-imported (never bundled, per the Gemma terms) and opened lazily.
        // PDFBox needs one process-wide asset initialisation, performed on the first PDF import.
        val embeddingStore = FileEmbeddingModelStore(
            rootDir = application.filesDir,
            factory = LiteRtEmbeddingProviderFactory(),
        )
        val pdfInitialisation = lazy { PDFBoxResourceLoader.init(application) }
        val extractors = ExtractorRegistry(
            defaultExtractors() + PdfBoxExtractor(initialize = { pdfInitialisation.value }),
        )
        val ragRepository = RagRepository(
            store = FileRagStore(application.filesDir),
            embeddings = embeddingStore,
            extractors = extractors,
        )
        context.provideService(RagService::class.java, ragRepository, owner = "host:rag")
        context.provideService(
            FileEmbeddingModelStore::class.java,
            embeddingStore,
            owner = "host:embeddings",
        )

        // The engine is a host-level choice, not a feature: exactly one InferenceEngine provider is
        // installed, selected by config. Feature modules never name a concrete engine.
        val enginePlugin = resolveEnginePlugin(application, config)
        val manifest: List<Plugin> = listOf(
            LibraryPlugin(application, rag = ragRepository),
            enginePlugin,
            RagPlugin(ragRepository, embeddingStore),
            AboutPlugin(),
            // M9: the app supplies the Android image preparer; the chat feature only sees the seam.
            ChatPlugin(
                attachments = attachmentStore,
                rag = ragRepository,
                imagePreparer = AndroidImagePreparer(),
                composerTextRequests = composerTextRequests,
            ),
            SettingsPlugin(),
            // M10: dictation. Asr installs the engine/store/recorder/controller; Voice contributes the
            // mic, the status strip and the Settings card, and bridges transcripts to the composer.
            AsrPlugin(application),
            VoicePlugin(),
            // M11: model manager + onboarding. The plugin owns the Settings card and the onboarding
            // screens; the host above owns the catalogue, the device report and the downloader.
            ModelManagerPlugin(),
            // P3: privacy surfaces. The host above owns the stores and the endpoint registry; the
            // plugin contributes the Settings group and the per-answer report action. Flipping
            // offline-only on cancels a running download through this host hook.
            PrivacyPlugin(
                appLabel = "Sirrak",
                modelLabel = {
                    context.service(ModelStore::class.java).selected.value?.displayName.orEmpty()
                },
                onOfflineEnabled = {
                    runCatching { context.service(ModelDownloader::class.java).cancel() }
                },
            ),
        )
        val ordered = resolveManifest(manifest, config)
        val order = context.install(ordered)
        Log.i(TAG, "Installed plugins: $order")
        return context
    }

    /**
     * Picks the engine plugin for [config]. A typo cannot silently produce two InferenceEngine
     * providers: unknown ids fall back to the demo engine in release, and fail loudly in debug.
     */
    internal fun resolveEnginePlugin(application: Application, config: AppConfig): Plugin =
        when (val provider = config.engine.provider) {
            LlamaCppEngine.ID -> LlamaCppPlugin(application, config)
            DemoInferenceEngine.ID -> DemoEnginePlugin(application)
            else -> {
                val message = "engine.provider='$provider' has no engine plugin in this build"
                if (BuildConfig.DEBUG) error(message) else Log.w(TAG, "$message; using demo engine")
                DemoEnginePlugin(application)
            }
        }

    /**
     * Applies `features.enabled`/`features.order` from the asset config to the compile-time manifest.
     * Empty `enabled` means "everything in the manifest", so removing a feature is exactly one manifest
     * line. Stale ids in the JSON are logged and ignored rather than crashing the app: the config is a
     * safe override layer, while compile-time membership stays authoritative. Package-internal so the
     * composition unit test drives the same function the app calls.
     */
    internal fun resolveManifest(manifest: List<Plugin>, config: AppConfig): List<Plugin> {
        val byId = manifest.associateBy { it.id }
        val requested = if (config.features.enabled.isEmpty()) byId.keys.toList() else config.features.enabled
        val staleEnabled = requested.filterNot { byId.containsKey(it) }
        val staleOrder = config.features.order.filterNot { byId.containsKey(it) }
        if (staleEnabled.isNotEmpty() || staleOrder.isNotEmpty()) {
            Log.w(TAG, "config names plugins not in this manifest, ignored: enabled=$staleEnabled order=$staleOrder")
        }
        val enabled = requested.filter { byId.containsKey(it) }
        return if (config.features.order.isEmpty()) {
            manifest.filter { it.id in enabled }
        } else {
            manifest.filter { it.id in enabled }.sortedBy { plugin ->
                val index = config.features.order.indexOf(plugin.id)
                if (index >= 0) index else Int.MAX_VALUE
            }
        }
    }

    private fun loadConfig(application: Application): AppConfig {
        val raw = application.assets.open(CONFIG_ASSET).bufferedReader().use { it.readText() }
        val parsed = runCatching { AppConfigLoader.parse(raw) }
            .getOrElse { error ->
                if (BuildConfig.DEBUG) {
                    throw IllegalStateException("$CONFIG_ASSET is not valid: ${error.message}", error)
                }
                Log.e(TAG, "$CONFIG_ASSET is not valid; using defaults", error)
                return AppConfig()
            }
        val problems = AppConfigLoader.validate(parsed)
        if (problems.isNotEmpty()) {
            if (BuildConfig.DEBUG) error("$CONFIG_ASSET is invalid: $problems")
            Log.w(TAG, "$CONFIG_ASSET is invalid; using defaults: $problems")
            return AppConfig()
        }
        return parsed
    }
}