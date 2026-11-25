# Changelog

All notable changes to Sirrak are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Version numbers follow Semantic Versioning:
`1.0.0-rcN` is a release candidate for `1.0.0`, not a stable release, and pre-release suffixes are never
reused for different content.

## [1.1.0] - 2026-11-25

The UX rework from the six-area product review: the app now tells the truth about its state and routes
you to the fix instead of showing a dead end, the Library is one shelf, Settings is six doors, and a
model can be acquired from Hugging Face without leaving the app. Council record and decisions:
[docs/31-ux-rework-council.md](docs/31-ux-rework-council.md); build record:
[docs/32-ux-rework-build-record.md](docs/32-ux-rework-build-record.md).

### Added

- **Library (النماذج / التنزيلات / الوسائط)** — one device shelf in three segments. **Models** lists the
  curated catalogue with an honest per-device verdict (runs / tight / won't run / not verified), GGUF
  import and a per-model page that owns its context and generation settings. **Downloads** is the
  persisted queue: one transfer at a time, pause/resume/cancel/retry, progress survives an app restart,
  and the background notification goes through the same queue. **Media** holds documents (indexed for
  cited answers), images and audio; audio is transcribed on the device by Whisper into a text item —
  the recording never leaves the phone.
- **Keep-or-once media retention with agent access control** — every media item asks whether to keep it
  or use it once; a once-only item is never indexed and is deleted when its session ends. A per-item
  access switch and policy defaults decide what the agent may read.
- **In-app Hugging Face import** — search the public GGUF index, open a repository, inspect a file, and
  get a fit verdict before downloading. The 8–12 MB capped GGUF header probe returns real architecture
  numbers plus a **تقديري** estimate of memory; a model that will not fit is blocked with its reason, a
  tight fit needs an explicit confirmation, and an unprobed model reads "not verified", never "runs".
  Gated repositories keep their token in memory only. Search, file list and probe are audited as
  `hf_search` and `hf_probe` in the network log and covered by the privacy policy.
- **Settings doors with live summaries** — the single long scroll is now six doors: appearance & reading,
  language, privacy & network, voice, advanced and about. Each door shows a live one-line summary and
  opens a page; language is chosen inline.

### Changed

- **The model chip tells the truth in four states** — no model loaded (with a button to the Library),
  downloading, selected but not loaded, and failed. Its sheet routes to the model's own page or the
  Library instead of a dead end.
- **Model management moved out of Settings into the Library**; the fake catalogue cards and the old
  download-manager surface are gone.
- **Version 1.1.0** (versionCode 14), the first build from the reworked UI. Policy version 2 records the
  two new Hugging Face endpoints; both policies, the store copy, the website and the screenshots were
  refreshed from this tree.

### Known limits in this build

- Media-to-chat wiring is V1: once-only items are deleted at app close/restart (no conversation link
  yet), images are view-only and never indexed, and indexed documents are stored once for the shelf and
  once for retrieval.
- Discovering more results in Hugging Face search, concurrency beyond one active download, and OCR are
  deliberately out of scope for this build.

## [1.0.0-rc2] - 2026-11-24

### Added

- **A compact 2 GB tier in the curated catalogue.** LFM2.5 350M Instruct (GGUF Q4_K_M, 218.7 MiB, `minRamMb 2048`) gives
  2 GB phones a fitting recommendation instead of "nothing fits". The row carries the Hugging Face LFS SHA-256 value and
  Arabic/English descriptions. The default recommendation is unchanged: Qwen2.5 0.5B still wins for 3 GB+ devices.
  Qwen3.5 0.8B was evaluated and rejected after the device gate: it loads (arch `qwen35`, 1,659 ms on the emulator) but
  llama.cpp's template application turns its thinking mode on by default, so answers start with a `<think>` block — the
  row stays out until the engine can pass `enable_thinking=false`.
- **[docs/29-small-models-council.md](docs/29-small-models-council.md)**: the Qiyas council record for formats and small
  models — GGUF as the only native text format, the verified candidate table (including Jais-3-0.9B rejected for its
  unsupported `k2-horizon` architecture), the open device falsification gate, and a safetensors→GGUF conversion guide.

### Changed

- **Arabic terminology normalized: an AI model is نموذج, not طراز.** "لا طراز" (the engine status that meant "no model
  selected or loaded", not a load failure) now reads **"لا يوجد نموذج"**, and "تعذّر تحميل الطراز" reads "تعذّر تحميل
  النموذج". Prompt copy is unified as **تعليمات** (تعليمات النظام/المحادثة/العامة/النهائية), the vision projector is
  **"ملحق الرؤية (mmproj)"** instead of "مشروع الرؤية", and the old calques are replaced by التكميم، الملفات غير المرتبطة،
  بنية المعالج (ABI), and المُرمِّز/مُفكِّك الترميز for encoder/decoder. 158 occurrences of طراز plus five related terms were
  removed from app strings, both READMEs, the website content, the store listing and the demo chat asset; one JVM test
  literal was updated. The dictionary evidence, Qiyas council transcript and the governing glossary are in
  [docs/30-arabic-terminology.md](docs/30-arabic-terminology.md).

- **Public surfaces refreshed for `1.0.0-rc2`.** The signed APK/AAB, the eight store screenshots, the screenshot
  regression baseline, the store listing, both READMEs and the generated website were rebuilt from the same tree.
  `1.0.0-rc1` keeps its tag, artifacts, screenshots and record untouched as history; the rc2 release is a new tag,
  never a re-tag.

Shipped as `1.0.0-rc2` (versionCode 13) on 2026-11-24: the signed artifacts were rebuilt and re-recorded, the
screenshots were recaptured from the rc2 build, and the store/site/README facts were refreshed from the same source.
On `1.0.0-rc1` the same model files could be imported through Settings → Models → Add from Hugging Face, but the
installed rc1 app still shows the old Arabic wording; that record stands. Publishing steps stay in
[docs/27-sirrak-launch-checklist.md](docs/27-sirrak-launch-checklist.md).

## [1.0.0-rc1] - 2026-11-23

First release candidate: versionCode 12, `minSdk 28` (Android 9+), `compileSdk`/`targetSdk` 36,
`arm64-v8a` and `x86_64`, Kotlin 2.0.21 and Jetpack Compose. Everything below runs on the phone; the app
has no account system, no advertising, no telemetry and no analytics.

### Added

- **On-device chat engine.** A vendored llama.cpp behind the `InferenceEngine` seam
  (`:engine:llamacpp`, `libsirrak_llm.so`), streaming tokens with stop, retry and regenerate, GGUF import
  from the Android document picker into a persistent model store, load/unload from Settings, automatic
  reload of the last model, context and sampling values from config, chat-template selection with prompt
  preview, seven bilingual prompt presets, global and per-model system prompts, and per-model settings
  keyed by model fingerprint. A deterministic demo engine remains the config-selectable fallback.
- **Conversation library.** Multiple chats with search, rename, delete-with-confirmation and automatic
  migration of the single-transcript store; Markdown and JSON export to `Downloads/Sirrak` or the share
  sheet; a token meter with truncation warnings and measured tokens per second; per-conversation system
  prompt and stop sequences; a persisted 85/100/115/130 % text size that scales `sp` only.
- **Dictation.** A pure-JVM speech policy module (`:core:speech`: recorder, transcription, model store and
  settings seams, WAV codec and resampler, Whisper three-file layout rule, silence filter, controller state
  machine) and a sherpa-onnx 2.0.1 Whisper engine (`:engine:whisper`, int8). Arabic, English and
  auto-detect are selectable; the transcript is appended to the composer and never sent on its own; audio
  never leaves the phone and model files stay in app-private storage.
- **Document retrieval.** A pure-JVM `:core:rag` module: PDF (page-aware), DOCX, EPUB, Markdown and text
  extraction, Arabic orthography normalisation, page-preserving chunking, and hybrid retrieval
  (EmbeddingGemma 2 270M vectors plus Arabic BM25, fused by reciprocal rank). Grounded answers cite a
  numbered, explicitly untrusted source block; citation chips open an excerpt sheet; a deterministic
  fallback covers small models that omit inline markers. The Library tab gained a documents list with SAF
  import, status, reindex and cascade delete.
- **Model manager.** A curated catalogue with a startup validator, a measured device profile and
  recommender with a storage warning below 3× the model size, licence consent, a three-step onboarding
  screen, resumable downloads (Range requests, `.part` staging, SHA-256 and GGUF magic verification,
  metered and storage gates), Hugging Face import with an optional token for gated repositories, installed
  and storage cards, and orphan cleanup.
- **Attachments and images.** One attachment seam (document, image, audio transcript, shared text) with
  streaming MIME/size validation, app-private per-conversation storage, cascade delete, and an
  untrusted-data prompt block; a capability-gated composer sheet where an unavailable backend explains
  itself instead of hiding. When the loaded model reports vision, images are decoded with a power-of-two
  sample, EXIF-oriented, scaled to 2048 px and answered by the engine through a paired `mmproj` projector.
- **Privacy surfaces.** A local network audit of declared and actual endpoints, bounded to the 50 newest
  entries; an offline-only switch enforced before any socket opens, which also cancels a running download;
  a local-first AI-output report queue with an explicit second-step share; and a licence registry that
  feeds Settings → About.
- **Theme packs.** Five data-only packs — Girih & Ink (default), Lapis Night, Turquoise Dawn, Madder Dusk
  and Paper — each a complete light and dark Material 3 palette with a live preview, applied instantly and
  remembered. A WCAG contrast gate checks every text and UI pair in every pack, runs in the test suite, and
  excludes decorative tokens by rule rather than by convention.
- **Accessibility, Arabic and RTL.** Arabic as the default interface language with English parity, RTL
  layout, 48 dp minimum touch targets, 200 % text scaling, TalkBack content descriptions, correct
  direction for mixed Arabic/Latin values, edge-to-edge insets and a WCAG contrast gate.
- **Distribution.** `libre` and `play` flavours built from one source, with `BuildConfig.STORE` shown in
  Settings → About; a release signed from a never-committed keystore; a `dataSync` foreground service for
  model downloads; a generated baseline profile; and a `libre` APK that contains no Play-only code.
- **Launch record.** A deterministic CycloneDX 1.5 SBOM (`sbom.cdx.json`) rendered from the licence
  registry; store graphics generated from the shipped palette, fonts and copy (512×512 icon and 1024×500
  feature graphic, hashes in `store/graphics/manifest.json`); F-Droid metadata, Play listing copy, data
  safety answers and eight screenshots; and three PowerShell gates — `scripts/validate-store.ps1`,
  `scripts/release-check.ps1` and `scripts/ui-regression.ps1`.
- **Arabic eval data.** `core/rag/src/test/resources/arabic/eval_cases.json` with 24 cases (12 orthography
  normalisations, 4 sentence-splitting contracts, 5 retrieval rankings, 3 negative controls), run by
  `ArabicEvalTest` in CI and on a monthly plan.

### Changed

- `targetSdk` moved to 36 with edge-to-edge insets and a generated baseline profile; `minSdk` stays at 28.
- The licence, NOTICE and SBOM surfaces now render from one asset registry
  (`app/src/main/assets/config/licences.json`) instead of hand-maintained licence lists.
- Store copy, platform limits, graphics hashes and the screenshot set are data (`store/store-spec.json`,
  `store/graphics/brand.json`), so a platform-limit change is an edit rather than a script change.
- Release signing reads `keystore.properties`; a checkout without it builds an unsigned release by design,
  so a missing key can never be mistaken for a signed artifact.
- The sherpa-onnx runtime ships as a pinned local AAR under `engine/whisper/libs`, so the build
  downloads no native dependency.
- Chat persistence moved from a single transcript file to the conversation library, with a one-time
  migration that keeps the old file as `conversation.json.migrated`.
- The JVM suite, the Arabic eval and lint now run in GitHub Actions on every push and pull request.

### Fixed

- A grounded prompt larger than `n_batch` aborted llama.cpp and killed the UI; native prefill is now
  chunked.
- R8 renamed the JNI-reflected sherpa-onnx recognizer config fields, so dictation worked in debug and
  failed only in the signed release with `NoSuchFieldError: decodingMethod`; a keep rule now pins the
  package.
- A silent input made Whisper hallucinate `(static)` into the composer; an RMS gate and a silence filter
  now reject it before the transcript is shown.
- A long projector filename squeezed a settings label into a one-character column; the row now weights,
  wraps and ellipsises both sides.
- Pausing and resuming a download could start two run loops; a run-id guard makes the older loop exit.
- Cleartext HTTP was released in every build; it is now confined to the debug manifest, where the loopback
  tests need it.

### Security

- No telemetry, analytics, crash reporting, accounts or ads; the only network feature is a model download
  the user starts, to the host declared in `app.json`.
- The offline-only switch refuses new network requests and cancels a running download before a socket is
  opened.
- Downloaded models are verified against the catalogue SHA-256 (and the GGUF magic) before installation.
- Chats, documents, extracted text, embeddings, model files, the network log and reports live in
  app-private storage; exports and shares leave only through an explicit user action.
- Keystore material is git-ignored and never committed; the release APK is v2-signed and the AAB is
  verified with `jarsigner` by the release gate.
- Vulnerability reports go to a private GitHub advisory, never a public issue; see
  [SECURITY.md](SECURITY.md).

### Notes

- **Quality evidence for this candidate:** 567 green JVM tests (`:app:testLibreDebugUnitTest` plus
  `testDebugUnitTest`), 24/24 Arabic eval cases, Android lint with 0 errors, a four-screen screenshot
  regression against committed baselines, the store validator and the release gate green on the signed
  artifacts.
- **Performance numbers are emulator numbers.** A 1.5B Q4_K_M model streams at about 4.5 tok/s with
  roughly 1.39 GB peak RSS on the API 36 x86_64 emulator; prompt prefill dominates time to first token.
  arm64 phone timings, thermals, battery and sunlight legibility are unmeasured.
- **One model, one context.** One model is loaded at a time into a 2048-token context, with no GPU or NPU
  offload.
- **The APK is universal** (`arm64-v8a` + `x86_64`, about 139 MiB) with no ABI splits, and signing is
  **v2 only** — no v3 or v4 signature yet.
- **F-Droid is planned, not published.** The pinned prebuilt sherpa-onnx AAR still needs a source build in
  the F-Droid recipe; the submission has not been opened.
- **Play is not published either.** The bundle is built and verified, but closed testing has not started.
  No store availability is claimed by this changelog, the README or the app.
- **Dictation quality is emulator-measured.** The Arabic replay gate scores 28.77 % WER (gate ≤ 35 %) and
  0.64 RTF on the emulator; the phone WER gate is still open.
