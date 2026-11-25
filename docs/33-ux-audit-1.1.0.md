# 33 — UX audit of Sirrak 1.1.0 (round 2): the front end, the answer path and the experience

**Date:** 25 Nov 2026 · **Build audited:** `app.sirrak` 1.1.0 (versionCode 14), debug APK, at HEAD `e28a9b6` (whose diff touches no app source) — on two emulators: `emulator-5554` (Android API 36, 720×1280 @320 dpi, x86_64, 6 GB) with a model installed, and a clean `emulator-5556` (1080×2400 @420 dpi, 4 GB) used for the first-run journey.
**Method:** specialist area reviews (user journey, answer interaction, accessibility/RTL, state integrity, performance/motion, copy & claims) + a clean-install evidence run + an independent Lead cross-check + one adversarial council that re-verified every P0/P1 in the shipped tree and merged the duplicates. The screens/IA review did not deliver; it was folded into the journey and interaction reports and is listed as an owed pass. Area reports and the screenshot evidence pack are linked in §4.
**Precedent:** [31-ux-rework-council.md](31-ux-rework-council.md) (the founder's first review and the rework it produced), [32-ux-rework-build-record.md](32-ux-rework-build-record.md) (what 1.1.0 claims to have built).

---

## 0. How to read this

- **§1** is the state of the app in one page — what is genuinely good, what is broken, and the verdict.
- **§2** is the backlog: every finding, deduplicated, ranked, with evidence and the smallest fix. P0/P1 first.
- **§3** is the council's ruling: what to fix first, in what order, and why; plus what the audit could not verify.
- **§4** is the evidence pack: where every claim comes from (screenshots, timings, code lines).
- Area reports (per specialist) live in [audit-1.1.0/](audit-1.1.0/README.md) and are the raw record.

Severity: **P0** breaks or actively misleads on the core promise · **P1** the user cannot complete or does not discover the main path, or trust breaks · **P2** friction, hesitation, extra work · **P3** polish.

---

## 1. State of the app

**One line:** 1.1.0 is a carefully engineered assistant wrapped in an experience that does not yet tell the truth about time, and whose first run sends the user to the room where the fix is not.

### What is genuinely good (do not regress this)

1. **The model-control layer is honest engineering, and it is the best thing in the build.** The four-state model chip (no model · loading · ready · failed), its always-a-doorway sheet, a typed failure taxonomy with a retry on every failed turn, one persisted download queue with atomic JSON writes and a single active transfer, and a Hugging Face fit verdict that refuses a model predicted not to fit are all real. This is not a demo; it is a considered product surface.
2. **Arabic typography and RTL are done right.** The rework's reading column, the Arabic-first prose face, markdown with per-block direction, mirrored icons and bottom navigation, sentence-final punctuation and ordered lists all render correctly on device (verified at 2× zoom). 651/651 string parity AR/EN, no hardcoded UI strings, and the `docs/30` terminology law (`نموذج`, `تعليمات`, `ملحق الرؤية`) holds across the main surfaces.
3. **The answer surface is real.** True token streaming with a 48 dp Stop that swaps in place, a live token meter, one persisted conversation library with search and atomic writes, export and backup, per-answer report, and a document-scope pill. It looks and behaves like a serious assistant.
4. **Privacy is designed, not claimed.** No telemetry, three declared and visible network endpoints, an offline-only switch, a network audit screen, and a privacy ledger with rationale. The structure is trustworthy even where the words are not yet exact (§2, CLM).
5. **The accessibility foundation is better than average** — semantic descriptions written, 48 dp targets, contrast gates in code, font-scale presets, and a tokens/packs theming system. It is gated by gaps, not by absence.

### The honest picture, along the journey the user actually walks

**First run.** The onboarding promises «بدون نموذج يعمل محرك العرض… جرّب الآن» and a «التجربة بدون نموذج» button. The shipped build selects `llamacpp`, so the demo engine never installs and the first send returns «لم يُحمَّل أي نموذج بعد». The failure's own instruction — «استورد ملف GGUF من **الإعدادات**» — and its «فتح الإعدادات» button point at the one screen of six doors that has no model management since 1.1.0 moved models to the Library (points at `app.json:25`, `AppComposition.kt:303-312`, `ChatPlugin.kt:87`). The correct path does exist and is good — Library → النماذج → «للتنزيل» → curated Qwen2.5 0.5B with a fit verdict and one-tap download — but nothing in the chat or the failure points to it.

**Getting a model.** The queue is durable and the HF flow is genuinely thoughtful (search → files → quantisation → fit verdict → probe → estimate → download with resume and retry, captured end-to-end in 18 clean shots). It is also the place where the run can silently die: on **mobile data the first-run download can never succeed** — onboarding has no metered-consent path, the downloader's not-unmetered gate fires, and the failure copy always blames the connection, with a Retry that loops the same way; the metered dialog is wrong in both directions (it asks for mobile data in airplane mode and stays silent on metered Wi-Fi), «يبقى حتى Wi-Fi» never auto-resumes, and a retry shows ~100 % while restarting from zero. A gated-repo token is proven by the probe and then never sent to the download (401 dressed as a generic failure). Nothing shows speed or ETA although `bytesPerSecond` is computed.

**Asking, and waiting.** A question can be sent while no model is loaded (it becomes an error turn after the user typed it), and a *loading* model is reported as «no model» with a Retry that fails identically. Then comes the silence: on this device the first token arrived after **26.7 s** (8.6 s warm) and 73 tokens took **64.5 s**, while the only sign of life was a 12 dp rotating mark; the meter reported «≈ 0.4–0.7 رمز/ث» while the engine logged 1.93 tok/s, and the engine's own rate is known only after the stream ends. Cold start itself is **6.1–7.5 s** to first frame (the app builds its whole graph on the main thread, 1.63–3.11 s, then loads the selected 1.1 GB model on every launch, 1.27 s, ~1.4 GB RSS). None of this is stated to the user.

**Getting the result.** Two defects stand between a slow answer and a trustworthy one. The first is a hard dead end: a question or paste that does not fit the 2 048-token window ends as an **empty answer that is not marked failed** — the native prompt build fails, no event reaches the UI, the store finalises blank, and copy/retry/actions disappear, with no error and no way out (`llama_jni.cpp:773-778` → `LlamaCppEngine.kt:265` → `ChatStore.kt:1040-1051`). The second is about the product's own identity: an Arabic-first app **answered a plain Arabic question in English** — a complete English letter with an unfilled «[Recipient]» — until the user typed «أجب بالعربية فقط.»; the shipped system prompt is one English sentence that *asks* for the user's language but the 0.5–1.5B models do not reliably obey, and there is no script check and no response-language control.

**Reading and keeping the answer.** The transcript auto-scrolls to the bottom on every token, so a reader who scrolls up to re-read is dragged back within a frame; the stop button keeps a cut-off answer that looks finished and is saved as complete; copying puts raw Markdown on the clipboard; regenerate deletes the old answer before the new one exists; «المصادر المستخدمة» lists every retrieved chunk even when the model cited none and `[n]` markers are dead text; nothing in the transcript or the library says *when* — no timestamps, no day separators; and a conversation that cannot be written to disk is discarded by `runCatching` and simply vanishes next launch.

**Returning.** The history screen is clean and searchable, but «2 رسالة» is the only context, rows are unnamed buttons for TalkBack, and an unreadable chat file makes the row do nothing when tapped. The model chip shows a truncated filename («…qwen2.5 1.5b instruct») plus an unexplained orange dot.

**For everyone else.** TalkBack users get no live region anywhere (streamed answers, download and copy confirmations, dictation failures are silent), clickable rows are not merged so they announce as unnamed buttons, icon-only buttons carry their name on a non-clickable child node, and the composer has no accessible name. The default GirihInk pack paints `onSurfaceVariant` on `surfaceContainerHigh` at **4.39:1** — above the 3:1 large-text line but below 4.5:1 for body text, and not covered by the code's contrast gate; the gate itself covers only 20 pairs. Font scale is multiplicative and uncapped (130 % preset × system 200 % = 260 %) in fixed-height single-line boxes. The English pass has never been captured end-to-end; `android:localeConfig` is missing.

### The claims

The privacy absolute in About — «لا يرسل شيئًا إلى أي خادم» / “sends nothing to any server” — contradicts the three declared and audited network features (HF search, model probe, model download). The Play listing and the data-safety answers repeat the narrower version («the only network request is a model download») and the data-safety document predates 1.1.0. These are P1 today and become P0 the day the listing is submitted. The media-retention copy contradicts itself inside one card («deleted with the conversation» next to «deleted when the app closes»), and «الأشياء لا تعمل في الخلفية» sits next to a background download service.

### Verdict

The front end is **round-1 quality and then some — on the surfaces the rework touched**. What it has not yet done is the second half: *say* what it is doing (time, progress, language, offline-vs-online), *route* the user to the right room, and *finish the failure*. Almost every defect in §2 is a truth-telling or routing failure, not a missing feature. That is good news: the expensive parts (engine, queue, typography, persistence, theming) are built.


## 2. Findings backlog

**How this list was built.** Seven area reports and the Lead cross-check filed **221 findings** (61 of them P0/P1). The council merged duplicates, re-verified every P0/P1 in the shipped tree, and produced **31 distinct defects — 8 P0 and 23 P1** — plus ~120 P2/P3 compressed into themes. The per-finding detail (where / what happens / why it hurts / fix / how to verify / confidence) lives in the area reports linked in §4; the merge map and the adversarial reasoning live in [08-council-verdict.md](audit-1.1.0/08-council-verdict.md).

Severity: **P0** breaks or actively misleads on the core promise · **P1** the user cannot complete or does not discover the main path, or trust breaks · **P2** friction, extra work · **P3** polish.

### 2.1 P0 — eight defects that must be fixed before this ships

| # | The defect | Where / strongest evidence | Smallest fix |
|---|---|---|---|
| **P0-1** | First-run «التجربة بدون نموذج» promises a **demo engine this build never selects**; the first send becomes a failed turn. | `onboarding_model_demo_note` (`feature/models/…/values/strings.xml:96`) promises «محرك العرض»; `app.json` selects `llamacpp`; the demo installs only for an unknown provider (`AppComposition.kt:303-312`); the turn fails at `ChatStore.kt:459-461`. 4 reports. | Delete the demo claim and the "try without" promise (or ship the demo), and state the real prerequisite: a model from the Library. Effort **S**. |
| **P0-2** | The no-model failure **sends the user to Settings**, where model management no longer exists; *loading* and *load-failed* are also reported as "no model". | Copy `strings.xml:18-19`, CTA `ChatScreen.kt:332-361`, route `ChatPlugin.kt:87`; Settings root has six doors and no models (device walk); only `status !is Ready` is tested (`ChatStore.kt:459-461`). 5 reports. | One model route (Library → Models / HF), reword to «احصل على نموذج من المكتبة», branch unloaded / loading / failed-with-reason. Effort **S–M**. |
| **P0-3** | A question or paste that **does not fit the 2 048-token window ends as an empty, non-failed answer** — no error, no retry, no action. | `llama_jni.cpp:773-778` returns with no sink event when `build_prompt` fails → `LlamaCppEngine.kt:265` emits `Completed(empty)` unconditionally → `ChatStore.kt:1018-1051` finalises a blank turn → `ChatScreen.kt:297` hides every action. Queued attachments are not counted (`ChatStore.kt:749-769`). | Typed `Failed(PROMPT_TOO_LONG)`, an actionable error (trim / shorten attachment), attachments counted in the meter. Effort **S–M**. |
| **P0-4** | The **first-run download can never succeed on mobile data**: no consent, the downloader refuses, the copy blames the network, Retry loops. | `OnboardingScreen.kt:93-105` starts the downloader with `allowMetered=false` and no question; gate `Downloads.kt:156-162,263-268`; failure renders `models_reason_network` + Retry (`OnboardingScreen.kt:304-312`). 3 reports. | Route onboarding through the queue that already owns the consent dialog (or ask the same question) and show the real reason code. Effort **S**. |
| **P0-5** | A **failed conversation save is discarded and never shown**: the transcript stays on screen, disappears on relaunch, nothing warns. | `ChatStore.kt:853-877` `runCatching { library.save(…) }` result thrown away; `FileConversationLibrary.kt:301-316` logs and returns nothing. | Surface the result: non-blocking banner with retry/export, dirty flag, retry on the next turn. Effort **S–M**. |
| **P0-6** | The first answer **waits 26.7 s in silence** and the only speed number on screen is **wrong by ~3×**. | Engine STATS cold `ttft_ms=26723.8 tok_s=1.93`, warm `8646 / 6.35`; the meter divides a chars÷4 estimate by the whole request wall time (`ChatStore.kt:1063-1068`) → «≈0.4–0.7 رمز/ث»; `StreamingLabel` exists but is never used (`Chat.kt:443`). 4 reports. | Show the real stage while waiting + a rough ETA from measured tok/s; report generation tok/s only; keep the meter visible during the stream. Effort **M**. |
| **P0-7** | The **Hugging Face download route is broken**: 3/3 attempts write 0 bytes, queue row FAILED `errorCode "network"`, UI blames the connection — while the identical 491,400,032 B file installs from the curated card on the same validated Wi-Fi. | Clean-install run 01b: queue JSON `phase=FAILED, errorCode=network, attempts=3`; curated card INSTALLED in 3 m 32 s; HF path `HfBrowseSession.downloadNow` → `DownloadBridge` → downloader. Root cause not yet isolated. | Log and show the HTTP status, compare the HF fetch against the probe's working Range read (URL / Range / UA / redirect), retest 5 consecutive downloads. Effort **M**. |
| **P0-8** | The **Arabic-first promise fails at the only two obtainable models**: the recommended 0.5B's first answer was incoherent word salad; the 1.5B answered a plain Arabic question in English. The app recommends for RAM, not for Arabic, and sets no expectation. | 01b MODEL-01 (`clean-42`, word salad with a Latin fragment; second attempt also nonsense); SND-08/JRN-15 (English letter with a literal `[Recipient]`); `app.json` asks for language matching but nothing enforces or checks it. | Onboarding "test your model" with one Arabic prompt; recommend ≥1.5B for Arabic or label 0.5B as limited; Arabic last-line prompt rule + response-language setting + mismatch notice. Effort **M**. |

### 2.2 P1 — twenty-three defects that decide whether the product feels trustworthy

**Model acquisition.** P1-01 the HF repo screen **breaks on any real repo id** — raw id as page title, 330 px tall for one line, wrapped to three lines with a lone "F", back glyph detached (clean-16, `zoom/hf-top.png`) · P1-02 **gated download loses the token** on the browse path (probe proves it, queued download starts unauthenticated → 401; `HfBrowseSession.kt:343-387`, `DownloadBridge.kt:132-150`) · P1-03 "enter a token" with no token field on that screen (`hf_strings.xml:66`, `HfBrowseScreens.kt:445-504`) · P1-04 «يبقى حتى Wi-Fi» **never auto-resumes** — no connectivity callback anywhere (`DownloadBridge.kt:132-150,189`).

**The answer.** P1-05 **auto-scroll yanks the reader** to the bottom on every token (`ChatScreen.kt:174-188`) — the cheapest high-frequency fix in the audit · P1-06 **copy puts raw Markdown** on the clipboard (`ChatScreen.kt:305-308`) · P1-07 **answers cannot be selected** (no `SelectionContainer` outside licences) · P1-08 **regenerate destroys the old answer** before the new one exists (`ChatStore.kt:530-540`) · P1-09 **Stop leaves a partial answer looking finished** (`ChatStore.kt:568-586`) · P1-10 **citations untraceable**: `[n]` is dead text, chips unnumbered, "المصادر المستخدمة" over-promises (`RagUi.kt:370-398`) · P1-11 **truncation invisible** and queued attachments outside the budget (`Attachments.kt`, `ChatStore.kt:749-769`).

**Persistence and downloads.** P1-12 **process death mid-answer loses the answer** silently (`ChatStore.kt:453-456`) · P1-13 an **unreadable conversation is a dead tap** (`ChatHistory.kt:181-183`) · P1-14 the **metered-consent dialog lies in both directions** (`LibraryScreen.kt:857-862` vs `Downloads.kt:263-268`) · P1-15 **retry restarts from zero while showing ~100 %** · P1-16 a **corrupt download store renders "no downloads"** (`DownloadQueue.kt:179-209`) · P1-23 a **running download is invisible outside Library → التنزيلات**: no chip progress, no in-app banner, and the notification never appears because the permission request never fires in practice (fresh install: granted=false, importance=NONE).

**Trust and identity.** P1-17 the Arabic-first assistant answered Arabic in English; the rule exists in the prompt only (`app.json` `systemPrompt`) · P1-18 **public claims exceed behaviour** — About «لا يرسل شيئًا إلى أي خادم», the listing "the only network request is a model download", and the Play data-safety answers, against three shipped network features that send the search string and (gated) the token.

**Accessibility.** P1-19 **nothing is announced**: no `liveRegion` anywhere, so streamed answers, dictation failures and download progress are silent · P1-20 the **composer and history search have no accessible name** (`Chat.kt:109-136`) · P1-21 the **default theme pack paints body text at 4.39:1** (AA fail; the contrast gate checks 20 pairs and misses this one) · P1-22 **text scale multiplies uncapped** (130 % × 200 % = 260 %) against fixed-height rows (`FontScale.kt:55-63, 102-111`).

### 2.3 P2/P3 — the long tail (~120 findings), grouped

- **Startup and main-thread cost (~14):** index rebuilt and rewritten every launch; 1.1 GB model loaded before the first frame; whole graph built synchronously (1.63–3.11 s); one token recomposes the screen and re-parses Markdown; download queue JSON rewritten 4–8×/s; Library is a plain `Column`; history search re-reads every transcript per keystroke; media index + orphan pruning on main; 1.43 GB peak with no messaging.
- **Motion (~5):** two effects total; most motion tokens unused; no reduced-motion handling — and that restraint is a **strength**: do not add animation.
- **Download/state honesty (~10):** load failure never says why; verifying row has no cancel; unknown-size rows show 0 % forever; process death looks like a pause; queue rejections show raw English codes; blocked fit verdict is a dead end; completed download vanishes without an "installed" signal.
- **Interaction / IA (~20):** answer actions are unlabelled glyphs; no edit/resend on the question; draft leaks across conversations; attach sheet ships a dead audio row; dictation has no level meter and permanent denial is a dead end; export is four taps deep and not restorable when returned; library rows carry no date; "+" persists empty conversations; Settings door summaries leak internal vocabulary; the model chip is a two-step doorway; media segment still says "قيد التنفيذ".
- **Copy drift (~18):** the project's own plurals rule bypassed; digits/percent style switches; two names for the same fit verdict; "switch engines" with no engine switch; stale new-chat strings; About shows the raw flavour id; probe HTTP internals exposed to users; the English pass reads as translated Arabic; the empty-state suggestion set mixes one English subtitle into four Arabic cards; media retention text contradicts itself inside one card.
- **A11y / typography (~12):** contrast gate covers 20 of the painted pairs (hairline/outline 1.03–2.95:1); segmented-tab boundaries 1.33:1; no headings; disabled send gives no reason; citation chips ~32 dp; copy confirmation is a silent icon swap; model chip clips its own state to a 13 dp dot; privacy timestamps use locale digits against the Latin-digit policy; no `localeConfig`.
- **Claims and docs (~8):** README, PRIVACY, PRODUCT and the install docs repeat the same absolute; the listing sells the pre-1.1.0 interface; PRODUCT lists scope 1.1.0 does not ship.


## 3. Council ruling

**The ruling.** Sirrak 1.1.0 is a careful, genuinely private assistant with an honest engine layer and a first minute that lies. The audit's instinct — "the product is good but the state handling is weak" — is right, but it aimed at the wrong end: **the state layer is the strongest part of the build; the first-run path is the weakest.** Fix the eight P0s and the five cheapest high-frequency P1s, and 1.1.0 is a defensible release for an Arabic-first private assistant. Ship without them and the first impression is a promised demo that does not exist, a Settings page that cannot help, a download route that fails three times out of three, and a first answer in the wrong language or in word salad.

### 3.1 The ten fixes, in order

| # | Fix | Done when |
|---|---|---|
| 1 | **Make the model story true** — remove the demo claim, route the failure CTA to Library → Models / HF, branch unloaded / loading / failed-with-reason. *(P0-1, P0-2)* | Fresh install, no model, send → the CTA opens a screen that has a download CTA; loading and failed read differently; no surface promises a demo engine. |
| 2 | **Never end in a blank or false-success answer** — typed `PROMPT_TOO_LONG`, attachments counted in the meter, Stop marked as stopped. *(P0-3, P1-09, P1-11)* | An 8 000-character paste answers or names the limit with a remedy; a stopped turn is visibly partial with continue/retry; an empty non-failed turn is impossible. |
| 3 | **Make the first download work**, on the user's network and through the HF route — onboarding through the queue/consent, the HF fetch path fixed, real reason codes. *(P0-4, P0-7)* | Mobile-data-only first run gets a consent question or a truthful Wi-Fi message; clean install → an HF repo download reaches INSTALLED with the exact byte count, 5/5; a failure row names the status, never "connection dropped" for an app-side failure. |
| 4 | **Never lose a conversation silently** — surface save failures with retry/export. *(P0-5)* | With the conversations directory unwritable, a visible warning appears and the user can retry or export. |
| 5 | **Stop stealing the scroll.** *(P1-05 — the cheapest high-frequency fix in the audit)* | Scroll up during a stream → the view stays put; a "latest" pill appears; tapping it follows again. |
| 6 | **Make the wait legible and honest** — stage text, ETA from measured tok/s, generation-only speed, meter visible during the stream; same for download speed/ETA. *(P0-6)* | During the first token the user sees what is happening and roughly how long; the displayed speed is within 20 % of the engine's STATS; no 12 dp-only wait state. |
| 7 | **Answer actions that respect the answer** — copy the rendered text, selection/share, non-destructive regenerate. *(P1-06, P1-07, P1-08)* | Copy pastes clean text; any part of an answer can be selected and shared; regenerate keeps the old answer until the new one completes and offers undo. |
| 8 | **Make citations traceable** — number the chips to match `[n]`, list only cited sources (fallback labelled), no page label for flow formats. *(P1-10)* | Every `[n]` maps to a numbered chip that shows its excerpt and opens the document where it exists. |
| 9 | **Make the Arabic promise testable** — onboarding "test your model" with one Arabic prompt; recommend ≥1.5B or label 0.5B as limited; Arabic last-line prompt rule, a response-language setting, a mismatch notice. *(P0-8, P1-17)* | The recommended path's first three answers are readable, on-topic Arabic — or the user is warned and offered a stronger model *before* judging. |
| 10 | **Make the public claims true** — About, listing, data-safety and README name the three user-initiated network features and what they send. *(P1-18)* | No absolute "nothing is sent to any server" remains; the data-safety answers match the network log. |

**Order:** 1, 3, 2, 4 first (the P0s, ~2 weeks), then 5 and 6 (days), then 7–10 in parallel (~1 week). Fixes 1, 2, 5 and 9 touch files the same owner holds — do not split them across agents.

### 3.2 What to defer, and why

- **Startup re-architecture** (async graph, lazy model load, index cache). A clean first frame is 2,769 ms — acceptable. The 6.1–7.5 s figure is the eager 1.12 GB model load; fix the *feedback* now (fix 6), measure on one real ARM phone, restructure in 1.1.1. This is the highest-risk change in the audit.
- **Motion system.** The app is deliberately quiet; there is no user pain here and adding motion would fight its character.
- **Full accessibility program.** Real WCAG gaps, but not release-blocking for the target user. Ship the two cheap wins now (live region on the streaming answer, composer label) if fixes 6/7 touch those files; schedule the rest for 1.1.1.
- **Library/history performance** (main-thread search, index rebuild, snapshot writes) — invisible until libraries are large; 1.1.1 with a benchmark.
- **Model-quality benchmark matrix** (10+ Arabic prompts across 1B/3B/vision) — the smoke test in fix 9 is enough for release; the matrix is a 1.1.1 product decision.
- **Copy drift batch, per-message edit/delete, share target, TTS, hands-free voice, document indexing UX** — 1.1.1 or later; a user who cannot get a model never meets documents.

### 3.3 Three blind spots

1. **The honesty is inverted.** The team built an unusually honest model-state and download-state layer, then put the false claims at the very start of the journey (demo engine, "import from Settings", Arabic-first answering English, a meter that under-reports 3×). Trust is set by the first three screens, not by the fit verdict.
2. **The 2 048-token window is the product ceiling, and nothing says so.** "Ask your documents" caps attachments at 20 000 characters, ignores queued attachments in the budget, shows no counter, and turns an over-budget request into an empty answer. The user most likely to love this product — paste a document, ask a question — is exactly the one who hits the dead end.
3. **The first answer is the product's reputation, and it is the worst answer the app produces.** Cold TTFT 26.7 s vs 8.6 s warm; 1.93 vs 6.35 tok/s; 6.1–7.5 s to first frame with a model vs 2,769 ms clean. Optimising averages will hide this. Judge only the cold first answer, on target hardware, with the recommended model.

### 3.4 What the audit could not verify

Condensed from §4.5: release build and real ARM hardware; a TalkBack listening pass; the English UI, dark mode, non-default theme packs and enlarged text end-to-end; keyboard/IME overlap, the blank-answer trigger, dictation, attachments and document Q&A live; gated HF downloads; a metered-network live run; disk-full and process-kill runs; frame-level jank. Two root causes are open by design of this audit: **P0-7** (HF route — isolated to the route, not the network, but not to the line) and **P0-8** (model class vs template/prompt — the app ships the combination either way).

### 3.5 The re-audit acceptance test (one sitting, one real ARM phone, clean install)

Mobile-data-only first run reaches a working answer within three taps after consent · an HF repo download completes 5/5 with the right byte count · no send can produce an empty non-failed answer · an 8 000-character paste either answers or says why · the recommended model's first three Arabic answers are readable, or the user was warned and offered a stronger one · scrolling up during a stream is not stolen · the displayed speed matches the engine log · a running download is visible from chat · About and the store listing name the three network features · a TalkBack pass reads the streaming answer and the composer.


## 4. Evidence

Everything in §2 and §3 is traceable to one of four kinds of evidence. Where a claim rests only on code reading, it says so and is marked with its confidence.

### 4.1 The audited build

- `app.sirrak` 1.1.0, versionCode 14, **debug** APK `app/build/outputs/apk/libre/debug/app-libre-debug.apk` (223,217,333 B), installed on two emulators.
- HEAD at audit time is `e28a9b6` ("Store: stop claiming 1.1.0 is an already-signed APK"), whose diff touches only `docs/27`, `store/listing/{ar,en}/whats_new.txt` — **no app source**. The installed APK was built from the same app sources; the working tree's uncommitted changes are the owner-rename sweep (`alshahia` → `ahmadmhmdsy`) in markdown/site/config URLs, not UI code. No auditor edited any source file: `git status` shows only the new `docs/33-…` and `docs/audit-1.1.0/`.
- **Release build, real phone and real network were not audited.** Tokens/s and TTFT are emulator numbers (llama.cpp logs `Unexpected CPU variant for x86` — generic kernel). The *shape* of the experience (silence before the first token, a meter that disagrees with the engine, no progress while loading) is UI-level and does transfer; absolute numbers do not.

### 4.2 Device A — `emulator-5554` (the founder's device, 720×1280 @320 dpi, API 36, 6 GB)

Used for baseline capture only; a third party kept driving it during the audit (interference log in [EVIDENCE.md](../build/ux-audit/EVIDENCE.md)). It is where the model was already installed, so it supplies the "with a model" measurements: cold start 6.1–7.5 s, model load 1,273 ms, peak RSS 1,434 MB, TTFT 26.7 s, 73 tokens in 64.5 s, engine 1.93 tok/s vs meter ≈0.4–0.7.

- screenshots: `build/ux-audit/shots/` (9 PNGs) — **known defect: `00-baseline-settings-root-ar.png` is mislabelled; it is the Library → Models screen.** Node tables and the raw logcat (`logs/logcat-full-1901.txt`, `logcat-1906.txt`) are in the same folder.
- caveat: because a foreign driver typed into the app, the two Arabic turns in shots 15/16 are not this team's script; the outputs were cross-checked against the engine log before being used (see 03-…:SND-08).

### 4.3 Device B — `emulator-5556` (clean AVD `review36b`, 1080×2400 @420 dpi, API 36, 4 GB)

A fresh install and a complete first-run journey, captured without any external interference: 39 screenshots `build/ux-audit/shots-clean/clean-01…39` with UI hierarchies and node tables in `build/ux-audit/logs-clean/`, the timeline in `build/ux-audit/EVIDENCE-clean.md`, the capture driver in `build/ux-audit/tools-clean/`. This pack is the authority for: onboarding, the no-model failure and its wrong CTA, Library segments, the HF flow (search → files → quantisation → probe → verdict → estimate), the download lifecycle **including a captured failure and three retries**, the curated 491 MB download to completion, the model-ready chip, and the first streamed answer (`clean-39`).

Two facts from this pack that only a clean run could produce: a fresh install (no model, no history) cold-starts in **2,769 ms**, and the HF download path failed instantly with `errorCode "network"` three times on validated unmetered Wi-Fi while the curated download started normally seconds later.

### 4.4 Code

Every `where` in the area reports is a `path:line` at HEAD. The most load-bearing citations were re-read by the Lead and the council; the rest were written by the specialist who owns that area and are reproducible from the tree.

### 4.5 What the audit did not verify

Stated plainly because it bounds the conclusions:

1. **Release build and real hardware.** No R8 build, no baseline profile effect, no real GPU/NPU numbers, no thermal behaviour. (P0/P1 UX defects are build-independent; the timing numbers are not.)
2. **TalkBack audio itself** — the accessibility findings rest on the uiautomator accessibility tree, sampled node tables and code, not on listening to TalkBack.
3. **End-to-end English pass, dark mode, theme packs other than the default, 130 %/200 % font scale** — not captured; a11y findings there are code-gated and marked medium confidence.
4. **Keyboard/IME overlap, mid-stream scroll fight, the blank-answer trigger, dictation, attachments and RAG document Q&A** — code-proven with mechanisms, not reproduced on a device (no speech model installed; the blank-answer trigger needs a >2 048-token paste).
5. **Real network failures** (no DNS blackhole, no 4xx injection) — except the HF download failure that happened to be captured.
6. **The HF download failure's root cause** — device-proven, cause not yet identified; captured in §2 as an open defect with a repro request.
