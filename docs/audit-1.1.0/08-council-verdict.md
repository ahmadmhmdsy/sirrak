# 08 — Council verdict: adversarial review of the Sirrak 1.1.0 audit

**Council-reviewer:** perf-motion-auditor (reassigned by the Lead) · **Date:** 25 Nov 2026
**Target:** Sirrak 1.1.0 (versionCode 14), debug build at the audited tree; device evidence from `build/ux-audit/` (5554 audit install, 5556 clean install, 44-shot clean pack).
**Inputs:** 00-brief; area reports 01-user-journey, **01b-cold-start-evidence**, 03-result-interaction, 04-a11y-i18n, 05-states-errors, 06-performance-motion, 07-copy-claims; Lead cross-check 09. **Not delivered at verdict time:** 02-screens-ia.md (screen-auditor resumed with a deadline; see §F).
**Method:** dedupe → personal code verification of every kept P0 and P1 → `review-room` adversarial pass on the top five → rank by user pain × frequency × trust damage / effort. No app source was edited, no adb was run, no build was made. Every claim below is a line I re-read in the shipped tree or a number from the evidence pack.

---

## 0. What the council did to the audit

- **61 P0/P1 items in eight reports collapse to 31 distinct defects: 8 P0 and 23 P1.** 24 items were duplicates of another report's finding, 1 was killed outright, 4 were downgraded and 1 was corrected into a narrower defect. The rest of the ~140 P2/P3 findings compress into eight themes (§B.4).
- **Verification rule applied:** every P0 and every P1 below was re-read in the shipped tree (file:line cited) or checked against `build/ux-audit/`. Where a P1 rests only on the reporting auditor's own code reading, the evidence column says so.
- **Killed and downgraded (with reasons):**

| Item | As filed | Ruling | Why |
|---|---|---|---|
| HF-01 (05) | P0 | **Corrected, kept as P1-02** | The claim "token never used for the download" is right only for the browse path: `HfBrowseSession.downloadNow` enqueues a synthetic entry and the queue port has no authorization field, so the token is lost there. The paste-URL dialog path does pass it (`ModelManagerSection.kt:274-286` → `ModelDownloader.start(authorization=token)` → `Downloads.kt:281`). P1, not P0: public repos are unaffected. |
| JRN-07 (01), DL-12 (05) | P1 / P2 | **Corrected into P1-23** | The manifest declares POST_NOTIFICATIONS (`AndroidManifest.xml:20`) and the helper is called at both download entry points, so "never requested in code" is false. But the fresh install shows the request never fires in practice (granted=false, importance=NONE, no dialog), so the real defect is narrower and worse for the user: the download is invisible outside Library → Downloads. One P1, not two findings. |
| COPY-02 (07) | P1 | **Downgraded to P3** | The "once" media contradiction is real copy drift, but the shipped behaviour (delete at app close) is *stricter* than the "deleted with the chat" line. It does not over-retain; it is not a privacy promise break. |
| CITE-01 (03) | P1 | **Downgraded to P2** | The fallback lists retrieved passages that were genuinely in the prompt; "sources used" is defensible. The real defect is traceability (P1-10). |
| A11Y-02, A11Y-03 (04) | P1 | **Not counted (unverified)** | uiautomator dumps the *unmerged* semantics tree; Compose `clickable` nodes merge descendants, so TalkBack likely announces the icon label. Needs a TalkBack pass, not a dump. |
| LV-06 (09) | P1-if-real | **Not promoted** | Emulator-only speed sample; the speed story is carried by measured engine STATS in P0-6 instead. |

- **Adversarial pass (review-room, top five):**

| Attack | Outcome |
|---|---|
| Wrong build? | All P0 refs re-read at HEAD; `app.json`/`strings.xml` taken from the installed APK assets. No mismatch. |
| Emulator artifact? | Timing numbers are device-class-specific: softened "speed is broken" to "this device class", added the clean-install 2.77 s number, kept only the device-independent feedback defects as P0-6. |
| Documented limit? | The demo engine is not documented as absent — the onboarding copy *promises* it. No defence. |
| Fix risk? | All six P0 fixes are copy/routing/status/queue changes (S–M). None needs re-architecture. |
| Duplicate inflation / severity inflation? | 24 of 58 items were duplicates; two claims killed; A11Y-02/03 dropped; three downgraded. CH-01 kept P0 despite low frequency because it is data loss plus a false "saved" state. |

- **Merge map (the duplicates that mattered):**

