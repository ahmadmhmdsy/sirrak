package app.sirrak.core.design

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.sirrak.core.runtime.ThemeMode

/**
 * Non-Material design tokens. Colour comes from the active [ThemePack] (see ThemePacks.kt); this file
 * only carries the tokens that are *shared by every pack*: decoration wiring, motion and shape. Nothing
 * here may contain a hex value — packs own colour, and only packs.
 */
data class SirrakDecorations(
    val girihLine: Color,
    val brass: Color,
    val brassText: Color,
    val hairline: Color,
)

data class SirrakMotion(
    val quick: Int = 140,
    val standard: Int = 240,
    val slow: Int = 360,
    val ease: Easing = FastOutSlowInEasing,
)

data class SirrakTokens(
    val dark: Boolean,
    val packId: String,
    val decorations: SirrakDecorations,
    val motion: SirrakMotion,
)

/** Default composition-local value: the brand default pack's light decorations. */
val LocalSirrakTokens = staticCompositionLocalOf {
    SirrakTokens(
        dark = false,
        packId = ThemePacks.DEFAULT_ID,
        decorations = ThemePacks.default.decorations(dark = false),
        motion = SirrakMotion(),
    )
}

val SirrakShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Tokens for a pack/mode; the default pack keeps the M1–M3 values. */
fun sirrakTokens(dark: Boolean, pack: ThemePack = ThemePacks.default): SirrakTokens = SirrakTokens(
    dark = dark,
    packId = pack.id,
    decorations = pack.decorations(dark),
    motion = SirrakMotion(),
)

/** Compatibility names for the default pack's M3 schemes; only the default pack uses them directly. */
val LightColorScheme get() = ThemePacks.default.colorScheme(dark = false)
val DarkColorScheme get() = ThemePacks.default.colorScheme(dark = true)

val DefaultThemeMode = ThemeMode.SYSTEM
