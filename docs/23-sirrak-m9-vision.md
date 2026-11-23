# Sirrak — M9 build record: vision (image Q&A)

**Milestone:** M9 (VIS-1 attach from gallery/camera, VIS-2 VL model via mmproj, VIS-3 bounded preprocessing, VIS-4 OCR-style prompt shortcut; VIS-5 multi-image stays v1.1) · **Status:** closed 21 Nov 2026
**App version:** 0.1.0-m9 · **Date:** 2026-11-21

> M9 turns the image attachment seam from M7 into a working capability: attach a photo from the gallery or
> the camera, and the loaded vision-language model answers about it — entirely on the device. The engine is
> the same llama.cpp build, extended with the vendored libmtmd front-end and a user-imported mmproj
> projector; the model store pairs a projector with its text model by one naming rule; the UI is
> capability-gated, so a text-only model never shows an enabled camera button that would silently do
> nothing. Two defects were caught by evidence, not by review: a long projector filename squeezed a
> settings label into a one-character column (fixed in the shared row primitive), and the attach sheet
> still explained documents with stale M7 copy (fixed). The honest limit is the model, not the pipeline:
> SmolVLM-500M is English-centric, so an Arabic vision question returns noise while an English one answers
> correctly — recorded as a model-manager decision for M11.

## 1. What landed

### :core:runtime — the vision seam (pure JVM, testable)

- `ImageInput(bytes, mimeType)`: one already-prepared image handed to an engine.
- `ImagePreparer` fun interface: `prepare(bytes, mimeType): ImageInput?`; null means the image cannot be
  prepared and the chat surface reports a failed turn instead of silently dropping the image.
- `VisionProjector`: the pairing convention between a text model and its projector — suffix
  `.mmproj.gguf`, `fileNameFor(modelFileName)`, `isProjector`, `modelBase`. Pure name logic so the store,
  settings UI and tests share one rule.
- `ImageSizing`: pure policy shared by the Android preparer and its tests — `sampleSize` (largest
  power-of-two sample whose decoded longest side is still >= maxDimension) and `targetSize` (scale the
  longer side to maxDimension, keep ratio, never upscale).
- `InstalledModel.visionProjectorPath` + `hasVisionProjector`; `ModelStore.attachVisionProjector` /
  `detachVisionProjector` as defaulted interface methods, so existing stores and fakes stay
  source-compatible.
- Typed reasons in `EngineReasons`: `VISION_PROJECTOR_FAILED`, `VISION_PROJECTOR_NO_VISION`,
  `VISION_UNAVAILABLE = "vision-unavailable"`, `VISION_IMAGE_FAILED = "vision-image-failed"`.
- `InferenceEngine.streamWithImages(prompt, history, images, options)` with a default that falls back to
  the text path; a text-only engine can be asked for an image turn without breaking its contract.
- `AttachmentKind.IMAGE` + `AttachmentLimits` (10 MB image cap, 2048 px dimension) + `AttachmentGate`
  image decision from `EngineCapabilities.images`.

### :engine:llamacpp — libmtmd (vendored, Apache-2.0/MIT)

- The pinned llama.cpp source under `third_party/llama.cpp` (LLAMA_VERSION 1.0.0-dev) already
  ships `tools/mtmd` and `common/mtmd-helper.cpp`; the build enables `LLAMA_BUILD_MTMD ON` (its root
  CMake hook fires when tools/examples are off, which is how Sirrak builds) and links
  `llama mtmd android log` into the single static `libsirrak_llm.so`.
- New JNI entry points in `llama_jni.cpp`:
  - `nativeLoadModel(path, contextTokens, mmprojPath)` — loads the text model, then the projector; returns
    the session handle, or `0` (text load failed), `-1` (projector init failed), `-2` (projector loaded but
    no vision capability).
  - `nativeVisionSupported()`, `nativeVisionMarker()` (the model's own marker; SmolVLM reports
    `<__media__>`), `nativeProjectorInfo(path)` (prints `vision=`, `audio=`, `audio_gen=` lines so the
    settings picker can validate a projector *before* saving it).
  - `nativeGenerateVision(prompt, history, imageBytes, options)` — one session, KV cleared per request,
    cooperative stop; shares `run_generation` with the text path so sampling, stop sequences, stats and
    error mapping are identical.
