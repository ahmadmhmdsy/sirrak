# 05 — Technical architecture

**App:** Nawa (نواة) · **Platform:** Android 10+ (API 29), targeting API 36 · **ABI:** arm64-v8a
**Language:** Kotlin 2.x + Jetpack Compose · **Licence:** Apache-2.0 (ours) with third-party notices
**Status:** design for v1.0; validated by Step Zero before Phase 1.

---

## 1. Architecture principles

1. **Layered, engine-agnostic core.** UI and domain never import engine-specific types. One
   InferenceEngine interface; llama.cpp and LiteRT-LM are implementations.
2. **Offline-first data.** Room is the source of truth; no network dependency for chat, RAG, voice or
   settings. Network only for model catalogue/downloads and opt-in services.
3. **Process isolation for the engine.** Inference runs in a dedicated Android process (or at minimum
   a bound service) so a native crash does not kill the UI — a model load crash becomes a recoverable
   error, not a 1-star review.
4. **Foreground service for long jobs.** Downloads and indexing run in WorkManager; inference when
   screen-off/voice mode runs in a foreground service with wake lock and notification.
5. **No analytics, no cloud.** No Firebase, Crashlytics, Supabase, ads or tracking SDKs in the free
   flavour. Crash reports are local and user-exported.
6. **Two engines maximum in v1.** llama.cpp (GGUF breadth, CPU/GPU) + LiteRT-LM (NPU packs).
   Whisper/ONNX are separate speech modules, not LLM engines.
7. **Every third-party binary has a licence and a NOTICE entry**; the app has an in-app third-party
   licences screen generated from a checked-in SBOM.
8. **Arabic is a module, not an afterthought.** Arabic prompt templates, eval harness, typography
   and locale resources live in a core module used by every feature.

---

## 2. Module graph

    :app                      (Compose, navigation, DI wiring, flavours)
      |
      +-- :feature:onboarding
      +-- :feature:chat
      +-- :feature:models
      +-- :feature:library          (documents + RAG UI)
      +-- :feature:voice            (dictation, TTS, voice mode)
      +-- :feature:settings
      +-- :feature:benchmark
      |
      +-- :core:ui                  (theme, Arabic typography, RTL helpers, components)
      +-- :core:common              (Result, dispatchers, logging, time)
      +-- :core:data                (Room DB, repositories, DataStore, storage)
      +-- :core:domain              (use cases, models, interfaces — pure Kotlin)
      +-- :core:llm                 (InferenceEngine interface, model loader, tokenizer, prompts)
      +-- :core:rag                 (parsing, chunking, embeddings, index, retrieval)
      +-- :core:speech              (Whisper STT, TTS engines, audio routing)
      +-- :core:modelcatalog        (HF client, curated catalogue, download manager, recommender)
      +-- :core:privacy             (network audit, offline toggle, export/delete)
      +-- :core:test-utils
      |
      +-- :engine:llamacpp          (JNI wrapper, CMake, GGUF metadata)
      +-- :engine:litertlm          (LiteRT-LM wrapper, NPU pack manager)
      +-- :engine:whisper           (whisper.cpp via JNI or sherpa-onnx)
      +-- :engine:tts               (sherpa-onnx Piper/Kokoro, system TTS wrapper)
      +-- :engine:embeddings        (EmbeddingGemma 2 via LiteRT-LM EmbeddingEngine — spike-proven
      |                             18 Nov 2026; llama.cpp embed as fallback)

**Dependency rule:** features depend on core/domain; engines are injected. No feature imports an
engine module directly. This is what makes backend fallback and future engines (ExecuTorch/MNN)
possible without rewriting screens.

**M1 implementation note (18 Nov 2026):** the product app at `.` implements an earlier, smaller
subset of this graph — `:app`, `:core:runtime` (composition/contracts/config), `:core:design` (tokens,
components), `:feature:chat`, `:feature:settings` — with a demo engine behind a slimmer
`InferenceEngine` seam (`fun reply(prompt, history): Flow<String>`). This document remains the v1.0
target; the growth path from M1 to it (engine, data, speech, RAG modules) is recorded in doc 13 §8.

---

## 3. Engine layer

### 3.1 InferenceEngine interface (domain)

    interface InferenceEngine {
        suspend fun load(config: ModelLoadConfig): EngineHandle
        fun generate(handle: EngineHandle, request: GenerateRequest): Flow<GenerationEvent>
        suspend fun embed(handle: EngineHandle, texts: List<String>): List<FloatArray>
        suspend fun unload(handle: EngineHandle)
        val capabilities: EngineCapabilities  // vision, embeddings, tool calling, backends
        val backends: List<Backend>           // CPU, OPENCL, HEXAGON, QNN, NNAPI...
    }

