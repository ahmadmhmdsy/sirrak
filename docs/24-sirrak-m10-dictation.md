# Sirrak — M10 build record: dictation (speech → text)

**Milestone:** M10 (STT-1 toggle/hold dictation with Whisper small/base and ar/en/auto; STT-2 audio never leaves the device + permission rationale; STT-3 live partials and language picker deferred to v1.1) · **Status:** closed 21 Nov 2026
**App version:** 0.1.0-m10 · **Date:** 2026-11-21

> M10 turns the audio input seam from M7 into a working capability: tap the mic in the composer (or hold it),
> speak Arabic or English, and the on-device Whisper model puts the transcript into the text field — the audio
> never leaves the phone and the transcript is never sent automatically. The speech stack is a new pure-JVM
> `:core:speech` seam (recorder, WAV, model layout, dictation state machine, silence filter), a new
> `:engine:whisper` adapter over the pinned sherpa-onnx 2.0.1 AAR (Apache-2.0), and a new `:feature:voice`
> UI module that contributes a mic button and a status strip through the existing typed slots. Two defects were
> caught by evidence, not review: R8 renamed the JNI-reflected recognizer config fields, so dictation worked in
> debug and failed only in the signed release with `NoSuchFieldError: decodingMethod` (fixed with one keep
> rule); and the emulator's silent microphone made Whisper hallucinate `(static)`, which the composer pasted —
> fixed with an RMS gate before decode plus a silence filter on the transcript. The honest limits are the model
> and the test rig: the WER gate is replayed on synthetic TTS audio, the emulator has no audio input device, and
> the phone WER gate remains open under the F8 emulator-only decision.

## 1. What landed

### :core:speech — the speech seam (pure JVM, 40 tests)

- `SpeechModels.kt`: the typed vocabulary of the milestone.
  - `SpeechReasons`: stable codes for every failure — `IMPORT_EMPTY`, `IMPORT_UNSUPPORTED_FILE`,
    `IMPORT_MISSING_ENCODER`, `IMPORT_MISSING_DECODER`, `IMPORT_MISSING_TOKENS`,
    `IMPORT_DUPLICATE_ROLE`, `IMPORT_TOO_LARGE`, `IMPORT_FAILED`, `NO_MODEL`, `ENGINE_LOAD_FAILED`,
    `NO_PERMISSION`, `RECORDER_UNAVAILABLE`, `RECORDING_FAILED`, `RECORDING_TOO_SHORT`,
    `NO_SPEECH`, `TRANSCRIPTION_FAILED`. The UI maps codes to localised strings; the engine never
    returns prose.
  - `SpeechModel` (id, display name, engine id, three absolute file paths, size) with a `sizeLabel` that
    formats MiB/GiB via `Locale.US`; `SpeechModelFile` (name, size, `open()`) so the store and the pure
    layout rule never depend on Android `Uri`; `SpeechFileRole {ENCODER, DECODER, TOKENS}`.
  - `WhisperModelLayout.match(files)`: one pure function decides whether a set of picked files is a valid
    Whisper trio — exactly one `.onnx` encoder and one `.onnx` decoder (an `int8` file wins over the
    float one when both are present), one `tokens.txt`, no duplicate role, one inferred base name. Returns
    `Matched(files, baseName)` or `Rejected(reason)`; the store and the tests share this rule.
  - `SpeechModelStore`: `models`/`selected` StateFlows, suspend `import(files)`, `select(id)`,
    `delete(model)` — the contract the app store implements and the controller consumes.
  - `SpeechSilence`: the hallucination filter. `isSilence(text)` is true for Whisper's known
    no-speech outputs (`"(static)"`, `[BLANK_AUDIO]`, `[silence]`, `[MUSIC]`, `♪`, `...` and
    friends), comparing exact and whitespace-stripped forms, so a stray space or NBSP cannot slip through.
- `Wav.kt`: `SpeechLimits` is the single source for audio policy — 16 kHz target rate, silence RMS 32.0
  (about −60 dBFS), minimum 400 ms, maximum 120 s, 600 MiB per model file. `WavCodec.writePcm16` /
  `read` handle RIFF/PCM16, 44-byte header, mono/stereo downmix; `AudioResampler.resample` is a linear
  resampler for the 22.05/8 kHz fixtures.
