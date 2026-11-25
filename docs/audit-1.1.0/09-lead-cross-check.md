# Lead cross-check notes (independent findings, for the council to merge)

These are findings the Lead verified directly in code/device while the area reports were being written.
The council-reviewer must dedupe them against the area reports and keep the strongest evidence for each.

## LC-01 — Onboarding promises a working demo chat that the shipped build cannot deliver
- Severity P0 · Confidence high · Effort S
- Where: onboarding model step, `feature/models/src/main/res/values/strings.xml:96` (`onboarding_model_demo_note`) + `OnboardingScreen.kt:203-208`; engine resolution `app/src/main/java/app/sirrak/AppComposition.kt:303-312`; failure path `feature/chat/src/main/java/app/sirrak/feature/chat/ChatStore.kt:460`
- Now: the note says "Without a model the demo engine runs: explore chat and settings, then install a model later", and the button "Try without a model" (`onboarding_model_try_without`) finishes onboarding with no model. But the shipped config is `engine.provider = "llamacpp"` and the demo engine is only installed for an *unknown* provider id, so chat without a model produces the `NO_MODEL` failed turn.
- Why it hurts: the very first promise the app makes about its core function is false on the first run; the user's first message fails.
- Fix: either install the demo engine as the fallback when the configured engine has no model, or rewrite the copy/buttons to say the assistant needs a model and skip means "you can look around, chat will work after you get a model".
- Verify: fresh install, skip onboarding at the model step, send a message, read the result.

## LC-02 — The no-model error sends the user to the place model management left
- Severity P0 · Confidence high · Effort S
- Where: `feature/chat/src/main/res/values/strings.xml:18-19`; `ChatScreen.kt:356-360`; `ChatPlugin.kt:87`
- Now: `error_no_model` = "No model is loaded yet. Import a GGUF file in Settings, then try again", the action is "Open settings", and it calls `navigator.select("settings")`. Round 1 (`docs/32` P4) moved model management out of Settings into Library; there is no model import in Settings any more. The model chip's own no-model sheet, by contrast, routes to `navigator.select("library")` (`Panels.kt:93, 232`).
- Why it hurts: the one dead-end state a new user is most likely to hit gives a wrong instruction and a wrong destination; the same app routes the same job two different ways.
- Fix: one constant for "where models live" (Library → models), used by the chip, the error action and the copy; delete the Settings wording.
- Verify: no model installed, send a message, tap the action.

## LC-03 — The transcript auto-scrolls to the bottom on every token, even while the user reads
- Severity P1 · Confidence high · Effort S-M
- Where: `feature/chat/.../ChatScreen.kt:171-188`
- Now: `LaunchedEffect(store.items.size, streamingLength)` runs on every token (streamingLength changes each chunk) and calls `scrollToItem(lastIndex)` then `scrollBy(10_000f)`, unconditionally.
- Why it hurts: a user who scrolls up during a long answer is yanked back down on the next token — they cannot read the start of the answer, a document, or an older turn while it streams. It is also a layout every token (see the perf report).
- Fix: follow only while the user is near the end (e.g. last visible index == lastIndex and not "user scrolled up since send"); show a "jump to latest" affordance when detached.
- Verify: send a long prompt, scroll up immediately, watch whether the view stays where the user put it.

## LC-04 — Copy puts raw Markdown on the clipboard
- Severity P2 · Confidence high · Effort S
- Where: `ChatScreen.kt:305-308`
- Now: `clipboard.setText(AnnotatedString(item.text))` — the Markdown source (`**bold**`, `| tables |`, code fences, `## headings`) is copied verbatim.
- Why it hurts: pasting an answer into WhatsApp/Word shows asterisks and pipes; the user has to clean it by hand, and Arabic mixed with `#` at line start reorders badly.
- Fix: offer plain-text copy as the default action (strip Markdown) with "copy as Markdown" as a secondary choice; or copy plain and keep Markdown behind a long-press.
- Verify: copy an answer containing bold + a list, paste into a plain-text field.