| Consolidated defect | Source items |
|---|---|
| Demo-engine promise (P0-1) | LC-01, COPY-01, JRN-02, CMP-01 |
| No-model recovery + state conflation (P0-2) | LC-02, JRN-01, COPY-03, CMP-02, ST-01, ST-04 |
| Blank answer dead end (P0-3) | SND-01 + ATT-01/ATT-02 (mechanism) |
| Metered first-run loop (P0-4) | DL-02, LC-10, LC-11 |
| Silent save failure (P0-5) | CH-01 |
| Silent 27 s wait + lying meter (P0-6) | PERF-07, PERF-08, PERF-05, SND-03, SND-05, JRN-12, JRN-13 |
| Autoscroll yank (P1-05) | SND-02, LC-03, PERF-09, JRN-18 |
| Citations untraceable (P1-10) | CITE-02, CITE-04, CITE-05/DOC-01/COPY-04, CITE-01 (P2) |
| Stop looks finished (P1-09) | SND-04, CH-03 |
| Answer language (P1-19) | SND-08, JRN-15 |
| Privacy claims (P1-20) | CLAIM-01, CLAIM-02, CLAIM-03 |
| HF token on browse path (P1-02) | HF-01, corrected |
| Screen-reader silence (P1-16) | A11Y-01, A11Y-05, A11Y-10 |
| Text scale (P1-18) | FS-01, I18N-01 |
| HF route broken (P0-7) | 01b HF-01 |
| Arabic promise (P0-8) | 01b MODEL-01, SND-08, JRN-15 |
| Download invisible / notification (P1-23) | 01b DL-01, JRN-07, DL-12 (corrected) |

---

## A. State of the app — the blunt verdict

1. **It is a real product, not a demo.** True token streaming, an in-place 48 dp Stop, markdown with per-block RTL direction, a typed failure taxonomy, atomic conversation writes, and a four-state model chip that is always a doorway. The audit's positive findings are corroborated across four reports; do not rebuild these.
2. **But 1.1.0 is not ready for a first-time user.** The first minute fails on truth (a promised demo engine that does not exist), on route (a failure that sends the user to a Settings page with no models), on download (the Hugging Face route fails 3/3), and on language (the only two obtainable models answered a plain Arabic request in English, then in word salad).
3. **The model story is pre-1.1.0 code wearing 1.1.0 copy.** Demo-engine claim, "import a GGUF from Settings", onboarding downloads that bypass the durable queue and its metered consent. This is one stale information architecture, not six unrelated bugs.
4. **The worst defect is invisible.** A question or paste that does not fit the 2,048-token window ends as an **empty, non-failed answer** with no copy, no retry, no error. The highest-intent action the product advertises — paste a document and ask — is the one that dies silently.
5. **The Arabic promise is the deepest problem, and it is partly a product choice.** The app recommends a model for RAM, not for Arabic quality; neither the 0.5B it recommends nor the 1.5B the founder tested produced a usable Arabic first answer. Part of this is model class (0.5B quantized Arabic is weak) — but the app owns the choice, the absence of a quality gate, and the absence of any expectation-setting. A first-time user cannot tell "weak model" from "broken app".
6. **Speed is a first-answer problem, not an average problem.** Clean first frame is **2,769 ms** (first launch) / **3,472 ms** (relaunch) on a fresh install with no model, but **6.1–7.5 s once a 1.1 GB model is selected** on the other emulator — the steady state after onboarding. The first answer waits **26.7 s** for its first token cold (**8.6 s** warm) and then runs at **1.9–6.3 tok/s**; the only feedback is a 12 dp mark and a meter that under-reports by roughly 3×.
7. **The public claims overreach.** About says «لا يرسل سِرِّك أي بيانات إلى أي خادم» and the listing says "nothing is sent to a server", while 1.1.0 ships three network features (Hugging Face search, header probe, model download) that send the search string and, for gated models, the user's token. The absolute is false as written.
8. **Accessibility is not implemented at the core.** No live region anywhere (streaming answers, dictation failures, download progress are silent), the composer and history search have no accessible name, the default theme pack paints text at **4.39:1** (AA fail), and text scale multiplies with the system scale to 260 % with no cap. Not release-blocking for an unimpaired user; unusable with a screen reader.
9. **The audit itself over-reported.** 61 P0/P1 items for 31 defects is a process problem, not a quality signal. The top findings are corroborated 3–5×, so consolidation changes the plan, not the evidence.
10. **Ruling: fix the eight P0s and the five cheapest high-frequency P1s (2–3 weeks), then ship.** Do not start with startup re-architecture, the motion system or a full accessibility program — they are real, but they are not what breaks the promise.

## B. Master list

### B.1 P0 — eight defects that must be fixed before 1.1.0 ships