- GenerationEvent: Token(text), PrefillProgress(tokens), Done(stats), Error(recoverable).
- GenerateRequest: messages, system prompt, sampling params, stop sequences, images, grammar.
- Sampling config passed through: temperature, top-k, top-p, min-p, repeat penalty, seed, max tokens,
  stop.

### 3.2 llama.cpp engine (primary)

- **Integration:** prebuilt llama.cpp static/shared lib built with CMake + NDK 27, arm64-v8a only,
  flags GGML_OPENMP=ON, GGML_KLEIDIAI=ON, optional GGML_OPENCL=ON for Adreno GPU. JNI bridge modelled
  on llama.cpp's own Android example (MIT) with llama.rn's C++ layer as reference only.
- **KV cache:** configurable context; keep a per-conversation KV snapshot for branch/regenerate
  (llama.cpp supports state save/load).
- **Vision:** mmproj multimodal path for Gemma 4 / Qwen3.5 VL (image embeddings via clip).
- **Embeddings:** LiteRT-LM **EmbeddingEngine** with an EmbeddingGemma-2 `.litertlm` (768-dim,
  spike-proven 18 Nov 2026: ~160–200 ms/query, 572 MB peak RSS on the emulator CPU — doc 10 §3.6);
  llama.cpp embedding mode stays the fallback where the AAR/runtime is unavailable. No ONNX runtime
  needed for embeddings.
- **Threads:** big cores count minus one; batch 512; ubatch 128; flash attention where supported.
- **Backends:** CPU (NEON/KleidiAI), OpenCL (Adreno 700+), Hexagon (experimental, behind a flag and a
  capability check — never default).
- **Memory safety:** compute model size + kv cache + compute buffer estimate; refuse load if it
  exceeds available RAM x 0.8; use mmap where possible.

### 3.3 LiteRT-LM engine (NPU packs)

- Dependency: com.google.ai.edge.litertlm:litertlm-android (verify current coordinates at build).
- **Spike pin, 18 Nov 2026:** `litertlm-android:0.19.1` (latest on Google Maven; Apache-2.0). It ships
  Kotlin metadata 2.4.0 — a consuming module needs **Kotlin 2.3+** — and Java 21 class files (v65);
  JNI libs for arm64-v8a **and x86_64** are inside the AAR. The H2 spike avoided the Kotlin constraint
  by being Java-only with JDK 21; the Kotlin product must move to Kotlin 2.3+ before this module
  lands. No separate native build step is needed for LiteRT-LM (unlike llama.cpp).
- **H2 API proof:** engine init + streamed generation + close work on the emulator x86_64 CPU backend
  (doc 10 §3.5). Streaming deltas for reasoning models may arrive in `Message.channels` (e.g. Qwen3
  `thought`) rather than `contents`; pass `enable_thinking=false` in per-message `extraContext` to
  get plain answers, and never render hidden reasoning in the chat UI.
- Model packs: .litertlm files from Hugging Face litert-community (Gemma 4 E2B/E4B, Qwen3.5,
  EmbeddingGemma, Kokoro, whisper variants) — catalogue metadata bundled, files downloaded on demand.
- NPU path selected by vendor (Qualcomm QNN, MediaTek NeuroPilot, Tensor TPU) via LiteRT accelerator
  runtime; app reads device SoC and enables only what the capability matrix proves.
- **Honesty rule:** if the NPU backend initialises but falls back to CPU at runtime, report the actual
  backend in Settings and in benchmark output.
- **Licensing:** LiteRT-LM is Apache-2.0; vendor accelerator binaries may carry their own terms —
  keep them out of the F-Droid flavour (see section 11).

### 3.4 Speech engines

- **STT:** whisper.cpp via JNI preferred for Arabic quality; sherpa-onnx offline recognizer as size
  fallback. **Spike evidence (18 Nov 2026, emulator CPU, doc 10 §3.7):** whisper int8 transcribes our
  Piper TTS audio at **27.4% WER overall / 15.0% MSA** with whisper-small (base: 46.6% / 38.3%), but
  whisper-small runs at RTF 0.82 with ~1 GB peak RSS next to TTS, while base runs RTF 0.25 at ~545 MB.
  Plan: ship a base-class size for dictation and streaming, and only promote small (or a
  distil/fine-tuned Arabic Whisper) after phone measurements; accuracy/latency/memory is a measured
  trade-off, not a guess.
- **TTS:** sherpa-onnx **v2.0.1** + **piper-phonemize 1.5.9** — both pinned AARs. sherpa-onnx ≥ 2.0 no
  longer bundles espeak-ng, so the phonemizer AAR is mandatory for Piper/Kokoro and
  `GenerationConfig.phonemeCodepoints` is the API (the 1.x `--vits-data-dir` path is ignored). Models:
  vits-piper-ar_JO-kareem-medium (Piper, ~18 MB int8, 22.05 kHz) for Arabic, Kokoro-82M for English,
  Android TextToSpeech as fallback. Both AARs are Apache-2.0 and packaged with the app (no JitPack —
  piper-phonemize must come from the GitHub release).
