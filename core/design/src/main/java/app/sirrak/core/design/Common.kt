package app.sirrak.core.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** The Arabic wordmark, set in Amiri. Text passed in so each surface can localise it. */
@Composable
fun Wordmark(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = WordmarkStyle,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

/** A screen title in the authored serif; never a kicker above it. */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

/**
 * Centred reading column: fills the window, capped at the configured measure so Arabic prose
 * never runs to 140 characters per line on a tablet or a wide emulator.
 */
@Composable
fun ReadingColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val maxWidth = LocalAppContext.current.config.ui.maxContentWidthDp.dp
    Box(modifier = modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = maxWidth)
                .fillMaxSize(),
            content = content,
        )
    }
}

/** The empty conversation: one promise, one privacy line, then suggestions from content JSON. */
@Composable
fun EmptyConversation(
    promise: String,
    privacy: String,
    suggestionsTitle: String,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
    suggestions: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GirihMark(modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
        androidx.compose.foundation.layout.Spacer(Modifier.size(14.dp))
        Text(
            text = promise,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
        Text(
            text = privacy,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (suggestions != null) {
            androidx.compose.foundation.layout.Spacer(Modifier.size(18.dp))
            Text(
                text = suggestionsTitle,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(start = 2.dp, bottom = 8.dp),
            )
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) { suggestions() }
        }
        if (footer != null) {
            androidx.compose.foundation.layout.Spacer(Modifier.size(24.dp))
            footer()
        }
    }
}

@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScreenTitle(text = title, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}
