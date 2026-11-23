package app.sirrak.feature.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sirrak.core.design.LocalSirrakTokens
import app.sirrak.core.runtime.Attachment
import app.sirrak.core.runtime.AttachmentBlockReason
import app.sirrak.core.runtime.AttachmentDecision
import app.sirrak.core.runtime.AttachmentGate
import app.sirrak.core.runtime.AttachmentKind
import app.sirrak.core.runtime.AttachmentLimits

/**
 * The M7 attachment surfaces: the composer's attach sheet, the shared-text dialog and the chips that
 * show what is queued (before sending) or what was sent (inside a user turn).
 *
 * Every label here comes from resources, and every disabled row says *why* it is disabled. That is
 * deliberate: a greyed paperclip with no explanation reads as a bug, and a row that accepts a tap but
 * does nothing is worse than a row that cannot be tapped. The only kind this build can actually import
 * is shared text; the gate decides whether the loaded engine can read it, and the sheet renders the
 * decision instead of hiding unavailable kinds (a user should be able to see what is coming).
 */

/**
 * Sheet order: the one action available today first, so it is not buried under three unavailable
 * rows. Kinds the order does not mention are appended (the order is a preference, not a filter), so a
 * future enum entry cannot silently disappear from the sheet.
 */
private val SheetKindOrder = listOf(
    AttachmentKind.SHARED_TEXT,
    AttachmentKind.DOCUMENT,
    AttachmentKind.IMAGE,
    AttachmentKind.AUDIO_TRANSCRIPT,
)

private val SheetKinds: List<AttachmentKind> = AttachmentKind.entries.sortedBy { kind ->
    val index = SheetKindOrder.indexOf(kind)
    if (index >= 0) index else SheetKindOrder.size
}

/**
 * Rows whose import flow exists in this build. The gate still has the final say: a kind is tappable
 * only when it is both importable and supported by the composed backend (M7 text, M8 documents).
 */

/** Kind glyph, shared by the sheet rows and the chips so a document looks the same everywhere. */
private fun iconOf(kind: AttachmentKind): ImageVector = when (kind) {
    AttachmentKind.DOCUMENT -> Icons.Outlined.Description
    AttachmentKind.IMAGE -> Icons.Outlined.Image
    AttachmentKind.AUDIO_TRANSCRIPT -> Icons.Outlined.GraphicEq
    AttachmentKind.SHARED_TEXT -> Icons.Outlined.ContentPaste
}

/** Localised kind name; the sheet row title and the transcript chip's icon description. */
@Composable
private fun kindLabel(kind: AttachmentKind): String = stringResource(
    when (kind) {
        AttachmentKind.DOCUMENT -> R.string.attachment_kind_document
        AttachmentKind.IMAGE -> R.string.attachment_kind_image
        AttachmentKind.AUDIO_TRANSCRIPT -> R.string.attachment_kind_audio
        AttachmentKind.SHARED_TEXT -> R.string.attachment_kind_shared_text
    },
)

/**
 * One attachment chip: kind glyph plus the stored display name, optionally removable. Both the queued
 * row (removable) and the transcript (fixed) use it, so a file looks the same before and after it is
 * sent. The 48 dp minimum keeps the remove target reachable; the name stays on one line so a pasted
 * paragraph cannot push the composer off screen.
 */
@Composable
fun AttachmentChip(
    attachment: Attachment,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
) {
    val hairline = LocalSirrakTokens.current.decorations.hairline
    val label = kindLabel(attachment.kind)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, hairline),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .padding(start = 12.dp, end = if (onRemove != null) 4.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = iconOf(attachment.kind),
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = attachment.displayName,
                style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Content),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp),
            )
            if (onRemove != null) {
                IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(
                            R.string.attachment_remove,
                            attachment.displayName,
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/**
 * A wrapping row of chips: the queued attachments above the composer and the sent ones under a user
 * turn. The arrangement is a parameter so the transcript can mirror to the reading end while the
 * composer queue starts at the leading edge; an empty list renders nothing, not a gap.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AttachmentChipRow(
    attachments: List<Attachment>,
    modifier: Modifier = Modifier,
    arrangement: Arrangement.Horizontal = Arrangement.spacedBy(8.dp),
    onRemove: ((Attachment) -> Unit)? = null,
) {
    if (attachments.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = arrangement,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEach { attachment ->
            key(attachment.id) {
                AttachmentChip(
                    attachment = attachment,
                    onRemove = onRemove?.let { remove -> { remove(attachment) } },
                )
            }
        }
    }
}

/**
 * The attach sheet. Rows come from the runtime gate, not from a UI list of favourite types, so the
 * sheet and the send path can never disagree about what the loaded model accepts. Disabled rows stay
 * visible and explain themselves; tapping them does nothing because there is nothing honest to do.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentSheet(
    gate: AttachmentGate,
    onDismiss: () -> Unit,
    /**
     * Import flows by kind. A kind without an action stays visible but untappable, so the sheet can
     * show the roadmap honestly without pretending the action exists. Adding a flow is one map entry
     * at the call site, not a sheet change.
     */
    actions: Map<AttachmentKind, () -> Unit>,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
    ) {
        // ModalBottomSheet already applies BottomSheetDefaults.windowInsets to its content, so the
        // sheet only adds horizontal rhythm; adding navigation-bar padding here would double the gap.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.attachments_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.action_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SheetKinds.forEach { kind ->
                AttachmentSheetRow(
                    kind = kind,
                    decision = gate.decision(kind),
                    onClick = actions[kind],
                )
            }
        }
    }
}

