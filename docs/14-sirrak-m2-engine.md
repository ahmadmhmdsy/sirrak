# 14 — Sirrak M2: the real on-device engine

**Status:** M2 built, installed and verified on the Nawa_Phone emulator, **18 November 2026**.
**Product code:** `.` (Apache-2.0, offline, no telemetry) · **Brand:** Sirrak (سِرِّك) · **Version:** `0.1.0-m2`
**Predecessor:** doc 13 (M1 shell) · **Frontend ruling:** doc 12 (council, mode C) · **Engine choice:** doc 10 (Step Zero, llama.cpp v1.0.0)
**Estimate:** the M1 app plus one new Gradle module (`:engine:llamacpp`), a static JNI library built
from vendored llama.cpp, a model store and import flow, and a real streaming engine behind the existing
`InferenceEngine` seam.

This document is the durable record of what M2 is, why it is shaped this way, what was verified on the
emulator with real numbers, what is deliberately missing, and how to change it without breaking it.

## 0. What exists

M1's demo provider is replaced (behind the same interface) by a real llama.cpp engine. The app now:

- imports a **GGUF model file** from anywhere Android's document picker can reach (SAF
  `OpenDocument`), copies it into app-private storage atomically, verifies the GGUF magic, and
  records it in a persistent local model registry;
- **loads and unloads** a selected model on the fly from Settings, and **auto-loads** the remembered
  selection on every cold start;
- **streams real tokens** into the chat screen with the M1 UI unchanged — same `InferenceEngine`
  interface, same ChatStore, same composer/stop button;
- reports engine state honestly (`NotLoaded` → `Loading` → `Ready` / `Failed`) in a header chip and
  in an engine section in Settings, and keeps the demo provider available behind configuration;
- **fails loudly** with typed reasons instead of silent no-ops: `no-model`, `load-failed`,
  `no-output`, `generation-failed`, `stopped`.

| | No model | Imported, ready | Real answer |
|---|---|---|---|
| Arabic, light | ![No model](assets/sirrak-m2-chat-no-model.png) | ![Ready](assets/sirrak-m2-chat-ar-light.png) | ![Answer](assets/sirrak-m2-answer-ltr.png) |
| Engine state | — | ![Engine ready](assets/sirrak-m2-engine-ar-light.png) | ![Streaming](assets/sirrak-m2-streaming-t10s.png) |

More evidence: [settings, engine section](assets/sirrak-m2-settings-engine-top.png) ·
[SAF import](assets/sirrak-m2-saf-picker.png) ·
[loading after import](assets/sirrak-m2-imported-loading.png) ·
[auto-load after relaunch](assets/sirrak-m2-relaunch-autoload.png) ·
[unloaded](assets/sirrak-m2-engine-unloaded.png) · [reloaded](assets/sirrak-m2-engine-reloaded.png) ·
[stop keeps the partial answer](assets/sirrak-m2-stopped-quiet.png) ·
[English engine UI](assets/sirrak-m2-engine-en.png) · [dark](assets/sirrak-m2-chat-dark.png) ·
[font scale 1.3](assets/sirrak-m2-engine-fontscale-1_3.png) ·
[library with importable catalogue](assets/sirrak-m2-library-ar.png).

## 1. Scope decisions (and what was deliberately not done)

| Decision | Why | Consequence |
|---|---|---|
| **llama.cpp v1.0.0, vendored + statically linked** | Doc 10 already proved this engine, GGUF breadth, Apache/MIT licence and arm64 link on this machine. No runtime dlopen, no download of native code. | One `libsirrak_llm.so` per ABI; no JNI dependency on an external server; engine code is pinned at `third_party/llama.cpp`. |
| **Real engine is a plugin, not a rewrite** | The M1 seam (`InferenceEngine`) was designed for exactly this. | `LlamaCppPlugin` replaces `DemoEnginePlugin` via one config value; ChatStore and every screen stayed the same. |
| **Import by SAF, not a built-in downloader** | Offline/no-telemetry product; a model downloader implies network code, hosting, licence questions and a catalogue we do not own. | The user picks a local GGUF; the app copies it into private storage. A curated catalogue/downloader is M3+. |
| **Model registry in private storage + SharedPreferences selection** | Simplest honest persistence: survives restarts, no DB migration yet. | One selected model, N installed models, delete supported. Registry is a `ModelStore` interface so a DB can replace it. |
| **Auto-load remembered model on start** | PocketPal-class apps feel broken if the chosen model is forgotten; the step from M1 to a usable assistant. | Cold start p95 pays the model load (~1.0–3.0 s warm/cold on the emulator) once; UI shows Loading. |
| **Demo provider retained, config-selected** | The repo must run with no 1.1 GB model file (CI, fresh clone, store screenshots, release smoke). | `engine.provider` in `app.json`: `llamacpp` ships; `demo` is a supported fallback; debug fails loud on unknown ids, release logs and falls back to demo. |

