package app.sirrak.core.models

/**
 * A measured snapshot of the device. [totalRamMb] is what Android reports as total memory,
 * [availableRamMb] is what is actually free right now (the number that decides whether a load is
 * likely to survive), and [memoryThresholdMb] is the low-memory watermark below which the platform
 * starts killing processes. A fit prediction that ignores the watermark is optimistic.
 *
 * [availableStorageBytes] is measured on the volume that will hold the file, not on the data partition
 * in general; [isLowRamDevice] and [sdkInt] let the engine refuse configurations that no amount of
 * free space will make safe.
 */
data class DeviceCapacity(
    val totalRamMb: Int,
    val availableRamMb: Int,
    val memoryThresholdMb: Int,
    val isLowRamDevice: Boolean,
    val availableStorageBytes: Long,
    val sdkInt: Int,
    val abis: List<String>,
) {
    /** RAM the app may use before the platform starts reclaiming, with two watermarks of headroom. */
    val usableRamMb: Int get() = (availableRamMb - memoryThresholdMb * 2).coerceAtLeast(0)

    val arm64: Boolean get() = abis.any { it.startsWith("arm64") }

    companion object {
        fun unknown(): DeviceCapacity = DeviceCapacity(
            totalRamMb = 0,
            availableRamMb = 0,
            memoryThresholdMb = 0,
            isLowRamDevice = false,
            availableStorageBytes = 0L,
            sdkInt = 0,
            abis = emptyList(),
        )
    }
}

/** What the catalogue and the GGUF header together know about a model's cost. */
data class ModelFootprint(
    val fileBytes: Long,
    val kvBytes: Long,
    val overheadBytes: Long,
) {
    val residentBytes: Long get() = fileBytes + overheadBytes
    val totalBytes: Long get() = residentBytes + kvBytes
}

enum class FitStatus {
    /** Everything fits with headroom. */
    RUNS,

    /** Loads, but the device is close to its limit: another app may evict it mid-chat. */
    TIGHT,

    /** Predictably fails: not enough memory or disk, or an architecture this build cannot run. */
    WONT_RUN,

    /** The header did not answer (no architecture, unknown file). "Not verified", never "runs". */
    UNKNOWN,
}

/** Stable reason codes the UI maps to localised text; never user-visible strings. */
object FitReasons {
    const val LOW_RAM_DEVICE = "low_ram_device"
    const val RAM_INSUFFICIENT = "ram_insufficient"
    const val RAM_TIGHT = "ram_tight"
    const val DISK_INSUFFICIENT = "disk_insufficient"
    const val ARCHITECTURE_UNSUPPORTED = "architecture_unsupported"
    const val ARCHITECTURE_UNKNOWN = "architecture_unknown"
    const val FILE_UNKNOWN = "file_unknown"
}

/** A fit verdict with the arithmetic that produced it, so the UI can explain itself honestly. */
data class FitVerdict(
    val status: FitStatus,
    val footprint: ModelFootprint,
    val usableRamMb: Int,
    val freeDiskBytes: Long,
    /** Largest context on the ladder that still fits the budget, clamped to the trained window. */
    val suggestedContext: Int?,
    val requestedContext: Int,
    val reasons: List<String>,
) {
    val blocked: Boolean get() = status == FitStatus.WONT_RUN
    val needsConfirmation: Boolean get() = status == FitStatus.TIGHT
}

/**
 * The memory model, kept in one pure place so every screen (Library card, HF verdict, model page,
 * download gate) agrees. Verified arithmetic from the probe round: weights are the file bytes, the KV
 * cache is `2 × layers × kvHeads × headDim × context × 2 bytes`, and a fixed overhead covers the
 * runtime's own buffers. That overhead is a measured estimate, never a promise: a TIGHT verdict is
 * "may be killed", not "will work".
 */
