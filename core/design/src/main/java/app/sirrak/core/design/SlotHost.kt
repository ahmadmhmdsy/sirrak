package app.sirrak.core.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.sirrak.core.runtime.AppContext
import app.sirrak.core.runtime.SlotKey

val LocalAppContext = staticCompositionLocalOf<AppContext> {
    error("No AppContext provided — wrap the UI in SirrakHost")
}

/** Renders the single contributor of a declared slot, or nothing. */
@Composable
fun SlotSingle(key: SlotKey, modifier: Modifier = Modifier) {
    val content = LocalAppContext.current.slots.singleContent(key) ?: return
    androidx.compose.foundation.layout.Box(modifier) { content() }
}

/** Renders every contributor of a declared list slot in declared order. */
@Composable
fun SlotList(key: SlotKey, modifier: Modifier = Modifier) {
    val contents = LocalAppContext.current.slots.listContent(key)
    androidx.compose.foundation.layout.Column(modifier) {
        contents.forEach { content -> content() }
    }
}

/**
 * Renders a declared list slot as one horizontal row of actions. Used where contributors add a
 * button next to existing actions (chat answer actions, a future composer toolbar) instead of
 * stacking a second bar.
 */
@Composable
fun SlotRow(
    key: SlotKey,
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(8.dp),
) {
    val contents = LocalAppContext.current.slots.listContent(key)
    if (contents.isEmpty()) return
    Row(
        modifier = modifier,
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        contents.forEach { content -> content() }
    }
}
