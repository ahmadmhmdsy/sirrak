package app.sirrak.core.rag

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic Arabic eval for the retrieval stack, driven by a checked-in case file
 * (`src/test/resources/arabic/eval_cases.json`). It covers the four things the Arabic
 * pipeline owns: normalisation, tokenisation, sentence splitting and BM25 retrieval, plus
 * negative controls that prove the normaliser does not invent matches.
 *
 * This is the eval that runs in CI (P3.6). It is intentionally model-free: model quality is
 * measured on device with real answers (docs 26), while this gate makes the deterministic
 * Arabic behaviour regressions visible on every pull request without an emulator.
 */
class ArabicEvalTest {

    private val root: JsonObject = run {
        val stream = javaClass.classLoader?.getResourceAsStream("arabic/eval_cases.json")
            ?: error("arabic/eval_cases.json missing from test resources")
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        Json.parseToJsonElement(text).jsonObject
    }

    private fun cases(section: String) = root.getValue(section).jsonArray

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.optionalText(key: String): String? =
        get(key)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content

    @Test
    fun evalFileIsVersionedAndHasUniqueIds() {
        assertEquals(1, root.getValue("version").jsonPrimitive.content.toInt())
        val ids = listOf("normalization", "sentenceSplit", "retrieval", "noMatch")
            .flatMap { section -> cases(section).map { it.jsonObject.getValue("id").jsonPrimitive.content } }
        assertEquals("case ids must be unique", ids.size, ids.toSet().size)
        assertTrue("eval file must carry at least 20 cases", ids.size >= 20)
    }

    @Test
    fun normalizationCasesMatchTheContract() {
        var passed = 0
        for (case in cases("normalization")) {
            val obj = case.jsonObject
            val id = obj.text("id")
            val input = obj.text("input")
            val expected = obj.text("expected")
            val actual = ArabicText.normalizeForMatch(input)
            assertEquals("normalizeForMatch [$id]", expected, actual)
            val expectedTokens = obj["tokens"]?.jsonArray
            if (expectedTokens != null) {
                val tokens = expectedTokens.map { it.jsonPrimitive.content }
                assertEquals("tokens [$id]", tokens, ArabicText.tokens(input))
            }
            passed++
        }
        println("Arabic eval normalization: $passed/${cases("normalization").size} cases passed")
        assertEquals(cases("normalization").size, passed)
    }

    @Test
    fun sentenceSplittingCasesMatchTheContract() {
        var passed = 0
        for (case in cases("sentenceSplit")) {
            val obj = case.jsonObject
            val id = obj.text("id")
            val expected = obj.getValue("expected").jsonArray.map { it.jsonPrimitive.content }
            val actual = ArabicText.splitSentences(obj.text("input"))
            assertEquals("splitSentences [$id]", expected, actual)
            passed++
        }
        println("Arabic eval sentence splitting: $passed/${cases("sentenceSplit").size} cases passed")
        assertEquals(cases("sentenceSplit").size, passed)
    }

    @Test
    fun retrievalCasesRankTheExpectedDocumentFirst() {
        var passed = 0
        for (case in cases("retrieval")) {
            val obj = case.jsonObject
            val id = obj.text("id")
            val chunks = chunksOf(obj)
            val index = Bm25Index.build(chunks)
            val hits = index.search(obj.text("query"), limit = 10)
            val expectedTop = obj.text("expectTopDocument")
            assertTrue("retrieval [$id] produced no hit", hits.isNotEmpty())
            assertEquals("retrieval top document [$id]", expectedTop, hits.first().chunk.documentId)
            passed++
        }
        println("Arabic eval retrieval: $passed/${cases("retrieval").size} cases passed")
        assertEquals(cases("retrieval").size, passed)
    }

    @Test
    fun negativeControlsProduceNoHits() {
        var passed = 0
        for (case in cases("noMatch")) {
            val obj = case.jsonObject
            val id = obj.text("id")
            val hits = Bm25Index.build(chunksOf(obj)).search(obj.text("query"), limit = 10)
            assertTrue("negative case [$id] must not match, got ${hits.map { it.chunk.documentId }}", hits.isEmpty())
            passed++
        }
        println("Arabic eval negative controls: $passed/${cases("noMatch").size} cases passed")
        assertEquals(cases("noMatch").size, passed)
    }

    private fun chunksOf(case: JsonObject): List<RagChunk> =
        case.getValue("documents").jsonArray.flatMap { document ->
            val doc = document.jsonObject
            val docId = doc.text("id")
            val text = doc.text("text")
            text.split(Regex("(?<=[.!?؟])\\s+"))
                .filter { it.isNotBlank() }
                .mapIndexed { ordinal, chunkText ->
                    RagChunk(
                        id = "$docId-$ordinal",
                        documentId = docId,
                        ordinal = ordinal,
                        text = chunkText.trim(),
                        page = 1,
                    )
                }
        }
}
