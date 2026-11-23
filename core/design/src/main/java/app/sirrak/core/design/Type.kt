package app.sirrak.core.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * Two families only: Amiri for authored Arabic (display, assistant prose, wordmark) and IBM Plex
 * Sans Arabic for interface text. Both are OFL and bundled — never fetched at runtime.
 */
val AmiriFamily = FontFamily(
    Font(R.font.amiri_regular, FontWeight.Normal),
    Font(R.font.amiri_bold, FontWeight.Bold),
)

val PlexArabicFamily = FontFamily(
    Font(R.font.ibm_plex_sans_arabic_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_arabic_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_arabic_semibold, FontWeight.SemiBold),
)

private val arabicLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun style(
    size: Int,
    lineHeight: Int,
    weight: FontWeight,
    family: FontFamily,
    letterSpacing: Double = 0.0,
) = TextStyle(
    fontFamily = family,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
    lineHeightStyle = arabicLineHeight,
)

val SirrakTypography = Typography(
    displayLarge = style(56, 64, FontWeight.Normal, AmiriFamily),
    displayMedium = style(44, 52, FontWeight.Normal, AmiriFamily),
    displaySmall = style(36, 46, FontWeight.Normal, AmiriFamily),
    headlineLarge = style(32, 42, FontWeight.Normal, AmiriFamily),
    headlineMedium = style(28, 38, FontWeight.Normal, AmiriFamily),
    headlineSmall = style(24, 34, FontWeight.Normal, AmiriFamily),
    titleLarge = style(20, 28, FontWeight.SemiBold, PlexArabicFamily),
    titleMedium = style(16, 24, FontWeight.SemiBold, PlexArabicFamily),
    titleSmall = style(14, 20, FontWeight.SemiBold, PlexArabicFamily),
    bodyLarge = style(16, 26, FontWeight.Normal, PlexArabicFamily),
    bodyMedium = style(14, 22, FontWeight.Normal, PlexArabicFamily),
    bodySmall = style(12, 18, FontWeight.Normal, PlexArabicFamily),
    labelLarge = style(14, 20, FontWeight.SemiBold, PlexArabicFamily),
    labelMedium = style(12, 16, FontWeight.SemiBold, PlexArabicFamily),
    labelSmall = style(11, 16, FontWeight.Medium, PlexArabicFamily, letterSpacing = 0.3),
)

/** Reading style for assistant prose: larger, airier, serif. */
val AssistantProseStyle = style(19, 33, FontWeight.Normal, AmiriFamily)
val WordmarkStyle = style(26, 34, FontWeight.Bold, AmiriFamily)
val DisplayNumberStyle = style(30, 36, FontWeight.SemiBold, PlexArabicFamily)
