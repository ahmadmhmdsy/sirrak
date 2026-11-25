# State & error integrity — Sirrak 1.1.0 (round 2)

**Owner:** state-integrity-auditor · **Report:** `docs/audit-1.1.0/05-states-errors.md`
**Question answered:** for every state the user can meet — empty, loading, failed, offline, denied, queued,
paused, cancelled, interrupted, corrupt, restored — does the app say *what happened, why, and what to do next*,
is it distinguishable from its neighbours, and is it persisted honestly? Code references are from the shipped
1.1.0 tree (`versionCode 14`); device evidence is `build/ux-audit/` (journey-runner, emulator-5554).

## Executive summary (5 lines)

1. **Biggest strengths:** failure states are typed, not stringly: stable reason codes are centrally mapped for
   downloads (`DownloadReasons`), HF (`HfCodes`), fit (`FitReasons`), media (`mediaReasonLabel`); the queue and
   conversation library write atomically and survive corruption instead of crashing; "not verified" is stated
   as such in HF and the model passport. The honesty infrastructure exists.
2. **Biggest risk — the first run can dead-end on the target user's network:** onboarding starts the model
   download with no metered-consent path and then blames "the connection" for the guaranteed
   `NOT_UNMETERED` failure (DL-02); the retry loops forever on mobile data.
3. **Biggest risk — states that actively lie about data:** a failed conversation save is discarded
   (`runCatching` result ignored) and never surfaced (CH-01); a corrupt download store renders as
   "no downloads in progress" (DL-06); a gated-repo download silently drops the token the user just entered
   (HF-01). Each turns a recoverable failure into apparent data loss.
4. **The same state is described differently on different surfaces:** engine *loading* and *load-failed* are
   both reported to the chat as "no model — import a GGUF" (ST-01); the Library asks for mobile-data consent
   with no network and skips it on metered Wi-Fi (DL-01); cancelled partial files stay on disk while the UI
   says the resume point is wiped (DL-05).
5. **Counts:** 35 findings — **P0: 4 · P1: 10 · P2: 19 · P3: 2**. Every P0/P1 is high confidence and cites a
   code path; screenshot ids refer to journey-runner's `build/ux-audit/` pack.

---

## 1. Engine / model-load states (ST)

### ST-01 — "Loading" and "Load failed" are both reported to the chat as "no model"
- **Severity:** P0 **Confidence:** high **Effort:** S
- **Where:** chat composer → failed turn (`feature/chat/.../ChatStore.kt:459-461`, copy
  `feature/chat/src/main/res/values/strings.xml:18-19`, action `ChatScreen.kt:352-361`); engine chip state
  `app/.../LlamaCppPlugin.kt:81-98` (auto-load at startup), `app/.../Panels.kt:70-79`.
- **What happens now:** `send()` tests only `engine.status !is Ready` and emits a `NO_MODEL` failed turn, so a
  model that is still loading and a model that failed to load both produce «لم يُحمَّل أي نموذج بعد. استورد ملف
  GGUF من الإعدادات ثم حاول مجددًا.» with "Open settings". Meanwhile the chip in the same header says
  «تعذّر التحميل» (load failed) — two surfaces, two different stories, one of them false.
- **Why it hurts:** the first message after launch can be sent while the engine is auto-loading (that is the
  default state right after the app opens), and the user is told to import a model they already have, in a
  place where model import no longer lives. They now doubt both surfaces.
- **Fix:** branch on the real status: Loading → an inline "model is loading…" turn/disabled send with progress;
  Failed → the load-failure body from ST-02; only Unloaded/no-selection → the get-a-model turn.
- **Verify:** with an installed model, cold-start and send within the first second, then force a load failure
  (rename the GGUF under `files/models`) and send — assert three distinct messages.

### ST-02 — A load failure never says why; the one fix that would work is unnamed
- **Severity:** P1 **Confidence:** high **Effort:** S
- **Where:** model chip → passport failed state (`app/.../Panels.kt:178-196`); engine reasons
  `engine/llamacpp/.../LlamaCppEngine.kt:117-134` (`PROJECTOR_FAILED`, `PROJECTOR_NO_VISION`,
  `LOAD_FAILED`); copy `app/src/main/res/values/strings.xml:331`.
- **What happens now:** the sheet body is always «فشل تحميل النموذج. أعد المحاولة، أو افتح المكتبة لاختيار نموذج
  أخف.» — "retry, or pick a lighter model" — regardless of whether the failure was an out-of-memory load, a
  missing vision projector, or an unreadable file. The engine's distinct reasons have no user-facing text.
- **Why it hurts:** the advice is wrong for most causes (a projector failure is not fixed by a lighter model; a
  corrupt file is not fixed by retrying), so the user repeats the same two actions and concludes the app is
  broken. Choosing a lighter model is also a two-screen trip from a dead-end sheet.
- **Fix:** map each engine reason to one sentence + one primary action: projector missing → "re-import model
  file"; out of memory/too big → "pick a smaller model" (deep-link to the fit-filtered list); unknown →
  "open model details" with the model name and size, never a bare "retry".
- **Verify:** rename/truncate the installed GGUF, restart, read the chip sheet — the copy must name the real
  cause; repeat with a projector-less vision model.

### ST-03 — Any unmapped engine error becomes "retry or switch engines", and no engine switch exists
- **Severity:** P2 **Confidence:** high **Effort:** S
- **Where:** failed-turn mapping `feature/chat/.../ChatScreen.kt:337-341`; generic copy
  `feature/chat/src/main/res/values/strings.xml:17`; stream failure carries only a stable code
  (`ChatStore.kt:1020-1037`).
- **What happens now:** only `NO_MODEL`, `STOPPED`, `RAG_NO_SOURCES` and `PROCESS_LOST` get specific copy; every
  other engine code — including `NO_OUTPUT` and all vision failures — shows «النموذج غير متاح الآن. أعد المحاولة
  أو بدّل المحرّك.» The engine is chosen by build config (`app.json engine.provider`); there is no switch.
