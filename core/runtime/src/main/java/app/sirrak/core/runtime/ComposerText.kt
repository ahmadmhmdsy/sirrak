package app.sirrak.core.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A one-way request channel from any plugin to the chat composer. M10 dictation emits its transcript
 * here and the composer appends it, so the voice feature never owns or mirrors the text field: adding
 * another producer (a future smart action, a quick-insert chip) is another caller, not another screen.
 *
 * Emission is non-suspending and buffered, so a producer on another plugin's scope never blocks. The
 * only producer today is the composer's own mic, which lives on the screen that collects the flow, so
 * a fragment is never emitted with nobody listening; if a future producer outlives the screen it
 * should keep the last value itself rather than rely on replay.
 */
class ComposerTextRequests {
    private val _requests = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val requests: Flow<String> = _requests.asSharedFlow()

    /** Appends one non-blank fragment; blank input is ignored so a silent transcript adds nothing. */
    fun append(text: String) {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) _requests.tryEmit(trimmed)
    }
}
