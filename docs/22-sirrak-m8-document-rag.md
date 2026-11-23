# Sirrak — M8 build record: document RAG

**Milestone:** M8 (RAG-1 import/extract, RAG-2 hybrid retrieval, RAG-3 grounded answers + citations, RAG-4 engine isolation spike) · **Status:** closed 19 Nov 2026
**App version:** 0.1.0-m8 · **Date:** 2026-11-19

> M8 turns the attachment seam from M7 into a real on-device knowledge library: import a PDF, DOCX,
> EPUB, Markdown or text file, and ask questions whose answers are grounded in retrieved passages with
> real page numbers. Retrieval is hybrid (embeddings + BM25 fused with RRF) and every number is a
> policy value in `RagLimits`, not a constant buried in UI code. Two things were learned the hard way
> and are documented here as first-class results: llama.cpp can abort on a prefill larger than
> `n_batch` (fixed), and a native abort kills the UI process today (ADR-4 spike proves the fix).

## 1. What landed

- **`:core:rag` — pure-JVM RAG core** (no Android imports):
  - format registry + detection from MIME type and extension: PDF, DOCX, EPUB, TXT, MD
    (`Extraction.kt`; adding a format is one registry entry + one extractor, MD3 style).
  - extractors (`Extractors.kt`): page-aware PDF, streaming DOCX/EPUB zip+XML with per-entry and
    total byte caps (zip-bomb guard), BOM/encoding-tolerant plain text and Markdown.
  - Arabic normalisation (`ArabicText.kt`): diacritics, tatweel, invisible bidi characters,
    alef/ya/ta-marbuta folding for matching only; the displayed text is never mutated.
  - structure-aware chunker (`Chunking.kt`): heading/sentence boundaries, 700-char target,
    1100 max, 120 overlap, 80 min, 400 chunks per document, page number preserved per chunk, and
    **a chunk never spans a page break** (a citation page must be the page the quoted text is on;
    the device PDF test caught the first draft folding page 2 into a page 1 chunk).
  - hybrid retrieval (`Retrieval.kt`): cosine over 768-dim vectors + lexical Okapi BM25, fused by
    Reciprocal Rank Fusion (k = 60), query window 8 -> context window 3.
  - grounded prompt builder (`RagPrompt.kt`): numbered sources rendered as explicitly untrusted
    data, answer-language rule, inline-citation instruction, bounded snippets (480 chars) and a
    truncation marker.
  - citation parser: `\[([0-9]{1,2})]`, out-of-range/hallucinated markers are dropped instead of
    becoming citations.
- **`:engine:embeddings`** — EmbeddingGemma 2 text 270M via LiteRT-LM, 768-dim, batched (8 texts,
  2000 chars max each), model user-imported through SAF (Gemma licence forbids bundling weights).
- **Chat grounding** (`ChatStore.generateGrounded`): retrieval gate runs before generation, empty
  retrieval short-circuits with `RAG_NO_SOURCES`, citations are parsed or fall back deterministically
  (see 4.6), attachments from M7 are included as labelled data, and the M5 parameter layers still
  apply unchanged.
- **Documents library** (`LibraryPlugin`, `DocumentsLibrarySection`, `RagUi.kt`,
  `DocumentPicker.kt`): SAF import, list with format/pages/chunks/status, import progress, reindex,
  delete with cascade, honest empty/error states, Arabic (default) and English strings.
- **Persistence** (`FileRagStore`, `RagRepository`): app-private JSON for documents, chunks and
  vectors; atomic writes; corrupt-file tolerance; embedding-version marker so a model change triggers
  reindex instead of silent garbage.
- **Tests:** 61 new JVM tests in `:core:rag`; device evaluation suite in the app module (see 6).

## 2. Architecture

### 2.1 Module boundaries

```text
feature:chat  ──▶ RagRepository (app) ──▶ :core:rag (pure JVM) ──▶ EmbeddingProvider seam
                        │                       ├─ detection + extraction
                        │                       ├─ normalisation + chunking
                        │                       └─ retrieval + prompt + citations
                        └─▶ RagStore (FileRagStore: documents/chunks/vectors, atomic JSON)

app/rag/PdfBoxExtractor ──implements──▶ DocumentExtractor seam (PDF engine stays out of the core)
:engine:embeddings      ──implements──▶ EmbeddingProvider seam (LiteRT-LM stays out of the core)
```

The core module depends on nothing Android and on no model runtime, so the whole retrieval pipeline
(including Arabic edge cases) is unit-testable on the JVM; PDF extraction and embeddings are seams
that can be swapped (ONNX, a different PDF library) without touching chat or storage.

