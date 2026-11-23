package app.sirrak

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.sirrak.core.rag.ArabicText
import app.sirrak.core.speech.AudioRecorder
import app.sirrak.core.speech.DictationController
import app.sirrak.core.speech.DictationLanguage
import app.sirrak.core.speech.DictationMode
import app.sirrak.core.speech.DictationSettings
import app.sirrak.core.speech.DictationState
import app.sirrak.core.speech.RecordedAudio
import app.sirrak.core.speech.SpeechImportException
import app.sirrak.core.speech.SpeechModel
import app.sirrak.core.speech.SpeechModelFile
import app.sirrak.core.speech.SpeechModelStore
import app.sirrak.core.speech.SpeechReasons
import app.sirrak.core.speech.TranscriptionEngine
import app.sirrak.core.speech.TranscriptionResult
import app.sirrak.core.speech.TranscriptionStatus
import app.sirrak.core.speech.WavCodec
import app.sirrak.engine.whisper.SherpaWhisperEngine
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M10 dictation evidence on the emulator, using the same sherpa-onnx AAR and the same Whisper int8 trio
 * the H3b spike measured (doc 10 §3.7). Assets are staged from /sdcard/Download into the app's external
 * files directory through the shell (no storage permission), the pattern M9 used for its models; a
 * missing asset skips the test instead of failing it, so a clean checkout still runs the rest.
 *
 * The WER gate is honest about its limits: the 20 H3 clips are synthetic Arabic TTS, and F8 removed the
 * test phone, so this reproduces the spike's 27.4% emulator figure and asserts the same ≤35% ceiling.
 * Phone WER stays an open item in the build record, exactly like the earlier phone-number gaps.
 */
