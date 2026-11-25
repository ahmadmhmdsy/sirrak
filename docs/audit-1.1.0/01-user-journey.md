# 01 — User Journey (Sirrak 1.1.0)

**Owner:** journey-runner · **Device:** emulator-5554 (720×1280, 320 dpi, API 36, x86_64) until 19:07,
then read-only source verification; cold-install evidence is owned by `01b` on emulator-5556
(`build/ux-audit/shots-clean/`). **Evidence pack:** `build/ux-audit/EVIDENCE.md`, `build/ux-audit/shots/`,
`build/ux-audit/logs/`. **Method:** live capture where possible, `file.kt:line` verification for everything
that could not be captured, and an explicit interference log (below). No app code was modified.

## Executive summary (5 lines)

1. **Biggest strength:** the 1.1.0 model-control layer is honestly engineered — a four-state chip that is
   always a doorway, a typed failure taxonomy with a retry on every failed turn, one persisted download
   queue with atomic JSON writes and one active transfer, and an HF fit verdict that refuses a model
   predicted not to run. It reads as a careful product, not a demo.
2. **Biggest risk (P0):** the first failure is a misdirection. Send without a model and the app says
   «لم يُحمَّل أي نموذج بعد. استورد ملف GGUF من الإعدادات ثم حاول مجددًا.» and offers «فتح الإعدادات» —
   Settings has six doors and **none of them touches models** (appearance, language, privacy, voice,
   advanced, about). The user is sent to the one screen where the fix does not exist.
3. **Biggest risk (P1):** the good path to a model exists — Library → النماذج → «للتنزيل» lists a curated Qwen2.5 0.5B (491 MB, «يعمل») with one-tap download — but **nothing in the chat points to it**: the empty state has no model CTA, the failure CTA goes to Settings (JRN-01) and the chip is a two-step sheet; the chat while a download runs (`clean-32`) shows no sign of it, and the 1.1 GB-class transfer has no system notification on Android 13+ because POST_NOTIFICATIONS is declared but never requested.
   screens, the no-model chip is a two-step doorway (sheet, then Library), and the 1.1 GB download runs with
   no visible progress outside the app because POST_NOTIFICATIONS is declared but never requested.
4. **Biggest risk (P1):** the two numbers that decide trust are wrong or missing. The first answer took 64.5 s for 73 tokens (TTFT 26.7 s) with 26.7 s of spinner and no ETA; the meter printed «≈ 0.4–0.7 رمز/ث» while the engine logged 1.93 tok/s generation. And an Arabic-first app answered a plain Arabic question in English until the prompt was manually prefixed «أجب بالعربية فقط.»
   64.5 s for 73 tokens (TTFT 26.7 s) with 26.7 s of spinner and no ETA; the meter printed «≈ 0.4–0.7
   رمز/ث» while the engine logged 1.93 tok/s generation. And an Arabic-first app answered a plain Arabic
   question in English until the prompt was manually prefixed «أجب بالعربية فقط.»
5. **Counts:** P0 1 · P1 7 · P2 15 · P3 5 (28 findings).
   empty/answered chat, History, model states, the 18:55–18:57 generation with engine logs. Unverified live:
   Settings subpages, English pass, font scale, themes, HF probe/download, dictation, attachments — flagged
   per finding; several are covered by 01b or by the specialist reports.

## Evidence status and method

The brief assumed a fresh install, no model and one empty conversation. The device I was handed already had
one 1.12 GB model (`models/qwen2.5-1.5b-instruct-q4_k_m.gguf`, written 18:53) and one conversation, because
the handover baseline was produced one minute before this audit started (18:54:19 force-stop, 18:54:21 start).
Then a **third party outside this team** kept driving the device: 19:01:06 a root force-stop killed the app
mid-capture, it was relaunched, the model file was renamed and re-selected, and two Arabic turns I did not
type appeared (19:04:38, 19:07:05 with `PREFILL tokens=94`). The default IME is `com.android.adbkeyboard`,
i.e. scripted Arabic input. Per the Lead ruling, emulator-5554 is the founder-device, so all my interaction
stopped at 19:07 and the cold-start path moved to emulator-5556 (`01b`).

Therefore this report is a **journey analysis**:

- **live-captured on 5554** (timestamped; state may have changed under the capture): the no-model chat and
  failure, the Settings root, the model states, the History screen, one real generated answer with its
  engine statistics;
- **live-captured on 5556 by the clean runner** (32 shots in `build/ux-audit/shots-clean/`, 19:04–19:19): the no-model failure and its Settings result (`clean-06`/`07`), Library segments and the curated «للتنزيل» list (`clean-08`…`11`, `clean-31`), the HF probe/verdict/estimate (`clean-12`…`21`), the download lifecycle including a captured network failure and retry (`clean-22`…`31`), and the chat while a download runs (`clean-32`). `01b-cold-start-evidence.md` was still being written at 19:20.
- **source-verified** for every rule quoted below (`file.kt:line`), including the queue, the chip, the HF
  verdicts, the export path and the failure copy;
- **marked unverified** wherever only a live run could settle it.

### Emulator caveat for every timing number

All performance figures come from an x86_64 emulator whose llama.cpp falls back to a generic kernel —
`logcat`: `W/app.sirrak: Unexpected CPU variant for x86: x86_64`. Absolute tokens-per-second on a phone are
not represented by these numbers. The *shape* of the experience (silence before the first token, a meter that
does not match the engine, no progress while loading) is UI-level and does transfer.

## The journey as it stands (narrative)

**Install and first open.** The app opens directly into Arabic inside an English system, which is the right
promise for this product. A three-page onboarding (privacy, language, model) is skippable on every page, and
the model page offers «تنزيل النموذج الموصى به», «التجربة بدون نموذج» and «تخطّي». A user who takes the
last two lands in an empty chat that looks completely ready: the wordmark and the model chip in the header,
the promise «مساعدك الخاص، يعمل على جهازك ولا يغادره.», the privacy line «لا حساب. لا سحابة. لا تتبّع.», the
title «جرّب أن تسأل» and a suggestion card. Nothing on this screen says that the assistant cannot answer yet,
and nothing offers to get a model. This is the single most consequential gap in the journey: at the moment
the user is most willing to try, the screen is at its most convincing about a capability that does not exist.

