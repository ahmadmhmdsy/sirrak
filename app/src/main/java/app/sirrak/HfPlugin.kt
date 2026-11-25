package app.sirrak

import android.content.Context
import androidx.compose.runtime.remember
import app.sirrak.core.models.AndroidDeviceProbe
import app.sirrak.core.models.CatalogueEntry
import app.sirrak.core.models.EnqueueResult
import app.sirrak.core.models.GgufProbe
import app.sirrak.core.models.HfAccessPolicy
import app.sirrak.core.models.HfCallKind
import app.sirrak.core.models.HfClient
import app.sirrak.core.models.ModelFitEngine
import app.sirrak.core.models.UrlHfTransport
import app.sirrak.core.privacy.NetworkAudit
import app.sirrak.core.privacy.OfflineModeStore
import app.sirrak.core.runtime.AppNavigator
import app.sirrak.core.runtime.PageContribution
import app.sirrak.core.runtime.PageIds
import app.sirrak.core.runtime.Plugin
import app.sirrak.core.runtime.PluginScope
import app.sirrak.downloads.DownloadBridge
import app.sirrak.feature.models.HfEnqueueOutcome
import app.sirrak.feature.models.HfRepoPage
import app.sirrak.feature.models.HfRepoSession
import app.sirrak.feature.models.HfSearchPage
import app.sirrak.feature.models.HfSearchSession

/**
 * P3 in-app Hugging Face acquisition: the search page and the repo page, wired to the host's real
 * policy, audit and queue.
 *
 * The feature module owns the pages, the debounce, the probe call and the fit arithmetic; this plugin
 * is the only Android tie. It builds the client with the app's offline switch and the same
 * [app.sirrak.core.models.NetworkPolicy] the downloader enforces, records every Hub call in the
 * on-device audit under the two endpoint ids the privacy screen declares (hf_search, hf_probe), and
 * hands downloads to [DownloadBridge] - never to the downloader directly - so a requested file
 * becomes a persisted, visible row like every curated model.
 *
 * Search state is plugin-held: opening a repo and coming back must not spend the anonymous API budget
 * on the same query again. The last opened repo is cached for the same reason (and so repo ->
 * Downloads -> back does not refetch the file list).
 */
class HfPlugin : Plugin {

    override val id: String = "hf"

    override val requires: List<Class<*>> = listOf(
        Context::class.java,
        AppNavigator::class.java,
        DownloadBridge::class.java,
        AndroidDeviceProbe::class.java,
        ModelFitEngine::class.java,
        NetworkAudit::class.java,
        OfflineModeStore::class.java,
    )

    override fun install(scope: PluginScope) {
        val context = scope.service(Context::class.java)
        val navigator = scope.service(AppNavigator::class.java)
        val bridge = scope.service(DownloadBridge::class.java)
        val deviceProbe = scope.service(AndroidDeviceProbe::class.java)
        val fitEngine = scope.service(ModelFitEngine::class.java)
        val audit = scope.service(NetworkAudit::class.java)
        val offline = scope.service(OfflineModeStore::class.java)

        val tokenStore = HfTokenStore()
        val prompt = HfMeteredPromptHost()
        val access = HfHostAccessPolicy(
            offline = offline,
            network = AndroidNetworkPolicy(context, offline),
            prompt = prompt,
        )

        // The client records the two JSON kinds (search + repo card/file list); the probe records its
        // own typed hops. A PROBE/DOWNLOAD never reaches this hook because the client only gates them.
        val client = HfClient(
            transport = UrlHfTransport(userAgent = USER_AGENT),
            access = access,
            onRequest = { _, kind ->
                audit.record(
                    endpointId = if (kind == HfCallKind.PROBE) ENDPOINT_PROBE else ENDPOINT_SEARCH,
                    host = HOST,
                )
            },
        )
        val probe = GgufProbe(
            transport = UrlGgufProbeTransport(userAgent = USER_AGENT),
            onRequest = { audit.record(endpointId = ENDPOINT_PROBE, host = HOST) },
        )

        scope.provide(HfClient::class.java, client)
        scope.provide(GgufProbe::class.java, probe)
        scope.provide(HfAccessPolicy::class.java, access)
        scope.provide(HfTokenStore::class.java, tokenStore)
        scope.provide(HfMeteredPromptHost::class.java, prompt)

        val searchSession = HfSearchSession(client)
        // One-entry repo cache: repo -> Downloads -> back must not refetch the same file list, and a
        // plain map would keep every visited repo's state for the whole process.
        var cachedRepo: Pair<String, HfRepoSession>? = null
        val downloadsPageAvailable = scope.hasPage(PageIds.DOWNLOADS)

        scope.page(
            PageContribution(PageIds.HF_SEARCH, labelRes = R.string.hf_search_title) {
                HfSearchPage(
                    session = searchSession,
                    onOpenRepo = { repoId -> navigator.push(PageIds.HF_REPO, repoId) },
                    onBack = { navigator.back() },
                    onDisableOffline = { offline.setOfflineOnly(false) },
                )
            },
        )

        scope.page(
            PageContribution(PageIds.HF_REPO, labelRes = R.string.hf_repo_title) { argument ->
                val repoId = argument.orEmpty()
                val session = remember(repoId) {
                    cachedRepo?.takeIf { it.first == repoId }?.second
                        ?: HfRepoSession(
                            repoId = repoId,
                            client = client,
                            probe = probe,
                            fitEngine = fitEngine,
                            policy = access,
                            tokenProvider = { tokenStore.token.value },
                            capacity = { deviceProbe.capacity() },
                            enqueue = { entry -> enqueueThroughBridge(bridge, entry) },
                            start = { id, allowMetered -> bridge.start(id, allowMetered) },
                        ).also { cachedRepo = repoId to it }
                }
                HfRepoPage(
                    session = session,
                    token = tokenStore.token,
                    meteredPending = prompt.pending,
                    onMeteredAnswer = prompt::answer,
                    onSaveToken = tokenStore::set,
                    onDisableOffline = { offline.setOfflineOnly(false) },
                    onBack = { navigator.back() },
                    downloadsPageAvailable = downloadsPageAvailable,
                    onOpenDownloads = { navigator.push(PageIds.DOWNLOADS) },
                )
            },
        )
    }

    /**
     * The one download path: map the bridge's answer onto the page's honest notice. Nothing here
     * starts bytes itself on Wi-Fi - [DownloadBridge.enqueue] pumps the queue, which is the whole
     * point of handing it a synthetic entry instead of driving the downloader.
     */
    private fun enqueueThroughBridge(bridge: DownloadBridge, entry: CatalogueEntry): HfEnqueueOutcome =
        when (bridge.enqueue(entry)) {
            is EnqueueResult.Added -> HfEnqueueOutcome.ADDED
            is EnqueueResult.Duplicate -> HfEnqueueOutcome.DUPLICATE
            is EnqueueResult.AlreadyInstalled -> HfEnqueueOutcome.INSTALLED
            is EnqueueResult.Invalid -> HfEnqueueOutcome.REJECTED
        }

    private companion object {
        const val USER_AGENT = "Sirrak"
        const val HOST = "huggingface.co"

        /** Anonymously capped JSON calls: search pages, repo card and file list. */
        const val ENDPOINT_SEARCH = "hf_search"

        /** The capped GGUF header probe (at most 12 MB, Wi-Fi first, audited per request). */
        const val ENDPOINT_PROBE = "hf_probe"
    }
}
