package app.sirrak.core.runtime

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * M5 model control, pure-Kotlin half: facts parsing, typed generation params, layer precedence,
 * template/prompt resolution, model fingerprint and the content assets' parsers.
 *
 * Every test runs on the JVM without Android or a native library, so the precedence rules and the
 * store format are the cheapest thing to pin before any UI or JNI work starts.
 */
class ModelControlTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val nativeInfo = """
        architecture=qwen2
        n_ctx_train=32768
        n_params=1543714304
        n_vocab=151936
        chat_template_present=1
        chat_template_first_line=<|im_start|>system
        bos_token_id=151643
        eos_token_id=151645
        general.sampling.temp=0.80
        general.sampling.top_k=20
        general.sampling.top_p=0.90
        general.sampling.min_p=0.02
        general.sampling.penalty_repeat=1.05
        general.sampling.penalty_last_n=128
    """.trimIndent()

    @Test
    fun parsesNativeModelInfoIntoFacts() {
        val facts = ModelFacts.parse(nativeInfo)!!
        assertEquals("qwen2", facts.architecture)
        assertEquals(32768, facts.nCtxTrain)
        assertEquals(1543714304L, facts.nParams)
        assertEquals(151936, facts.nVocab)
        assertEquals(TemplateSource.MODEL, facts.templateSource)
        assertEquals("<|im_start|>system", facts.templateFirstLine)
        assertEquals(151643, facts.bosTokenId)
        assertEquals(151645, facts.eosTokenId)
        assertEquals("0.80", facts.samplingMetadata["general.sampling.temp"])
        assertEquals(6, facts.samplingMetadata.size)
    }

    @Test
    fun garbageNativeModelInfoIsRejected() {
        assertNull(ModelFacts.parse(""))
        assertNull(ModelFacts.parse("\n\n"))
        assertNull(ModelFacts.parse("this is not key value"))
        assertNull(ModelFacts.parse("=no-key"))
    }

    @Test
    fun missingOptionalKeysStayEmpty() {
        val facts = ModelFacts.parse("architecture=llama\nn_ctx_train=4096")!!
        assertEquals("llama", facts.architecture)
        assertEquals(4096, facts.nCtxTrain)
        assertEquals(TemplateSource.NONE, facts.templateSource)
        assertNull(facts.templateFirstLine)
        assertNull(facts.bosTokenId)
        assertTrue(facts.samplingMetadata.isEmpty())
    }

    @Test
    fun chatTemplateTextRefinesTheFacts() {
        val facts = ModelFacts.parse(nativeInfo, chatTemplate = "<|im_start|>system\nYou are helpful.<|im_end|>\n")!!
        assertEquals(TemplateSource.MODEL, facts.templateSource)
        assertEquals("<|im_start|>system", facts.templateFirstLine)
        assertEquals(ModelFingerprint.sha256Hex("<|im_start|>system\nYou are helpful.<|im_end|>\n"), facts.templateHash)
    }

    @Test
    fun generationDefaultsMatchTheDocumentedLayer() {
        val params = GenerationParams()
        assertEquals(0.7f, params.temperature)
        assertEquals(0.95f, params.topP)
        assertEquals(40, params.topK)
        assertEquals(512, params.maxTokens)
        assertEquals(1.0f, params.repeatPenalty)
        assertEquals(64, params.repeatLastN)
        assertEquals(0.05f, params.minP)
        assertEquals(SamplingMode.DEFAULT, params.samplingMode)
        assertEquals(-1, params.seed)
        assertTrue(params.stop.isEmpty())
        assertTrue(params.problems().isEmpty())
    }

    @Test
    fun invalidParamsAreReportedAndClamped() {
        val broken = GenerationParams(
            temperature = 3f, topP = -1f, topK = 900, maxTokens = 3,
            repeatPenalty = 5f, repeatLastN = -9, minP = 2f, typicalP = -1f,
            presencePenalty = 3f, frequencyPenalty = -3f, mirostatTau = 99f, mirostatEta = 9f,
            stop = listOf("", "x".repeat(65)),
        )
        val problems = broken.problems()
        assertTrue(problems.size >= 10)
        val fixed = broken.clamped()
        assertTrue(fixed.temperature in 0f..2f)
        assertTrue(fixed.topP in 0f..1f)
        assertTrue(fixed.topK in 0..200)
        assertTrue(fixed.maxTokens in 16..2048)
        assertTrue(fixed.repeatPenalty in 0f..2f)
        assertTrue(fixed.repeatLastN in -1..2048)
        assertTrue(fixed.stop.isEmpty())
    }

    @Test
    fun ggufSamplingMetadataBecomesOverrides() {
        val facts = ModelFacts.parse(
            """
            architecture=qwen2
            general.sampling.temp=0.8
            general.sampling.top_k=20
            general.sampling.top_p=0.9
            general.sampling.min_p=0.02
            general.sampling.penalty_repeat=1.05
            general.sampling.penalty_last_n=128
            general.sampling.mirostat=2
            general.sampling.mirostat_tau=4.5
            general.sampling.mirostat_eta=0.2
            """.trimIndent(),
        )!!
        val overrides = GgufSampling.fromMetadata(facts.samplingMetadata)
        assertEquals(0.8f, overrides.temperature)
        assertEquals(20, overrides.topK)
        assertEquals(0.9f, overrides.topP)
        assertEquals(0.02f, overrides.minP)
        assertEquals(1.05f, overrides.repeatPenalty)
        assertEquals(128, overrides.repeatLastN)
        assertEquals(SamplingMode.MIROSTAT_V2, overrides.samplingMode)
        assertEquals(4.5f, overrides.mirostatTau)
        assertEquals(0.2f, overrides.mirostatEta)
    }

    @Test
    fun ggufSamplingIgnoresUnknownAndOutOfRangeValues() {
        val overrides = GgufSampling.fromMetadata(
            mapOf(
                "general.sampling.temp" to "9.0",
                "general.sampling.top_k" to "not-a-number",
                "general.sampling.unknown" to "1",
                "general.sampling.mirostat" to "0",
            ),
        )
        assertNull(overrides.temperature)
        assertNull(overrides.topK)
        assertNull(overrides.samplingMode)
        assertTrue(overrides.isEmpty())
    }

    @Test
    fun precedenceAppliesEveryLayerInOrder() {
        val resolved = ModelControlResolver.resolveParams(
            code = GenerationParams(temperature = 0.1f),
            appConfig = GenerationOverrides(temperature = 0.2f, topP = 0.5f),
            familyPreset = GenerationOverrides(temperature = 0.3f, topK = 10),
            modelMetadata = GenerationOverrides(temperature = 0.4f, minP = 0.01f),
            userParams = GenerationParams(temperature = 0.5f, topP = 0.6f, topK = 20),
            userTouched = setOf("temperature", "topP", "topK"),
            conversation = GenerationOverrides(temperature = 0.6f),
        )
        // Conversation > user > model metadata > family preset > app config > code, per field.
        assertEquals(0.6f, resolved.temperature)
        assertEquals(0.6f, resolved.topP)
        assertEquals(20, resolved.topK)
        assertEquals(0.01f, resolved.minP)
        assertEquals(GenerationParams().maxTokens, resolved.maxTokens)
    }

    @Test
    fun paramsSourceNamesTheStrongestBaseLayer() {
        fun source(
            app: GenerationOverrides = GenerationOverrides(),
            preset: GenerationOverrides? = null,
            metadata: GenerationOverrides = GenerationOverrides(),
            user: GenerationParams? = null,
            touched: Set<String> = emptySet(),
            conversation: GenerationOverrides? = null,
        ) = ModelControlResolver.paramsSource(
            appConfig = app,
            familyPreset = preset,
            modelMetadata = metadata,
            userParams = user,
            userTouched = touched,
            conversation = conversation,
        )

        assertEquals(DefaultSource.CODE, source())
        assertEquals(DefaultSource.APP_DEFAULT, source(app = GenerationOverrides(temperature = 0.4f)))
        assertEquals(DefaultSource.PRESET, source(preset = GenerationOverrides(topK = 8)))
        assertEquals(
            DefaultSource.MODEL,
            source(preset = GenerationOverrides(topK = 8), metadata = GenerationOverrides(temperature = 0.3f)),
        )
        assertEquals(
            DefaultSource.USER,
            source(
                metadata = GenerationOverrides(temperature = 0.3f),
                user = GenerationParams(temperature = 0.5f),
                touched = setOf("temperature"),
            ),
        )
        // A user edit that happens to equal the code default is still a user edit.
        assertEquals(
            DefaultSource.USER,
            source(user = GenerationParams(), touched = setOf("topK")),
        )
        assertEquals(
            DefaultSource.CONVERSATION,
            source(user = GenerationParams(), conversation = GenerationOverrides(topK = 5)),
        )
    }

    @Test
    fun templateChoiceRoundTripsThroughStorage() {
        assertEquals(TemplateChoice.ModelDefault, TemplateChoice.fromStored(null))
        assertEquals(TemplateChoice.ModelDefault, TemplateChoice.fromStored("  "))
        assertEquals(TemplateChoice.Builtin("qwen2"), TemplateChoice.fromStored("qwen2"))
        assertNull(TemplateChoice.ModelDefault.storedValue())
        assertEquals("qwen2", TemplateChoice.Builtin("qwen2").storedValue())
    }

    @Test
    fun templateResolutionHonoursModelAndOverrides() {
        val model = ModelControlResolver.resolveTemplate(TemplateChoice.ModelDefault, hasModelTemplate = true)
        assertEquals(TemplateSource.MODEL, model.source)
        assertNull(model.templateName)

        val fallback = ModelControlResolver.resolveTemplate(TemplateChoice.ModelDefault, hasModelTemplate = false)
        assertEquals(TemplateSource.FALLBACK, fallback.source)
        assertNull(fallback.templateName)

        val forced = ModelControlResolver.resolveTemplate(
            TemplateChoice.Builtin("gemma"),
            hasModelTemplate = true,
        )
        assertEquals(TemplateSource.BUILTIN, forced.source)
        assertEquals("gemma", forced.templateName)
    }

    @Test
    fun systemPromptPrecedenceFollowsLayers() {
        val app = "app default"
        val resource = "resource fallback"
        fun resolve(conversation: String? = null, perModel: String? = null, global: String? = null) =
            ModelControlResolver.resolveSystemPrompt(conversation, perModel, global, app, resource)

        assertEquals(ResolvedPrompt("conversation", PromptSource.CONVERSATION), resolve("conversation", "model", "global"))
        assertEquals(ResolvedPrompt("model", PromptSource.MODEL), resolve(perModel = "model", global = "global"))
        assertEquals(ResolvedPrompt("global", PromptSource.GLOBAL), resolve(global = "global"))
        assertEquals(ResolvedPrompt(app, PromptSource.APP_DEFAULT), resolve(global = "  "))
        assertEquals(
            ResolvedPrompt(resource, PromptSource.RESOURCE),
            ModelControlResolver.resolveSystemPrompt(null, null, null, "", resource),
        )
    }

    @Test
    fun fingerprintIsStableAndCoversEndsNotMiddle() {
        val body = ByteArray(300_000) { (it % 251).toByte() }
        val a = tmp.newFile("a.gguf").apply { writeBytes(body) }
        val b = tmp.newFile("b.gguf").apply { writeBytes(body.copyOf()) }
        val hash = ModelFingerprint.of(a)
        assertEquals(hash, ModelFingerprint.of(b))
        assertTrue(hash.startsWith("sha256:"))
        assertEquals(7 + 64, hash.length)

        val middleChanged = body.copyOf()
        for (i in 150_000 until 150_100) middleChanged[i] = 7
        val c = tmp.newFile("c.gguf").apply { writeBytes(middleChanged) }
        assertEquals("the sampled ends did not change, so the key is stable", hash, ModelFingerprint.of(c))

        val startChanged = body.copyOf()
        startChanged[10] = 42
        assertNotEquals(hash, ModelFingerprint.of(tmp.newFile("d.gguf").apply { writeBytes(startChanged) }))
        assertNotEquals(hash, ModelFingerprint.of(tmp.newFile("e.gguf").apply { writeBytes(body + byteArrayOf(1)) }))
    }

    @Test
    fun modelPresetAssetParsesAndResolvesByArchitecture() {
        val json = """
            {
              "schemaVersion": 1,
              "presets": [
                { "id": "qwen", "families": ["qwen2", "qwen3"], "params": { "temperature": 0.6, "topK": 20 } },
                { "id": "llama", "families": ["llama"], "params": { "repeatPenalty": 1.1 } }
              ]
            }
        """.trimIndent()
        val file = ModelPresets.parse(json).getOrThrow()
        assertEquals(2, file.presets.size)
        assertEquals("qwen", ModelPresets.forArchitecture(file, "qwen2")?.id)
        assertEquals("llama", ModelPresets.forArchitecture(file, "llama")?.id)
        assertNull(ModelPresets.forArchitecture(file, "gemma3"))
        assertEquals(0.6f, ModelPresets.forArchitecture(file, "qwen2")!!.params.temperature)
        assertTrue(ModelPresets.problems(file).isEmpty())
    }

    @Test
    fun promptPresetAssetParsesAndLocalises() {
        val json = """
            {
              "schemaVersion": 1,
              "presets": [
                {
                  "id": "tutor",
                  "name": { "ar": "مدرّس", "en": "Tutor" },
                  "description": { "ar": "يشرح ببطء", "en": "Explains slowly" },
                  "systemPrompt": { "ar": "أنت مدرّس.", "en": "You are a tutor." },
                  "tags": ["study"]
                }
              ]
            }
        """.trimIndent()
        val file = PromptPresets.parse(json).getOrThrow()
        assertEquals(1, file.presets.size)
        val preset = file.presets.first()
        assertEquals("Tutor", preset.name.inLanguage(AppLanguage.ENGLISH))
        assertEquals("مدرّس", preset.name.inLanguage(AppLanguage.ARABIC))
        assertEquals("You are a tutor.", preset.systemPrompt.inLanguage(AppLanguage.ENGLISH))
        assertEquals(listOf("study"), preset.tags)
        assertNull(preset.params)
        assertTrue(PromptPresets.problems(file).isEmpty())
    }

    @Test
    fun promptPresetValidationReportsDuplicatesAndBlankFields() {
        val json = """
            {
              "schemaVersion": 1,
              "presets": [
                { "id": "a", "name": { "ar": "", "en": "A" }, "description": { "ar": "x", "en": "x" }, "systemPrompt": { "ar": "x", "en": "x" } },
                { "id": "a", "name": { "ar": "ب", "en": "" }, "description": { "ar": "x", "en": "x" }, "systemPrompt": { "ar": "x", "en": "x" } }
              ]
            }
        """.trimIndent()
        val file = PromptPresets.parse(json).getOrThrow()
        val problems = PromptPresets.problems(file)
        assertTrue(problems.any { it.contains("duplicate") })
        assertTrue(problems.any { it.contains("name") })
    }

    @Test
    fun modelSettingsSerialiseAndDeserialise() {
        val settings = ModelSettings(
            modelKey = "sha256:abc",
            params = GenerationParams(temperature = 0.3f, stop = listOf("</s>"), samplingMode = SamplingMode.GREEDY),
            touchedParams = listOf("temperature", "stop", "samplingMode"),
            systemPrompt = "be brief",
            template = "qwen2",
            userModifiedAt = 1234L,
        )
        val json = Json.encodeToString(ModelSettings.serializer(), settings)
        val back = Json.decodeFromString(ModelSettings.serializer(), json)
        assertEquals(settings, back)
    }
}