package app.sirrak.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M9 pure vision policy: the sizing math that keeps a 12 MP photo from OOMing before it reaches the
 * projector, and the model/projector pairing convention shared by the store and the settings UI.
 */
class VisionTest {

    @Test
    fun `a 12 MP photo decodes with a power-of-two sample under the cap`() {
        // 4000x3000 is the classic 12 MP camera frame; 2048 is AttachmentLimits.Default.imageDimension.
        assertEquals(2, ImageSizing.sampleSize(4000, 3000, 2048))
        assertEquals(4, ImageSizing.sampleSize(8000, 6000, 2048))
        // Exactly at the cap needs no sampling, one pixel over does.
        assertEquals(1, ImageSizing.sampleSize(2048, 1536, 2048))
        assertEquals(2, ImageSizing.sampleSize(2049, 1536, 2048))
    }

    @Test
    fun `sample size is symmetric for portrait and landscape`() {
        assertEquals(ImageSizing.sampleSize(4000, 3000, 2048), ImageSizing.sampleSize(3000, 4000, 2048))
    }

    @Test
    fun `degenerate sizes never divide by zero or sample`() {
        assertEquals(1, ImageSizing.sampleSize(0, 0, 2048))
        assertEquals(1, ImageSizing.sampleSize(-5, 10, 2048))
        assertEquals(1, ImageSizing.sampleSize(4000, 3000, 0))
    }

    @Test
    fun `target size scales the long side to the cap and keeps the ratio`() {
        assertEquals(2048 to 1536, ImageSizing.targetSize(4000, 3000, 2048))
        assertEquals(1536 to 2048, ImageSizing.targetSize(3000, 4000, 2048))
        // Already small enough: untouched, no accidental upscale.
        assertEquals(800 to 600, ImageSizing.targetSize(800, 600, 2048))
        // Never collapses to zero for extreme panoramas.
        assertEquals(2048 to 1, ImageSizing.targetSize(8000, 1, 2048))
    }

    @Test
    fun `projector file names pair with the model base`() {
        assertEquals("qwen2.5-0.5b-instruct-q4_k_m.mmproj.gguf", VisionProjector.fileNameFor("qwen2.5-0.5b-instruct-q4_k_m.gguf"))
        assertEquals("model.mmproj.gguf", VisionProjector.fileNameFor("model.gguf"))
        // A file without an extension is treated as its own base instead of crashing.
        assertEquals("model.mmproj.gguf", VisionProjector.fileNameFor("model"))
    }

    @Test
    fun `projector detection never treats a model as a projector`() {
        assertTrue(VisionProjector.isProjector("smolvlm.mmproj.gguf"))
        assertTrue(VisionProjector.isProjector("SMOlVLM.MMPROJ.GGUF"))
        assertFalse(VisionProjector.isProjector("smolvlm.gguf"))
        assertEquals("smolvlm", VisionProjector.modelBase("smolvlm.mmproj.gguf"))
        assertNull(VisionProjector.modelBase("smolvlm.gguf"))
    }
}
