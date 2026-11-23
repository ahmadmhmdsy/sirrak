package app.sirrak

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.sirrak.core.runtime.EngineChunk
import app.sirrak.core.runtime.EngineStatus
import app.sirrak.core.runtime.GenerationOptions
import app.sirrak.core.runtime.GenerationParams
import app.sirrak.core.runtime.ImageInput
import app.sirrak.core.runtime.InstalledModel
import app.sirrak.core.runtime.VisionProjector
import app.sirrak.engine.llamacpp.LlamaCppEngine
import app.sirrak.engine.llamacpp.LlamaProjector
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M9 device evidence: the image preparer bounds a 12 MP camera frame and applies EXIF orientation, a
 * real SmolVLM mmproj reports vision support, and the loaded engine answers a question about pixels
 * instead of claiming it cannot see. Large models are staged from /sdcard/Download like the M8 suites,
 * so a Gradle uninstall does not force a re-push per run; the model-store test uses tiny synthetic
 * GGUF headers and needs no big files at all.
 */
@RunWith(AndroidJUnit4::class)
class VisionDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun imagePreparerDownscalesA12MegapixelPhotoWithoutOom() {
        val raw = largeJpeg(width = 4000, height = 3000)
        Log.i(TAG, "12MP source bytes=" + raw.size)

        val prepared = AndroidImagePreparer().prepare(raw, "image/jpeg")
        assertNotNull("the preparer refused a valid JPEG", prepared)
        prepared!!

