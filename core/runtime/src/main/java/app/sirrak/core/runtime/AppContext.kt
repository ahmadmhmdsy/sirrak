package app.sirrak.core.runtime

/** A hand-rolled, Cordis-shaped context: services, plugins, slots, destinations, disposable effects. */
class AppContext(val config: AppConfig) {

    private data class ServiceEntry(val instance: Any, val owner: String)

    private val services = LinkedHashMap<Class<*>, ServiceEntry>()
    private val effects = ArrayDeque<Pair<String, () -> Unit>>()
    private val installed = LinkedHashSet<String>()
    private var closed = false

    val slots = SlotRegistry()
    val destinations = DestinationRegistry()
    val pages = PageRegistry()
    val settingsDoors = SettingsDoorRegistry()

    fun provideService(type: Class<*>, instance: Any, owner: String = "host") {
        check(!closed) { "context is closed" }
        val existing = services[type]
        check(existing == null) {
            "Service ${type.name} already provided by ${existing?.owner} — refusing $owner"
        }
        services[type] = ServiceEntry(instance, owner)
    }

    fun <T : Any> service(type: Class<T>): T {
        val entry = services[type]
            ?: error("No provider for service ${type.name} — plugin ordering or composition is wrong")
        @Suppress("UNCHECKED_CAST")
        return entry.instance as T
    }

    fun hasService(type: Class<*>): Boolean = services.containsKey(type)

    fun registerEffect(pluginId: String, onDispose: () -> Unit) {
        effects.addLast(pluginId to onDispose)
    }

    /**
     * Installs the given plugins in dependency order: a plugin starts only when every service it
     * declares in [Plugin.requires] is already provided. Missing providers and cycles fail loudly
     * here instead of producing a half-wired app. Returns the installation order (for logging/tests).
     */
    fun install(plugins: List<Plugin>): List<String> {
        check(!closed) { "context is closed" }
        val pending = plugins.toMutableList()
        val order = mutableListOf<String>()
        val duplicated = pending.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        check(duplicated.isEmpty()) { "Duplicate plugin ids: $duplicated" }

        while (pending.isNotEmpty()) {
            var progressed = false
            val iterator = pending.iterator()
            while (iterator.hasNext()) {
                val plugin = iterator.next()
                val missing = plugin.requires.filterNot { hasService(it) }
                if (missing.isEmpty()) {
                    plugin.install(PluginScope(this, plugin.id))
                    installed += plugin.id
                    order += plugin.id
                    iterator.remove()
                    progressed = true
                }
            }
            if (!progressed) {
                val details = pending.joinToString { p ->
                    "${p.id} needs ${p.requires.joinToString { it.simpleName }}"
                }
                error("Cannot satisfy plugin dependencies (missing service or cycle): $details")
            }
        }
        return order
    }

    fun close() {
        if (closed) return
        closed = true
        while (effects.isNotEmpty()) {
            runCatching { effects.removeLast().second.invoke() }
        }
        slots.clear()
        destinations.clear()
        pages.clear()
        settingsDoors.clear()
        services.clear()
        installed.clear()
    }
}