- **Arabic diacritization is a pipeline requirement, not an option:** espeak-ng phonemizes unvocalized
  Arabic wrongly ("سهلة" → `shlt`, "كم" → `kilometer`), so Piper Arabic TTS must be fed diacritized
  text — v1 needs a small Arabic diacritizer (fine-tuned seq2seq) or rule/lexicon fallback before the
  TTS engine. Validated in the H3b round-trip (doc 10 §3.7); this is why the product's TTS feature
  (Phase 4) is scheduled after the text/RAG wedge.
- **Voice mode (v1.1):** audio record → VAD (WebRTC VAD or sherpa VAD) → STT → chat → TTS, with
  barge-in via audio focus; streaming ASR + endpointing are product work (the spike used file-based
  non-streaming decode).

---

## 4. Data layer

### 4.1 Storage model

| Data | Store | Notes |
|---|---|---|
| Models | app-private files dir or SAF tree (user choice) | multi-GB; checksum; resumable download temp |
| Chat messages/conversations | Room (SQLite) | FTS5 for search |
| Documents + extracted text | Room + app files | copy original into app storage for reliability |
| Chunks | Room | text, page/offset, doc id, hash |
| Embeddings | sqlite-vec extension in Room DB (or file-based index) | float32/float16; cosine |
| Settings | DataStore | incl. per-model overrides |
| Prompt templates / personas | JSON assets + Room for user edits | versioned |
| Benchmark results | Room | local only unless opt-in upload |
| Diagnostics | rolling file log | local; export via share |

### 4.2 RAG pipeline

    import → detect type → extract text (PDFBox-Android / POI for docx / jsoup for epub+html / plain)
           → clean + Arabic normalisation → chunk (300–500 tokens, 10–15% overlap)
           → embed (EmbeddingGemma 2, batched) → store vectors + metadata
    query  → normalise → embed query → hybrid search (vector top-k + BM25 top-k, RRF fusion)
           → optional rerank (v1: RRF + recency/heading boost)
           → build prompt with numbered sources + Arabic instructions
           → generate with citations → parse citations → UI links to chunk/page

- **Library choices (all permissive):** PDFBox-Android (Apache-2.0) for PDF text; Apache POI or
  docx4j for DOCX; jsoup (MIT) for EPUB/HTML; kotlinx-serialization for metadata.
- **Arabic handling:** normalisation for matching (alef/hamza forms, tatweel, diacritics) but keep
  original text for display; sentence splitter aware of Arabic punctuation.
- **Grounded mode:** strict (answer only from sources, cite or say not found), balanced (default),
  creative (allow model knowledge, still cite).
- **Index lifecycle:** per-document index; model change → warn + offer reindex; delete doc → cascade.
- **Spike evidence (18 Nov 2026):** retrieval half proved with `embeddinggemma-2-text-270m.litertlm`
  (157 MB, 768 dims): 20/20 Arabic top-1 over 8 docs that include adjacent-topic distractors, 4/4
  English→Arabic cross-lingual, ~160–200 ms/query, 572 MB peak RSS on the emulator CPU (doc 10 §3.6).
  Raw-text embedding matched the documented EmbeddingGemma prompt format exactly, so start with raw
  text. PDF extraction, chunking, hybrid RRF and the larger chunked eval remain product-phase work.
- **Integrated loop measured (H3c, 18 Nov 2026):** staged WAV question → Whisper → keyword top-1
  retrieval over the 8-doc corpus → Qwen2.5-1.5B grounded answer → Piper WAV runs in one app process
  (peak 1.39 GB, ~57 s/turn shape-only); retrieval itself is 59–61 ms. Product consequence is a
  **context budget**: a 250–330-token grounded prompt costs 35–43 s prefill on the x86_64 emulator, so
  plan top-1/top-2 chunks of ~80–120 tokens, stream tokens, and re-measure prefill on arm64 (doc 10 §3.8).

### 4.3 Model catalogue and recommender

- Curated JSON catalogue (versioned, shipped in APK with optional signed refresh): model id, repo,
  file, size, quant, RAM requirement, context, languages, licence, VL/embedding flags, device tier.
- **Recommender:** rules over RAM/storage/SoC/GPU plus measured benchmark aggregates. Outputs
  "fast / balanced / best quality" cards with estimated tok/s.
- **Downloader:** OkHttp + WorkManager, Range resume, sha256 checksum, Wi-Fi-only default,
  notification progress, storage pre-check, licence gate, retry with mirror.

