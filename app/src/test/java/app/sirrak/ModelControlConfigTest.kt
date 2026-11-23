package app.sirrak

import app.sirrak.core.runtime.AppConfigLoader
import app.sirrak.core.runtime.ContentConfig
import app.sirrak.core.runtime.EngineConfig
import app.sirrak.core.runtime.GenerationRanges
import app.sirrak.core.runtime.generationOverrides
import app.sirrak.core.runtime.ModelPresets
import app.sirrak.core.runtime.PromptPresets
import app.sirrak.core.runtime.SamplingMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M5 config layers: app.json can move engine defaults and content paths without a rebuild. The
 * generation block is a sparse layer in the council precedence chain, so only declared values are
 * applied (null/absent values inherit from code).
 */
class ModelControlConfigTest {

    @Test
    fun m5EngineLayerParsesFromAppJson() {
        val config = AppConfigLoader.parse(
            """
            {
              "schemaVersion": 1,
              "engine": {
                "provider": "llamacpp",
                "temperature": 0.9,
                "repeatPenalty": 1.1,
                "repeatLastN": 128,
                "minP": 0.02,
                "seed": 42,
                "samplingMode": "greedy",
                "stop": ["<|im_end|>"]
              }
            }
            """.trimIndent(),
        )
        assertEquals(1.1f, config.engine.repeatPenalty)
        assertEquals(128, config.engine.repeatLastN)
        assertEquals(0.02f, config.engine.minP)
        assertEquals(42, config.engine.seed)
        assertEquals("greedy", config.engine.samplingMode)
        assertEquals(listOf("<|im_end|>"), config.engine.stop)
        assertTrue(AppConfigLoader.validate(config).isEmpty())
    }

    @Test
    fun m5EngineLayerOutsideRangeIsReported() {
        val problems = AppConfigLoader.validate(
            app.sirrak.core.runtime.AppConfig(
                engine = EngineConfig(
                    repeatPenalty = 9f,
                    repeatLastN = -5,
                    minP = 2f,
                    samplingMode = "quantum",
                    stop = listOf(""),
                ),
            ),
        )
        assertTrue(problems.any { it.contains("repeatPenalty") })
        assertTrue(problems.any { it.contains("repeatLastN") })
        assertTrue(problems.any { it.contains("minP") })
        assertTrue(problems.any { it.contains("samplingMode") })
        assertTrue(problems.any { it.contains("stop") })
        // Defaults must never be reported: the shipped config is expected to validate cleanly.
        assertTrue(problems.size >= 5)
    }

    @Test
    fun m5SparseLayerOnlyCarriesDeclaredValues() {
        val config = EngineConfig(temperature = 0.9f)
        val overrides = config.generationOverrides()
        assertEquals(0.9f, overrides.temperature)
        assertEquals(null, overrides.topP)
        assertEquals(null, overrides.maxTokens)
        assertEquals(null, overrides.repeatPenalty)
        assertEquals(null, overrides.samplingMode)
    }

    @Test
    fun m5SamplingModeNamesRoundTrip() {
        assertEquals(SamplingMode.GREEDY, SamplingMode.fromConfig("greedy"))
        assertEquals(SamplingMode.MIROSTAT_V2, SamplingMode.fromConfig("mirostat_v2"))
        assertEquals(SamplingMode.DEFAULT, SamplingMode.fromConfig("default"))
        assertEquals(null, SamplingMode.fromConfig("quantum"))
        assertEquals("greedy", SamplingMode.GREEDY.configValue)
    }

    @Test
    fun m5ContentPresetPathsDefaultToShippedAssets() {
        assertEquals("content/prompt_presets.json", ContentConfig().promptPresets)
        assertEquals("content/model_presets.json", ContentConfig().modelPresets)
        for (path in listOf(ContentConfig().promptPresets, ContentConfig().modelPresets)) {
            assertTrue("missing asset $path", File("src/main/assets/$path").exists())
        }
    }

    @Test
    fun m5PromptPresetsAssetIsValidAndBilingual() {
        val file = File("src/main/assets/" + ContentConfig().promptPresets)
        val parsed = PromptPresets.parse(file.readText()).getOrThrow()
        assertTrue(PromptPresets.problems(parsed).isEmpty())
        assertTrue("at least six presets", parsed.presets.size >= 6)
        parsed.presets.forEach { preset ->
            assertTrue(preset.name.ar.isNotBlank() && preset.name.en.isNotBlank())
            assertTrue(preset.systemPrompt.ar.isNotBlank() && preset.systemPrompt.en.isNotBlank())
        }
    }

    @Test
    fun m5ModelPresetsAssetIsValidAndResolvesFamilies() {
        val file = File("src/main/assets/" + ContentConfig().modelPresets)
        val parsed = ModelPresets.parse(file.readText()).getOrThrow()
        assertTrue(ModelPresets.problems(parsed).isEmpty())
        assertTrue(parsed.presets.size >= 3)
        assertEquals("qwen", ModelPresets.forArchitecture(parsed, "qwen2")?.id)
        assertEquals("llama", ModelPresets.forArchitecture(parsed, "llama")?.id)
    }

    @Test
    fun m5AppJsonListsThePresetAssets() {
        val config = AppConfigLoader.parse(File("src/main/assets/config/app.json").readText())
        assertEquals("content/prompt_presets.json", config.content.promptPresets)
        assertEquals("content/model_presets.json", config.content.modelPresets)
        assertTrue(GenerationRanges.maxTokens.contains(config.engine.maxTokens))
    }
}