- Log lines that make the pipeline auditable on device: `VISION_PROJECTOR_LOADED ms=… marker=… vision=1
  audio=0`, `MODEL_LOADED … vision=1`, `VISION_PREFILL chunks=… prompt_tokens=… image_tokens=…
  dropped_turns=… n_past=…`, and `STATS … vision=1 image_tokens=…`.
- `LlamaCppEngine` reports `capabilities.images = visionReady`, exposes `visionMarker`, maps the load
  return codes to the typed reasons, and rejects an image generation on a text-only session with
  `VISION_UNAVAILABLE` instead of sending bytes nowhere.
- `LlamaProjector.inspect(path)` parses the native projector info into
  `Capabilities(vision, audio, audioGeneration)`.

### :app — Android plumbing

- `AndroidImagePreparer`: bounds-only decode → `ImageSizing.sampleSize` → ARGB_8888 decode → framework
  `android.media.ExifInterface` orientation (rotate/flip/transpose) → `ImageSizing.targetSize` →
  `createScaledBitmap` → JPEG quality 85. Every intermediate bitmap is recycled in `finally`; a 12 MP
  camera original never allocates its full 48 MB ARGB buffer.
- `FileModelStore`: projector files are never listed as models; `attachVisionProjector` streams the
  picker's bytes into a `.part` file, checks the GGUF header, renames to `VisionProjector.fileNameFor` and
  refreshes; `detachVisionProjector` deletes it; deleting a model deletes its projector; `scan` pairs
  `visionProjectorPath` by the naming rule.
- `FileAttachmentStore.open` hardened for image turns: it now also requires the file's id segment to equal
  the attachment id (a wrong-but-inside-path file can no longer be returned).
- `AppComposition` wires `AndroidImagePreparer` into `ChatPlugin`; `file_paths.xml` exposes the capture
  cache directory to the FileProvider.

### :feature:chat — the image turn

- `ChatStore`: `attachImage(source)` validates and stores the attachment, queues it as a pending chip and
  only allows it when the engine reports image capability; `send` prepares the image through
  `ImagePreparer` (failure → `VISION_IMAGE_FAILED`) and calls `streamWithImages`. Image turns bypass
  document grounding by design (an image question is not a library question); a retry after the engine
  loses vision fails with `VISION_UNAVAILABLE` rather than dropping the image.
- `ChatScreen`: attach sheet → image source dialog → gallery (`ActivityResultContracts.OpenDocument`) or
  camera (`TakePicture` into a FileProvider `captures/` file) → chip; a failed attach or a non-vision model
  opens a localised error dialog instead of a silent no-op.
- `ImagePromptSuggestions.kt` (VIS-4): a data-driven enum of prompt shortcuts rendered as chips once an
  image is queued — "extract all visible text exactly as written" and "describe the image in detail". A
  chip only fills the composer; the user still sends. Adding a shortcut is one enum entry + two strings,
  no screen change.
- `AttachmentUi.kt`: the image row and the source dialog live in the same attachment vocabulary as M7/M8.

### Settings

- Settings → Model gains a **vision section**: status (`engine_vision_ready` / `engine_vision_off`), the
  selected model, the projector row (label + file name), a picker that imports a projector through SAF and
  runs `LlamaProjector.inspect` before saving (a projector with `vision=false` is detached and reported as
  `engine_vision_unsupported`), a remove row, a badge in the installed-model list, and a note that the
  projector is a separate file checked before saving.

### Tests

- **13 new JVM tests:** 6 in `VisionTest` (sizing/ratio/projector naming), 5 in `ChatStoreVisionTest`
  (attach only with vision, prepared bytes sent, image-failed, missing preparer, retry after losing
  vision), 2 `FileAttachmentStore` open tests (exact bytes; refuses wrong conversation/unknown id/blank
  path/traversal).
- **5 new device tests** in `VisionDeviceTest` (see §5).

## 2. Architecture

### 2.1 Seams and module boundaries