- `Transcription.kt`: `TranscriptionStatus {UNLOADED, LOADING, READY, FAILED}`,
  `TranscriptionCapabilities(onDevice = true, languages = all)`, `TranscriptionResult.Success(text,
  language, audioDurationMs, latencyMs)` / `Failure(reason)`, and the `TranscriptionEngine` seam
  (`id`, `displayName`, `status`, `loadedModelId`, `capabilities`, suspend `load(model)`,
  defaulted `load(model, language)`, `unload()`, suspend `transcribe(wav, language)`).
- `Dictation.kt`: `DictationLanguage {AUTO("auto",""), ARABIC("ar","ar"), ENGLISH("en","en")}` (the id is
  the persisted value, the whisper code is the engine argument — `AUTO` is the empty string Whisper reads as
  "detect"), `DictationMode {TOGGLE, HOLD}`, the `DictationSettings` interface (two StateFlows + two
  setters), `RecordedAudio(wav, durationMs)`, the `AudioRecorder` seam (`isRecording/start/stop/cancel`),
  `DictationState {Idle, Recording(startedAtMs), Transcribing, Failed(reason)}` and `DictationController`
  — the whole state machine: `start()` (typed `NO_MODEL` / `RECORDER_UNAVAILABLE`), `stop()` →
  `stopAndTranscribe()` (`RECORDING_FAILED` / `RECORDING_TOO_SHORT` / lazy engine load /
  `NO_SPEECH`), `toggle()`, `cancel()`, `onPermissionDenied()`, `clearFailure()` and one
  `transcript` SharedFlow. It has no Android or Compose type, so all 14 of its tests are plain JVM tests.

### :engine:whisper — sherpa-onnx Whisper (Apache-2.0, pinned AAR)

- The runtime is **sherpa-onnx 2.0.1**, shipped as one local AAR pinned in the repository at
  `engine/whisper/libs/sherpa-onnx-2.0.1.aar` (49,441,341 bytes, Apache-2.0, no network fetch at build
  time) and wired through a `flatDir` repository in `settings.gradle.kts`. It is the same engine family the
  Step Zero voice spike used (doc 10 §3.7), so the model behaviour carries over.
- `SherpaWhisperEngine` (`id = "sherpa-whisper"`, `DEFAULT_THREADS = 4`, log tag `SirrakSpeech`) builds
  an `OfflineRecognizer` with `FeatureConfig(16000, 80)`, `OfflineModelConfig(whisper =
  OfflineWhisperModelConfig(encoder, decoder, language, task = "transcribe"), tokens, numThreads = 4,
  provider = "cpu")` and `decodingMethod = "greedy_search"`; a `Mutex` serialises load and transcribe,
  because one recognizer is not safe for concurrent decodes.
- The engine validates the three files (exist, non-empty) before touching native code, reports
  `ENGINE_LOAD_FAILED` instead of a native crash, and rebuilds the recognizer when either the model id or
  the language changed — the language is baked into the Whisper config, so switching ar → en is a reload
  (`SPEECH_LANGUAGE_RELOAD` in the log). The language value is always `""`, `"ar"` or `"en"`: an
  unknown code makes the native layer exit, so it is never passed.
- Before decoding, the engine computes the PCM RMS and returns `Failure(NO_SPEECH)` below
  `SpeechLimits.SILENCE_RMS` (`SPEECH_SILENCE audio_ms=… rms=…`) — the second line of defence against
  hallucination, below the controller's `SpeechSilence` filter.
- Log lines that make the pipeline auditable on device: `SPEECH_MODEL_LOADED model=… language=… threads=4
  load_ms=…`, `SPEECH_LANGUAGE_RELOAD`, `SPEECH_MODEL_RELEASED`, `SPEECH_SILENCE`,
  `SPEECH_TRANSCRIBE language=… detected=… audio_ms=… latency_ms=… rtf=… chars=…` and
  `SPEECH_TRANSCRIBE_FAILED`.
- **R8 keep rule** (found by the release pass, §4.1): `-keep class com.k2fsa.sherpa.onnx.** { *; }` in
  `app/proguard-rules.pro`. The JNI layer reads the config objects' fields by name from C++, so shrinking or
  renaming them breaks the release build only.

