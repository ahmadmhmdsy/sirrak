package app.sirrak.core.speech

import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.util.Locale

/**
 * Stable failure codes for speech. They are data, not user-facing English: the UI maps each code to a
 * localised string, so adding a language or rewording a message never touches the pipeline. The strings
 * are stable because the device tests and the build record assert on them.
 */
object SpeechReasons {
    const val IMPORT_EMPTY = "speech-import-empty"
    const val IMPORT_UNSUPPORTED_FILE = "speech-import-unsupported-file"
    const val IMPORT_MISSING_ENCODER = "speech-import-missing-encoder"
    const val IMPORT_MISSING_DECODER = "speech-import-missing-decoder"
    const val IMPORT_MISSING_TOKENS = "speech-import-missing-tokens"
    const val IMPORT_DUPLICATE_ROLE = "speech-import-duplicate-role"
    const val IMPORT_TOO_LARGE = "speech-import-too-large"
    const val IMPORT_FAILED = "speech-import-failed"

    const val NO_MODEL = "speech-no-model"
    const val ENGINE_LOAD_FAILED = "speech-engine-load-failed"
    const val NO_PERMISSION = "speech-no-permission"
    const val RECORDER_UNAVAILABLE = "speech-recorder-unavailable"
    const val RECORDING_FAILED = "speech-recording-failed"
    const val RECORDING_TOO_SHORT = "speech-recording-too-short"
    const val NO_SPEECH = "speech-no-speech"
    const val TRANSCRIPTION_FAILED = "speech-transcription-failed"
}

/**
 * One user-imported speech model. M10 supports the Whisper int8 trio the H3b spike measured
 * (encoder + decoder + tokens); [engineId] keeps the record extensible for a future engine without a
 * schema migration, and every path is app-private so the model is read only by this app.
 */
data class SpeechModel(
    val id: String,
    val displayName: String,
    val engineId: String,
    val encoderPath: String,
    val decoderPath: String,
    val tokensPath: String,
    val sizeBytes: Long,
) {
    /** Human label for the settings row; the UI never hardcodes a size. */
    val sizeLabel: String
        get() {
            val mib = sizeBytes / (1024.0 * 1024.0)
            return if (mib >= 1024.0) {
                String.format(Locale.US, "%.1f GiB", mib / 1024.0)
            } else {
                String.format(Locale.US, "%.1f MiB", mib)
            }
        }
}

/**
 * One file offered to an import. The stream is opened lazily so the picker can describe a selection
 * without copying it; the store decides when bytes are actually written.
 */
data class SpeechModelFile(
    val name: String,
    val sizeBytes: Long,
    val open: () -> InputStream,
)

enum class SpeechFileRole { ENCODER, DECODER, TOKENS }

data class MatchedSpeechFile(val role: SpeechFileRole, val name: String)

sealed interface WhisperLayoutResult {
    data class Matched(val files: List<MatchedSpeechFile>, val baseName: String) : WhisperLayoutResult {
        fun nameFor(role: SpeechFileRole): String? = files.firstOrNull { it.role == role }?.name
    }

    data class Rejected(val reason: String) : WhisperLayoutResult
}

/**
 * The one rule that turns a multi-file picker selection into a Whisper model directory. The rule is
 * data (suffixes and preference), so a new quantisation or naming convention is a change here, not in
 * the store, the UI or the engine.
 */
object WhisperModelLayout {
    private const val ONNX_SUFFIX = ".onnx"
    private const val TOKENS_SUFFIX = "tokens.txt"
    private const val INT8 = "int8"
    private const val ENCODER = "encoder"
    private const val DECODER = "decoder"

