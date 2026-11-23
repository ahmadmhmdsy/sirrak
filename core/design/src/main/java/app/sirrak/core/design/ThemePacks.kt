package app.sirrak.core.design

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Theme packs are *data, never code at the call site*. A pack is a pair of complete Material 3 role
 * palettes (light + dark) plus Sirrak's decorative tokens; geometry, typography, spacing and motion are
 * deliberately absent here so a pack can never change how the app is laid out or how Arabic renders.
 *
 * Values are ARGB longs (not Compose [Color]) so the registry, contrast gate and any future
 * import/export remain pure Kotlin and testable on the JVM without a device.
 *
 * Adding a pack = one [ThemePack] value in [ThemePacks.all] + four string resources. Removing one = delete
 * that value (saved ids fall back to [ThemePacks.DEFAULT_ID] automatically). No screen knows any pack by
 * name.
 */
data class ThemePack(
    /** Persisted id; stable, never localised, never reused for a different pack. */
    val id: String,
    /** AR/EN display name resource (":core:design" owns its own resources). */
    val nameRes: Int,
    /** AR/EN one-line description resource. */
    val descriptionRes: Int,
    val light: ThemePalette,
    val dark: ThemePalette,
)

/** Every colour role a pack must define. One field per role the app actually uses. */
data class ThemePalette(
    val background: Long,
    val onBackground: Long,
    val surface: Long,
    val onSurface: Long,
    val surfaceVariant: Long,
    val onSurfaceVariant: Long,
    val surfaceContainerLowest: Long,
    val surfaceContainerLow: Long,
    val surfaceContainer: Long,
    val surfaceContainerHigh: Long,
    val surfaceContainerHighest: Long,
    val primary: Long,
    val onPrimary: Long,
    val primaryContainer: Long,
    val onPrimaryContainer: Long,
    val inversePrimary: Long,
    val secondary: Long,
    val onSecondary: Long,
    val secondaryContainer: Long,
    val onSecondaryContainer: Long,
    val tertiary: Long,
    val onTertiary: Long,
    val tertiaryContainer: Long,
    val onTertiaryContainer: Long,
    val outline: Long,
    val outlineVariant: Long,
    val error: Long,
    val onError: Long,
    val errorContainer: Long,
    val onErrorContainer: Long,
    val inverseSurface: Long,
    val inverseOnSurface: Long,
    /** Decorative girih line colour (never carries text). */
    val girihLine: Long,
    /** Decorative brass accent (never carries text). */
    val brass: Long,
    /** Brass as *text tint*; unlike [brass] this one must pass contrast on [surface]. */
    val brassText: Long,
    /** Quiet 1 dp divider colour; decorative by design. */
    val hairline: Long,
)

/** WCAG tier a pair is gated at: body text needs 4.5:1, meaningful UI 3:1. Decorative tokens are not gated. */
enum class ContrastTier { BODY, UI }

data class ContrastPair(
    val label: String,
    val foreground: Long,
    val background: Long,
    val tier: ContrastTier,
)