## 2. Architecture

```
app/
  FileModelStore.kt        ModelStore implementation: files/models, .part copy + GGUF magic check,
                           SharedPreferences selection, scan/delete
  LlamaCppPlugin.kt        requires ModelStore + ContentRepository; provides InferenceEngine;
                           slots: EngineModelChip + LlamaEngineSettingsSection; effect closes engine
  DemoEnginePlugin.kt      the M1 demo provider (config fallback), same slots
  Panels.kt                engine chip, model sheet, both settings sections (all strings localised)
  AppComposition.kt        host services incl. ModelStore; engine plugin resolved from app.json
engine/llamacpp/
  src/main/cpp/llama_jni.cpp   JNI: nativeLoadModel / nativeGenerate / nativeRequestStop / nativeUnloadModel
  src/main/cpp/CMakeLists.txt  static llama.cpp + one shared JNI lib, stripped at link
  LlamaNative.kt               System.loadLibrary("sirrak_llm") + external methods + TokenSink
  LlamaCppEngine.kt            InferenceEngine implementation: status flow, load/unload/stop, stream
feature/chat/
  ChatStore.kt             unchanged interface; stop() now records STOPPED instead of a generic failure
  ChatScreen.kt            unchanged layout; typed failure mapping + quiet stopped state
core/runtime/
  Models.kt                InstalledModel, EngineStatus, ModelStore, GgufFile, EngineReasons
  Contracts.kt             InferenceEngine { id, displayName, status, stream, stop }
```

**Wiring, in one line each:**

- host: `AppComposition` constructs `FileModelStore(context)` and registers it as a service;
- manifest: `LlamaCppPlugin` (chosen by `config.engine.provider`) is installed after the host services;
- plugin: collects `store.selected`, loads that model on change and at start, provides `InferenceEngine`;
- ChatStore: `engine.stream(prompt, history)` is collected exactly as in M1 — the chat layer never sees JNI.

Nothing in the chat or settings features imports the engine module. Adding another engine (LiteRT-LM,
a second llama.cpp build, a fake for tests) is one module + one plugin + one config value.

## 3. Import, selection and lifecycle

1. Settings → Engine → **Import a GGUF file** launches `ActivityResultContracts.OpenDocument` with
   `application/octet-stream` (SAF shows any file manager; verified with DocumentsUI Downloads).
2. The picked `Uri` is copied to `files/models/<name>.part` in 1 MiB chunks on `Dispatchers.IO`,
   then renamed atomically. The header is checked for `GGUF` magic before the rename; a bad file
   never enters the registry.
3. Names are de-duplicated, so importing the same file twice yields distinct entries
   (`… (2).gguf`); the display name is derived from the SAF document name, size from the copied file.
4. The first import is auto-selected; later imports leave the selection alone. Selection is stored in
   `SharedPreferences` (`sirrak_models/selected_model_path`), so the app remembers it across restarts.
5. Changing the selection (model row → "Use") loads it: the plugin stops any stream, unloads the old
   model, loads the new one, and updates `EngineStatus`. Settings shows `Loading`, then `Ready` (or
   `Failed` with the reason); the chat header chip repeats the state so the user never wonders.
6. **Unload model** releases native memory (RSS drops back to the app baseline, ~150–200 MB) and sets
   `NotLoaded`. **Load model** reloads the remembered selection without re-importing.
7. Delete removes the file and clears selection if it was selected; the engine unloads.

## 4. Inference path (inside `llama_jni.cpp`)

