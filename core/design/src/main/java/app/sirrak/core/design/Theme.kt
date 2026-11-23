package app.sirrak.core.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.sirrak.core.runtime.ThemeMode

/**
 * Material 3 carries structure, navigation, type scale and interaction; a [ThemePack] supplies every
 * colour role, Sirrak supplies the two type families and the decorative tokens.
 *
 * [packId] is a persisted string, not an enum: packs are data, so adding/removing one never changes this
 * signature and a stale/unknown id resolves to the default pack instead of crashing or flashing an
 * unstyled app. No dynamic colour anywhere: brand fidelity wins.
 */
@Composable
fun SirrakTheme(
    mode: ThemeMode,
    packId: String = ThemePacks.DEFAULT_ID,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val pack = remember(packId) { ThemePacks.byId(packId) }
    val tokens = remember(pack, dark) { sirrakTokens(dark = dark, pack = pack) }
    CompositionLocalProvider(LocalSirrakTokens provides tokens) {
        MaterialTheme(
            colorScheme = remember(pack, dark) { pack.colorScheme(dark) },
            typography = SirrakTypography,
            shapes = SirrakShapes,
        ) {
            Surface(modifier = modifier, color = MaterialTheme.colorScheme.background, content = content)
        }
    }
}

/** Monogram tint used on the launcher-style mark. */
val GirihInk: Color @Composable get() = MaterialTheme.colorScheme.primary
