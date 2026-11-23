package app.sirrak

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.sirrak.core.rag.DocumentFormats
import app.sirrak.core.rag.ExtractionSource
import app.sirrak.core.rag.ExtractorRegistry
import app.sirrak.core.rag.RagDocument
import app.sirrak.core.rag.RagDocumentStatus
import app.sirrak.core.rag.RagImportResult
import app.sirrak.core.rag.RagLimits
import app.sirrak.core.rag.RagPrompt
import app.sirrak.core.rag.RagPromptPolicy
import app.sirrak.core.rag.defaultExtractors
import app.sirrak.core.runtime.ContentRepository
import app.sirrak.core.runtime.DemoReply
import app.sirrak.core.runtime.InstalledModel
import app.sirrak.core.runtime.ModelInfo
import app.sirrak.core.runtime.Suggestion
import app.sirrak.core.runtime.TokenEstimate
import app.sirrak.engine.embeddings.LiteRtEmbeddingProviderFactory
import app.sirrak.engine.llamacpp.LlamaCppEngine
import app.sirrak.feature.chat.ChatStore
import app.sirrak.rag.PdfBoxExtractor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M8.9 device evaluation. Everything here runs against the real native stack on an emulator/phone:
 * the user-imported EmbeddingGemma .litertlm file, the real hybrid retriever, PDFBox-Android parsing
 * with its Android assets, and llama.cpp generating a grounded answer from the numbered sources.
 *
 * The tests are skipped (not failed) when the model files have not been pushed to the device, so CI
 * without models still compiles and the rest of the suite stays green. Push them to:
 *   adb push embeddinggemma-2-text-270m.litertlm /sdcard/Android/data/app.sirrak/files/models/
 *   adb push qwen2.5-1.5b-instruct-q4_k_m.gguf /sdcard/Download/
 *
 * The measurements these tests print to logcat (retrieval rank, latency, prompt chars/tokens) are the
 * raw numbers recorded in docs/22-sirrak-m8-document-rag.md; the assertions are the acceptance gates.
 */