- **Prompt:** the engine renders the conversation as a Qwen-style ChatML prompt
  (`<|im_start|>role\ncontent<|im_end|>\n`), with a system prompt from `app.json` and a trailing
  `<|im_start|>assistant\n`. History is trimmed from the oldest turn so the prompt fits the context
  window (2048 tokens by default) with room to generate.
- **Default system prompt (shipped):** "You are Sirrak, a private AI assistant running entirely on
  the user's phone. Answer in the language of the user's last message. Be concise, honest and helpful.
  If asked who you are, say you are Sirrak. Never claim to be made by another company." It is plain
  config — replace it in `app.json` without a rebuild. It costs ~37 prompt tokens per turn
  (~5 s extra prefill on the emulator, negligible on a phone). Without it the 1.5B model introduced
  itself as another company's assistant; with it the identity answer is correct
  (`sirrak-m2-identity.png`).
- **Prefill:** `llama_decode` on the prompt batch; first token sampled immediately after.
- **Sampling:** temperature / top-k / top-p from `app.json` via a llama.cpp sampler chain; greedy when
  temperature is 0.
- **Streaming:** each token piece is decoded as UTF-8 with a pending-bytes buffer, so multi-byte
  Arabic characters are never split into mojibake, and delivered through a JNI callback to a Kotlin
  `channelFlow`.
- **Stop:** `nativeRequestStop` sets an atomic flag checked between tokens; the loop returns and the
  stop flag is reset at the end of the run, so a new prompt streams normally.
- **Measurement:** the native side logs one line per turn — `STATS prompt_tokens=… dropped_turns=…
  prefill_ms=… ttft_ms=… generated=… gen_ms=… tok_s=… peak_rss_mb=… stopped=…` — and one
  `MODEL_LOADED ms=… n_ctx=… n_threads=…` per load. These are the numbers in §6.

## 5. Chat semantics on stop (the only behaviour change in the chat layer)

M1 had one failure path for "the engine ended without text". A real engine can end a turn because the
**user pressed stop**, which is not a failure. M2 adds `EngineReasons.STOPPED`:

- stop after some tokens → the partial answer **stays** in the bubble, no error, composer returns to
  send state, inking mark disappears (`sirrak-m2-stopped-quiet.png`);
- stop before the first token → no empty bubble; a quiet inline note "Stopped." in the muted text
  colour (not error red) appears under the turn (`sirrak-m2-chat-no-model.png` shows the no-model
  failure for contrast; unit tests pin the stopped state);
- a genuine empty/failed run still shows the engine error and the Settings shortcut.

This was found by testing (stop during a 6.4 s prefill returned 0 tokens and M1 mapped it to a scary
error), fixed with one reason code and two unit tests, not by changing the engine.

## 6. Verified on the emulator (Nawa_Phone, API 36 x86_64, 6 GB RAM, 6 cores)

### 6.1 Tests — 35 green

