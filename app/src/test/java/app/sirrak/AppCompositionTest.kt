package app.sirrak

import app.sirrak.core.runtime.AppConfig
import app.sirrak.core.runtime.FeaturesConfig
import app.sirrak.core.runtime.Plugin
import app.sirrak.core.runtime.PluginScope
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The add/remove acceptance test from the frontend council (doc 12): a feature exists only if it is a
 * manifest line plus a module. This drives the same resolver the app uses, so "one manifest line" is
 * pinned by tests and a stale config entry can never crash the app.
 */
class AppCompositionTest {

    private class FakePlugin(override val id: String) : Plugin {
        override fun install(scope: PluginScope) = Unit
    }

    private val manifest = listOf(
        FakePlugin("library"),
        FakePlugin("engine"),
        FakePlugin("about"),
        FakePlugin("chat"),
        FakePlugin("settings"),
    )

    /** Mirrors assets/config/app.json: explicit order, empty enabled (= all manifest plugins). */
    private val shippedConfig = AppConfig(
        features = FeaturesConfig(
            order = listOf("library", "engine", "about", "chat", "settings"),
            enabled = emptyList(),
        ),
    )

    @Test
    fun `shipped config keeps all five features in declared order`() {
        val resolved = AppComposition.resolveManifest(manifest, shippedConfig)
        assertEquals(listOf("library", "engine", "about", "chat", "settings"), resolved.map { it.id })
    }

    @Test
    fun `removing a manifest line removes the feature with an untouched config`() {
        val withoutAbout = manifest.filterNot { it.id == "about" }
        val resolved = AppComposition.resolveManifest(withoutAbout, shippedConfig)
        assertEquals(listOf("library", "engine", "chat", "settings"), resolved.map { it.id })
    }

    @Test
    fun `stale enabled entry is ignored instead of crashing`() {
        val withoutAbout = manifest.filterNot { it.id == "about" }
        val config = shippedConfig.copy(
            features = shippedConfig.features.copy(
                enabled = listOf("library", "about", "chat"),
            ),
        )
        val resolved = AppComposition.resolveManifest(withoutAbout, config)
        assertEquals(listOf("library", "chat"), resolved.map { it.id })
    }

    @Test
    fun `config can disable a feature without removing its module`() {
        val resolved = AppComposition.resolveManifest(manifest, shippedConfig.copy(
            features = shippedConfig.features.copy(
                enabled = listOf("chat", "settings"),
            ),
        ))
        assertEquals(listOf("chat", "settings"), resolved.map { it.id })
    }

    @Test
    fun `order from config wins over manifest order`() {
        val resolved = AppComposition.resolveManifest(manifest, shippedConfig.copy(
            features = shippedConfig.features.copy(
                order = listOf("chat", "settings", "library", "engine", "about"),
            ),
        ))
        assertEquals(listOf("chat", "settings", "library", "engine", "about"), resolved.map { it.id })
    }
}