### :app — Android plumbing

- `FileSpeechModelStore`: the host registry. One app-private directory per model under
  `filesDir/speech_models/<dir>`, imported through a `.part` directory that is renamed only after every
  file is copied and re-read, so a cancelled import can never appear as a model; the selected model is the
  directory id persisted in `sirrak_speech/selected_speech_model_id`, so there is no second index that could
  disagree with the filesystem. `scan()` re-derives models through the same pure layout rule,
  `restoreSelection()` drops a stale id, `delete()` deletes the directory and clears the selection,
  `uniqueTargetDir()` never overwrites an existing import and `displayNameFor()` turns `small` into
  `Small`.
- `PcmAudioRecorder`: the microphone behind the pure `AudioRecorder` seam. One daemon thread reads
  `AndroidPcmSource` in 1,600-sample (100 ms) chunks into memory (a two-minute turn is ≈3.8 MB, so a temp
  file would only add failure modes), caps capture at `MAX_RECORDING_MS`, and on stop converts the buffer
  with `WavCodec` so the engine always receives a real 16 kHz mono WAV. `PcmSource` is the test seam: the
  device test feeds a WAV-backed fake source through the exact same loop, so the recorder → controller → engine
  pipeline is proven without an emulator microphone. `AndroidPcmSource` opens
  `MediaRecorder.AudioSource.VOICE_RECOGNITION` at 16 kHz mono, checks `RECORD_AUDIO` and returns null
  (→ `RECORDER_UNAVAILABLE`) instead of crashing when the permission was revoked or the device has no
  input.
- `SharedPrefsDictationSettings`: the two persisted preferences (language id, mode id) resolved defensively
  through the enums' `fromId`, so an unknown stored value falls back to `AUTO`/`TOGGLE`.
- `AsrPlugin` (id `asr`) owns the speech composition: it constructs the engine, store, settings and
  recorder, provides them under their seams and provides one `DictationController` with its own
  `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`, so a transcription in flight survives a
  recomposition and is cancelled only at graph disposal; its effect cancels the scope first and then releases
  the native recognizer (reverse order). No feature module names sherpa-onnx or `AudioRecord`.
- `VoicePlugin` (id `voice`) requires those five services plus the composer channel and owns exactly one
  bridge: `controller.transcript.collect { requests.append(it) }`. It contributes three slot entries:
  `ChatComposerTrailing` order 20 (the mic button), `ChatComposerAbove` order 10 (the status strip, only
  while the state is not `Idle`, so the composer keeps its exact height until the mic is used) and
  `SettingsSections` order 30 (the dictation card).
- `ComposerTextRequests` in `:core:runtime`: a buffered one-way channel to the chat composer. The voice
  plugin emits a transcript; `ChatScreen` collects it into the text field it already owns. The chat feature
  never imports the voice feature and the composer stays the single owner of its input.
- `Slots.ChatComposerAbove` is a new list slot owned by `feature:chat`, declared in the slot table like
  every other slot; `ChatScreen` renders it directly above the composer bar and gives the contributor all
  padding.
- Manifest: `RECORD_AUDIO` permission only (no network permission anywhere). `app.json` adds `asr` and
  `voice` to `features.order`; `AppComposition` hosts `ComposerTextRequests` and installs
  `AsrPlugin(application)` and `VoicePlugin()`.

### :feature:voice — the UI

- `DictationMicButton`: the composer mic. It shows the permission rationale dialog first, then launches the
  system request, remembering that it asked once so a denial is not re-prompted in a loop; `TOGGLE` mode is a
  clickable button, `HOLD` mode uses `detectTapGestures(onPress / tryAwaitRelease)`; the icon switches to a
  stop square while recording and the button is disabled while transcribing. It exposes
  `contentDescription`, `Role.Button` and an explicit `onClick` (HOLD only) for TalkBack.
- `DictationStatusRow`: the strip above the composer, three states in one component — **Recording**
  (waveform glyph, "Listening…", an LTR `m:ss` timer, Stop and Cancel), **Transcribing** (spinner and
  "Transcribing…"), **Failed** (error glyph, the localised reason and Dismiss). The timer is forced LTR so
  Arabic digits do not reorder.