---

## 5. UI layer

- **Compose + Material 3**, Navigation Compose (type-safe routes), Hilt DI, Coil for images,
  Paging 3 for history/library lists, Vico or custom for benchmark charts.
- **Typography:** IBM Plex Sans Arabic (OFL) or Noto Naskh Arabic for body; Latin companion for mixed
  text; dynamic type; font scaling tested to 200%.
- **RTL:** LayoutDirection.Rtl, logical padding start/end, mirror only directional icons; bidi tests
  with mixed Arabic/English/code.
- **Streaming UI:** token buffer batched per frame; LazyColumn with stable keys; markdown parsed
  incrementally (avoid full reparse per token).
- **State:** clean MVI-ish (UI state + events + effects) per feature; ViewModel + StateFlow; engine
  events mapped to domain events.
- **Design system:** small token set (colours, spacing 4/8/12/16/24, radii, typography scale);
  components: MessageBubble, StreamingText, Composer, ModelCard, DocumentCard, CitationChip,
  CapabilityBadge, EmptyState, ErrorState.

---

## 6. Background work and process isolation

| Job | Mechanism | Notes |
|---|---|---|
| Model download | WorkManager + foreground notification | resumable, Wi-Fi default |
| Document import/index | WorkManager long-running | progress in UI; cancellable |
| Inference (chat) | app process + engine service in inference process | crash isolation; binder callbacks |
| Voice mode / screen-off chat | foreground service (microphone + wake lock) | Android 14 FGS types; visible notification |
| Benchmark | WorkManager | resumable; battery/thermal checks |
| Optional leaderboard upload | WorkManager, opt-in, HTTPS | anonymous device metadata |

- **Inference process:** android:process=":inference" service that owns the native handle; the UI
  binds via AIDL or Messenger with streaming callbacks. If the native engine crashes, the service
  dies and the UI shows a recoverable error plus diagnostics. This is the single most important
  reliability decision in the architecture.
- **Wake locks:** only during active generation/voice; scoped and released with timeouts.
- **Thermal:** listen to PowerManager.OnThermalStatusChangedListener; reduce threads/batch and show a
  "device warm" state.

---

## 7. Security and privacy

- **Network allow-list:** Network Security Config pinned to the domains we use; no wildcard cleartext;
  a "fully offline" toggle blocks all networking.
- **Local API server (v1.2):** bind 127.0.0.1 by default, bearer token generated on first use, optional
  LAN mode with explicit warning and token; never expose without auth; rate limit.
- **Storage:** app-private files; optional encrypted index in v1.1; no world-readable files; SAF URIs
  persisted with permissions.
- **Secrets:** HF token in EncryptedSharedPreferences/Keystore; never logged; redacted in diagnostics.
- **Permissions:** INTERNET, ACCESS_NETWORK_STATE, RECORD_AUDIO (STT/voice), CAMERA (vision capture),
  POST_NOTIFICATIONS (13+), FOREGROUND_SERVICE plus MICROPHONE/DATA_SYNC types, WAKE_LOCK,
  REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (with explanation). No location, contacts, SMS, phone state.
- **Exported components:** minimal; explicit intents; validate incoming share intents (size, MIME);
  no exported providers.
- **AI output reporting:** local queue plus email intent (no server in v1); documented in policy.
- **SBOM:** CycloneDX generated in CI; in-app licences screen from assets.

---

## 8. Build, CI and release

### 8.1 Gradle setup

- Gradle 8.x wrapper, AGP 8.x, Kotlin 2.x, JDK 17, compileSdk 36, minSdk 29, targetSdk 36,
  versionCode scheme MMmmpp.
- **Native build:** CMake 3.22+ via Android SDK; llama.cpp and whisper.cpp as git submodules pinned to
  commits; build arm64-v8a only; LTO release; strip symbols; keep debug symbols in debug.
- **Flavours:** play (Play Billing plus vendor NPU runtime allowed) and libre (F-Droid/GitHub; no
  proprietary blobs, no Play Billing, NPU packs only if open and redistributable, else hidden).
- **Signing:** release keystore offline plus Play App Signing; GitHub release builds signed with a
  separate key; build steps documented.
- **CI:** GitHub Actions: lint, unit tests, Compose screenshot tests (Roborazzi or Paparazzi), native
  build, APK size gate, SBOM, nightly Arabic eval, release on tag.
- **Size budget:** core APK under 60 MB; enforce in CI; models never in APK.

### 8.2 Testing strategy

