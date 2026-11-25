package app.sirrak.core.models

import java.nio.charset.StandardCharsets

/**
 * What a GGUF header tells us without loading the weights. Every field is nullable because a probe
 * reads a bounded prefix of a remote file: the header of a real 1 GB model is tens of kilobytes, but a
 * pathological tokenizer block can run past the probe budget and must degrade instead of throwing.
 *
 * [kvHeadCounts] is a list because some architectures (notably LFM2) set
 * `attention.head_count_kv` to a per-layer array instead of one number.
 */
data class GgufArchitecture(
    val name: String,
    val layerCount: Int? = null,
    val headCount: Int? = null,
    val kvHeadCounts: List<Int> = emptyList(),
    val embeddingLength: Int? = null,
    val contextTokens: Int? = null,
    /** The Jinja chat template, when the file ships one. This is what lets a raw HF GGUF chat at all. */
    val chatTemplate: String? = null,
    /** GGUF's own total tensor count (sanity signal only). */
    val tensorCount: Long? = null,
) {
    /** KV heads per layer as a single number when the architecture uses one value for all layers. */
    val uniformKvHeadCount: Int? get() = kvHeadCounts.distinct().singleOrNull()

    /** Total KV-cache layers: sum of the per-layer KV head counts, or layers × uniform count. */
    val kvLayerHeads: Long?
        get() = when {
            kvHeadCounts.isEmpty() -> null
            kvHeadCounts.size == 1 -> layerCount?.let { it.toLong() * kvHeadCounts.first() }
            else -> layerCount?.takeIf { it == kvHeadCounts.size }?.let { _ -> kvHeadCounts.sumOf { it.toLong() } }
        }
}

/** The outcome of reading a GGUF header prefix. */
data class GgufMetadata(
    val magicOk: Boolean,
    val version: Int? = null,
    /** Null when the header stopped before the model's architecture block. */
    val architecture: GgufArchitecture? = null,
    /** True when the parser consumed the whole metadata block, i.e. the byte buffer was enough. */
    val complete: Boolean = false,
    val bytesRead: Int = 0,
    /** Stable code for diagnostics (never shown to the user); null on success. */
    val problem: String? = null,
) {
    val usable: Boolean get() = magicOk && architecture != null
}

/**
 * Tolerant GGUF metadata reader (GGUF v2/v3). It parses only the key/value header — enough to name the
 * architecture, size the KV cache and find a chat template — and never trusts the declared sizes: every
 * read is bounds-checked, so a truncated prefix yields a partial [GgufMetadata] instead of an exception.
 *
 * Deliberately not a full GGUF parser: tensor descriptors and data offsets are skipped/never read.
 */
object GgufMetadataReader {

    private const val GGUF_MAGIC = 0x46554747 // "GGUF" little-endian
    private const val MAX_STRING_BYTES = 1 shl 20
    private const val MAX_ARRAY_ITEMS = 4096

    /** Keys worth carrying; everything else is skipped without allocation. */
    private const val KEY_ARCH = "general.architecture"
    private const val KEY_TEMPLATE = "tokenizer.chat_template"
    private const val SUFFIX_LAYERS = ".block_count"
    private const val SUFFIX_HEADS = ".attention.head_count"
    private const val SUFFIX_KV_HEADS = ".attention.head_count_kv"
    private const val SUFFIX_EMBED = ".embedding_length"
    private const val SUFFIX_CONTEXT = ".context_length"

    private const val TYPE_UINT8 = 0
    private const val TYPE_INT8 = 1
    private const val TYPE_UINT16 = 2
    private const val TYPE_INT16 = 3
    private const val TYPE_UINT32 = 4
    private const val TYPE_INT32 = 5
    private const val TYPE_FLOAT32 = 6
    private const val TYPE_BOOL = 7
    private const val TYPE_STRING = 8
    private const val TYPE_ARRAY = 9
    private const val TYPE_UINT64 = 10
    private const val TYPE_INT64 = 11
    private const val TYPE_FLOAT64 = 12