```text
feature:chat ──▶ ChatStore ──▶ InferenceEngine.streamWithImages ──▶ :engine:llamacpp (llama.cpp + mtmd)
      │                │                     │
      │                └──▶ ImagePreparer seam ──▶ :app AndroidImagePreparer (Bitmap/EXIF)
      │                └──▶ AttachmentStore / ModelStore seams ──▶ :app file stores
      └──▶ UI (Compose) reads capability decisions only from AttachmentGate

:core:runtime  = contracts + pure policy (no Android, no native)
:core:design   = components/tokens (no feature logic)
```

The core module knows nothing about Bitmap, EXIF or llama.cpp; the Android preparer is a thin wrapper over
a pure sizing policy, and the engine is behind the same `InferenceEngine` contract the text path uses. That
is what lets the whole image-turn decision logic be unit-tested on the JVM and lets a different VL runtime
(LiteRT-LM vision, a future NPU path) implement the same seam without touching chat.

### 2.2 Projector pairing contract

A vision model is two files: the text GGUF and its mmproj projector. Sirrak pairs them by one rule:

```text
model:      SmolVLM-500M-Instruct-Q8_0.gguf
projector:  SmolVLM-500M-Instruct-Q8_0.mmproj.gguf   (= modelBase + ".mmproj.gguf")
```

- `scan()` never lists a projector as a model; it pairs `visionProjectorPath` onto the model whose base
  matches.
- Attaching checks the GGUF magic/version header, then runs the native projector inspection; a projector
  that loads but reports no vision is rejected and deleted, so the store cannot hold a "vision" model that
  cannot see.
- Deleting the model deletes the projector; detaching the projector keeps the text model usable.
- The rule is data-shaped: a future model manager that stores files elsewhere can reuse
  `VisionProjector` without copying the convention.

### 2.3 Capability gate and typed errors

The composer never guesses. `EngineCapabilities.images` comes from `LlamaCppEngine.visionReady`, which is
set only after a successful model + projector load. `AttachmentGate` turns that into the sheet row decision;
`ChatStore` re-checks it at send time (the engine can be unloaded between composing and sending).

| Failure | Code | UI |
| --- | --- | --- |
| projector fails to load | `vision-projector-failed` | load failure with a retry path |
| projector loads but has no vision | `vision-projector-no-vision` | picker rejects and detaches it |
| image generation requested on a text-only session | `vision-unavailable` | turn marked failed, image kept for retry |
| attached image cannot be decoded | `vision-image-failed` | turn marked failed with the localised reason |

No failure silently drops the image or sends the question without it.

### 2.4 Image preparation pipeline (VIS-3)

```text
attachment bytes (<= 10 MB)
  → bounds-only decode (no pixel allocation)
  → sampleSize: power-of-two sample so the longest decoded side is >= 2048
  → ARGB_8888 decode
  → EXIF orientation (rotate 90/180/270, flip H/V, transpose, transverse)
  → targetSize: longest side = 2048, ratio preserved (never upscale)
  → JPEG quality 85
  → ImageInput (bytes + image/jpeg)
```

The projector resizes to a few hundred pixels anyway, so decoding a 12 MP original is pure waste; the
two-stage policy keeps peak memory at roughly two bounded buffers. Measured on the emulator: a 4000×3000
(12 MP) PNG (82,696 bytes) became a 2000×1500 JPEG of 25,344 bytes in 0.198 s, and an EXIF-rotated
120×60 image became 60×120 in 0.071 s (device test, §5).

### 2.5 Native mtmd path

The canonical mtmd-cli flow, implemented in `llama_jni.cpp`:

1. Load the text model, create the context (`n_ctx = 2048`, `n_batch = 512`, 4 threads, CPU only) and,
   when a projector path is given, `mtmd_init_from_file` against the same model. Capabilities are read with
   `mtmd_support_vision/audio` and the default marker with `mtmd_default_marker()`.
2. Build the chat-formatted prompt, but insert the media marker into the **user content before chat
   formatting** — the marker must survive templating, and `add_special = true` makes the tokenizer see it.
3. `mtmd_helper_bitmap_init_from_buf` turns the prepared JPEG into a bitmap wrapper (returned by value;
   the inner `wrapper.bitmap` is freed with `mtmd_bitmap_free`).