| Layer | Tests |
|---|---|
| Domain | JUnit + Kotest for use cases, recommender rules, chunking, citation parsing |
| Data | Room migration tests, DAO tests, download resume tests (MockWebServer), storage tests |
| Engine | instrumented tests on emulator (CPU) plus physical devices (GPU/NPU); golden prompts |
| RAG | golden PDFs (Arabic + English) → chunk/retrieval eval; citation correctness |
| UI | Compose screenshot tests (light/dark/RTL/font scale), navigation tests |
| E2E | Maestro/UI Automator on 2 devices: onboarding → mocked download → chat → RAG → dictation |
| Performance | Macrobenchmark: cold start, chat streaming, model load; Perfetto traces |
| Privacy | network interceptor test asserting zero requests in offline flows; manifest permission audit |
| Release | staged rollout gates via Play; crash-free check before 100% |

### 8.3 Observability without telemetry

- Local rolling logs (last 7 days, capped MB); diagnostics export zip (device info, backend, model,
  error, no chat content by default); "copy diagnostics" button.
- Optional opt-in benchmark upload (anonymous): device model, SoC, RAM, Android, engine/backend,
  model id, tok/s, memory, app version; no identifiers.
- Play vitals for crashes/ANRs (aggregate, no user data); GitHub issue template with diagnostics.

---

## 9. Performance budget (mid-range target, 2B Q4)

| Metric | Budget | Notes |
|---|---|---|
| Cold start to chat list | 1.5 s | baseline profile + no blocking init |
| Model load (1.4 GB, mmap) | 3–6 s | show progress |
| Prefill 2k ctx | at least 150 tok/s | KleidiAI/NEON; Step Zero target |
| Decode | at least 12 tok/s | 2B Q4 on SD 7-series class |
| TTFT | at most 1.5 s | after prefill begins |
| Grounded prompt (RAG) | ≤120 tokens (top-1 chunk) | prefill dominates TTFT; H3c measured a 250–330-token grounded prompt at 35–43 s prefill on the x86_64 emulator (shape-only — doc 10 §3.8) |
| Memory peak | under 60% of device RAM | refuse load if over budget |
| Streaming jank | under 1% janky frames | batched token UI |
| Download resume | 100% success after kill | WorkManager + Range |
| APK | under 60 MB | arm64 only |

---

## 10. Repository layout

    nawa/
      app/                     Gradle module, flavours, manifest
      core/{common,ui,data,domain,llm,rag,speech,modelcatalog,privacy,test-utils}/
      feature/{onboarding,chat,models,library,voice,settings,benchmark}/
      engine/{llamacpp,litertlm,whisper,tts,embeddings}/
      third_party/             submodules: llama.cpp, whisper.cpp, sherpa-onnx
      tools/                   catalogue generator, SBOM, Arabic eval runner
      docs/                    architecture, ADRs, licences
      NOTICE, THIRD_PARTY_LICENSES, LICENSE (Apache-2.0), README

---

## 11. Distribution flavours

| Flavour | Stores | Contains | Excludes |
|---|---|---|---|
| play | Google Play | Play Billing (Pro later), vendor NPU runtime allowed, Play In-App Updates | — |
| libre | GitHub Releases, F-Droid | no Play Billing, no proprietary blobs, NPU packs only if redistributable/open; all core features free | Play Services, vendor blobs, crash SDKs |
| dev | internal | debug menus, benchmark tools, verbose logs | — |

- **F-Droid requirements:** buildable from source, no proprietary dependencies, no tracking, metadata
  in repo, reproducible builds where possible; NPU vendor binaries break this, so they are excluded
  from the libre flavour (CPU/GPU only there).
- **Play requirements:** Data safety form, AI content reporting, content rating, privacy policy,
  target API level, closed testing for new personal accounts (12 testers/14 days — verify current).

---

## 12. Key ADRs (decision records)