### 2.2 Import -> extract -> normalise -> chunk

Import is bounded at every step by `RagLimits`: 50 MB per document, 500 pages, 400 chunks, and
zip entries are capped so a decompression bomb is rejected as `TOO_LARGE`/CORRUPT rather than
exhausting the device. Every rejection has a stable `RagImportRejection` code that the UI maps to a
localised message; the library never shows a document that is not fully indexed (no ghost entries).
Chunks keep `documentId`, `page` and `ordinal`, which is what makes citations show a real page
instead of a fake "source: document". Because `page` is provenance, the chunker never lets a chunk
cross a page boundary: short pages yield short chunks (and `mergeTinyTail` only folds within one page),
so the page on a citation always matches the excerpt shown.

### 2.3 Hybrid retrieval (why RRF)

Arabic questions and Arabic documents diverge in orthography and morphology; embeddings handle
paraphrase, BM25 handles exact names and numbers. The two scores are not comparable, so M8 fuses
ranks, not scores:

```text
rrf(chunk) = 1 / (k + rank_vector) + 1 / (k + rank_lexical),  k = 60
```

Top 8 fused chunks are cut to the 3 that fit the 1600-character context budget, in rank order, with
the budget and the snippet cap both in `RagLimits`/`RagPromptPolicy`.

### 2.4 Grounded prompt (Hāris invariant 5)

Retrieved text is user data, never instructions:

```text
You are Sirrak, an on-device assistant. Answer only from the numbered sources below.
Answer in the same language as the question.
Cite the sources you use inline as [1], [2] immediately after each claim...
Sources:
[1] page 3 — ...
...
Question: ...
```

The prompt policy is data (`RagPromptPolicy`): instructions, headers, source line format, snippet
cap, context cap and truncation marker are all editable without touching retrieval code; the tests
assert the policy fields are present and that document text cannot masquerade as instructions.

### 2.5 Embedding pipeline

Only chunks are embedded (never whole documents), in batches of 8, truncated at 2000 characters.
Vectors are L2-normalised at write time, so retrieval is a dot product. The embedding model identity
(name + version) is stored next to the vectors; changing the model surfaces a reindex action rather
than mixing incompatible vector spaces.

### 2.6 Citations: best-effort markers + deterministic fallback

On-device evaluation with 0.5B and 1.5B Q4_K_M models answered the questions correctly but often
omitted the inline `[n]` markers (0 markers in the recorded 1.5B run). Losing provenance entirely
was worse than the alternative, so `ChatStore.generateGrounded` now falls back to the retrieved
numbered sources when the model emits no marker at all:

- markers that resolve to a real source win (small models that do cite behave exactly as before);
- a hallucinated marker (`[9]` with 3 sources) can never create a citation;
- the fallback cites the sources the answer was actually conditioned on, with real document name,
  page and excerpt; it is labelled "Sources used" / "المصادر المستخدمة", not "the model cited these";
- the instruction is still strengthened first, so larger models (M9/M11 candidates) can earn exact
  citations without a code change.

This is a deliberate, documented trade-off: guaranteed provenance at the risk of listing one source
the model did not end up using. Correctness of the *answer* is unchanged (the same context is sent).

### 2.7 UI

- Documents library in the Library tab: import FAB (SAF, multiple files), status rows, document
  detail with pages/chunks and reindex/delete, honest disabled states while the embedding model is
  missing (gate from M7 extended with EMBEDDING_UNAVAILABLE).
- Chat: grounded answers show citation chips under the message; tapping opens a bottom sheet with
  document name, page and the excerpt; the empty-retrieval path explains "no relevant sources in your
  documents" instead of hallucinating.
- Settings: embedding model card reuses the M5 model-control pattern (import, status, delete) with
  licence text; no model is bundled in the APK.
- All strings in `values/` (Arabic, default) and `values-en/`; theme tokens only; 48 dp targets and
  content descriptions; RTL verified on the emulator.

### 2.8 Privacy and cost

Extraction, chunking, embeddings, retrieval and generation all run on the device; no network
permission is used for RAG and there is no telemetry. The import is the only data entry, vectors are
app-private, deletion cascades through documents, chunks, vectors and any attachment rows.

## 3. Device evaluation

Measured on the project emulator (Nawa_Phone, API 36, x86_64) with qwen2.5-1.5B Q4_K_M +
EmbeddingGemma 2 270M; the same suite runs with 0.5B for faster feedback.