/**
 * One sheet row. A kind is tappable only when the engine can read it and this build can import it;
 * otherwise the subtitle explains the missing capability in the user's language. The enabled subtitle
 * for shared text states the trust model up front (explicit paste, untrusted data) so the user knows
 * what they are handing over before the dialog opens.
 */
@Composable
private fun AttachmentSheetRow(
    kind: AttachmentKind,
    decision: AttachmentDecision,
    onClick: (() -> Unit)?,
) {
    val importable = onClick != null
    val enabled = decision == AttachmentDecision.Enabled && importable
    val subtitle = when {
        decision is AttachmentDecision.Disabled -> blockedLabel(kind, decision.reason)
        !importable -> stringResource(R.string.attachment_capture_pending)
        kind == AttachmentKind.DOCUMENT -> stringResource(R.string.attachment_document_hint)
        kind == AttachmentKind.IMAGE -> stringResource(R.string.attachment_image_hint)
        else -> stringResource(R.string.attachment_shared_text_hint)
    }
    val titleColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        // Faded enough to read as unavailable, still above the disabled-text floor on the sheet
        // surface; the reason below keeps full onSurfaceVariant contrast so it stays readable.
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .then(
                if (enabled) {
                    // enabled implies a click handler (see importable above); the fallback keeps the
                    // clickable overload without a second nullable branch.
                    Modifier.clickable(onClick = onClick ?: {}, role = Role.Button)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = iconOf(kind),
            // The title carries the meaning for screen readers; a second description would repeat it.
            contentDescription = null,
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            },
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = kindLabel(kind),
                style = MaterialTheme.typography.bodyLarge,
                color = titleColor,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The localised reason a gate decision refused a kind. Shared text is mapped separately: the runtime
 * gate reports AUDIO_BACKEND for a text-less engine, which would be a lie on the shared-text row.
 */
@Composable
private fun blockedLabel(kind: AttachmentKind, reason: AttachmentBlockReason): String = when {
    kind == AttachmentKind.SHARED_TEXT -> stringResource(R.string.attachment_blocked_text)
    reason == AttachmentBlockReason.DOCUMENT_BACKEND ->
        stringResource(R.string.attachment_blocked_document)
    reason == AttachmentBlockReason.VISION_MODEL ->
        stringResource(R.string.attachment_blocked_vision)
    else -> stringResource(R.string.attachment_blocked_audio)
}

/**
 * The explicit shared-text flow. The caller reads the clipboard only after the user taps the row and
 * passes the result in as initialText; this dialog never touches the clipboard itself, so composing
 * the sheet cannot leak a paste the user did not ask for. Attach is the only action that leaves the
 * dialog, and it leaves only when onAttach reports success: validation is in the store, so the dialog
 * cannot disagree with what was actually saved.
 */
@Composable
fun SharedTextDialog(
    initialText: String,
    onDismiss: () -> Unit,
    onAttach: (text: String, displayName: String) -> Boolean,
    onAttached: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable { mutableStateOf(initialText) }
    var error by remember { mutableStateOf<String?>(null) }
    val limit = AttachmentLimits.Default.sharedTextChars
    val emptyError = stringResource(R.string.shared_text_empty)
    val tooLongError = stringResource(R.string.shared_text_too_long)
    val failedError = stringResource(R.string.shared_text_failed)
    val overLimit = text.length > limit
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(stringResource(R.string.shared_text_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        textDirection = TextDirection.Content,
                    ),
                    label = { Text(stringResource(R.string.shared_text_label)) },
                    minLines = 4,
                    maxLines = 8,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.shared_text_counter, text.length, limit),
                    style = MaterialTheme.typography.labelSmall.copy(
                        textDirection = TextDirection.Content,
                    ),
                    color = if (overLimit) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (error != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmed = text.trim()
                    when {
                        trimmed.isEmpty() -> error = emptyError
                        overLimit -> error = tooLongError
                        onAttach(trimmed, displayNameOf(trimmed)) -> onAttached()
                        else -> error = failedError
                    }
                },
            ) {
                Text(stringResource(R.string.action_attach))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/**
 * M9: one image, two sources. Gallery and camera are the same [AttachmentKind.IMAGE] to the engine, so
 * the fork lives entirely in the UI; the caller gets one callback per source and never a shared
 * "whatever the user chose" path that would hide which permission surface was used.
 */
@Composable
fun ImageSourceDialog(
    onDismiss: () -> Unit,
    onGallery: () -> Unit,
    onCamera: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(stringResource(R.string.image_source_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ImageSourceRow(
                    icon = Icons.Outlined.PhotoLibrary,
                    label = stringResource(R.string.image_source_gallery),
                    onClick = onGallery,
                )
                ImageSourceRow(
                    icon = Icons.Outlined.PhotoCamera,
                    label = stringResource(R.string.image_source_camera),
                    onClick = onCamera,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun ImageSourceRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick, role = Role.Button)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Chip label for shared text: the text's own first line (user data, not UI copy), bounded like a file
 * name. Compose's clipboard exposes no label, so the content is the only honest source of a name.
 */
private fun displayNameOf(text: String): String =
    text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().take(60)