/** WCAG 2.x relative luminance / contrast maths for ARGB longs (sRGB). */
object Contrast {
    fun relativeLuminance(argb: Long): Double {
        val r = channel(argb, 16)
        val g = channel(argb, 8)
        val b = channel(argb, 0)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    fun ratio(foreground: Long, background: Long): Double {
        val a = relativeLuminance(foreground)
        val b = relativeLuminance(background)
        val lighter = maxOf(a, b)
        val darker = minOf(a, b)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun channel(argb: Long, shift: Int): Double {
        val value = ((argb shr shift) and 0xFF).toDouble() / 255.0
        return if (value <= 0.03928) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
    }
}

/**
 * The pairs the app actually paints and therefore promises: body text, containers, actions, outlines.
 * This list is production code so the M4 test (and any future debug assertion) iterates it, and new
 * packs/roles are covered automatically. Decorative tokens (girihLine, brass, hairline, swatches) are
 * intentionally absent: they never carry information on their own.
 */
fun ThemePack.contrastPairs(dark: Boolean): List<ContrastPair> {
    val p = if (dark) this.dark else light
    return listOf(
        ContrastPair("onBackground/background", p.onBackground, p.background, ContrastTier.BODY),
        ContrastPair("onSurface/surface", p.onSurface, p.surface, ContrastTier.BODY),
        ContrastPair("onSurfaceVariant/surface", p.onSurfaceVariant, p.surface, ContrastTier.BODY),
        ContrastPair("onSurfaceVariant/surfaceVariant", p.onSurfaceVariant, p.surfaceVariant, ContrastTier.BODY),
        ContrastPair("onSurfaceVariant/surfaceContainerLow", p.onSurfaceVariant, p.surfaceContainerLow, ContrastTier.BODY),
        ContrastPair("onSurface/surfaceContainerLow", p.onSurface, p.surfaceContainerLow, ContrastTier.BODY),
        ContrastPair("onSurfaceVariant/surfaceContainer", p.onSurfaceVariant, p.surfaceContainer, ContrastTier.BODY),
        ContrastPair("onPrimary/primary", p.onPrimary, p.primary, ContrastTier.BODY),
        ContrastPair("onPrimaryContainer/primaryContainer", p.onPrimaryContainer, p.primaryContainer, ContrastTier.BODY),
        ContrastPair("onSecondary/secondary", p.onSecondary, p.secondary, ContrastTier.BODY),
        ContrastPair("onSecondaryContainer/secondaryContainer", p.onSecondaryContainer, p.secondaryContainer, ContrastTier.BODY),
        ContrastPair("onTertiary/tertiary", p.onTertiary, p.tertiary, ContrastTier.BODY),
        ContrastPair("onTertiaryContainer/tertiaryContainer", p.onTertiaryContainer, p.tertiaryContainer, ContrastTier.BODY),
        ContrastPair("onError/error", p.onError, p.error, ContrastTier.BODY),
        ContrastPair("onErrorContainer/errorContainer", p.onErrorContainer, p.errorContainer, ContrastTier.BODY),
        ContrastPair("brassText/surface", p.brassText, p.surface, ContrastTier.BODY),
        ContrastPair("primary/background", p.primary, p.background, ContrastTier.UI),
        ContrastPair("secondary/background", p.secondary, p.background, ContrastTier.UI),
        ContrastPair("error/background", p.error, p.background, ContrastTier.UI),
        ContrastPair("outline/surface", p.outline, p.surface, ContrastTier.UI),
    )
}

/**
 * The registry. Order is the order the picker shows: the default first, then the alternatives.
 *
 * The default pack is pinned to the M1–M3 hexes and must never drift.
 */
object ThemePacks {
    const val DEFAULT_ID = "girih_ink"

    val all: List<ThemePack> = listOf(GirihInkPack, LapisNightPack, TurquoiseDawnPack, MadderDuskPack, PaperContrastPack)
    val default: ThemePack get() = all.first()

    /** Unknown, blank, renamed or corrupted ids fall back to the brand default — never crash. */
    fun byId(raw: String?): ThemePack {
        val id = raw?.trim()
        return all.firstOrNull { it.id == id } ?: default
    }

    fun isKnown(raw: String?): Boolean {
        val id = raw?.trim()
        return all.any { it.id == id }
    }

    fun ids(): List<String> = all.map { it.id }
}

/** Maps a pack palette onto the Material 3 scheme the app consumes. */
fun ThemePack.colorScheme(dark: Boolean): ColorScheme {
    val p = if (dark) this.dark else light
    return if (dark) {
        darkColorScheme(
            primary = Color(p.primary),
            onPrimary = Color(p.onPrimary),
            primaryContainer = Color(p.primaryContainer),
            onPrimaryContainer = Color(p.onPrimaryContainer),
            inversePrimary = Color(p.inversePrimary),
            secondary = Color(p.secondary),
            onSecondary = Color(p.onSecondary),
            secondaryContainer = Color(p.secondaryContainer),
            onSecondaryContainer = Color(p.onSecondaryContainer),
            tertiary = Color(p.tertiary),
            onTertiary = Color(p.onTertiary),
            tertiaryContainer = Color(p.tertiaryContainer),
            onTertiaryContainer = Color(p.onTertiaryContainer),
            background = Color(p.background),
            onBackground = Color(p.onBackground),
            surface = Color(p.surface),
            onSurface = Color(p.onSurface),
            surfaceVariant = Color(p.surfaceVariant),
            onSurfaceVariant = Color(p.onSurfaceVariant),
            surfaceTint = Color(p.primary),
            inverseSurface = Color(p.inverseSurface),
            inverseOnSurface = Color(p.inverseOnSurface),
            error = Color(p.error),
            onError = Color(p.onError),
            errorContainer = Color(p.errorContainer),
            onErrorContainer = Color(p.onErrorContainer),
            outline = Color(p.outline),
            outlineVariant = Color(p.outlineVariant),
            scrim = Color.Black,
            surfaceBright = Color(p.surfaceContainerHigh),
            surfaceDim = Color(p.background),
            surfaceContainer = Color(p.surfaceContainer),
            surfaceContainerLow = Color(p.surfaceContainerLow),
            surfaceContainerLowest = Color(p.surfaceContainerLowest),
            surfaceContainerHigh = Color(p.surfaceContainerHigh),
            surfaceContainerHighest = Color(p.surfaceContainerHighest),
        )
    } else {
        lightColorScheme(
            primary = Color(p.primary),
            onPrimary = Color(p.onPrimary),
            primaryContainer = Color(p.primaryContainer),
            onPrimaryContainer = Color(p.onPrimaryContainer),
            inversePrimary = Color(p.inversePrimary),
            secondary = Color(p.secondary),
            onSecondary = Color(p.onSecondary),
            secondaryContainer = Color(p.secondaryContainer),
            onSecondaryContainer = Color(p.onSecondaryContainer),
            tertiary = Color(p.tertiary),
            onTertiary = Color(p.onTertiary),
            tertiaryContainer = Color(p.tertiaryContainer),
            onTertiaryContainer = Color(p.onTertiaryContainer),
            background = Color(p.background),
            onBackground = Color(p.onBackground),
            surface = Color(p.surface),
            onSurface = Color(p.onSurface),
            surfaceVariant = Color(p.surfaceVariant),
            onSurfaceVariant = Color(p.onSurfaceVariant),
            surfaceTint = Color(p.primary),
            inverseSurface = Color(p.inverseSurface),
            inverseOnSurface = Color(p.inverseOnSurface),
            error = Color(p.error),
            onError = Color(p.onError),
            errorContainer = Color(p.errorContainer),
            onErrorContainer = Color(p.onErrorContainer),
            outline = Color(p.outline),
            outlineVariant = Color(p.outlineVariant),
            scrim = Color.Black,
            surfaceBright = Color(p.surface),
            surfaceDim = Color(p.surfaceContainerHigh),
            surfaceContainer = Color(p.surfaceContainer),
            surfaceContainerLow = Color(p.surfaceContainerLow),
            surfaceContainerLowest = Color(p.surfaceContainerLowest),
            surfaceContainerHigh = Color(p.surfaceContainerHigh),
            surfaceContainerHighest = Color(p.surfaceContainerHighest),
        )
    }
}

/** Maps a pack palette onto Sirrak's non-Material tokens (decorations; motion stays global). */
fun ThemePack.decorations(dark: Boolean): SirrakDecorations {
    val p = if (dark) this.dark else light
    return SirrakDecorations(
        girihLine = Color(p.girihLine),
        brass = Color(p.brass),
        brassText = Color(p.brassText),
        hairline = Color(p.hairline),
    )
}

/**
 * lapis_night: cool blue-grey paper with lapis action and brass girih lines by day; the signature deep-lapis night by dark.
 * Both modes were validated by an independent WCAG script against the same gated pair list as the default
 * pack; the tightest margins are 5.73:1 body and 3.52:1 UI (Paper reaches 7.19:1 body, i.e. AAA).
 */
private val LapisNightPack = ThemePack(
    id = "lapis_night",
    nameRes = R.string.theme_pack_lapis_night,
    descriptionRes = R.string.theme_pack_lapis_night_desc,
    light = ThemePalette(
        background = 0xFFEFF2F6,
        onBackground = 0xFF182333,
        surface = 0xFFF3F5F9,
        onSurface = 0xFF182333,
        surfaceVariant = 0xFFDCE3ED,
        onSurfaceVariant = 0xFF46556A,
        surfaceContainerLowest = 0xFFFDFEFF,
        surfaceContainerLow = 0xFFF6F8FB,
        surfaceContainer = 0xFFECF0F6,
        surfaceContainerHigh = 0xFFE2E7EE,
        surfaceContainerHighest = 0xFFD6DEE8,
        primary = 0xFF1E4173,
        onPrimary = 0xFFFBFCFE,
        primaryContainer = 0xFFD3E0F3,
        onPrimaryContainer = 0xFF12294A,
        inversePrimary = 0xFFA6C4E8,
        secondary = 0xFF1B6A72,
        onSecondary = 0xFFF7FBFB,
        secondaryContainer = 0xFFC9E5E4,
        onSecondaryContainer = 0xFF0B3A3E,
        tertiary = 0xFF6B5316,
        onTertiary = 0xFFFDF9EE,
        tertiaryContainer = 0xFFF0E3C4,
        onTertiaryContainer = 0xFF3A2C0A,
        outline = 0xFF6F7D8D,
        outlineVariant = 0xFFC6CFDC,
        error = 0xFFA63A2E,
        onError = 0xFFFDF6F3,
        errorContainer = 0xFFF6DCD4,
        onErrorContainer = 0xFF56160F,
        inverseSurface = 0xFF1D2A3D,
        inverseOnSurface = 0xFFEAF0F8,
        girihLine = 0xFFC2AB76,
        brass = 0xFF9C7B34,
        brassText = 0xFF6B5316,
        hairline = 0xFFD8DEE7,
    ),
    dark = ThemePalette(
        background = 0xFF0E1526,
        onBackground = 0xFFEDE7D8,
        surface = 0xFF121A2C,
        onSurface = 0xFFEDE7D8,
        surfaceVariant = 0xFF1C2740,
        onSurfaceVariant = 0xFFA9B6CB,
        surfaceContainerLowest = 0xFF0A111F,
        surfaceContainerLow = 0xFF101828,
        surfaceContainer = 0xFF141D31,
        surfaceContainerHigh = 0xFF19233A,
        surfaceContainerHighest = 0xFF1F2A43,
        primary = 0xFF9DBBE4,
        onPrimary = 0xFF0C1B33,
        primaryContainer = 0xFF24406B,
        onPrimaryContainer = 0xFFCFE0F7,
        inversePrimary = 0xFF1E4173,
        secondary = 0xFF5FBEB8,
        onSecondary = 0xFF06211F,
        secondaryContainer = 0xFF11403F,
        onSecondaryContainer = 0xFFB7E6E1,
        tertiary = 0xFFC9A85C,
        onTertiary = 0xFF2A1E06,
        tertiaryContainer = 0xFF3E3011,
        onTertiaryContainer = 0xFFEBD9AE,
        outline = 0xFF7C8AA3,
        outlineVariant = 0xFF333F58,
        error = 0xFFE88C7D,
        onError = 0xFF3C0F08,
        errorContainer = 0xFF55201A,
        onErrorContainer = 0xFFF3C9BF,
        inverseSurface = 0xFFE9E4D6,
        inverseOnSurface = 0xFF1B2430,
        girihLine = 0xFF57482A,
        brass = 0xFFB08C40,
        brassText = 0xFFD3B26A,
        hairline = 0xFF29344C,
    ),
)

/**
 * turquoise_dawn: green-tinged ivory paper and deep teal action by day; teal-ink night with mint accent by dark.
 * Both modes were validated by an independent WCAG script against the same gated pair list as the default
 * pack; the tightest margins are 5.73:1 body and 3.52:1 UI (Paper reaches 7.19:1 body, i.e. AAA).
 */
private val TurquoiseDawnPack = ThemePack(
    id = "turquoise_dawn",
    nameRes = R.string.theme_pack_turquoise_dawn,
    descriptionRes = R.string.theme_pack_turquoise_dawn_desc,
    light = ThemePalette(
        background = 0xFFF3F2E5,
        onBackground = 0xFF1A2726,
        surface = 0xFFF7F6EC,
        onSurface = 0xFF1A2726,
        surfaceVariant = 0xFFDEE5DA,
        onSurfaceVariant = 0xFF45544D,
        surfaceContainerLowest = 0xFFFCFBF4,
        surfaceContainerLow = 0xFFF6F4E9,
        surfaceContainer = 0xFFF0EEE2,
        surfaceContainerHigh = 0xFFE9E7DA,
        surfaceContainerHighest = 0xFFE1DFD1,
        primary = 0xFF14615E,
        onPrimary = 0xFFF6FBF9,
        primaryContainer = 0xFFCDE6E1,
        onPrimaryContainer = 0xFF06322F,
        inversePrimary = 0xFF7FC9C1,
        secondary = 0xFF1F3F6E,
        onSecondary = 0xFFF5F8FD,
        secondaryContainer = 0xFFD6E0F0,
        onSecondaryContainer = 0xFF122A4B,
        tertiary = 0xFF6A5216,
        onTertiary = 0xFFFCF8EC,
        tertiaryContainer = 0xFFEFE2C3,
        onTertiaryContainer = 0xFF3A2B09,
        outline = 0xFF6C7A71,
        outlineVariant = 0xFFC3CCBE,
        error = 0xFFA23A2E,
        onError = 0xFFFDF5F2,
        errorContainer = 0xFFF4DAD2,
        onErrorContainer = 0xFF55150E,
        inverseSurface = 0xFF1C2A27,
        inverseOnSurface = 0xFFF0EDDF,
        girihLine = 0xFFB2BFA6,
        brass = 0xFFA4853C,
        brassText = 0xFF6A5216,
        hairline = 0xFFD9D6C8,
    ),
    dark = ThemePalette(
        background = 0xFF0C1A18,
        onBackground = 0xFFE8E9DC,
        surface = 0xFF101F1C,
        onSurface = 0xFFE8E9DC,
        surfaceVariant = 0xFF1B2E2A,
        onSurfaceVariant = 0xFFA6B8AE,
        surfaceContainerLowest = 0xFF081412,
        surfaceContainerLow = 0xFF0D1B18,
        surfaceContainer = 0xFF122521,
        surfaceContainerHigh = 0xFF172A26,
        surfaceContainerHighest = 0xFF1D312C,
        primary = 0xFF6FD0C4,
        onPrimary = 0xFF04231F,
        primaryContainer = 0xFF12463F,
        onPrimaryContainer = 0xFFB7E9E2,
        inversePrimary = 0xFF14615E,
        secondary = 0xFF9FB9DC,
        onSecondary = 0xFF0D1F38,
        secondaryContainer = 0xFF223A5C,
        onSecondaryContainer = 0xFFCFDEF2,
        tertiary = 0xFFD0AE63,
        onTertiary = 0xFF2C1F05,
        tertiaryContainer = 0xFF43340F,
        onTertiaryContainer = 0xFFEFDCB0,
        outline = 0xFF7E9187,
        outlineVariant = 0xFF33443E,
        error = 0xFFE98F80,
        onError = 0xFF3D1009,
        errorContainer = 0xFF55211A,
        onErrorContainer = 0xFFF4CBC1,
        inverseSurface = 0xFFE9E4D6,
        inverseOnSurface = 0xFF1B2430,
        girihLine = 0xFF3F5348,
        brass = 0xFFB18D42,
        brassText = 0xFFD2B56E,
        hairline = 0xFF273832,
    ),
)

/**
 * madder_dusk: rose paper, madder action and a deeper error so destructive stays distinct by day; warm ink night by dark.
 * Both modes were validated by an independent WCAG script against the same gated pair list as the default
 * pack; the tightest margins are 5.73:1 body and 3.52:1 UI (Paper reaches 7.19:1 body, i.e. AAA).
 */
private val MadderDuskPack = ThemePack(
    id = "madder_dusk",
    nameRes = R.string.theme_pack_madder_dusk,
    descriptionRes = R.string.theme_pack_madder_dusk_desc,
    light = ThemePalette(
        background = 0xFFF6EFE9,
        onBackground = 0xFF241A1B,
        surface = 0xFFFAF4EF,
        onSurface = 0xFF241A1B,
        surfaceVariant = 0xFFEBDCD4,
        onSurfaceVariant = 0xFF5A4744,
        surfaceContainerLowest = 0xFFFDF9F5,
        surfaceContainerLow = 0xFFF8F1EB,
        surfaceContainer = 0xFFF3E9E2,
        surfaceContainerHigh = 0xFFECDFD7,
        surfaceContainerHighest = 0xFFE4D4C9,
        primary = 0xFF9C3A2E,
        onPrimary = 0xFFFDF6F2,
        primaryContainer = 0xFFF0D5CD,
        onPrimaryContainer = 0xFF521A12,
        inversePrimary = 0xFFE0A295,
        secondary = 0xFF274672,
        onSecondary = 0xFFF6F8FD,
        secondaryContainer = 0xFFD9E0EF,
        onSecondaryContainer = 0xFF16294A,
        tertiary = 0xFF6B5015,
        onTertiary = 0xFFFDF8EC,
        tertiaryContainer = 0xFFF0E2C0,
        onTertiaryContainer = 0xFF3B2B07,
        outline = 0xFF826D68,
        outlineVariant = 0xFFD3C1B9,
        error = 0xFF8E2A1D,
        onError = 0xFFFDF5F1,
        errorContainer = 0xFFF2D7D0,
        onErrorContainer = 0xFF4E140C,
        inverseSurface = 0xFF2A1E1E,
        inverseOnSurface = 0xFFF6EDE7,
        girihLine = 0xFFCAAEA1,
        brass = 0xFFA88440,
        brassText = 0xFF6B5015,
        hairline = 0xFFE0CDC4,
    ),
    dark = ThemePalette(
        background = 0xFF17100F,
        onBackground = 0xFFEFE3D9,
        surface = 0xFF1D1513,
        onSurface = 0xFFEFE3D9,
        surfaceVariant = 0xFF2C201D,
        onSurfaceVariant = 0xFFBFA79E,
        surfaceContainerLowest = 0xFF100A09,
        surfaceContainerLow = 0xFF171110,
        surfaceContainer = 0xFF211816,
        surfaceContainerHigh = 0xFF2A201D,
        surfaceContainerHighest = 0xFF342724,
        primary = 0xFFDE9182,
        onPrimary = 0xFF3A1109,
        primaryContainer = 0xFF55241C,
        onPrimaryContainer = 0xFFF4CDC4,
        inversePrimary = 0xFF9C3A2E,
        secondary = 0xFFA9BCDA,
        onSecondary = 0xFF102138,
        secondaryContainer = 0xFF26395A,
        onSecondaryContainer = 0xFFD3E0F4,
        tertiary = 0xFFD2B068,
        onTertiary = 0xFF2D2005,
        tertiaryContainer = 0xFF44350F,
        onTertiaryContainer = 0xFFF0DDB4,
        outline = 0xFF94807A,
        outlineVariant = 0xFF453430,
        error = 0xFFF0907F,
        onError = 0xFF3E1109,
        errorContainer = 0xFF57231B,
        onErrorContainer = 0xFFF6CCC3,
        inverseSurface = 0xFFF0E7E0,
        inverseOnSurface = 0xFF241A1B,
        girihLine = 0xFF4D3A34,
        brass = 0xFFB58F43,
        brassText = 0xFFD6B873,
        hairline = 0xFF352622,
    ),
)

/**
 * paper_contrast: near-monochrome maximum contrast (AAA body) with one blue-ink action; black ground and warm white text by dark.
 * Both modes were validated by an independent WCAG script against the same gated pair list as the default
 * pack; the tightest margins are 5.73:1 body and 3.52:1 UI (Paper reaches 7.19:1 body, i.e. AAA).
 */
private val PaperContrastPack = ThemePack(
    id = "paper_contrast",
    nameRes = R.string.theme_pack_paper_contrast,
    descriptionRes = R.string.theme_pack_paper_contrast_desc,
    light = ThemePalette(
        background = 0xFFFFFFFF,
        onBackground = 0xFF101214,
        surface = 0xFFFCFCFB,
        onSurface = 0xFF101214,
        surfaceVariant = 0xFFE7E8E6,
        onSurfaceVariant = 0xFF3E4348,
        surfaceContainerLowest = 0xFFFFFFFF,
        surfaceContainerLow = 0xFFF5F5F3,
        surfaceContainer = 0xFFECEDEA,
        surfaceContainerHigh = 0xFFE3E4E1,
        surfaceContainerHighest = 0xFFD9DAD7,
        primary = 0xFF14417A,
        onPrimary = 0xFFFFFFFF,
        primaryContainer = 0xFFD6E2F0,
        onPrimaryContainer = 0xFF0B2748,
        inversePrimary = 0xFFA8C6E8,
        secondary = 0xFF33475C,
        onSecondary = 0xFFFFFFFF,
        secondaryContainer = 0xFFDDE3E9,
        onSecondaryContainer = 0xFF14202B,
        tertiary = 0xFF4A4A46,
        onTertiary = 0xFFFFFFFF,
        tertiaryContainer = 0xFFE5E5DF,
        onTertiaryContainer = 0xFF22221F,
        outline = 0xFF5C6165,
        outlineVariant = 0xFFC7C9C6,
        error = 0xFFA32B1F,
        onError = 0xFFFFFFFF,
        errorContainer = 0xFFF3DAD5,
        onErrorContainer = 0xFF4E1109,
        inverseSurface = 0xFF16181A,
        inverseOnSurface = 0xFFF7F6F1,
        girihLine = 0xFFC7C9C5,
        brass = 0xFF8A7546,
        brassText = 0xFF5E4E22,
        hairline = 0xFFD6D7D4,
    ),
    dark = ThemePalette(
        background = 0xFF000000,
        onBackground = 0xFFF5F2EA,
        surface = 0xFF0B0B0A,
        onSurface = 0xFFF5F2EA,
        surfaceVariant = 0xFF1E1F1E,
        onSurfaceVariant = 0xFFC3C3BC,
        surfaceContainerLowest = 0xFF000000,
        surfaceContainerLow = 0xFF090909,
        surfaceContainer = 0xFF111111,
        surfaceContainerHigh = 0xFF191A19,
        surfaceContainerHighest = 0xFF232423,
        primary = 0xFF9EC2EC,
        onPrimary = 0xFF04192F,
        primaryContainer = 0xFF1B3A62,
        onPrimaryContainer = 0xFFCFE0F5,
        inversePrimary = 0xFF14417A,
        secondary = 0xFFB4C2D1,
        onSecondary = 0xFF101A24,
        secondaryContainer = 0xFF2A3641,
        onSecondaryContainer = 0xFFD6DFE7,
        tertiary = 0xFFD3D1C6,
        onTertiary = 0xFF232320,
        tertiaryContainer = 0xFF33332E,
        onTertiaryContainer = 0xFFE5E3D8,
        outline = 0xFF8E8E88,
        outlineVariant = 0xFF3A3B39,
        error = 0xFFF09484,
        onError = 0xFF3A0D06,
        errorContainer = 0xFF5A1E15,
        onErrorContainer = 0xFFF6CEC4,
        inverseSurface = 0xFFF2EFE7,
        inverseOnSurface = 0xFF141416,
        girihLine = 0xFF33342F,
        brass = 0xFFA18A57,
        brassText = 0xFFD9C088,
        hairline = 0xFF2A2B28,
    ),
)

/**
 * The default pack: the M1–M3 Girih & Ink hexes, byte for byte. This is the brand's floor; every other
 * pack is a sibling of it. Never "tidy" these values — the M4 test pins them and the M1/M3 screenshots
 * are the evidence.
 */
private val GirihInkPack = ThemePack(
    id = ThemePacks.DEFAULT_ID,
    nameRes = R.string.theme_pack_girih_ink,
    descriptionRes = R.string.theme_pack_girih_ink_desc,
    light = ThemePalette(
        background = 0xFFF7F3EA,
        onBackground = 0xFF1B2430,
        surface = 0xFFFDFAF3,
        onSurface = 0xFF1B2430,
        surfaceVariant = 0xFFEDE7D8,
        onSurfaceVariant = 0xFF5C6672,
        surfaceContainerLowest = 0xFFF7F3EA,
        surfaceContainerLow = 0xFFF3EEE2,
        surfaceContainer = 0xFFEDE7D8,
        surfaceContainerHigh = 0xFFE6DFCE,
        surfaceContainerHighest = 0xFFDED6C2,
        primary = 0xFF1D3F6E,
        onPrimary = 0xFFF7F3EA,
        primaryContainer = 0xFFD8E2F0,
        onPrimaryContainer = 0xFF10305A,
        inversePrimary = 0xFF9DB8DD,
        secondary = 0xFF1F6F6B,
        onSecondary = 0xFFF7F3EA,
        secondaryContainer = 0xFFD6E9E5,
        onSecondaryContainer = 0xFF0E3F3C,
        tertiary = 0xFF6F561A,
        onTertiary = 0xFFF7F3EA,
        tertiaryContainer = 0xFFEFE2C2,
        onTertiaryContainer = 0xFF3F300B,
        outline = 0xFF8A8F93,
        outlineVariant = 0xFFD6D0C2,
        error = 0xFFA63A2E,
        onError = 0xFFF7F3EA,
        errorContainer = 0xFFF4DAD3,
        onErrorContainer = 0xFF5A160E,
        inverseSurface = 0xFF0F1416,
        inverseOnSurface = 0xFFE9E4D6,
        girihLine = 0xFFB08D3E,
        brass = 0xFFB08D3E,
        brassText = 0xFF6F561A,
        hairline = 0xFFE3DCCB,
    ),
    dark = ThemePalette(
        background = 0xFF0F1416,
        onBackground = 0xFFE9E4D6,
        surface = 0xFF121A1D,
        onSurface = 0xFFE9E4D6,
        surfaceVariant = 0xFF1A262A,
        onSurfaceVariant = 0xFFA9B1AF,
        surfaceContainerLowest = 0xFF0B1012,
        surfaceContainerLow = 0xFF162024,
        surfaceContainer = 0xFF1A262A,
        surfaceContainerHigh = 0xFF1F2D31,
        surfaceContainerHighest = 0xFF253438,
        primary = 0xFF9DB8DD,
        onPrimary = 0xFF0B2B52,
        primaryContainer = 0xFF24436B,
        onPrimaryContainer = 0xFFD8E2F0,
        inversePrimary = 0xFF1D3F6E,
        secondary = 0xFF7FC0B9,
        onSecondary = 0xFF08302D,
        secondaryContainer = 0xFF1B4B47,
        onSecondaryContainer = 0xFFD6E9E5,
        tertiary = 0xFFD6B568,
        onTertiary = 0xFF3A2C08,
        tertiaryContainer = 0xFF4A3A12,
        onTertiaryContainer = 0xFFEFE2C2,
        outline = 0xFF7C8A8E,
        outlineVariant = 0xFF2C3A3E,
        error = 0xFFE8A79C,
        onError = 0xFF4A1009,
        errorContainer = 0xFF5E1D14,
        onErrorContainer = 0xFFF4DAD3,
        inverseSurface = 0xFFF7F3EA,
        inverseOnSurface = 0xFF1B2430,
        girihLine = 0xFFD6B568,
        brass = 0xFFD6B568,
        brassText = 0xFFD6B568,
        hairline = 0xFF243134,
    ),
)