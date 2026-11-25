package app.sirrak

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sirrak.core.design.SettingsDivider
import app.sirrak.core.design.SettingsNote
import app.sirrak.core.design.SettingsPageScaffold
import app.sirrak.core.design.SettingsRow
import app.sirrak.core.design.SettingsSection
import app.sirrak.core.rag.EmbeddingStatus
import app.sirrak.core.rag.RagDocumentStatus
import app.sirrak.core.rag.RagService
import app.sirrak.core.runtime.AppNavigator
import app.sirrak.core.runtime.PageContribution
import app.sirrak.core.runtime.PageIds
import app.sirrak.core.runtime.Plugin
import app.sirrak.core.runtime.PluginScope
import app.sirrak.core.runtime.SettingsDoor
import app.sirrak.feature.chat.DocumentPicker
import kotlinx.coroutines.launch

/**
 * M8 RAG composition: provides nothing new, contributes the embedding-model settings section, and owns
 * the two resources whose lifetime is the app's (the embedding provider's native memory and the
 * repository's reindex collector).
 *
 * The document library surface lives in the Library destination; this plugin only adds the Settings
 * group that makes the feature usable (install/remove the embedding model, see readiness and counts).
 */
class RagPlugin(
    private val rag: RagRepository,
    private val embeddings: FileEmbeddingModelStore,
) : Plugin {
    override val id: String = "rag"

    override val requires: List<Class<*>> = listOf(
        RagService::class.java,
        FileEmbeddingModelStore::class.java,
        AppNavigator::class.java,
    )

    override fun install(scope: PluginScope) {
        val navigator = scope.service(AppNavigator::class.java)
        scope.door(
            SettingsDoor(
                id = "advanced",
                labelRes = R.string.settings_advanced_title,
                order = 30,
                summary = {
                    val installed = embeddings.installedModel()
                    val status by embeddings.status.collectAsStateWithLifecycle()
                    // The door is named "Advanced", so the bare status word ("Not imported") read as if
                    // the advanced section itself were missing; the summary now names the capability it
                    // actually controls. An imported model keeps its own name as the live summary.
                    installed?.displayName ?: if (status is EmbeddingStatus.Missing) {
                        stringResource(R.string.settings_advanced_door_semantic_missing)
                    } else {
                        embeddingStatusLabel(status, busy = false)
                    }
                },
                onClick = { navigator.push(PageIds.SETTINGS_ADVANCED) },
            ),
        )
        scope.page(
            PageContribution(PageIds.SETTINGS_ADVANCED, labelRes = R.string.settings_advanced_title) {
                SettingsPageScaffold(
                    title = stringResource(R.string.settings_advanced_title),
                    onBack = { navigator.back() },
                ) {
                    EmbeddingModelSettingsSection(embeddings = embeddings, rag = rag)
                }
            },
        )
        // Reverse-order disposal: the repository's collector stops first, then the native provider.
        scope.effect {
            runCatching { rag.close() }
            runCatching { embeddings.close() }
        }
    }
}

/**
 * Settings > Embedding model. Import is the only way the Gemma weights reach the device (the licence
 * does not allow bundling them), so the panel is explicit about what it is asking the user to do and
 * reports import failures by stable code instead of a stack trace.
 */
@Composable
fun EmbeddingModelSettingsSection(
    embeddings: FileEmbeddingModelStore,
    rag: RagService,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by embeddings.status.collectAsStateWithLifecycle()
    val ragStatus by rag.status.collectAsStateWithLifecycle()
    var installed by remember { mutableStateOf(embeddings.installedModel()) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val source = DocumentPicker.source(context, uri)
            if (source == null) {
                failure = FileEmbeddingModelStore.REASON_IMPORT
            } else {
                busy = true
                failure = null
                scope.launch {
                    val result = embeddings.import(
                        displayName = source.displayName,
                        declaredSizeBytes = source.sizeBytes,
                        openStream = source.openStream,
                    )
                    installed = embeddings.installedModel()
                    busy = false
                    if (result is EmbeddingModelImport.Rejected) failure = result.reason
                }
            }
        }
    }

    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        SettingsSection(title = stringResource(R.string.embedding_section_title)) {
            SettingsRow(
                label = stringResource(R.string.embedding_status),
                value = embeddingStatusLabel(status, busy),
            )
            if (installed != null) {
                SettingsDivider()
                SettingsRow(
                    label = stringResource(R.string.embedding_installed),
                    value = installed?.displayName,
                )
                SettingsDivider()
                SettingsRow(
                    label = stringResource(R.string.embedding_size),
                    value = formatBytes(installed?.sizeBytes ?: 0L),
                )
            }
            SettingsDivider()
            SettingsRow(
                label = if (installed == null) {
                    stringResource(R.string.embedding_import)
                } else {
                    stringResource(R.string.embedding_replace)
                },
                // A .litertlm file has no registered MIME type, so the embedding picker shows all
                // files; the store rejects anything that is not a loadable model.
                onClick = { picker.launch(arrayOf("*/*")) },
            )
            if (installed != null) {
                SettingsDivider()
                SettingsRow(
                    label = stringResource(R.string.embedding_remove),
                    onClick = {
                        busy = true
                        scope.launch {
                            embeddings.remove()
                            installed = embeddings.installedModel()
                            busy = false
                        }
                    },
                )
            }
            SettingsDivider()
            SettingsRow(
                label = stringResource(R.string.embedding_documents),
                value = pluralStringResource(
                    R.plurals.embedding_documents_count,
                    ragStatus.readyCount,
                    ragStatus.readyCount,
                    ragStatus.pendingCount,
                ),
            )
            if (ragStatus.pendingCount > 0) {
                SettingsRow(
                    label = stringResource(R.string.embedding_reindex_pending),
                    onClick = {
                        busy = true
                        scope.launch {
                            rag.documents.value
                                .filter { it.status == RagDocumentStatus.EXTRACTED }
                                .forEach { rag.reindex(it.id) }
                            busy = false
                        }
                    },
                )
            }
            SettingsNote(stringResource(R.string.embedding_import_note))
            if (busy) {
                SettingsNote(stringResource(R.string.embedding_working))
            }
            failure?.let { reason ->
                SettingsNote(stringResource(embeddingErrorLabel(reason)))
            }
        }
    }
}

@Composable
private fun embeddingStatusLabel(status: EmbeddingStatus, busy: Boolean): String = when {
    busy -> stringResource(R.string.embedding_status_working)
    status is EmbeddingStatus.Ready -> stringResource(R.string.embedding_status_ready)
    status is EmbeddingStatus.Loading -> stringResource(R.string.embedding_status_loading)
    status is EmbeddingStatus.Failed -> stringResource(R.string.embedding_status_failed)
    else -> stringResource(R.string.embedding_status_missing)
}

/** Stable import-rejection code -> localised sentence; one place for both settings and library. */
fun embeddingErrorLabel(reason: String): Int = when (reason) {
    FileEmbeddingModelStore.REASON_TOO_LARGE -> R.string.embedding_error_too_large
    FileEmbeddingModelStore.REASON_EMPTY -> R.string.embedding_error_empty
    FileEmbeddingModelStore.REASON_LOAD_FAILED -> R.string.embedding_error_load
    FileEmbeddingModelStore.REASON_STORAGE -> R.string.embedding_error_storage
    else -> R.string.embedding_error_generic
}

/** Binary-size formatting for file rows; intentionally blunt (no per-locale unit fuss). */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1_000_000.0)
    bytes >= 1000L -> "%.0f kB".format(bytes / 1000.0)
    else -> bytes.toString() + " B"
}