    fun read(bytes: ByteArray): GgufMetadata {
        val cursor = Cursor(bytes)
        if (bytes.size < 8) return GgufMetadata(magicOk = false, problem = "too_short", bytesRead = bytes.size)
        val magic = cursor.u32LE()
        if (magic != GGUF_MAGIC) return GgufMetadata(magicOk = false, problem = "bad_magic", bytesRead = cursor.position)
        // GGUF has always been little-endian; Java's ByteBuffer would help, but the tolerant parser is
        // hand-rolled so every overrun is a value, not an IndexOutOfBoundsException.
        val version = cursor.u32LE()
        if (version != 2 && version != 3) {
            return GgufMetadata(magicOk = true, version = version, problem = "unsupported_version", bytesRead = cursor.position)
        }
        val tensorCount = cursor.u64LE()
        val kvCount = cursor.u64LE()
        if (kvCount < 0 || kvCount > 1_000_000L) {
            return GgufMetadata(magicOk = true, version = version, problem = "implausible_kv_count", bytesRead = cursor.position)
        }

        var archName: String? = null
        val scalars = LinkedHashMap<String, List<Int>>()
        var chatTemplate: String? = null
        var index = 0L
        while (index < kvCount) {
            val key = cursor.string() ?: return partial(bytes, version, tensorCount, index, scalars, chatTemplate, archName)
            val type = cursor.u32LEOrNull() ?: return partial(bytes, version, tensorCount, index, scalars, chatTemplate, archName)
            val captured = when (type) {
                TYPE_STRING -> {
                    val value = cursor.string() ?: return partial(bytes, version, tensorCount, index, scalars, chatTemplate, archName)
                    if (key == KEY_TEMPLATE) chatTemplate = value
                    null
                }
                TYPE_ARRAY -> {
                    val elementType = cursor.u32LEOrNull() ?: return partial(bytes, version, tensorCount, index, scalars, chatTemplate, archName)
                    val length = cursor.u64LEOrNull() ?: return partial(bytes, version, tensorCount, index, scalars, chatTemplate, archName)
                    if (length < 0 || length > MAX_ARRAY_ITEMS) {
                        return partial(bytes, version, tensorCount, index, scalars, chatTemplate, archName, problem = "array_too_large")
                    }
                    if (elementType == TYPE_UINT32 || elementType == TYPE_INT32 ||
                        elementType == TYPE_UINT16 || elementType == TYPE_INT16 ||
                        elementType == TYPE_UINT8 || elementType == TYPE_INT8
                    ) {
                        val values = ArrayList<Int>(length.toInt())
                        var failed = false
                        for (item in 0 until length) {
                            val value = cursor.integer(elementType) ?: run { failed = true; 0 }
                            if (failed) return partial(bytes, version, tensorCount, index, scalars, chatTemplate, archName)
                            values += value
                        }
                        values
                    } else {
                        // Skip any other array type soundly: strings and floats are not needed.
                        var failed = false
                        for (item in 0 until length) {
                            if (!cursor.skipValue(elementType)) { failed = true; break }
                        }
                        if (failed) return partial(bytes, version, tensorCount, index, scalars, chatTemplate, archName)
                        null
                    }
                }
                else -> {
                    val value = cursor.integer(type)
                    if (value == null) {
                        // Non-integer scalar (float/double): consume it so the walk stays aligned.
                        if (!cursor.skipValue(type)) return partial(bytes, version, tensorCount, index, scalars, chatTemplate, archName)
                        null
                    } else if (type != TYPE_BOOL) {
                        listOf(value)
                    } else {
                        null
                    }
                }
            }
            if (captured != null) scalars[key] = captured
            if (key == KEY_ARCH) archName = cursor.lastString
            index++
        }

        val architecture = archName?.let { name -> buildArchitecture(name, scalars, chatTemplate, tensorCount) }
        return GgufMetadata(
            magicOk = true,
            version = version,
            architecture = architecture,
            complete = architecture != null,
            bytesRead = cursor.position,
            problem = if (architecture == null) "no_architecture" else null,
        )
    }