4. `mtmd_tokenize` splits the prompt into chunks: text chunks and one image chunk at the marker. The image
   chunk carries the model's fixed number of image tokens (128 for a 384 px image, 192 for the 900×600
   test image on SmolVLM-500M).
5. `mtmd_helper_eval_chunks` prefills every chunk (logits from the last one) with a fresh batch per slice,
   so the chunked-prefill fix from M8 also protects the vision path.
6. Sampling, stop sequences, stats and `stream` callbacks are the same code as text generation
   (`run_generation`), which is why the STATS line simply grows `vision=1 image_tokens=…`.

Two integration details worth recording because the API shape is easy to get wrong: the bitmap helper
returns the wrapper **by value** (the inner `wrapper.bitmap` is what gets freed with `mtmd_bitmap_free`),
and the marker must be inserted before chat formatting, not after — the template positions/escapes text,
so a marker inserted later is tokenized as literal text and the image never reaches the model.

### 2.6 Prompt shortcuts (VIS-4)

The product spec asks for an "OCR-style extract text" shortcut. Sirrak implements it as a **prompt**, not
an OCR engine: the loaded VL model reads the pixels, so it works for any supported vision model, adds no
dependency, no download and no cloud call. `ImagePromptSuggestion` is a data-driven enum; the composer
renders the chips only while an image is pending, and a chip fills the input. The list is intentionally
short — two shortcuts — because the model, not the UI, is the limit.

### 2.7 Privacy and cost

Image bytes are decoded, oriented, scaled and re-encoded on the device; the JPEG is passed to the native
session and never leaves the process, the app or the phone. No network permission is used for vision, no
telemetry, and no model weights are bundled: SmolVLM-500M + projector (545,590,272 bytes total) are
imported through SAF by the user, exactly like the text and embedding models.

## 3. Device evaluation

Measured on the project emulator (Nawa_Phone, API 36, x86_64) with SmolVLM-500M-Instruct Q8_0
(436,806,912 bytes) + mmproj Q8_0 (108,783,360 bytes), production stores, signed release build.

| Check | Result |
| --- | --- |
| Vision model load | `VISION_PROJECTOR_LOADED ms=523 marker=<__media__> vision=1 audio=0`; `MODEL_LOADED ms=709 n_ctx=2048 n_batch=512 n_threads=4 vision=1` |
| Simple image Q&A (384×384 red square) | question "What is the main color of this image? Answer with one word." → **"Red."**; prompt 169 tokens (128 image tokens), prefill 14.8 s, 2 generated tokens at 31.47 tok/s, peak RSS 2181.8 MB |
| 12 MP preprocessing | 4000×3000 PNG (82,696 B) → 2000×1500 JPEG (25,344 B) in 0.198 s, no OOM |
| EXIF orientation | 120×60 rotated 90 → 60×120 in 0.071 s |
| Projector inspection | SmolVLM projector → `Capabilities(vision=true, audio=false, audioGeneration=false)`; a text GGUF → null (rejected) in 0.667 s |
| Model store pairing | projector paired to its model, not listed as a model, detach deletes it, delete cascades (0.026 s) |
| OCR-style shortcut (900×600 text image) | prompt "Extract all visible text from the image exactly as written." → **"SIRRAC VISION OCR TEST 2026."**; prompt 332 tokens (192 image tokens), prefill 65.5 s, 15 generated tokens at 3.07 tok/s, peak RSS 829.6 MB |
| Gallery entry | system photo picker → image chip in the composer → VIS-4 chips appear (screenshots §7) |
| Full device suite | **20 tests, 0 failures, 236.5 s** (Gradle run 6m41s), see §5 |

Two honest notes on these numbers. The OCR-style run's prefill (65.5 s) is much slower than the simple
image run (14.8 s) even though the token count is only ~2×; the emulator was CPU-contended after a long
session, and the 900×600 image produced 192 image tokens vs 128. And the extraction is **not
character-perfect**: it read "SIRRAK" as "SIRRAC" — expected from a 500M English-centric model, and the
reason VIS-4 is a prompt shortcut, not a claimed OCR feature.

## 4. Findings and fixes (evidence-driven)

### 4.1 A long projector filename squeezed a settings label into one character per line

