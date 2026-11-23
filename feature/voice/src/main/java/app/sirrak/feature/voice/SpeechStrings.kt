package app.sirrak.feature.voice

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.sirrak.core.speech.SpeechReasons

/**
 * Stable speech/import reason codes -> localised sentences. One mapping for every surface (mic strip,
 * settings card, future onboarding), so a new engine failure is one line here and no screen can drift.
 */
@StringRes
fun speechReasonRes(reason: String?): Int = when (reason) {
    SpeechReasons.NO_MODEL -> R.string.dictation_reason_no_model
    SpeechReasons.ENGINE_LOAD_FAILED -> R.string.dictation_reason_engine_load
    SpeechReasons.NO_PERMISSION -> R.string.dictation_reason_no_permission
    SpeechReasons.RECORDER_UNAVAILABLE -> R.string.dictation_reason_recorder_unavailable
    SpeechReasons.RECORDING_FAILED -> R.string.dictation_reason_recording_failed
    SpeechReasons.RECORDING_TOO_SHORT -> R.string.dictation_reason_too_short
    SpeechReasons.NO_SPEECH -> R.string.dictation_reason_no_speech
    SpeechReasons.TRANSCRIPTION_FAILED -> R.string.dictation_reason_transcription_failed
    SpeechReasons.IMPORT_EMPTY -> R.string.dictation_reason_import_empty
    SpeechReasons.IMPORT_UNSUPPORTED_FILE -> R.string.dictation_reason_unsupported
    SpeechReasons.IMPORT_MISSING_ENCODER -> R.string.dictation_reason_missing_encoder
    SpeechReasons.IMPORT_MISSING_DECODER -> R.string.dictation_reason_missing_decoder
    SpeechReasons.IMPORT_MISSING_TOKENS -> R.string.dictation_reason_missing_tokens
    SpeechReasons.IMPORT_DUPLICATE_ROLE -> R.string.dictation_reason_duplicate_role
    SpeechReasons.IMPORT_TOO_LARGE -> R.string.dictation_reason_too_large
    else -> R.string.dictation_reason_generic
}

/** Compose convenience so a screen never hardcodes a speech error sentence. */
@Composable
fun speechReasonLabel(reason: String?): String = stringResource(speechReasonRes(reason))