## LC-05 — "Retry" is offered for failures that cannot succeed on retry
- Severity P2 · Confidence high · Effort S
- Where: `ChatScreen.kt:352-361`, `ChatStore.kt:488-499`
- Now: the failed-turn row always shows "Try again"; for `NO_MODEL` retry re-runs the same missing-model branch and produces the same failure. For `PROCESS_LOST` retry is right; for `NO_MODEL` the only useful action is "get a model".
- Why it hurts: the user taps the offered fix and gets the identical error — the app teaches them its actions do not work.
- Fix: NO_MODEL → single primary "Get a model" (Library); retry only where retry can change the outcome.
- Verify: no model, send, tap retry twice.

## LC-06 — Suggestion cards send immediately; the user cannot see or edit the prompt first
- Severity P2 · Confidence high · Effort S
- Where: `ChatScreen.kt:243-251`
- Now: tapping a suggestion calls `store.send(suggestion.prompt)` directly.
- Why it hurts: the first thing a new user taps fires a request they did not get to read or adjust (name, topic) — and if no model is loaded, it fires a failure. The card also does not become the user's own words.
- Fix: tap fills the composer (focused, cursor at end) with the suggestion; a second tap or the send button sends.
- Verify: tap a suggestion on the empty state.

## LC-07 — Copy says "switch the engine", but no engine switch exists
- Severity P2 · Confidence medium-high · Effort S
- Where: `feature/chat/src/main/res/values/strings.xml:17` ("The model is not available right now. Retry or switch engines.")
- Now: the engine is chosen by build config (`app.json engine.provider`); no screen lets the user switch it.
- Why it hurts: the copy offers a control that does not exist, which reads as a broken feature.
- Fix: drop "or switch engines", or expose the choice if it is meant to be user-facing.
- Verify: grep for any UI writing `engine.provider`.

## LC-08 — Unload is the loudest action on the loaded-model passport
- Severity P3 · Confidence high · Effort S
- Where: `app/.../Panels.kt:171-176`
- Now: on the Ready sheet the filled `Button` is "Unload", with "Open Library" as a text button and the model page as another text button. The consequence (chat stops answering) is not stated.
- Fix: make "open model page / library" the primary route; put unload behind a text button with a one-line consequence.
- Verify: chip → passport on a loaded model.

## LC-09 — No keyboard send
- Severity P3 · Confidence high · Effort S
- Where: `core/design/.../Chat.kt:121-124` (`imeAction = ImeAction.Default`, `maxLines = 6`)
- Now: Enter always inserts a newline; sending requires the button. That is defensible for multi-line prompts, but there is no `KeyboardActions` and no "Enter sends / Shift+Enter newline" affordance or setting.
- Fix: keep newline as default but add a settings option "Enter sends the message" (a11y users on hardware keyboards and power users expect it).
- Verify: type with a hardware keyboard.

## LC-10 — Onboarding download bypasses the new durable queue
- Severity P2 · Confidence medium (verify) · Effort M
- Where: `OnboardingScreen.kt:93-123` (uses `ModelDownloader` + `modelStore.importFile` directly), versus `docs/32` P2's `DownloadBridge`/`DownloadQueue` path used by Library.
- Now: the first-run download is driven by the old direct downloader, not the persisted queue; the queue's honest rows, pause/resume and metered question are not part of the first-run experience, and an install started in onboarding may not appear in Library → Downloads.
- Why it hurts: the first model download is the longest wait in the product; it is the one place a durable, resumable, visible queue matters most.
- Fix: route onboarding's download through the same bridge/queue (one download path, as round 1 claimed).
- Verify: start a download in onboarding, open Library → Downloads, check whether the row exists and survives an app restart.

