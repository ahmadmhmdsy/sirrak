package app.sirrak

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import app.sirrak.core.runtime.AttachmentLimits
import app.sirrak.core.runtime.ImageInput
import app.sirrak.core.runtime.ImagePreparer
import app.sirrak.core.runtime.ImageSizing
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * M9: turns an attached image into a bounded, EXIF-correct JPEG for the vision projector.
 *
 * Why re-encode at all: a 12 MP camera JPEG is ~48 MB as an ARGB bitmap and the projector resizes it
 * to a few hundred pixels anyway. This decodes with [ImageSizing.sampleSize], rotates by EXIF, scales
 * to [AttachmentLimits.imageDimension] and compresses to JPEG, so peak memory stays at roughly two
 * bounded buffers instead of the full camera frame (VIS-3: no OOM on 12 MP).
 *
 * Android-specific on purpose: the pure sizing policy lives in :core:runtime and is unit-tested; this
 * class is the thin Bitmap wrapper the app composes.
 */
class AndroidImagePreparer(
    private val maxDimension: Int = AttachmentLimits.Default.imageDimension,
    private val quality: Int = DEFAULT_JPEG_QUALITY,
) : ImagePreparer {

    override fun prepare(bytes: ByteArray, mimeType: String): ImageInput? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val sample = ImageSizing.sampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
        var bitmap = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return null

        try {
            val oriented = applyExifOrientation(bytes, bitmap)
            if (oriented !== bitmap) {
                bitmap.recycle()
                bitmap = oriented
            }
            val (targetWidth, targetHeight) = ImageSizing.targetSize(bitmap.width, bitmap.height, maxDimension)
            if (targetWidth != bitmap.width || targetHeight != bitmap.height) {
                val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
                if (scaled !== bitmap) {
                    bitmap.recycle()
                    bitmap = scaled
                }
            }
            val output = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) return null
            return ImageInput(output.toByteArray(), JPEG_MIME_TYPE)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /** Applies the camera's EXIF orientation; unknown or normal images are returned unchanged. */
    private fun applyExifOrientation(bytes: ByteArray, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    companion object {
        /** JPEG is what stb_image (inside libmtmd) decodes fastest and every vision model expects. */
        const val JPEG_MIME_TYPE = "image/jpeg"
        const val DEFAULT_JPEG_QUALITY = 85
    }
}
