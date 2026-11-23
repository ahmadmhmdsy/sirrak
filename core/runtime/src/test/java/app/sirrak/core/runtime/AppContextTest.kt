package app.sirrak.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppContextTest {

    private interface FirstService
    private interface SecondService

    private class FirstProvider : Plugin {
        override val id: String = "first-provider"
        override fun install(scope: PluginScope) {
            scope.provide(FirstService::class.java, object : FirstService {})
        }
    }

    private class SecondProvider : Plugin {
        override val id: String = "second-provider"
        override fun install(scope: PluginScope) {
            scope.provide(SecondService::class.java, object : SecondService {})
        }
    }

    private class Consumer : Plugin {
        override val id: String = "consumer"
        override val requires: List<Class<*>> = listOf(FirstService::class.java, SecondService::class.java)
        override fun install(scope: PluginScope) {
            // Both services must already exist when install runs.
            assertEquals(true, scope.service(FirstService::class.java) != null)
            assertEquals(true, scope.service(SecondService::class.java) != null)
        }
    }

    @Test
    fun installOrdersByDependenciesNotListOrder() {
        val context = AppContext(AppConfig())
        val order = context.install(listOf(Consumer(), SecondProvider(), FirstProvider()))
        assertEquals(listOf("second-provider", "first-provider", "consumer"), order)
    }

    @Test
    fun installFailsLoudlyWhenRequiredServiceIsMissing() {
        val context = AppContext(AppConfig())
        val error = assertThrows(IllegalStateException::class.java) {
            context.install(listOf(Consumer()))
        }
        assertTrue(error.message!!.contains("Cannot satisfy plugin dependencies"))
    }

    @Test
    fun duplicateServiceProviderIsRejected() {
        val context = AppContext(AppConfig())
        val error = assertThrows(IllegalStateException::class.java) {
            context.install(listOf(FirstProvider(), FirstProvider()))
        }
        assertTrue(error.message!!.contains("Duplicate plugin ids"))
    }

    @Test
    fun duplicateDestinationIdIsRejected() {
        val context = AppContext(AppConfig())
        val pluginA = object : Plugin {
            override val id: String = "a"
            override fun install(scope: PluginScope) {
                scope.destination(Destination("same", 0, NavIcon.CHAT, 0) {})
            }
        }
        val pluginB = object : Plugin {
            override val id: String = "b"
            override fun install(scope: PluginScope) {
                scope.destination(Destination("same", 0, NavIcon.CHAT, 1) {})
            }
        }
        assertThrows(IllegalStateException::class.java) { context.install(listOf(pluginA, pluginB)) }
    }

    @Test
    fun undeclaredSlotIsRejected() {
        val context = AppContext(AppConfig())
        val content: SlotContent = {}
        assertThrows(IllegalStateException::class.java) {
            context.slots.provideSingle(SlotKey("not.declared"), "test", content = content)
        }
    }

    @Test
    fun listSlotKeepsRegistrationOrder() {
        val context = AppContext(AppConfig())
        val first: SlotContent = {}
        val second: SlotContent = {}
        context.slots.provideList(Slots.SettingsSections, "b", order = 20, content = second)
        context.slots.provideList(Slots.SettingsSections, "a", order = 10, content = first)
        val contents = context.slots.listContent(Slots.SettingsSections)
        assertEquals(2, contents.size)
        assertSame(first, contents[0])
        assertSame(second, contents[1])
    }

    @Test
    fun effectsDisposeInReverseOrderWhenContextCloses() {
        val context = AppContext(AppConfig())
        val disposed = mutableListOf<String>()
        val plugin = object : Plugin {
            override val id: String = "effects"
            override fun install(scope: PluginScope) {
                scope.effect { disposed += "first" }
                scope.effect { disposed += "second" }
            }
        }
        context.install(listOf(plugin))
        context.close()
        assertEquals(listOf("second", "first"), disposed)
        assertTrue(context.destinations.all().isEmpty())
    }

    @Test
    fun configValidationCatchesUnsafeValues() {
        val problems = AppConfigLoader.validate(
            AppConfig(
                theme = ThemeConfig("midnight"),
                language = LanguageConfig("fr"),
                features = FeaturesConfig(order = listOf("chat", "chat"), enabled = listOf("chat", "nope")),
                ui = UiConfig(maxContentWidthDp = 100),
            ),
        )
        assertTrue(problems.any { it.contains("theme.defaultMode") })
        assertTrue(problems.any { it.contains("language.defaultLanguage") })
        assertTrue(problems.any { it.contains("duplicates") })
        assertTrue(problems.any { it.contains("not in order") })
        assertTrue(problems.any { it.contains("maxContentWidthDp") })
    }
}