## LC-11 — Download failure in onboarding always blames the network
- Severity P2 · Confidence high · Effort S
- Where: `OnboardingScreen.kt:304-312` (always `models_reason_network`)
- Now: any `DownloadPhase.FAILED` shows the network reason, whatever the real cause (disk full, checksum, 403, cancelled).
- Fix: use the failure reason the downloader already carries (the Library path does), and offer the matching action.
- Verify: make a download fail for a non-network reason (e.g. cancel) and read the onboarding copy.

## LC-12 — Onboarding ends with an Activity recreate
- Severity P3 · Confidence medium · Effort S
- Where: `OnboardingScreen.kt:89-92`
- Now: `onboardingStore.complete(...)` then `activity.recreate()` — the shell appears after a full activity restart (visible flash; on slow devices a longer blank).
- Fix: drive the shell swap from the observed onboarding state (the composable already collects it) and drop the recreate.
- Verify: finish onboarding on the emulator and watch the transition.

## LC-13 — No timestamps or day separators in the transcript
- Severity P3 · Confidence high · Effort S
- Where: `app/src/main/assets/config/app.json` `ui.showTimestamps = false`; `ChatScreen.kt` has no date grouping
- Now: a restored conversation shows turns with no indication of when they happened.
- Why it hurts: the app is explicitly a long-lived local notebook (nominal: lecture notes, contracts); a user returning a week later cannot tell what is current.
- Fix: a subtle day separator between turns from different days (no per-message clock needed).
- Verify: open an old conversation.

## LC-14 — Attaching a photo is a three-step flow
- Severity P2 · Confidence high · Effort M
- Where: `ChatScreen.kt:442-456, 465-490, 525-543`
- Now: paperclip → attachment sheet → source dialog (gallery/camera) → system picker. The paperclip is the only route; there is no camera shortcut in the composer.
- Fix: keep the sheet, but make the last-used source a one-tap action, or add explicit camera/gallery icons to the sheet.
- Verify: time the photo attach path.

---

## Lead visual notes (from live evidence pack, to be merged/deduped)

### LV-01 — The send button's mirrored glyph reads as "back"
- Severity P2 · Confidence high (screenshot 11) · Effort S
- Where: `core/design/.../Chat.kt:158` `Icons.AutoMirrored.Filled.Send` at 48 dp in a filled circle, RTL.
- Now: in Arabic the send control is a left-pointing arrow in a circle — visually identical to the back arrow family used in the app (history screen back arrow points right, so the pair is confusing at a glance); disabled it is a beige circle with that arrow, which reads as "go back" rather than "send".
- Fix: use a send glyph that does not mirror into an arrow (paper plane), or keep the arrow and add the word "إرسال"; at minimum test with 5 users for "what does this button do".
- Verify: screenshot of composer in AR + EN.

### LV-02 — The model chip is a truncated machine filename with an unexplained dot
- Severity P2 · Confidence high (screenshot 11) · Effort S
- Where: `Panels.kt:70-84`, chip label = `current.label` (installed display name), status shown by `ModelChip` dot.
- Now: the header reads "…qwen2.5 1.5b instruct" (leading ellipsis, quant suffix lost) with a small orange dot and no word. A first-time user cannot tell whether this is loaded, ready, or warning; the ellipsis makes it look broken.
- Fix: a friendly display name ("Qwen2.5 1.5B") plus a state word/dot with a legend on first run; full facts in the sheet.
- Verify: chip on a long filename and on short one, AR + EN.

### LV-03 — The transcript shows no time context at all
- Severity P2 · Confidence high (screenshot 12) · Effort S-M
- Where: `ChatHistory.kt` conversation rows show title/snippet/"N رسالة" only; `app.json ui.showTimestamps=false`.
- Now: neither the history list nor a restored transcript says when a conversation happened. A returning user with a week of notes cannot order them.
- Fix: relative date in the history row ("أمس", "١٢ نوفمبر") and day separators in the transcript.
- Verify: two conversations created on different days (or fake timestamps in files).