**The first question.** The suggestion card is a one-tap **send** (`ChatScreen.kt:245-248`,
`onClick = { store.send(suggestion.prompt, scope) }`), so the very first interaction a new user can make
becomes a failed turn. The clean-runner captured exactly this: the suggested question «ما الفرق بين القياس
والاستنباط في أصول الفقه؟» sits as a user bubble, and below it, in red, the failure «لم يُحمَّل أي نموذج بعد.
استورد ملف GGUF من الإعدادات ثم حاول مجددًا.» with two text buttons, «إعادة المحاولة» and «فتح الإعدادات»
(`clean-06`). Tapping the second one opens the Settings root (`clean-07`), whose six doors are المظهر، اللغة،
الخصوصية والبيانات، الصوت، متقدم، عن سِرِّك — there is no model import, no Library link and no download CTA.
The app tells the user to import a GGUF **from Settings**, and Settings cannot do that. From here the real
path is the bottom-nav Library (hidden while the keyboard is up, `Shell.kt:78`), then its model segment, then
«مصادر أخرى» → Hugging Face, then search, then a repo, then a file, then the probe, then a download, and then
a separate load action. The journey from "I typed a question" to "I am reading an answer" is not one path; it
is a scavenger hunt whose first clue points the wrong way.

**Getting a model.** Library does this part with care: three segments (النماذج / التنزيلات / الوسائط), a model
list that distinguishes the selected model, and a Hugging Face browser that is honest about what it knows —
fit verdicts `runs / tight / wont-run / unknown`, a quantization label read from the file name, a probe that
reads the GGUF header with a ranged request capped at 12 MB (`HfServices.kt:107-116`), and a download decision
that blocks only a model predicted not to run while allowing an unverified one only with a warning
(`HfBrowseLogic.kt:108-113`). The honesty is real. What the user cannot see is everything after the decision:
the queue is durable, one-active and atomically written (`QueueFileStore.kt:25-63`), a transfer interrupted by
process death is demoted to PAUSED and never silently resumed (`DownloadBridge.kt:108-117`) — correct, but
silent: the next launch does not tell the user «the app closed while downloading, tap to resume» anywhere
except inside the downloads segment. Pause keeps the partial bytes, cancel deletes them (`DownloadQueue.kt:284-290`
vs `:334-336`), which is a real data difference the copy must carry. And the foreground service that would
show progress outside the app cannot notify on Android 13+: the manifest declares POST_NOTIFICATIONS but no
code requests it (only file-picker and audio-permission launchers exist). A 1.1 GB download with no system
progress is a trust test most users fail.

**The first answer.** When the model is loaded the chip finally says «Qwen2.5 1.5B Instruct · جاهز على
الجهاز», and the wait begins. On the device I measured a full turn: `PREFILL tokens=87`, `prefill_ms=26719.2`,
`ttft_ms=26723.8`, `generated=73`, `gen_ms=37781.9`, `tok_s=1.93`, `peak_rss_mb=1434.0`. In words: 27 seconds
in which the chat shows a spinner and nothing else, then a 73-token answer taking another 38 seconds. During
the silence there is no "reading your question" state, no elapsed time, no estimate; the composer does switch
to «إيقاف», but a new user has no idea whether the app is working or stuck. When the answer lands, the footer
prints «≈ 0.7 رمز/ث» (later «≈ 0.4 رمز/ث») — the number comes from `ChatStore.recordSpeed`, which divides
*estimated* tokens by wall time **since the request start** (`ChatStore.kt:1063-1064`), so it folds the 27 s
prefill into the speed and reports roughly a third of the engine-measured generation rate. The app is slower
than it looks here on a phone, but the shown number is not the number it claims to be, and TTFT — the only
number a waiting user feels — is never shown at all.

**Using the answer.** The answer bubble carries copy, regenerate (newest answer only) and a contributed
«الإبلاغ عن هذه الإجابة»; the actions are placed under the prose and appear only when streaming ends
(`ChatScreen.kt:297-331`). Copy works and shows «تم النسخ»; regenerate replaces the newest answer and
re-sends the question with no way back to the previous answer (`ChatStore.regenerate`, `dropLast(2)`); a
stopped answer keeps its partial text but the bubble itself is not marked as truncated — only a quiet line
below says «أوقفت الإجابة. أعد المحاولة إن شئت.» (`ChatScreen.kt:332-361`). There is no share or
select-and-long-press for a single answer; the only exit is exporting the entire conversation from the
History row menu. And while an answer streams the list always snaps to the bottom on every length change
(`ChatScreen.kt:174-188`), so reading the beginning of a long answer is impossible until generation ends;
there is no "back to latest" affordance.

**Keeping and coming back.** Conversations are persisted on disk and restored by the library; History shows
derived titles from the first question, a per-row overflow (rename, export Markdown, export JSON, delete,
conversation settings) and search. Export is honest — MediaStore Downloads under `Download/Sirrak`, a toast
«حُفظ في التنزيلات.» and a share fallback (`ConversationFiles.kt:33-42`). The header has no share or export,
so saving one good answer means walking into History and finding the row. Titles are derived, so two
conversations that start with the same question look identical until renamed. The header "new conversation"
button appears only once a conversation has messages (`ChatScreen.kt:213-221`), and the shipped
`new_chat_title` / `new_chat_body` / `new_chat_confirm` strings — which promise «ستُحذف هذه المحادثة…» — are
referenced nowhere, while the code path keeps the old conversation. After process death the model selection is
restored by **path** from SharedPreferences (`FileModelStore.restoreSelection:184-191`), so any file-level
change (a re-import, a rename) silently deselects the model: I observed exactly this on 5554 at 19:01, when the
renamed file left `sirrak_models.xml` empty and the chip said «لا يوجد نموذج» while the file was sitting in
`models/`. Settings itself is well organised — six doors, live summaries, the language door expanding in place —
but a user who follows the failure CTA into it finds nothing about models, which is the one thing they went
there to fix.

**State of the app.** This is a product with unusually honest plumbing and a broken front door. The internal
honesty (atomic queue snapshots, no silent resume, typed failures, fit verdicts that can say "will not run",
quiet styling for a user-stopped turn, a real file on disk for an export) is better than most shipping AI apps.
The journey around it is organised for someone who already knows the product: models live one layer away from
where the user is told they live, the first send is a failure, the first answer is a minute of silence with a
misleading speed number, and the Arabic-first promise is not enforced by the default answer policy. The first
ten minutes decide whether this app is kept, and the ten minutes are routed away from the model library.

---

## Findings — in journey order

### A. First open and the no-model state