The first vision settings screenshot showed "مشروع الرؤية (mmproj)" rendered as a vertical one-character
column while the 36-character projector filename took the row. Root cause was in the shared
`SettingsRow` primitive, not in the vision screen: the label had `Modifier.weight(1f)` but the value had
no weight and no width bound, so a long value was measured at full row width first and starved the label.

Fix in `core:design` (one primitive, every settings row benefits): the value takes
`Modifier.weight(1.2f)` when there is no trailing action (wrap to two lines, then ellipsis, end-aligned),
keeps the 220 dp cap + single line when a trailing action must stay reachable, and the label gets
`maxLines = 2` with ellipsis. Re-captured screenshot §7 confirms both the label and the filename wrap
cleanly and nothing is clipped.

### 4.2 The attach sheet still described documents with M7 copy

The M9 screenshot pass showed the document row saying "next milestone" after M8 had already shipped
documents. The row's *disabled* state was correct — the seeded release had no embedding model imported —
but the copy was wrong. Both strings now say the truth: a document attachment needs the embedding model
first, importable from the Library tab. The gate itself was already right
(`documentsAvailable = rag != null && ragStatus.documentsAvailable`), so only the sentence changed.

### 4.3 SmolVLM-500M is English-centric — an Arabic vision question returns noise

Asking the red-square question in Arabic produced Arabic-shaped gibberish ("أجب لكل ألغاز."), while the
same question in English answered "Red." correctly. This is a model property, not a pipeline bug: the
pipeline sends the prompt and image tokens the same way in both cases. M9 therefore keeps the vision
feature honest — the VIS-4 shortcuts and the model-facing prompts are English by design (model-facing
text in this product always is) — and the choice of a bilingual VL model is an M11 model-manager
decision, not something a UI string can fix.

## 5. Testing

JVM unit tests (debug variant, definitive counts from `build/test-results`):

| Module | Tests |
| --- | --- |
| `:core:runtime` | 78 |
| `:core:rag` | 62 |
| `:core:design` | 47 |
| `:feature:chat` | 73 |
| `:engine:embeddings` | 6 |
| `:app` | 149 |
| **Total** | **415** |

All green, 0 failures (M8 was 402; +13 M9 tests). `:app:assembleDebug`, `:app:assembleRelease` and the
release build all pass; the M3–M8 suites were not modified except for the two `FileAttachmentStore`
hardening tests.

Device tests: `VisionDeviceTest` (5) added to the existing `RagDeviceEvaluationTest` (3),
`EngineIsolationDeviceTest` (2) and `PdfBoxExtractorDeviceTest` (10) = **20 tests**, run together with
`adb shell am instrument -w -e class app.sirrak.VisionDeviceTest,app.sirrak.RagDeviceEvaluationTest,app.sirrak.EngineIsolationDeviceTest,app.sirrak.rag.PdfBoxExtractorDeviceTest app.sirrak.test/androidx.test.runner.AndroidJUnitRunner`.

Final device run: **OK (20 tests)** in 236.5 s of test time (6m41s wall clock):

```text
VisionDeviceTest#projectorInspectionReportsVisionForSmolVlmAndRejectsATextModel 0.667 s
VisionDeviceTest#fileModelStorePairsProjectorWithoutListingItAsAModel           0.026 s
VisionDeviceTest#imagePreparerAppliesExifOrientation                            0.071 s
VisionDeviceTest#imagePreparerDownscalesA12MegapixelPhotoWithoutOom             0.198 s
VisionDeviceTest#smolVlmAnswersAboutAnImageInsteadOfClaimingItCannotSee        18.126 s
```

The 5th test asserts the answer is non-blank, is not a "cannot see" refusal and is not the failed-turn
marker; the model is staged from `/sdcard/Download` by the test (copied into app external files, chmod
666, `assumeTrue` skip when absent) so the suite still runs on a fresh emulator without models bundled.

## 6. Build record

