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

/** Holds the currently selected top-level destination id (survives configuration changes). */
class AppNavigator(initial: String?) {
    var currentId by mutableStateOf(initial)
        private set

    fun select(id: String) {
        currentId = id
    }
}