### JRN-01 — The no-model failure sends the user to Settings, where no model control exists
- **Severity:** P0  **Confidence:** high  **Effort:** S
- **Where:** chat failure turn (`feature/chat/ChatScreen.kt:332-361`), copy `feature/chat/src/main/res/values/strings.xml:18-19`, destination `feature/chat/ChatPlugin.kt:87`; screenshots `clean-06-send-no-model-failure-ar.png`, `clean-07-failure-open-settings-ar.png`.
- **What happens now:** a send with no model renders, in error colour, «لم يُحمَّل أي نموذج بعد. استورد ملف GGUF من الإعدادات ثم حاول مجددًا.» with «فتح الإعدادات»; that button calls `navigator.select("settings")`, and the Settings root has six doors (appearance, language, privacy, voice, advanced, about — `deps` order 0/5/20/30/40/55) with no model, import or Library entry. `error_no_model` even names Settings as the place to import a GGUF.
- **Why it hurts:** this is the first and only recovery action a new user is offered at the exact moment the product failed them, and it leads somewhere the described fix does not exist. Trust is lost in the first minute; the real path (Library → HF) is never mentioned.
- **Fix:** make the failure copy and CTA point to the model library (`navigator.select("library")` + models segment, or push `PageIds.HF_SEARCH`), and reword to «احصل على نموذج من المكتبة». Keep «إعادة المحاولة» as the secondary action.
- **Verify:** fresh install, no model, send any question, tap the failure CTA → the model library/HF page opens with a download CTA, and the question is preserved for retry.

### JRN-02 — The empty chat promises a working assistant before any model exists
- **Severity:** P1  **Confidence:** high  **Effort:** S
- **Where:** `feature/chat/ChatScreen.kt:224-255` (empty state), strings `empty_promise` / `empty_privacy` / `suggestions_title` in `feature/chat/src/main/res/values/strings.xml`; screenshot `clean-06` (pre-failure state) and 5554 `s-1` observation 19:05.
- **What happens now:** with no model selected or loaded the chat shows the full ready-state chrome: wordmark, chip, promise «مساعدك الخاص، يعمل على جهازك ولا يغادره.», «لا حساب. لا سحابة. لا تتبّع.», «جرّب أن تسأل» and a suggestion card. The only indication of the missing capability is the small chip label «لا يوجد نموذج · غير محمّل» and the disabled-looking send button; the primary content region still invites a question.
- **Why it hurts:** the empty state is the app’s only onboarding after the skipped slides. It sells the promise and hides the prerequisite, so the first action is guaranteed to fail; users conclude the app is broken rather than unconfigured.
- **Fix:** gate the ready-state copy on `engine.status`/`store.selected`; when there is no model, show the promise once and make the primary action «احصل على نموذج (تنزيل لمرة واحدة)» plus a secondary «تجاهل الآن» — the existing copy, not new marketing.
- **Verify:** fresh install → skip onboarding model step → empty chat shows a model CTA as the primary action and a one-line explanation; after a model loads, the current promise/suggestion state appears.

### JRN-03 — The suggestion card sends immediately, so the first tap becomes a failed turn
- **Severity:** P2  **Confidence:** high  **Effort:** S
- **Where:** `feature/chat/ChatScreen.kt:245-248` (`onClick = { store.send(suggestion.prompt, scope) }`); evidence `clean-06` where the suggested question appears as a sent user bubble followed by the failure.
- **What happens now:** tapping the only prominent CTA on the empty screen submits the prompt without putting it in the composer, so the user cannot edit, cancel or even see what will be asked before it is asked.
- **Why it hurts:** it converts an exploratory tap into a permanent failed turn in the transcript, and it removes the small but meaningful control of reviewing your first question.
- **Fix:** fill the composer (and focus it) with the suggestion instead of sending; keep send explicit.
- **Verify:** tap a suggestion on an empty conversation → text appears in the composer and no request starts until send is tapped.

### JRN-04 — Skipping the model step leaves no standing status or reminder anywhere
- **Severity:** P2  **Confidence:** high  **Effort:** S
- **Where:** `feature/models/OnboardingScreen.kt:58,89-92,179-207` (`onboarding_model_skip`, `onboarding_model_try_without`); Settings summaries `clean-07`.
- **What happens now:** onboarding can be skipped per page and `onboardingStore.complete(skipped=true)` is all that is persisted. Nothing in Settings, Library or the chat header tells the user later that the setup is incomplete; the only trace is the chip label and the failure.
- **Why it hurts:** a user who skipped "for now" has no way back to the decision, and no way to see how much is missing (model size, free space, what it enables).
- **Fix:** a dismissible one-line setup banner on chat and a Settings/Library row «النموذج غير مُهيّأ — احصل على نموذج» until a model is installed; reuse the chip’s state, do not add a second truth.
- **Verify:** skip the model step, reopen the app → a visible "setup incomplete" affordance appears in chat and Library; it disappears after install.

### B. Getting a model (Library, Hugging Face, download queue)

### JRN-05 — The shortest model path exists but nothing in chat points to it; the HF route is 8–12 interactions
- **Severity:** P1  **Confidence:** high  **Effort:** M
- **Where:** curated «للتنزيل» section inside Library → النماذج (`app/src/main/java/app/sirrak/LibraryScreen.kt`; `clean-31`, `clean-08`, `clean-11`), the HF route `LibraryScreen.kt:247` → `HfPlugin.kt:104-140` → `HfBrowseLogic.kt:108-113` (`clean-12`…`clean-21`), the chip doorway `app/src/main/java/app/sirrak/Panels.kt:83,93`; `clean-32` (chat while a download runs).
- **What happens now:** two routes exist. The good one: Library → النماذج → «للتنزيل» lists curated small models with fit badges — `clean-31`: Qwen2.5 0.5B Instruct · Q4_K_M · 491 MB «يعمل», LFM2.5 350M · 229 MB «غير متحقَّق» with an explicit not-checked note — and starts a download with one tap, one transfer at a time. The slow one: «مصادر أخرى» → Hugging Face → search → repo → file → probe → verdict → download, 8–12 interactions. The chat offers neither: the empty state has no model CTA (JRN-02), the failure CTA goes to Settings (JRN-01), and nothing on the chat names Library or says a curated download exists.
- **Why it hurts:** the product already ships a two-minute path to a first answer and a new user cannot find it from the screen that failed them; the only in-context route (the chip) is a two-step sheet (JRN-06) and the failure route is wrong (JRN-01). Discoverability, not capability, costs the first answer.
- **Fix:** the chat no-model primary action should open Library → النماذج with «للتنزيل» in view (or push the curated sheet directly), keep HF behind «مصادر أخرى» for power users, and return to the waiting question with a "download running — you can ask when it finishes" state.
- **Verify:** from a fresh empty chat the first CTA reaches the curated list, the recommended 491 MB model for a 491 MB download, and tapping the chip while it downloads shows progress rather than a dead state.
### JRN-06 — The no-model chip is a two-step doorway, not the claimed one-tap route
- **Severity:** P1  **Confidence:** high  **Effort:** S
- **Where:** `app/src/main/java/app/sirrak/Panels.kt:70-97`: `ModelChip(onClick = { open = true })` then `ModelDoorwaySheet(... onOpenLibrary = { navigator.select("library") })`; observed chip states: 5554 19:01 «لا يوجد نموذج · غير محمّل», 19:05 «Qwen2.5 1.5B Instruct · جاهز على الجهاز»; `clean-06`.
- **What happens now:** the round-1 record claims the no-model state acts and routes to Library→models in ≤1 tap. In 1.1.0 the chip always opens a modal bottom sheet; the Library is a second tap inside it, and from Library the model segment (not the download page) is where the user lands. The ready-state chip opens the model passport, which is correct.
- **Why it hurts:** at the one moment the chip is the only in-context entry point to models, it costs two taps through a sheet whose content the user did not ask for; combined with the hidden bottom bar under the keyboard, the shortest visible route out of the no-model state is the misdirected Settings button (JRN-01).
- **Fix:** when `EngineStatus.Unloaded`/`Failed` and nothing is selected, make the chip itself navigate to Library→models (or push HF search); keep the sheet for the ready/loading passport.
- **Verify:** with no model, tap the chip once → the model library is on screen; with a loaded model, tap once → passport sheet.