- `VoiceSettingsSection`: the Settings → Dictation card. Status (model loaded/not), the selected model and
  its size, an import row that opens the multi-document picker (`OpenMultipleDocuments`, `*/*`, three files
  at once) and feeds `SpeechFilePicker.filesFrom` into the store, the installed-model list with a selected
  badge and delete-with-confirmation, a language choice row (`AUTO/ARABIC/ENGLISH`), a mode row
  (`TOGGLE/HOLD`) and a note that transcription runs on the device and the model files stay in app-private
  storage.
- Strings live in `feature/voice/src/main/res/values/strings.xml` (Arabic) and `values-en` (English);
  nothing user-visible is hardcoded. `values` is Arabic by product rule, so the default build shows the
  Arabic card and the English one is a resource override.

### Tests

- **40 new JVM tests in `:core:speech`:** `WavCodecTest` 8 (header/round-trip/mono-stereo downmix/limits),
  `AudioResamplerTest` 4 (8 k→16 k, 22.05 k→16 k, length/amplitude), `WhisperModelLayoutTest` 9 (int8
  preference, missing roles, duplicate roles, base-name inference, size/format rejection),
  `DictationControllerTest` 14 (no model, recorder unavailable, too short, failed recording, lazy load,
  load failure, silence filter, transcript emission, cancel, permission denied, clear failure, toggle) and
  `SpeechSilenceTest` 5 (exact/space-stripped/known labels/normal text).
- **6 new tests in `:app`:** `PcmAudioRecorderTest` (start/stop round-trip, failed source, empty capture,
  cancel, duration cap, WAV header).
- **3 new tests in `:core:runtime`:** `ComposerTextTest` (append, trim, blank ignored).
- **8 new device tests** in `DictationDeviceTest` (see §5).

## 2. Architecture

### 2.1 Seams and module boundaries

```text
feature:voice ──▶ DictationController (state machine, transcript flow) ──▶ TranscriptionEngine seam
      │                        │                                                  │
      │                        └──▶ SpeechModelStore seam ──▶ :app FileSpeechModelStore
      │                        └──▶ AudioRecorder seam ──▶ :app PcmAudioRecorder ──▶ AudioRecord
      │                        └──▶ DictationSettings seam ──▶ :app SharedPrefsDictationSettings
      └──▶ typed slots: ChatComposerTrailing (mic), ChatComposerAbove (status), SettingsSections (card)
                              │
:feature:chat ──▶ composer text field ◀── ComposerTextRequests ◀── VoicePlugin bridge

:core:speech  = contracts + pure policy (no Android, no native)
:engine:whisper = sherpa-onnx adapter behind TranscriptionEngine
```

The controller and the whole speech policy are Android-free and native-free; the engine is behind the same
`TranscriptionEngine` contract the controller consumes, so a different runtime (a future LiteRT-LM audio
model, a system speech service) can implement the same seam without touching the UI, and every branch of the
dictation behaviour is unit-tested on the JVM. That is the same seam discipline M2–M9 used for text, vision
and embeddings.

### 2.2 The dictation pipeline

```text
mic tap / press
  → DictationController.start(): model selected? recorder opened?  (NO_MODEL / RECORDER_UNAVAILABLE)
  → PcmAudioRecorder: AudioRecord 16 kHz mono PCM16 → 100 ms chunks → in-memory buffer (cap 120 s)
  → stop(): WAV (RIFF/PCM16) + duration; < 400 ms → RECORDING_TOO_SHORT
  → engine.load(model, language) if not already loaded  (ENGINE_LOAD_FAILED)
  → SherpaWhisperEngine.transcribe(wav, language):
       validate files → RMS gate (< 32 ≈ −60 dBFS) → Failure(NO_SPEECH)
       OfflineRecognizer decode (greedy, 4 threads) → text, detected language, latency
  → SpeechSilence.isSilence(text)?  → Failure(NO_SPEECH)      ← catches "(static)"-style output
  → transcript.tryEmit(text) → VoicePlugin bridge → ComposerTextRequests → composer input
  → state = Idle
```