| ADR | Decision | Alternatives rejected | Reason |
|---|---|---|---|
| ADR-1 | Kotlin + Compose native | React Native (PocketPal stack), Flutter | Deep Android integration, no bridge tax, solo-maintainable; RN would inherit PocketPal's ceiling |
| ADR-2 | llama.cpp primary engine | LiteRT-LM only, MLC, ExecuTorch | GGUF breadth + CPU/GPU maturity + vision/embeddings; NPU added via LiteRT |
| ADR-3 | LiteRT-LM for NPU packs | llama.cpp Hexagon only | Cross-vendor NPU support (Qualcomm+MediaTek+Tensor) and maintained format |
| ADR-4 | Inference in separate process | Same process | Native crashes must not kill UI; reliability drives ratings |
| ADR-5 | Room + sqlite-vec for RAG | ObjectBox, HNSW lib, cloud | Simple, transactional, offline, licence-clean |
| ADR-6 | EmbeddingGemma 2 | MiniLM, bge-m3, cloud embeddings | On-device quality for Arabic/English, Apache-2.0, small |
| ADR-7 | Whisper + Piper for Arabic speech | System only, cloud | Offline Arabic; Piper ar_JO exists; system fallback. Spike 18 Nov 2026: synthetic MSA WER 15% (whisper-small) but Iraqi 46.8%, whisper-small RTF 0.82, and Piper needs diacritized input (doc 10 §3.7) |
| ADR-8 | Apache-2.0 licence | MIT, AGPL | Patent grant + permissive; compatible with reused components; F-Droid friendly |
| ADR-9 | No telemetry | Firebase/Sentry | Trust positioning; avoids Data-safety ambiguity; local diagnostics instead |
| ADR-10 | Two flavours | Single build | F-Droid purity vs Play features cannot coexist in one artifact |
| ADR-11 | arm64-v8a only | arm32/x86 | 2026 devices; halves native build time; **dev flavour builds x86_64 + arm64** so the x86_64 AVD can run the engine (doc 07); release stays arm64-only |
| ADR-12 | Models in app storage by default | Shared Downloads | Reliability + SAF complexity; optional SAF move |
| ADR-13 | Public name **Sirrak (سِرِّك)**, "your secret"; package ID provisional **dev.sirrak.spike**, final **app.sirrak** (reverse of sirrak.app) | Nawa (crowded in AI), Nuwair (founder rejected), Qataban (founder rejected), 65+ screened alternatives | Founder approved 18 Nov 2026; only name of 36 in the Round 3 desk screen with no same-class product (doc 08 rev 2); sirrak.app/.com/.ai/.chat unregistered by RDAP 18 Nov 2026; formal trademark search + domain purchase before first Play upload |

---

## 13. Risks in this architecture and their mitigations

| Risk | Mitigation |
|---|---|
| llama.cpp JNI build complexity on Windows | Step Zero proves it; fallback: prebuilt Android libs or Plan B fork |
| LiteRT-LM API changes | Wrap behind InferenceEngine; pin version; runtime capability detect |
| Native crash from mmap/model corruption | checksum before load; process isolation; diagnostics |
| Memory fragmentation with multiple models | unload before load; one active model per process; LRU cache of 1 |
| Arabic PDF extraction poor | text-layer first; OCR v1.1; document limitation in UI |
| Arabic TTS mispronounces unvocalized text | diacritizer in the TTS pipeline (doc 10 §3.7); validate on a vocalized gold set before shipping |
| Iraqi dialect ASR accuracy | synthetic WER 46.8% vs 15% MSA; collect founder recordings, evaluate fine-tune/distil before promising dialect dictation (doc 11) |
| Grounded-prompt prefill blows the TTFT budget | cap retrieved context (~80–120 tokens top-1 chunk); stream tokens so TTFT is not dead air; measure prefill on arm64 phone before locking the prompt budget (H3c, doc 10 §3.8) |
| Thermal throttling on mid-range | adaptive threads/batch; honest UI state; sustained benchmarks |
| F-Droid reproducibility with native libs | document toolchain; strip timestamps; provide build script |
| Play rejection (AI policy) | in-app reporting + policy page + disclaimers before submission |
| Model licence drift | catalogue stores licence + acceptance state; CI checks allow-list |

---

## 14. Step Zero build (concrete, 10 days)

**Day 1–2:** Gradle project skeleton, CMake, llama.cpp submodule pinned, JNI load-model-and-generate
with a single screen; log tok/s to logcat and screen. Build on a throwaway spike project (the E: drive had only 7.7 GB free).
**Day 3:** run on the target mid-range device; measure prefill/decode/TTFT/RSS at 2k context; record
PocketPal baseline numbers on the same device and model.
**Day 4–5:** LiteRT-LM HelloWorld + Gemma 4 E2B pack; measure CPU vs NPU; log actual backend
(HelloWorld + Qwen3-0.6B CPU done early on the emulator, 18 Nov 2026 — doc 10 §3.5; E2B pack + NPU
are phone-day).
**Day 6–7:** Arabic H3: Whisper-small WER on 20 sentences; Piper ar_JO synthesis plus listener ratings;
RAG mini-spike: EmbeddingGemma + 3 Arabic PDFs + 20 questions (retrieval half done early on the
emulator with 8 docs / 20 Arabic + 4 English questions and adjacent-topic distractors — top-1 20/20,
doc 10 §3.6; voice round-trip also done early on the emulator, 18 Nov 2026: 20 doc-11 sentences,
whisper-small **27.4% overall / 15.0% MSA** on diacritized Piper, base 46.6%, Piper RTF 0.34,
ASR-small RTF 0.82, peak RSS 977 MB — doc 10 §3.7; human recordings, listener ratings and PDF
extraction remain). Integrated voice→retrieval→LLM→TTS loop also done early on the emulator (18 Nov
2026): whisper-small 3/3 retrieval hits with grounded answers, ~57 s/turn, 1.39 GB peak RSS, prefill
the dominant cost (doc 10 §3.8).
**Day 8–9:** H4: 15 user interviews and demos.
**Day 10:** write H1–H4 report; decide Go / Plan B / stop. No product code before this.