### JRN-07 — A 1.1 GB download has no notification on Android 13+ (POST_NOTIFICATIONS never requested)
- **Severity:** P1  **Confidence:** high  **Effort:** S
- **Where:** `app/src/main/AndroidManifest.xml` declares `POST_NOTIFICATIONS` and `DownloadService` (dataSync, `app/src/main/java/app/sirrak/DownloadService.kt:106-176`) builds a progress notification; no permission request exists in the codebase — the only `rememberLauncherForActivityResult` uses are file pickers (`LibraryScreen.kt:122`, `MediaLibrarySection.kt:134-140`, `ChatScreen.kt:109-142`) and `RECORD_AUDIO` (`DictationControls.kt:84`). `clean-32` shows the chat while a download runs: no progress, no mention of the transfer.
- **What happens now:** on API 33+ the foreground-service notification is suppressed because the permission was never granted (never even asked), so the only download progress is the Library→التنزيلات row. If the user switches away — the normal thing to do during a 400 MB–1.1 GB transfer — the app looks idle and the transfer looks stopped.
- **Why it hurts:** users either wait on the downloads screen doing nothing, or kill the app believing it froze; both cost the journey. A silently-running large transfer without system-level progress is also a battery/data trust issue.
- **Fix:** request POST_NOTIFICATIONS with a rationale before the first enqueue, and give the notification pause/cancel actions plus percentage; if denied, keep an in-app banner that says progress continues and where to look.
- **Verify:** API 33+ device, start a model download, press Home → a progress notification with the file name and percentage is present and its cancel action stops the transfer.

### JRN-08 — Resume-after-process-death is correct but invisible where the user is
- **Severity:** P2  **Confidence:** high  **Effort:** S
- **Where:** `app/src/main/java/app/sirrak/downloads/DownloadBridge.kt:97-117` (persisted ACTIVE → PAUSED, «only after a tap»), rendered only in the Library downloads segment; `core/models/DownloadQueue.kt:147-157` documents the same rule.
- **What happens now:** after the process dies the row is paused with its byte count intact and waits for an explicit resume — the right behaviour. But the next launch shows the normal chat, with no indication that a transfer is stopped, no app-level badge on Library, and no message on the downloads row beyond its paused state. The rule itself is stated in the downloads segment («تنزيل واحد في كل مرة. تُتابع الملفات من حيث توقفت بعد عودة التطبيق.», `clean-28`), so the information exists — one segment too deep.
- **Why it hurts:** the user cannot distinguish "paused because I paused" from "paused because the app died", and the largest thing they started has quietly stopped.
- **Fix:** on load, if any row was demoted from ACTIVE, show a one-line «توقّف تنزيل النموذج عند إغلاق التطبيق — متابعة» entry point on the chat (or a snackbar with a resume action), and a numeric badge on the Library nav item while a transfer is paused/running.
- **Verify:** start a download, force-stop the app, relaunch → a resume affordance is visible without navigating to Library; tapping it continues from the saved offset.

### JRN-09 — Pause and cancel have different data outcomes but the UI must carry that difference
- **Severity:** P2  **Confidence:** medium  **Effort:** S
- **Where:** `core/models/DownloadQueue.kt:284-290` (`pause` keeps bytes so resume continues the .part file) vs `:334-336` (cancel discards the partial offset, «a download the user refused»); copy source `feature/models/src/main/res/values/strings.xml` (`models_reason_*`, action labels).
- **What happens now:** both are one-tap actions on the row and both end the visible progress; only the queue comments and (partly) the labels distinguish that pause keeps 800 MB of work and cancel throws it away. Live copy for the two labels was not captured on 5554 (interference) — verify wording against the strings before shipping.
- **Why it hurts:** with a slow connection a mistaken «إلغاء» costs the whole download, and the user cannot know that from the button.
- **Fix:** label cancel with its consequence («إلغاء وحذف 000 MB») and put pause first; keep a resume-from-zero confirmation for cancel when more than a few MB were downloaded.
- **Verify:** download 50%+, cancel → confirmation names the lost bytes; pause → resume continues from the same percentage.

### JRN-10 — The fit estimate is strong; it does not say how long the model will take
- **Severity:** P3  **Confidence:** high  **Effort:** S
- **Where:** `clean-16` (quant selected, verdict «يُتوقَّع أنه يعمل»), `clean-20` (probe + estimate: «فحص الترويسة (حتى 12 م.ب)», 13 MB read over a Range request, «قراءة جزئية داخل حد 12 م.ب؛ بعض الحقول غير متاحة», «من ترويسة الملف، لا من بيانات الناشر», file 491 MB, KV 25 MB, overhead ≈315 MB, total ≈831 MB, available 2166 MB, free 5.36 GB, suggested ctx 16384), curated badges `clean-31`; `HfBrowseLogic.kt:108-113`.
- **What happens now:** the probe and the estimate are unusually honest and concrete: the app states what it read, what it could not read, where the numbers came from, and the estimated memory total against this device. What is not set is the experience — no expected load time and no expected tokens-per-second, so «تقديري» numbers come without a range.
- **Why it hurts:** the decision this panel gates is a 229–491 MB download followed by a first answer whose speed is the actual surprise (JRN-12); one line of "expect about N s to load and about M s to the first answer" would set the expectation the rest of the journey must meet.
- **Fix:** derive expected load/first-answer time from the existing memory and quant numbers (or from measured engine stats of previous runs), label it as an estimate, and reuse it on the curated «للتنزيل» rows.
- **Verify:** the HF estimate panel and the curated rows show a load-time / first-answer expectation next to the memory estimate.
### JRN-11 — The loaded model loses its quantization in the chat header
- **Severity:** P2  **Confidence:** high  **Effort:** S
- **Where:** `app/src/main/java/app/sirrak/Panels.kt:70-79` (Ready label is `current.label`), observed chip names 5554 19:01 «qwen2.5 1.5b instruct» and 19:05 «Qwen2.5 1.5B Instruct» for the same 1.12 GB file; quantization is read only from the HF file name (`HfBrowseLogic.kt:59-71`).
- **What happens now:** the chip and the model passport show the display name derived from the file name; Q4_K_M — the single fact that predicts speed and memory — is missing from the chat header (it is present on the Library row).
- **Why it hurts:** when the user compares two models (or wonders why an answer is slow) the header gives them no quant/context/size hint, so the visible label cannot explain the experience.
- **Fix:** show the quantization (and size) in the chip’s secondary label for the ready/loading state, sourced from the installed model, not the file name.
- **Verify:** load a Q4_K_M model → chip shows «Q4_K_M · 1.12 GB» (or similar) without opening the passport.

