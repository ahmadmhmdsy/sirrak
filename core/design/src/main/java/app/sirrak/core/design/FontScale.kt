package app.sirrak.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * A text-size preset (M6 THM-6). Presets are *data, never code at the call site*: the settings screen
 * iterates [FontScales.all] and the root applies whatever id the service resolves, so adding 145% is
 * one registry entry plus its AR/EN label — no new branch anywhere.
 *
 * Only sp text scales. Density (dp geometry) is deliberately excluded, so rows keep their 56 dp touch
 * target and the layout does not zoom with the reader's preferred text size.
 */
data class FontScale(
    /** Persisted id; stable, never localised, never reused for a different percent. */
    val id: String,
    /** Text size as a percentage of the platform size. */
    val percent: Int,
    /** AR/EN display label (":core:design" owns its own resources, like [ThemePack.nameRes]). */
    val labelRes: Int,
) {
    init {
        require(id.isNotBlank()) { "font-scale id must not be blank" }
        require(percent > 0) { "font-scale percent must be positive: $percent" }
    }

    /** Multiplier applied to the platform font scale. */
    val factor: Float get() = percent / 100f

    /**
     * The platform font scale after this preset. Multiplication preserves a user's system
     * accessibility scale instead of replacing it.
     */
    fun applyTo(platformFontScale: Float): Float = platformFontScale * factor
}

object FontScales {

    const val DEFAULT_ID = "default"

    /**
     * Ordered smallest to largest. The first and last entries are also the clamp bounds for numeric
     * fallbacks, so the range can only grow by adding an entry.
     */
    val all: List<FontScale> = listOf(
        FontScale(id = "small", percent = 85, labelRes = R.string.font_scale_small),
        FontScale(id = DEFAULT_ID, percent = 100, labelRes = R.string.font_scale_default),
        FontScale(id = "large", percent = 115, labelRes = R.string.font_scale_large),
        FontScale(id = "largest", percent = 130, labelRes = R.string.font_scale_largest),
    )

    val default: FontScale = all.first { it.id == DEFAULT_ID }

    /**
     * Resolves a persisted preference. Ids are the stored form; a numeric percent is accepted as a
     * defensive fallback for old or external writers, clamped into the supported range and snapped to
     * the nearest preset. Anything else — a removed id, blank, null — opens at the 100% default.
     */
    fun byId(id: String?): FontScale {
        val raw = id?.trim()
        all.firstOrNull { it.id == raw }?.let { return it }
        val percent = raw?.toIntOrNull() ?: return default
        val clamped = percent.coerceIn(all.first().percent, all.last().percent)
        return all.minByOrNull { abs(it.percent - clamped) } ?: default
    }
}

/**
 * Applies [fontScaleId] to every sp inside [content] by overriding LocalDensity.fontScale only.
 * Density.density (dp geometry) is untouched, so text grows while layout does not. Call once at the
 * composition root; previews and tests that do not call it keep the platform scale.
 */
@Composable
fun ProvideFontScale(fontScaleId: String, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val scale = remember(fontScaleId) { FontScales.byId(fontScaleId) }
    val scaledDensity = remember(density, scale) {
        Density(
            density = density.density,
            fontScale = scale.applyTo(density.fontScale),
        )
    }
    CompositionLocalProvider(LocalDensity provides scaledDensity, content = content)
}

/**
 * One selectable text-size row, mirroring [ThemePackOption] semantics: 56 dp, [selectable] with the
 * RadioButton role so TalkBack announces "selected", and a tonal container plus a check so colour is
 * never the only cue. The percentage is laid out LTR so bidi cannot reorder "115%".
 */
@Composable
fun FontScaleOption(
    scale: FontScale,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(SirrakShapes.medium)
            .background(background)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(scale.labelRes),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${scale.percent}%",
            style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}