``:core:runtime:testDebugUnitTest :feature:chat:testDebugUnitTest :app:testDebugUnitTest```
— **35 tests, all green** (up from 18 in M1):
container/slot lifecycle (8), config parsing/validation incl. engine ranges (7), model-store header
parsing (8), composition manifest + add/remove-one-line (5), ChatStore including streaming, retry,
no-model and both stop cases (7).

### 6.2 Import and load

| Check | Result |
|---|---|
| Imported file | a local Qwen2.5-1.5B-Instruct Q4_K_M GGUF, **1,117,320,736 B** → private `files/models/` (copy verified against on-disk size) |
| Malformed import guard | `.part` + GGUF magic check; only valid headers are committed |
| Auto-load after process restart | `MODEL_LOADED ms=1148 n_ctx=2048 n_threads=4` (stripped build smoke) |
| Warm load (in-process unload → load) | `MODEL_LOADED ms=470` → status Ready |
| Cold load (first launch after import) | `MODEL_LOADED ms=2977` (x86_64 emulator, CPU) |
| Unload | `SirrakLlama: model unloaded`; UI status `NotLoaded`; action flips to "Load model" |

### 6.3 Real generation (three measured turns, 1.5B Q4_K_M, 4 threads)

| Run | prompt_tokens | prefill_ms | generated | tok/s | peak_rss_mb | stopped |
|---|---|---|---|---|---|---|
| Turn 1 | 61 | 8,961.7 | 23 | 4.49 | 1,388.9 | 0 |
| Turn 2 | 44 | 6,234.4 | 72 | 4.57 | 1,385.8 | 0 |
| Final-build turn | 41 | 5,929.9 | 27 | 4.35 | 1,384.1 | 0 |
| Identity turn (with system prompt) | 78 | 10,826.4 | 18 | 4.37 | 1,388.4 | 0 |
| Stop test | 44 | 6,424.9 | 12 | — | — | 1 |

TTFT on this emulator is **dominated by prompt prefill** (~7 prompt tokens/s), not by generation
(~4.5 tokens/s); that is an x86_64 emulator shape, not phone performance. Peak RSS ~1.39 GB for a
1.1 GB model is consistent with mmap + context.

### 6.4 Build artefact sizes

| Artifact | Size | Note |
|---|---|---|
| Debug APK | **65,184,883 B** (62.2 MiB) | includes Compose debug tooling dex; one x86_64 engine |
| Release APK (R8, unsigned) | **16,971,705 B** (16.2 MiB) | arm64-v8a + x86_64 only, stripped native; `versionName 0.1.0-m2`; signed with the debug key (~16,985,128 B) for the internal install test |
| `libsirrak_llm.so` arm64-v8a | 6.41 MB | stripped at link with `-s`; without it the APK packed ~53 MB per ABI |
| `libsirrak_llm.so` x86_64 | 6.95 MB | same |

The first release build of M2 was 114 MB because the NDK CMake build type is `RelWithDebInfo` and
the JNI library kept debug symbols; fixing it was a one-line linker option, not a size trade-off.
Only `x86_64` and `arm64-v8a` are built and packaged; badging advertises exactly those.

### 6.5 Release build executed, and failure paths (19 Nov)

The R8 release APK was signed with the debug key for internal testing (production signing remains
out of scope), installed over the debug build with data preserved, and **executed** — this is the check
that R8 keep rules for the native layer are right.

| Check | Result |
|---|---|
| Release cold start (`am start -W`, `LaunchState: COLD`) | **2,383 ms** first run (cold disk cache) · **1,644 ms** second run |
| Release model auto-load | `MODEL_LOADED ms=9657` (cold disk) then `ms=2777` |
| Release real generation | `STATS prompt_tokens=40 prefill_ms=5098.3 ttft_ms=5099.6 generated=12 gen_ms=2341.9 tok_s=5.12 peak_rss_mb=1332.2 stopped=0` — no crash, no missing JNI symbols |
| Invalid GGUF import | rejected with the localised message "تعذّر الاستيراد: الملف ليس GGUF صالحًا." — no registry row added |
| Second model import (0.5B, 491,400,032 B) | imported, auto-selected and loaded; two models listed |
| Switch model | selecting the 1.5B row unloaded the 0.5B and loaded 1.5B — `MODEL_LOADED ms=591` |
| Delete non-selected model | row and file removed; selected model untouched |
| Delete **selected** model | engine unloaded (`model unloaded`), status `NotLoaded`, selected `No model`, empty-state message, version row shows `0.1.0-m2` |
| Re-import after delete | auto-selected and loaded again — `MODEL_LOADED ms=672` |

Screenshots: `sirrak-m2-release-launch.png`, `sirrak-m2-release-answer.png`,
`sirrak-m2-invalid-import.png`, `sirrak-m2-two-models.png`, `sirrak-m2-delete-selected.png`.

UX note found while testing: **delete is immediate, with no confirmation and no undo**. That is
acceptable while models are re-importable files, but it is on the M3 hardening list (confirm sheet or
undo snackbar), together with a "new chat / clear conversation" action once persistence exists.

### 6.6 Screen checks (all re-run after the final build)

- **No model:** chat shows the typed message + Settings shortcut; engine section shows `Not loaded`
  and a working import action.
- **SAF import:** DocumentsUI picker → Downloads → the GGUF imports; progress state, then model row.
- **Ready:** chip and settings show `Ready`; model chip size ~1.12 GB; unload/reload cycle verified.
- **Streaming:** real incremental Arabic answer; stop mid-answer leaves the partial text with no error.
- **Bidi:** English answer renders LTR inside the RTL app with correct punctuation
  (`AssistantProse` / `UserNote` use `TextDirection.Content`).
- **Dark theme:** engine section and chip readable; no hard-coded colours at call sites.
- **English UI:** engine strings present in `values-en`; header chip ellipsises instead of crowding
  the wordmark (regression from the English longer label, fixed with a weighted header host).
- **Font scale 1.3:** theme/language/engine rows reflow without clipping.
- **Library:** catalogue now says `GGUF · ready to import` (was M1's stale `Step Zero`) and
  `Planned · voice` for voice assets; sizes match the tested file.

## 7. Limits (what M2 does not claim)

- **Emulator numbers only.** TTFT/tok/s/RSS are x86_64-on-desktop shape numbers. Real arm64 phone
  performance, thermals and battery are still unmeasured; the test phone is the next gate.
- **One model, one context, 2048 tokens.** No GPU/OpenCL offload, no speculative decoding, no
  per-model settings (temperature etc. are global in `app.json`). The shipped system prompt is global
  too and costs ~37 prompt tokens per turn; a small model can still drift in a long conversation.
- **Destructive actions are immediate.** Deleting a model has no confirmation/undo yet (M3).
- **No downloader / catalogue.** Import is SAF-only; the Library catalogue is informational.
- **No chat persistence.** Conversations are process-scoped (as M1); process death clears them.
- **Demo content is Arabic-only** for suggested prompts; the engine answer language follows the model.
- **JNI + engine version is pinned.** Updating llama.cpp means re-vendoring `third_party/llama.cpp`,
  rebuilding, and re-running §6 numbers.
- **Model licence is the user's.** The app imports whatever GGUF the user chooses; M3 store work must
  surface model licences.
- **Release is unsigned, no baseline profile** (M1 limits unchanged); Play submission later.
- **Single ABI pair.** No armeabi-v7a/x86 build; the phone target is arm64-v8a only by design.

## 8. Next (M3 queue, unchanged from doc 13 except what M2 closed)

Closed by M2: real engine on the seam, GGUF import/registry, load/unload/auto-load, real streaming,
stop semantics, honest engine status, typed reasons, release size/ABI hygiene.

Open, in the order the founding plan put them:

1. **Chat persistence + conversation store** (process death is still a hole) — **closed 19 Nov 2026: see [doc 15](15-sirrak-m3-persistence.md)**.
2. **Model catalogue + downloader** with licence display (needs hosting decision; offline flag stays).
3. **Voice**: Whisper STT + Piper TTS plugins into the same composition (Step Zero harness exists).
4. **Arabic RAG**: EmbeddingGemma retrieval plugin + document import (Step Zero harness exists).
5. **arm64 phone measurement**: H1 numbers, grounded-prompt prefill/TTFT, thermal soak — needs the phone.
6. Release signing, baseline profile, Play closed test (12 testers / 14 days).
7. GPU/NPU exploration behind the same `InferenceEngine` interface (never a fork of chat).

## 9. Working in the project

```powershell
# debug build + tests
gradle -p . :app:assembleDebug
gradle -p . :core:runtime:testDebugUnitTest :feature:chat:testDebugUnitTest :app:testDebugUnitTest