| ID | Finding (one line) | Strongest evidence | Smallest fix |
|---|---|---|---|
| **P0-1** | First-run "try without a model" promises a demo engine that this build never selects; the first send becomes a failed turn. | `onboarding_model_body`/`onboarding_model_demo_note` (`feature/models/src/main/res/values/strings.xml:93,96`) promise «محرك العرض»; `app.json` `engine.provider = llamacpp`; `resolveEnginePlugin` picks the demo only for an unknown provider (`AppComposition.kt:303-312`); send gate `ChatStore.kt:459-461`. Corroborated by 01, 03, 07, 09. | Delete the demo claim and the "try without" promise, or ship the demo engine. Onboarding must state the real prerequisite: a model from the Library. |
| **P0-2** | The no-model failure sends the user to Settings, where model management does not exist; loading and load-failed are also reported as "no model". | Copy + CTA `strings.xml:18-19`, `ChatScreen.kt:332-361`, route `ChatPlugin.kt:87`; Settings root has six doors and no models (device walk, 01); `ChatStore.kt:459-461` tests only `status !is Ready`. Corroborated by 01, 03, 05, 07, 09. | One model route (Library → Models / HF), reword to «احصل على نموذج من المكتبة», and branch three states: unloaded / loading / failed-with-reason. |
| **P0-3** | A question or paste that does not fit the 2,048-token window ends as an empty, non-failed answer: no error, no retry, no action. | `llama_jni.cpp:773-778` returns with no sink event when `build_prompt` fails; `LlamaCppEngine.kt:265` then sends `Completed(emptyList())` unconditionally; `ChatStore.kt:1018-1051` finalises a blank, non-failed turn; `ChatScreen.kt:297` hides every action. Mechanism amplified by queued attachments not counted (`ChatStore.kt:749-769`). | Emit a typed `Failed(PROMPT_TOO_LONG)`, show an actionable error (trim / shorten the attachment), and count queued attachments in the budget meter. |
| **P0-4** | The first-run download can never succeed on mobile data: no consent dialog, the downloader refuses, and the failure copy blames the network. | `OnboardingScreen.kt:93-105` starts `ModelDownloader` with `allowMetered` default false and no metered question; gate `Downloads.kt:156-162, 263-268`; failure renders `models_reason_network` + Retry (`OnboardingScreen.kt:304-312`), and retry repeats the same call. Corroborated by 01, 05, 09. | Route onboarding through the persisted queue (it already owns the consent dialog) or add the same "download over mobile data?" prompt, and show the real reason code. |
| **P0-5** | A failed conversation save is discarded and never shown: the transcript stays on screen, disappears on relaunch, and nothing warns. | `ChatStore.kt:853-877` `runCatching { library.save(snapshot) }` result thrown away at :863/:873; `FileConversationLibrary.kt:301-316` logs write errors, returns nothing. | Surface the save result: non-blocking banner "couldn't save — free space / retry / export", dirty flag and retry on the next turn. |
| **P0-6** | The first answer waits 26.7 s in silence and the only speed number on screen is wrong by ~3×. | Engine STATS: cold `prefill_ms=26719.2 ttft_ms=26723.8 generated=73 tok_s=1.93`; warm `ttft_ms=8646 tok_s=6.35` (`logcat-full-1901.txt`). Meter divides a chars÷4 estimate by the **whole** request wall time (`ChatStore.kt:1063-1068`) → «≈ 0.4–0.7 رمز/ث» while generation ran at 1.93; `StreamingLabel` exists but is never used (`Chat.kt:443`). Corroborated by 01, 03, 06, 09. | Show the real stage while waiting (reading question → writing, with a rough ETA from measured tok/s), report generation tok/s only, and keep the meter visible during the stream. |
| **P0-7** | The Hugging Face download route is broken on a fresh install: 3/3 attempts write 0 bytes, the queue row goes FAILED with errorCode "network", and the UI blames the connection — while the identical 491,400,032 B file installs from the curated card on the same validated Wi-Fi. | 01b clean run: 3 attempts, 0 bytes, `phase=FAILED, errorCode=network, attempts=3` in the queue JSON; curated card INSTALLED in 3 m 32 s; the HF path goes `HfBrowseSession.downloadNow` → `DownloadBridge` queue → downloader. | Fix the HF fetch path (compare its HTTP client with the probe's working Range read: URL, Range, user-agent, redirect), log and show the HTTP status, and retest 5 consecutive HF downloads. |
| **P0-8** | The Arabic-first promise fails at the only two models a user can obtain: the recommended 0.5B's first answer was incoherent word salad (twice), and the 1.5B answered a plain Arabic question in English. The app recommends for RAM, not for Arabic, and sets no quality expectation. | 01b MODEL-01 (clean-42: word salad with a Latin fragment, second attempt also nonsense); SND-08/JRN-15 (1.5B English letter with literal "[Recipient]"); `app.json` asks for language matching but nothing enforces or checks it. Cause not isolated: model class (likely) vs chat-template/prompt. | Onboarding "test your model" with one Arabic prompt; recommend ≥1.5B for Arabic or label 0.5B as tutorial/limited; script/quality check with a regenerate notice; an honest expectation line before download. |

### B.2 P1 — twenty-three defects that decide whether the product feels trustworthy

**Cluster 1 — model acquisition (4)**

| ID | Finding | Evidence | Fix |
|---|---|---|---|
| **P1-01** | The Hugging Face repo screen breaks on any real repo id: the raw id is the page title in display serif, 330 px tall for one line, wrapped over three lines with a lone "F", back glyph detached mid-line — on the model-install decision screen. | clean-16 shot + crop `build/ux-audit/zoom/hf-top.png` (seen: "Qwen/" / "Qwen2.5-0.5B-Instruct-GGU →" / "F"); node bounds 53,139→943,469; the same id is repeated as the card title. New from Lead, verified visually. | Show the repo name, not the full id, at header size; one line + ellipsis; pin the back control. |
| **P1-02** | Gated HF download loses the token on the browse path: the probe proves the token works, the queued download starts without authorization and fails 401. | `HfBrowseSession.kt:343-387` enqueues a synthetic entry; `DownloadBridge.enqueue` stores no auth (`DownloadBridge.kt:132-150`); `start` → `port.start` → `ModelDownloader.start(..., allowMetered)` with `authorization` default null (`AppComposition.kt:371-372`, `Downloads.kt:156-162`). Only the paste-URL dialog passes the token. | Carry the token on the queued row in memory (or pass it through `start`), and map 401/403 to "token required/expired". |
| **P1-03** | Gated probe failure says "enter a token", but the token card exists only in another phase; retry without a token repeats the failure. | `hf_strings.xml:66`; probe section `HfBrowseScreens.kt:445-504` (failure + retry only); token card `702-742` rendered only for `TOKEN_REQUIRED`. | Render the token card whenever the probe reason is gated/401, and retry the probe after saving. |
| **P1-04** | "Waiting for Wi-Fi" never resumes: nothing watches connectivity. | No `registerNetworkCallback` anywhere (ConnectivityManager is used only to read capabilities: `AndroidDownloadSupport.kt:36-43`, `LibraryScreen.kt:857-862`); the bridge pumps only on enqueue/start (`DownloadBridge.kt:132-150, 189`). | Register a connectivity callback (start the queued row on unmetered return) or state "connect, then tap resume". |

**Cluster 2 — the answer (7)**

| ID | Finding | Evidence | Fix |
|---|---|---|---|
| **P1-05** | Auto-scroll yanks the reader to the bottom on every token: a streaming answer cannot be read. | `ChatScreen.kt:174-188`: `LaunchedEffect(items.size, streamingLength)` → `scrollToItem(last)` + `scrollBy(10_000f)` per delta. | Follow only while the user is at the bottom; otherwise show a "jump to latest" affordance. |
| **P1-06** | Copy puts the raw Markdown source on the clipboard. | `ChatScreen.kt:305-308` `clipboard.setText(AnnotatedString(item.text))` where `item.text` is the Markdown `MarkdownText` parses (`Chat.kt:185-190`). | Copy the rendered text (share/export can keep the source). |
| **P1-07** | Answers cannot be selected: no partial copy, no quote, no share. | No `SelectionContainer` outside `AboutLicences.kt` (repo grep); `AssistantProse` renders a plain text column. | Wrap the answer in a selection container; add share. |
| **P1-08** | Regenerate destroys the current answer before a replacement exists. | `ChatStore.kt:530-540` removes the assistant item and calls `persist()` before launching the new stream; a failure or Stop leaves nothing to return to. | Keep the old answer until the replacement completes; offer undo. |
| **P1-09** | Stop leaves a partial answer presented as finished: same actions, no "stopped" mark. | `ChatStore.kt:568-586`: non-blank partial → `copy(streaming = false)` only; UI gate `ChatScreen.kt:297` then shows the normal copy/regenerate row. | Mark the turn as stopped/partial with "continue" and "retry", visually distinct from complete. |
| **P1-10** | Citations cannot be traced: [n] stays literal text, chips carry no number, and the label promises "المصادر المستخدمة". | No citation parsing in `Markdown.kt` (repo grep); chips render documentName + page only (`RagUi.kt:370-398`); page label appended unconditionally (:392); fallback lists all retrieved chunks (`ChatStore.kt:984-994`, downgraded to P2). | Number the chips to match [n], list only sources the model cited (fallback clearly labelled "retrieved, not cited"), hide the page for flow formats. |
| **P1-11** | Truncation is invisible: the model reads less than the user attached, and queued attachments do not count against the meter. | `AttachmentPrompt` caps model-facing text at 20,000 chars and inserts a marker (`Attachments.kt`); queued attachments deliberately ignored in the budget (`ChatStore.kt:749-769`); no counter in the field. | Show "truncated to N of M characters" and count queued attachments in the meter (this is the feeder for P0-3). |

**Cluster 3 — persistence and downloads (6)**

| ID | Finding | Evidence | Fix |
|---|---|---|---|
| **P1-12** | Process death mid-answer loses the answer silently: the question is saved, the answer is not, and nothing says so on relaunch. | `ChatStore.kt:453-456` persists the user turn before the engine check; only completed/failed turns persist. | On relaunch, show "the answer to this question was interrupted — retry?" for the last unanswered user turn. |
| **P1-13** | An unreadable conversation is a dead tap: the row does nothing and gives no reason. | `ChatHistory.kt:181-183` `if (store.openConversation(id)) onOpened()` — no else. | Show a failure row/message with the reason and an export/delete escape. |
| **P1-14** | The metered-consent question lies in both directions: it is computed from the network at dialog time, while the queue decides at start time. | `LibraryScreen.kt:857-862` local `isMetered`; the queue re-checks `allowMetered` at run time (`Downloads.kt:263-268`); copy promises a one-shot decision. | Decide at start; show the actual state ("started on Wi-Fi", "waiting for Wi-Fi"). |
| **P1-15** | Retry after a verification failure restarts from zero but the row shows ~100 %. | Failure paths delete the part file (`Downloads.kt:358-374`); queue retry keeps the old fraction. | Reset progress on retry; say "restarting from 0 %". |
| **P1-16** | A corrupt download store is rendered as "no downloads in progress". | `DownloadQueue.kt:179-209` returns an empty list plus `CORRUPT_QUEUE`. | Render a corrupt-state row with a reset action. |
| **P1-23** | A running download is invisible outside Library → التنزيلات: no chip progress, no in-app banner, and the notification never appears because the permission request does not fire in practice (fresh install: granted=false, importance=NONE, no dialog). | `AndroidManifest.xml:20` declares the permission; `DownloadNotificationPermission.kt:21-34` is called from `OnboardingScreen.kt:95` and `ModelManagerSection.kt:133` but silently returns when `findActivity()` is null (`AndroidExt.kt:8-16`); 01b observed no request and no notification during a 3.5-minute download. | Request from the Activity before the FGS starts and verify the dialog appears; add an in-app download indicator on the chat chip and empty state. |

**Cluster 4 — trust and identity (2)**

| ID | Finding | Evidence | Fix |
|---|---|---|---|
| **P1-17** | The Arabic-first assistant answered a plain Arabic question in English, with a literal "[Recipient]". The rule exists in the prompt, but nothing enforces it and no setting or notice exists. | `app.json` `engine.systemPrompt` says "Answer in the language of the user's last message" — the mechanism is prompt-only; observed English answer to «اكتب رسالة اعتذار قصيرة…» (shot 15, `s1-nodes.txt`), Arabic only after the user typed «أجب بالعربية فقط.» (`SND-08`, `JRN-15`). | Put the rule in Arabic as the last prompt line, add a visible «لغة الرد» choice, and detect a script mismatch on completion with "answer came in English — regenerate in Arabic?". |
| **P1-18** | Public privacy claims exceed behaviour: About, the listing and the Play data-safety answers all say nothing is sent to any server, while HF search/probe/download send the search string and (gated) the token. | `strings.xml:10` (`privacy_statement`); `store/listing/en/full_description.txt:1,22,27` ("nothing is sent to a server", "the only network request is a model download"); `store/play/data-safety.md:10-11`. Corroborated by 07 only (docs, not device). | Reword to name the three user-initiated network features and what they send; fix the listing and the data-safety answers in the same commit. |

**Cluster 5 — accessibility (4)**

| ID | Finding | Evidence | Fix |
|---|---|---|---|
| **P1-19** | Streaming answers, dictation failures and download progress are never announced. | No `liveRegion` anywhere in the repo (grep = 0); `AssistantProse` (`Chat.kt:168-203`), dictation strips, download rows. | One `liveRegion = Polite` on the streaming answer and on failure strips; announce progress changes at a low rate. |
| **P1-20** | The composer and the history search box have no accessible name. | `BasicTextField` with a placeholder drawn as sibling text, no semantics label (`Chat.kt:109-136`); history search likewise. | `semantics { contentDescription = ... }` on both fields. |
| **P1-21** | The default theme pack fails WCAG AA on text it actually paints: 4.39:1. | 04's contrast computation over all 5 packs; the gate only checks 20 pairs and misses `onSurfaceVariant on surfaceContainerHigh`. | Add the pair to the gate and darken the token (or raise the surface) until ≥4.5:1. |
| **P1-22** | Text scale multiplies with the system scale with no cap (130 % × 200 % = 260 %) while text boxes are fixed-height. | `FontScale.kt:55-63, 102-111`: `applyTo(platformFontScale) = platformFontScale × factor`; `maxLines = 6`, fixed 48 dp rows, single-line metadata. | Cap the combined scale (e.g. 200 %) and make the critical rows wrap/ellipsise; verify at 130 % + 200 %. |

### B.3 P2/P3 — compressed by theme (~120 findings)

- **Startup and main-thread cost (P2, ~14):** conversation index rebuilt and rewritten every launch; 1.12 GB model loaded before first frame; whole graph built synchronously (1.63–3.11 s); no startup progress; one token recomposes the chat screen; Markdown re-parsed per token; transcript/index written on the main thread; download snapshot rewritten per tick; Library composed in a plain Column; history search re-reads every transcript per keystroke; media index + orphan pruning on main; memory peak 1.43 GB with no messaging.
- **Motion (P3, ~5):** two effects total, most motion tokens unused; no reduced-motion/animator-scale handling; tab switches rebuild state inside the transition; detail pages cut in; answer completion is a hard cut. The restraint is a strength — do not "add animation".
- **Download/state honesty (P2, ~10):** load failure never says why (ST-02); verifying row has no cancel/progress; unknown-size rows show 0 % forever; process death looks like a user pause; queue rejections show raw English codes; storage warning uses a stale snapshot; metered consent does not survive process; HF rate-limit offers only manual retry; blocked fit verdict is a dead end; completed download vanishes with no "installed" signal.
- **Interaction/IA (P2, ~20):** answer actions are unlabelled glyphs; no edit/resend/copy on the question; no per-message delete; draft leaks across conversations; attach sheet ships a dead audio row; dictation has no level/silence/cap warning and permanent denial is a dead end; image prompt chips replace the draft; export is not restorable and is four taps deep; library rows have no date; "+" persists empty conversations; header new-chat is conditional while stale copy promises deletion; conversation identity only editable from History; Settings door summaries leak internal vocabulary; model chip is a two-step doorway; LV-08 metadata line opens with a stray "·" and repeats "من Hugging Face"; media segment still says images/audio "قيد التنفيذ".
- **Copy drift (P2/P3, ~18):** plurals bypass the project's own rule; digits/percent style switches; "الوكيل" only in the media shelf; two names for the same fit verdict; "switch engines" with no engine switch; stale new-chat strings; About shows the raw flavour id; "in this build" jargon; probe HTTP internals exposed; English reads as translated Arabic; media "once" deletion timing (downgraded); README/PRODUCT/PRIVACY contradictions (demo engine in install docs, "8–12 MB" probe, offline default, v1.1 scope).
- **A11y/typography (P2/P3, ~12):** contrast gate covers 20 of the painted pairs; segmented-button boundaries 1.3:1; no headings; disabled send gives no reason; citation chips ~32 dp with no role; remove-document 40 dp; export relies on a Toast; copy confirmation is a silent icon swap; model chip clips its state at 100 %; privacy timestamps use locale digits; bidi isolation on 3 of 42 formatted strings; no `localeConfig`.
- **Claims and docs (P2, ~8):** README "your companion" etymology; "there is no server to send anything to"; install docs and listing sell the demo engine; policy contradicts itself on token transmission; PRODUCT lists a scope 1.1.0 does not ship; store description still sells the pre-1.1.0 interface.
- **Missing area:** 02-screens-ia.md never landed; per the Lead, screens/IA was folded into 01/03/05. A screen-by-screen visual pass is still owed before a polish release.

---

## C. The ten fixes, in order (with acceptance criteria)

1. **Make the model story true.** Remove the demo claim, route the failure CTA to Library → Models / HF, and branch unloaded / loading / failed-with-reason. *(P0-1, P0-2)*
   **Accept:** fresh install, no model, send → the CTA opens a screen where a download CTA exists; loading and failed states read differently; no surface promises a demo engine.
2. **Never end in a blank or false-success answer.** Typed `PROMPT_TOO_LONG`, attachment budget in the meter, stop marked as stopped. *(P0-3, P1-09, P1-11)*
   **Accept:** paste 8,000 characters → an answer, or an error that names the limit and offers trim; stopping mid-answer → the turn is visibly partial with continue/retry; an empty non-failed turn is impossible.
3. **Make the first download work — on the user's network and through the HF route.** Onboarding through the queue/consent, the HF fetch path fixed, real reason codes. *(P0-4, P0-7)*
   **Accept:** mobile-data-only first run → either "download over mobile data?" or a truthful "Wi-Fi required" message with a skip; clean install → an HF repo download reaches INSTALLED with 491,400,032 B (5/5 attempts); a failure row names the HTTP status or the real reason, never "connection dropped" for an app-side failure.
4. **Never lose a conversation silently.** Surface save failures with retry/export. *(P0-5)*
   **Accept:** with the conversations directory unwritable, a visible save warning appears and the user can retry or export; relaunch never silently loses a turn.
5. **Stop stealing the scroll.** *(P1-05, cheapest high-frequency fix in the audit)*
   **Accept:** scroll up during a stream → the view stays put; a "latest" pill appears; tapping it follows again.
6. **Make the wait legible and honest.** Stage text, ETA from measured tok/s, generation-only speed, meter visible during the stream; same for downloads (speed/ETA). *(P0-6, PERF-15)*
   **Accept:** during the first token the user sees what is happening and a rough time; the displayed speed matches the engine's STATS within 20 %; no 12 dp-only wait state.
7. **Answer actions that respect the answer.** Copy rendered text, selection/share, non-destructive regenerate. *(P1-06, P1-07, P1-08)*
   **Accept:** copy pastes clean text; any part of an answer can be selected and shared; regenerate keeps the old answer until the new one completes and offers undo.
8. **Make citations traceable.** Number the chips to match [n], list only cited sources (fallback labelled), no page for flow formats. *(P1-10)*
   **Accept:** every [n] in the answer maps to a numbered chip; tapping it shows the excerpt and, when the document exists, opens it at the page.
9. **Make the Arabic promise testable.** Onboarding "test your model" with one Arabic prompt; recommend ≥1.5B for Arabic or label 0.5B as limited; Arabic last-line prompt rule + response-language setting + script/quality mismatch notice. *(P0-8, P1-17)*
   **Accept:** fresh install + recommended path → the first three answers are readable, on-topic Arabic — or the user is warned and offered a stronger model before judging; a language/quality mismatch is surfaced, never shipped silently.
10. **Make the public claims true.** About, listing, data-safety and README name the three user-initiated network features and what they send. *(P1-18)*
    **Accept:** no absolute "nothing is sent to any server" remains; the data-safety answers match the network log; a reviewer reading About and the listing finds the same story as the app.

**Suggested order of work:** 1, 3, 2, 4 plus the cheap half of 9 (onboarding self-test + honest 0.5B label, ~1 day) — all P0, ~2 weeks; then 5, 6 (days); then 7, 8, 10 and the full model decision in 9 (parallel, ~1 week). Fixes 1, 2, 5 and 9 touch files the same people own — do not parallelise those across agents.

---

## D. What to defer, and why

- **Startup re-architecture (async graph, lazy model load, index cache).** Clean first frame is 2,769 ms — acceptable. The 6.1–7.5 s figure is the eager 1.12 GB model load; fix the *feedback* now (fix 6), measure on one real ARM phone, and restructure in 1.1.1. Rebuilding this before release is the highest-risk change in the audit.
- **Motion system (P3).** The app is deliberately quiet; there is no user pain here, and adding motion would fight the product's character.
- **Systematic model-quality benchmark (10+ Arabic prompts across the 1B/3B/vision matrix).** The smoke test in fix 9 is enough for release; the full matrix is a 1.1.1 product decision.
- **Full accessibility program.** Real WCAG gaps (contrast, semantics, headings, locale config, scale), but not release-blocking for the target user; schedule 1.1.1. Do only the two cheap wins now if fix 6/7 touches those files (live region, composer label).
- **Library/history performance (main-thread search, index rebuild, download snapshot writes).** Invisible until libraries are large; 1.1.1 with a benchmark.
- **Download ETA/speed as its own project.** Fold the cheap part into fix 6; defer the rest.
- **Per-message edit/delete, share target, TTS, hands-free voice.** Feature scope, not 1.1.0.
- **Media/document indexing UX (DOC-02/03).** Second-order; fix the model story first — a user who cannot get a model never meets documents.
- **Copy drift (plurals, digits, terminology, stale strings).** Batch in 1.1.1; only the strings that mislead are pulled into fixes 1 and 10.
- **Visual/IA polish from 02.** The report never landed; do not design a polish pass on a missing critique.

---

## E. Three blind spots (what the founder has not noticed)

1. **The honesty is inverted.** The team built an unusually honest model-state and download-state layer, then put the false claims at the very start of the journey (demo engine, "import from Settings", Arabic-first answering English, a speed meter that under-reports). A reader of the strengths list will conclude trust is solved; the first minute says otherwise. Trust is set by the first three screens, not by the fit verdict.
2. **The 2,048-token window is the product ceiling, and nothing says so.** The app advertises "ask your documents", caps attachments at 20,000 characters, ignores queued attachments in the budget, shows no counter, and turns an over-budget request into an empty answer. The user most likely to love this product (pastes a document, asks a question) is the one who hits the dead end. This is one design decision (context budget surfaced honestly), not five bugs.
3. **The first answer is the product's reputation, and it is the worst answer the app produces.** Cold TTFT 26.7 s vs 8.6 s warm; 1.93 vs 6.35 tok/s; 6.1–7.5 s to first frame with a model vs 2,769 ms clean; and the meter under-reports by ~3× exactly while the user is deciding whether the app works. Optimising averages will hide this. Judge only the cold first answer, on target hardware, with the recommended model.

---

## F. What this audit could not verify (and what would change the verdict)

- **One report never landed:** 02-screens-ia.md (the screen-auditor went inactive; the Lead resumed it with a deadline). Per the Lead's instruction, screens/IA was folded into 01/03/05 rather than delivered as its own report. None of the eight P0s depends on it.
- **01b landed late but is used**, and it is the strongest evidence in the audit: clean-install cold start, the HF 3/3 failure, the notification gap, the first-answer word salad. Its device is a different AVD (1080×2400, 3919 MB RAM) from the 5554 audit install — its timings must not be averaged with the 5554 numbers.
- **All timings are from software-emulated x86_64 devices.** Do not quote 26.7 s or 2,769 ms as phone numbers; quote them as "this device class". The design defects (no stage, no ETA, lying meter, silent start) are device-independent; the absolute speed must be re-measured on one ARM phone before a launch decision.
- **P0-7's root cause is unknown.** No logcat of the HTTP status was captured; the same 491,400,032 B file installs from the curated card on the same validated Wi-Fi, so the defect is on the HF route, but whether it is the URL/Range/user-agent/redirect handling, the queue-path entry construction, or the CDN handshake is not isolated. The fix starts by logging and showing the status.
- **P0-8's cause is not isolated either:** model class (0.5B/1.5B quantized Arabic competence) versus chat-template/prompt. The app ships the combination either way; a 10-prompt Arabic smoke test and a comparison against a reference runner would separate the two.
- **No TalkBack pass.** A11Y-02/A11Y-03 (labels on separate nodes) are probably a uiautomator unmerged-tree artifact — Compose clickable nodes merge descendants — so they are not counted. A11Y-01/04/05 are verified by absence in code. One 20-minute TalkBack pass would settle the rest.
- **No metered-network live run.** P0-4 is verified in code (default `allowMetered=false`, no consent in onboarding, network copy); 01b could not reproduce the metered path on its Wi-Fi.
- **No disk-full and no process-kill run.** P0-5 and the process-death answer loss are verified in code only.
- **No gated HF download executed.** P1-02 is corrected from code (the queue path has no authorization field).
- **No frame-level jank data** (no gfxinfo/framestats in the pack); jank claims rest on logcat "Skipped N frames" lines and 01b's visual note of janky launch frames.
- **Model quality is sampled, not audited.** Two models, three answers, no Arabic eval, no hallucination/refusal check, no comparison of the recommended matrix. A competitor's reviewer would say the product's ceiling is the model, not the UI — this audit can now say the app ships a model that fails the promise, but not how often.
- **Evidence defect to record:** `build/ux-audit/shots/00-baseline-settings-root-ar.png` is actually Library → Models (journey-runner mislabel). Use `shots-clean/` + `EVIDENCE-clean.md` for any cold-start claim.
- **Static absences are greps, not runtime probes:** no `liveRegion`, no `SelectionContainer`, no connectivity callback, no `derivedStateOf` are source-level facts; the behaviour they imply was not observed live.

## G. Ruling

Sirrak 1.1.0 is a careful, genuinely private assistant with an honest engine layer and a first minute that lies. The audit's instinct — "the product is good but the state handling is weak" — is right, but it aimed at the wrong end: the state layer is the strongest part of the build; the first-run path is the weakest. Fix the eight P0s (fixes 1–4, 6, 9) and the cheap high-frequency P1s (fixes 5, 7, 8, 10), and 1.1.0 is a defensible release for an Arabic-first private assistant. Ship without them and the first impression is a promised demo that does not exist, a Settings page that cannot help, a download route that fails three times out of three, and a first answer in the wrong language or in word salad.

**Re-audit acceptance (one sitting, one real ARM phone, clean install):** mobile-data-only first run reaches a working answer within three taps after consent; an HF repo download completes 5/5 with the right byte count; no send can produce an empty non-failed answer; an 8,000-character paste either answers or says why; the recommended model's first three Arabic answers are readable, or the user was warned and offered a stronger one before judging; scrolling up during a stream is not stolen; the displayed speed matches the engine log; a running download is visible from chat; About and the store listing name the three network features; a TalkBack pass reads the streaming answer and the composer.

— end of verdict —