| Check | Result |
| --- | --- |
| Arabic fact retrieval, 60-document library | 20/20 questions returned the expected fact in the top-3 |
| Retrieval latency | average 596 ms per question (embed + BM25 + RRF), 20 questions |
| Largest retrieval prompt | 880 characters, 220 estimated tokens |
| PDF page fidelity | 2-page fixture indexes as 2 chunks with pages 1 and 2; retrieving the page-1 fact keeps `page = 1` |
| PDF engine coverage | 10 device tests over text, empty pages, RTL and metadata (`PdfBoxExtractorDeviceTest`) |
| Grounded generation | prompt 807 native tokens; prefill 129.3 s in 2 chunks; 26 tokens at 3.85 tok/s; peak RSS 1936.6 MB; total 136.6 s |
| Citations | 3 sources returned by the fallback path; inline markers 0 (this run answered in English although asked in Arabic — the 1.5B Q4 model is not reliable on instruction language) |

The grounded generation test ("grounded generation cites the retrieved source on device") asserts the
answer is non-blank, not failed, has non-empty citations, every citation belongs to the imported
document, every page is >= 1 and every excerpt is non-blank. It runs with a 480 s timeout because a
1.5B prefill on the emulator takes ~2 minutes; production targets smaller models (M11).

## 4. Native crash: prefill larger than n_batch

### 4.1 Symptom

During grounded Arabic generation the app process died with SIGABRT (`ggml_abort`) inside
`llama_context::decode`, tripping `GGML_ASSERT(n_tokens_all <= cparams.n_batch)`. The prompt was
807 native tokens; `n_batch` was 512. A single `llama_decode` was being asked to process the whole
prompt.

### 4.2 Why the obvious bound failed

`TokenEstimate.tokens(chars / 4)` estimated 516 tokens for that prompt (and 209 for the retrieval
prompt), but the tokenizer produced 801-807: Arabic letters are 1-2 tokens each, so a characters/4
estimate under-counts by ~1.6x. Bounding `n_batch` from a character estimate is therefore unsafe.
Chunked prefill is the general fix, not a bigger constant.

### 4.3 Fix

`nativeGenerate` now prefills in slices of at most `n_batch` tokens with a fresh `llama_batch` per
slice, logs `PREFILL tokens=... n_batch=... chunks=...`, and requests logits only from the final
slice. The session stores `n_batch` read back from `llama_n_batch(ctx)` instead of assuming a value.
Verified on device: `PREFILL tokens=807 n_batch=512 chunks=2`, no abort, generation completes.

### 4.4 Consequence

Chunked prefill fixes the crash *inside* llama.cpp, but any native abort (a different assert, OOM,
a bug in a future model path) still kills the whole app because the engine runs in the UI process.
That is the ADR-4 problem below; M8 ships the spike, not the migration.

## 5. ADR-4 (M8.8): engine process isolation spike

**Decision:** keep production inference in-process for M8; adopt process isolation before public
beta (M9/M11/late v1.0). The spike proves the seam and the recovery path now, while the product still
has time to move at a controlled pace.

**Alternatives considered**

| Option | Why not now |
| --- | --- |
| In-process + try/catch | Native aborts/SIGSEGV are not catchable Kotlin exceptions; the UI dies. |
| Separate APK / shared service app | Distribution and signature complexity far beyond the milestone; no benefit over a private process. |
| AIDL interface | Fine, but more boilerplate; Messenger already carries the small load/generate/stop vocabulary and is enough for the spike. |

**Spike shape (shipped, not wired into production composition):**

- `:engine:llamacpp` manifest declares `IsolatedEngineService` with `android:process=":engine"`,
  `exported=false`, `stopWithTask=true`.
- `EngineWire`: stable message ids and bundle keys (load, generate, stop, unload, debug crash; loaded,
  token, done, error) so the wire is data, not ad-hoc strings.
- `IsolatedEngineService`: hosts a `LlamaCppEngine`, copies message data before the coroutine (the
  Looper recycles the Message), streams tokens to `replyTo`.
- `RemoteInferenceEngine`: implements the same `InferenceEngine` contract as the in-process engine;
  binder death surfaces as `EngineStatus.Failed(EngineReasons.PROCESS_LOST)`, and `connect()` +
  `load()` recover in a fresh process.
- `SirrakApplication` composes the UI graph only in the UI process; the engine process is a bare
  service host (verified by the plugin log appearing only for the UI pid).
- `MSG_SIMULATE_CRASH` is honoured only when the app is debuggable and calls
  `Process.killProcess(Process.myPid())` - the same abrupt death a failed GGML assert produces.

**Evidence** (`EngineIsolationDeviceTest`, 2 device tests, green):

