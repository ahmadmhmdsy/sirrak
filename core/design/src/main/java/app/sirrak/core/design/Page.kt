package app.sirrak.core.design

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * A full-screen page pushed over the bottom navigation: back arrow, serif title, then content.
 * Pages are the only place a back affordance exists, so the gesture and the arrow mean the same thing.
 * The back arrow is auto-mirrored: in RTL it points right, in LTR left.
 */
@Composable
fun PageScaffold(
    title: String,
    onBack: () -> Unit,
    backContentDescription: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 20.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = backContentDescription,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            ScreenTitle(text = title, modifier = Modifier.weight(1f))
            trailing?.invoke()
        }
        Hairline()
        Column(modifier = Modifier.fillMaxSize(), content = content)
    }
}

/**
 * The standard body of a pushed settings page: scrollable, inset by the reading column, with the
 * shared back affordance. Contributor modules own only the rows; this keeps every page consistent and
 * keeps the back arrow's accessible name in one place.
 */
@Composable
fun SettingsPageScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    PageScaffold(
        title = title,
        onBack = onBack,
        backContentDescription = stringResource(R.string.page_back),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 4.dp),
            content = content,
        )
    }
}

/** Tone of a status pill; the caller supplies the words, the theme supplies the colour. */
enum class PillTone { OK, WARN, BLOCK, NEUTRAL }

/** A small status pill used by model cards and verdict rows. */
@Composable
fun StatusPill(text: String, tone: PillTone, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (tone) {
        PillTone.OK -> colors.secondaryContainer to colors.onSecondaryContainer
        PillTone.WARN -> colors.tertiaryContainer to colors.onTertiaryContainer
        PillTone.BLOCK -> colors.errorContainer to colors.onErrorContainer
        PillTone.NEUTRAL -> colors.surfaceContainerHigh to colors.onSurfaceVariant
    }
    androidx.compose.material3.Surface(
        modifier = modifier,
        shape = androidx.compose.foundation.shape.CircleShape,
        color = container,
        contentColor = content,
    ) {
        androidx.compose.material3.Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            maxLines = 1,
        )
    }
}