---

---

## 15. Amendment — 19 Nov 2026: customization seams, model control, agent boundary

**Source:** [doc 17 — Customization, model control and agent scope](17-customization-council.md). Read this
section together with §12 (ADR table) and §3 (engine layer). Nothing here changes an existing ADR; it adds
five and records the contract/ownership consequences.

### 15.1 New ADRs (continue the §12 table; ADR-14..ADR-18)

| ADR | Decision | Alternatives rejected | Reason |
|---|---|---|---|
| ADR-14 | Theme packs are **data-only palettes** in `:core:design» (`ThemePack»/`ThemePalette» registry, 5 packs, Girih & Ink default), selected via `SettingsService» and rendered by `SirrakTheme(mode, packId)»; no colour editor, no Material You in v1.0 | full colour editor; dynamic colour now; per-feature theming | brand fidelity (geometry/type/motion fixed), WCAG floor, solo maintenance; cheapest ownership win |
| ADR-15 | Generation behaviour is **per model**, keyed by a content fingerprint, layered (conversation > per-model > GGUF `general.sampling.*» > family preset > `app.json» > code); template source is displayed and previewed through the same native formatter; forcing a built-in template only behind Advanced; custom Jinja only after the T4 build test | one global config; filename-keyed settings; arbitrary pasted Jinja now | models differ by family; invented defaults mislead; an unparsed Jinja template would silently degrade answers |
| ADR-16 | "Agent" scope is **skills, not autonomy**: v1.0 ships prompt+parameter presets (SET-16); v1.1 may ship skill bundles with allowlisted deterministic local tools (grammar-constrained JSON, visible cards, per-tool permission, no network) only after the T5 BFCL-lite gate (≥70% correct, ≤2 s overhead) | free-form agent loop; MCP now; tool use in v1.0 | 1–3B models call tools syntactically but not reliably (BFCL ~65.74%); a wrong read on user documents is a trust failure |
| ADR-17 | The default-assistant role stays **v1.1** and is an *entry point*, not screen intelligence: `ROLE_ASSISTANT» + `ACTION_ASSIST» + QS tile + share target, overlay via `VoiceInteractionService»; access limited to the explicit invocation (`AssistContent», selected text, voice, attachments); no AccessibilityService, no background reads, no persistence unless the user sends | accessibility screen automation; background agent; always-listening | Play policy + privacy positioning; selected text is what the platform contract offers and what the jobs need |
| ADR-18 | One **attachment model** in `core:runtime» precedes every modality backend; the composer gates by the selected model's facts/capabilities; audio-as-model-input suspended, video dropped | one pipeline per modality; promise audio-LLM | one seam prevents three parallel UIs and three broken states; no small on-device Arabic audio-LLM exists |

### 15.2 Contract changes (§3.1 engine interface, §4 data, §5 UI)

| Contract | Module | Change |
|---|---|---|
| `SettingsService» | `:core:runtime» | + `themePackId: StateFlow<String>», `setThemePack(id: String)», `defaultSystemPrompt: StateFlow<String>» (M4/M5) |
| `ThemePack», `ThemePalette», `ThemePacks» | `:core:design» | new (M4). `Palette» hex literals move behind `ThemePacks.default»; call sites keep tokens, never hex |
| `ModelFacts» | `:core:runtime» | new value type filled from the engine (M5): architecture, nCtxTrain, nParams, nVocab, template source/hash, sampling metadata, bos/eos |
| `GenerationParams», `SamplingMode», `TemplateChoice» | `:core:runtime» | new value types with range validation (M5); hidden experimental fields stay in the model to avoid a schema break in v1.1 |
| `ModelSettings», `ModelSettingsStore» | `:core:runtime» (contract) / `:app» (impl) | new (M5): layered settings keyed by `sha256:…» fingerprint, atomic JSON, schemaVersion 1, corrupt-backup recovery |
| `InferenceEngine» | `:core:runtime» | additive: `modelFacts: ModelFacts?» and a default-implemented `stream(prompt, history, options: GenerationOptions)» overload delegating to the existing method — DemoEngine and existing tests compile untouched |
| `PromptPreset», `PromptPresetStore» | `:core:runtime» (contract) / assets + app (impl) | new (M5): asset catalogue `content/prompt_presets.json», local duplicate/edit, AR/EN |
| `Attachment», `AttachmentKind», `AttachmentStore» | `:core:runtime» (contract, M7) | new; storage `files/attachments/<conversationId>/», MIME/size validation, cascade delete, EXIF strip |
| `Tool», `ToolSpec», `ToolRegistry», `Skill», `SkillRuntime» | `:core:runtime» (M12/v1.1 only) | new behind the T5 gate; no dependency in v1.0 code |
| JNI surface | `:engine:llamacpp» | + `nativeModelInfo()», `nativeFormatPrompt(...)», expanded `nativeGenerate(...)» args, expanded sampler chain, reload on context/template change |

**No new Gradle module is required for M4/M5.** M6 keeps the conversation library inside
`:core:runtime» + `:app» (replacing the M3 file store behind the existing `ConversationStore» seam, with a
migration). M7/M8 follow the planned module graph: attachment contracts stay in `:core:runtime»; RAG keeps
its planned `:core:retrieval» home from doc 05 §2. The composition manifest gains no entries for these
changes except where a new feature plugin is genuinely added (prompt-preset store stays an app service).

