package app.sirrak.core.runtime

/**
 * The Cordis-shaped plugin contract, statically applied. A plugin declares the services it needs,
 * contributes destinations and slot content, and registers every reversible effect through
 * [PluginScope.effect] so [AppContext.close] tears the whole graph down in reverse order.
 */
interface Plugin {
    val id: String

    /** Service interfaces this plugin consumes; the container orders installation by these. */
    val requires: List<Class<*>> get() = emptyList()

    fun install(scope: PluginScope)
}

class PluginScope internal constructor(
    private val container: AppContext,
    val pluginId: String,
) {
    fun <T : Any> service(type: Class<T>): T = container.service(type)

    fun <T : Any> provide(type: Class<T>, instance: T) {
        container.provideService(type, instance, pluginId)
    }

    fun effect(onDispose: () -> Unit) {
        container.registerEffect(pluginId, onDispose)
    }

    fun destination(destination: Destination) {
        container.destinations.add(pluginId, destination)
    }

    /** Contributes a full-screen page reachable through [AppNavigator.push]. */
    fun page(page: PageContribution) {
        container.pages.add(pluginId, page)
    }

    /** Whether some composed plugin contributed [id]; used for optional doorway rows. */
    fun hasPage(id: String): Boolean = container.pages.find(id) != null

    /** Contributes one row to the Settings root (see [SettingsDoor]). */
    fun door(door: SettingsDoor) {
        container.settingsDoors.add(pluginId, door)
    }

    fun slotSingle(key: SlotKey, content: SlotContent) {
        container.slots.provideSingle(key, pluginId, content)
    }

    fun slotList(key: SlotKey, order: Int = 0, content: SlotContent) {
        container.slots.provideList(key, pluginId, order, content)
    }
}
