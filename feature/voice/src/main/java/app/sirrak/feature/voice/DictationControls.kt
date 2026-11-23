package app.sirrak.feature.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sirrak.core.speech.DictationController
import app.sirrak.core.speech.DictationMode
import app.sirrak.core.speech.DictationState
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * The mic control for the composer. It owns the runtime permission flow (rationale first, request
 * once, typed failure when declined) and both interaction modes:
 *
 *  - TOGGLE: tap starts, tap again stops and transcribes;
 *  - HOLD: press starts, release stops — the walkie-talkie gesture.
 *
 * It never records by itself: every action goes through [DictationController], so permission, model
 * and engine failures arrive as the same typed state the status strip renders.
 */
@Composable
fun DictationMicButton(
    controller: DictationController,
    mode: DictationMode,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val state by controller.state.collectAsStateWithLifecycle()
    var showRationale by rememberSaveable { mutableStateOf(false) }
    var requestedOnce by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        requestedOnce = true
        if (granted) controller.start() else controller.onPermissionDenied()
    }

    fun begin() {
        if (hasRecordPermission(context)) {
            controller.start()
            return
        }
        if (requestedOnce) permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) else showRationale = true
    }

    fun activate() {
        when (controller.state.value) {
            is DictationState.Recording -> controller.stop()
            is DictationState.Transcribing -> Unit
            else -> begin()
        }
    }

    val recording = state is DictationState.Recording
    val transcribing = state is DictationState.Transcribing
    val gestureModifier = if (mode == DictationMode.HOLD) {
        Modifier.pointerInput(mode) {
            detectTapGestures(
                onPress = {
                    if (controller.state.value !is DictationState.Recording) begin()
                    tryAwaitRelease()
                    if (controller.state.value is DictationState.Recording) controller.stop()
                },
            )
        }
    } else {
        Modifier.clickable(enabled = !transcribing) { activate() }
    }
    val description = stringResource(
        if (recording) R.string.dictation_mic_stop else R.string.dictation_mic_start,
    )

    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(
                if (recording) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
            )
            .then(gestureModifier)
            .semantics {
                contentDescription = description
                role = Role.Button
                // HOLD has no clickable modifier to publish the action, so the accessibility action is
                // declared here; TOGGLE already gets one from clickable and would otherwise duplicate.
                if (mode == DictationMode.HOLD) {
                    onClick(label = description) {
                        activate()
                        true
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (transcribing) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                imageVector = if (recording) Icons.Outlined.Stop else Icons.Outlined.Mic,
                contentDescription = null,
                tint = if (recording) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }

    if (showRationale) {
        AlertDialog(
            onDismissRequest = {
                showRationale = false
                controller.onPermissionDenied()
            },
            title = { Text(stringResource(R.string.dictation_permission_title)) },
            text = { Text(stringResource(R.string.dictation_permission_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRationale = false
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                ) { Text(stringResource(R.string.dictation_permission_allow)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showRationale = false
                        controller.onPermissionDenied()
                    },
                ) { Text(stringResource(R.string.dictation_permission_not_now)) }
            },
        )
    }
}

/**
 * The contributed row above the composer: recording with a live timer, transcribing with a spinner, or
 * the localised failure with a dismiss action. Idle renders nothing, so the composer keeps its height
 * when dictation is not in use.
 */
@Composable
fun DictationStatusRow(
    controller: DictationController,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    when (val current = state) {
        is DictationState.Idle -> Unit
        is DictationState.Recording -> RecordingStrip(
            startedAtMs = current.startedAtMs,
            onStop = controller::stop,
            onCancel = controller::cancel,
            modifier = modifier,
        )
        is DictationState.Transcribing -> StatusStrip(
            icon = { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) },
            text = stringResource(R.string.dictation_transcribing),
            container = MaterialTheme.colorScheme.surfaceContainerHigh,
            content = MaterialTheme.colorScheme.onSurface,
            modifier = modifier,
        )
        is DictationState.Failed -> FailureStrip(
            reason = current.reason,
            onDismiss = controller::clearFailure,
            modifier = modifier,
        )
    }
}

@Composable
private fun RecordingStrip(
    startedAtMs: Long,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAtMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(TIMER_TICK_MS)
        }
    }
    val elapsedSeconds = ((now - startedAtMs).coerceAtLeast(0L) / 1000L)
    val elapsed = String.format(Locale.US, "%d:%02d", elapsedSeconds / 60, elapsedSeconds % 60)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.GraphicEq,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.dictation_listening),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = elapsed,
                // A clock is LTR even inside the RTL interface, or "0:07" renders as "7:0".
                style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Ltr),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            TextButton(onClick = onStop) {
                Text(stringResource(R.string.dictation_stop))
            }
            IconButton(onClick = onCancel) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.dictation_cancel),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun StatusStrip(
    icon: @Composable () -> Unit,
    text: String,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = container,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Spacer(Modifier.width(10.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = content,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FailureStrip(
    reason: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = speechReasonLabel(reason),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dictation_dismiss))
            }
        }
    }
}

private fun hasRecordPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

private const val TIMER_TICK_MS = 250L