### C. The first answer and the result

### JRN-12 — 26.7 seconds of silence before the first token, with no TTFT state and no ETA
- **Severity:** P1  **Confidence:** high (emulator-measured; shape transfers)  **Effort:** M
- **Where:** engine statistics from `build/ux-audit/logs/logcat-full-1901.txt` at 18:57:50: `STATS prompt_tokens=87 dropped_turns=0 prefill_ms=26719.2 ttft_ms=26723.8 generated=73 gen_ms=37781.9 tok_s=1.93 peak_rss_mb=1434.0`; UI during that time is the streaming state of `feature/chat/ChatScreen.kt` with only an indicator (no text), screenshots `16-concurrent-generation-1907.png`, `20-library-models-ar.png`.
- **What happens now:** after send, the app shows a spinner-like indicator for the whole prefill (27 s here) and then starts streaming. There is no "reading your question" label, elapsed timer, expected time, or any signal about how much of the wait is prompt processing versus generation.
- **Why it hurts:** a minute of unexplained silence on first use is indistinguishable from a hang; users stop the answer, close the app or conclude the phone cannot run it. The app knows the prompt is being processed and does not say so.
- **Fix:** an explicit prefill state («أقرأ سؤالك…») with elapsed seconds, replaced by a first-token moment (show the caret moving), and keep the stop button visible from second one; consider a smaller default model/quant for the recommended download.
- **Verify:** send a prompt on a cold engine → the state text changes when prefill ends and the elapsed timer matches the logged `ttft_ms` within tolerance.

### JRN-13 — The speed meter is not generation speed, and TTFT is never shown
- **Severity:** P1  **Confidence:** high  **Effort:** S
- **Where:** `feature/chat/ChatStore.kt:1063-1064` («Honest approximation: tokens estimated from the text, wall time from the request start»), `feature/chat/ChatMeter.kt` renders `meter_speed ‎≈ %1$s رمز/ث` only when not streaming; observed «≈ 0.7 رمز/ث» (5554 18:57) and «≈ 0.4 رمز/ث» (19:07) against engine `tok_s=1.93`.
- **What happens now:** the footer divides estimated prompt+answer tokens by total wall time, including the prefill, and prints it as «رمز/ث». For the measured turn that is ~2.3× below the engine’s generation rate; for long prompts it can be far worse. The engine’s true TTFT (26.7 s) is not surfaced anywhere.
- **Why it hurts:** this is the app’s only quantitative trust signal, and it is wrong in the direction that makes the product look worse than it is — a user comparing models or phones is misled, and a user who wants to know "how long will this take" gets no answer at all.
- **Fix:** compute speed from generation start (exclude prefill), show `TTFT 27 s · 1.9 tok/s` as two labelled numbers, and drop the «≈» ambiguity by labelling the token count as estimated.
- **Verify:** one answered turn shows TTFT and generation rate that match the engine log within 10%, with the prompt-processing time excluded from the rate.

### JRN-14 — The token budget meter appears on a failed turn and never explains the limit
- **Severity:** P2  **Confidence:** high  **Effort:** S
- **Where:** `feature/chat/ChatScreen.kt:370-374`, `feature/chat/ChatMeter.kt` (hidden only when `budget.estimatedTokens <= 0 && speed == null`); observed «81 / 1536 رمز تقديري» with a no-model failure in `clean-06`, and «128/114/99/84 / 1536» across 5554 turns.
- **What happens now:** the counter renders above the composer on the very turn that never reached a model, and the denominator 1536 is unexplained — no copy says what happens at the limit (truncation? refused? summarisation?).
- **Why it hurts:** on a failed turn it is a misleading detail that looks like progress; in general an unexplained budget makes the user hoard questions or fear that longer questions will be cut.
- **Fix:** hide the meter when the turn failed before generation; add a one-line explanation on first appearance and a warning state near the limit; name what happens at the limit.
- **Verify:** a failed pre-generation turn shows no meter; a long conversation shows an explained warning before the budget is exhausted.

### JRN-15 — Arabic-first product answered a plain Arabic question in English
- **Severity:** P1  **Confidence:** high (observed)  **Effort:** S
- **Where:** conversation `c-f1b98a0be1df` in `files/conversations/index.json` (preview `Dear [Recipient],` for the Arabic prompt «اكتب رسالة اعتذار قصيرة عن حضور اجتماع.») and 5554 dump/shot at 19:05 showing the same English answer under the Arabic bubble; the same question prefixed «أجب بالعربية فقط.» produced an Arabic answer (shot `16-concurrent-generation-1907.png`); system-prompt plumbing `app/src/main/java/app/sirrak/AppModelControlService.kt:194-213` resolves app/global/per-model prompts, and the chat supports a per-model/global prompt. The default is configurable, i.e. nothing guarantees Arabic.
- **What happens now:** the shipped default answer policy does not constrain language, so a small model answers an Arabic question in English. The user must know to add «أجب بالعربية فقط.» or find a system-prompt setting to get the product’s promised language.
- **Why it hurts:** for an Arabic-first assistant this reads as a broken promise at the very first answer; it also changes the tone/format of the answer and makes RTL rendering, tokenization and meter numbers inconsistent with what the user expects.
- **Fix:** when `AppLanguage` is Arabic, ship a default system prompt that answers in Arabic unless asked otherwise (and the mirror for English), surfaced in the conversation settings as an editable «لغة الإجابة»; do not rely on the model.
- **Verify:** fresh install in Arabic, ask an Arabic question with no other instruction → the answer is Arabic; switching the app language changes the policy; the setting is visible in chat settings.