### 15.3 Data layer (§4.1) additions

| File | Owner | Rules |
|---|---|---|
| `files/model_settings.json» | `ModelSettingsStore» (app) | atomic write (temp + rename), schemaVersion 1, keyed by model fingerprint, corrupt → `.corrupt» backup + defaults + log; never blocks chat; sized small (no conversation content) |
| `files/attachments/<conversationId>/…» | `AttachmentStore» (app) | local copies only; validated MIME/size; delete with conversation; orphan sweep on launch; no network; EXIF stripped |
| `content/prompt_presets.json» | assets + user copy | schemaVersion 1; ids stable; AR/EN text; no HTML/markup injection surface beyond plain text |
| `app.json» `theme.defaultPack» | config | validated against the pack registry; unknown → default + logged warning |

Model facts are cached in memory per loaded model (one JNI call after load); they are not persisted except
the fingerprint and the small settings file. The M3 single-conversation file is migrated in M6 (new
conversation index + per-conversation transcript, old file imported as "first conversation"; rollback =
keep the old file untouched until the migration succeeds).

### 15.4 Engine layer (§3.2) additions

- Metadata comes from `llama_model_meta_val_str»/`llama_model_meta_key_by_index»/`llama_model_n_ctx_train»
  /`llama_model_n_params»/`llama_model_chat_template» (verified in `include/llama.h» 580–636).
- Template application stays on `llama_chat_apply_template» (built-in names only; no Jinja). The preview
  call and the generation call share one C++ function so the preview cannot lie.
- Sampler chain mirrors `common/sampling.cpp» order (logit_bias → dry → top_k → top_p → top_n_sigma →
  min_p → xtc → typical → temp_ext → penalties → dist/greedy; mirostat mode as in `common_sampler_init»).
  Default-off features are only added when enabled, so v1.0 parity is preserved.
- Context size, thread count and template choice are load-time parameters: changing them shows "applies
  after reload" and triggers unload/reload; sampler/prompt changes apply to the next message.
- Template/Jinja expansion is **not** in the v1.0 build; T4 measures a narrow `common/chat» target
  (`chat.cpp», `chat-*.cpp», `json*.cpp», `jinja/», `parsers/», `peg-parser.cpp»,
  `json-schema-to-grammar.cpp», vendored nlohmann/sheredom) before any v1.1 dependency is added.

### 15.5 Security and privacy (§7) consequences

- Attachments, extracted text, image captions and tool results are **untrusted data**: they are inserted
  as delimited data blocks, never as system instructions; tool output is labelled in the transcript and in
  the prompt; the prompt-injection rule is a test (M7/M12), not a comment.
- Assistant role access is an allowlist (ADR-17): the explicit invocation payload only; no
  AccessibilityService, MediaProjection, contacts, SMS, location, notifications; the assist payload is
  held in memory and discarded at session end unless the user sends/saves it; clipboard read only on
  explicit paste.
- Downloads (M11) are the only planned network path besides an explicit user-initiated model download:
  Wi-Fi-only by default, checksum verified, licence consent first; the network audit page (PRV-2) must
  report it honestly.
- No telemetry, no analytics, no crash reporting: unchanged (ADR-9).

### 15.6 Reliability gates added to §6

- **Inference process isolation (ADR-4):** the decision/design must be spiked by the M8 exit; v1.0 public
  beta may not ship with a native crash class that takes the UI down.
- **Context management:** the M3 JNI truncation of oldest user/assistant pairs becomes a visible token
  meter + near-limit warning in M6; silent memory loss is a bug after M6.
- **Model load guard:** a RAM/storage estimate check before load (bad-model download prevention remains
  M11).
- **Downloader/onboarding** are launch gates, not polish (M11 before P3).

*Previous record: §14 Step Zero build. This amendment is the architecture consequence of doc 17; no
existing ADR is repealed.*
