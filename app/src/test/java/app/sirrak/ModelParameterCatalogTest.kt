package app.sirrak

import app.sirrak.core.runtime.GenerationParams
import app.sirrak.core.runtime.GenerationRanges
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Settings > Model sliders are data-driven: [ModelParameters.ALL] is the single source of truth
 * for ids, ranges and formatting. These tests pin the contract between that data and the runtime so
 * a mistyped id or a range drift cannot silently stop an edit from reaching llama.cpp.
 */
class ModelParameterCatalogTest {

    @Test
    fun `ids are unique and every persisted field is exposed somewhere`() {
        val ids = ModelParameters.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)

        // Every GenerationParams field must be editable through exactly one path: a catalogue row,
        // or one of the bespoke editors (stop sequences, sampling mode).
        val bespoke = setOf("seed", "stop", "samplingMode")
        val generatedFields = GenerationParams::class.java.declaredFields
            .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) || it.name.contains('$') }
            .map { it.name }
            .toSet()
        assertEquals(generatedFields - bespoke, ids.toSet())
    }

    @Test
    fun `ranges mirror the runtime clamps`() {
        fun assertFloatRange(id: String, range: ClosedFloatingPointRange<Float>) {
            val spec = ModelParameters.spec(id)!!
            assertEquals(id + " min", range.start, spec.min, 0f)
            assertEquals(id + " max", range.endInclusive, spec.max, 0f)
        }

        fun assertIntRange(id: String, range: IntRange) {
            val spec = ModelParameters.spec(id)!!
            assertEquals(id + " min", range.first.toFloat(), spec.min, 0f)
            assertEquals(id + " max", range.last.toFloat(), spec.max, 0f)
        }

        assertFloatRange("temperature", GenerationRanges.temperature)
        assertFloatRange("topP", GenerationRanges.topP)
        assertIntRange("topK", GenerationRanges.topK)
        assertIntRange("maxTokens", GenerationRanges.maxTokens)
        assertFloatRange("repeatPenalty", GenerationRanges.repeatPenalty)
        assertIntRange("repeatLastN", GenerationRanges.repeatLastN)
        assertFloatRange("minP", GenerationRanges.minP)
        assertFloatRange("typicalP", GenerationRanges.typicalP)
        assertFloatRange("presencePenalty", GenerationRanges.presencePenalty)
        assertFloatRange("frequencyPenalty", GenerationRanges.frequencyPenalty)
        assertFloatRange("mirostatTau", GenerationRanges.mirostatTau)
        assertFloatRange("mirostatEta", GenerationRanges.mirostatEta)
        assertFloatRange("xtcProbability", GenerationRanges.xtcProbability)
        assertFloatRange("xtcThreshold", GenerationRanges.xtcThreshold)
        assertFloatRange("dryMultiplier", GenerationRanges.dryMultiplier)
        assertFloatRange("topNSigma", GenerationRanges.topNSigma)
    }
    @Test
    fun `simple parameters define the simple list and everything else is advanced`() {
        assertEquals(listOf("temperature", "topP", "topK", "maxTokens", "repeatPenalty"), ModelParameters.simple.map { it.id })
        assertTrue(ModelParameters.advancedOnly.isNotEmpty())
        assertFalse(ModelParameters.advancedOnly.any { it in ModelParameters.simple })
    }

    @Test
    fun `every parameter round-trips a value through copy`() {
        val base = GenerationParams.DEFAULT
        for (spec in ModelParameters.ALL) {
            // Any range corner or the midpoint works as long as it differs from the shipped
            // default, so a no-op setter cannot pass this test.
            val candidate = listOf(spec.min, spec.max, (spec.min + spec.max) / 2f)
                .map { spec.snap(it) }
                .firstOrNull { it != spec.get(base) }
            assertTrue("spec " + spec.id + " never changes the value", candidate != null)
            val value = candidate!!
            val updated = spec.set(base, value)
            assertNotEquals("spec " + spec.id + " does not write a value", base, updated)
            assertEquals("spec " + spec.id + " round-trip", value, spec.get(updated), 0.0001f)
        }
    }

    @Test
    fun `snap keeps values inside the range and on the step grid`() {
        for (spec in ModelParameters.ALL) {
            val lowest = spec.snap(spec.min - 100f)
            val highest = spec.snap(spec.max + 100f)
            assertEquals("" + spec.id + " low", spec.min, lowest, 0.0001f)
            assertEquals("" + spec.id + " high", spec.max, highest, 0.0001f)
            val offset = spec.snap(spec.min + spec.step * 0.4f)
            val steps = (offset - spec.min) / spec.step
            assertEquals("" + spec.id + " grid", steps, Math.round(steps).toFloat(), 0.001f)
        }
    }

    @Test
    fun `formatting uses the integer flag and the step decimals`() {
        assertEquals("40", ModelParameters.spec("topK")!!.format(40.49f))
        assertEquals("512", ModelParameters.spec("maxTokens")!!.format(511.7f))
        assertEquals("0.70", ModelParameters.spec("temperature")!!.format(0.7f))
        assertEquals("0.05", ModelParameters.spec("minP")!!.format(0.05f))
        assertEquals("1.05", ModelParameters.spec("repeatPenalty")!!.format(1.05f))
        assertEquals("-1.0", ModelParameters.spec("topNSigma")!!.format(-1f))
    }
}