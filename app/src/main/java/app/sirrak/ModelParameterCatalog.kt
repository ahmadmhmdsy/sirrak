package app.sirrak

import androidx.annotation.StringRes
import app.sirrak.core.runtime.GenerationParams
import app.sirrak.core.runtime.GenerationRanges
import java.util.Locale
import kotlin.math.roundToInt

/**
 * UI metadata for every numeric generation parameter: one data row per field, so a new parameter is
 * one entry here (plus a string) rather than a new composable. [id] is the persisted field id that
 * [AppModelControlService.saveParams] records in touchedParams; it must match the JSON name used by
 * the runtime so overrides round-trip through model_settings.json.
 *
 * Ranges are read from [GenerationRanges], the single source of truth shared with the JSON clamp and
 * the native bridge: the UI cannot drift from what the runtime will accept.
 */
data class ModelParameterSpec(
    val id: String,
    @StringRes val labelRes: Int,
    val min: Float,
    val max: Float,
    /** Slider increment; also the number of decimals shown in the value. */
    val step: Float,
    val integer: Boolean = false,
    /** Advanced parameters are hidden behind the Simple/Advanced switch in Settings > Model. */
    val advanced: Boolean = false,
    val get: (GenerationParams) -> Float,
    val set: (GenerationParams, Float) -> GenerationParams,
) {
    val steps: Int get() = (((max - min) / step).roundToInt() - 1).coerceAtLeast(0)

    val decimals: Int
        get() = when {
            integer -> 0
            step >= 1f -> 0
            step >= 0.1f -> 1
            else -> 2
        }

    fun format(value: Float): String =
        if (integer) value.roundToInt().toString() else String.format(Locale.US, "%.${decimals}f", value)

    /** Slider values snap to [step] so a 0.05 step never records 0.70000004. */
    fun snap(value: Float): Float {
        val snapped = (min + ((value - min) / step).roundToInt() * step).coerceIn(min, max)
        return if (integer) snapped.roundToInt().toFloat() else snapped
    }
}

object ModelParameters {

