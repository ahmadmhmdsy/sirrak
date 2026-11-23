package app.sirrak.core.runtime

/**
 * M9: one image handed to an image-capable engine. The bytes are already decoded, EXIF-oriented and
 * re-encoded by an [ImagePreparer] (bounded pixel size, bounded bytes), so the native layer never
 * receives a 12 MP camera original and the prompt stays predictable.
 */
class ImageInput(
    val bytes: ByteArray,
    val mimeType: String,
) {
    val sizeBytes: Long get() = bytes.size.toLong()
}

/**
 * Turns attached image bytes into an engine-ready [ImageInput]. The Android implementation lives in
 * :app (Bitmap + EXIF); a fake keeps ChatStore and the engine unit-testable. Null means the image
 * cannot be prepared (corrupt, unsupported codec), which the chat surface reports as a failed turn
 * instead of silently sending the question without the image.
 */
fun interface ImagePreparer {
    fun prepare(bytes: ByteArray, mimeType: String): ImageInput?
}

/**
 * M9 pairing convention between a text model and its multimodal projector. Pure name logic so the
 * model store, the settings UI and tests agree on one rule; a store that keeps files elsewhere can
 * reuse the same convention without copying it.
 */
object VisionProjector {
    /** Projector files end with this suffix and are never listed as models. */
    const val SUFFIX = ".mmproj.gguf"

    /** "qwen2.5-0.5b.gguf" -> "qwen2.5-0.5b.mmproj.gguf" (case preserved). */
    fun fileNameFor(modelFileName: String): String {
        val base = modelFileName.substringBeforeLast('.', modelFileName)
        return base + SUFFIX
    }

    fun isProjector(fileName: String): Boolean = fileName.endsWith(SUFFIX, ignoreCase = true)

    /** The model base a projector file name belongs to, or null when the name is not a projector. */
    fun modelBase(projectorFileName: String): String? =
        if (isProjector(projectorFileName)) {
            projectorFileName.dropLast(SUFFIX.length)
        } else {
            null
        }
}

/**
 * Pure image-sizing policy shared by the Android preparer and its unit tests. Two rules keep a 12 MP
 * attachment from OOMing on a low-RAM phone (VIS-3):
 *  - decode with the smallest power-of-two sample that still meets [maxDimension], so a 4000 px
 *    original is decoded at 2000 px instead of allocating a 48 MB ARGB buffer;
 *  - scale the decoded bitmap down to [maxDimension] only when it is still larger.
 */
object ImageSizing {

    /** The largest power-of-two sample size whose decoded longest side is still >= [maxDimension]. */
    fun sampleSize(width: Int, height: Int, maxDimension: Int): Int {
        val longest = maxOf(width, height)
        if (longest <= 0 || maxDimension <= 0) return 1
        var sample = 1
        while (longest / sample > maxDimension && sample <= Int.MAX_VALUE / 2) {
            sample *= 2
        }
        return sample
    }

    /** Target size after downscaling; the longer side becomes [maxDimension] and the ratio is kept. */
    fun targetSize(width: Int, height: Int, maxDimension: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 0 to 0
        val longest = maxOf(width, height)
        if (longest <= maxDimension || maxDimension <= 0) return width to height
        val scale = maxDimension.toDouble() / longest
        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (height * scale).toInt().coerceAtLeast(1)
        return targetWidth to targetHeight
    }
}