@RunWith(AndroidJUnit4::class)
class RagDeviceEvaluationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var root: File
    private lateinit var embeddingStore: FileEmbeddingModelStore
    private lateinit var repository: RagRepository

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(context)
        root = File(context.cacheDir, "rag-device-eval")
        root.deleteRecursively()
        root.mkdirs()
        embeddingStore = FileEmbeddingModelStore(root, LiteRtEmbeddingProviderFactory())
        repository = RagRepository(
            store = FileRagStore(root),
            embeddings = embeddingStore,
            extractors = ExtractorRegistry(
                extractors = defaultExtractors() + PdfBoxExtractor {
                    PDFBoxResourceLoader.init(context)
                },
                formats = DocumentFormats.all,
                limits = RagLimits.Default,
            ),
        )
    }

    @After
    fun tearDown() {
        runCatching { repository.close() }
        runCatching { embeddingStore.close() }
        root.deleteRecursively()
    }

    // ---- M8.9 gate: 20 Arabic questions, top-3 retrieval, real embeddings -----------------------

    @Test
    fun twentyArabicQuestionsRetrieveTheirFactFromSixtyDocumentLibrary() = runBlocking {
        installEmbeddingModel()
        val facts = arabicFacts()
        val decoys = decoyPassages()
        assertEquals(20, facts.size)
        assertEquals(60, facts.size + decoys.size)

        val started = SystemClock.elapsedRealtime()
        val documents = importDocuments(
            facts.mapIndexed { index, fact -> "fact-" + (index + 1).toString().padStart(2, '0') + ".txt" to fact.passage } +
                decoys.mapIndexed { index, text -> "decoy-" + (index + 1).toString().padStart(2, '0') + ".txt" to text },
        )
        val importMs = SystemClock.elapsedRealtime() - started
        assertEquals(60, documents.size)
        assertTrue("no document was indexed", documents.all { it.status == RagDocumentStatus.READY })
        assertTrue("indexing produced no chunks", documents.sumOf { it.chunkCount } >= 60)
        Log.i(TAG, "M8.9 corpus: docs=" + documents.size + " chunks=" + documents.sumOf { it.chunkCount } + " importMs=" + importMs)

        var passed = 0
        val failures = ArrayList<String>()
        var totalRetrievalMs = 0L
        var maxPromptChars = 0
        var maxPromptTokens = 0
        facts.forEachIndexed { index, fact ->
            val retrievalStarted = SystemClock.elapsedRealtime()
            val hits = repository.retrieve(fact.question)
            val retrievalMs = SystemClock.elapsedRealtime() - retrievalStarted
            totalRetrievalMs += retrievalMs

            val prompt = RagPrompt.compose(fact.question, hits)
            maxPromptChars = maxOf(maxPromptChars, prompt.prompt.length)
            maxPromptTokens = maxOf(maxPromptTokens, TokenEstimate.tokens(prompt.prompt))
            assertTrue(
                "q" + (index + 1) + " grounded prompt exceeds the prefill budget: " + prompt.prompt.length,
                prompt.prompt.length <= RagPromptPolicy.Default.maxContextChars + PROMPT_OVERHEAD_CHARS,
            )

            val top3 = hits.take(3)
            val combined = top3.joinToString(separator = " ") { it.chunk.text }
            val ok = combined.contains(fact.expected)
            if (ok) passed++ else failures += "q" + (index + 1) + " expected=" + fact.expected + " top3=" + top3.joinToString { it.documentName }
            Log.i(
                TAG,
                "M8.9 q" + (index + 1) + " ok=" + ok + " rank=" + top3.indexOfFirst { it.chunk.text.contains(fact.expected) } +
                    " hits=" + top3.size + " ms=" + retrievalMs + " promptChars=" + prompt.prompt.length +
                    " promptTokens=" + TokenEstimate.tokens(prompt.prompt) + " q=" + fact.question,
            )
        }
        Log.i(
            TAG,
            "M8.9 SUMMARY passed=" + passed + "/20 avgRetrievalMs=" + (totalRetrievalMs / 20) +
                " maxPromptChars=" + maxPromptChars + " maxPromptTokens=" + maxPromptTokens,
        )
        assertEquals("top-3 retrieval failures: " + failures, 20, passed)
    }

    // ---- M8.9 gate: PDF import with real PDFBox assets + page-numbered grounding ----------------

    @Test
    fun pdfDocumentIsIndexedWithPageNumbersAndGroundsARetrieval() = runBlocking {
        installEmbeddingModel()
        val bytes = pdfWithText(
            "Sirrak evaluation PDF. The launch date is March 14, 2026.",
            "The second page explains the offline privacy model in plain English.",
        )
        val document = imported(
            repository.import(
                ExtractionSource("eval-document.pdf", "application/pdf", bytes.size.toLong()) {
                    ByteArrayInputStream(bytes)
                },
            ),
        )
        assertEquals(DocumentFormats.PDF.id, document.formatId)
        assertEquals(RagDocumentStatus.READY, document.status)
        assertEquals(2, document.pageCount)
        assertTrue(document.chunkCount > 0)
        val pages = repository.chunks(document.id).map { it.page }.toSet()
        assertTrue("PDF chunks lost their page numbers: " + pages, pages.containsAll(setOf(1, 2)))

        val hits = repository.retrieve("When is the launch date?", listOf(document.id))
        assertFalse("no PDF retrieval hit", hits.isEmpty())
        assertTrue(hits.first().chunk.text.contains("March 14"))
        assertEquals(1, hits.first().chunk.page)
        val prompt = RagPrompt.compose("When is the launch date?", hits)
        assertTrue(prompt.prompt.contains("(page 1)"))
        Log.i(TAG, "M8.9 PDF ready: pages=" + document.pageCount + " chunks=" + document.chunkCount + " topPage=" + hits.first().chunk.page)
        Unit
    }

    // ---- M8.9 gate: grounded answer with citations generated on device -------------------------

    @Test
    fun groundedGenerationCitesTheRetrievedSourceOnDevice() = runBlocking {
        installEmbeddingModel()
        val gguf = stageModel(GGUF_FILE)
        if (!gguf.isFile) {
            Assume.assumeTrue("Push " + gguf + " to /sdcard/Download/ first", false)
        }
        assertTrue("staged model is not readable by the app: " + gguf, gguf.canRead())

        val document = importEvaluationDocument()
        val engine = LlamaCppEngine(
            engineDisplayName = "llama.cpp device evaluation",
            defaultSystemPrompt = "You are Sirrak, a concise on-device assistant. Follow the grounded-answer instructions in the prompt.",
            defaultContextTokens = 2048,
            defaultMaxTokens = 220,
        )
        try {
            engine.load(
                InstalledModel(
                    id = gguf.name,
                    displayName = "qwen2.5 1.5b device evaluation",
                    path = gguf.absolutePath,
                    sizeBytes = gguf.length(),
                    quantization = "Q4_K_M",
                ),
            )
            assertTrue("engine did not load: " + engine.status.value, engine.status.value is app.sirrak.core.runtime.EngineStatus.Ready)

            val store = ChatStore(
                engine = engine,
                library = EmptyContent,
                rag = repository,
                contextTokens = 2048,
            )
            store.addDocumentToScope(document.id)
            val scope = CoroutineScope(Dispatchers.Default)
            val question = "ما أنواع المستندات التي يمكن استيرادها؟"
            val started = SystemClock.elapsedRealtime()
            store.send(question, scope)
            var answer = awaitGroundedAnswer(store)
            var attempts = 1
            while (answer.citations.isEmpty() && attempts < MAX_GROUNDING_ATTEMPTS) {
                store.regenerate(scope)
                answer = awaitGroundedAnswer(store)
                attempts++
            }
            val generationMs = SystemClock.elapsedRealtime() - started
            Log.i(TAG, "M8.9 grounded answer attempt=" + attempts + " ms=" + generationMs)
            Log.i(TAG, "M8.9 grounded answer: " + answer.text)
            Log.i(TAG, "M8.9 citations=" + answer.citations.size + " -> " + answer.citations.joinToString { c -> c.documentName + "#" + c.chunkId })
            Log.i(TAG, "M8.9 inline markers=" + Regex("\\[([0-9]{1,2})]").findAll(answer.text).count())
            assertFalse("grounded answer is blank", answer.text.isBlank())
            assertFalse("model answered with a failure flag: " + answer.failureReason, answer.failed)
            assertTrue("grounded answer cited no source", answer.citations.isNotEmpty())
            assertTrue("cited source is not the retrieved document", answer.citations.all { it.documentId == document.id })
            assertTrue("citation lost its page", answer.citations.all { it.page >= 1 })
            assertTrue("citation lost its excerpt", answer.citations.all { it.snippet.isNotBlank() })
        } finally {
            engine.close()
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private suspend fun awaitGroundedAnswer(store: ChatStore): ChatStore.Item.Assistant {
        withTimeout(GENERATION_TIMEOUT_MS) {
            while (store.streaming) delay(250)
        }
        val answer = store.items.lastOrNull() as? ChatStore.Item.Assistant
        assertNotNull("no assistant turn was produced", answer)
        return answer!!
    }

    private fun imported(result: RagImportResult): RagDocument {
        assertTrue("import was rejected: " + result, result is RagImportResult.Imported)
        return (result as RagImportResult.Imported).document
    }

    private suspend fun importDocuments(items: List<Pair<String, String>>): List<RagDocument> =
        items.map { (name, text) -> imported(repository.import(textSource(name, text))) }

    private suspend fun importEvaluationDocument(): RagDocument {
        val fileName = "rag-eval-ar.txt"
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open(fileName)
            .use { input -> input.readBytes() }
        val document = imported(
            repository.import(
                ExtractionSource(fileName, "text/plain", bytes.size.toLong()) { ByteArrayInputStream(bytes) },
            ),
        )
        assertEquals(RagDocumentStatus.READY, document.status)
        assertTrue(document.chunkCount > 0)
        return document
    }

    private fun textSource(name: String, text: String): ExtractionSource {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return ExtractionSource(name, "text/plain", bytes.size.toLong()) { ByteArrayInputStream(bytes) }
    }

    private fun installEmbeddingModel() {
        val file = stageModel(EMBEDDING_FILE)
        assumeTrue("Push " + file + " to /sdcard/Download/ first", file.isFile)
        val result = runBlocking {
            embeddingStore.import(file.name, file.length()) { file.inputStream() }
        }
        assertTrue("embedding model import failed: " + result, result is EmbeddingModelImport.Imported)
        val installed: EmbeddingModelInfo? = embeddingStore.installedModel()
        assertNotNull(installed)
        assertTrue(embeddingStore.status.value is app.sirrak.core.rag.EmbeddingStatus.Ready)
    }

    private fun modelsDir(): File = File(context.getExternalFilesDir(null), "models")

    /**
     * Device runs start from a clean install (Gradle uninstalls the app and its external files after
     * every connected test), while multi-hundred-megabyte models are expensive to adb-push per run.
     * The models are therefore kept once in /sdcard/Download and copied into the app's own external
     * directory here through a shell command, which does not need a storage permission. A missing
     * source yields the (absent) target, and the caller turns that into a skip instead of a failure.
     */
    private fun stageModel(fileName: String): File {
        val target = File(modelsDir(), fileName)
        modelsDir().mkdirs()
        if (!target.isFile) {
            shell("cp /sdcard/Download/" + fileName + " " + target.absolutePath)
        }
        // A copy made by the shell uid is 0600/0660 and unreadable to the app, so the engine cannot
        // open it. The chmod is unconditional: it repairs copies left behind by an earlier failed run
        // too. Readability is asserted by the caller instead of silently skipping.
        shell("chmod 666 " + target.absolutePath)
        return target
    }

    /** Runs one shell command through UiAutomation and drains its output stream. */
    private fun shell(command: String) {
        runCatching {
            ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
            ).use { stream -> stream.readBytes() }
        }
    }

    private fun pdfWithText(vararg pageTexts: String?): ByteArray = PDDocument().use { document ->
        pageTexts.forEach { document.addPage(PDPage(PDRectangle.LETTER)) }
        val font = PDType1Font.HELVETICA
        pageTexts.forEachIndexed { index, text ->
            if (text == null) return@forEachIndexed
            PDPageContentStream(document, document.getPage(index)).use { content ->
                content.beginText()
                content.setFont(font, 12f)
                content.newLineAtOffset(72f, 720f)
                content.showText(text)
                content.endText()
            }
        }
        val out = ByteArrayOutputStream()
        document.save(out)
        out.toByteArray()
    }

    // ---- evaluation data -----------------------------------------------------------------------

    private data class ArabicFact(val question: String, val expected: String, val passage: String)

    /**
     * Twenty distinct Arabic facts. Each lives in its own document so a hit is unambiguous, and each
     * [expected] token appears in exactly one passage: the test measures retrieval, not luck.
     */
    private fun arabicFacts(): List<ArabicFact> = listOf(
        ArabicFact("أين تُنفَّذ عمليات التطبيق؟", "محليًا", "الخصوصية: لا يرسل التطبيق أي بيانات إلى الخوادم؛ تُنفَّذ كل العمليات محليًا على الجهاز نفسه."),
        ArabicFact("ما صيغة ملفات النماذج المدعومة؟", "GGUF", "النماذج: يدعم التطبيق استيراد ملفات النماذج بصيغة GGUF وتشغيلها دون أي اتصال بالإنترنت."),
        ArabicFact("بأي صيغة تُحفظ المحادثات؟", "JSON", "المحادثات: تُحفظ المحادثات في ملفات بصيغة JSON داخل مساحة التطبيق الخاصة، ويمكن استعادتها بعد إعادة التشغيل."),
        ArabicFact("ما أنواع المستندات التي يمكن استيرادها؟", "EPUB", "المستندات: يستورد التطبيق ملفات PDF و DOCX و EPUB والنصوص العادية، ثم يستخرج نصها ويفهرسها."),
        ArabicFact("كم عدد أبعاد متجهات طراز التضمين؟", "768", "التضمين: يستخدم البحث الدلالي طراز EmbeddingGemma لتوليد متجهات من 768 بُعدًا لكل مقطع نصي."),
        ArabicFact("ما اسم منهج الاستدلال الأصولي المستخدم؟", "القياس", "الاستدلال: يعتمد التطبيق منهج القياس الأصولي عند الموازنة بين المصادر والاستدلال عليها."),
        ArabicFact("ما الذي يطبّقه المحرك على الحروف العربية قبل البحث؟", "التطبيع", "المعالجة: يطبّق المحرك التطبيع على الحروف العربية مثل الألف والياء والتاء المربوطة قبل البحث."),
        ArabicFact("ما طول مقطع النص المستخدم في التقطيع؟", "700", "التقطيع: تُقسَّم المستندات إلى مقاطع نصية بطول 700 حرف مع تداخل 120 حرفًا بين المقاطع المتجاورة."),
        ArabicFact("كيف تظهر أرقام المصادر داخل الإجابة؟", "قوسين معقوفين", "الاستشهاد: تُدرَج المصادر بين قوسين معقوفين مثل [1] و[2] بعد كل عبارة مستندة إلى نص."),
        ArabicFact("كم يبلغ حجم نافذة السياق الافتراضية؟", "2048", "السعة: يبلغ حجم نافذة السياق الافتراضية 2048 رمزًا، ويقلل المحرك عدد الأدوار القديمة عند التجاوز."),
        ArabicFact("بأي وحدة يقيس العدّاد سرعة التوليد؟", "الرموز في الثانية", "السرعة: يعرض العدّاد سرعة التوليد بوحدة الرموز في الثانية أسفل شريط الحالة."),
        ArabicFact("ما الميزة الصوتية القادمة في مرحلة لاحقة؟", "الاستماع إلى الردود", "الصوت: الاستماع إلى الردود بالصوت قادم في مرحلة لاحقة بعد اكتمال البحث في المستندات."),
        ArabicFact("ما الميزة البصرية القادمة في مرحلة لاحقة؟", "الرؤية الحاسوبية", "الصور: تحليل الصور بالرؤية الحاسوبية قادم في مرحلة لاحقة بعد إكمال المحادثة المستندة."),
        ArabicFact("تحت أي ترخيص يُوزَّع التطبيق؟", "Apache-2.0", "الترخيص: يُوزَّع التطبيق تحت ترخيص Apache-2.0 مع الإبقاء على خصوصية المستخدم الكاملة."),
        ArabicFact("من أين يمكن محو جميع المحادثات؟", "شاشة الإعدادات", "الحذف: يمكن محو جميع المحادثات والملفات المؤقتة من شاشة الإعدادات بضغطة واحدة."),
        ArabicFact("بأي صيغة يمكن تصدير المحادثة؟", "Markdown", "التصدير: يمكن تصدير المحادثة كاملة بصيغة Markdown أو JSON للاحتفاظ بها خارج التطبيق."),
        ArabicFact("على أي خيط تُنفَّذ الفهرسة؟", "خيط خلفي", "الفهرسة: تُنفَّذ الفهرسة على خيط خلفي حتى تبقى واجهة المحادثة سريعة الاستجابة."),
        ArabicFact("بماذا تُحمى ملفات المستندات؟", "صلاحيات النظام", "الحماية: تُحمى ملفات المستندات بصلاحيات النظام بحيث لا يصل إليها أي تطبيق آخر."),
        ArabicFact("أي طراز صغير يعمل على الأجهزة المتوسطة؟", "qwen2.5-0.5b", "الطرازات الصغيرة: الطراز qwen2.5-0.5b يعمل على الأجهزة المتوسطة ويوفر تجربة محادثة مقبولة."),
        ArabicFact("ما اللغات المدعومة في الواجهة؟", "الإنجليزية", "اللغات: يدعم التطبيق اللغتين العربية والإنجليزية في الواجهة والإعدادات والرسائل."),
    )

    /** Forty unrelated encyclopedia-style passages so retrieval has to choose among 60 documents. */
    private fun decoyPassages(): List<String> = listOf(
        "دمشق", "بغداد", "القاهرة", "عمّان", "بيروت", "تونس", "الرباط", "الخرطوم",
        "مسقط", "صنعاء", "الدوحة", "المنامة", "الكويت", "الجزائر", "طرابلس", "نواكشوط",
        "إستانبول", "طهران", "كوالالمبور", "جاكرتا", "طوكيو", "سيول", "بكين", "دلهي",
        "لندن", "باريس", "برلين", "مدريد", "روما", "أثينا", "موسكو", "أوتاوا",
        "برازيليا", "بوينس آيرس", "مكسيكو", "كانبرا", "ويلينغتون", "أوسلو", "ستوكهولم", "هلسنكي",
    ).mapIndexed { index, city ->
        "مدخل موسوعي رقم " + (index + 1) + ": مدينة " + city +
            " لها تاريخ طويل في التجارة والعلوم، وتشتهر بأسواقها القديمة ومتاحفها الوطنية، " +
            "ويختلف عدد سكانها حسب حدود المدينة، وتُعد وجهة سياحية معروفة دون أن يكون لها علاقة بموضوع التطبيق."
    }

    private object EmptyContent : ContentRepository {
        override fun suggestions(): List<Suggestion> = emptyList()
        override fun models(): List<ModelInfo> = emptyList()
        override fun demoReply(prompt: String): DemoReply = DemoReply(emptyList(), emptyList())
    }

    private companion object {
        const val TAG = "SirrakRagEval"
        const val EMBEDDING_FILE = "embeddinggemma-2-text-270m.litertlm"
        const val GGUF_FILE = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
        const val PROMPT_OVERHEAD_CHARS = 1200
        /**
         * One grounded attempt per run. Citations no longer depend on the model emitting [n] markers:
         * when a small model omits them the store falls back to the retrieved numbered sources (the
         * unit suite covers both branches), so the emulator does not need to pay for retries.
         */
        const val MAX_GROUNDING_ATTEMPTS = 1
        const val GENERATION_TIMEOUT_MS = 480_000L
    }
}
