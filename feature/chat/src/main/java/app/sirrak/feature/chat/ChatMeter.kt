package app.sirrak.feature.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import app.sirrak.core.runtime.TokenBudget
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement

/**
 * The quiet budget line above the composer: how full the context window is, and (after an answer)
 * how fast it arrived. Estimates are labelled as estimates; the warning appears before the engine
 * starts dropping the oldest turns, never after. Hidden on an untouched conversation.
 */
@Composable
fun PromptMeter(
    budget: TokenBudget,
    speed: Float?,
    streaming: Boolean,
    modifier: Modifier = Modifier,
) {
    if (budget.estimatedTokens <= 0 && speed == null) return
    val warning = when {
        budget.overflow -> stringResource(R.string.meter_overflow)
        budget.nearLimit -> stringResource(R.string.meter_near)
        else -> null
    }
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(
                    R.string.meter_tokens,
                    budget.estimatedTokens,
                    if (budget.promptBudget > 0) budget.promptBudget else budget.contextTokens,
                ),
                style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Content),
                color = when {
                    budget.overflow -> MaterialTheme.colorScheme.error
                    budget.nearLimit -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f),
            )
            if (!streaming && speed != null && speed > 0f) {
                Text(
                    text = stringResource(R.string.meter_speed, formatSpeed(speed)),
                    style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Content),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (warning != null) {
            Text(
                text = warning,
                style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Content),
                color = if (budget.overflow) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

/** Below ten tokens per second a whole number would read as "0" or lie; keep one decimal. */
private fun formatSpeed(speed: Float): String =
    if (speed >= 10f) speed.roundToInt().toString() else String.format(Locale.US, "%.1f", speed)