# minified release (unsigned)
gradle -p . :app:assembleRelease

# install and watch the engine log
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb logcat -s SirrakLlama
```

Change rules (same as M1, extended):

| Change | File |
|---|---|
| Engine provider, context/max tokens, sampling | `app/src/main/assets/config/app.json` (validated; invalid values fail loud) |
| Engine implementation / swap engine | `engine/llamacpp/` + `app/.../LlamaCppPlugin.kt`; ChatStore unchanged |
| Model storage, selection, import rules | `app/.../FileModelStore.kt` (behind `ModelStore`) |
| Engine strings / statuses | `app/src/main/res/values/strings.xml` + `values-en/strings.xml` |
| Prompt template / trimming / sampling internals | `engine/llamacpp/src/main/cpp/llama_jni.cpp` |
| Which ABIs ship | `engine/llamacpp/build.gradle.kts` + `app/build.gradle.kts` (`abiFilters`) |
| Library catalogue rows | `app/src/main/assets/content/models.json` |

## Appendix — evidence index

Screenshots in `assets/`:

| File | Shows |
|---|---|
| `sirrak-m2-chat-no-model.png` | chat with no model selected (typed message + shortcut) |
| `sirrak-m2-chat-failure-no-model.png` | failure copy for `no-model` |
| `sirrak-m2-settings-engine-top.png`, `…-mid.png` | engine section, top and scrolled |
| `sirrak-m2-saf-picker.png` | SAF document picker at import |
| `sirrak-m2-importing.png`, `sirrak-m2-import-after-10s.png` | import progress and completion |
| `sirrak-m2-imported-loading.png` | model row + Loading status |
| `sirrak-m2-relaunch-autoload.png` | persisted selection auto-loading after relaunch |
| `sirrak-m2-streaming-t2s.png`, `…-t10s.png`, `…-t20s.png`, `…-t4s.png` | real token streaming at successive times |
| `sirrak-m2-answer-ltr.png` | English answer LTR inside RTL UI (bidi fix) |
| `sirrak-m2-answer-complete.png` | completed real answer |
| `sirrak-m2-stopped-quiet.png` | stop mid-answer keeps partial text, no error |
| `sirrak-m2-engine-before-unload.png`, `…-unloaded.png`, `…-reloaded.png` | unload/reload cycle |
| `sirrak-m2-settings-dark.png`, `sirrak-m2-chat-dark.png` | dark theme |
| `sirrak-m2-settings-en.png`, `sirrak-m2-engine-en.png` | English UI + engine strings |
| `sirrak-m2-engine-fontscale-1_3.png` | font scale 1.3 reflow |
| `sirrak-m2-library-ar.png`, `sirrak-m2-library.png` | library catalogue with importable/planned states |
| `sirrak-m2-chat-ar-light.png` | final Arabic light state with ready chip |
| `sirrak-m2-stripped-launch.png` | stripped-build smoke launch |
| `sirrak-m2-final-e2e.png` | final build: real English answer (LTR) from the final installed APK |
| `sirrak-m2-final-state.png` | final installed app state: conversation + ready chip |
| `sirrak-m2-identity.png` | identity answer after the shipped system prompt ("I am Sirrak…") |
| `sirrak-m2-release-launch.png`, `sirrak-m2-release-answer.png` | the R8 release build running: launch + real answer |
| `sirrak-m2-invalid-import.png` | invalid GGUF rejected with the localised error |
| `sirrak-m2-two-models.png` | two imported models (1.5B selected, 0.5B listed) |
| `sirrak-m2-delete-selected.png` | selected model deleted: NotLoaded, "No model", empty-state copy |

Log excerpts (from `adb logcat -s SirrakLlama`):

```
I SirrakLlama: MODEL_LOADED ms=672 n_ctx=2048 n_threads=4
I SirrakLlama: STATS prompt_tokens=41 dropped_turns=0 prefill_ms=5929.9 ttft_ms=5931.9 generated=27 gen_ms=6200.4 tok_s=4.35 peak_rss_mb=1384.1 stopped=0
I SirrakLlama: MODEL_LOADED ms=2977 n_ctx=2048 n_threads=4
I SirrakLlama: STATS prompt_tokens=61 prefill_ms=8961.7 generated=23 tok_s=4.49 peak_rss_mb=1388.9 stopped=0
I SirrakLlama: STATS prompt_tokens=44 prefill_ms=6234.4 generated=72 tok_s=4.57 peak_rss_mb=1385.8 stopped=0
I SirrakLlama: STATS prompt_tokens=44 prefill_ms=6424.9 generated=12 stopped=1
I SirrakLlama: model unloaded
I SirrakLlama: MODEL_LOADED ms=470 n_ctx=2048 n_threads=4
I SirrakLlama: MODEL_LOADED ms=1148 n_ctx=2048 n_threads=4   (stripped debug smoke)
```

The last model load before the final-generation line is the auto-load on a cold start of the final
install. The first line is verbatim with the full field set (`dropped_turns`, `ttft_ms`, `gen_ms`);
the older STATS lines were transcribed from session notes before the emulator log buffer rolled, so
they show only the fields that were captured.

Honest caveats: the STATS numbers above are x86_64 emulator shape numbers, and turn 2's 72 tokens at
4.57 tok/s is the longest measured answer; do not quote them as phone performance.
