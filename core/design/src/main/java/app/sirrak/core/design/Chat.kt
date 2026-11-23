package app.sirrak.core.design

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.sirrak.core.runtime.SourceRef
import androidx.compose.ui.draw.rotate

/**
 * The composer bar: one tonal surface holding dictation, the field, slot actions and send/stop.
 * Send is the only saturated lapis element on the chat screen, so it reads as the one action.
 *
 * [leading] hosts an affordance before the field (M7: attach) without the design module learning
 * what it does. [sendEnabled] is separate from the text because a turn can be worth sending with an
 * empty field (an attachment-only question); the default keeps text as the gate for older callers.
 */
@Composable
fun ChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    sendDescription: String,
    stopDescription: String,
    streaming: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    micDescription: String? = null,
    onMic: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
    leading: (@Composable () -> Unit)? = null,
    sendEnabled: Boolean = value.isNotBlank(),
) {
    val hairline = LocalSirrakTokens.current.decorations.hairline
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, hairline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            // The leading action (attach in M7) sits at the row's start, before dictation and field.
            leading?.invoke()
            if (onMic != null) {
                IconButton(onClick = onMic, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Mic,
                        contentDescription = micDescription,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp, max = 160.dp)
                    .padding(horizontal = 8.dp, vertical = 13.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    textDirection = TextDirection.ContentOrRtl,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Default,
                ),
                maxLines = 6,
                decorationBox = { innerTextField ->
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    innerTextField()
                },
            )
            trailing()
            FilledIconButton(
                onClick = if (streaming) onStop else onSend,
                enabled = streaming || sendEnabled,
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                colors = if (streaming) {
                    IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                } else {
                    IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            ) {
                Icon(
                    imageVector = if (streaming) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (streaming) stopDescription else sendDescription,
                )
            }
        }
    }
}

/** Assistant prose: no bubble. Serif, airy, with a low-key inking mark while text is arriving. */
@Composable
fun AssistantProse(
    text: String,
    streaming: Boolean,
    sources: List<SourceRef>,
    sourcesLabel: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            if (streaming) {
                InkingMark()
                Spacer(Modifier.width(8.dp))
            }
            // The answer is markdown: headings, lists, quotes, tables and fenced code render as
            // themselves instead of leaking raw punctuation into the reading surface. Paragraph
            // direction follows the content, not the app locale: an English answer inside the
            // Arabic app must not render its punctuation RTL.
            MarkdownText(
                markdown = text,
                style = AssistantProseStyle.copy(textDirection = TextDirection.Content),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (sources.isNotEmpty() && !streaming) {
            Spacer(Modifier.size(14.dp))
            Text(
                text = sourcesLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(6.dp))
            SourceChips(sources)
        }
    }
}

/**
 * The quiet actions under a finished answer: copy always, regenerate only on the newest answer
 * (an older one would re-run a turn out of its context). Glyphs stay muted so the prose keeps the
 * surface; the copied state is a momentary tick in lapis, the one accent an action earns.
 */
@Composable
fun AnswerActions(
    copyLabel: String,
    copiedLabel: String,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
    copied: Boolean = false,
    regenerateLabel: String? = null,
    onRegenerate: (() -> Unit)? = null,
    /**
     * Contributed actions (report, read-aloud) rendered after the built-in ones, inside the same
     * row so they inherit the glyph inset and spacing. Empty by default: a composition without a
     * contributor renders exactly the M3 surface.
     */
    extraActions: @Composable RowScope.() -> Unit = {},
) {
    val direction = LocalLayoutDirection.current
    Row(
        // The 48 dp button boxes leave a 14 dp glyph inset; pull the row toward the prose edge so
        // the icons sit under the text rather than floating inboard of it (mirrored for LTR).
        modifier = modifier.offset(x = if (direction == LayoutDirection.Rtl) 14.dp else (-14).dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onCopy, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                contentDescription = if (copied) copiedLabel else copyLabel,
                tint = if (copied) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(20.dp),
            )
        }
        if (onRegenerate != null && regenerateLabel != null) {
            IconButton(onClick = onRegenerate, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = regenerateLabel,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        extraActions()
    }
}

@Composable
private fun SourceChips(sources: List<SourceRef>) {
    SourceChipRow(sources)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceChipRow(sources: List<SourceRef>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        sources.forEach { source -> SourceChip(source) }
    }
}

@Composable
fun SourceChip(source: SourceRef, modifier: Modifier = Modifier) {
    val hairline = LocalSirrakTokens.current.decorations.hairline
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, hairline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GirihTick(modifier = Modifier.size(6.dp))
            Spacer(Modifier.width(7.dp))
            Text(
                text = source.title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (source.note != null) {
                Text(
                    text = " · ${source.note}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A user turn: a short tonal note, aligned to the reading end, no tail, no avatar. */
@Composable
fun UserNote(text: String, modifier: Modifier = Modifier) {
    val hairline = LocalSirrakTokens.current.decorations.hairline
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            modifier = Modifier.widthIn(max = 300.dp),
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomEnd = 6.dp,
                bottomStart = 18.dp,
            ),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, hairline),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun InkingMark() {
    val transition = rememberInfiniteTransition(label = "inking")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2600), RepeatMode.Restart),
        label = "inkingRotation",
    )
    GirihMark(
        modifier = Modifier.size(12.dp).rotate(rotation),
        tint = LocalSirrakTokens.current.decorations.brassText,
    )
}

/** A suggested question: one row, reading-size prompt plus a quiet gloss. */
@Composable
fun SuggestionCard(
    prompt: String,
    gloss: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hairline = LocalSirrakTokens.current.decorations.hairline
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, hairline),
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClick, role = Role.Button)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GirihMark(modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = prompt,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = gloss,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The engine chip in the chat header; contents and click behaviour are contributed by the engine plugin. */
@Composable
fun ModelChip(
    label: String,
    status: String?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val hairline = LocalSirrakTokens.current.decorations.hairline
    // The pill is deliberately small, but its touch target must still be 48 dp: the outer Box owns
    // the gesture and the minimum size, the Surface stays the visible pill inside it.
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick, role = Role.Button)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, hairline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GirihTick(modifier = Modifier.size(6.dp))
            Spacer(Modifier.width(7.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (status != null) {
                Text(
                    text = " · $status",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    }
}

@Composable
fun StreamingLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = LocalContentColor.current,
        modifier = modifier,
    )
}