- engine ran as `app.sirrak:engine` pid 25869 while the test/UI process was pid 25722; tokens
  streamed over Binder (`The sky is vast and blue...`);
- after the simulated native abort the engine pid died, the UI pid stayed alive, status was
  `engine-process-lost`;
- a retry after `close()`/`connect()` loaded in a new engine pid (25975) and generated again.

**What adoption still needs:** serialization for attachments (or a shared file descriptor), a
foreground service/notification for long generations, crash-loop backoff, capability reporting for
M9 vision, and moving the model/parameter layers over the wire instead of reading them in-process.

## 6. Testing

JVM unit tests (debug variant, definitive counts from `build/test-results`):

| Module | Tests |
| --- | --- |
| `:core:runtime` | 72 |
| `:core:rag` | 62 |
| `:core:design` | 47 |
| `:feature:chat` | 68 |
| `:engine:embeddings` | 6 |
| `:app` | 147 |
| **Total** | **402** |

All green; `:app:lintDebug` completed without errors; `:app:assembleDebug` and the full `test`
task (debug + release variants) pass in one Gradle run.

Device tests: `RagDeviceEvaluationTest` (3), `EngineIsolationDeviceTest` (2),
`PdfBoxExtractorDeviceTest` (10) = 15 tests, run together via
`adb shell am instrument -w -e class ... app.sirrak.test/androidx.test.runner.AndroidJUnitRunner`.

Final device run after the chunker page-boundary fix and code freeze: **OK (15 tests)** in 208.2 s
(`app.sirrak.RagDeviceEvaluationTest:...`, `EngineIsolationDeviceTest:..`,
`PdfBoxExtractorDeviceTest:..........`). The same run re-measured the numbers in section 3 and the
ADR-4 evidence in section 5.

## 7. Build record

| Item | Value |
| --- | --- |
| Version | `versionCode 8`, `versionName 0.1.0-m8` |
| JVM verification | `test` + `:app:lintDebug` + `:app:assembleDebug`: BUILD SUCCESSFUL, 402 tests, 0 failures, 0 lint errors |
| Release build | `:app:assembleRelease`: BUILD SUCCESSFUL after adding `-dontwarn com.gemalto.jp2.**` (PDFBox-Android JPX optional dependency) |
| Release APK | `app-release-signed.apk`, 74,566,976 bytes (71.1 MiB), signed with the debug keystore (cert SHA-256 `c4a10363...bc86094`), SHA-256 `03229B38...E0126C13` |
| Install | `adb install -r` over the debug build: data preserved (RAG library, embedding model, conversations all still present) |
| Size note | The APK grew from the M7 release because LiteRT-LM and the native llama.cpp library ship for two ABIs; ABI splits are an M11 packaging task |

The screenshots below were captured from this signed release build, on the seeded production stores
(embedding model imported, four documents indexed, a real 0.5B grounded answer generated on device).

## 8. UI evidence (signed release, seeded production data)

| Screenshot | What it shows |
| --- | --- |
| ![Grounded answer with citation](screenshots/m8-chat-grounded.png) | Question, grounded answer, "المصادر المستخدمة" and the citation chip with document name and page 1 |
| ![Citation excerpt sheet](screenshots/m8-citation-sheet.png) | Tapping the citation opens the excerpt sheet: document, page and the exact indexed text |
| ![Document library sheet](screenshots/m8-documents-library.png) | The conversation's document scope: four ready documents, select-all, import, and the on-device note |
| ![Embedding model settings](screenshots/m8-settings-embedding.png) | Settings: embedding model "ready on device", imported file and 165 MB size |
| ![Conversation history](screenshots/m8-conversation-history.png) | The two persisted seeded conversations, each with its answer preview |

## 9. Deferred / honest gaps

- **Embedding model distribution:** not bundled (Gemma licence); SAF import today, M11 downloader
  with licence acceptance and checksum verification is the product path.
- **Generation speed:** 1.5B is ~4.2 tok/s on the emulator; 0.5B is the practical default. M11 model
  manager will make the trade-off visible and keep a recommended preset.
- **Inline citations:** best-effort; see 2.6. Larger models and M9+ prompt work may earn exact
  citations, with the deterministic fallback kept as the floor.
- **Scanned PDFs:** no OCR; a text-less PDF is rejected as `NO_TEXT` rather than pretending to index
  it.
- **EPUB images:** text only; grounding uses text, images are M9 material.
- **Isolation:** spike, not production; single in-flight generation, no attachment wire, no
  foreground service. Adoption tracked under ADR-4 before public beta.
