package app.sirrak.core.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class NavIcon { CHAT, LIBRARY, SETTINGS }

/** A top-level destination contributed by a feature plugin. The host never names a route itself. */
data class Destination(
    val id: String,
    val labelRes: Int,
    val icon: NavIcon,
    val order: Int,
    val content: @Composable () -> Unit,
)

class DestinationRegistry {
    private val destinations = LinkedHashMap<String, Destination>()
    private val owners = HashMap<String, String>()

    fun add(pluginId: String, destination: Destination) {
        require(destination.id.isNotBlank()) { "destination id must not be blank" }
        check(!destinations.containsKey(destination.id)) {
            "Destination '${destination.id}' already contributed by ${owners[destination.id]}"
        }
        destinations[destination.id] = destination
        owners[destination.id] = pluginId
    }

    fun all(): List<Destination> = destinations.values.sortedBy { it.order }

    fun clear() {
        destinations.clear()
        owners.clear()
    }
}

/**
 * Navigation state: one selected bottom destination plus a stack of pages pushed on top of it.
 * Android back pops the page stack first; only an empty stack lets the shell fall through to the
 * system default. [select] is a bottom-tab change and always returns to the destination root — a tab
 * switch must never leave a stale page open underneath.
 */
class AppNavigator(initial: String?) {
    var currentId by mutableStateOf(initial)
        private set

    var pages by mutableStateOf<List<PageRoute>>(emptyList())
        private set

    val currentPage: PageRoute? get() = pages.lastOrNull()

    val canGoBack: Boolean get() = pages.isNotEmpty()

    fun select(id: String) {
        currentId = id
        pages = emptyList()
    }

    fun push(id: String, argument: String? = null) {
        push(PageRoute(id, argument))
    }

    fun push(route: PageRoute) {
        pages = pages + route
    }

    /** Pops one page. Returns false when there was nothing to pop, so a back handler can fall through. */
    fun back(): Boolean {
        if (pages.isEmpty()) return false
        pages = pages.dropLast(1)
        return true
    }

    fun closePages() {
        pages = emptyList()
    }
}
