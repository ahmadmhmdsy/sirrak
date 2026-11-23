package app.sirrak

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sirrak.core.design.ReadingColumn
import app.sirrak.core.design.ScreenHeader
import app.sirrak.core.design.SettingsDivider
import app.sirrak.core.design.SettingsKeyValueRow
import app.sirrak.core.design.SettingsNote
import app.sirrak.core.design.SettingsSection
import app.sirrak.core.rag.RagService
import app.sirrak.core.runtime.ContentRepository
import app.sirrak.core.runtime.Destination
import app.sirrak.core.runtime.NavIcon
import app.sirrak.core.runtime.Plugin
import app.sirrak.core.runtime.PluginScope

/**
 * Library: the local model shelf plus, when M8 is composed, the on-device document shelf. Data comes
 * from assets and the RAG service; the surface is a plain plugin, so removing document grounding is
 * passing nothing here (the M7 build's behaviour, unchanged).
 */
class LibraryPlugin(
    private val context: Context,
    private val rag: RagService? = null,
) : Plugin {
    override val id: String = "library"

    override val requires: List<Class<*>> = buildList {
        add(ContentRepository::class.java)
        if (rag != null) add(RagService::class.java)
    }

    override fun install(scope: PluginScope) {
        val content = scope.service(ContentRepository::class.java)
        scope.destination(
            Destination(
                id = "library",
                labelRes = R.string.nav_library,
                icon = NavIcon.LIBRARY,
                order = 10,
                content = { LibraryScreen(content, rag) },
            ),
        )
    }
}

@Composable
fun LibraryScreen(content: ContentRepository, rag: RagService? = null) {
    val models = remember(content) { content.models() }
    ReadingColumn {
        ScreenHeader(title = stringResource(R.string.library_title))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        ) {
            Text(
                text = stringResource(R.string.library_models_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            models.forEach { model ->
                SettingsSection(title = model.displayName, emphasis = true) {
                    SettingsKeyValueRow(stringResource(R.string.model_params), model.parameterLabel)
                    SettingsDivider()
                    SettingsKeyValueRow(stringResource(R.string.model_quant), model.quant)
                    SettingsDivider()
                    SettingsKeyValueRow(stringResource(R.string.model_disk), model.sizeLabel)
                    SettingsDivider()
                    SettingsKeyValueRow(stringResource(R.string.model_ram), model.ramLabel)
                    SettingsDivider()
                    SettingsKeyValueRow(stringResource(R.string.model_status), model.status)
                }
                Spacer(Modifier.height(16.dp))
            }
            Text(
                text = stringResource(R.string.library_models_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (rag != null) {
                Spacer(Modifier.height(20.dp))
                DocumentsLibrarySection(rag = rag)
            }
        }
    }
}
