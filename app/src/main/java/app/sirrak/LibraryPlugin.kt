package app.sirrak

import android.content.Context
import app.sirrak.core.models.AndroidDeviceProbe
import app.sirrak.core.models.ModelCatalogueProvider
import app.sirrak.core.models.ModelFitEngine
import app.sirrak.core.rag.RagService
import app.sirrak.core.speech.SpeechModelStore
import app.sirrak.core.speech.TranscriptionEngine
import app.sirrak.core.runtime.AppNavigator
import app.sirrak.core.runtime.Destination
import app.sirrak.core.runtime.InferenceEngine
import app.sirrak.core.runtime.ModelStore
import app.sirrak.core.runtime.NavIcon
import app.sirrak.core.runtime.PageIds
import app.sirrak.core.runtime.Plugin
import app.sirrak.core.runtime.PluginScope
import app.sirrak.downloads.DownloadBridge
import app.sirrak.media.FileMediaLibrary
import app.sirrak.media.FileMediaStore
import app.sirrak.media.RagMediaIndex

/**
 * Library: the device's model shelf, its download desk and its media shelf — one destination with
 * three segments. Everything shown is real device state (store, downloader, catalogue, fit engine);
 * the fake reference cards and the "manage models in Settings" detour are gone (the review's central
 * complaint). Model management lives here; Settings keeps only app-level preferences.
 */
class LibraryPlugin(
    private val context: Context,
    private val rag: RagService? = null,
) : Plugin {
    override val id: String = "library"

    override val requires: List<Class<*>> = buildList {
        add(ModelStore::class.java)
        add(ModelCatalogueProvider::class.java)
        add(DownloadBridge::class.java)
        add(ModelFitEngine::class.java)
        add(AndroidDeviceProbe::class.java)
        add(ModelAdoptionCoordinator::class.java)
        add(InferenceEngine::class.java)
        add(AppNavigator::class.java)
        if (rag != null) add(RagService::class.java)
    }

    override fun install(scope: PluginScope) {
        val store = scope.service(ModelStore::class.java)
        val engine = scope.service(InferenceEngine::class.java)
        val catalogue = scope.service(ModelCatalogueProvider::class.java)
        val bridge = scope.service(DownloadBridge::class.java)
        val adoption = scope.service(ModelAdoptionCoordinator::class.java)
        val fitEngine = scope.service(ModelFitEngine::class.java)
        val probe = scope.service(AndroidDeviceProbe::class.java)
        val navigator = scope.service(AppNavigator::class.java)
        // P5: the media shelf is built here because AppComposition predates it and must not learn
        // about media. The store is app-private files; the RAG library is the index seam when one
        // exists; the Whisper engine is resolved lazily, because AsrPlugin may install after this
        // plugin and a missing engine has to degrade to a typed reason, never a composition failure.
        val media = FileMediaLibrary(
            store = FileMediaStore(context.filesDir),
            index = rag?.let { RagMediaIndex(it) },
            transcriber = AndroidAudioTranscriber(
                engine = { runCatching { scope.service(TranscriptionEngine::class.java) }.getOrNull() },
                models = { runCatching { scope.service(SpeechModelStore::class.java) }.getOrNull() },
            ),
        )
        // Construction already purges ONCE items; repeating it when the graph closes keeps a
        // temporary item temporary even when the process ends without a polite activity stop.
        scope.effect { media.purgeOnce() }
        scope.destination(
            Destination(
                id = "library",
                labelRes = R.string.nav_library,
                icon = NavIcon.LIBRARY,
                order = 10,
                content = {
                    LibraryScreen(
                        context = context,
                        store = store,
                        engine = engine,
                        catalogue = catalogue,
                        bridge = bridge,
                        adoption = adoption,
                        fitEngine = fitEngine,
                        probe = probe,
                        rag = rag,
                        navigator = navigator,
                        // An optional page contributed by another plugin; without it the row is absent,
                        // so a composition that drops HF search never advertises a dead doorway.
                        hasHuggingFaceSearch = scope.hasPage(PageIds.HF_SEARCH),
                        media = media,
                    )
                },
            ),
        )
    }
}