- **Why it hurts:** the message promises a control the UI does not have and hides the only actionable signal
  (the engine's own reason). The user is left to guess whether to retry, restart, or change models.
- **Fix:** remove "or switch engines"; add per-reason copy for `NO_OUTPUT` ("the model returned nothing —
  shorten the prompt or lower the context") and vision reasons, and show the raw reason under a "details"
  disclosure for support.
- **Verify:** force a stream failure with an unknown code (unit test on the mapping + a debug hook) and read
  the turn.

### ST-04 — A selected-but-unloaded model leaves the composer enabled, and the failure tells you to import
- **Severity:** P2 **Confidence:** high **Effort:** S
- **Where:** chip "unloaded" sheet `app/.../Panels.kt:206-222`; send gate `ChatStore.kt:459-461`; copy
  `feature/chat/src/main/res/values/strings.xml:18-19`; live state in
  `build/ux-audit/logs-clean/clean-06-send-no-model-failure-ar-nodes.txt`.
- **What happens now:** after `engine.unload` (chip passport) the model remains selected, the chip says
  «غير محمّل», the send button stays enabled, and sending produces "no model loaded yet — import a GGUF from
  Settings". The model *exists*; it just is not loaded.
- **Why it hurts:** the only recovery is to tap the chip and discover "Load" somewhere in the passport; the
  error text actively points at importing (a different, heavier action). Users may import a duplicate model.
- **Fix:** in the unloaded-but-selected state show "Load model" as the primary action in the failed turn and
  in the composer's disabled state; only the genuinely-no-model state should speak about importing.
- **Verify:** unload from the chip, send — the turn must offer "Load" (one tap), not "import".

## 2. Downloads (DL)

### DL-01 — The metered question lies in both directions
- **Severity:** P1 **Confidence:** high **Effort:** S
- **Where:** Library → model card "download" (`app/.../LibraryScreen.kt:857-862` local `isMetered`, dialog
  `283-300`); enforcement `core/models/.../Downloads.kt:265-268`; policy
  `app/.../AndroidDownloadSupport.kt:36-43`.
- **What happens now:** `isMetered` returns **true when there is no active network at all**, so in airplane
  mode the app asks «هل تريد التنزيل عبر بيانات الجوال؟» even though there is no mobile data; accepting then
  fails as a network error. Conversely, on a metered Wi-Fi hotspot `isMetered` reads false while the
  downloader's policy can still call it unmetered (Wi-Fi transport) — the question is not asked even on a
  connection the user may be paying for; on a metered Wi-Fi where policy says "not unmetered", the tap starts
  nothing with no message.
- **Why it hurts:** a consent prompt about data charges is the one place the app must be exact; here the
  prompt appears when it cannot matter and is absent when it does. The user either loses data or concludes
  the button is dead.
- **Fix:** one source of truth — ask the policy (`AndroidNetworkPolicy`) whether the *current* connection is
  unmetered, treat "no network" as its own "You're offline" state (not metered), and derive the dialog from
  the same predicate the downloader enforces.
- **Verify:** emulator airplane mode + Wi-Fi metered toggle; assert no mobile-data question offline and a
  question (or a clear "downloads on Wi-Fi only" note) on a metered connection.

### DL-02 — First-run download on mobile data can never succeed, and the app blames the network
- **Severity:** P0 **Confidence:** high **Effort:** M
- **Where:** onboarding model step → "Download model" (`feature/models/.../OnboardingScreen.kt:93-98` calls
  `downloader.start(entry, authorization = token)` with default `allowMetered = false`; failure rendering
  `304-313`; gate `core/models/.../Downloads.kt:265-268` → `NOT_UNMETERED`).
- **What happens now:** onboarding is the only path to the first model, it has no metered-consent dialog,
  and the downloader refuses every run unless the network is unmetered. Any `FAILED` phase renders
  «تعذّر إكمال التنزيل. تحقق من الاتصال وأعد المحاولة.» with one Retry button; retry calls the same
  `start` with the same default. On the target user's metered phone this is an infinite loop: check
  connection → retry → "check your connection".
- **Why it hurts:** the core promise (get a model, ask a question) cannot be completed on the network the
  brief says matters (2–4 GB on metered data, "one-handed, often no signal"). The copy makes it worse by
  diagnosing a policy refusal as a connectivity fault.
- **Fix:** route onboarding through the same `DownloadBridge`/queue as Library (it already owns the consent
  dialog and the "stays until Wi-Fi" row), or minimally add the same "Download over mobile data?" prompt
  before `start` and keep the real `DownloadReasons` text (the Library path already maps them).
- **Verify:** fresh install, mobile-data-only network, model step → download: either a consent dialog appears
  or an explicit "Wi-Fi required, connect or skip" message — never "check your connection".

### DL-03 — "Waiting for Wi-Fi" never resumes; nothing watches connectivity
- **Severity:** P1 **Confidence:** high **Effort:** S-M
- **Where:** `app/.../downloads/DownloadBridge.kt:182-190` (`pump` = the only automatic start);
  call sites `148, 203, 215, 222, 234, 248`; promise
  `feature/models/src/main/res/values/hf_strings.xml:126` («سيبقى في القائمة حتى Wi-Fi»).
- **What happens now:** `pump()` runs only after a user mutation (enqueue/pause/resume/cancel/retry/remove/
  install) and returns immediately while the connection is metered. No `ConnectivityManager` callback exists
  anywhere in the app (only two query sites: `AndroidDownloadSupport.kt:27`, `LibraryScreen.kt:858`), so when
  Wi-Fi comes back a row that was deferred stays QUEUED until the user taps Start or restarts the app.
- **Why it hurts:** the HF screen explicitly promises the download starts on Wi-Fi; a user who leaves the app
  waiting returns hours later to a row that never began. The state says "waiting" without saying "waiting for
  you to tap".
- **Fix:** register a network callback in the host and call `bridge.pump()` on unmetered transitions (and on
  process start after `load()`); if that is too large for this release, change the copy to "tap Start when
  you're on Wi-Fi" and surface a persistent row action.
- **Verify:** on mobile data queue a download, turn on Wi-Fi without touching the app, wait — the row must
  move to downloading (or the copy must not promise it).

### DL-04 — Retry after verification failure shows ~100% progress on a download starting from zero
- **Severity:** P1 **Confidence:** high **Effort:** S
- **Where:** failure paths delete the part file `core/models/.../Downloads.kt:358-374`; queue `retry` keeps the
  byte count `core/models/.../DownloadQueue.kt:316-318`; progress is clamped and never rewinds `272-282`;
  row rendering `app/.../LibraryScreen.kt:690-704`.
- **What happens now:** a checksum / size-mismatch / invalid-GGUF failure deletes the partial file, but
  `retry()` only changes the phase — `downloadedBytes` stays at the old (usually full) value, and
  `updateProgress` ignores smaller values. The row shows e.g. «100% · 1.12 GB من 1.12 GB» while the transfer
  really restarts at byte 0 and the bar does not move until the new transfer passes the stale number.
- **Why it hurts:** the only progress surface claims the work is done while nothing is happening; the user
  either force-stops it or leaves, and cannot tell a slow restart from a hang.
- **Fix:** reset `downloadedBytes = 0` (and the persisted row) whenever the part file is gone, and allow
  `updateProgress` to lower the value after a phase change from FAILED/CANCELLED to QUEUED.
- **Verify:** corrupt the staging zip or force a checksum failure, tap Retry, watch the first 10 s — the row
  must show a low, rising number.

### DL-05 — Cancelling a paused download wipes the resume point but leaves the .part file on disk
- **Severity:** P2 **Confidence:** high **Effort:** S
- **Where:** `app/.../downloads/DownloadBridge.kt:208-217` (`port.cancel()` only when the row was ACTIVE);
  downloader `core/models/.../Downloads.kt:207-213` deletes the part file only when `cancel()` is called;
  queue `DownloadQueue.kt:336-345` zeroes bytes; next run resumes from `part.length()`
  `Downloads.kt:269, 275`.
- **What happens now:** cancelling a PAUSED row zeroes the queue's byte count but never reaches the
  downloader, so the `.part` file stays in staging indefinitely; if the user retries, the downloader resumes
  from that stale file offset while the queue starts at 0, so the row's numbers jump and the disk keeps GBs
  the UI says were discarded.
- **Why it hurts:** cancelled downloads keep consuming storage on a 6 GB phone (the user was told the resume
  point is wiped), and progress accounting no longer matches the file on disk.
- **Fix:** always clear the resume point on cancel (call `port.cancel()`/a new `port.discard(id)` for any
  cancellable row, not just the active one), or make the bridge recompute bytes from the part file.
- **Verify:** pause at ~50%, cancel, check `files/models/…part` exists/does not exist and the reported free
  space; retry and watch whether progress starts at 0%.

### DL-06 — A corrupt download store is rendered as "no downloads in progress"
- **Severity:** P1 **Confidence:** high **Effort:** S
- **Where:** `core/models/.../DownloadQueue.kt:179-209` (returns an empty list + `CORRUPT_QUEUE`/
  `UNREADABLE_STORE`/schema code, never throws); host wiring `app/.../AppComposition.kt:210-212` logs the
  codes only; empty copy `app/.../LibraryScreen.kt:447` («لا تنزيلات جارية»).
- **What happens now:** if the queue snapshot cannot be read (truncated write, unreadable file, newer schema),
  the bridge loads zero tasks and the only consumer of the problem codes is `Log.w`. Library → Downloads shows
  the normal empty state; queued/paused/installed rows are gone with no notice.
- **Why it hurts:** the user's own list of downloads silently disappears — indistinguishable from having
  never downloaded anything — and there is no "repair", "restore" or "clear" action, so the state cannot be
  recovered inside the app.
- **Fix:** pass the queue problems to the Downloads segment: a one-line warning card ("Download list couldn't
  be read; the files on disk are unaffected") with "clear list" / "retry read"; log the code for support only.
- **Verify:** truncate `files/downloads/queue.json` (atomic store path) and open Library → Downloads.

### DL-07 — "Start" can refuse silently (and always refuses on mobile data)
- **Severity:** P2 **Confidence:** high **Effort:** S
- **Where:** `app/.../LibraryScreen.kt:172-179` ignores the `Boolean` from `bridge.start(..., allowMetered = false)`;
  refusal reasons `DownloadBridge.kt:161-175` (another active row / metered / not QUEUED / IO failure).
- **What happens now:** on a queued row, tapping «بدء» on a metered connection or while another download runs
  returns false and the UI does nothing — no toast, no state change, the same button stays there. On mobile
  data the hard-coded `allowMetered = false` means it can never succeed until Wi-Fi.
- **Why it hurts:** the user's only control on that row appears broken; they tap repeatedly instead of
  learning "wait for the active download" or "needs Wi-Fi".
- **Fix:** have the screen branch on the result: metered → the same consent dialog used elsewhere; another
  active → "another download is running" with a link to it; IO → mark the row failed with its reason.
- **Verify:** start one download, tap Start on a queued second row; repeat on mobile data.

### DL-08 — The "Verifying" row offers no cancel and no progress
- **Severity:** P2 **Confidence:** high **Effort:** S
- **Where:** `app/.../LibraryScreen.kt:715` (VERIFYING renders only `library_download_verifying`);
  queue allows cancelling a verifying row (`DownloadQueue.kt:336`); checksum of a 2–4 GB file is the longest
  silent phase (`Downloads.kt:357-375`).
- **What happens now:** for the minutes a large file is hashed the row shows a static line of text with no
  percentage, no spinner, no cancel. Other phases all carry actions.
- **Why it hurts:** indistinguishable from a freeze at the moment the user is most likely to give up; they
  cannot abort and free the slot.
- **Fix:** show an indeterminate indicator + "Verifying… (this can take a minute)" and a Cancel that routes to
  the same cancel path as ACTIVE.
- **Verify:** download a large model, watch the VERIFYING phase; the row must animate and accept Cancel.

### DL-09 — Unknown-size downloads show "0% · 0 B من 0 B" forever
- **Severity:** P2 **Confidence:** high **Effort:** S
- **Where:** `core/models/.../DownloadQueue.kt:57-62` (`fraction` is 0 when `sizeBytes <= 0`);
  `app/.../LibraryScreen.kt:690-704` still prints the percentage and both byte counts; HF entries can have no
  LFS size (`feature/models/.../HfBrowseLogic.kt` `syntheticEntry`).
- **What happens now:** when the size is unknown the progress bar is skipped (`sizeBytes <= 0`), but the
  text line still renders 0% and a frozen «0 B من 0 B» counter for the whole transfer.
- **Why it hurts:** the one live signal says "nothing has happened" even while bytes stream in; users kill a
  working download.
- **Fix:** when the size is unknown show "Downloading… · 1.2 GB" (bytes only, no percentage, no total) and
  keep the indeterminate bar; switch to the percentage form once the total is known.
- **Verify:** start a download for a file without LFS metadata (sizeBytes 0) and read the row.

### DL-10 — Process death during a download looks exactly like a user pause
- **Severity:** P2 **Confidence:** high **Effort:** S
- **Where:** `app/.../downloads/DownloadBridge.kt:108-117` (ACTIVE → PAUSED on load, bytes kept, comment
  cites docs/31); row label `LibraryScreen.kt:753-763`.
- **What happens now:** after the process is killed mid-transfer the row reappears as PAUSED with its old
  byte count and a Resume button; nothing says the transfer was interrupted by the system, and nothing
  auto-resumes even on Wi-Fi.
- **Why it hurts:** the user cannot tell whether they paused it (and why), whether the download failed, or
  whether it is safe to resume; ambiguity at the place where resuming costs data.
- **Fix:** persist a flag distinguishing "interrupted by app restart" from "paused by user", show
  "Interrupted — resume?" with the saved bytes, and let the existing pump/resume logic restart it on Wi-Fi
  (docs/31's no-silent-resume rule applies to metered only).
- **Verify:** start, kill the app via tools, reopen Library → Downloads.

### DL-11 — Queue rejections show a raw English code inside the Arabic UI
- **Severity:** P2 **Confidence:** high **Effort:** S
- **Where:** `app/.../LibraryScreen.kt:222-225` (interpolates `code` into
  `R.string.library_queue_rejected`), copy `app/src/main/res/values/strings.xml:327` «تعذّر إضافة الطلب
  (%1$s).»; codes `DownloadQueue.kt:123-143`.
- **What happens now:** when enqueue is rejected the user reads e.g. «تعذّر إضافة الطلب (not_enough_storage).»
  — a stable machine code, never localised, with no next step.
- **Why it hurts:** the only explanation of a failed action is unreadable to the target user; it looks like a
  crash string.
- **Fix:** map each `DownloadQueueCodes` value to one localised sentence + action, as the rest of the app
  already does; keep the code in logs only.
- **Verify:** force a rejection (low storage / invalid entry) and read the notice.

### DL-12 — Downloads started from Library never ask for the notification permission
- **Severity:** P2 **Confidence:** high **Effort:** S
- **Where:** `feature/models/.../DownloadNotificationPermission.kt:37` requested only from
  `OnboardingScreen.kt:95` and `ModelManagerSection.kt:133`; the Library download path
  (`LibraryScreen.kt:136-158`) never calls it; denial is silent.
- **What happens now:** on Android 13+ a user who skipped onboarding and downloads from Library for the first
  time never sees the permission dialog; the foreground service's progress notification is suppressed. The
  app gives no explanation.
- **Why it hurts:** the biggest wait in the product becomes invisible in the notification shade; if the user
  leaves the app they cannot tell whether the download continues, and the only place to learn why is system
  settings.
- **Fix:** request the permission on the first download started from any surface (contextual, as the comment
  says) and, if denied, keep an in-app "downloads continue while the app is open" note on the row.
- **Verify:** fresh install, skip onboarding, download from Library, leave the app — check the notification
  shade and whether any in-app copy mentions it.

### DL-13 — The storage-warning decision uses a capacity snapshot taken when the screen opened
- **Severity:** P2 **Confidence:** high **Effort:** S
- **Where:** `app/.../LibraryScreen.kt:109-110` (`remember(queueKey) { probe.capacity() }`), TIGHT branch
  `150-153`, dialog `301-326`.
- **What happens now:** free space is measured once per queue key; deleting a model, installing an update, or
  another app filling the disk leaves the dialog showing the old number and the same decision ("not enough
  room") even after room is made. There is no refresh action.
- **Why it hurts:** the user is blocked by a stale measurement and cannot make the dialog reconsider without
  leaving the screen; the number shown may contradict system storage.
- **Fix:** re-probe when the dialog opens and after returning from the model list, and label the value with
  its age or a refresh control.
- **Verify:** open the TIGHT dialog, delete the installed model, reopen — the number must change.

### DL-14 — Metered consent does not survive the process
- **Severity:** P2 **Confidence:** high **Effort:** S
- **Where:** consent lives in `Downloader.Request.allowMetered` (`core/models/.../Downloads.kt:135-142`) and
  is not part of `DownloadTask` (`DownloadQueue.kt` task fields); after restart `bridge.load` pauses rows
  (`DownloadBridge.kt:108-117`); Start hard-codes `allowMetered = false` (`LibraryScreen.kt:178`).
- **What happens now:** the user consents to a metered transfer, the process dies (or they pause and reopen),
  and the resumed row cannot start on mobile data: the "Start" path sends `allowMetered = false`, so it
  silently returns false until the app asks again — and it never asks again.
- **Why it hurts:** the consent the user already gave is forgotten, and the state does not explain that a
  new consent is needed; the download stalls with a button that does nothing.
- **Fix:** store the per-row metered authorisation with the task, or route Start through the consent dialog
  whenever the row was authorised before and the connection is still metered.
- **Verify:** consent on mobile data, pause, force-stop and reopen, tap Resume/Start.

### DL-15 — A completed download vanishes from the Downloads segment with no "installed" signal
- **Severity:** P3 **Confidence:** high **Effort:** S
- **Where:** `app/.../LibraryScreen.kt:444` filters `INSTALLED` rows out of the downloads list; the
  INSTALLED body `716` is therefore unreachable there; the model appears only in the Models segment.
- **What happens now:** the row the user has been watching for minutes disappears at 100%; nothing says
  "installed — the model is now ready in Models".
- **Why it hurts:** the transition reads as a loss at the moment of success; the user has to infer the
  outcome and find the model themselves.
- **Fix:** keep the finished row visible for one session with a check + "Open model" (or show a one-line
  "Installed" banner in Downloads) before the segment filter hides it.
- **Verify:** finish a download and watch the Downloads segment.

## 3. Hugging Face browsing and download hand-off (HF)

### HF-01 — The HF token is used for the probe but never for the download
- **Severity:** P0 **Confidence:** high **Effort:** M
- **Where:** port signature `app/.../downloads/DownloadBridge.kt:29-37` has no token;
  implementation `app/.../AppComposition.kt:365-377` calls `downloader.start(entry, allowMetered)` with the
  default `authorization = null`; token only reaches the probe transport
  (`app/.../HfServices.kt` `UrlGgufProbeTransport`); gated flow
  `feature/models/.../HfBrowseScreens.kt:702-742, 775-815`.
- **What happens now:** the user enters a token for a gated repo, the repo loads and the probe succeeds
  ("token works"), the download is allowed — and the transfer is started with no `Authorization` header, so
  the gated file returns 401. The library maps that to a generic download failure
  (`LibraryScreen.kt:845-855` collapses `http` to the generic reason).
- **Why it hurts:** gated repositories are the normal way to get several models; the app proves the token
  works and then fails anyway, with a reason that cannot be acted on. The user's data/time is spent on a
  transfer that was never authorised.
- **Fix:** carry the token with the download request (add `authorization: String?` to `DownloadPort.start`
  and persist it only for that queued row in memory), and map HTTP 401/403 to "token required/expired —
  re-enter" instead of the generic HTTP text.
- **Verify:** gated repo + valid token → probe OK → download: it must complete; with an invalid token the row
  must say "gated / token", not "download failed".

### HF-02 — Probe says "enter a token", but the token field only exists in another phase
- **Severity:** P1 **Confidence:** high **Effort:** S
- **Where:** probe failure copy `feature/models/src/main/res/values/hf_strings.xml:66`
  («الملف مقيّد؛ أدخل رمز Hugging Face ثم أعد المحاولة»); probe section
  `feature/models/.../HfBrowseScreens.kt:445-504` (failure + retry only); token card
  `702-742` rendered only for `TOKEN_REQUIRED`.
- **What happens now:** on a loaded repo page a gated *probe* failure shows the instruction to enter a token,
  but the page has no token input in that state — only "retry". Retrying without a token repeats the failure.
- **Why it hurts:** a direct dead end with an instruction the screen cannot satisfy; the user has to guess
  that going back and re-opening the repo (or another phase) reveals a token card.
- **Fix:** render the token card whenever `TOKEN_REQUIRED` *or* the probe failure reason is gated/401; after
  saving the token retry the probe automatically.
- **Verify:** gated repo without token, probe → follow the on-screen instruction only; it must be possible.

### HF-03 — The repo-page failure card says the *search* failed
- **Severity:** P2 **Confidence:** high **Effort:** S
- **Where:** `feature/models/.../HfBrowseScreens.kt:848-865` (`HfFailureCard` title always
  `hf_search_failure_generic`), used by both the search page (`152-156`) and the repo page (`300-320`).
- **What happens now:** a repo that fails to load shows «تعذّر إكمال البحث» — "the search could not be
  completed" — though no search is running and the query is not the failing operation.
- **Why it hurts:** the message misidentifies the operation, so the user retries the search instead of the
  repo open; on a retry loop it reads as a bug.
- **Fix:** give `HfFailureCard` a title parameter ("Could not open this repo") and keep the search title for
  the search page.
- **Verify:** open an unavailable repo (bad id) and read the card.

### HF-04 — Rate-limit state says "wait a moment" and offers only a manual retry
- **Severity:** P2 **Confidence:** high **Effort:** S
- **Where:** copy `feature/models/src/main/res/values/hf_strings.xml:30`; rendering
  `feature/models/.../HfBrowseScreens.kt:152-156, 171-177`.
- **What happens now:** a 429 renders a generic wait message; the screen offers Retry immediately (which
  repeats the 429) and does not say how long, though the response carries `Retry-After` and the client
  already parses it.
- **Why it hurts:** the user cycles retries during a rate limit, extending it, and cannot tell a temporary
  limit from a broken network.
- **Fix:** show the parsed wait ("try again in 32 s"), disable Retry until it elapses, and offer "search again
  later" — or retry automatically once.
- **Verify:** force a 429 (unit/mock) and read the card.

### HF-05 — A BLOCKED fit verdict is a dead end with no alternative
- **Severity:** P2 **Confidence:** high **Effort:** M
- **Where:** download section disabled when the decision is BLOCKED
  (`feature/models/.../HfBrowseScreens.kt:645-699`), verdict card `560-620`, note
  `feature/models/src/main/res/values/hf_strings.xml` (`hf_fit_blocked_note`).
- **What happens now:** when the fit verdict says the model will not run (too large for RAM, wrong arch, too
  little storage) the download control is disabled and the page states the reason, but the only ways forward
  are back and search again — there is no "show models that fit this device" filter or suggestion.
- **Why it hurts:** the user has completed a search and a probe only to be told "no"; the app knows the device
  capacity and the catalogue constraints but does not use that knowledge to offer the nearest working option.
- **Fix:** on BLOCKED, show the device limit next to the verdict and a one-tap "models that fit" action (or
  the closest smaller quantization of the same model); keep download disabled.
- **Verify:** pick a model that cannot fit on the 6 GB emulator (or fake capacity) and look for a next step.

## 4. Chat persistence, restore, and interruption (CH)

### CH-01 — A failed conversation save is discarded and never shown
- **Severity:** P0 **Confidence:** high **Effort:** M
- **Where:** `feature/chat/.../ChatStore.kt:853-877` (`runCatching { library.save(snapshot) }` — the result is
  thrown away at 863/873); `app/.../FileConversationLibrary.kt:301-316` (write errors logged, not returned);
  `save` `110-125`.
- **What happens now:** chats are persisted on every successful turn, but if the write fails (full disk,
  revoked storage, unreadable directory) the app keeps the transcript in memory, shows no error, and the user
  continues as if saved. On the next launch the conversation is missing or truncated back to the last good
  write.
- **Why it hurts:** the product's value is a private notebook that stays on the device; losing a session's
  notes without a single warning is the most damaging promise break in the app, and it looks like a random
  disappearance.
- **Fix:** propagate the save result to the chat: a non-blocking banner "Couldn't save this chat — free some
  space" with Retry/Export; keep an in-memory dirty flag and retry on the next turn; never return success
  for a swallowed write.
- **Verify:** fill the data partition (or make `conversations/` read-only via debug) and send a turn; the
  banner must appear and the copy must name saving.

### CH-02 — Process death mid-answer leaves the question saved and the answer gone, silently
- **Severity:** P1 **Confidence:** high **Effort:** M
- **Where:** question persisted before the engine check `ChatStore.kt:456`; only completed turns are
  persisted `811-837` (`persist`/`turnOf` `797-805`); restore path `236-261`.
- **What happens now:** if the process dies during streaming, the user's question survives, the partial answer
  does not, and on reopening there is a question followed by nothing/older turns — no "interrupted" marker, no
  retry affordance of its own.
- **Why it hurts:** the transcript reads as if the assistant ignored the last message; the user cannot tell
  whether the request was never sent, was lost, or is still running. They may re-send and duplicate.
- **Fix:** persist an "interrupted" placeholder for the in-flight turn on next start (or a per-conversation
  `inFlight` marker) and render "This answer was interrupted — regenerate" in the failed-turn style.
- **Verify:** send a long prompt, force-stop mid-stream, reopen the conversation.

### CH-03 — "Stop" saves a truncated answer as a complete one
- **Severity:** P1 **Confidence:** high **Effort:** S
- **Where:** `feature/chat/.../ChatStore.kt:568-586` (`stop()` keeps partial text, `persist()` at 585;
  nothing marks the turn stopped); failed-turn copy only for failures
  (`ChatScreen.kt:337-341`, `strings.xml:20` `error_stopped`).
- **What happens now:** stopping mid-generation keeps the partial text as an ordinary assistant turn; the
  transcript, history snippet, export and restored session all present it as a finished answer.
- **Why it hurts:** a half sentence becomes a quotable "answer" — for a student's notes or a contract review
  that is misleading content, not just UI. The user cannot tell later that the model was cut off.
- **Fix:** mark the turn "stopped at your request" (a status line, not an error) and keep regenerate; persist
  the marker so restore shows it too.
- **Verify:** send, stop after two words, restart the app, open the chat.

### CH-04 — An unreadable conversation either does nothing or disappears from history
- **Severity:** P1 **Confidence:** high **Effort:** M
- **Where:** history row tap `feature/chat/.../ChatHistory.kt:181-183` (`onOpen = { if
  (store.openConversation(id)) onOpened() }` — no else); read `FileConversationLibrary.kt:244-252` (returns
  null on unreadable, logs only); index rebuild `176-190` skips undecodable transcripts.
- **What happens now:** if a conversation file cannot be decoded, tapping its row does nothing at all (no
  sheet, no message, the list stays); after a corrupt index the rebuild silently drops the file from the list.
- **Why it hurts:** a note the user believes exists becomes unreachable/deleted with zero signal — the worst
  possible failure mode for a notebook, and it is indistinguishable from a missed tap.
- **Fix:** on open failure show "This chat couldn't be read" with the file's name/date and an "export raw" or
  delete action; on rebuild report skipped files in a recoverable "issues" row instead of dropping them.
- **Verify:** truncate one `conversations/*.json`, open history, tap that row.

### CH-05 — New chat silently replaces the transcript and the only confirm copy is dead
- **Severity:** P3 **Confidence:** high **Effort:** S
- **Where:** header action `feature/chat/.../ChatScreen.kt:214-217` calls `store.newConversation()`;
  `ChatStore.kt:629-635` keeps the old transcript in history; unused strings
  `feature/chat/src/main/res/values/strings.xml:22-24` (`new_chat_title/body/confirm`) still claim the
  conversation will be deleted.
- **What happens now:** "+" immediately starts a blank transcript with no cue that the previous chat was
  saved to history; the only copy that ever described this action is unused, and it describes the opposite
  behaviour (deletion).
- **Why it hurts:** users who fear losing notes avoid the button; if the dead copy were ever wired up it
  would lie. Today it is only confusing dead weight.
- **Fix:** either a one-time inline "previous chat saved to history" snackbar, or delete the dead strings;
  do not add a delete confirmation because nothing is deleted.
- **Verify:** tap + after a turn; check history contains the previous chat and no deletion copy exists.

## 5. Documents, RAG and embedding (DOC)

### DOC-01 — Two different wrong/right directions for the missing embedding model
- **Severity:** P1 **Confidence:** high **Effort:** S
- **Where:** chat attachment sheet document row `feature/chat/.../AttachmentUi.kt:339` + copy
  `feature/chat/src/main/res/values/strings.xml:67` («استورده من تبويب المكتبة»); the document picker's own
  copy `strings.xml:86` says "Settings"; the actual import lives in **Settings → Advanced**
  (`app/.../RagPlugin.kt:58-87`, label `settings_advanced_title`), not in Library.
- **What happens now:** from the composer the user is told to import the embedding model from the Library tab
  — where there is no such control (Library holds documents and media only). Importing a document from
  Library leaves it "waiting for the embedding model" with no route to the import screen. Elsewhere the same
  situation says "Settings" with no sub-page named.
- **Why it hurts:** documents are a headline feature; the setup instruction sends the user to a place where
  the required action does not exist, and the alternative path is hidden behind a door called "Advanced"
  whose summary only says "semantic search not set up".
- **Fix:** one route everywhere — deep-link "Import embedding model" to Settings → Advanced (or move the
  import into Library → Media → Documents); make the door name mention the model ("Semantic search &
  documents") so it is discoverable.
- **Verify:** no embedding model, open the composer attachment sheet → document; follow the instruction
  literally — the import control must be there.

### DOC-02 — A failed document says only "indexing failed", with no reason
- **Severity:** P2 **Confidence:** high **Effort:** S
- **Where:** status line `app/.../DocumentsLibrarySection.kt:182-199`; the failure reason is stored
  (`app/.../RagRepository.kt:237-243` for extraction rejection, `281-313` for indexing) but never rendered;
  retry icon `94-113`.
- **What happens now:** a document row that failed extraction or indexing shows «تعذّرت الفهرسة · <size>» only;
  the reason code (`OOM`, `NO_SPACE`, `EXTRACTION_FAILED`, `embedding missing`…) exists in the record and is not
  shown or mapped. Retry is offered for every failure.
- **Why it hurts:** the user cannot tell a permanently unsupported file from a transient failure or a full
  disk; retry becomes a guessing loop on a document they may have deleted the source of.
- **Fix:** map `failureReason` to the same `importRejectionLabel` vocabulary used at import time and show it
  in the row; hide "retry" for reasons where retry cannot help (unsupported/encrypted/too large).
- **Verify:** import an encrypted PDF, read the row; import a valid one and force an indexing failure.

### DOC-03 — Pending documents say "indexed automatically when available" with no way to provide it
- **Severity:** P2 **Confidence:** high **Effort:** S
- **Where:** plural note `app/src/main/res/values/strings.xml:190-197`, rendered
  `app/.../DocumentsLibrarySection.kt:141-149`; auto-index exists (`app/.../RagRepository.kt:85`,
  `264-278`) but the note names no source for the embedding model.
- **What happens now:** importing a document without the embedding model succeeds as "waiting for the
  embedding model; it will be indexed automatically when available". The note does not say what "available"
  means, where to get it, or that the feature is currently unusable.
- **Why it hurts:** the state is honest about waiting but not actionable; a user can import a library of
  documents and never learn that one file under Settings → Advanced unlocks them all.
- **Fix:** append one action to the pending note — "Import embedding model" deep-linking to Settings →
  Advanced — and show the same action on the document picker's blocked row.
- **Verify:** no embedding model, import a PDF, read the note and follow it.

### DOC-04 — "Embedding model failed to load" has no reason and no retry of its own
- **Severity:** P2 **Confidence:** high **Effort:** S
- **Where:** `app/.../RagPlugin.kt:216-220` maps `EmbeddingStatus.Failed` to
  `embedding_status_failed` («تعذّر التحميل») only; copy `app/src/main/res/values/strings.xml:160`,
  `embedding_error_*` `180-184` exist but are used only at import.
- **What happens now:** when the installed embedding model fails to load at startup, Settings shows "Not
  imported"/"Could not load" depending on race, documents stay pending, and the only action is to re-import
  the file (no retry-load, no reason).
- **Why it hurts:** the user cannot distinguish "file invalid" from "not enough memory right now"; re-importing
  a large file is the only offered action and may not help.
- **Fix:** show the load failure reason (reuse the `embedding_error_*` vocabulary) and add a "Try loading
  again" action next to Replace/Remove.
- **Verify:** corrupt the imported embedding file, restart, open Settings → Advanced.

## 6. Media & dictation permission states (MED)

### MED-01 — Microphone permission permanently denied is a dead end with no route to app settings
- **Severity:** P2 **Confidence:** high **Effort:** S
- **Where:** `feature/voice/.../DictationControls.kt:84-97` (request once; after "don't ask again" the
  launcher returns denied immediately), failure strip `217-221, 285-337` (dismiss only); copy
  `feature/voice/src/main/res/values/strings.xml:45`.
- **What happens now:** after the user denies twice, tapping the mic re-launches the permission request, the
  system does not show a dialog, and the strip says «الإملاء يحتاج إلى إذن الميكروفون.» with a single dismiss
  (X). There is no "Open app settings".
- **Why it hurts:** dictation is permanently unusable and the app never tells the user where to change the
  decision; each tap looks like a broken button.
- **Fix:** detect `shouldShowRequestPermissionRationale == false` after a denial and show "Open app settings"
  as the strip's primary action; keep dismiss secondary.
- **Verify:** fresh install, deny mic twice, tap the mic, read the strip.

## 7. Network/permission cross-cutting (GEN)

### GEN-01 — "Offline" and "no permission" states never say which setting to change where a feature is gated
- **Severity:** P2 **Confidence:** high **Effort:** S
- **Where:** HF blocked search card `feature/models/.../HfBrowseScreens.kt:137-151` (disable-offline action
  exists — good); downloads have no offline state at all (`LibraryScreen.kt:857-862` treats "no network" as
  metered, DL-01); dictation permission MED-01; embedding DOC-04.
- **What happens now:** the app is honest about *being* offline in HF but keeps the "metered" question in
  downloads, where an offline tap produces a network-error failure after a consent dialog that makes no
  sense; permission-gated features (mic, notifications) show a sentence without the one action that fixes it.
- **Why it hurts:** the same "you can't do this right now" class of state is explained well in one surface
  and badly in three; users learn not to trust the app's diagnoses.
- **Fix:** one shared "gate" component (offline / metered / permission / missing prerequisite) with a title,
  cause, and exactly one action; use it in downloads, dictation, notifications and embedding.
- **Verify:** compare the four gates side by side; each must name the setting and offer its shortcut.

---

## What is already honest (do not re-report as defects)

- HF download hand-off copy distinguishes queued / declined-mobile / waiting-for-another / start-failed
  (`hf_download_notice_*`, `hf_strings.xml:121-131`) and states when a file is unverified.
- The fit verdict rank and reasons are typed and localised (`FitReasons` → `fitLabel/fitTone`
  `LibraryScreen.kt:806-842`); the "unverified" note is explicit.
- The download queue persists atomically (temp + rename, `QueueFileStore.kt`), tolerates corruption instead
  of crashing, and the conversation index backs up and rebuilds from transcripts
  (`FileConversationLibrary.kt:161-190`).
- Process loss of the inference engine is surfaced as its own state, not as a generic error
  (`RemoteInferenceEngine.kt:224` → `PROCESS_LOST` → `error_engine_process_lost`).
- Media failure reasons are fully mapped (`MediaLibrarySection.kt:693-712`) and the media contract is stated
  in Settings rather than hidden in docs.

## Limits of this report (unverified here)

- I did not run the app or adb; visual-only claims are marked where used. Screenshot citations
  (`build/ux-audit/logs-clean/clean-05-…`, `clean-06-…`) are the matching node dumps I could read.
- The engine demo fallback, the onboarding Activity recreate and the "no engine switch" copy are already in
  the Lead's cross-check (`09-lead-cross-check.md` LC-01, LC-02, LC-07, LC-11, LC-12); my DL-02 adds the
  metered dead end, ST-01 the loading/failed conflation. The council should merge these rather than count
  them twice.
- Two state surfaces were out of reach in my window and deserve a follow-up: voice model import's own
  failure states (`VoiceSettingsSection.kt` not exhausted) and the HF token card's behaviour after process
  death (token is memory-only by design).

---

## Finding index (for consolidation)

| ID | Title |
| --- | --- |
| ST-01 | "Loading" and "Load failed" are both reported to the chat as "no model" |
| ST-02 | A load failure never says why; the one fix that would work is unnamed |
| ST-03 | Any unmapped engine error becomes "retry or switch engines", and no engine switch exists |
| ST-04 | A selected-but-unloaded model leaves the composer enabled, and the failure tells you to import |
| DL-01 | The metered question lies in both directions |
| DL-02 | First-run download on mobile data can never succeed, and the app blames the network |
| DL-03 | "Waiting for Wi-Fi" never resumes; nothing watches connectivity |
| DL-04 | Retry after verification failure shows ~100% progress on a download starting from zero |
| DL-05 | Cancelling a paused download wipes the resume point but leaves the .part file on disk |
| DL-06 | A corrupt download store is rendered as "no downloads in progress" |
| DL-07 | "Start" can refuse silently (and always refuses on mobile data) |
| DL-08 | The "Verifying" row offers no cancel and no progress |
| DL-09 | Unknown-size downloads show "0% · 0 B من 0 B" forever |
| DL-10 | Process death during a download looks exactly like a user pause |
| DL-11 | Queue rejections show a raw English code inside the Arabic UI |
| DL-12 | Downloads started from Library never ask for the notification permission |
| DL-13 | The storage-warning decision uses a capacity snapshot taken when the screen opened |
| DL-14 | Metered consent does not survive the process |
| DL-15 | A completed download vanishes from the Downloads segment with no "installed" signal |
| HF-01 | The HF token is used for the probe but never for the download |
| HF-02 | Probe says "enter a token", but the token field only exists in another phase |
| HF-03 | The repo-page failure card says the *search* failed |
| HF-04 | Rate-limit state says "wait a moment" and offers only a manual retry |
| HF-05 | A BLOCKED fit verdict is a dead end with no alternative |
| CH-01 | A failed conversation save is discarded and never shown |
| CH-02 | Process death mid-answer leaves the question saved and the answer gone, silently |
| CH-03 | "Stop" saves a truncated answer as a complete one |
| CH-04 | An unreadable conversation either does nothing or disappears from history |
| CH-05 | New chat silently replaces the transcript and the only confirm copy is dead |
| DOC-01 | Two different wrong/right directions for the missing embedding model |
| DOC-02 | A failed document says only "indexing failed", with no reason |
| DOC-03 | Pending documents say "indexed automatically when available" with no way to provide it |
| DOC-04 | "Embedding model failed to load" has no reason and no retry of its own |
| MED-01 | Microphone permission permanently denied is a dead end with no route to app settings |
| GEN-01 | "Offline" and "no permission" states never say which setting to change where a feature is gated |