### JRN-16 — Answer actions are copy and regenerate only; no share, no selection, regenerate destroys the previous answer
- **Severity:** P2  **Confidence:** high  **Effort:** M
- **Where:** `feature/chat/ChatScreen.kt:297-331` (`AnswerActions` with `copyLabel`, `regenerateLabel` only for `isNewest`, plus `SlotRow(Slots.ChatAnswerActions)`); `feature/chat/ChatStore.kt:530` (`regenerate` replaces the newest answer, history `dropLast(2)`); conversation share exists only in History (`ChatHistory.kt`, `share_title`).
- **What happens now:** a finished answer offers copy and regenerate on the newest turn only, plus contributed actions. There is no share of a single answer, no long-press selection, and regenerate swaps the answer out with no visible "previous answer" history.
- **Why it hurts:** the most natural post-answer actions (send this to someone, quote a sentence, try again but keep the first) are missing or destructive; the user’s only export path is a file from the History menu, several taps away.
- **Fix:** add share/select to the answer action row (it already has a contribution slot), keep the previous answer behind a «الإجابة السابقة» toggle when regenerating, and allow regenerate on the turn itself with a confirmation that names what will be replaced.
- **Verify:** a long-press selects and shares an answer as text; regenerating shows both answers and lets the user restore the first; copy/regenerate remain where they are.

### JRN-17 — A stopped answer looks like a finished one
- **Severity:** P2  **Confidence:** high  **Effort:** S
- **Where:** `feature/chat/ChatStore.kt:568-587` (partial text kept as the answer), `feature/chat/ChatScreen.kt:332-361` (STOPPED gets a quiet line «أوقفت الإجابة. أعد المحاولة إن شئت.» below, but the bubble itself is unstyled and the answer actions are rendered normally).
- **What happens now:** pressing stop keeps the partial text as the assistant answer; only the small grey line under it says it was stopped, and reopening the conversation later shows the same fragment with no marker at all (failureReason is persisted with the item, so re-reading should keep the line — but nothing on the bubble or in History marks it).
- **Why it hurts:** a truncated answer read later looks authoritative; the user may copy or rely on a sentence the model never finished, and does not know a retry would produce more.
- **Fix:** mark the bubble itself («توقّفت — إجابة جزئية») and offer «متابعة» where possible; in History, mark the conversation as containing a partial answer.
- **Verify:** stop mid-answer, leave and reopen the conversation → the partial answer is visibly marked and retryable.

### JRN-18 — The transcript always snaps to the bottom while streaming
- **Severity:** P2  **Confidence:** high  **Effort:** S
- **Where:** `feature/chat/ChatScreen.kt:174-188`: `LaunchedEffect(store.items.size, streamingLength) { if (items.isNotEmpty()) { listState.scrollToItem(lastIndex); listState.scrollBy(10_000f) } }`.
- **What happens now:** every streaming update scrolls to the last item and then by a large offset, so scrolling up during generation is immediately undone; there is no "jump to latest" control and no indication that new text is still arriving below.
- **Why it hurts:** for long answers the user cannot start reading from the top while the model continues, which is the natural reading habit; on a slow model (JRN-12) they are forced to watch the end of a paragraph they have not started.
- **Fix:** auto-follow only while the user is within a small distance of the bottom, pause following on manual scroll, and show a floating «أحدث» button when detached.
- **Verify:** start a long answer, scroll up mid-stream → the view stays where the user put it and a "latest" affordance appears; tapping it returns to the stream.

### JRN-19 — The send control in an RTL composer reads as a back arrow
- **Severity:** P3  **Confidence:** high  **Effort:** S
- **Where:** composer send button in `feature/chat/ChatScreen.kt` (content-desc `action_send`), observed in `clean-06`, `16-concurrent-generation-1907.png`, `20-library-models-ar.png`: a filled circular button on the left of the RTL composer whose icon is a left-pointing arrow/plane.
- **What happens now:** in RTL the mirrored send glyph points in the same direction as back/navigation, and it sits where the "back" affordance would be in LTR-trained eyes; the icon is also the only filled button on the screen.
- **Why it hurts:** new users hesitate at exactly the moment they must act; a mistaken tap closes the keyboard instead of sending (the true behaviour is send, so the hesitation costs a tap and a doubt).
- **Fix:** use a direction-neutral send glyph (paper plane pointing up/right in both directions) or an explicit Arabic label «إرسال» in the button; keep the filled treatment.
- **Verify:** RTL screenshot review: the send affordance is not confusable with the back arrow; TalkBack announces «إرسال».

### D. Keeping the result and coming back

### JRN-20 — The header new-chat button is conditional while shipped copy promises deletion
- **Severity:** P2  **Confidence:** high  **Effort:** S
- **Where:** `feature/chat/ChatScreen.kt:213-221` (new-chat `IconButton` only when `store.items.isNotEmpty()`), `feature/chat/ChatStore.kt:589-640` (`clear()` with a library calls `newConversation()` and keeps the old conversation), strings `new_chat_title` / `new_chat_body` / `new_chat_confirm` in `feature/chat/src/main/res/values/strings.xml` (defined, referenced nowhere; body says the conversation will be deleted).
- **What happens now:** the header has no new-chat control on an empty conversation (nothing to leave, so acceptable) and «محادثة جديدة» on a non-empty one; it silently starts a fresh conversation and keeps the old one. The shipped confirmation copy, which has never been wired, states the opposite («ستُحذف هذه المحادثة…»).
- **Why it hurts:** code and copy disagree about a destructive-sounding action; if the strings are ever wired the app will lie about data loss, and until then users cannot tell whether starting a new chat destroys the current one — a hesitation at the exact moment they want to move on.
- **Fix:** delete the unused destructive strings, or wire the button to the actual keep semantics with copy that says «تبدأ محادثة جديدة؛ محادثتك الحالية محفوظة في السجل».
- **Verify:** tap «محادثة جديدة» → the previous conversation is still in History, and no copy in the app claims it was deleted.

### JRN-21 — Conversation identity is derived, duplicated and only editable from History
- **Severity:** P2  **Confidence:** high  **Effort:** S
- **Where:** `feature/chat/ChatHistory.kt:82` (`fallbackTitle = stringResource(R.string.nav_chat)`), derived titles from the first question (observed `index.json` `derivedTitle` values), rename only in the row overflow; `feature/chat/ChatHistory.kt` (`history_search`, `history_messages %1$d رسالة`).
- **What happens now:** until the first message a conversation has no title and shows «المحادثة»; after it, the title is the first question truncated. Two conversations started from the same suggestion are indistinguishable, and renaming requires entering History and opening the row menu.
- **Why it hurts:** returning to a task among several conversations is the main reason History exists, and at that moment rows can be identical; the user must open each one to find the answer they remember.
- **Fix:** default a title from the first answer when the question is a duplicate (or append a date), and allow rename from the conversation header (long-press title) without leaving chat.
- **Verify:** start two conversations from the same suggestion → the History rows are distinguishable; a title can be renamed from the chat header.