        assertEquals("image/jpeg", prepared.mimeType)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(prepared.bytes, 0, prepared.bytes.size, bounds)
        assertTrue("prepared width " + bounds.outWidth, bounds.outWidth <= 2048)
        assertTrue("prepared height " + bounds.outHeight, bounds.outHeight <= 2048)
        assertTrue("re-encode did not shrink a 12MP frame", prepared.sizeBytes < raw.size)
        Log.i(TAG, "12MP prepared bytes=" + prepared.sizeBytes + " size=" + bounds.outWidth + "x" + bounds.outHeight)
    }

    @Test
    fun imagePreparerAppliesExifOrientation() {
        val raw = smallJpeg(width = 120, height = 60)
        val file = File(context.cacheDir, "exif-orientation.jpg")
        file.writeBytes(raw)
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val withExif = file.readBytes()
        file.delete()

        val prepared = AndroidImagePreparer().prepare(withExif, "image/jpeg")
        assertNotNull(prepared)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(prepared!!.bytes, 0, prepared.bytes.size, bounds)
        // A 120x60 photo rotated 90 degrees is 60x120: the model must see the upright picture.
        assertEquals(60, bounds.outWidth)
        assertEquals(120, bounds.outHeight)
    }

    @Test
    fun projectorInspectionReportsVisionForSmolVlmAndRejectsATextModel() {
        val projector = stageModel(PROJECTOR_FILE)
        assumeTrue("Push " + PROJECTOR_FILE + " to /sdcard/Download/ first", projector.isFile)
        assertTrue("staged projector is not readable by the app: " + projector, projector.canRead())

        val caps = LlamaProjector.inspect(projector.absolutePath)
        assertNotNull("the SmolVLM projector was not recognised as mtmd", caps)
        assertTrue("SmolVLM projector does not report vision: " + caps, caps!!.vision)
        Log.i(TAG, "projector caps=" + caps)

        val textModel = stageModel(TEXT_MODEL_FILE)
        if (textModel.isFile) {
            val textCaps = LlamaProjector.inspect(textModel.absolutePath)
            assertTrue("a text-only GGUF must not be treated as a vision projector: " + textCaps, textCaps?.vision != true)
        }
    }

    @Test
    fun fileModelStorePairsProjectorWithoutListingItAsAModel() = runBlocking {
        val store = FileModelStore(context)
        val modelName = "vision-test-model.gguf"
        val modelBytes = minimalGguf()
        val imported = store.import(modelName) { ByteArrayInputStream(modelBytes) }
        assertTrue("synthetic model import failed: " + imported, imported.isSuccess)
        val model = imported.getOrThrow()
        assertNull("a fresh model must not have a projector", model.visionProjectorPath)

        val attached = store.attachVisionProjector(model) { ByteArrayInputStream(minimalGguf()) }
        assertTrue("projector attach failed: " + attached, attached.isSuccess)
        val withProjector = attached.getOrThrow()
        assertNotNull("projector was not paired with its model", withProjector.visionProjectorPath)
        assertTrue(withProjector.visionProjectorPath!!.endsWith(VisionProjector.SUFFIX))
        assertEquals(
            VisionProjector.fileNameFor(modelName),
            File(withProjector.visionProjectorPath!!).name,
        )

        // The projector is a GGUF too but must never appear as its own model row.
        val listed = store.models.value
        assertTrue("model missing from the list", listed.any { it.path == withProjector.path })
        assertFalse(
            "projector leaked into the model list",
            listed.any { it.path == withProjector.visionProjectorPath },
        )
        assertTrue(
            "the selected model did not keep its projector",
            store.selected.value?.visionProjectorPath == withProjector.visionProjectorPath,
        )

        val detached = store.detachVisionProjector(withProjector)
        assertTrue("projector detach failed: " + detached, detached.isSuccess)
        assertNull(detached.getOrThrow().visionProjectorPath)
        assertFalse("detach did not delete the projector file", File(withProjector.visionProjectorPath!!).exists())

        // Deleting the model must take the projector with it (cascade).
        val reattached = store.attachVisionProjector(detached.getOrThrow()) {
            ByteArrayInputStream(minimalGguf())
        }.getOrThrow()
        val projectorPath = reattached.visionProjectorPath!!
        store.delete(reattached)
        assertFalse("deleting the model left its projector behind", File(projectorPath).exists())
    }

    @Test
    fun smolVlmAnswersAboutAnImageInsteadOfClaimingItCannotSee() {
        val modelFile = stageModel(MODEL_FILE)
        val projectorFile = stageModel(PROJECTOR_FILE)
        assumeTrue("Push " + MODEL_FILE + " to /sdcard/Download/ first", modelFile.isFile)
        assumeTrue("Push " + PROJECTOR_FILE + " to /sdcard/Download/ first", projectorFile.isFile)
        assertTrue("staged model is not readable by the app: " + modelFile, modelFile.canRead())
        assertTrue("staged projector is not readable by the app: " + projectorFile, projectorFile.canRead())

        val engine = LlamaCppEngine(
            engineDisplayName = "SmolVLM device test",
            defaultSystemPrompt = "You are a helpful assistant. Answer briefly.",
            defaultContextTokens = 2048,
            defaultMaxTokens = 24,
        )
        val model = InstalledModel(
            id = MODEL_FILE,
            displayName = "SmolVLM 500M",
            path = modelFile.absolutePath,
            sizeBytes = modelFile.length(),
            quantization = "Q8_0",
            visionProjectorPath = projectorFile.absolutePath,
        )
        try {
            runBlocking { engine.load(model) }
            val status = engine.status.value
            assertTrue("vision model did not load: " + status, status is EngineStatus.Ready)
            assertTrue("engine does not report image capability", engine.capabilities.images)
            assertTrue("engine.visionEnabled is false after a vision load", engine.visionEnabled)

            val image = largeJpeg(width = 384, height = 384)
            val chunks = runBlocking {
                withTimeout(GENERATION_TIMEOUT_MS) {
                    engine.streamWithImages(
                        prompt = "What is the main color of this image? Answer with one word.",
                        history = emptyList(),
                        images = listOf(ImageInput(image, "image/jpeg")),
                        options = GenerationOptions(params = GenerationParams.DEFAULT.copy(maxTokens = 16)),
                    ).toList()
                }
            }
            val text = chunks.filterIsInstance<EngineChunk.Delta>().joinToString("") { it.text }.trim()
            val failure = chunks.filterIsInstance<EngineChunk.Failed>().firstOrNull()
            Log.i(TAG, "vision answer='" + text + "' failure=" + failure + " chunks=" + chunks.size)
            assertNull("vision generation failed: " + failure, failure)
            assertTrue("vision model produced no answer", text.isNotBlank())
            assertFalse(
                "the model claimed it cannot see: " + text,
                text.contains("cannot see", ignoreCase = true) || text.contains("لا أستطيع", ignoreCase = false),
            )
        } finally {
            engine.unload()
        }
    }

    // ---- fixtures -----------------------------------------------------------------------------

    private fun largeJpeg(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.RED)
        canvas.drawRect(
            width * 0.25f,
            height * 0.25f,
            width * 0.75f,
            height * 0.75f,
            Paint().apply { color = Color.WHITE },
        )
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private fun smallJpeg(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.BLUE)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    /** The smallest file [app.sirrak.core.runtime.GgufFile] accepts: magic + version, nothing else. */
    private fun minimalGguf(): ByteArray {
        val bytes = ByteArray(8)
        "GGUF".toByteArray(Charsets.US_ASCII).copyInto(bytes, 0)
        bytes[4] = 3
        return bytes
    }

    private fun modelsDir(): File = File(context.getExternalFilesDir(null), "models")

    /**
     * Copies a multi-hundred-megabyte model once from /sdcard/Download into the app's external files
     * directory through a shell command (no storage permission), then makes it app-readable. A missing
     * source yields the absent target and the caller skips instead of failing.
     */
    private fun stageModel(fileName: String): File {
        val target = File(modelsDir(), fileName)
        modelsDir().mkdirs()
        if (!target.isFile) {
            shell("cp /sdcard/Download/" + fileName + " " + target.absolutePath)
        }
        shell("chmod 666 " + target.absolutePath)
        return target
    }

    private fun shell(command: String) {
        runCatching {
            ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
            ).use { stream -> stream.readBytes() }
        }
    }

    private companion object {
        const val TAG = "SirrakVision"
        const val MODEL_FILE = "SmolVLM-500M-Instruct-Q8_0.gguf"
        const val PROJECTOR_FILE = "mmproj-SmolVLM-500M-Instruct-Q8_0.gguf"
        const val TEXT_MODEL_FILE = "qwen2.5-0.5b-instruct-q4_k_m.gguf"
        const val GENERATION_TIMEOUT_MS = 240_000L
    }
}
