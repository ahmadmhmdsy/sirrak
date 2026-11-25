package app.sirrak

import app.sirrak.core.models.DownloadPhase
import app.sirrak.core.models.DownloadProgress
import app.sirrak.core.models.ModelCatalogueProvider
import app.sirrak.core.models.ModelDownloader
import app.sirrak.core.runtime.InstalledModel
import app.sirrak.core.runtime.ModelStore
import app.sirrak.downloads.DownloadBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The bridge between "a verified file sits in the staging directory" and "the store shows its model".
 * Both chat and Library need this to happen exactly once and regardless of which screen is open, so it
 * is a host service observing the downloader rather than UI-local code (defect G4: adoption lived in
 * one composable, so a completed download could vanish when that screen was left).
 */
class ModelAdoptionCoordinator(
    private val downloader: ModelDownloader,
    private val store: ModelStore,
    private val catalogue: ModelCatalogueProvider,
    private val bridge: DownloadBridge,
    scope: CoroutineScope,
) {
    /** What the UI needs to say after an adoption attempt; null means "nothing to report". */
    sealed interface Event {
        data class Installed(val model: InstalledModel) : Event
        data class Failed(val code: String, val displayName: String) : Event
    }

    private val _events = MutableStateFlow<Event?>(null)
    val events: StateFlow<Event?> = _events.asStateFlow()

    fun dismiss() {
        _events.value = null
    }

    init {
        scope.launch {
            downloader.progress.collect { progress ->
                if (progress != null) adopt(progress)
            }
        }
    }

    private suspend fun adopt(progress: DownloadProgress) {
        if (progress.phase != DownloadPhase.INSTALLED) return
        val file = progress.file ?: return
        val entry = catalogue.catalogue().models.firstOrNull { it.id == progress.entryId }
        val displayName = entry?.displayName ?: file.nameWithoutExtension
        val result = store.importFile(displayName, file, entry?.sha256)
        result
            .onSuccess { model -> _events.value = Event.Installed(model) }
            .onFailure { _events.value = Event.Failed(code = "import_failed", displayName = displayName) }
        if (result.isFailure) file.delete()
        // The verified file is either owned by the store now or was rejected; either way the staging
        // snapshot must not keep advertising it as an installed download.
        downloader.clear()
        // The durable queue row is only FINISHED when the import actually happened; clearing before
        // markInstalled matters because that call pumps the next queued transfer.
        if (result.isSuccess) {
            bridge.markInstalled(progress.entryId)
        } else {
            bridge.markFailed(progress.entryId, "import_failed")
        }
    }
}