@RunWith(AndroidJUnit4::class)
class DictationDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun engineTranscribesTheEnglishFixture() = runBlocking {
        val model = stagedModel() ?: run {
            assumeTrue("Push the whisper-small int8 trio to /sdcard/Download/ first", false)
            return@runBlocking
        }
        val engine = SherpaWhisperEngine()
        try {
            engine.load(model, DictationLanguage.ENGLISH)
            assertEquals(TranscriptionStatus.READY, engine.status.value)
            assertEquals(model.id, engine.loadedModelId.value)

            val wav = fixture("0.wav").readBytes()
            val result = engine.transcribe(wav, DictationLanguage.ENGLISH)
            assertTrue("transcription failed: " + result, result is TranscriptionResult.Success)
            val success = result as TranscriptionResult.Success
            assertTrue("empty English transcript", success.text.isNotBlank())
            assertTrue("latency was not measured", success.latencyMs > 0)
            assertTrue("audio duration was not measured", success.audioDurationMs > 1_000)
            val reference = fixtureReference("0.wav")
            val errors = wordErrors(ArabicText.tokens(reference), ArabicText.tokens(success.text))
            val wer = errors.toDouble() / ArabicText.tokens(reference).size
            Log.i(TAG, "SPEECH_EN text=" + success.text + " wer=" + wer)
            assertTrue("English fixture WER too high: " + wer, wer <= 0.35)
        } finally {
            engine.unload()
            assertEquals(TranscriptionStatus.UNLOADED, engine.status.value)
        }
    }

    @Test
    fun engineAutoDetectsTheLanguageWhenAsked() = runBlocking {
        val model = stagedModel() ?: run {
            assumeTrue("Push the whisper-small int8 trio to /sdcard/Download/ first", false)
            return@runBlocking
        }
        val engine = SherpaWhisperEngine()
        try {
            engine.load(model, DictationLanguage.AUTO)
            val result = engine.transcribe(fixture("0.wav").readBytes(), DictationLanguage.AUTO)
            assertTrue("auto transcription failed: " + result, result is TranscriptionResult.Success)
            val success = result as TranscriptionResult.Success
            assertTrue("empty auto transcript", success.text.isNotBlank())
            Log.i(TAG, "SPEECH_AUTO language=" + (success.language?.id ?: "-") + " text=" + success.text)
        } finally {
            engine.unload()
        }
    }

    @Test
    fun engineResamplesTheEightKilohertzFixture() = runBlocking {
        val model = stagedModel() ?: run {
            assumeTrue("Push the whisper-small int8 trio to /sdcard/Download/ first", false)
            return@runBlocking
        }
        val engine = SherpaWhisperEngine()
        try {
            engine.load(model, DictationLanguage.ENGLISH)
            val wav = fixture("8k.wav").readBytes()
            val decoded = WavCodec.read(wav)
            assertNotNull("8 kHz fixture is not a readable WAV", decoded)
            assertEquals(8_000, decoded!!.sampleRate)
            val result = engine.transcribe(wav, DictationLanguage.ENGLISH)
            assertTrue("8 kHz transcription failed: " + result, result is TranscriptionResult.Success)
            assertTrue("empty 8 kHz transcript", (result as TranscriptionResult.Success).text.isNotBlank())
        } finally {
            engine.unload()
        }
    }

    @Test
    fun engineRejectsSilenceInsteadOfHallucinating() = runBlocking {
        val model = stagedModel() ?: run {
            assumeTrue("Push the whisper-small int8 trio to /sdcard/Download/ first", false)
            return@runBlocking
        }
        val engine = SherpaWhisperEngine()
        try {
            engine.load(model, DictationLanguage.ENGLISH)
            // 1.6 s of digital silence: without the energy gate whisper-small answers "(static)".
            val silence = WavCodec.writePcm16(ShortArray(16_000 * 16 / 10), 16_000)
            val result = engine.transcribe(silence, DictationLanguage.ENGLISH)
            assertEquals(TranscriptionResult.Failure(SpeechReasons.NO_SPEECH), result)
        } finally {
            engine.unload()
        }
    }

    @Test(timeout = 900_000)
    fun arabicReplayMeetsTheWerGate() = runBlocking {
        val model = stagedModel() ?: run {
            assumeTrue("Push the whisper-small int8 trio to /sdcard/Download/ first", false)
            return@runBlocking
        }
        val h3Dir = stageDir("/sdcard/Download/sirrak-h3/replay", "h3")
        val refsFile = stageFile("/sdcard/Download/sirrak-h3/refs.tsv", "h3-refs.tsv")
        assumeTrue("Push h3-01..20.wav to /sdcard/Download/sirrak-h3/replay/ first", h3Dir.isDirectory)
        assumeTrue("Push refs.tsv to /sdcard/Download/sirrak-h3/ first", refsFile.isFile)

        val refs = refsFile.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") && it.count { ch -> ch == '|' } >= 2 }
            .map { line ->
                val parts = line.split("|", limit = 3)
                Triple(parts[0].trim(), parts[1].trim(), parts[2].trim())
            }
        assertTrue("no references parsed", refs.size >= 20)
        refs.forEach { (id, _, _) ->
            assumeTrue("missing " + id + ".wav in the staged H3 replay", File(h3Dir, id + ".wav").isFile)
        }

        val engine = SherpaWhisperEngine()
        var totalErrors = 0
        var totalReferenceWords = 0
        var totalLatencyMs = 0L
        var totalAudioMs = 0L
        try {
            engine.load(model, DictationLanguage.ARABIC)
            refs.forEach { (id, group, reference) ->
                val wav = File(h3Dir, id + ".wav").readBytes()
                val result = engine.transcribe(wav, DictationLanguage.ARABIC)
                assertTrue("$id transcription failed: $result", result is TranscriptionResult.Success)
                val success = result as TranscriptionResult.Success
                val referenceTokens = ArabicText.tokens(reference)
                val hypothesisTokens = ArabicText.tokens(success.text)
                val errors = wordErrors(referenceTokens, hypothesisTokens)
                totalErrors += errors
                totalReferenceWords += referenceTokens.size
                totalLatencyMs += success.latencyMs
                totalAudioMs += success.audioDurationMs
                val wer = if (referenceTokens.isEmpty()) 0.0 else errors.toDouble() / referenceTokens.size
                val rtf = success.latencyMs.toDouble() / success.audioDurationMs.coerceAtLeast(1)
                Log.i(
                    TAG,
                    "SPEECH_WER id=" + id + " group=" + group + " wer=" +
                        String.format(java.util.Locale.US, "%.3f", wer) + " rtf=" +
                        String.format(java.util.Locale.US, "%.3f", rtf) + " text=" + success.text,
                )
            }
        } finally {
            engine.unload()
        }

        val overallWer = totalErrors.toDouble() / totalReferenceWords
        val overallRtf = totalLatencyMs.toDouble() / totalAudioMs.coerceAtLeast(1)
        val rssMb = readRssMb()
        Log.i(
            TAG,
            "SPEECH_STATS files=" + refs.size + " wer=" +
                String.format(java.util.Locale.US, "%.4f", overallWer) + " rtf=" +
                String.format(java.util.Locale.US, "%.3f", overallRtf) + " rss_mb=" + rssMb,
        )
        assertTrue("Arabic replay WER above the 35% gate: " + overallWer, overallWer <= 0.35)
        assertTrue("decode RTF too slow for dictation: " + overallRtf, overallRtf <= 1.2)
    }

    @Test
    fun fileStoreRejectsAnIncompleteTrioAndAcceptsACompleteOne() = runBlocking {
        val store = FileSpeechModelStore(context)
        val missing = store.import(
            listOf(byteFile("tiny-encoder.int8.onnx"), byteFile("tiny-decoder.int8.onnx")),
        )
        assertTrue("an incomplete trio must be rejected", missing.isFailure)
        assertEquals(
            SpeechReasons.IMPORT_MISSING_TOKENS,
            (missing.exceptionOrNull() as? SpeechImportException)?.reason,
        )

        val imported = store.import(
            listOf(
                byteFile("tiny-encoder.int8.onnx"),
                byteFile("tiny-decoder.int8.onnx"),
                byteFile("tiny-tokens.txt"),
            ),
        )
        assertTrue("a complete trio must import: " + imported, imported.isSuccess)
        val model = imported.getOrThrow()
        assertTrue("imported model missing from the list", store.models.value.any { it.id == model.id })
        assertEquals("the imported model was not selected", model.id, store.selected.value?.id)
        assertTrue(File(model.encoderPath).isFile)
        assertTrue(File(model.decoderPath).isFile)
        assertTrue(File(model.tokensPath).isFile)
        assertTrue("size label missing", model.sizeLabel.isNotBlank())

        val reopened = FileSpeechModelStore(context)
        assertEquals("selection did not survive a restart", model.id, reopened.selected.value?.id)

        val modelDir = File(model.encoderPath).parentFile!!
        reopened.delete(model)
        assertFalse("delete left the model directory behind", modelDir.exists())
        assertNull("delete left the model selected", reopened.selected.value)
        assertFalse("delete left the model in the list", reopened.models.value.any { it.id == model.id })
    }

    @Test
    fun fileStoreAdoptsTheStagedRealModelForTheUi() = runBlocking {
        val model = stagedModel() ?: run {
            assumeTrue("Push the whisper-small int8 trio to /sdcard/Download/ first", false)
            return@runBlocking
        }
        val store = FileSpeechModelStore(context)
        if (store.models.value.none { it.id == "small" }) {
            val staged = listOf(
                stageFile("/sdcard/Download/small-encoder.int8.onnx", "small-encoder.int8.onnx"),
                stageFile("/sdcard/Download/small-decoder.int8.onnx", "small-decoder.int8.onnx"),
                stageFile("/sdcard/Download/small-tokens.txt", "small-tokens.txt"),
            )
            val imported = store.import(
                staged.map { file ->
                    SpeechModelFile(file.name, file.length()) { file.inputStream() }
                },
            )
            assertTrue("real model import failed: " + imported, imported.isSuccess)
        }
        assertNotNull("the UI has no speech model to select", store.selected.value)
        Log.i(TAG, "SPEECH_STORE model=" + store.selected.value?.displayName + " size=" + store.selected.value?.sizeBytes)
    }

    @Test(timeout = 300_000)
    fun recorderPipelineFeedsTheRealEngineAndTheComposerChannel() = runBlocking {
        val model = stagedModel() ?: run {
            assumeTrue("Push the whisper-small int8 trio to /sdcard/Download/ first", false)
            return@runBlocking
        }
        val fixtureWav = WavCodec.read(fixture("0.wav").readBytes())
        assumeTrue("0.wav is not readable", fixtureWav != null)
        val source = WavBackedSource(fixtureWav!!.samples)
        val recorder = PcmAudioRecorder { source }
        val settings = FixedDictationSettings(DictationLanguage.ENGLISH)
        val store = FixedSpeechModelStore(model)
        val engine = SherpaWhisperEngine()
        val controller = DictationController(
            recorder = recorder,
            engine = engine,
            models = store,
            settings = settings,
            scope = this,
        )
        val transcripts = mutableListOf<String>()
        val collector = launch(Dispatchers.Unconfined) { controller.transcript.collect { transcripts += it } }
        try {
            assertTrue("recorder did not start", controller.start())
            assertEquals(DictationState.Recording::class.java, controller.state.value::class.java)
            waitFor("WAV-backed source was not consumed") { source.finished }
            controller.stopAndTranscribe()
            assertEquals("pipeline did not finish cleanly: " + controller.state.value, DictationState.Idle, controller.state.value)
            assertEquals("composer channel got no transcript", 1, transcripts.size)
            assertTrue("empty transcript from the pipeline", transcripts.first().isNotBlank())
            Log.i(TAG, "SPEECH_PIPELINE transcript=" + transcripts.first())
        } finally {
            controller.cancel()
            collector.cancel()
            engine.unload()
        }
    }

    // --- staging helpers -------------------------------------------------------------------------

    private fun speechDir(): File = File(context.getExternalFilesDir(null), "speech").apply { mkdirs() }

    private fun stageFile(sourcePath: String, fileName: String): File {
        val target = File(speechDir(), fileName)
        if (!target.isFile) shell("cp " + sourcePath + " " + target.absolutePath)
        shell("chmod 666 " + target.absolutePath)
        return target
    }

    private fun stageDir(sourcePath: String, name: String): File {
        val target = File(speechDir(), name)
        if (!target.isDirectory) shell("cp -r " + sourcePath + " " + target.absolutePath)
        shell("chmod -R 777 " + target.absolutePath)
        return target
    }

    private fun stagedModel(): SpeechModel? {
        val encoder = stageFile("/sdcard/Download/small-encoder.int8.onnx", "small-encoder.int8.onnx")
        val decoder = stageFile("/sdcard/Download/small-decoder.int8.onnx", "small-decoder.int8.onnx")
        val tokens = stageFile("/sdcard/Download/small-tokens.txt", "small-tokens.txt")
        val files = listOf(encoder, decoder, tokens)
        if (files.any { !it.isFile || !it.canRead() }) return null
        return SpeechModel(
            id = "small-device",
            displayName = "Small",
            engineId = SherpaWhisperEngine.ID,
            encoderPath = encoder.absolutePath,
            decoderPath = decoder.absolutePath,
            tokensPath = tokens.absolutePath,
            sizeBytes = files.sumOf { it.length() },
        )
    }

    private fun fixture(name: String): File = File(stageDir("/sdcard/Download/sirrak-whisper-en", "whisper-en"), name)

    private fun fixtureReference(name: String): String {
        val trans = fixture("trans.txt")
        return trans.readLines()
            .firstOrNull { it.startsWith(name + " ") }
            ?.removePrefix(name)
            ?.trim()
            .orEmpty()
    }

    private fun byteFile(name: String): SpeechModelFile =
        SpeechModelFile(name, 1024) { ByteArrayInputStream(ByteArray(1024)) }

    private fun shell(command: String) {
        runCatching {
            ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
            ).use { stream -> stream.readBytes() }
        }
    }

    private fun waitFor(message: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue(message, condition())
    }

    private fun readRssMb(): Long = runCatching {
        File("/proc/self/status").readLines()
            .firstOrNull { it.startsWith("VmRSS:") }
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.getOrNull(1)
            ?.toLong()
            ?.div(1024L)
            ?: -1L
    }.getOrDefault(-1L)

    private fun wordErrors(reference: List<String>, hypothesis: List<String>): Int {
        val dp = IntArray(hypothesis.size + 1) { it }
        for (i in 1..reference.size) {
            var previous = dp[0]
            dp[0] = i
            for (j in 1..hypothesis.size) {
                val current = dp[j]
                dp[j] = minOf(
                    dp[j] + 1,
                    dp[j - 1] + 1,
                    previous + if (reference[i - 1] == hypothesis[j - 1]) 0 else 1,
                )
                previous = current
            }
        }
        return dp[hypothesis.size]
    }

    // --- test doubles for the pipeline ------------------------------------------------------------

    /** A PCM device that replays a WAV's samples, then idles like a real microphone. */
    private class WavBackedSource(private val samples: ShortArray) : app.sirrak.PcmSource {
        private var index = 0

        @Volatile
        var finished = false

        override fun read(buffer: ShortArray): Int {
            if (index >= samples.size) {
                finished = true
                Thread.sleep(2)
                return 0
            }
            val count = minOf(buffer.size, samples.size - index)
            samples.copyInto(buffer, 0, index, index + count)
            index += count
            return count
        }

        override fun release() = Unit
    }

    private class FixedDictationSettings(
        initialLanguage: DictationLanguage,
    ) : DictationSettings {
        override val language: StateFlow<DictationLanguage> = MutableStateFlow(initialLanguage)
        override val mode: StateFlow<DictationMode> = MutableStateFlow(DictationMode.TOGGLE)
        override fun setLanguage(language: DictationLanguage) = Unit
        override fun setMode(mode: DictationMode) = Unit
    }

    private class FixedSpeechModelStore(private val model: SpeechModel) : SpeechModelStore {
        override val models: StateFlow<List<SpeechModel>> = MutableStateFlow(listOf(model))
        override val selected: StateFlow<SpeechModel?> = MutableStateFlow(model)
        override fun select(modelId: String?) = Unit
        override suspend fun import(files: List<SpeechModelFile>): Result<SpeechModel> =
            Result.failure(UnsupportedOperationException("read-only store"))
        override fun delete(model: SpeechModel) = Unit
    }

    private companion object {
        const val TAG = "SirrakDictation"
    }
}
