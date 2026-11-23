package app.sirrak.core.speech

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class DictationControllerTest {

    private val model = SpeechModel(
        id = "whisper-small",
        displayName = "Whisper small",
        engineId = "sherpa-whisper",
        encoderPath = "/m/small-encoder.int8.onnx",
        decoderPath = "/m/small-decoder.int8.onnx",
        tokensPath = "/m/small-tokens.txt",
        sizeBytes = 375_000_000,
    )

    private class FakeRecorder(
        var nextRecording: RecordedAudio? = RecordedAudio(ByteArray(4), 1_500),
        var startResult: Boolean = true,
    ) : AudioRecorder {
        var started = false
        var cancelled = false
        var stopCalls = 0
        override val isRecording: Boolean get() = started
        override fun start(): Boolean {
            started = startResult
            return startResult
        }
        override fun stop(): RecordedAudio? {
            stopCalls++
            started = false
            return nextRecording
        }
        override fun cancel() {
            cancelled = true
            started = false
        }
    }

    private class FakeEngine : TranscriptionEngine {
        override val id: String = "sherpa-whisper"
        override val displayName: String = "Sherpa Whisper"
        val statusFlow = MutableStateFlow(TranscriptionStatus.UNLOADED)
        override val status: StateFlow<TranscriptionStatus> = statusFlow
        val loadedFlow = MutableStateFlow<String?>(null)
        override val loadedModelId: StateFlow<String?> = loadedFlow
        var loadCalls = 0
        var transcribeCalls = 0
        var lastLanguage: DictationLanguage? = null
        var result: TranscriptionResult = TranscriptionResult.Success(
            text = "مرحبا",
            language = DictationLanguage.ARABIC,
            audioDurationMs = 1_500,
            latencyMs = 900,
        )
        override suspend fun load(model: SpeechModel) {
            loadCalls++
            loadedFlow.value = model.id
            statusFlow.value = TranscriptionStatus.READY
        }
        override fun unload() {
            loadedFlow.value = null
            statusFlow.value = TranscriptionStatus.UNLOADED
        }
        override suspend fun transcribe(wav: ByteArray, language: DictationLanguage): TranscriptionResult {
            transcribeCalls++
            lastLanguage = language
            return result
        }
    }

    private class FakeStore(private val initial: SpeechModel?) : SpeechModelStore {
        val modelsFlow = MutableStateFlow(initial?.let { listOf(it) } ?: emptyList())
        override val models: StateFlow<List<SpeechModel>> = modelsFlow
        val selectedFlow = MutableStateFlow(initial)
        override val selected: StateFlow<SpeechModel?> = selectedFlow
        override fun select(modelId: String?) {
            selectedFlow.value = modelsFlow.value.firstOrNull { it.id == modelId }
        }
        override suspend fun import(files: List<SpeechModelFile>): Result<SpeechModel> =
            Result.failure(IllegalStateException("not used"))
        override fun delete(model: SpeechModel) {
            modelsFlow.value = modelsFlow.value.filterNot { it.id == model.id }
            if (selectedFlow.value?.id == model.id) selectedFlow.value = null
        }
    }

    private class FakeSettings : DictationSettings {
        val languageFlow = MutableStateFlow(DictationLanguage.ARABIC)
        val modeFlow = MutableStateFlow(DictationMode.TOGGLE)
        override val language: StateFlow<DictationLanguage> = languageFlow
        override val mode: StateFlow<DictationMode> = modeFlow
        override fun setLanguage(language: DictationLanguage) { languageFlow.value = language }
        override fun setMode(mode: DictationMode) { modeFlow.value = mode }
    }

    private fun controller(
        recorder: FakeRecorder = FakeRecorder(),
        engine: FakeEngine = FakeEngine(),
        store: FakeStore = FakeStore(model),
        settings: FakeSettings = FakeSettings(),
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    ) = DictationController(
        recorder = recorder,
        engine = engine,
        models = store,
        settings = settings,
        scope = scope,
        clock = { 1_000L },
    )

    @Test
    fun `start fails honestly when no speech model is selected`() {
        val recorder = FakeRecorder()
        val dictation = controller(recorder = recorder, store = FakeStore(null))

        dictation.start()

        assertEquals(
            DictationState.Failed(SpeechReasons.NO_MODEL),
            dictation.state.value,
        )
        assertTrue(!recorder.started)
    }

    @Test
    fun `start records and stop transcribes into the transcript`() = runBlocking {
        val recorder = FakeRecorder()
        val engine = FakeEngine()
        val store = FakeStore(model)
        val settings = FakeSettings()
        val dictation = controller(recorder = recorder, engine = engine, store = store, settings = settings)

        dictation.start()
        assertEquals(DictationState.Recording(startedAtMs = 1_000L), dictation.state.value)

        val transcripts = mutableListOf<String>()
        val collector = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        collector.launch { dictation.transcript.collect { transcripts += it } }

        dictation.stopAndTranscribe()

        assertEquals(DictationState.Idle, dictation.state.value)
        assertEquals(listOf("مرحبا"), transcripts)
        assertEquals(1, recorder.stopCalls)
        assertEquals(1, engine.loadCalls)
        assertEquals(1, engine.transcribeCalls)
        assertEquals(DictationLanguage.ARABIC, engine.lastLanguage)
    }

    @Test
    fun `stop discards a recording shorter than the minimum`() = runBlocking {
        val recorder = FakeRecorder(nextRecording = RecordedAudio(ByteArray(2), durationMs = 100))
        val engine = FakeEngine()
        val dictation = controller(recorder = recorder, engine = engine)

        dictation.start()
        dictation.stopAndTranscribe()

        assertEquals(
            DictationState.Failed(SpeechReasons.RECORDING_TOO_SHORT),
            dictation.state.value,
        )
        assertEquals(0, engine.transcribeCalls)
    }

    @Test
    fun `a blank transcript becomes a no-speech failure`() = runBlocking {
        val engine = FakeEngine().apply {
            result = TranscriptionResult.Success("", DictationLanguage.ARABIC, 1_500, 500)
        }
        val dictation = controller(engine = engine)

        dictation.start()
        dictation.stopAndTranscribe()

        assertEquals(DictationState.Failed(SpeechReasons.NO_SPEECH), dictation.state.value)
    }

    @Test
    fun `a decoder sound label becomes a no-speech failure`() = runBlocking {
        // Device evidence: whisper-small, handed 1.3 s of emulator silence, returned exactly "(static)".
        val engine = FakeEngine().apply {
            result = TranscriptionResult.Success("(static)", DictationLanguage.ENGLISH, 1_300, 2_000)
        }
        val dictation = controller(engine = engine)
        val transcripts = mutableListOf<String>()
        val collector = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        collector.launch { dictation.transcript.collect { transcripts += it } }

        dictation.start()
        dictation.stopAndTranscribe()

        assertEquals(DictationState.Failed(SpeechReasons.NO_SPEECH), dictation.state.value)
        assertEquals(emptyList<String>(), transcripts)
    }

    @Test
    fun `engine failure surfaces its typed reason`() = runBlocking {
        val engine = FakeEngine().apply {
            result = TranscriptionResult.Failure(SpeechReasons.TRANSCRIPTION_FAILED)
        }
        val dictation = controller(engine = engine)

        dictation.start()
        dictation.stopAndTranscribe()

        assertEquals(
            DictationState.Failed(SpeechReasons.TRANSCRIPTION_FAILED),
            dictation.state.value,
        )
    }

    @Test
    fun `toggle stops while recording and starts from idle`() = runBlocking {
        val recorder = FakeRecorder()
        val engine = FakeEngine()
        val dictation = controller(recorder = recorder, engine = engine)

        dictation.toggle()
        assertTrue(dictation.state.value is DictationState.Recording)

        dictation.toggle()
        // The stop path launched on the controller scope; the recording is done and the engine ran.
        assertEquals(1, recorder.stopCalls)
        assertEquals(1, engine.transcribeCalls)
    }

    @Test
    fun `permission denial is a typed failure and leaves the recorder alone`() {
        val recorder = FakeRecorder()
        val dictation = controller(recorder = recorder)

        dictation.onPermissionDenied()

        assertEquals(DictationState.Failed(SpeechReasons.NO_PERMISSION), dictation.state.value)
        assertTrue(!recorder.started)
    }

    @Test
    fun `cancel aborts the recording without transcribing`() {
        val recorder = FakeRecorder()
        val engine = FakeEngine()
        val dictation = controller(recorder = recorder, engine = engine)

        dictation.start()
        dictation.cancel()

        assertEquals(DictationState.Idle, dictation.state.value)
        assertTrue(recorder.cancelled)
        assertEquals(0, engine.transcribeCalls)
    }

    @Test
    fun `a recorder that cannot start is a typed failure`() {
        val recorder = FakeRecorder(startResult = false)
        val dictation = controller(recorder = recorder)

        dictation.start()

        assertEquals(
            DictationState.Failed(SpeechReasons.RECORDER_UNAVAILABLE),
            dictation.state.value,
        )
    }

    @Test
    fun `clearFailure returns to idle`() {
        val dictation = controller(store = FakeStore(null))
        dictation.start()
        assertTrue(dictation.state.value is DictationState.Failed)

        dictation.clearFailure()

        assertEquals(DictationState.Idle, dictation.state.value)
    }

    @Test
    fun `language ids round-trip and unknown ids fall back to auto`() {
        DictationLanguage.entries.forEach { language ->
            assertEquals(language, DictationLanguage.fromId(language.id))
        }
        assertEquals(DictationLanguage.AUTO, DictationLanguage.fromId("klingon"))
        assertEquals(DictationLanguage.AUTO, DictationLanguage.fromId(null))
    }

    @Test
    fun `mode ids round-trip and unknown ids fall back to toggle`() {
        DictationMode.entries.forEach { mode ->
            assertEquals(mode, DictationMode.fromId(mode.id))
        }
        assertEquals(DictationMode.TOGGLE, DictationMode.fromId("nonsense"))
    }

    @Test
    fun `speech model files open lazily and the model size label is derived`() {
        var opened = 0
        val file = SpeechModelFile("a.onnx", 1_500_000) {
            opened++
            ByteArrayInputStream(ByteArray(0))
        }
        file.open()
        assertEquals(1, opened)

        // 375,000,000 bytes / 1 MiB = 357.6; the label is derived, never stored.
        assertEquals("357.6 MiB", model.sizeLabel)
    }
}
