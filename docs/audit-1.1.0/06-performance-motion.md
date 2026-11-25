# Performance, jank and motion — Sirrak 1.1.0 (round 2)

**Member:** perf-motion-auditor · **Report:** `docs/audit-1.1.0/06-performance-motion.md` · **Date:** 25 Nov 2026
**Audited build:** app.sirrak 1.1.0 (versionCode 14), **debug**, lastUpdateTime 2026-11-25 01:08:36; emulator-5554, 720×1280 @ 320 dpi, API 36, x86_64, 6 GB RAM. All timings are from this build on this device.
**Evidence used:** `build/ux-audit/logs/logcat-full-1901.txt` (same install, launches 00:00–19:01 incl. the audit window; the app's own `SirrakLlama` STATS/MODEL_LOADED instrumentation, ActivityTaskManager `Displayed` and Choreographer/HWUI jank lines), `build/ux-audit/EVIDENCE.md`, `logs/*-nodes.txt`, `shots/*.png`, source at HEAD. Frame-level jank during streaming/scroll could **not** be measured — no `gfxinfo` framestats / `am start -W` from journey-runner were in the pack, and the emulator is theirs alone.

## Executive summary (5 lines)

- **Speed is the product's weak point, and it is silent:** measured cold start is **6.1–7.5 s to first frame** with a model installed, and the app's own log shows a **26.7 s first-token wait** (8.6 s warm) followed by **1.9–6.3 tokens/s** — while the only wait feedback is a 12 dp rotating mark.
- **The main thread pays for the whole app before the first pixel:** `Application.onCreate` builds every store and 11 plugins synchronously (measured **1.63–3.11 s** here) and the conversation index is rebuilt from every transcript on every launch.
- **The streaming path does O(screen + answer) work per token:** per-token scroll calls, per-token full-screen recomposition reads, a full Markdown re-parse, and an unconditional auto-scroll that overrides the reader.
- **The durable download queue is right in shape and wrong in cost:** the whole JSON snapshot is rewritten 4–8×/s, the non-lazy Library screen recomposes per tick, the row shows no speed or ETA, and the first-run download bypasses the queue.
- **Counts:** **P0 1 · P1 5 · P2 19 · P3 5 (30 findings)**; healthy paths and unverifiable items listed at the end.

---

## A. Startup and first paint

### PERF-01 — Cold start takes 6.1–7.5 s to first frame with a model installed
- **Severity:** P1   **Confidence:** high   **Effort:** M
- **Where:** app launch → first frame (`app/src/main/java/app/sirrak/SirrakApplication.kt:17-18`, `app/src/main/java/app/sirrak/MainActivity.kt:8-16`); measured `logcat-full-1901.txt:121437` and `:131632`
- **What happens now:** ActivityTaskManager reports `Displayed app.sirrak/.MainActivity ... +7s458ms` (18:54) and `+6s119ms` (19:01) on the current build with a model installed; earlier launches on the same install measured `+3s906ms, +4s033ms, +4s539ms, +6s234ms`. The first frame is itself a `Davey! duration=4175ms` frame, preceded by `Skipped 84 frames` and followed by `Skipped 241 frames`; the system splash shows only the app icon until then.
- **Why it hurts:** the user taps the icon and watches a still icon for six-plus seconds; on a mid-range phone this is the first thing the product says about itself, and it reads as a hung app.
- **Fix:** get the first frame out before disk work: build only the config/UI graph in `onCreate`, move stores, index rebuilds, media snapshot and model load behind the first composition (a `LaunchedEffect`/splash state that swaps content in when ready), ship a baseline profile for release.
- **Verify:** `am start -W` cold start 5× (report `TotalTime`); screen recording from tap to first usable screen.

### PERF-02 — The whole app graph is built synchronously on the main thread before the first frame
- **Severity:** P1   **Confidence:** high   **Effort:** L
- **Where:** `SirrakApplication.kt:17-18` → `app/src/main/java/app/sirrak/AppComposition.kt:67-296`; measured `logcat-full-1901.txt:121067-121138` and `:131284-131348`
- **What happens now:** from `Start proc` to `SirrakComposition: Installed plugins` is **1.63–3.11 s across 15 launches on this device** (current build 1.63/1.64/1.68/2.14/2.14/3.11 s). Inside: `loadConfig` asset JSON parse (`:68`), licence registry parse of `config/licences.json` (`:177-181`), `FileModelStore` construction = directory scan + GGUF header reads + selection restore (`:82`, `FileModelStore.kt:29-32`), `FileConversationLibrary` constructor = full transcript index rebuild (`:90-94`), `DownloadBridge.load()` queue read (`:203-212`), 12-plugin manifest install (`:259-294`), and each plugin's stores (media snapshot + orphan prune, `MediaLibrary.kt:120-130`). Choreographer immediately logs `Skipped 84 frames!`.
- **Why it hurts:** every cold start pays the combined cost of every feature before any pixel; the first frame then renders a fully composed UI on a thread already behind. Same mechanism as the historical startup ANR (PERF-06).
- **Fix:** split composition into a "first frame" graph (config, navigation, theme) and an "after first frame" graph (libraries, media, downloads, engine warm-up) installed from a coroutine after `setContent`; keep the splash/placeholder until the destination is ready.
- **Verify:** startup trace: `onCreate` < 300 ms; no `Skipped`/`Davey` in the first 2 s; over a 20-launch loop.

### PERF-03 — The conversation index is rebuilt from every transcript and rewritten on every launch
- **Severity:** P2   **Confidence:** high   **Effort:** S
- **Where:** `app/src/main/java/app/sirrak/FileConversationLibrary.kt:161-173` (`ensureLoadedLocked`), `:176-190` (`rebuildFromTranscriptsLocked`), `:293-299` (`persistIndexLocked`); called from the constructor `:70-72`
- **What happens now:** the comment at `:168` says an existing index is authoritative for the active id, but the code always calls `rebuildFromTranscriptsLocked()`: list every `conversations/*.json`, `readText()`, JSON-decode it, then rewrite the index file. Even with a valid index, launch does O(history) reads plus an index write.
- **Why it hurts:** startup cost grows linearly with the user's history — the most loyal users get the slowest launches; the index exists but saves nothing.
- **Fix:** treat the index as a cache: read it, validate a cheap consistency signal (file count + newest mtime or schema stamp), rebuild only when it disagrees; write only when changed.
- **Verify:** seed 50 and 200 conversations, measure `onCreate` time and transcript reads with StrictMode.

### PERF-04 — The 1.12 GB model is loaded on every cold start, before the user asks anything
- **Severity:** P2   **Confidence:** high   **Effort:** S
- **Where:** `app/src/main/java/app/sirrak/LlamaCppPlugin.kt:80-98` (`Dispatchers.Main.immediate` scope, `store.selected.collect { engine.load(...) }`), double-load branch `:93-95`; measured `logcat-full-1901.txt:122331`, `:121137`
- **What happens now:** the selected-model `StateFlow` replays the restored selection during plugin install, so `engine.load` runs at every process start: native library mapped at `+3.0 s`, `MODEL_LOADED ms=1273 n_ctx=2048 n_batch=512 n_threads=4`. When requested context differs from the trained one it loads a second time (`:93-95`). The user may be opening Settings or History when this happens.
- **Why it hurts:** 1.3 s of CPU/IO and up to 1.4 GB resident (PERF-26) burned before any interaction, competing with the first frames; on slower storage "loading" becomes the user's first impression of the model.
- **Fix:** load lazily on first send (or first Chat visit) with an honest «يُحمَّل النموذج عند أول رسالة» line; keep it warm for the process lifetime only; never double-load (compute the effective context once).
- **Verify:** cold start then open Settings: no `MODEL_LOADED` until the first send; first send shows a "loading model" phase.

### PERF-05 — Startup offers no progress beyond the system icon splash
- **Severity:** P2   **Confidence:** high   **Effort:** S
- **Where:** `MainActivity.kt:1-21` (no splash/skeleton), system splash logged `18:54:21.243` (`SplashScreenView ... size: 384`), first frame `18:54:28.623`
- **What happens now:** Android 12+ shows the launcher icon on the system splash for the entire 6–7.5 s (PERF-01); the app shows nothing of its own until the full first frame, and no screenshot exists between launch and first frame — because there is nothing to see.
- **Why it hurts:** a 7-second still icon gives no evidence the app is working; taps/back are swallowed, and first-run patience is spent before onboarding appears.
- **Fix:** keep the system splash until the first destination composes, then a one-line branded state («جارٍ تحضير المساعد…») if the graph is still settling; never show chat chrome before model state is known.
- **Verify:** screen recording of a cold start; count visible state changes before the first usable screen.

### PERF-06 — This device class already produced a startup ANR from the same main-thread path
- **Severity:** P2   **Confidence:** medium (previous build)   **Effort:** M
- **Where:** `logcat-full-1901.txt:10809-10824`
- **What happens now:** on 2026-11-25 00:00 the app was killed with `ANR in app.sirrak`, `Reason: Process ... failed to complete startup`, I/O pressure `some avg10=70.52 full avg10=69.10`, memory pressure `full avg10=22.83`; `Killing ... (adj 0): bg anr`. That run predates the 01:08 install, so it is **not** the audited build — but the blocking-`onCreate` structure it died in is unchanged (PERF-02).
- **Why it hurts:** on a cold, I/O-contended phone the "app won't start" failure is one slow disk away; PERF-01/02's fix list is also the ANR fix.
- **Fix:** same as PERF-01/02; run StrictMode in debug so a new blocking disk read shows up before shipping.
- **Verify:** 20 cold launches under I/O load; no `ANR in app.sirrak` in logcat.

## B. The first answer and streaming

### PERF-07 — The first answer takes 8.6–26.7 s to begin and shows only a rotating mark
- **Severity:** P0   **Confidence:** high   **Effort:** M
- **Where:** chat wait state `core/design/src/main/java/app/sirrak/core/design/Chat.kt:168-203` and `:334-346` (`InkingMark`); measured `logcat-full-1901.txt:124605, 125305, 28795, 28938`; UI `build/ux-audit/shots/11-chat-existing-top-ar.png` + `logs/11-chat-existing-top-nodes.txt:12-13`
- **What happens now:** the app's own instrumentation records `prompt_tokens=87 ... prefill_ms=26719.2 ttft_ms=26723.8 generated=73 gen_ms=37781.9 tok_s=1.93` (18:57); a second run on the same install records `prefill_ms=8644.0 ttft_ms=8646.0 ... tok_s=6.35` (01:03). During that whole prefill the user sees an empty assistant bubble with the 12 dp inking mark: no phase label, no elapsed time, no warning that the **first answer after launch is ~3× slower** because the 1.12 GB GGUF pages are cold (warm prefill 8.6 s vs 26.7 s cold). The live UI reports «≈ 0.7 رمز/ث» (`11-chat-existing-top-nodes.txt:13`) because its number averages the wait.
- **Why it hurts:** the core loop (ask → answer) silently takes up to half a minute before the first character, at sub-2 token/s after; the user cannot tell "working" from "hung", so they stop or leave. PRODUCT promises honesty about speed; this is the least honest moment in the app.
- **Fix:** a three-phase status line in the assistant bubble — «أقرأ رسالتك» (prefill) → «أكتب الجواب» (generation) — with an elapsed-seconds counter after 2 s; after 10 s add «أول جواب بعد تشغيل التطبيق يكون أبطأ على هذا الجهاز»; show the measured per-device rate once tokens exist (PERF-08).
- **Verify:** cold app, send a prompt: the bubble names the phase and counts seconds; TTFT is visible on screen.

### PERF-08 — The app knows the speed only after the stream ends, and hides it while the user waits
- **Severity:** P1   **Confidence:** high   **Effort:** S
- **Where:** `feature/chat/src/main/java/app/sirrak/feature/chat/ChatMeter.kt:54-60` (`if (!streaming && speed != null && speed > 0f)`), `ChatStore.kt:1018-1030` (`recordSpeed` on `Completed`), `:1064-1068`; observed in `shots/11-chat-existing-top-ar.png`
- **What happens now:** while streaming the meter shows only the prompt budget («128 / 1536 رمز تقديري»); «≈ 0.7 رمز/ث» is appended only after completion. The user learns how slow the device is after already waiting, never during the wait when it would change their decision.
- **Why it hurts:** no basis to decide "keep waiting or stop"; silence makes a slow device look stuck, and the post-hoc number reads as an excuse rather than feedback.
- **Fix:** live rate in the meter once ~10 tokens exist (rolling 5 s over `EngineChunk` timestamps, «≈ 1.8 رمز/ث»); keep the final average; phrase the range at first token (PERF-07).
- **Verify:** watch the meter during a live stream: rate changes before the answer completes.

### PERF-09 — Auto-scroll yanks the reader to the bottom on every token
- **Severity:** P1   **Confidence:** high   **Effort:** S–M
- **Where:** `feature/chat/src/main/java/app/sirrak/feature/chat/ChatScreen.kt:171-188`; Lead cross-check LC-03 covers the same defect — this entry adds layout cost and the long-transcript fix
- **What happens now:** `streamingLength` changes each chunk, so `LaunchedEffect(store.items.size, streamingLength)` restarts per token and unconditionally runs `scrollToItem(lastIndex)`, then `scrollBy(10_000f)`, then `withFrameNanos {}`. No "user scrolled up" check exists.
- **Why it hurts:** a user reading the start of a 512-token answer, a table, or the previous turn is pulled to the bottom on the next token (~every 0.5 s at 2 tok/s) — reading during streaming is impossible; each yank is also a forced layout on a long list.
- **Fix:** follow only while the list was at the end when the user last touched it (`lastVisibleItemIndex == lastIndex` before the effect); otherwise keep the position and show a «العودة إلى الأحدث» chip; drop the extra `scrollBy(10_000f)`.
- **Verify:** send a long prompt, scroll up immediately; viewport stays put and the chip appears.

### PERF-10 — One token recomposes the whole chat screen and every visible answer row
- **Severity:** P2   **Confidence:** high (mechanism) / medium (frame cost)   **Effort:** M
- **Where:** `ChatScreen.kt:171-172` (`streamingLength` read at screen scope), `:300` (`isNewest = item.id == store.items.lastOrNull()?.id` inside every assistant item), `:263` (`items(store.items, key = { it.id })`), `:422-460` (composer lambdas); `grep derivedStateOf` = 0 hits in `app/` and `core/design/`
- **What happens now:** each token writes the last item (`ChatStore.kt:1017`), changing both `items` and `streamingLength`. The screen reads `streamingLength` and every assistant row reads `items.lastOrNull()`, so the per-token recomposition scope covers the screen header instead of one bubble; pending work scales with visible message count.
- **Why it hurts:** work per token grows with transcript length — the brief's 50-message conversation is the worst case — and it competes with scroll. No gfxinfo was available to quantify the drop; mechanism is certain, magnitude unverified.
- **Fix:** hoist `isStreaming`/`isNewest` once at screen scope with `derivedStateOf` over the id, pass stable booleans into rows, `remember` the composer callbacks; let the bubble read its own item state so only it invalidates.
- **Verify:** Layout Inspector recomposition counts during a stream (target: one bubble marked, not the header).

### PERF-11 — The whole Markdown answer is re-parsed on every token
- **Severity:** P2   **Confidence:** high (mechanism) / medium (impact)   **Effort:** M
- **Where:** `core/design/src/main/java/app/sirrak/core/design/Markdown.kt:514` (`remember(markdown) { MarkdownParser.parse(markdown) }`), `:522-539` (`blocks.forEachIndexed` with no `key()`), `:550, :565` (`inlineMarkdown` built in composition without `remember`); `grep 'key('` = 0 hits in the file
- **What happens now:** `markdown` is the growing answer string, so `remember(markdown)` misses every token: the whole answer is re-parsed and every block's `AnnotatedString` rebuilt on the main thread. Cost is O(answer length) per token, O(n²) per answer.
- **Why it hurts:** long answers are the ones users wait for and read, and they are the most expensive to render; per-token cost rises as generation proceeds, so the last third can jank while the first third is smooth.
- **Fix:** parse incrementally — parse only the trailing incomplete block per token; key blocks by index+hash; `remember` inline parses per block text (or debounce the Markdown parse to ~10 Hz while raw text stays immediate).
- **Verify:** microbenchmark `MarkdownParser.parse` vs length; frame trace on a 512-token answer.

### PERF-12 — Send and answer-completion write the transcript and index synchronously on the main thread
- **Severity:** P2   **Confidence:** high   **Effort:** M
- **Where:** `ChatStore.kt:456` (`persist()` right after adding the user turn), `:850-875` (whole-transcript JSON + index write), `:1018-1030` (`persist()` on `Completed`), `app/src/main/java/app/sirrak/ChatPlugin.kt:63-74` (store `appScope` is `Main.immediate`)
- **What happens now:** `send()` serialises the complete conversation and rewrites it plus the index before inference starts; completion does it again. Both run in the UI coroutine, so the frame that should show the user bubble and start streaming is also doing disk I/O; cost scales with turns (up to `MAX_TURNS`).
- **Why it hurts:** tap-to-first-token grows with conversation size, and completion incurs a write at the exact moment the UI switches to the done state.
- **Fix:** persist on `Dispatchers.IO` (fire-and-forget with a pending flag), coalesce writes during a stream, rewrite the index only when title/active/preview changed.
- **Verify:** StrictMode disk-write-on-main; tap→bubble and last-token→done at 1 and 50 messages.

### PERF-13 — Answer completion is a hard cut: controls and speed appear in one frame
- **Severity:** P3   **Confidence:** high   **Effort:** S
- **Where:** `core/design/src/main/java/app/sirrak/core/design/Chat.kt:211-258` (`AnswerActions`), `ChatScreen.kt:332-362`, `Chat.kt:177-180`; `grep AnimatedVisibility` = 0 hits in `app/`
- **What happens now:** the rotating mark disappears, the actions row (copy/regenerate/…) is inserted and the speed text appears in a single frame; the bubble height jumps by the actions row.
- **Why it hurts:** at 2 tokens/s the stream is the only activity for a minute; ending it with a snap and a layout jump reads as a glitch, and the new controls are easy to miss.
- **Fix:** `AnimatedVisibility` with `fadeIn() + expandVertically(tween(motion.quick))` for the actions row and speed, crossfade the mark out; keep layout stable while waiting.
- **Verify:** record an answer completing; no abrupt jump, controls visibly arrive.

### PERF-14 — The only live animation runs at full frame rate for the whole generation
- **Severity:** P2   **Confidence:** medium-high   **Effort:** S
- **Where:** `Chat.kt:334-346` (`rememberInfiniteTransition` + `animateFloat(0f→360f, infiniteRepeatable(tween(2600), Restart))`), shown at `:177-180` while streaming; `grep MotionDurationScale` = 0 hits in `app/` and `core/design/`
- **What happens now:** a 12 dp mark requests a new frame every vsync for the entire generation — 8–40 s per answer in the measured runs, minutes per session — on a CPU already 100% busy with inference. There is no reduced-motion or animator-scale path.
- **Why it hurts:** needless battery/heat on the device that can least afford it (the slow-device problem is partly thermal), and the animation carries no information where the product most needs information.
- **Fix:** replace with the phase text/elapsed counter (PERF-07) plus a slow ≤2 Hz pulse or static state; stop when not resumed; honour `LocalMotionDurationScale` = 0.
- **Verify:** frame counts/GPU profile while streaming; animator scale 0 run shows no animation.

## C. Library, downloads and queue

### PERF-15 — Downloads show no speed and no ETA
- **Severity:** P1   **Confidence:** high   **Effort:** S
- **Where:** `core/models/src/main/java/app/sirrak/core/models/Downloads.kt:319` (`bytesPerSecond` is computed), `core/models/src/main/java/app/sirrak/core/models/DownloadQueue.kt:35-54` (`DownloadTask` has no speed/remaining field), `app/src/main/java/app/sirrak/LibraryScreen.kt:690-714` (percent + bytes only); first-run copy at `feature/models/src/main/java/app/sirrak/feature/models/OnboardingScreen.kt:288-300`
- **What happens now:** the row reads «42% · 480 MB / 1.12 GB» and nothing else. The downloader measures `bytesPerSecond`, but the bridge publishes only the persisted task, so the rate never reaches the UI; no remaining-time estimate exists anywhere.
- **Why it hurts:** these are 1.1–4 GB downloads on metered data (PRODUCT target users); the user cannot tell 5 minutes from 3 hours, cannot judge a pause, and a slow-but-alive download is indistinguishable from a stalled one.
- **Fix:** keep non-persisted UI state beside the queue (`taskId -> (bytesPerSecond, remainingMs)` refreshed ≤1 Hz) and render «≈ 2.4 MB/ث · بقي 8 د» with an em dash until sampled; reuse in onboarding.
- **Verify:** start a download; the row shows a rate and a countdown updating about once a second.

### PERF-16 — The durable queue rewrites its entire JSON snapshot on every progress tick
- **Severity:** P2   **Confidence:** high   **Effort:** M
- **Where:** `DownloadQueue.kt:272-282` (`updateProgress`), `:394-396` (`persist`), `app/src/main/java/app/sirrak/downloads/QueueFileStore.kt:35-63` (temp write + rename), cadence `Downloads.kt:297-337` (250 ms primary, 120 ms fallback)
- **What happens now:** every tick JSON-encodes the whole snapshot, writes a temp file, renames it, and does not fsync (`QueueFileStore.kt:22-23`). That is **4–8 whole-file writes/s** for the entire transfer, O(number of tasks) each, even when only one `downloadedBytes` changed.
- **Why it hurts:** sustained I/O on the user's flash for hours-long downloads, competing with UI and inference (the startup ANR log shows io pressure 70.5 at another moment); `mutate()` also republishes the task list per tick, driving PERF-17.
- **Fix:** persist progress at most every 2–5 s plus immediately on phase change/pause/cancel; keep in-memory progress for the bar. If per-tick recovery is required, persist a tiny `id:downloadedBytes` sidecar.
- **Verify:** count `write()` calls per download second (StrictMode/strace); queue survives `kill -9` after a pause.

### PERF-17 — One progress tick recomposes the whole non-lazy Library screen
- **Severity:** P2   **Confidence:** high   **Effort:** M
- **Where:** `LibraryScreen.kt:105` (`tasks by bridge.tasks().collectAsStateWithLifecycle()`), `:109-110` (`queueKey` + `remember(queueKey) { probe.capacity() }`), `:211-217` (single `Column(verticalScroll)` body), `:388-411` (`verdictFor` per catalogue entry at composition), `app/src/main/java/app/sirrak/downloads/DownloadBridge.kt:336-342` (`mutableTasks.value = queue.tasks()` per mutation)
- **What happens now:** each tick publishes a brand-new list, the screen-level collector invalidates the whole Library body (state of all three segments is read at the top), and every catalogue entry's fit verdict is recomputed even though one row's bytes changed. 4–8×/s while the user is on the downloads screen.
- **Why it hurts:** the screen users watch during a long download is the busiest; it does needless work every tick on a device already doing network + SHA-256 + inference.
- **Fix:** collect per task (`bridge.taskFlow(id)`) or split list state with `derivedStateOf` keyed by id+phase; `remember(catalogue)` the verdicts; keep screen-level state out of the ticking flow.
- **Verify:** Layout Inspector recomposition counts while a download runs (target: one row's body).

### PERF-18 — A long download queue is composed and measured in a plain Column
- **Severity:** P2   **Confidence:** high   **Effort:** M
- **Where:** `LibraryScreen.kt:211-217` (`Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()))`), `:449-460` (`pending.forEachIndexed { ... DownloadTaskRow ... }`), ordering `DownloadQueue.kt:216-218`
- **What happens now:** every queued, paused and active task is composed and measured even offscreen, and every list update re-lays out all of them. The brief's 300-row scenario is a plain `Column` of 300 composed rows; the model supports arbitrarily many tasks.
- **Why it hurts:** the more the user queues, the slower the queue screen — backwards from what a queue UI must do; combined with PERF-17 it multiplies per tick.
- **Fix:** `LazyColumn` with `key = { it.id }` and per-row state collection; the rest of the screen can become lazy sections.
- **Verify:** seed 100 rows; scroll and measure frames/JankStats.

### PERF-19 — The first-run download bypasses the durable queue and shows no speed either
- **Severity:** P2   **Confidence:** high   **Effort:** M
- **Where:** `feature/models/src/main/java/app/sirrak/feature/models/OnboardingScreen.kt:68` (`container.service(ModelDownloader::class.java)`), `:79` (progress collect), `:93-123` (start/import/`modelStore.select`), `:288-300` (percent-only UI); versus the Library path through `DownloadBridge`/`DownloadQueue`. Lead cross-check LC-10 covers the state gap; this entry adds the cost/feedback consequence.
- **What happens now:** the longest first-run wait is driven by the in-memory downloader: no persisted queue row (no pause/resume across process death, no entry in Library → Downloads), no speed/ETA, progress only in compose state. If the process dies mid-download the `.part` file exists but is not queued.
- **Why it hurts:** the first download is the least durable and least informative path — exactly where the queue was needed; if it dies the user loses the only visible record, and a 1–4 GB wait has no ETA.
- **Fix:** enqueue through `DownloadBridge` from onboarding and render the same task row (bar + percent + speed/ETA + pause); onboarding can stay simple.
- **Verify:** start the onboarding download, kill the app, relaunch: a queue row exists and is resumable with a rate.

### PERF-20 — Queue phase changes happen with no motion at all
- **Severity:** P3   **Confidence:** high   **Effort:** S
- **Where:** `LibraryScreen.kt:681-730` (`DownloadTaskBody` `when (phase)` branches), `grep AnimatedVisibility|animateContentSize|animateItem` = 0 hits in `app/`
- **What happens now:** QUEUED→ACTIVE→VERIFYING→INSTALLED replace the row's text and buttons in a single frame; row height jumps (waiting text → bar + bytes + buttons); status pill changes tone instantly.
- **Why it hurts:** phase changes are the queue's entire value proposition; hard cuts make a working download look like flicker, and a short VERIFYING phase is missed entirely.
- **Fix:** `Modifier.animateContentSize()` on the row, crossfade the status pill, animate progress transitions.
- **Verify:** watch one download through all phases; no layout snap, VERIFYING is perceivable.

### PERF-21 — Model loading has a label but no progress
- **Severity:** P3   **Confidence:** high   **Effort:** S
- **Where:** `app/src/main/java/app/sirrak/Panels.kt:70-79` (chip label from `EngineStatus.Loading`), `:100-107` (`engineStatusLabel`), `:197-205` (loading sheet: title + generic body); measured `MODEL_LOADED ms=1273` (`logcat-full-1901.txt:122331`)
- **What happens now:** «جارٍ التحميل» is the whole feedback; no percentage, no elapsed time, no bytes. On this device load took 1.27 s (mmap), so impact is small today, but cold page cache/slower storage can stretch it and the only surface is one word.
- **Why it hurts:** the chip is the model-state surface; a bare label gives no expectation, and the same surface later reports "ready" with equal confidence.
- **Fix:** add a one-line passport state («تحميل النموذج في الذاكرة… 2 ث») and a determinate bar only if the engine exposes progress; otherwise show elapsed time.
- **Verify:** unload then load from the chip; the passport shows elapsed time.

## D. Motion and feedback quality

### PERF-22 — The motion system is two effects; most of its tokens are dead code
- **Severity:** P2   **Confidence:** high   **Effort:** M
- **Where:** `core/design/src/main/java/app/sirrak/core/design/Tokens.kt:24-29` (`quick=140`, `standard=240`, `slow=360`, `ease=FastOutSlowInEasing`), `core/design/src/main/java/app/sirrak/core/design/Shell.kt:116-126` (only consumer of `standard`/`quick`); `slow` and `ease` unused; `grep AnimatedVisibility|animateContentSize|animateItem` = 0 hits in `app/`
- **What happens now:** the product has exactly two animations: the 240 ms fade+slide page switch and the inking mark. New messages cut, queue phases cut (PERF-20), banners/errors cut, answer completion cuts (PERF-13), dialogs use M3 defaults (inconsistent with the tokens), and most token values are unread.
- **Why it hurts:** the UI feels static and abrupt exactly where motion would communicate state for free, and with no role mapping it cannot move coherently later.
- **Fix:** define three roles (feedback-enter = `quick`, content-change = `standard`, page = `slow`), consume `ease` in the page spec, apply to the listed state changes.
- **Verify:** design QA sweep of the 8 state changes; `grep` shows `slow`/`ease` consumed.

### PERF-23 — Tab switches throw away destination state and rebuild it inside the transition
- **Severity:** P2   **Confidence:** high (state) / medium (frame)   **Effort:** M
- **Where:** `Shell.kt:112-127` (single `AnimatedContent`, no `SaveableStateHolder`), `LibraryScreen.kt:113` (segment `rememberSaveable` inside destination), `grep SaveableStateHolder` = 0 hits
- **What happens now:** when a destination leaves composition its `rememberSaveable` entries are dropped, so returning to Library resets the segment to Models and list scroll; returning to Chat loses transcript scroll and composes the whole 50-message `LazyColumn` during the 240 ms transition.
- **Why it hurts:** users hop Library↔Chat while a download runs and Settings↔Chat to copy something; every return lands at the top of the wrong segment, and the heaviest composition happens mid-animation (most likely visible hitch; unverified without frames).
- **Fix:** wrap destination content in `rememberSaveableStateHolder().SaveableStateProvider(current.pageId)`; consider composing before animating or fade-only for heavy destinations.
- **Verify:** Chat→Library→Chat and back: segment and scroll retained; frame trace across switches.

### PERF-24 — No reduced-motion or animator-scale handling
- **Severity:** P2   **Confidence:** medium   **Effort:** S
- **Where:** `Shell.kt:116-126`, `Chat.kt:334-346`; `grep MotionDurationScale|LocalMotionDurationScale` = 0 hits in `app/` and `core/design/`
- **What happens now:** with the platform animator scale at 0 ("remove animations") the page transition and the infinite inking mark still run; infinite transitions are not governed by the finite-animation scale, so the app never stops moving for users who asked it to.
- **Why it hurts:** motion-sensitive users cannot turn the app's motion off (comfort + battery), and it ignores a system preference the rest of Android honours.
- **Fix:** read `LocalMotionDurationScale.current.scaleFactor` and skip both effects at 0 (cap the mark's rate); test with animator scale 0 and "remove animations".
- **Verify:** set `settings put global animator_duration_scale 0`, navigate and stream: no page slide, no rotation, functionality intact.

### PERF-25 — Detail pages cut in with no transition while tabs animate
- **Severity:** P3   **Confidence:** medium   **Effort:** S
- **Where:** `Shell.kt:112-114` (`if (page != null && pageContent != null) { Box { pageContent(page) } }` — no `AnimatedContent` on this branch) vs `Shell.kt:116-126` (tab branch animates)
- **What happens now:** tab switches fade+slide over 240 ms, but pushed pages (model detail etc.) and their back navigation replace content in one frame with no animation; the same navigation looks different depending on destination kind.
- **Why it hurts:** inconsistent depth cues — the user cannot tell whether they moved within a tab or to a new page, and back feels like a reload rather than a return (state also lost, PERF-23).
- **Fix:** animate the page branch too (shared-axis slide/fade, direction-aware) or remove the tab animation and rely on one consistent mechanism.
- **Verify:** model passport → model page → back; both directions animate consistently with tab switches.

## E. Memory, honesty and the rest of the app

### PERF-26 — Peak memory measured at 1.43 GB during an answer, with no memory messaging
- **Severity:** P2   **Confidence:** medium (one sample)   **Effort:** M
- **Where:** `logcat-full-1901.txt:125305` (`peak_rss_mb=1434.0`) and `:28938` (`peak_rss_mb=801.0`); installed model 1,117,320,736 B per `EVIDENCE.md`; passport shows model facts only, `Panels.kt:126-160`
- **What happens now:** after model load plus one 73-token answer the process peaked at 1.43 GB (an earlier run: 801 MB); the number is shown nowhere, and nothing warns that a 1.1 GB model on a 4 GB phone is close to the comfort zone. A boot-time GC line shows 49.6 ms total / 5.0 ms pauses — small, but the kind of pause that lands on a streaming frame.
- **Why it hurts:** PRODUCT promises honesty about memory; on stated minimum devices LMK can kill the app mid-answer (conversation survives, stream does not), and the user cannot see that the model is heavy for the phone.
- **Fix:** show last measured peak RSS / estimated footprint in the passport and a one-time note when model size × ~1.4 approaches device RAM; free the KV cache and unload after long idle.
- **Verify:** `dumpsys meminfo app.sirrak` before/after an answer; repeat on a 4 GB configuration.

### PERF-27 — Nothing measures or explains the device's speed class
- **Severity:** P3   **Confidence:** high   **Effort:** S
- **Where:** `Panels.kt:126-160` (passport: status, disk, quant, arch, trained context, template — no measured rate), `ChatMeter.kt:26-70` (post-hoc speed only)
- **What happens now:** the only performance number the app ever shows is the last answer's tok/s in the chat meter; the passport — the screen where a user asks "can this phone run this model?" — never shows a measured rate or a verdict like «بطيء على هذا الجهاز».
- **Why it hurts:** the honest-about-limits stance is contradicted where it matters; users pick the 1.5B model for its description and then conclude the app is broken at 2 tok/s.
- **Fix:** store the last 3 runs' tok/s locally and show them in the passport and install-complete row with a sentence for the range; never invent a number before measuring.
- **Verify:** complete two answers; the passport reports the measured rate and range.

### PERF-28 — Opening a conversation's settings reads a transcript file inside composition
- **Severity:** P2   **Confidence:** medium-high   **Effort:** S
- **Where:** `feature/chat/src/main/java/app/sirrak/feature/chat/ChatHistory.kt:394` (`val existing = remember(summary.id) { store.loadConversation(summary.id) }`), `ChatStore.kt:619` → `FileConversationLibrary.find:89-92` (synchronized `readConversationLocked` = file read + JSON decode); export path `ChatHistory.kt:88`
- **What happens now:** the settings dialog body synchronously reads and parses the whole conversation while composing, so the dialog cannot appear until the disk read finishes; `loadConversation` is also used by export on the UI thread.
- **Why it hurts:** dialogs that should be instant feel sticky on long conversations (cost scales with turns) and gets worse as history grows.
- **Fix:** `produceState` loading off the main thread (or an LRU of parsed conversations in `ChatStore`), show the dialog shell immediately; assemble exports on `Dispatchers.IO`.
- **Verify:** open conversation settings on the largest conversation; dialog appears without a pause (StrictMode/systrace).

### PERF-29 — Media index snapshot and orphan pruning run on main during startup
- **Severity:** P2   **Confidence:** high   **Effort:** M
- **Where:** `app/src/main/java/app/sirrak/media/MediaLibrary.kt:120-130` (constructor: `store.snapshot()`, expiry deletes, `store.write`), `app/src/main/java/app/sirrak/media/MediaStore.kt:66-82` (index read), `:175-187` (`pruneOrphanFiles` lists and deletes), invoked from `app/src/main/java/app/sirrak/LibraryPlugin.kt:60-70` during plugin install
- **What happens now:** at every process start the media library lists `media/files`, reads its index, deletes orphan files and may rewrite the index — synchronously, before the first frame, inside the already-too-long graph build (PERF-02). Cost grows with saved media (docs/32 notes documents are stored twice).
- **Why it hurts:** adds startup I/O and destructive filesystem work to the critical path; a user with many images pays more at launch, and deletion on the main thread is risky if the process is killed mid-prune.
- **Fix:** initialise media lazily after the first frame and prune on `Dispatchers.IO`; the Library media segment can show counts when they arrive.
- **Verify:** seed 200 media files; measure `onCreate` and confirm pruning does not block the first frame (StrictMode).

### PERF-30 — History search re-reads and parses every transcript on every keystroke
- **Severity:** P2   **Confidence:** high   **Effort:** M
- **Where:** `ChatHistory.kt:79-81` (`remember(revision, query, ...) { store.searchConversations(query) }` — called during composition), `ChatStore.kt:615-616`, `FileConversationLibrary.search:79-87` (`readConversationLocked` per entry); no debounce
- **What happens now:** every character typed into the history search triggers a synchronous pass that reads and JSON-parses each `conversations/*.json` on the main thread; with 50 conversations that is 50 file reads + decodes per keystroke, inside composition.
- **Why it hurts:** search-as-you-type drops keys and jitters once history is real — the feature degrades exactly for the users who need it — and it burns I/O per character.
- **Fix:** debounce ~250 ms, run the search in a `viewModelScope`/`Dispatchers.Default` with a `StateFlow` of results, and index message text alongside summaries (search the index, not the transcripts).
- **Verify:** seed 50 conversations; type a 10-character query and measure keystroke latency / dropped frames.

---

## Round-1 claim checks (docs/32) relevant to this area

- **V3 / docs/32 P2 — "durable download queue": half true.** The Library path is genuinely durable and honest — JSON snapshot, temp+rename write (`QueueFileStore.kt:35-63`), restart repairs ACTIVE→PAUSED (`DownloadBridge.kt:108-117`), determinate bar with no fake 100% (`DownloadQueue.kt:56-62`). But it is not cheap (full-file write 4–8×/s, PERF-16), not informative (no speed/ETA, PERF-15), not list-efficient (PERF-17/18), and the **first-run download does not use it at all** (PERF-19, LC-10).
- **V7 / docs/32 P1 — streaming result experience: real but dishonest about time.** The stream exists and is instrumented (`PREFILL`/`STATS` measured; token meter shows «128 / 1536 رمز تقديري»), but the wait is silent for up to 26.7 s (PERF-07), the rate is hidden while it matters (PERF-08), auto-scroll fights the reader (PERF-09), and completion snaps (PERF-13). Round 1 fixed the honesty of *state*, not of *time*.
- **docs/32 P4 — Settings doors with live summaries:** not a perf defect at rest, but summaries are computed at render time and recomputed on every return because destination state is not retained (PERF-23). No separate finding.

## What I verified as healthy (so it is not re-litigated)

- **Inference is off the main thread.** The engine streams `EngineChunk` on its own scope; measured streaming works and token generation does not run on the UI thread. The findings above target what the *UI* does per chunk.
- **No fake progress.** `DownloadTask.fraction` is 0 when the total is unknown (`DownloadQueue.kt:56-62`); the bar never pretends 100% before INSTALLED, and the queue refuses unknown schema versions rather than resuming at a guessed offset.
- **Queue writes are atomic-ish.** Temp file + rename (`QueueFileStore.kt:35-63`); the missing fsync is documented honestly (`:22-23`) — the defect is cadence (PERF-16), not correctness at this level.
- **Media thumbnails are decoded off the main thread** with `produceState` + `withContext(Dispatchers.IO)` and bounded to 96 px (`MediaLibrarySection.kt:534-568, 715-724`) — the pattern the chat surfaces should copy.
- **Lazy list identity is correct in chat:** `items(store.items, key = { it.id })` (`ChatScreen.kt:263`) means the problem is what reads the list (PERF-10), not unstable keys.
- **Model load itself is fast when it happens:** `MODEL_LOADED ms=1273` for 1.12 GB (`logcat-full-1901.txt:122331`) — eager loading is a policy problem (PERF-04), not a slow-loader problem.

## Unverified / cannot measure from here

- **Frame-level jank during streaming and scrolling.** No `gfxinfo framestats`, `am start -W` or JankStats capture was in `build/ux-audit/` while writing (logs had `Displayed`/`Davey`/`Skipped` only), and the emulator is journey-runner's. PERF-10/11/17/18/23 state mechanism with high confidence and magnitude as unverified.
- **Cold-start attribution.** The +6.1/+7.5 s launches are those with a model installed and history present vs +3.9–4.5 s earlier on the same install; model presence is *correlated*, not controlled (no clean A/B).
- **Release-build numbers.** Debug build only: Compose debug, no baseline-profile benefit, JIT-heavy. Absolute seconds are a ceiling; the structural findings (main-thread work before first frame, per-token work) are build-independent.
- **Real-flash queue write wear and I/O contention.** Emulator I/O differs from a phone's eMMC/UFS; the 4–8 writes/s count is exact, the device-level harm is inferred from the ANR's io-pressure readings.
- **The 18:54 model-load timing.** `llama_backend_init` logged 45 s after first frame in that run (vs 1.5 s in the 00:58 run); the trigger could not be reconstructed from the log, so no finding depends on it.
- **Download speed/ETA absence** is proven from types and UI (`DownloadTask` has no field; row renders percent+bytes only); what a user does with the number is inference.

---

*Method: read-only. No app source, assets or gradle were modified; no adb commands were run (emulator reserved for journey-runner). Every measured number cites `build/ux-audit/logs/logcat-full-1901.txt` by line or timestamp; every code claim cites `path:line` at HEAD.*