class ModelFitEngine(
    private val overheadBytes: Long = DEFAULT_OVERHEAD_BYTES,
    private val diskMarginFactor: Double = DEFAULT_DISK_MARGIN,
    private val tightFraction: Double = DEFAULT_TIGHT_FRACTION,
    private val supportedArchitectures: Set<String> = DEFAULT_ARCHITECTURES,
) {

    fun evaluate(
        fileBytes: Long,
        architecture: GgufArchitecture?,
        requestedContext: Int,
        capacity: DeviceCapacity,
        freeDiskBytes: Long = capacity.availableStorageBytes,
    ): FitVerdict {
        val reasons = mutableListOf<String>()
        val archName = architecture?.name
        val supported = when {
            archName == null -> {
                reasons += FitReasons.ARCHITECTURE_UNKNOWN
                false
            }
            archName !in supportedArchitectures -> {
                reasons += FitReasons.ARCHITECTURE_UNSUPPORTED
                false
            }
            else -> true
        }

        val trainedContext = architecture?.contextTokens?.takeIf { it > 0 }
        val context = (requestedContext.takeIf { it > 0 } ?: trainedContext ?: DEFAULT_CONTEXT)
        val footprint = ModelFootprint(
            fileBytes = fileBytes,
            kvBytes = kvBytes(architecture, context),
            overheadBytes = overheadBytes,
        )

        // RAM. A low-RAM device cannot mmap a large model without being killed, whatever the averages
        // look like; a device report with no usable headroom is treated as a hard no, not a guess.
        if (capacity.isLowRamDevice && fileBytes > LOW_RAM_MODEL_LIMIT_BYTES) {
            reasons += FitReasons.LOW_RAM_DEVICE
        }
        val usable = capacity.usableRamMb
        val neededMb = footprint.totalBytes / MB
        when {
            capacity.totalRamMb <= 0 -> Unit // no measurement: fall through to UNKNOWN
            usable <= 0 -> reasons += FitReasons.RAM_INSUFFICIENT
            neededMb > usable -> reasons += FitReasons.RAM_INSUFFICIENT
            neededMb > (usable * tightFraction).toLong() -> reasons += FitReasons.RAM_TIGHT
        }

        if (fileBytes > 0 && freeDiskBytes > 0 && freeDiskBytes < (fileBytes * diskMarginFactor).toLong()) {
            reasons += FitReasons.DISK_INSUFFICIENT
        }
        if (fileBytes <= 0) reasons += FitReasons.FILE_UNKNOWN

        val status = when {
            reasons.any { it in BLOCKING_REASONS } -> FitStatus.WONT_RUN
            reasons.contains(FitReasons.RAM_TIGHT) -> FitStatus.TIGHT
            reasons.isEmpty() -> FitStatus.RUNS
            else -> FitStatus.UNKNOWN
        }

        val suggested = if (supported && capacity.totalRamMb > 0) {
            suggestContext(fileBytes, architecture, trainedContext, usable)
        } else {
            null
        }
        return FitVerdict(
            status = status,
            footprint = footprint,
            usableRamMb = usable,
            freeDiskBytes = freeDiskBytes,
            suggestedContext = suggested,
            requestedContext = context,
            reasons = reasons.toList(),
        )
    }

    /**
     * The largest rung of [CONTEXT_LADDER] (never above the trained window) whose KV cache still keeps
     * the whole footprint inside [tightFraction] of the usable RAM budget.
     */
    fun suggestContext(
        fileBytes: Long,
        architecture: GgufArchitecture?,
        trainedContext: Int?,
        usableRamMb: Int,
        ladder: List<Int> = CONTEXT_LADDER,
    ): Int? {
        if (usableRamMb <= 0) return null
        val maxTrained = trainedContext ?: Int.MAX_VALUE
        val budgetBytes = (usableRamMb * tightFraction * MB).toLong()
        val candidates = ladder.filter { it <= maxTrained }
        return candidates.asReversed().firstOrNull { candidate ->
            val kv = kvBytes(architecture, candidate)
            fileBytes + overheadBytes + kv <= budgetBytes
        }
    }

    /** KV cache bytes for [context] tokens; 0 when the header lacks the numbers to compute it. */
    fun kvBytes(architecture: GgufArchitecture?, context: Int): Long {
        if (architecture == null || context <= 0) return 0L
        val heads = architecture.headCount?.takeIf { it > 0 } ?: return 0L
        val embedding = architecture.embeddingLength?.takeIf { it > 0 } ?: return 0L
        val kvHeads = architecture.kvLayerHeads ?: return 0L
        val headDim = embedding / heads
        // 2 tensors (K and V) × 2 bytes (fp16 cache) × context × (layers × kv heads) × head dim.
        return 2L * 2L * context.toLong() * kvHeads * headDim
    }

    companion object {
        const val MB = 1024L * 1024L
        const val DEFAULT_OVERHEAD_BYTES = 300L * MB
        const val DEFAULT_DISK_MARGIN = 1.1
        const val DEFAULT_TIGHT_FRACTION = 0.9
        const val DEFAULT_CONTEXT = 2048
        const val LOW_RAM_MODEL_LIMIT_BYTES = 1024L * MB

        val CONTEXT_LADDER = listOf(512, 1024, 2048, 4096, 8192, 16384)

        /** Reasons that block a download/load outright. UNKNOWN reasons never block. */
        val BLOCKING_REASONS = setOf(
            FitReasons.LOW_RAM_DEVICE,
            FitReasons.RAM_INSUFFICIENT,
            FitReasons.DISK_INSUFFICIENT,
            FitReasons.ARCHITECTURE_UNSUPPORTED,
        )

        /** llama.cpp architectures this build's engine ships a parser for. */
        val DEFAULT_ARCHITECTURES = setOf(
            "llama", "qwen2", "qwen3", "qwen3moe", "lfm2", "smollm", "smollm3", "phi3", "gemma3",
            "gemma2", "mistral", "deepseek2", "command-r", "minicpm3", "olmo2", "granite", "cohere2",
        )
    }
}