The transcript is appended to whatever the user already typed; it is never sent. The user still presses send.
That is a product rule, not an implementation detail: dictation fills the composer, exactly like a keyboard.

### 2.3 The three-file model contract

A Whisper model is three user-imported files, and the app never bundles weights (Apache-2.0 models are still
hundreds of MB). `WhisperModelLayout.match` accepts exactly:

```text
encoder:  <base>-encoder.int8.onnx   (an int8 file wins over a float one when both are picked)
decoder:  <base>-decoder.int8.onnx
tokens:   <base>-tokens.txt
```

The import copies all three into one `.part` directory, verifies each copy is non-empty, renames the
directory into place and only then rescans and selects it. A partial pick is rejected with a typed reason
before any byte is copied. `FileSpeechModelStore` re-derives models from the filesystem through the same rule,
so a directory edited by hand simply stops being a model instead of corrupting the list. The model is
engine-agnostic data: swapping the Whisper runtime for another engine changes the engine module, not the
store or the UI.

### 2.4 Failure vocabulary

| Failure | Code | UI |
| --- | --- | --- |
| no model selected | `no-model` | strip: import or select a model first |
| recorder cannot open (permission revoked, no input) | `recorder-unavailable` | strip with the localised reason |
| capture failed / empty | `recording-failed` | strip with dismiss |
| recording shorter than 400 ms | `recording-too-short` | strip with dismiss |
| engine load failed (files missing/corrupt, native error) | `engine-load-failed` | strip with dismiss |
| silence before decode | `no-speech` | "no speech was heard in the recording" |
| silence after decode (hallucination filter) | `no-speech` | same |
| microphone permission denied | `no-permission` | "dictation needs the microphone permission" |
| import problems | `import-…` | the import row reports the specific reason |

No failure is silent, and none pastes a guess into the composer.

### 2.5 Composer ownership and the slot bridge

The chat feature owns the text field and the composer bar. The voice feature owns the microphone and the
dictation state. They meet at two typed slots and one request channel: the mic is a
`ChatComposerTrailing` contributor, the status strip is a `ChatComposerAbove` contributor, and the
transcript travels over `ComposerTextRequests`. Adding a second producer (a future "insert citation" action)
is another caller of the same channel, not another screen or a second text field. This is the M1 slot rule
applied to audio.

### 2.6 Privacy and cost

Audio is captured into app memory, converted to WAV and decoded by a native model inside the process; nothing
is written to a public folder, uploaded or logged as audio. The app has no network permission. The Whisper
model files are user-imported through SAF and stay in app-private storage; the APK bundles the runtime, not
the weights. A dictation turn costs one recognizer load (≈2.4–4.1 s measured, §3) and one decode whose RTF is
below 1 on the emulator, and the recognizer is released when the graph is disposed.

## 3. Device evaluation

Measured on the project emulator (Nawa_Phone, API 36, x86_64) with **Whisper small int8**
(encoder 112,442,483 B + decoder 262,226,114 B + tokens 816,730 B = **375,485,327 B / 358.1 MiB**),
production stores, signed release build. The Arabic gate replays the 20 H3 synthetic WAVs (doc 10 §3.7, Piper
ar_JO TTS, 22,050 Hz → resampled to 16 kHz).

| Check | Result |
| --- | --- |
| Model load | `SPEECH_MODEL_LOADED model=small language=auto threads=4 load_ms=4116` (cold release run); device-suite loads 2,392 / 2,562 / 2,859 ms |
| English fixture | exact transcript `After early nightfall the yellow lamps would light up here and there the squalid quarter of the brothels.`; WER 0.0, RTF 0.49–0.53 |
| Auto-detect | `language=auto` on the English fixture → `detected=en` |
| 8 kHz input | 8 kHz WAV resampled to 16 kHz and transcribed correctly |
| Arabic replay gate | 20/20 files, **WER 0.2877 (28.77%)**, average **RTF 0.642**, peak RSS **982 MB**; gate WER ≤ 0.35 / RTF ≤ 1.2 — passed |
| Silence gate | 1.6 s of zeros → `SPEECH_SILENCE audio_ms=1600 rms=0.0` → `Failure(NO_SPEECH)`, no hallucination |
| Recorder pipeline | WAV-backed fake source through the real recorder loop → real engine → exact transcript, delivered through `ComposerTextRequests` |
| Model store | an incomplete trio is rejected with its typed reason; a complete trio imports, is selected and appears in the list; the staged real model is adopted by the UI |
| Release UI run | record 4.4 s → model load 4.1 s → `NO_SPEECH` strip; permission rationale, system dialog and denied states captured (§7) |
| APK | 145,367,120 bytes (138.63 MiB) — M9 was 77,790,584 bytes (74.2 MiB); the delta is the sherpa-onnx native libraries for two ABIs |

