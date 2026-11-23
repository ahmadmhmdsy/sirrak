# Sirrak — M11 build record: model manager & onboarding

**Milestone:** M11 (ONB-1..5 onboarding gate; MOD-1..6 model manager: curated catalogue, Hugging Face + SAF import, resumable checksum-verified downloads, model detail, storage manager, recommended default) · **Status:** closed 21 Nov 2026
**App version:** 0.1.0-m11 (versionCode 11) · **Date:** 2026-11-21

> M11 closes the last "sideload first" gap in the product: a fresh install can pick a model, watch it
> download from the public Hugging Face resolver, see it verified, and start chatting without ever touching
> a file manager or a computer. The milestone adds a pure-JVM `:core:models` seam (catalogue schema, device
> profile + recommender, storage math, licence consent, onboarding flow, the resumable downloader) and a new
> `:feature:models` UI module (onboarding screens, model manager section, model detail sheet) contributed
> through the existing typed slots, plus the app-side plumbing (asset catalogue, SharedPrefs stores, Android
> network/storage/device probes, the HTTP transport, GGUF import into the model store, and the launch gate in
> `SirrakApp`). Evidence is JVM (40 new tests), on-device (8 new instrumented tests, 36/36 total), and — new
> for this milestone — a real network run: the signed release was installed fresh on the emulator, the
> recommended 491,400,032-byte Qwen2.5-0.5B-Instruct Q4_K_M was downloaded from `huggingface.co` at
> ~3 MB/s, checksum-verified, imported, selected and used in a chat turn ("What is 2 plus 2" → "2 plus 2
> equals 4.", `prompt_tokens=78 generated=8 tok_s=4.58 peak_rss_mb=711.6`). The remaining honest limits are
> the missing foreground service (a download pauses when the app leaves the foreground), no "move model"
> between volumes, repo/file entry instead of a browsable Hugging Face search, and the phone WER gate that
> F8 still defers.

## 1. What landed

### :core:models — the model-management seam (pure JVM, 40 tests)

- `Catalogue.kt`: the shipped-catalogue contract. `CatalogueProjector(repo, fileName, sizeBytes, sha256)`
  is the multimodal projector a vision entry pairs with; `CatalogueEntry(id, displayName, repo, fileName,
  sizeBytes, sha256, parameterLabel, quantization, contextTokens, ramMb, minRamMb, licence, licenceUrl,
  gated, languages, descriptionKey, tokensPerSecondHint, visionProjector)` derives
  `downloadUrl = "https://huggingface.co/<repo>/resolve/main/<fileName>"` and a `sizeLabel`; the whole
  catalogue is `ModelCatalogue(schemaVersion, models)`. `CatalogueJson.parse` is a small hand-rolled parser
  (no JSON dependency in a pure module) and `CatalogueJson.validationErrors` rejects duplicate ids,
  malformed SHA-256, missing licence, non-HTTPS projector and empty repo/file — the asset is validated at
  startup, not trusted.
- `Devices.kt`: `DeviceProfile(totalRamMb, availableStorageBytes, sdkInt, abis, socLabel, gpuLabel)`,
  `DeviceClass {LOW < 4 GB, MID < 8 GB, HIGH}`, `DeviceProfileProvider`; `RecommendationReason
  {RECOMMENDED, SMALLEST_THAT_FITS, NOTHING_FITS, EMPTY_CATALOGUE}`, `Recommendation(entry, reason,
  whyKey, storageTight, ramTight)`; `ModelRecommender.recommend(catalogue, profile)` prefers the flagged
  recommended entry when it fits RAM, otherwise the smallest entry that fits, with a
  `STORAGE_MARGIN_BYTES = 64 MiB` reserve; `SpeedEstimator` scales the catalogue's measured hint by
  0.55/0.85/1.0 per device class so the UI never promises a phone number it did not measure.
- `Storage.kt`: `StorageUsage`, `StorageMath.storageTight(size, free, factor = 3.0)` (the ONB-3 "<3×
  headroom" warning), `freeAfter`, `totalBytes`.
- `Consent.kt`: `LicenceConsent(modelId, licence, acceptedAtMs)`, the `LicenceConsentStore` seam
  (`isAccepted`, `accept`, `clear`, `accepted`), and `ConsentRules.requiresConsent(entry) = entry.gated`
  — a gated licence can never be auto-accepted, because the download path asks the store first.
- `Onboarding.kt`: `OnboardingStep {PRIVACY, LANGUAGE, MODEL}`, `OnboardingState(completed, skipped,
  completedAtMs)`, the `OnboardingStore` seam, and `OnboardingFlow` (ordered steps, count, index, next,
  previous, `canSkip`) so the screen never hardcodes "3".
- `Downloads.kt`: the resumable downloader.
  - `DownloadPhase {IDLE, QUEUED, DOWNLOADING, PAUSED, VERIFYING, INSTALLED, FAILED}` and
    `DownloadProgress(entryId, phase, bytesDownloaded, totalBytes, bytesPerSecond, reason, file)` with
    `fraction`/`percent`.
  - `DownloadReasons`: stable codes `NOT_UNMETERED, STORAGE_LOW, NETWORK, HTTP, CHECKSUM, IO, CANCELLED,
    INVALID_MODEL, RANGE_UNSUPPORTED`.
  - The seams: `NetworkPolicy`, `StorageProbe`, `TransportStream`, `TransportResponse(body,
    contentLength, supportsRange)`, `DownloadTransport.open(url, offset, authorization)`.
  - `ModelDownloader(transport, networkPolicy, storage, scope, stagingDir, wifiOnly, storageMarginBytes,
    maxAttempts = 3, retryDelayMs = 400)`: a `StateFlow<DownloadProgress?>`, `start(entry, url, authorization)`,
    `startProjector(...)`, `pause()`, `resume()`, `cancel()`, `clear()`. It downloads to
    `<id>.gguf.part`, sends `Range: bytes=<offset>-` on resume (restarting cleanly when the server ignores
    Range), retries transient failures with backoff, verifies SHA-256 when the catalogue has one, validates
    the GGUF magic with `GgufFile`, and only then renames the part file into place. A `@Volatile runId`
    guard stops a cancelled run from overwriting the PAUSED state (a defect the tests caught, §5.1).
- The catalogue asset itself (`app/src/main/assets/catalog/models_catalogue.json`) ships four entries:
  Qwen2.5-0.5B-Instruct Q4_K_M (491,400,032 B, Apache-2.0, recommended), Qwen2.5-1.5B-Instruct Q4_K_M
  (Apache-2.0), SmolVLM-500M-Instruct Q8_0 + mmproj (Apache-2.0, vision), and Llama-3.2-1B-Instruct Q4_K_M
  (`gated = true`, licence consent required). Apache-2.0-first is a deliberate product rule: three of the
  four shipped entries can be downloaded without a licence click.

### :feature:models — onboarding and the model manager UI

- `OnboardingScreen(container)`: three steps with a progress bar and "n of 3" counter.
  - **Privacy**: what stays on the device, what the network is used for, no telemetry, no account.
  - **Language**: Arabic / English choice written through `SettingsService.setLanguage` and applied by the
    activity `recreate()` on finish — the same switch the Settings screen uses, so there is one language
    path, not two.
  - **Model**: the device card (RAM / free storage / API), the recommendation with its "why" text and the
    <3× storage warning, the download button with live progress/pause/resume, licence consent for gated
    entries, and a "Try without a model" path that finishes onboarding with `skipped = true` and drops the
    user into the demo-engine chat. If a download finished while the screen was open, a `LaunchedEffect`
    imports the verified file through `ModelStore.importFile` and selects it, so "installed" is never a
    dead end.
- `ModelManagerPlugin` (id `models`): requires `ModelStore`, `SettingsService`,
  `ModelCatalogueProvider`, `DeviceProfileProvider`, `ModelDownloader`, `OnboardingStore`,
  `LicenceConsentStore`; contributes `SettingsSections` order 12. It owns no state of its own — the
  downloader's `StateFlow` is the only mutable source, so leaving and re-entering Settings shows the same
  download.
- `ModelManagerSection`: device card, recommendation card (with why + warnings + Download), the catalogue
  list with badges (Recommended / Gated / Installed / Selected) and per-row download / pause / resume /
  cancel / retry, the installed-models card (select / delete), the storage manager (free space, models size,
  orphan cleanup) and the "Add from Hugging Face" dialog (repo / file / token). A licence-consent dialog
  blocks only gated downloads; a delete confirmation and an orphan confirmation are the only destructive
  actions, and deleting a model never touches conversations.
- `ModelDetailSheet`: a Material 3 `ModalBottomSheet` with parameters, quantisation, size, RAM needed,
  context tokens, languages, repository, file, projector (when present), estimated speed with the
  "estimate, not a measurement" disclaimer, licence + URL, installed state, and the actions (download /
  select / delete).
- 105 keys × 2 locales (`res/values` = Arabic default, `res/values-en` = English), so every string the
  milestone adds is localised and none is hardcoded in Kotlin.

### :app — Android plumbing

- `AssetModelCatalogue`: reads `content.catalog` from `assets/config/app.json` (default
  `catalog/models_catalogue.json`), parses and validates the asset, and provides the catalogue as a
  `StateFlow` — a broken asset fails visibly at startup instead of producing an empty screen.
- `AndroidDownloadSupport`: `AndroidNetworkPolicy` (`ConnectivityManager` → unmetered), `AndroidStorageProbe`
  (`filesDir` free space), `AndroidDeviceProfiler` (`ActivityManager.MemoryInfo`, `StatFs`, SDK, ABIs,
  `Build.SOC_MODEL` / `Build.HARDWARE`).
- `SharedPrefsModelManagerStores`: `SharedPrefsLicenceConsentStore` (per-model licence + timestamp) and
  `SharedPrefsOnboardingStore` (completed/skipped/timestamp).
- `FileModelStore` additions: `importFile(displayName, source, expectedSha256)` (GGUF magic check, atomic
  rename or stream copy, then select), `ownedFiles()` and `deleteOrphanFiles()` for the storage manager.
- `HttpDownloadTransport`: a real `HttpURLConnection` transport with `Range` requests, HTTP 206 detection,
  `Authorization: Bearer <token>` for gated Hugging Face repos, blocking `TransportStream.read` (the
  downloader moves it to `Dispatchers.IO`), and `SirrakDownload` logging.
- `AppComposition`: provides the seven services, builds the `ModelDownloader` on a
  `CoroutineScope(SupervisorJob() + Dispatchers.IO)` with `filesDir/model_downloads` staging, registers a
  `host:model-downloader` effect that cancels the scope on disposal, and installs `ModelManagerPlugin()`.
  `app.json` gains `content.catalog` and `features.order` ends `"asr", "voice", "models"`.
- `SirrakApp`: the launch gate — `if (onboarding.completed) SirrakShell(...) else OnboardingScreen(container)`,
  collected with `collectAsStateWithLifecycle`; finishing onboarding recreates the activity so the language
  choice applies immediately.
- `app/src/debug/AndroidManifest.xml`: debug-only `usesCleartextTraffic="true"` so the device tests can
  drive the real transport against a loopback server; the release manifest keeps HTTPS-only traffic.
- `AndroidManifest.xml`: `INTERNET` + `ACCESS_NETWORK_STATE` with the download-only comment; no other
  permission is added by the milestone.

## 2. Acceptance criteria → evidence

| Criterion | Where it lives | Evidence |
|---|---|---|
| ONB-1 three onboarding screens | `OnboardingScreen` + `OnboardingFlow` | `m11-onboarding-privacy.png`, `m11-onboarding-language.png`, `m11-onboarding-language-en.png`, `m11-onboarding-model.png`; `OnboardingFlowTest` (6) |
| ONB-2 skip path reaches the shell | `finish(skipped = true)` | `m11-onboarding-done-chat.png`, `m11-release-shell.png` (fresh release, Arabic); device test `licenceConsentAndOnboardingStoresPersist` |
| ONB-3 device check + recommendation + <3× warning | `DeviceProfile`/`ModelRecommender`/`StorageMath` | `m11-onboarding-model.png` (RAM 5923 MB, free 11.76 GB, API 36), `m11-release-settings-models.png`; `ModelRecommenderTest` (9), `ConsentStorageTest` (5) |
| ONB-4 download flow with resume | `ModelDownloader` | `m11-release-download.png` (3% · 18 MB / 491 MB, 3 MB/s live); device tests `downloaderPausesAndResumesWithARangeRequest` (real Range header), `downloaderDownloadsVerifiesAndInstallsOverLoopback`; `ModelDownloaderTest` (12) |
| ONB-5 licence never auto-accepted; try without model | `ConsentRules` + dialog | `m11-onboarding-model-installed.png`, gated row in `m11-settings-model-manager.png`; `ConsentStorageTest` |
| MOD-1 curated Apache-2.0-first catalogue | `models_catalogue.json` | `catalogueAssetIsValidAndRecommendsAModel` (validates the shipped asset on device); `CatalogueJsonTest` (8) |
| MOD-2 HF repo/file + token download, resume, checksum | `CatalogueEntry.downloadUrl`, `HttpDownloadTransport`, `ModelDownloader` | **public Hugging Face download of the 491 MB recommended model on the signed release** (§3); device tests for checksum/resume/token pass-through |
| MOD-3 SAF import + GGUF validation | `ModelStore.importFile` | `importsADownloadedModelAndSelectsIt`; the M7 SAF import path still green |
| MOD-4 model detail | `ModelDetailSheet` | `m11-model-detail.png`, `m11-model-detail-actions.png` |
| MOD-5 storage manager | `StorageManagerCard` + `deleteOrphanFiles` | `m11-settings-storage-manager.png` (free 10.77 GB, models 491 MB, orphan cleanup) |
| MOD-6 recommended default + why | `ModelRecommender` | `m11-settings-model-manager.png` (Selected badge, "Fits your device's memory and storage"), `m11-release-settings-installed.png`; `ModelRecommenderTest` (9) |

## 3. End-to-end evidence (fresh signed release → public download → chat)

1. The debug build was uninstalled, the signed release installed fresh (`versionCode=11`,
   `versionName=0.1.0-m11`), and the app opened on onboarding step 1 in Arabic RTL
   (`m11-release-onboarding.png`).
2. "Skip for now" (`تخطّي الآن`) finished onboarding with `skipped = true` and opened the chat shell with
   the demo prompts and "No model · Not loaded" (`m11-release-shell.png`).
3. Settings → Models showed the device card, the recommended Qwen2.5-0.5B-Instruct card and the catalogue
   (`m11-release-settings-models.png`). Tapping **Download** started the real network download from
   `https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf`:
   the UI reported **3% · 18 MB / 491 MB at 3 MB/s**, then 23%, 36%, 49%, 63%, 78%, 92% over ~2 minutes
   (`m11-release-download.png`), i.e. the production transport, progress flow, storage check, checksum and
   GGUF verification all ran against the public host.
4. On completion the file was imported into app-private storage, selected, and the chat header switched to
   `qwen2.5 0.5b instruct · جاهز على الجهاز` ("ready on device"). Settings showed the entry with the
   **Selected** badge and the catalogue intact (`m11-release-settings-installed.png`).
5. A real chat turn ran on llama.cpp: "What is 2 plus 2" → "2 plus 2 equals 4."
   (`m11-release-chat-answer.png`), with
   `SirrakLlama: STATS prompt_tokens=78 generated=8 prefill_ms=7906.6 ttft_ms=7908.0 tok_s=4.58
   peak_rss_mb=711.6`.

This is the milestone's headline: **install → choose a model → download from the public catalogue → chat,
with no sideload and no computer.** The instrumented suite repeats the same download/import/select path
against a loopback mirror (`installsTheRealModelFromALocalMirror`, 16.19 s) so the behaviour is also
covered offline and deterministically.

## 4. Verification / build record

| Item | Value |
| --- | --- |
| Version | `versionCode 11`, `versionName 0.1.0-m11` |
| JVM verification | 464 (M10) + 40 new `:core:models` tests = **504 tests, 0 failures** (app 155, core:design 47, core:models 40, core:rag 62, core:runtime 81, core:speech 40, engine:embeddings 6, feature:chat 73) |
| New JVM suites | `CatalogueJsonTest` (8), `ModelRecommenderTest` (9), `OnboardingFlowTest` (6), `ConsentStorageTest` (5), `ModelDownloaderTest` (12: checksum, resume, Range-ignored restart, bad SHA, non-GGUF payload, metered refusal, metered-allowed override, low-storage refusal, pause + resume, token pass-through, projector download, retry) |
| Device verification | **36/36 tests, 0 skipped, 0 failed; test time 320.751 s, Gradle 5m44s** on `Nawa_Phone` (API 36 x86_64, emulator-5554) |
| New device tests | `ModelManagerDeviceTest` 8: `catalogueAssetIsValidAndRecommendsAModel` 0.031 s, `downloaderDownloadsVerifiesAndInstallsOverLoopback` 0.067 s, `downloaderPausesAndResumesWithARangeRequest` 0.429 s, `downloaderBlocksMeteredConnectionsAndLowStorage` <0.001 s, `downloaderRejectsABadChecksumAndANonGgufPayload` 0.123 s, `licenceConsentAndOnboardingStoresPersist` 0.004 s, `importsADownloadedModelAndSelectsIt` 0.065 s, `installsTheRealModelFromALocalMirror` 16.19 s |
| Release build | `:app:assembleRelease`: BUILD SUCCESSFUL in 2m34s (R8 minified, resources shrunk, x86_64 + arm64-v8a native) |
| Release APK | `app-release-sirrak.apk`, **145,518,754 bytes (138.78 MiB)**, signed with the debug keystore (cert SHA-256 `c4a103636f460152cf1353b3afca99bf9b41740c75412b6db28d07546bc86094`), APK SHA-256 `EAAE2F67066185837FE2A9688257B65263F145D77DFDCF4ED1E02DFACADEA9EA` |
| Size delta | +151,634 bytes over M10 (145,367,120 B): the model manager adds no native library; the growth is Compose UI, the catalogue asset and the downloader |
| Install sequence | signed release installed fresh after uninstalling the debug build; onboarding → skip → Settings → public download → chat verified on it (§3) |

## 5. Defects found by evidence

1. **Pause/resume was racy.** A cancelled run kept emitting DOWNLOADING after `pause()`, overwriting the
   PAUSED state; and `pause()` nulled the job reference so `resume()` could not join the cancelled run,
   appending the part file twice and failing with `network`. Fixed with a `@Volatile runId` guard in
   `emit`/`fail` and by keeping the job reference until the next run joins it (`enqueue` does
   `previous?.join()`); the test now also asserts the terminal phase.
2. **AAPT rejects unescaped apostrophes in `values-en`.** "Fits your device's memory" and "model's
   minimum" failed the resource compile until escaped as `\'`; the same trap exists in every English
   string added later.
3. **Cleartext HTTP is blocked for targetSdk ≥ 28**, so the loopback device tests initially timed out (the
   downloader retried and the test only waited for INSTALLED). Fixed with a debug-only
   `usesCleartextTraffic` manifest overlay; the release keeps HTTPS-only traffic.
4. **A loopback server bound to `InetAddress.getLoopbackAddress()` is IPv6 `::1` on the emulator**, while
   the test URL used `127.0.0.1`; binding to `127.0.0.1` explicitly fixed the connection.
5. **JUnit 4 rejects non-void `runBlocking` tests.** Three device tests used an expression-body
   `runBlocking` whose last expression was a value, so the runner failed the class at initialization with
   "should be void"; adding an explicit `Unit` fixed it.
6. **SharedPreferences writes from `am instrument` can be lost** when the instrumentation process exits
   right after `apply()`; the UI flow (Settings → Select) is the authoritative manual evidence and the
   stores' in-process assertions are the automated ones. Not a product defect — noted so a future test does
   not mistake it for one.

## 6. Honest limits / open items

- **No foreground service / notification yet.** A download pauses when the app leaves the foreground and
  resumes from its `.part` file when the app returns. ONB-4 asks for a background-capable flow; the
  resumable part-file behaviour is implemented and tested, the service/notification wrapper is deferred to
  v1.1 (same treatment as STT-3's live partials). The public download in §3 was watched with the app in the
  foreground.
- **MOD-5 "move model" is not implemented.** The storage manager reports free space, models size and
  deletes orphans; moving an installed model between storage volumes is deferred (SAF import already covers
  "bring your own file").
- **Hugging Face search is repo/file entry, not browsing.** The dialog accepts a repo id, file name and
  optional token and builds the download URL; there is no catalogue search UI. Browsing needs network and
  an API-key policy the offline build does not have.
- **Gated HF tokens are kept in memory only.** The token is passed to the request and never persisted; a
  restart asks again. That is deliberate (no secret storage yet) and is the safer default.
- **The public download was measured on the emulator's NAT (~3 MB/s).** A real phone network is unmeasured,
  but the transport, retry, Range and checksum code is now proven against the real host, not only a mirror.
- **Phone WER is still open (F8).** M10's dictation gate is replayed on synthetic TTS on the emulator; the
  phone-number claim stays unverified until a real phone is allowed.
- **Size advisory for unverified entries.** An HF entry with no catalogue size shows 0 bytes until the
  server reports `Content-Length`; the downloader then uses the real total, but the pre-download storage
  warning cannot be exact for such entries.
- **No ABI splits yet.** The release APK still ships both x86_64 and arm64-v8a (138.78 MiB, dominated by the
  llama.cpp and sherpa-onnx native libraries); ABI splits / on-demand packaging remain a packaging task for
  a later milestone.

## 7. Reuse / expand recipes (no hardcoding)

- **Add a catalogue model:** append one object to `app/src/main/assets/catalog/models_catalogue.json`
  (id, displayName, repo, fileName, sizeBytes, sha256, parameterLabel, quantization, contextTokens, ramMb,
  minRamMb, licence, gated, languages, descriptionKey, optional projector). `CatalogueJson.validationErrors`
  fails the startup check if the entry is malformed; no Kotlin changes are needed. Add the two
  `catalogue_<id>_description` strings only if you want a custom description.
- **Change the download host:** implement `DownloadTransport` (one suspend `open` returning a
  `TransportResponse`) and pass it to `ModelDownloader` in `AppComposition`; the downloader, resume,
  checksum, GGUF gate and UI are host-agnostic.
- **Add an onboarding step:** add an enum value to `OnboardingStep`, a branch in `OnboardingScreen`, and
  its strings; `OnboardingFlow` and the "n of 3" counter derive from the enum, not from a constant.
- **Swap the device probe:** implement `DeviceProfileProvider` / `NetworkPolicy` / `StorageProbe` and
  provide it in `AppComposition`; the recommender and downloader take the interfaces.
- **Add a download gate:** `DownloadReasons` + a check in `ModelDownloader`; the UI maps codes to localised
  strings, so a new reason needs a string, not a new branch in the screen.

## 8. UI evidence (signed release + seeded debug build)

| Screenshot | What it shows |
|---|---|
| ![Onboarding privacy](screenshots/m11-onboarding-privacy.png) | Onboarding step 1 of 3 (Arabic RTL): privacy, models, no telemetry, Next / Skip |
| ![Onboarding language](screenshots/m11-onboarding-language.png) | Step 2: interface language choice (Arabic / English) |
| ![Onboarding language English](screenshots/m11-onboarding-language-en.png) | Step 2 in the English UI |
| ![Onboarding model](screenshots/m11-onboarding-model.png) | Step 3: device card (RAM 5923 MB, free 11.76 GB, API 36), recommended model + why + Download, "try without a model" |
| ![Onboarding model installed](screenshots/m11-onboarding-model-installed.png) | Step 3 after a download completed: "The model is installed." and Start chatting |
| ![Onboarding done chat](screenshots/m11-onboarding-done-chat.png) | The skip path landing in the chat shell |
| ![Release onboarding](screenshots/m11-release-onboarding.png) | Fresh signed release: onboarding step 1 in Arabic, before any data exists |
| ![Release shell](screenshots/m11-release-shell.png) | The release shell after Skip: demo prompts, "No model · Not loaded" |
| ![Settings top](screenshots/m11-settings-top.png) | Settings top after onboarding (appearance, theme pack, language, engine) |
| ![Settings models](screenshots/m11-settings-models.png) | Settings → Models before installing: device card, recommended model, catalogue |
| ![Release settings models](screenshots/m11-release-settings-models.png) | Fresh release: recommended model + Download, catalogue rows and badges (Arabic) |
| ![Release download](screenshots/m11-release-download.png) | Live public download: 3% · 18 MB / 491 MB at 3 MB/s |
| ![Settings model manager](screenshots/m11-settings-model-manager.png) | Model manager after install: Selected badge, Recommended/Gated badges, catalogue rows |
| ![Release settings installed](screenshots/m11-release-settings-installed.png) | Release after the public download: Selected badge on Qwen2.5-0.5B |
| ![Settings installed model](screenshots/m11-settings-installed-model.png) | Installed-models card with Select action |
| ![Settings installed storage](screenshots/m11-settings-installed-storage.png) | Device card + recommended model + catalogue with Selected |
| ![Settings storage manager](screenshots/m11-settings-storage-manager.png) | Storage manager: free space 10.77 GB, models size 491 MB, orphan cleanup |
| ![Model detail](screenshots/m11-model-detail.png) | Model detail sheet: name, file, licence, parameters/quantisation/size |
| ![Model detail actions](screenshots/m11-model-detail-actions.png) | Detail sheet scrolled: RAM, context, languages, repo, estimated speed, licence URL, Delete |
| ![Release chat answer](screenshots/m11-release-chat-answer.png) | The release chat after the public download: model chip "ready on device", "2 plus 2 equals 4." |
| ![Chat answer](screenshots/m11-chat-answer.png) | The same turn on the seeded debug build (English UI) |