### JRN-22 — Export and share of the result sit four taps deep in History
- **Severity:** P2  **Confidence:** high  **Effort:** S
- **Where:** `feature/chat/ChatHistory.kt` row overflow (export Markdown / export JSON / share via fallback), `feature/chat/ConversationFiles.kt:19-42` (`saveToDownloads` → MediaStore `Download/Sirrak`, toast `export_saved`, share fallback), observed export copy in `feature/chat/src/main/res/values/strings.xml`.
- **What happens now:** to keep or send one answer the user must open History, open the row menu, export or share — and the export is the whole conversation, not the answer. The chat header has no export/share action.
- **Why it hurts:** "this answer is useful, keep it/send it" is the most common post-answer intent, and it costs leaving the result; the file-based export then lands in Downloads with no in-app confirmation beyond a toast.
- **Fix:** add share and export to the conversation header next to the model chip, and make the answer-level share/select available (JRN-16) so a single answer can leave the app as text.
- **Verify:** from an answered conversation, share the transcript and copy one answer without entering History; exported file appears under Downloads/Sirrak with the toast shown.

### JRN-23 — Model selection is stored by file path, so any file-level change silently deselects the model
- **Severity:** P2  **Confidence:** medium (observed after third-party file change)  **Effort:** M
- **Where:** `app/src/main/java/app/sirrak/FileModelStore.kt:184-191` (`restoreSelection` reads `selected_model_path` and clears it when the file is not a readable GGUF), observed on 5554 at 19:01: the model file was renamed to `models/Qwen2.5-1.5B-Instruct.gguf`, `shared_prefs/sirrak_models.xml` became `<map />`, the chip said «لا يوجد نموذج · غير محمّل» while the file was present, and `model_keys.json` still registered the old path.
- **What happens now:** the selection is a path string; a rename, a re-import that changes the file name, or a restore that moves files leaves the app with a model on disk and no selection. I could not tell whether the rename was the app’s own adoption path or the third party’s import (the device was being driven), so this is flagged medium: the code makes the outcome inevitable for any file-level change, whatever caused it.
- **Why it hurts:** the user sees "no model" while 1.12 GB sit in storage, and the only recovery is manual reselection; the fingerprint registry can also keep stale entries for a path that no longer exists.
- **Fix:** store a stable model identity (fingerprint/GGUF id) rather than a path, migrate the existing preference on load, and prune `model_keys.json` entries whose file is gone; when a single readable GGUF exists and the selection is stale, select it instead of clearing.
- **Verify:** rename a selected model file outside the app, relaunch → the app still resolves the model (or migrates the selection) instead of showing "no model"; stale fingerprint entries are removed.

### JRN-24 — Process-death recovery exists for the transcript but the model state is not explained
- **Severity:** P2  **Confidence:** medium  **Effort:** S
- **Where:** `feature/chat/ChatStore.kt` restore on init, failure reason `PROCESS_LOST` → `R.string.error_engine_process_lost` (`feature/chat/ChatScreen.kt:340`), engine reload path `app/src/main/java/app/sirrak/LlamaCppPlugin.kt:30` (selecting a model loads it; loading is asynchronous); `EngineStatus.Loading` chip label (`Panels.kt:72`).
- **What happens now:** after the app process dies, the conversation is restored and an interrupted turn gets a "engine process lost" failure with retry — good — but no surface tells the user the model is being loaded again (the chip goes through Loading with no elapsed/progress), and the restored chip can be «لا يوجد نموذج» until the load completes. Live verification of the full death cycle was cut short by the device takeover.
- **Why it hurts:** the two recoveries (transcript and model) are visible to different degrees, so a returning user sees their conversation back but no explanation for why the next send is (again) slow.
- **Fix:** one recovery banner after an abnormal exit that names both what was restored and what is loading, with a retry on the last turn; keep the chip in Loading until the engine is ready.
- **Verify:** force-stop while a model is loaded, relaunch → conversation is back, chip shows an in-progress load, the last turn offers retry, and the first send after recovery works.

### E. Honesty of the journey surface

### JRN-25 — The empty-state privacy promise sits next to an internet-dependent model download
- **Severity:** P3  **Confidence:** high  **Effort:** S
- **Where:** `feature/chat/src/main/res/values/strings.xml` (`empty_promise`, `empty_privacy`), rendered `feature/chat/ChatScreen.kt:224-255`; the model path it does not mention is `LibraryScreen.kt:247` → `HfPlugin.kt:104` (Hugging Face over the network).
- **What happens now:** «لا حساب. لا سحابة. لا تتبّع.» is literally true — inference is local — but the first thing the product actually does is download from a remote host, and no copy on the promise screen says the one-time download is the only network step.
- **Why it hurts:** the promise is the app’s main differentiator; a user who reads it as "works offline, nothing leaves the device" then sees a Hugging Face search will suspect the claim. Honesty is preserved by one clause, not by silence.
- **Fix:** add «الاستخدام كلّه على الجهاز؛ التنزيل الأول للنموذج هو الاتصال الوحيد» to the empty/privacy copy or to the model CTA (JRN-02).
- **Verify:** copy review: every network-touching step (model download, HF search) is named on the surface where the local-only promise is made.

### JRN-26 — Settings door summaries leak internal vocabulary and the root has no models entry
- **Severity:** P2  **Confidence:** high  **Effort:** S
- **Where:** `clean-07` Settings root: «الصوت · لا يوجد نموذج • نقرة», «متقدم · البحث الدلالي غير مهيّأ», «الخصوصية والبيانات · متّصل عند الطلب • سجل على الجهاز»; doors are contributed with live summaries (`core/runtime/SettingsDoors.kt:15-51`).
- **What happens now:** the Settings root is genuinely well organised as six doors with live values, but the voice door’s summary «نموذج · نقرة» and «البحث الدلالي غير مهيّأ» read like internal status codes, and — the larger point — a user hunting for models in Settings finds no door at all.
- **Why it hurts:** the root is the app’s status screen; status that reads as jargon is not useful, and the absence of a model door contradicts the failure CTA (JRN-01) that sends people here.
- **Fix:** rewrite the voice/advanced summaries as user language («الإملاء يحتاج نموذجًا — احصل عليه») and add a model door that mirrors the Library model state (or remove the Settings CTA in JRN-01).
- **Verify:** Settings root review with a first-time user: every summary is understandable without knowing the internals, and the model state is reachable from the screen the failure sends them to.