    private fun buildArchitecture(
        name: String,
        scalars: Map<String, List<Int>>,
        chatTemplate: String?,
        tensorCount: Long?,
    ): GgufArchitecture = GgufArchitecture(
        name = name,
        layerCount = scalars[name + SUFFIX_LAYERS]?.firstOrNull(),
        headCount = scalars[name + SUFFIX_HEADS]?.firstOrNull(),
        kvHeadCounts = scalars[name + SUFFIX_KV_HEADS].orEmpty(),
        embeddingLength = scalars[name + SUFFIX_EMBED]?.firstOrNull(),
        contextTokens = scalars[name + SUFFIX_CONTEXT]?.firstOrNull(),
        chatTemplate = chatTemplate,
        tensorCount = tensorCount,
    )

    private fun partial(
        bytes: ByteArray,
        version: Int,
        tensorCount: Long,
        index: Long,
        scalars: Map<String, List<Int>>,
        chatTemplate: String?,
        archName: String?,
        problem: String = "truncated_header",
    ): GgufMetadata {
        val architecture = archName?.let { name -> buildArchitecture(name, scalars, chatTemplate, tensorCount) }
        return GgufMetadata(
            magicOk = true,
            version = version,
            architecture = architecture,
            complete = false,
            bytesRead = bytes.size,
            problem = problem,
        )
    }

    /** A bounds-checked little-endian cursor; every read returns null instead of throwing. */
    private class Cursor(private val bytes: ByteArray) {
        var position: Int = 0
            private set

        var lastString: String? = null
            private set

        fun u32LE(): Int {
            val value = u32LEOrNull() ?: error("truncated u32")
            return value
        }

        fun u32LEOrNull(): Int? {
            if (position + 4 > bytes.size) return null
            val value = (bytes[position].toInt() and 0xFF) or
                ((bytes[position + 1].toInt() and 0xFF) shl 8) or
                ((bytes[position + 2].toInt() and 0xFF) shl 16) or
                ((bytes[position + 3].toInt() and 0xFF) shl 24)
            position += 4
            return value
        }

        fun u64LE(): Long = u64LEOrNull() ?: error("truncated u64")

        fun u64LEOrNull(): Long? {
            if (position + 8 > bytes.size) return null
            var value = 0L
            for (shift in 0 until 8) {
                value = value or ((bytes[position + shift].toLong() and 0xFF) shl (8 * shift))
            }
            position += 8
            return value
        }

        fun string(): String? {
            val length = u64LEOrNull() ?: return null
            if (length < 0 || length > MAX_STRING_BYTES) return null
            val count = length.toInt()
            if (position + count > bytes.size) return null
            val value = String(bytes, position, count, StandardCharsets.UTF_8)
            position += count
            lastString = value
            return value
        }

        fun integer(type: Int): Int? {
            // Floats and booleans are never used as model facts; refuse before consuming so the
            // caller's skipValue() owns the single, aligned advance.
            if (type == TYPE_FLOAT32 || type == TYPE_FLOAT64 || type == TYPE_BOOL) return null
            val size = sizeOf(type) ?: return null
            if (position + size > bytes.size) return null
            var value = 0L
            for (offset in 0 until size) {
                value = value or ((bytes[position + offset].toLong() and 0xFF) shl (8 * offset))
            }
            position += size
            return when (type) {
                TYPE_UINT8, TYPE_UINT16, TYPE_UINT32 -> value.toInt()
                TYPE_INT8 -> value.toByte().toInt()
                TYPE_INT16 -> value.toShort().toInt()
                TYPE_UINT64 -> value.toInt()
                TYPE_INT32 -> value.toInt()
                TYPE_INT64 -> value.toInt()
                else -> null
            }
        }

        fun skipValue(type: Int): Boolean {
            val size = sizeOf(type) ?: return false
            if (position + size > bytes.size) return false
            position += size
            return true
        }

        private fun sizeOf(type: Int): Int? = when (type) {
            TYPE_UINT8, TYPE_INT8, TYPE_BOOL -> 1
            TYPE_UINT16, TYPE_INT16 -> 2
            TYPE_UINT32, TYPE_INT32, TYPE_FLOAT32 -> 4
            TYPE_UINT64, TYPE_INT64, TYPE_FLOAT64 -> 8
            else -> null
        }
    }
}