Two honest notes on these numbers. First, the WER gate is measured on **synthetic TTS audio**, not human
speech: it proves the pipeline and the model's shape, not field accuracy — the H3 recording sheet (doc 11) is
still the real gate and the founder's F8 decision (emulator only, no test phone) keeps it open. Second, the
Arabic gate's RTF is stable at 0.64–0.82 across reruns but a cold, CPU-contended first run measured 1.353;
the gate stays ≤ 1.2 and the outlier is recorded here rather than hidden. The 20 files are 22.05 kHz mono, so
the run also exercises `AudioResampler` on every file.

## 4. Findings and fixes (evidence-driven)

### 4.1 Release-only engine load failure: R8 renamed the JNI-reflected config fields

The signed release build's first dictation attempt failed with the localised
`تعذّر تحميل محرّك الإملاء. تحقّق من ملفات الطراز.` while the debug build worked. Logcat showed the real
cause:

```text
sherpa-onnx ... Failed to get field ID for 'decodingMethod'
java.lang.NoSuchFieldError: no "Ljava/lang/String;" field "decodingMethod" in class
  "Lcom/k2fsa/sherpa/onnx/OfflineRecognizerConfig;"
SPEECH_MODEL_LOAD_FAILED model=small
```

The sherpa-onnx JNI layer constructs `OfflineRecognizerConfig` and reads its fields by name from C++, so R8's
renaming/minification breaks the contract even though the Kotlin compiles. Fix: one keep rule in
`app/proguard-rules.pro` (`-keep class com.k2fsa.sherpa.onnx.** { *; }`, with the failure mode written in
the comment). Rebuilt the release in 1m44s, re-signed, reinstalled — dictation then worked in the release
build and the silence path produced `NO_SPEECH`. This is exactly the class of bug a debug-only verification
cannot catch, and the reason the release pass runs the feature, not just the install.

### 4.2 The emulator's silent microphone made Whisper hallucinate `(static)`

The emulator is started with `-no-audio`, so `AudioRecord` returns zeros; Whisper decoded that silence into
`(static)` and the composer pasted it. Two defences were added, at two different layers: the engine now
computes the PCM RMS before decoding and returns `NO_SPEECH` below `SILENCE_RMS` (log:
`SPEECH_SILENCE audio_ms=1600 rms=0.0`), and the controller filters known no-speech outputs through
`SpeechSilence` after decoding. The same test now proves the end-to-end silence path (1.6 s of zeros →
typed failure, empty composer), and the release screenshot shows the localised strip.

### 4.3 The device suite resets app data — model selection must be re-checked after it

The full `connectedDebugAndroidTest` run installs the debug app, and Gradle uninstalls the app afterwards;
the fresh install at 14:28:40 is visible as `firstInstallTime` in `dumpsys package`, and the seeded chat
model, speech model and conversation were gone after the suite. The M9 tests also mutate shared selection
state (a synthetic model import/delete clears the chat selection; a synthetic speech trio clears the speech
selection). Practical consequence, now written down for every future milestone: run the device suite **before**
the release evidence pass, or re-seed and re-verify after it. That is what happened here — the final release
APK was reinstalled fresh, the speech trio and SmolVLM + projector were re-imported through the real SAF
pickers, and dictation was re-verified on the release build (record → transcribe → `NO_SPEECH`), so the
screenshots in §7 come from a release build with genuinely imported models.

## 5. Testing

JVM unit tests (debug variant, definitive counts from `build/test-results`):

