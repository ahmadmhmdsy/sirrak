package app.sirrak.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

/**
 * A theme-pack choice row. It is [selectable] (TalkBack announces "selected", not just "checked"), 56 dp
 * tall, and shows a two-mode preview so the user sees both light and dark before applying. The preview is
 * decorative and hidden from TalkBack: the name and description already say what the pack is.
 */
@Composable
fun ThemePackOption(
    pack: ThemePack,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Selected rows get a tonal container as well as the check: colour alone is never the only cue.
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
        ThemePackPreview(pack)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(pack.nameRes),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(pack.descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
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

/** Light + dark mini previews; drawn from the pack itself, never from the currently active theme. */
@Composable
fun ThemePackPreview(pack: ThemePack, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ThemePackPreviewTile(pack.light)
        ThemePackPreviewTile(pack.dark)
    }
}

@Composable
private fun ThemePackPreviewTile(palette: ThemePalette) {
    Box(
        modifier = Modifier
            .size(width = 28.dp, height = 32.dp)
            .clip(SirrakShapes.small)
            .background(Color(palette.background))
            .border(1.dp, Color(palette.outlineVariant), SirrakShapes.small),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            PreviewBar(palette.primary, width = 15.dp, height = 4.dp)
            PreviewBar(palette.secondary, width = 10.dp, height = 3.dp)
            PreviewBar(palette.tertiary, width = 6.dp, height = 3.dp)
        }
    }
}

@Composable
private fun PreviewBar(color: Long, width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .size(width = width, height = height)
            .clip(RoundedCornerShape(2.dp))
            .background(Color(color)),
    )
}
