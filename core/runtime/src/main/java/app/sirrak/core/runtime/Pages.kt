package app.sirrak.core.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * A page pushed on top of a bottom destination. Pages are identified by a stable, never-localised id
 * declared in [PageIds]; [argument] carries the one detail a page needs (e.g. a model file name).
 * The content for an id is contributed by a plugin through [PageRegistry], so the shell renders pages
 * without naming them and features never import each other.
 */
data class PageRoute(
    val id: String,
    val argument: String? = null,
) {
    init {
        require(id.isNotBlank()) { "page id must not be blank" }
    }
}

/** Stable page ids shared by the host and the feature that owns the page. */
object PageIds {
    const val MODELS = "library.models"
    const val DOWNLOADS = "library.downloads"
    const val MEDIA = "library.media"
    const val MODEL_DETAIL = "library.model-detail"
    const val HF_SEARCH = "library.hf-search"
    const val HF_REPO = "library.hf-repo"
    const val SETTINGS_APPEARANCE = "settings.appearance"
    const val SETTINGS_PRIVACY = "settings.privacy"
    const val SETTINGS_VOICE = "settings.voice"
    const val SETTINGS_ADVANCED = "settings.advanced"
    const val SETTINGS_ABOUT = "settings.about"
}

/** A full-screen page (with a back affordance) contributed by a plugin. */
data class PageContribution(
    val id: String,
    val labelRes: Int,
    val content: @Composable (argument: String?) -> Unit,
) {
    init {
        require(id.isNotBlank()) { "page id must not be blank" }
    }
}

/** Pages by id; the host composes the first page its navigator points at. */
class PageRegistry {
    private val pages = LinkedHashMap<String, PageContribution>()
    private val owners = HashMap<String, String>()

    fun add(pluginId: String, page: PageContribution) {
        check(!pages.containsKey(page.id)) {
            "Page '${page.id}' already contributed by ${owners[page.id]}"
        }
        pages[page.id] = page
        owners[page.id] = pluginId
    }

    fun find(id: String): PageContribution? = pages[id]

    fun all(): List<PageContribution> = pages.values.toList()

    fun clear() {
        pages.clear()
        owners.clear()
    }
}