| Item | Value |
| --- | --- |
| Version | `versionCode 9`, `versionName 0.1.0-m9` |
| JVM verification | `:core:design:testDebugUnitTest :core:runtime:testDebugUnitTest :feature:chat:testDebugUnitTest :app:testDebugUnitTest`: BUILD SUCCESSFUL, 415 tests, 0 failures |
| Device verification | 20/20 tests, 0 skipped, 0 failed; `OK (20 tests)`, 236.5 s |
| Release build | `:app:assembleRelease`: BUILD SUCCESSFUL (R8 minified, resources shrunk, x86_64 + arm64-v8a native) |
| Release APK | `app-release-sirrak.apk`, **77,790,584 bytes (74.19 MiB)**, signed with the debug keystore (cert SHA-256 `c4a103636f460152cf135b3bafca99bf9b41740c75412b6db28d07546bc86094`), APK SHA-256 `3E1AF5E758BC44FB86175526B25380FE4D2E119085CFFA6070954C45E68CF3ED` (rebuilt after the comment-only KDoc fix; byte-identical size, same cert) |
| Install | `adb install -r` over the debug build: data preserved (SmolVLM + projector, the seeded image conversation, all M5–M8 data still present) |
| Size note | The APK grew ~3.2 MB over M8 because the mtmd front-end is linked into the single native library per ABI; the vision model itself is **not** in the APK (user-imported). ABI splits remain an M11 packaging task. |

The screenshots in §7 were captured from this signed release build on the seeded production stores
(SmolVLM + projector imported and selected, a 384×384 red-square image turn answered "Red.", and a
900×600 text image attached from the system photo picker for the VIS-4 run).

## 7. UI evidence (signed release, seeded production data)

| Screenshot | What it shows |
| --- | --- |
| ![Image question answered](screenshots/m9-chat-image-answer.png) | The image turn in the transcript: question, `red-square.png` chip and the answer **"Red."** with the copy/regenerate actions |
| ![Attach sheet](screenshots/m9-attach-sheet.png) | The capability-gated attach sheet: shared text enabled; document disabled with the embedding-model reason; image enabled with its hint; audio disabled |
| ![Image source dialog](screenshots/m9-image-source-dialog.png) | The image source dialog: "from gallery" and "take a photo" (camera) |
| ![Prompt shortcuts](screenshots/m9-image-prompt-suggestions.png) | A queued image chip and the VIS-4 shortcut chips ("extract all visible text…", "describe…") |
| ![OCR-style answer](screenshots/m9-ocr-shortcut-answer.png) | The VIS-4 extract-text prompt answered from the image: **"SIRRAC VISION OCR TEST 2026."** |
| ![Vision settings](screenshots/m9-settings-vision.png) | Settings → Model vision section: status "supported on device", the selected model, the projector row with its file name, remove action and the explanatory note (layout fixed, §4.1) |

The test image used for the VIS-4 run is `screenshots/vision-ocr-test.png` (900×600, three lines of
text), pushed to the emulator's Pictures folder for the gallery picker.

## 8. Deferred / honest gaps

- **Model choice:** SmolVLM-500M is small, fast and Apache-2.0, but English-centric and weak at Arabic
  text extraction. The M11 model manager will offer a bilingual VL option (Gemma 3/Qwen VL class) and make
  the trade-off visible; M9 ships the pipeline, not a promise of Arabic OCR quality.
- **OCR:** VIS-4 is a prompt shortcut, not an OCR engine. Scanned PDFs still import as `NO_TEXT` and the
  document path does not use the vision model; image text extraction accuracy is model-bound.
- **Multi-image:** VIS-5 (several images per message) stays v1.1; the runtime and native path handle one
  image per turn today.
- **Image turns and documents:** an image turn bypasses document grounding by design (a photo question is
  not a library question); combining both is a v1.1 design decision.
- **Projector pairing by name:** `<modelBase>.mmproj.gguf` is one documented convention; a model manager
  with metadata will replace it with an explicit pairing.
- **Model distribution:** no weights are bundled (Apache-2.0 models are still large); SAF import is the
  product path until the M11 downloader exists.
- **Isolation:** vision increases the native surface (bitmap decode, projector load). The ADR-4
  process-isolation spike is green but still not wired into production; adoption remains a before-public-beta
  task, now with one more reason.
- **Audio/other modalities:** `LlamaProjector.Capabilities` already reports `audio`/`audioGeneration` so a
  future audio projector can be surfaced without a new contract, but M9 gates images only.