| Module | Tests |
| --- | --- |
| `:core:runtime` | 81 |
| `:core:rag` | 62 |
| `:core:design` | 47 |
| `:core:speech` | 40 |
| `:feature:chat` | 73 |
| `:engine:embeddings` | 6 |
| `:app` | 155 |
| **Total** | **464** |

All green, 0 failures, 0 errors, 0 skipped (M9 was 415; +49 M10 tests: `:core:speech` +40,
`:app` +6, `:core:runtime` +3). `:app:assembleDebug`, `:app:assembleRelease` and the signed release
build all pass; the M3–M9 suites were not modified.

Device tests: `DictationDeviceTest` (8) added to the existing `VisionDeviceTest` (5),
`RagDeviceEvaluationTest` (3), `EngineIsolationDeviceTest` (2) and `PdfBoxExtractorDeviceTest` (10) =
**28 tests**, run together by `:app:connectedDebugAndroidTest`.

Final device run: **28/28 tests, 0 skipped, 0 failed; test time 263.68 s, Gradle wall clock 4m52s**:

```text
DictationDeviceTest#arabicReplayMeetsTheWerGate                                  59.430 s
DictationDeviceTest#recorderPipelineFeedsTheRealEngineAndTheComposerChannel       6.201 s
DictationDeviceTest#engineTranscribesTheEnglishFixture                            6.007 s
DictationDeviceTest#engineAutoDetectsTheLanguageWhenAsked                         5.587 s
DictationDeviceTest#engineResamplesTheEightKilohertzFixture                       5.557 s
DictationDeviceTest#engineRejectsSilenceInsteadOfHallucinating                    2.973 s
DictationDeviceTest#fileStoreAdoptsTheStagedRealModelForTheUi                     0.103 s
DictationDeviceTest#fileStoreRejectsAnIncompleteTrioAndAcceptsACompleteOne        0.034 s
```

The Arabic gate test asserts WER ≤ 0.35, average RTF ≤ 1.2 and logs
`SPEECH_STATS files=20 wer=0.2877 rtf=0.642 rss_mb=982`; the English test asserts WER 0.0 (the fixture
transcript must match exactly); the pipeline test pushes a WAV through `PcmSource` → `PcmAudioRecorder` →
`DictationController` → the real engine → the composer channel and asserts the transcript text; the model
tests exercise the real filesystem store with a synthetic trio and the staged real model. All model files are
staged from `/sdcard/Download` and the tests `assumeTrue`-skip when absent, so a fresh emulator without
models still runs the rest of the suite.

## 6. Build record

| Item | Value |
| --- | --- |
| Version | `versionCode 10`, `versionName 0.1.0-m10` |
| JVM verification | `:core:design:testDebugUnitTest :core:runtime:testDebugUnitTest :core:speech:testDebugUnitTest :feature:chat:testDebugUnitTest :app:testDebugUnitTest`: BUILD SUCCESSFUL, 464 tests, 0 failures |
| Device verification | 28/28 tests, 0 skipped, 0 failed; test time 263.68 s, Gradle 4m52s |
| Release build | `:app:assembleRelease`: BUILD SUCCESSFUL (R8 minified, resources shrunk, x86_64 + arm64-v8a native); rebuilt in 1m44s after the R8 keep-rule fix |
| Release APK | `app-release-sirrak.apk`, **145,367,120 bytes (138.63 MiB)**, signed with the debug keystore (cert SHA-256 `c4a103636f460152cf1353b3afca99bf9b41740c75412b6db28d07546bc86094`), APK SHA-256 `2070A9DFC5A531D55394E22656D8B78984CD41366C37957A435919128F4B5ED7` |
| Superseded artifact | the first M10 release (145,072,120 bytes, SHA-256 `A2244974A42B02AA2C30A8176660310F7C462A0E09944D6C0BCEFF1A72283272`) lacked the R8 keep rule and failed at recognizer load; it is not shipped |
| Install sequence | signed release installed over the debug build with data preserved (screenshots §7); the full device suite then uninstalled the app (Gradle behaviour, §4.3); final signed release installed fresh at 14:28:40, models re-imported through SAF and dictation re-verified on it |
| Size note | The APK grew 67.6 MiB over M9 because sherpa-onnx ships native libraries for both ABIs (the AAR alone is 49.4 MiB) and no model weights are inside. ABI splits and on-demand model download are M11 packaging tasks. |