### LV-04 — A floating "اسأل مستنداتك" button sits between the token meter and the composer
- Severity P2 · Confidence high (screenshot 11) · Effort S
- Where: `ChatScreen.kt:384-396` `DocumentScopeRow`.
- Now: an outlined pill "اسأل مستنداتك" appears above the composer, visually detached from both the answer above it and the input below it; it is the only way to discover document grounding and it is not obvious what it does until tapped.
- Fix: attach it inside the composer as a scope chip (like the attachment chips) or label it as a statement of scope ("الإجابة من مستنداتك") with a scope picker behind it.
- Verify: RAG enabled, empty and full scope, AR.

### LV-05 — Ordered-list markers in Arabic: CHECKED, NOT A DEFECT (kept as calibration)
- Verified at 2x zoom (build/ux-audit/zoom/answer-list.png) that the ordered-list marker renders at the RTL start (right margin) with its period after the numeral in reading order, and the wrapped continuation aligns to the text column. Arabic prose, sentence-final periods and mixed Latin digits all render correctly. Do not report this as a bug.
- What would still be worth a golden test: numbered + bulleted lists inside an English answer shown in the Arabic UI, and a list item that starts with a Latin word (e.g. "API") to confirm the marker does not jump.

### LV-06 — "≈ 0.7 رمز/ث" is what the meter showed on the only observed answer
- Severity P1 if real on target hardware · Confidence low (emulator, unverified on phone) · Effort L
- Where: `ChatMeter.kt` speed readout; `docs/24` measured only whisper; chat speed was never gated.
- Now: the single live answer seen in the evidence pack reports ≈0.7 tokens/second for a ~30-token answer (≈40 s for two sentences). It is honest (≈, تقديري) but it means the core experience is unusable at this rate.
- Fix: measure on the target phone class (Redmi Note 7 / A54) with the recommended compact model; if the number holds, the model tier, thread count and context defaults must change before launch.
- Verify: tok/s on a physical arm64 phone with the recommended model, three prompts.
---

## Second pass — found by the Lead while reviewing the clean-install evidence (verify or reject)

### LV-07 — The HF repo screen's header breaks on any real repository id (P1, verified visually at 1080×2400)
- **Evidence:** `build/ux-audit/shots-clean/clean-16-hf-q4km-selected-verdict-ar.png`, zoom crop `build/ux-audit/zoom/hf-top.png`; node table `logs-clean/clean-16-…-nodes.txt` shows the title node bounds `[53,139][943,469]` — a **330 px tall** text node for one line of text.
- **What happens now:** the page title is the raw repo id (`Qwen/Qwen2.5-0.5B-Instruct-GGUF`), rendered in the display serif at header size with no `maxLines`/ellipsis; it wraps into three lines, the third being a single letter ("F"), and the back glyph floats detached at mid-height of the second line. The same repo id is then repeated as the card title below.
- **Why it hurts:** this is the screen where the user decides which model to install — the product's most technical, highest-stakes moment — and it looks broken. It also discredits the (good) fit/probe/verdict work underneath it.
- **Fix:** title = a human label ("نموذج من Hugging Face") with the repo id as a secondary, single-line, ellipsised subtitle; `maxLines = 1` + `TextOverflow.Ellipsis`; never apply the Arabic prose face to a Latin identifier.
- **Verify:** open three repos with long ids; header stays one line and the back control stays on the title row.

### LV-08 — HF metadata line reads as machine output (P3)
- **Evidence:** same shot: "· الرخصة: apache-2.0 · السياق من Hugging Face: 8192 · المعمارية من Hugging Face: qwen2" — it opens with a stray "·" and repeats "من Hugging Face" twice.
- **Fix:** "رخصة apache-2.0 · سياق 8192 · معمارية qwen2" on one line, or drop the prefixes entirely.
