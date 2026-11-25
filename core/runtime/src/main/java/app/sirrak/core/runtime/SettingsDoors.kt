package app.sirrak.core.runtime

import androidx.compose.runtime.Composable

/**
 * One row of the Settings root (P4). The root screen owns the card, the dividers and the order; a
 * plugin owns what its door says and where it goes. This is how six doors stay honest without
 * `feature:settings` importing any feature: the summary is read from the contributor's own state
 * every time the root composes (which is again after every page pop, because navigation changes).
 *
 * `labelRes` is a string resource owned by the contributor's module, so the root never hardcodes a
 * section title. A door with [expandedContent] expands in place (language is the only one) instead of
 * pushing a page; the root toggles that and keeps only one door open.
 */
class SettingsDoor(
    val id: String,
    /** AR/EN row label owned by the contributing module. */
    val labelRes: Int,
    /** Ascending display order; the root sorts, so registration order is not a contract. */
    val order: Int,
    /** Live summary composable: read the contributor's state here, never a cached string. */
    val summary: @Composable () -> String?,
    /** Called when the row is tapped. Ignored for a door with [expandedContent]. */
    val onClick: () -> Unit = {},
    /** Inline expansion rendered inside the same card, directly under the row. */
    val expandedContent: (@Composable () -> Unit)? = null,
) {
    init {
        require(id.isNotBlank()) { "settings door id must not be blank" }
        require(labelRes != 0) { "settings door '$id' needs a label resource" }
        require(order >= 0) { "settings door '$id' order must not be negative" }
    }
}

/**
 * Doors contributed by plugins during install. Kept outside [SlotRegistry] on purpose: a door is not
 * a free-form composable but a row with a label, a live summary and a destination, and the root must
 * be able to render dividers and a single expanded door across all of them.
 */
class SettingsDoorRegistry {
    private val doors = LinkedHashMap<String, SettingsDoor>()

    fun add(pluginId: String, door: SettingsDoor) {
        check(!doors.containsKey(door.id)) {
            "Settings door '${door.id}' already contributed by ${doors[door.id]?.let { pluginId }}"
        }
        doors[door.id] = door
    }

    fun all(): List<SettingsDoor> = doors.values.sortedBy { it.order }

    fun clear() = doors.clear()
}