    val ALL: List<ModelParameterSpec> = listOf(
        ModelParameterSpec(
            id = "temperature", labelRes = R.string.param_temperature,
            min = GenerationRanges.temperature.start, max = GenerationRanges.temperature.endInclusive, step = 0.05f,
            get = { it.temperature }, set = { p, v -> p.copy(temperature = v) },
        ),
        ModelParameterSpec(
            id = "topP", labelRes = R.string.param_top_p,
            min = GenerationRanges.topP.start, max = GenerationRanges.topP.endInclusive, step = 0.05f,
            get = { it.topP }, set = { p, v -> p.copy(topP = v) },
        ),
        ModelParameterSpec(
            id = "topK", labelRes = R.string.param_top_k,
            min = GenerationRanges.topK.first.toFloat(), max = GenerationRanges.topK.last.toFloat(), step = 1f, integer = true,
            get = { it.topK.toFloat() }, set = { p, v -> p.copy(topK = v.roundToInt()) },
        ),
        ModelParameterSpec(
            id = "maxTokens", labelRes = R.string.param_max_tokens,
            min = GenerationRanges.maxTokens.first.toFloat(), max = GenerationRanges.maxTokens.last.toFloat(), step = 16f, integer = true,
            get = { it.maxTokens.toFloat() }, set = { p, v -> p.copy(maxTokens = v.roundToInt()) },
        ),
        ModelParameterSpec(
            id = "repeatPenalty", labelRes = R.string.param_repeat_penalty,
            min = GenerationRanges.repeatPenalty.start, max = GenerationRanges.repeatPenalty.endInclusive, step = 0.01f,
            get = { it.repeatPenalty }, set = { p, v -> p.copy(repeatPenalty = v) },
        ),
        ModelParameterSpec(
            id = "repeatLastN", labelRes = R.string.param_repeat_last_n,
            min = GenerationRanges.repeatLastN.first.toFloat(), max = GenerationRanges.repeatLastN.last.toFloat(), step = 1f, integer = true, advanced = true,
            get = { it.repeatLastN.toFloat() }, set = { p, v -> p.copy(repeatLastN = v.roundToInt()) },
        ),
        ModelParameterSpec(
            id = "presencePenalty", labelRes = R.string.param_presence_penalty,
            min = GenerationRanges.presencePenalty.start, max = GenerationRanges.presencePenalty.endInclusive, step = 0.01f, advanced = true,
            get = { it.presencePenalty }, set = { p, v -> p.copy(presencePenalty = v) },
        ),
        ModelParameterSpec(
            id = "frequencyPenalty", labelRes = R.string.param_frequency_penalty,
            min = GenerationRanges.frequencyPenalty.start, max = GenerationRanges.frequencyPenalty.endInclusive, step = 0.01f, advanced = true,
            get = { it.frequencyPenalty }, set = { p, v -> p.copy(frequencyPenalty = v) },
        ),
        ModelParameterSpec(
            id = "minP", labelRes = R.string.param_min_p,
            min = GenerationRanges.minP.start, max = GenerationRanges.minP.endInclusive, step = 0.01f, advanced = true,
            get = { it.minP }, set = { p, v -> p.copy(minP = v) },
        ),
        ModelParameterSpec(
            id = "typicalP", labelRes = R.string.param_typical_p,
            min = GenerationRanges.typicalP.start, max = GenerationRanges.typicalP.endInclusive, step = 0.01f, advanced = true,
            get = { it.typicalP }, set = { p, v -> p.copy(typicalP = v) },
        ),
        ModelParameterSpec(
            id = "mirostatTau", labelRes = R.string.param_mirostat_tau,
            min = GenerationRanges.mirostatTau.start, max = GenerationRanges.mirostatTau.endInclusive, step = 0.1f, advanced = true,
            get = { it.mirostatTau }, set = { p, v -> p.copy(mirostatTau = v) },
        ),
        ModelParameterSpec(
            id = "mirostatEta", labelRes = R.string.param_mirostat_eta,
            min = GenerationRanges.mirostatEta.start, max = GenerationRanges.mirostatEta.endInclusive, step = 0.01f, advanced = true,
            get = { it.mirostatEta }, set = { p, v -> p.copy(mirostatEta = v) },
        ),
        ModelParameterSpec(
            id = "xtcProbability", labelRes = R.string.param_xtc_probability,
            min = GenerationRanges.xtcProbability.start, max = GenerationRanges.xtcProbability.endInclusive, step = 0.01f, advanced = true,
            get = { it.xtcProbability }, set = { p, v -> p.copy(xtcProbability = v) },
        ),
        ModelParameterSpec(
            id = "xtcThreshold", labelRes = R.string.param_xtc_threshold,
            min = GenerationRanges.xtcThreshold.start, max = GenerationRanges.xtcThreshold.endInclusive, step = 0.01f, advanced = true,
            get = { it.xtcThreshold }, set = { p, v -> p.copy(xtcThreshold = v) },
        ),
        ModelParameterSpec(
            id = "dryMultiplier", labelRes = R.string.param_dry_multiplier,
            min = GenerationRanges.dryMultiplier.start, max = GenerationRanges.dryMultiplier.endInclusive, step = 0.1f, advanced = true,
            get = { it.dryMultiplier }, set = { p, v -> p.copy(dryMultiplier = v) },
        ),
        ModelParameterSpec(
            id = "topNSigma", labelRes = R.string.param_top_n_sigma,
            min = GenerationRanges.topNSigma.start, max = GenerationRanges.topNSigma.endInclusive, step = 0.1f, advanced = true,
            get = { it.topNSigma }, set = { p, v -> p.copy(topNSigma = v) },
        ),
    )

    private val byId = ALL.associateBy { it.id }

    val simple: List<ModelParameterSpec> get() = ALL.filterNot { it.advanced }

    val advancedOnly: List<ModelParameterSpec> get() = ALL.filter { it.advanced }

    fun spec(id: String): ModelParameterSpec? = byId[id]
}