    fun match(files: List<SpeechModelFile>): WhisperLayoutResult {
        if (files.isEmpty()) return WhisperLayoutResult.Rejected(SpeechReasons.IMPORT_EMPTY)
        if (files.any { it.sizeBytes > SpeechLimits.MAX_MODEL_FILE_BYTES }) {
            return WhisperLayoutResult.Rejected(SpeechReasons.IMPORT_TOO_LARGE)
        }

        val encoder = choose(files, ENCODER)
            ?: return WhisperLayoutResult.Rejected(
                if (files.none { it.name.contains(ENCODER, ignoreCase = true) }) {
                    SpeechReasons.IMPORT_MISSING_ENCODER
                } else {
                    SpeechReasons.IMPORT_DUPLICATE_ROLE
                },
            )
        val decoder = choose(files, DECODER)
            ?: return WhisperLayoutResult.Rejected(
                if (files.none { it.name.contains(DECODER, ignoreCase = true) }) {
                    SpeechReasons.IMPORT_MISSING_DECODER
                } else {
                    SpeechReasons.IMPORT_DUPLICATE_ROLE
                },
            )
        val tokens = files.filter { it.name.endsWith(TOKENS_SUFFIX, ignoreCase = true) }
        if (tokens.isEmpty()) return WhisperLayoutResult.Rejected(SpeechReasons.IMPORT_MISSING_TOKENS)
        if (tokens.size > 1) return WhisperLayoutResult.Rejected(SpeechReasons.IMPORT_DUPLICATE_ROLE)

        val baseName = inferBaseName(encoder.name) ?: inferBaseName(decoder.name) ?: "whisper"
        return WhisperLayoutResult.Matched(
            files = listOf(
                MatchedSpeechFile(SpeechFileRole.ENCODER, encoder.name),
                MatchedSpeechFile(SpeechFileRole.DECODER, decoder.name),
                MatchedSpeechFile(SpeechFileRole.TOKENS, tokens[0].name),
            ),
            baseName = baseName,
        )
    }

    /** Picks the role file, preferring an int8 variant when both are offered. */
    private fun choose(files: List<SpeechModelFile>, role: String): SpeechModelFile? {
        val candidates = files.filter {
            it.name.contains(role, ignoreCase = true) && it.name.endsWith(ONNX_SUFFIX, ignoreCase = true)
        }
        if (candidates.isEmpty()) return null
        val int8 = candidates.filter { it.name.contains(INT8, ignoreCase = true) }
        val pool = int8.ifEmpty { candidates }
        return if (pool.size == 1) pool[0] else null
    }

    private fun inferBaseName(name: String): String? {
        val index = name.indexOf(ENCODER, ignoreCase = true).let { if (it >= 0) it else name.indexOf(DECODER, ignoreCase = true) }
        if (index <= 0) return null
        return name.substring(0, index).trimEnd('-', '_', '.').takeIf { it.isNotBlank() }
    }
}

/** Carries one stable [SpeechReasons] code out of a failed import so the UI can localise it. */
class SpeechImportException(val reason: String) : Exception(reason)

/**
 * Whisper's decoder always emits *something*. Handed silence or the emulator's synthetic input it
 * produces bracketed sound labels such as "(static)"; M10 device evidence caught exactly that and the
 * composer pasted it. These are not speech, so the controller turns them into the same
 * speech-no-speech failure an empty transcript gets. The set is data: add a label here and every
 * surface inherits the rule. Only whole-transcript bracket/paren labels are listed — a real word like
 * "music" is never filtered, because a user can legitimately dictate it.
 */
object SpeechSilence {

    private val LABELS: Set<String> = setOf(
        "(static)",
        "[static]",
        "(silence)",
        "[silence]",
        "(music)",
        "[music]",
        "(noise)",
        "[noise]",
        "(applause)",
        "[applause]",
        "(blank_audio)",
        "[blank_audio]",
        "[blank]",
        "(inaudible)",
        "[inaudible]",
        "(background noise)",
        "[background noise]",
    )

    /** True when the whole transcript is a decoder artefact rather than something the user said. */
    fun isSilence(text: String): Boolean {
        val normalized = text.trim().lowercase().replace(WHITESPACE, " ")
        if (normalized.isEmpty()) return true
        // Whisper sometimes pads inside the brackets ("( static )"); spacing is not meaningful here.
        return normalized in LABELS || normalized.replace(" ", "") in LABELS_WITHOUT_SPACES
    }

    private val LABELS_WITHOUT_SPACES: Set<String> = LABELS.mapTo(HashSet()) { it.replace(" ", "") }

    private val WHITESPACE = Regex("[\\s\\u00A0]+")
}

/**
 * Durable home for user-imported speech models. The store owns bytes and selection persistence; the
 * controller and the settings UI depend on this seam only, so storage can change without touching them.
 */
interface SpeechModelStore {
    val models: StateFlow<List<SpeechModel>>
    val selected: StateFlow<SpeechModel?>
    fun select(modelId: String?)
    suspend fun import(files: List<SpeechModelFile>): Result<SpeechModel>
    fun delete(model: SpeechModel)
}