### JRN-27 — First-run "setup complete" is a single flag with no way back
- **Severity:** P3  **Confidence:** high  **Effort:** S
- **Where:** `feature/models/OnboardingScreen.kt:89-92` (`onboardingStore.complete(skipped)`), `shared_prefs/sirrak_onboarding.xml` observed at baseline (completed + skipped true, `completed_at` timestamp); no "show onboarding again"/"reset setup" entry in Settings (doors list above).
- **What happens now:** onboarding runs once and is never reachable again; a user who skipped the model step cannot replay the explanation, and the only trace of "skipped" is the boolean.
- **Fix:** add «إعادة العرض التمهيدي» (or «كيف يعمل سِرِّك») in About/Advanced that replays the model slide with the download CTA, and keep the skip state to trigger the setup banner (JRN-04).
- **Verify:** Settings → the replay entry exists and opens the model onboarding step; skipping again returns to the same state.

### JRN-28 — The same model has three names: curated label, downloads row, chat chip
- **Severity:** P3  **Confidence:** high  **Effort:** S
- **Where:** curated list «Qwen2.5 0.5B Instruct · 0.5B · Q4_K_M · 491 MB» (`clean-31`); downloads row `qwen2.5-0.5b-instruct-q4_k_m.gguf` with «تعذّر التنزيل: انقطع الاتصال.» (`clean-28`); chat chip «Qwen2.5 1.5B Instruct · جاهز على الجهاز» at 5554 19:05 vs the same file labelled «qwen2.5 1.5b instruct» at 19:01; names derive from the file name (`HfBrowseLogic.kt:59-71`, `FileModelStore.scan`).
- **What happens now:** before download the model is a friendly name plus size and quant; once the transfer starts the row is the raw file name; once loaded the chip is a title-cased form of that file name. The user has to recognise one model across three labels, and a renamed or re-imported file changes the name they learned.
- **Why it hurts:** in the captured failure (`clean-28`) the row is least readable exactly when the user decides whether to retry or remove; and the chip label can silently change after any file-level change (JRN-23).
- **Fix:** carry a display name id alongside the file name; show the friendly name plus quant and size in the downloads row and the chip, with the raw file name as a secondary line only.
- **Verify:** start the recommended download → the row keeps the curated name; load the model → the chip shows the same name plus quant; rename the file → the chip name does not change.

---

## Appendix A — measured numbers (raw logs under `build/ux-audit/logs/`)

| Measurement | Value | Source |
|---|---|---|
| Installed model size | 1,117,320,736 B (1.12 GB), `models/Qwen2.5-1.5B-Instruct.gguf` | `run-as` listing, 19:02 |
| Model load time | 1,273 ms (`MODEL_LOADED ms=1273 n_ctx=2048 n_batch=512 n_threads=4 vision=0`) | logcat 18:55:14 |
| First-turn prefill + TTFT | 26,719.2 ms prefill / 26,723.8 ms TTFT, 87 prompt tokens | logcat 18:57:50 |
| First-turn generation | 73 tokens in 37,781.9 ms = 1.93 tok/s | logcat 18:57:50 |
| Peak memory (engine) | 1,434.0 MB RSS during the turn | logcat 18:57:50 |
| Second observed turn | `PREFILL tokens=94` at 19:07:05.917, UI meter «≈ 0.4 رمز/ث» | logcat, shot `16` |
| Token meter values seen | «128 / 1536», «114 / 1536», «99 / 1536», «84 / 1536», «81 / 1536» | 5554 dumps 18:55–19:07, `clean-06` |
| App cold start (device logcat, other sessions) | `Displayed ... +4s87ms`, `+5s134ms`, `+5s231ms`, `+5s302ms` | logcat 01:04–01:05 (indicative only) |
| Chip states observed | «لا يوجد نموذج · غير محمّل» (19:01, `clean-06`), «Qwen2.5 1.5B Instruct · جاهز على الجهاز» (19:05) | dumps + shots |

Not measured by me (device taken away): `am start -W` warm/cold medians, `dumpsys gfxinfo` framestats, `dumpsys meminfo` app summary. `perf-motion-auditor` owns startup/jank; the only app-side numbers I can stand behind are the engine STATS lines above, measured during a real turn on 5554.

## Appendix B — interference log (5554, third-party driver, confirmed by Lead)

| Time | Event | Consequence for this report |
|---|---|---|
| 18:54:19 / 18:54:21 | `Force stopping app.sirrak ... from pid 10073`, then `am start` | handover baseline; model file created 18:53, one minute before the audit |
| 19:01:06.404 | `Force stopping app.sirrak ... from pid 11019`; History screen disappeared under me | `12-history-ar.png` capture remains valid (taken 18:59:19); later probe invalidated |
| 19:01:08 | root `am start` → PID 11034 | app relaunched mid-session |
| 19:01:0x–19:02 | model file renamed, `sirrak_models.xml` rewritten, `model_keys.json` left stale | chip showed «لا يوجد نموذج» while the file existed; used only as JRN-23 medium-confidence evidence |
| 19:01:27 | new active conversation `c-f1b98a0be1df` | earlier conversation no longer active; nothing deleted by me |
| 19:04:38 / 19:07:05 | two Arabic turns I did not type, answered En/Ar; `PREFILL tokens=94` | used only as evidence for JRN-15 (language) and the meter (JRN-13) |
| 19:01:30 | my `uiautomator dump` crashed (`UiAutomationService ... already registered`) | driver patched at 19:05: delete remote+local XML, retry 3x |
| 19:07 | Lead ruling: 5554 is the founder-device; all my device interaction stopped | cold start evidence moved to 5556 (`01b`, `build/ux-audit/shots-clean/`) |

## Appendix C — what this report does not cover (and who does)

- **Cold install → first run on a clean device:** `01b-cold-start-evidence.md` (emulator-5556, `build/ux-audit/shots-clean/`). My JRN-01/02/03/04 are source-verified and corroborated by `clean-06`/`clean-07`, but 01b owns the authoritative sequence.
- **Per-screen visual/hierarchy critique, all Settings subpages:** `02-screens-ia.md`. I did not capture Settings subpages live (device taken away): appearance (theme, font scale, theme pack), privacy sheets (network audit, AI reports), voice, advanced (RAG/search), about/licences, and the Library model-detail page are **unverified** here.
- **Answer interaction detail (selection, long-press, keyboard, citations):** `03-result-interaction.md`. JRN-16/17/18 are journey-level statements; the interaction report should settle the exact long-press behaviour, which I could not capture.
- **Accessibility, RTL mirroring, font scale, AR/EN string parity:** `04-a11y-i18n.md`. The 130% font runs and the English pass were not executed by me; the clean runner may or may not cover them.
- **Empty/loading/error/offline states over the whole app:** `05-states-errors.md`. JRN-08/24 are the process-death slice.
- **Startup, streaming smoothness, jank, memory:** `06-performance-motion.md`. Appendix A gives the only engine-measured numbers on a real turn; the emulator caveat applies.
- **Copy quality and claims (README/store vs app, Arabic naturalness):** `07-copy-claims.md`. JRN-15/20/25/26 are journey consequences of copy, not the copy audit itself.