The nine screenshots in §7 were captured from the signed release build (versionCode 10) with the production
stores seeded by SAF imports: Whisper small selected (358.1 MiB) and SmolVLM-500M + its projector selected for
chat, on the seeded Arabic UI.

## 7. UI evidence (signed release, seeded production data)

| Screenshot | What it shows |
| --- | --- |
| ![Mic in the composer](screenshots/m10-chat-mic.png) | The chat composer with the new mic button, idle state, Arabic UI |
| ![Recording](screenshots/m10-recording.png) | The listening strip above the composer: waveform, "Listening…", the LTR timer at 0:01, Stop and Cancel, the mic turned into a stop square, and the green microphone privacy dot |
| ![Transcribing](screenshots/m10-transcribing.png) | The transcribing strip with the spinner and the localised "جارٍ التفريغ…" label after a 3 s recording |
| ![No speech](screenshots/m10-no-speech.png) | The typed failure strip "لم يُسمع كلام في التسجيل." with Dismiss — the silence gate, not a pasted hallucination |
| ![Permission rationale](screenshots/m10-permission-rationale.png) | The in-app rationale dialog before the system prompt: why the mic is needed and that the audio stays on the device |
| ![System permission](screenshots/m10-permission-system.png) | The Android permission dialog "Allow Sirrak to record audio?" |
| ![Permission denied](screenshots/m10-permission-denied.png) | The denied state: the strip explains that dictation needs the microphone permission |
| ![Settings card 1](screenshots/m10-settings-dictation.png) | Settings → Dictation: status, selected model and size, import row and the installed model with its "محدد" badge |
| ![Settings card 2](screenshots/m10-settings-dictation2.png) | The same card scrolled: language row (تلقائي selected), mode row (نقرة selected) and the privacy note |

## 8. Deferred / honest gaps

- **Phone WER gate is still open.** The 28.77% figure is replayed synthetic TTS audio on the emulator; the H3
  human-recording sheet (doc 11) remains the real gate, and the founder's F8 decision (no test phone) defers
  it. The Iraqi-dialect risk number from the spike (46.8% on Piper audio) is not improved by shipping.
- **The emulator has no microphone.** `-no-audio` means real capture cannot be exercised end-to-end; the
  recorder loop is proven with a WAV-backed fake source, the engine with replayed real speech WAVs, and the
  UI with the real permission flow. A real-phone capture test is part of the deferred phone day.
- **STT-3 stays v1.1.** Live partial transcripts (streaming decode) and a per-conversation language picker are
  not built; the language is a global setting and the transcript appears once, after stop. The native
  recognizer is offline/non-streaming by design in this milestone.
- **One language at a time.** The language is baked into the recognizer config, so switching it reloads the
  model (~2.4–4.1 s). Whisper's own auto-detect only distinguishes the languages it was asked to; the app only
  ever passes `""`, `"ar"` or `"en"` because an unknown code exits the native layer.
- **RTF is emulator-shape.** Average 0.64, cold-contended outlier 1.353; a mid-range phone and a long dictation
  turn are unmeasured. The 120 s cap bounds the worst case.
- **Model import is manual and three files.** A user must import encoder + decoder + tokens through SAF; the
  picker makes it one multi-select and the layout rule rejects mistakes with typed reasons, but it is still
  friction — the M11 model manager/downloader is the real fix. No weights are bundled.
- **APK size jumped 67.6 MiB.** sherpa-onnx native libraries for two ABIs. ABI splits, a smaller Whisper model
  as the default and on-demand download are M11 packaging decisions.
- **No TTS and no live voice mode.** Dictation is speech → text; speaking the answer back is M13 (TTS/voice
  mode). The audio-attachment kind (audio transcript files) stays M7-gated and is not enabled by M10.
- **Isolation not wired.** Whisper runs in-process; the ADR-4 process-isolation spike is still the plan for the
  whole engine surface before public beta, and dictation adds another native surface to it.
- **Hold-to-talk is UI-tested, not audio-tested.** The `HOLD` gesture and its state machine are covered, but
  on the emulator with no input device the full press-hold-speak-release path is a phone-day check.
