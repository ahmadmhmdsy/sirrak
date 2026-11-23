package app.sirrak

import android.content.SharedPreferences
import app.sirrak.core.design.FontScales
import app.sirrak.core.runtime.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * M6 THM-6 persistence: the chosen font-scale preset must survive a restart, unknown/removed ids must
 * open at the 100% default, and the setter must resolve ids before writing so a corrupt caller cannot
 * poison the store. Mirrors the theme-mode/pack tests' expectations on every other settings row.
 */
class SharedPrefsSettingsServiceTest {

    private val prefs = InMemoryPrefs()

    private fun service() = SharedPrefsSettingsService(prefs, AppConfig())

    @Test
    fun `missing preference loads the 100 percent default`() {
        val loaded = service()

        assertEquals(FontScales.DEFAULT_ID, loaded.fontScaleId.value)
        assertEquals(100, FontScales.byId(loaded.fontScaleId.value).percent)
    }

    @Test
    fun `setting a preset updates the flow immediately and stores its id`() {
        val loaded = service()

        loaded.setFontScale("large")

        assertEquals("large", loaded.fontScaleId.value)
        assertEquals("large", prefs.getString(SharedPrefsSettingsService.KEY_FONT_SCALE, null))
    }

    @Test
    fun `chosen preset round trips through a new service instance`() {
        service().setFontScale("largest")

        assertEquals("largest", service().fontScaleId.value)
    }

    @Test
    fun `unknown stored id opens at the 100 percent default`() {
        prefs.edit().putString(SharedPrefsSettingsService.KEY_FONT_SCALE, "gigantic").commit()

        assertEquals(FontScales.DEFAULT_ID, service().fontScaleId.value)
    }

    @Test
    fun `blank stored id opens at the 100 percent default`() {
        prefs.edit().putString(SharedPrefsSettingsService.KEY_FONT_SCALE, "   ").commit()

        assertEquals(FontScales.DEFAULT_ID, service().fontScaleId.value)
    }

    @Test
    fun `unknown id passed to the setter resolves before persisting`() {
        val loaded = service()

        loaded.setFontScale("gigantic")

        assertEquals(FontScales.DEFAULT_ID, loaded.fontScaleId.value)
        assertEquals(
            FontScales.DEFAULT_ID,
            prefs.getString(SharedPrefsSettingsService.KEY_FONT_SCALE, null),
        )
    }

    @Test
    fun `out of range numeric preference clamps to the largest preset`() {
        prefs.edit().putString(SharedPrefsSettingsService.KEY_FONT_SCALE, "999").commit()

        assertEquals("largest", service().fontScaleId.value)
    }

    @Test
    fun `numeric preference snaps to the nearest preset`() {
        prefs.edit().putString(SharedPrefsSettingsService.KEY_FONT_SCALE, "108").commit()

        assertEquals("large", service().fontScaleId.value)
    }

    /**
     * Minimal in-memory [SharedPreferences] so the real service code (including its edit/commit path)
     * runs on the JVM. Only the string surface the settings service uses is exercised; every other type
     * keeps Map semantics so a future setting can use it without rewriting the fake.
     */
    private class InMemoryPrefs : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()

        override fun getString(key: String?, defValue: String?): String? =
            values[key] as? String ?: defValue

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST")
            (values[key] as? MutableSet<String>)?.toMutableSet() ?: defValues

        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue

        override fun contains(key: String?): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var clear = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor =
                apply { pending[key.orEmpty()] = value }

            override fun putStringSet(
                key: String?,
                values: MutableSet<String>?,
            ): SharedPreferences.Editor = apply { pending[key.orEmpty()] = values?.toMutableSet() }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor =
                apply { pending[key.orEmpty()] = value }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor =
                apply { pending[key.orEmpty()] = value }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor =
                apply { pending[key.orEmpty()] = value }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor =
                apply { pending[key.orEmpty()] = value }

            override fun remove(key: String?): SharedPreferences.Editor =
                apply { pending[key.orEmpty()] = null }

            override fun clear(): SharedPreferences.Editor = apply { clear = true }

            override fun commit(): Boolean {
                if (clear) values.clear()
                for ((key, value) in pending) {
                    if (value == null) values.remove(key) else values[key] = value
                }
                pending.clear()
                clear = false
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }
}
