# 03 — The result interaction: how the user asks, and what comes back

**Member:** interaction-auditor · **App:** Sirrak 1.1.0 (vc 14), emulator-5554, 720×1280 @320 dpi, API 36 · **Build:** installed debug build
**Owns (brief §7):** composer, streaming, message actions, citations, attachments, dictation, keyboard, selection, long-press, scrolling, stop/regenerate/copy/share.
**Evidence used:** source at the audited tree; device evidence from build/ux-audit/ (shots 00, 10–16, 20; UI dumps and node tables in logs/, incl. s1/lib1 stream states; logcat-full-1901.txt and logcat-1906.txt carry the engine's own timing lines). Two later shots (15, 16) and the s1/lib1 captures were taken while a third party typed on the device (EVIDENCE.md interference log); the outputs they show are the app's own and are cross-checked against the engine log. Every finding cites code path:line and, where the device had reached the state, a shot/node-table. Where the device could not yet demonstrate a code-proven defect I say so; nothing here is invented.

## Executive summary

1. **Strongest:** the answer surface is real, not a mock — true token streaming, a 48 dp Stop that swaps in place, markdown with per-block RTL direction, a token meter, a per-answer report action, and a library that survives restarts with atomic writes; the four-state model chip and its doorway sheet (app/src/main/java/app/sirrak/Panels.kt:59-97) are the best-honesty pattern in the build.
2. **Biggest risk — the blank-answer dead end (P0):** a question or paste that does not fit the 2 048-token window ends as an *empty, non-failed* answer: native build_prompt fails, JNI returns without a sink event, the engine reports Completed, the store finalizes blank, and every action (copy/retry) is hidden — no error, no way out (llama_jni.cpp:773-778 → LlamaCppEngine.kt:265 → ChatStore.kt:1040-1051).
3. **Biggest risk — the wait and the record:** the first token takes **26.6 s** on this device and the only sign of life is a 12 dp ornament (logcat-full-1901.txt:124605,125305), while auto-scroll drags the reader back on every token (ChatScreen.kt:174-188) and stopping keeps a cut-off answer that looks finished and is persisted (ChatStore.kt:568-586).
4. **Counts:** 46 findings — **1 P0 · 12 P1 · 25 P2 · 8 P3**. Late device evidence (shots 15/16) added the two most damaging result findings: **SND-08** (an Arabic question was answered in English on the first answer of the audit) and **SND-09** (the Arabic re-ask produced a single ungrammatical clause while the same request in English produced a full letter). Round-1 claims checked in Appendix A: V1 half-true (chip routes to Library; the failure CTA still routes to Settings), V7 present but unsafe (copy=markdown, regenerate destructive, new chat kills the stream), V8 reachable but its promises overclaim (citations "sources used", "can be opened").
5. **Unverified on device at write time:** keyboard/IME overlap, mid-stream scroll behaviour, the blank-answer trigger, dictation (no speech model installed), attachment/image and RAG flows — these are code-cited with Confidence marked; build/ux-audit had shots 00–14 only (EVIDENCE.md). One evidence-pack defect noted: 00-baseline-settings-root-ar.png is in fact the **Library → Models** screen, not Settings.

## How to read

Area codes: **CMP** composer/asking · **SND** send → first token → stream · **ANS** the answer and its actions · **CITE** citations & document Q&A · **ATT** attachments & images · **DIC** dictation · **LIB** conversation library & recovery.
Confidence is the confidence in the *defect*, not in the fix. "Verify" is the smallest proof that the fix works. Findings follow the brief's schema (§4).

---

## CMP — composer

### CMP-01 — With no model you can still compose and send; the failure is a chat turn
- **Severity:** P1  **Confidence:** high  **Effort:** S
- **Where:** chat → empty state + composer (feature/chat/src/main/java/app/sirrak/feature/chat/ChatStore.kt:447-462, ChatScreen.kt:420-436) · shot 13-history-menu-ar.png (chip "لا يوجد نموذج · غير محمّل", composer fully enabled, suggestion card tappable)
- **What happens now:** send() appends the user's question, persists it, and only then checks the engine; with no model it attaches a failed turn (:459-462). Every "جرّب أن تسأل" card sends immediately, so a first-time user's first act becomes an error bubble.
- **Why it hurts:** the most common first-run state costs the user a typed question and teaches "the app does not answer". The failure looks like an answer defect, not a setup state, so the correction is guessed rather than shown before the effort.
- **Fix:** while engine.status is not Ready, disable send and the suggestion cards, and put one line above the composer: "لا يوجد نموذج بعد — استورده من المكتبة" whose tap opens the model chip's sheet. Keep the draft.
- **Verify:** fresh no-model state → send disabled + the line + one-tap route; type a question → nothing is lost.

### CMP-02 — A *loading* model is reported as "no model", and Retry repeats the false error
- **Severity:** P1  **Confidence:** high  **Effort:** S
- **Where:** ChatStore.kt:459, :498, :541 all test engine.status.value !is EngineStatus.Ready → failedTurn(NO_MODEL); copy error_no_model, "لم يُحمَّل أي نموذج بعد…" (feature/chat/src/main/res/values/strings.xml:18); states in core/runtime/src/main/java/app/sirrak/core/runtime/Models.kt (EngineStatus.Loading(label))
- **What happens now:** Loading falls into the same branch as "no model": the answer bubble says no model is loaded while a model is in fact loading (the device logged MODEL_LOADED ms=1273, and first token 26.6 s later — logcat-full-1901.txt:122331,125305). Tapping "إعادة المحاولة" re-runs the same non-Ready test and fails again identically.
- **Why it hurts:** a false state at the exact moment the user has just picked a 1 GB model reads as a broken model; users re-import or delete it. It is the honesty failure the chip was rebuilt to remove, still present on the failure path.
- **Fix:** give EngineStatus.Loading its own turn state — "النموذج قيد التحميل… (label)" with a spinner, Retry disabled until Ready, auto-resolving when it becomes Ready. Reserve NO_MODEL for Unloaded with no selection.
- **Verify:** load the model from Library, send immediately → loading bubble resolves by itself; unit test on ChatStore with a Loading status.

### CMP-03 — No counter or limit in the field, though the real window is a few thousand characters
- **Severity:** P2  **Confidence:** high  **Effort:** S
- **Where:** core/design/src/main/java/app/sirrak/core/design/Chat.kt:120-126 (maxLines = 6, heightIn(max = 160.dp), no length cap) · budget from app/src/main/assets/config/app.json (contextTokens: 2048, maxTokens: 512 → 1 536-token prompt budget) · meter computed only from saved turns, below the transcript (ChatStore.kt:754-769)
- **What happens now:** a 7 000-character question is accepted silently; before sending, the meter still reads "128 / 1536" because the draft is not part of the estimate. The first warning arrives only after the request was already fired.
- **Why it hurts:** the longest, highest-value questions (pasted Arabic text, a contract paragraph) are exactly the ones that overflow, and the user has no way to know the limit exists.
- **Fix:** count the draft into the meter live and turn it into a warning at ~85 % of the prompt budget; on overflow block send with the remedy ("قصّر السؤال أو احذف رسائل").
- **Verify:** paste 7 000 chars → the meter crosses the budget before send and send shows the remedy.

### CMP-04 — The keyboard has no send action and nothing dismisses it over the answer
- **Severity:** P3  **Confidence:** high  **Effort:** S
- **Where:** Chat.kt:123 ImeAction.Default; no clearFocus / LocalFocusManager anywhere in the app (grep over **/*.kt: 0 matches); composer imePadding() at ChatScreen.kt:190
- **What happens now:** the IME action key is "return" (newline only); after sending, the field keeps focus so the keyboard keeps covering the newest answer, and scrolling the transcript does not hide it. The only exit is the system back gesture.
- **Why it hurts:** each answer costs an extra back-press to read; users tap the return key expecting send, get a newline, and conclude the composer is odd.
- **Fix:** blur the field after send and clear focus when the transcript is scrolled or tapped; keep the 48 dp send button as the only send affordance so Return stays "newline" (which multiline questions need).
- **Verify:** send with keyboard open → it closes and the answer is visible; scroll transcript → keyboard hides.

### CMP-05 — The draft survives a conversation switch and can be sent into the wrong chat
- **Severity:** P2  **Confidence:** high  **Effort:** S
- **Where:** ChatScreen.kt:90 (var input by rememberSaveable) with ChatStore.kt:629-645 (newConversation/openConversation never touch the draft) · dictation appends to the same field (ChatScreen.kt:95-98)
- **What happens now:** type a question, open another conversation or a new chat from the library, and the text is still in the composer; send puts it in the newly opened conversation. Going back leaves the old chat without the draft.
- **Why it hurts:** a question answered in the wrong chat, and a privacy expectation ("this draft belongs to that conversation") broken with no signal.
- **Fix:** keep a draft per conversation (map conversationId → text) or clear on adopt with a one-time "لم يُرسل نصّك" note.
- **Verify:** type in chat A, switch to B, back to A: text is with A; send lands where it was written.

### CMP-06 — The paperclip greys out during streaming with no explanation
- **Severity:** P3  **Confidence:** high  **Effort:** S
- **Where:** ChatScreen.kt:420-436 composer wiring; Chat.kt:71-166 leading icon disabled while streaming with only a tint alpha
- **What happens now:** mid-answer the attach icon becomes a faded glyph; tapping does nothing and no text says why (attachments cannot be queued for the next turn).
- **Why it hurts:** a dead-looking control for the whole minute an answer streams; the user cannot prepare a follow-up (contrast dictation, which stays usable).
- **Fix:** either allow queuing the attachment for the next turn (the queue already exists) or show "انتظر انتهاء الإجابة" on tap.
- **Verify:** tap the paperclip mid-stream → tooltip or queued chip instead of silence.

---

## SND — send → first token → stream

### SND-01 — A question that does not fit the window yields an empty answer: no error, no retry, nothing
- **Severity:** P0  **Confidence:** high  **Effort:** M
- **Where:** engine/llamacpp/src/main/cpp/llama_jni.cpp:773-778 (build_prompt false → LOGE + return, **no sink callback and no failure**) · engine/llamacpp/src/main/java/app/sirrak/engine/llamacpp/LlamaCppEngine.kt:265 (trySend(EngineChunk.Completed(emptyList())) unconditionally after the native call returns) · ChatStore.kt:1040-1051 (finalises whatever text exists, leaves failed = false) · ChatScreen.kt:297 (actions need non-blank text) and ChatScreen.kt:332 (reason/retry only when failed) · ChatStore.kt:796 (turnOf will not persist a blank answer)
- **What happens now:** the native prompt builder drops the oldest user/assistant pairs; if the system prompt plus the current question still exceed n_ctx it returns false. The JNI function returns silently, the engine emits Completed with no text, the store finalises a blank non-failed assistant, the bubble renders empty, copy/regenerate are absent (blank text), Retry is absent (not failed), and the blank turn is not even saved. The trigger is reachable through the UI: the shared-text limit is 20 000 characters (core/runtime/src/main/java/app/sirrak/core/runtime/Attachments.kt) ≈ 5 000 estimated tokens against a 2 048-token context; typing ~6 000 characters reaches the same state. A scope cancellation mid-stream reaches a similar blank final state via ChatStore.kt:1052-1057.
- **Why it hurts:** the core promise ("ask, get an answer") fails with zero information: the question sits there, the answer area is empty, and no control offers recovery. After a restart even the empty bubble is gone, so nothing marks that anything was asked. This is an unrecoverable dead end.
- **Fix:** make the native failure a typed engine failure (PROMPT_TOO_LONG) instead of a silent return, surface it as a failed turn with copy that names the remedy ("سؤالك أطول من نافذة النموذج — قصّره أو احذف رسائل") plus Retry; add a pre-flight budget check in send() so the request is refused before the native call.
- **Verify:** unit test: fake engine that completes with empty text → the store produces a failed turn with the new reason and Retry visible; device: paste 8 000 characters → explicit error, not a blank bubble; logcat shows the new reason. *(Device not yet exercised at write time — the code path is complete and the arithmetic is guaranteed.)*

### SND-02 — Auto-scroll re-pins to the bottom on every token; you cannot read a streaming answer
- **Severity:** P1  **Confidence:** high  **Effort:** S
- **Where:** ChatScreen.kt:174-188 — LaunchedEffect(store.items.size, streamingLength) runs on every delta, calls scrollToItem(lastIndex) then scrollBy(10_000f); no check of how far the user is from the bottom and no "jump to newest" control
- **What happens now:** the effect is keyed on the streaming text length, so each token re-scrolls to the last item and hard-follows. Any scroll-up is undone within one token (≈0.5 s at the measured 1.93 tok/s). The only escape is Stop, which ends the answer.
- **Why it hurts:** a 73-token answer took **64.5 s** wall time on this device (logcat-full-1901.txt:125305: ttft_ms=26723.8, gen_ms=37781.9); the user starts reading immediately and is dragged down, unable to check the part already written — the very thing streaming is for. Shots 10/11 show the transcript parked hard at the bottom, consistent with the code.
- **Fix:** follow only when the user is at the bottom (!listState.canScrollForward or distance < 24 dp); otherwise freeze the position and show a "↓ جديد" pill that scrolls down when tapped.
- **Verify:** send with a long prompt, scroll up mid-stream → position holds and the pill appears; tap it → following resumes.

### SND-03 — For 27 s the only sign of life is a 12 dp ornament; no stage, no time
- **Severity:** P1  **Confidence:** high  **Effort:** S
- **Where:** Chat.kt:168-210 — AssistantProse shows InkingMark() only (12 dp rotating mark, 2 600 ms); StreamingLabel exists but is never used (Chat.kt:443); speed hidden while streaming (ChatMeter.kt:54); grounded turns retrieve before they generate (ChatStore.kt:931-996) with the same ornament
- **What happens now:** from send until the first token the screen shows a rotating mark — no words, no elapsed time, no distinction between "searching your documents", "reading your question" (prefill) and "writing". The app knows all of these stages; the engine even logs prefill_ms/ttft_ms, but the UI states none. The live stream state (build/ux-audit/logs/lib1-nodes.txt, captured at 19:07:05 with the Stop button showing) contains **no node at all** between the question and the meter: the in-progress answer is not in the accessibility tree, so a screen reader announces nothing while the answer generates.
- **Why it hurts:** **26.7 s** of unexplained motion before the first character (logcat-full-1901.txt:125305); a second run measured 27.4 s (logcat-1906.txt:1286, ttft_ms=27376.3), so this is the norm on this device, not a cold-start outlier. Users read motion-without-words as "frozen or stuck", tap Stop, or leave. This is the single strongest "the app feels broken" signal in the build.
- **Fix:** one live status line in the answer bubble driven by store state: "أبحث في مستنداتك…" → "أقرأ سؤالك…" (prefill) → "أكتب… ١٢ رمزًا · ٠:٠٨"; the ornament stays as a garnish. Stage changes are the cheapest trust win available.
- **Verify:** send on this device → the line changes at least twice before the first token; a screenshot at t+5 s shows elapsed time.

### SND-04 — Stopping after the first token keeps the text but presents it as a finished answer
- **Severity:** P2  **Confidence:** high  **Effort:** S
- **Where:** ChatStore.kt:568-586 — non-blank partial → copy(streaming = false) only, then persist(); the UI gate at ChatScreen.kt:297 then shows the normal copy/regenerate row; turnOf (:796) persists the partial as a complete assistant turn
- **What happens now:** press Stop after a few tokens: the bubble looks complete, is saved, is exported and is fed into the next turn's history as a finished answer. Only a stop *before* the first token is labelled (error_stopped); there is no "stopped" state in between.
- **Why it hurts:** a truncated sentence becomes the conversation's record and the model's context; the user cannot tell it was cut, and a later answer may build on half a sentence. Stopping is the escape hatch of a slow local model, so this happens constantly.
- **Fix:** add stopped to the item, render a quiet row "أوقفت هذه الإجابة — أكمل / أعد التوليد", and keep it out of history() until the user accepts or regenerates it.
- **Verify:** stop mid-answer → marker present; the next request's prompt token count (logcat prompt_tokens) excludes the partial until accepted; export shows the marker.

### SND-05 — The displayed speed contradicts the engine's own measurement, and vanishes while it matters
- **Severity:** P2  **Confidence:** high  **Effort:** S
- **Where:** ChatStore.kt:1063-1068 — TokenEstimate.tokens(text) (chars ÷ 4) divided by the whole request wall time incl. prefill; shown by ChatMeter.kt:54-56, hidden whenever streaming
- **What happens now:** for the same turn the UI shows **≈ 0.7 رمز/ث** (shot 10-chat-existing-ar.png; build/ux-audit/logs/10-chat-empty-nodes.txt node 16 reads "≈ 0.7 رمز/ث", node 15 reads "128 / 1536 رمز تقديري") while the engine logged tok_s=1.93 (logcat-full-1901.txt:125305): the estimate under-counts Arabic tokens ~40 % *and* charges the 26.7 s prefill to generation. During the stream the speed line disappears entirely, so the meter shifts every turn.
- **Why it hurts:** the yardstick promises a product "honest about its limits (speed…)"; its own speed number is ~2.7× off its own log. Users calibrate "this phone is too weak" from a wrong figure.
- **Fix:** compute decode speed from the engine's reported counts and duration-after-first-token (the engine already emits them), show time-to-first-token separately ("أول رمز بعد ٢٧ ث"), and keep the last speed visible while streaming with "…" until the first token.
- **Verify:** compare the meter to SirrakLlama STATS tok_s for the same turn — within 10 %; the screenshot shows TTFT and decode speed as distinct numbers.

### SND-06 — Unclosed markdown markers flash as literal characters while the answer streams
- **Severity:** P3  **Confidence:** high  **Effort:** S
- **Where:** core/design/src/main/java/app/sirrak/core/design/Markdown.kt — the inline renderer appends unmatched markers literally (the parser documents "no nested lists, no inline images, no clickable links"); MarkdownText re-parses on every text change (remember(markdown))
- **What happens now:** mid-word the bubble shows **, a trailing # or a backtick until the closing marker arrives; list/heading blocks can show the raw prefix for a frame before they classify.
- **Why it hurts:** for a minute-long answer the user watches raw markup flicker, which reads as unfinished rendering rather than a stream.
- **Fix:** in streaming mode drop a trailing unmatched marker run for that frame (render emphasis-to-end-of-text); switch to the exact parse once complete.
- **Verify:** prompt for bold/list output, capture a mid-stream frame → no literal asterisks; the final render is byte-identical.

### SND-07 — "Retry" on a document answer repeats the request its own copy says cannot work
- **Severity:** P2  **Confidence:** high  **Effort:** S
- **Where:** ChatScreen.kt:332-361 — every failed turn gets "إعادة المحاولة"; ChatStore.retry (:488) re-runs the same question over the same scopedDocuments; the copy error_rag_no_sources itself says "جرّب توسيع النطاق أو إعادة الصياغة" (feature/chat/src/main/res/values/strings.xml:98)
- **What happens now:** an answer that found nothing in the selected scope offers a button that will find nothing in the same scope. The remedy the text names (widen the scope, rephrase) is not offered at the error.
- **Why it hurts:** the one recovery the app suggests is not the button it shows; a second identical failure convinces the user the documents feature is broken.
- **Fix:** for RAG_NO_SOURCES replace Retry with "وسّع النطاق" (opens the document picker) and "أعد الصياغة" (puts the question back in the composer).
- **Verify:** a grounded ask with no match → the two actions appear and the first opens the picker.

### SND-08 — An Arabic question was answered in English, and the letter shipped a literal "[Recipient]"
- **Severity:** P1  **Confidence:** high  **Effort:** S
- **Where:** the response language comes only from one English sentence inside the system prompt — app/src/main/assets/config/app.json engine.systemPrompt: "Answer in the language of the user's last message." — assembled into the prompt by the engine's build_prompt (engine/llamacpp/src/main/cpp/llama_jni.cpp) with no post-check of the answer's script and no response-language setting anywhere in chat or settings · device: shot 15-live-state-1905.png (Arabic question bubble, English answer below it) and its node table build/ux-audit/logs/s1-nodes.txt nodes 6-7: question "اكتب رسالة اعتذار قصيرة عن حضور اجتماع." → answer "Dear [Recipient], I apologize for being unable to attend the meeting today. I hope you are doing well. Best regards, Sirrak"; the same turn in the engine log: generated=28 tokens at 19:04:38 (build/ux-audit/logs/logcat-1906.txt:1286), and the interference log in EVIDENCE.md records the English answer to the Arabic turn
- **What happens now:** with the Arabic UI, an Arabic question and a language rule in the prompt, the answer came back entirely in English and left the placeholder "[Recipient]" unfilled. The only Arabic answer on the device (shot 16-concurrent-generation-1907.png) came after the user typed "أجب بالعربية فقط." in front of the same request (lib1-nodes.txt node 6) — a workaround the user had to invent, which also spends prompt budget.
- **Why it hurts:** a private Arabic-first assistant that answers in English has failed its most basic promise on the very first answer; for the target user the English letter is unusable as-is, and the unfilled placeholder is presented as a finished result. A language rule buried in an English system prompt is not a guarantee for a quantized 1.5B model — the app ships no detection, no setting, and no recovery, so the user must learn the prompt trick.
- **Fix:** put the rule in Arabic and repeat it as the last line of the prompt (last-line instructions dominate small models), add a visible "لغة الرد" choice (العربية / لغة السؤال) near the model chip, and check the first sentence's script after completion: on mismatch, show "أجاب النموذج بالإنجليزية — أعد التوليد بالعربية؟" instead of silently shipping it. Also teach the letter format (salutation, closing, placeholder convention) in the prompt so "[Recipient]" is replaced by a clearly marked field.
- **Verify:** same Arabic request on this device → answer in Arabic; with a deliberately English-biased prompt, the mismatch notice appears instead of an English answer; unit test on the script check.

### SND-09 — The same letter request in Arabic collapsed to one ungrammatical clause, with no format
- **Severity:** P2  **Confidence:** high  **Effort:** M
- **Where:** model output and prompt only — shot 16-concurrent-generation-1907.png: answer to "أجب بالعربية فقط. اكتب رسالة اعتذار قصيرة عن حضور اجتماع." is "عذراً، لم يمكني الحضور إلى الاجتماع اليوم."; contrast shot 15-live-state-1905.png where the same request in English produced salutation, body, sign-off; no format guidance in app.json systemPrompt, no few-shot examples, no answer-length minimum
- **What happens now:** the Arabic answer is a single clause that is also not idiomatic MSA ("لم يمكني الحضور" instead of "لم أتمكن من الحضور"), has no greeting, no closing and no name, so it does not satisfy "رسالة اعتذار قصيرة" while the English version does. The meter records it as a normal completed answer (99 / 1536, ≈ 0.4 رمز/ث).
- **Why it hurts:** the Arabic-first audience gets the weaker result from the same product, and nothing in the UI marks it as a poor or partial answer — the user must judge the grammar and rewrite it themselves, which removes the value of asking at all.
- **Fix:** add a short Arabic format contract to the prompt for letters/messages ("تحية، متن قصير، خاتمة واسم") plus one worked example; prefer an Arabic-stronger model in the recommended catalogue for the Arabic UI; consider a length floor and a second-pass "أكمل" when the answer is one sentence for a letter request.
- **Verify:** same request three times → answers contain a greeting and a closing and read as MSA; spot-check with a native reviewer (Sara/Abu Zaid).

---

## ANS — the answer and what you can do with it

### ANS-01 — Copy puts the raw Markdown source on the clipboard
- **Severity:** P1  **Confidence:** high  **Effort:** S
- **Where:** ChatScreen.kt:305-308 — clipboard.setText(AnnotatedString(item.text)) where item.text is the model's Markdown (the same string MarkdownText parses for display); confirmation at :151-159
- **What happens now:** copying an answer yields **, #, |, literal list markers and [1] markers; pasting into a message or a document shows markup, not the answer the user read.
- **Why it hurts:** copy is the **only** way an answer leaves the app (see ANS-02: no selection, no share, no per-answer share), so the single exit path emits source text. On an Arabic-first device this is also the main way answers get reused for study or work.
- **Fix:** copy the rendered plain text (the parser already produces the span tree) and add "نسخ كمصدر Markdown" as a second, explicit choice; show a toast.
- **Verify:** copy an answer containing bold + a numbered list → paste shows no asterisks or pipes; the markdown copy remains available and labelled.

### ANS-02 — Answers cannot be selected: no partial copy, no quote, no share
- **Severity:** P1  **Confidence:** high  **Effort:** M
- **Where:** no SelectionContainer in answer rendering — grep over the tree finds it only in app/src/main/java/app/sirrak/AboutLicences.kt:14,209; AssistantProse renders plain Text per block (Chat.kt:168-210); no share intent for an answer anywhere in the chat feature
- **What happens now:** long-press on an answer selects nothing and opens nothing; there is no text-selection menu and no share target. The only per-answer actions are three icons (ANS-05).
- **Why it hurts:** working with an answer means taking a sentence, a citation or a block quote — impossible here, so the user retypes or screenshots. Combined with ANS-01 the app satisfies neither "read it" nor "use it".
- **Fix:** wrap the prose blocks in SelectionContainer with a toolbar that offers copy/share; keep long-press for the per-answer menu.
- **Verify:** long-press a sentence → handles appear, selection copy preserves Arabic order; the share sheet opens with the selected text.

### ANS-03 — Regenerate destroys the current answer before a replacement exists
- **Severity:** P1  **Confidence:** high  **Effort:** M
- **Where:** ChatStore.kt:530-566 — removes the assistant item and calls persist() **before** launching the new stream; the trigger is an unlabelled icon (Chat.kt:211-260) next to copy
- **What happens now:** one tap and the answer is gone from the screen and from disk; the replacement then takes 30–65 s and can fail or be stopped at zero tokens, leaving the user with an error and no old answer (no undo, no snapshot, no "previous answer").
- **Why it hurts:** a destructive, irreversible action sitting where the user expects "try again"; the only undo is regenerating again at full cost. Regenerating is the standard loop with a small model, so it cannot be the riskiest control in the app.
- **Fix:** keep the old answer until the new one completes; show the new answer with a collapsed "الإجابة السابقة" (or an Undo snackbar). Label the action (ANS-05).
- **Verify:** force an engine failure on regenerate → the old answer is still readable and restorable; on success both versions are reachable until the user leaves the chat.

### ANS-04 — A failed turn's partial answer cannot be copied or kept
- **Severity:** P2  **Confidence:** high  **Effort:** S
- **Where:** ChatScreen.kt:297 — the action row is gated on !item.failed && item.text.isNotBlank(); the failed branch (:332-361) renders only reason + Retry
- **What happens now:** when generation dies mid-answer (engine error, process loss), the partial text is shown but the copy/regenerate row is removed, and Retry (ChatStore.kt:488) removes the assistant item, discarding the partial permanently.
- **Why it hurts:** an 80 %-complete answer is lost twice — not copyable, then deleted by the obvious next tap. On a slow device, engine failures are the expected path, not the rare one.
- **Fix:** show copy whenever text.isNotBlank(), whatever the failure; keep the partial as "الإجابة السابقة" when the user retries.
- **Verify:** kill the engine process mid-answer → the copy icon is present and works; Retry retains the partial in a collapsed row.

### ANS-05 — The three answer actions are unlabelled 20 dp glyphs with no explanation
- **Severity:** P2  **Confidence:** high  **Effort:** S
- **Where:** Chat.kt:211-260 (AnswerActions: copy / refresh, 48 dp targets with 20 dp glyphs, contentDescription only) plus the contributed flag (feature/privacy/src/main/java/app/sirrak/feature/privacy/ReportAnswerAction.kt:57-64) · device: build/ux-audit/logs/10-chat-empty-nodes.txt nodes 12-14 (lines 13-15) — "نسخ الإجابة", "إعادة التوليد", "الإبلاغ عن هذه الإجابة" at 40×40 px glyphs inside 48 dp targets
- **What happens now:** under every answer sit three icons of equal visual weight; nothing tells the user what they do until one is tapped, and the flag sits beside copy, so a mis-tap opens a report dialog.
- **Why it hurts:** discoverability of the result's main actions depends on icon literacy; the complaint action shares the prime position of the most-used action, and no text anywhere ("نسخ") teaches it.
- **Fix:** give copy a text label (or a first-use coach mark), move report into an overflow "…" menu with rename/copy, keep the 48 dp targets.
- **Verify:** a first-time-user walkthrough finds copy without being told; no accidental report dialogs in the journey log.

### ANS-06 — The user's own question has no actions: no edit, no resend, no copy
- **Severity:** P2  **Confidence:** high  **Effort:** M
- **Where:** the Item.User branch renders UserNote only (ChatScreen.kt:250-280, Chat.kt:309); ChatStore exposes no edit/resend API (grep: none)
- **What happens now:** a typo or a badly phrased question can only be fixed by retyping it entirely; the question cannot even be copied to edit elsewhere. The only way to re-ask is Retry/Regenerate on the *answer*.
- **Why it hurts:** with a 1.5B-class model, rephrasing the question is the normal working loop; the app makes the loop the most expensive path, so users stop refining and settle for bad answers. It is also the one long-press interaction every chat user tries first.
- **Fix:** long-press the user bubble → "تعديل وإعادة إرسال" (loads the text into the composer; sending replaces that exchange) and "نسخ".
- **Verify:** long-press the question → menu; edit + send → the turn is replaced, no duplicate pair appears in the export.

### ANS-07 — No per-exchange delete; the only delete removes the whole conversation
- **Severity:** P2  **Confidence:** high  **Effort:** M
- **Where:** no per-message delete in ChatStore or ChatScreen (grep: none); the only delete is the library's conversation delete (ChatHistory.kt:221-227 → ChatStore.deleteConversation:654)
- **What happens now:** to remove one bad answer, or one accidentally pasted private line, the user must delete the entire conversation from the library (with confirmation), losing every other exchange.
- **Why it hurts:** a privacy-first product promises "export/delete must be obvious"; the granularity that matches the risk (one answer mentions a client name) does not exist, so the user destroys more than intended.
- **Fix:** overflow on an answer/turn → "حذف هذا التبادل" with a confirm, removing the Q+A pair from the transcript and the file.
- **Verify:** delete one exchange → it is gone after reopen and absent from the export; other exchanges and attachments untouched.

### ANS-08 — The only copy confirmation is a silent 1.8 s icon swap
- **Severity:** P3  **Confidence:** high  **Effort:** S
- **Where:** ChatScreen.kt:151-159 (tick resets after 1 800 ms), Chat.kt:232-245; no snackbar or haptic anywhere in the chat surface
- **What happens now:** tapping copy swaps the glyph to a check for 1.8 s with no system feedback; a double tap copies twice with no visible difference.
- **Why it hurts:** the most-used action's only acknowledgement is easy to miss, so users copy twice and paste twice.
- **Fix:** a short Snackbar/toast "تم نسخ الإجابة" plus LocalHapticFeedback tick; keep the check icon as secondary feedback.
- **Verify:** copy → toast + haptic; TalkBack announces the copy result.

---

## CITE — citations and document answers

### CITE-01 — "المصادر المستخدمة" lists every retrieved passage when the model cited none
- **Severity:** P1  **Confidence:** high  **Effort:** S
- **Where:** ChatStore.kt:979-994 — cited.ifEmpty { numbered.sources.map { … } } synthesises citations from **all** retrieved chunks; label citations_label = "المصادر المستخدمة" (feature/chat/src/main/res/values/strings.xml:96), rendered by RagUi.kt:351-401
- **What happens now:** retrieval returns N chunks; if the answer contains no [n] the app attaches every retrieved chunk as a citation and labels the row "sources used". The code comment justifies it as provenance (small models drop markers), but the string asserts the model *used* them.
- **Why it hurts:** the product's differentiator is "answers grounded in your documents with citations"; telling the user a passage was used when the model never referenced it is an actively misleading provenance claim. It also silently widens "used" to "retrieved", which is not what a citation means.
- **Fix:** two honest states — "المصادر المستخدمة" when cited.isNotEmpty(), otherwise "مقاطع ذات صلة من مستنداتك" with a one-line note ("لم يذكر النموذج هذه المقاطع"); the sheet repeats which case applies.
- **Verify:** a grounded question where the model omits [n] → the header changes; unit test on citationsFor for both branches.

### CITE-02 — [n] markers are dead text and the chips carry no number, so no claim can be traced
- **Severity:** P1  **Confidence:** high  **Effort:** M
- **Where:** core/design/src/main/java/app/sirrak/core/design/Markdown.kt keeps [n] as literal text; RagUi.kt:370-398 renders only documentName + " · صفحة N" — no ordinal anywhere; nothing makes a marker tappable
- **What happens now:** the answer says "…[1]" and below it sit chips with document names and pages but no 1/2 labels; tapping [1] does nothing. With two documents in scope the mapping from claim to source is a guess.
- **Why it hurts:** the entire value of document Q&A is "where does this claim come from?"; the build shows sources but severs the link between a sentence and its source — the feature looks implemented and is not usable for verification.
- **Fix:** render [n] as a superscript reference number matching numbered chips (1..N in citation order), make the number open the same sheet; strip markers that have no chip.
- **Verify:** an answer with two citations → the chips read "١ · اسم المستند" / "٢ · …" matching the body markers; tapping a marker opens its excerpt.

### CITE-03 — A citation cannot be opened or read in context, though the picker promises it
- **Severity:** P2  **Confidence:** high  **Effort:** M
- **Where:** RagUi.kt:406-447 CitationSheet — document name, page, snippet, close; no "open document", no page jump, no next/previous; documents_picker_body says "تُقتبَس المقاطع المستخدمة بصفحاتها **ويمكنك فتحها**" (feature/chat/src/main/res/values/strings.xml:85)
- **What happens now:** the sheet is a read-only excerpt with no route into the document; the picker's own sentence promises the passages can be opened.
- **Why it hurts:** the promise is false, and when the excerpt is not enough the user must leave the chat, open the library, find the document and page manually — the exact work citations exist to remove. Sara's mixed-document workflow dies here.
- **Fix:** add "افتح في المكتبة" that deep-links the document at the cited page/highlight, plus previous/next between citations of the same answer.
- **Verify:** tap a chip → sheet → "افتح" lands on the document with the excerpt highlighted (or the page's text visible); back returns to the chat.

### CITE-04 — Citations for text/markdown files read "صفحة 0"
- **Severity:** P2  **Confidence:** high  **Effort:** S
- **Where:** RagUi.kt:392 appends citation_page unconditionally; Citation.page: Int (core/runtime/src/main/java/app/sirrak/core/runtime/Citations.kt:10-15) with no page for non-paged formats
- **What happens now:** a citation from a .txt/.md file renders "اسم الملف · صفحة 0".
- **Why it hurts:** an impossible page number signals scraped/made-up metadata and makes every citation look untrustworthy — exactly the "honest about limits" promise inverted.
- **Fix:** show the page only when it is > 0; otherwise show the chunk index or nothing.
- **Verify:** cite a .txt document → the chip has no "صفحة 0".

### CITE-05 — Two contradictory routes for the same missing embedding model
- **Severity:** P2  **Confidence:** high  **Effort:** S
- **Where:** attachment_blocked_document — "استورده من **تبويب المكتبة**" (feature/chat/src/main/res/values/strings.xml:67) vs documents_picker_embedding_missing — "قبل استيراد نموذج التضمين من **الإعدادات**" (:86); the actual home is Settings → "متقدم" (app/src/main/java/app/sirrak/RagPlugin.kt:58-76, door id advanced)
- **What happens now:** depending on which surface the user meets first, the app sends them to the Library tab or to Settings for the same file; only one is right (Settings → متقدم). The same split exists for models: error_no_model and image_attach_no_vision send the user to Settings while model management and import live in Library → Models (shot 00-baseline-settings-root-ar.png, which is actually the Library/Models screen, node "استيراد ملف GGUF"; chip doorway Panels.kt:93 navigator.select("library")).
- **Why it hurts:** each wrong route costs two or three screens and ends in a section that cannot do the thing; the user concludes the feature is broken and stops. It also directly contradicts docs/32 P4 ("model management left Settings entirely") for the error surfaces.
- **Fix:** one capability→destination table used by every string and button: embedding model → Settings → متقدم; chat model → Library → النماذج. Correct the two wrong strings.
- **Verify:** trigger each blocked state → every route lands on a page that can actually do it; one screenshot per state.

### CITE-06 — "اسأل مستنداتك" sits permanently above the composer and never says why it cannot work
- **Severity:** P3  **Confidence:** high  **Effort:** S
- **Where:** feature/chat/src/main/java/app/sirrak/feature/chat/RagUi.kt:80-180 DocumentScopeRow — rendered whenever ragEnabled, enabled = !store.streaming, with no state about documents or the embedding model; visible in shots 15-live-state-1905.png and 16-concurrent-generation-1907.png (the pill above the composer), at a time when rag/documents and rag/embedding were empty (EVIDENCE.md baseline)
- **What happens now:** the strip is always there, looking like a ready capability, and its label is an invitation ("اسأل مستنداتك" — ask your documents) rather than a scope. With nothing imported it opens a picker whose import is itself blocked by the missing embedding model, so the first tap ends in a gate message rather than documents or a clear next step.
- **Why it hurts:** it consumes a permanent strip of the scarce composer area (docs/32 lesson 12) and sets a promise the current state cannot keep; the user learns to ignore the strip by the time documents do exist.
- **Fix:** show the row's real state on its face — "لا مستندات — أضف مستندًا" or the active scope ("مستندان") — and hide the row entirely until the embedding model is present, with the import route inside the picker.
- **Verify:** empty state → the row names the missing step and its tap starts the import; with two documents → the row names the scope.

---

## ATT — attachments and images

### ATT-01 — A queued attachment is invisible to the meter, so the highest-intent pastes end in the blank answer
- **Severity:** P1  **Confidence:** high  **Effort:** S
- **Where:** ChatStore.kt:749-769 — the comment states "Queued (unsent) attachments are deliberately ignored"; AttachmentLimits.sharedTextChars = 20_000 (core/runtime/src/main/java/app/sirrak/core/runtime/Attachments.kt); window 2 048 tokens (app/src/main/assets/config/app.json) → the allowed paste is ~5 000 estimated tokens
- **What happens now:** paste up to 20 000 characters through the sheet (the dialog even shows a counter), the meter stays at "128 / 1536", and send produces the empty answer of SND-01.
- **Why it hurts:** "answer/summarise from this text" is the flagship private use case; it is the one that silently returns nothing. The app permits exactly what the engine cannot do and hides the inconsistency until the failure.
- **Fix:** include pending attachments in refreshBudget() and show the overflow **before** send; when over, refuse the send with the remedy (trim, split, or attach as a document instead).
- **Verify:** paste 20 000 characters → the meter jumps past the budget and send is blocked with an explanation; a trimmed paste answers normally.

### ATT-02 — Truncation is invisible: the model reads less than the user attached
- **Severity:** P1  **Confidence:** high  **Effort:** M
- **Where:** AttachmentPrompt in core/runtime/src/main/java/app/sirrak/core/runtime/Attachments.kt caps the model-facing text (20 000 characters) and inserts "[... truncated ...]" **only in the prompt**; no truncated state or string reaches the UI (grep: no truncation string in any strings.xml)
- **What happens now:** an attachment whose extracted text exceeds the cap is cut silently; the chip, the turn and the answer say nothing. The answer may cover only the first part of the file while the user believes it read the whole document.
- **Why it hurts:** the assistant answers confidently about half a contract or half a chapter; the user has no signal to ask about the rest or to split the file. Silence here is a correctness failure the user cannot detect.
- **Fix:** compute truncation at attach time, store it on the attachment, and show "اقتُطع: أول ٢٠٬٠٠٠ حرف من ٦٥٬٠٠٠" on the chip and in the turn; offer "قسّم إلى أجزاء".
- **Verify:** attach a > 20 000-char text → the chip states the truncation before send; the model prompt and the chip agree.

### ATT-03 — Queued attachments are deleted silently when the conversation changes
- **Severity:** P2  **Confidence:** high  **Effort:** S
- **Where:** ChatStore.kt:689-692 (adopt() → discardPending()), reachable from newConversation (:629-635) and openConversation (:638-645); chips at ChatScreen.kt:398-404
- **What happens now:** queue a pasted text or a photo, open the library, tap another conversation (or "+"): the chip disappears, the stored file is deleted, nothing asks and there is no undo. The clipboard source is gone by then.
- **Why it hurts:** navigation — the most ordinary gesture — destroys user input, and the deletion is invisible. Every other queue in the app (downloads) is durable; this one is not.
- **Fix:** keep the queue per conversation (like the draft), or confirm "لديك مرفقات لم تُرسل — تجاهلها؟" when leaving with a non-empty queue.
- **Verify:** queue two attachments, switch away and back → either preserved or a confirmation appears; cancel keeps them.

### ATT-04 — An attached image is a filename chip with no preview
- **Severity:** P2  **Confidence:** high  **Effort:** S
- **Where:** feature/chat/src/main/java/app/sirrak/feature/chat/AttachmentUi.kt:120-160 AttachmentChip — kind glyph + display name only; the only IMAGE-specific line in the file is a sheet hint (:278); no bitmap/painter anywhere in it
- **What happens now:** after picking a photo the composer shows an image icon and a name; to check you chose the right photo you must reopen the gallery. Camera captures and screenshots often have meaningless names.
- **Why it hurts:** images are the one attachment whose content cannot be inferred from its name; a wrong-photo send costs a full generation (up to a minute here) and a wrong answer.
- **Fix:** render a 40–48 dp thumbnail inside the chip (decode on the same path the picker already uses) and open a full-screen preview on tap.
- **Verify:** attach a photo → the chip shows the exact photo; tap → preview.

### ATT-05 — The image prompt chips replace whatever you already typed
- **Severity:** P2  **Confidence:** high  **Effort:** S
- **Where:** feature/chat/src/main/java/app/sirrak/feature/chat/ImagePromptSuggestions.kt (onSelect(text)) wired at ChatScreen.kt:409-417 as onSelect = { input = it } — shown when a pending image exists and no stream is running
- **What happens now:** type "اكتب وصفًا قصيرًا ثم اذكر…", tap "صِف الصورة" → the draft is replaced by the canned prompt with no undo and no trace.
- **Why it hurts:** the chips read as helpful additions but behave as replacements; typed work disappears silently and the user cannot recover it.
- **Fix:** append the suggestion (with a space) to the draft instead of replacing it, or show it as a removable prefix chip before the text.
- **Verify:** type text, tap a suggestion → the draft is preserved and the prompt appended.

### ATT-06 — The attach sheet ships a dead audio row with milestone copy, contradicting the mic beside it
- **Severity:** P2  **Confidence:** high  **Effort:** S
- **Where:** AttachmentUi.kt:78-88 always orders AUDIO_TRANSCRIPT into the sheet; disabled rows keep their reason, attachment_capture_pending = "التقاط هذا النوع يصل في مرحلة لاحقة" (feature/chat/src/main/res/values/strings.xml:71), with a second contradictory reason attachment_blocked_audio for the same row
- **What happens now:** tap the paperclip and a row says audio will arrive "in a later stage", while the mic in the same composer already records and transcribes voice today; depending on gate state the same row may show a different, contradictory reason.
- **Why it hurts:** roadmap text in a shipping UI reads as unfinished and undercuts the dictation that *does* exist — a user may conclude the app cannot take voice at all.
- **Fix:** hide AUDIO_TRANSCRIPT from the sheet in 1.1.0 (keep the code path for the future), leaving shared text / document / image; no milestone copy in the product.
- **Verify:** open the sheet → no "later stage" row; the mic remains the documented voice path.

---

## DIC — dictation

### DIC-01 — In hold mode a quick tap is an error, and nothing tells you which mode you are in
- **Severity:** P2  **Confidence:** high  **Effort:** S
- **Where:** feature/voice/src/main/java/app/sirrak/feature/voice/DictationControls.kt:109-121 — detectTapGestures(onPress { begin(); tryAwaitRelease(); stop() }) with no minimum hold; the controller fails a short recording (core/speech/src/main/java/app/sirrak/core/speech/Dictation.kt:113-116, MIN_RECORDING_MS = 400 in Wav.kt:17) → RECORDING_TOO_SHORT; the mic looks identical in both modes (DictationControls.kt:107-124)
- **What happens now:** in HOLD mode a tap (or any press released within 400 ms) starts a recording and immediately fails with an error strip; whether the mic needs a tap or a hold is only knowable from a Settings toggle the user may never have seen.
- **Why it hurts:** the walkie-talkie gesture is undiscoverable and accidental taps produce red errors, which reads as "dictation is broken" before the user ever speaks.
- **Fix:** in HOLD mode ignore releases shorter than ~400 ms (or keep recording to the minimum instead of failing) and show "أمسك للتحدث" on the mic's first use; show the active mode as a hint when the strip appears.
- **Verify:** HOLD mode, quick tap → nothing happens (or the minimum recording completes); a 1 s hold → transcript.

### DIC-02 — Recording has no level, no silence cue, and no warning at the 2-minute cap
- **Severity:** P2  **Confidence:** high  **Effort:** M
- **Where:** DictationControls.kt:226-282 RecordingStrip — a static GraphicEq icon, a clock, Stop and Cancel; MAX_RECORDING_MS = 120_000 (Wav.kt:19) with no countdown; silence is only detected after transcription (Dictation.kt:135-137 → NO_SPEECH) — docs/32's own lesson 16 records that the "live level meter" claim was never built
- **What happens now:** recording shows a timer only. A muted mic, a phone held away or a noisy room is discovered after the whole clip is transcribed ("لا تُوجد كلمات" or gibberish), wasting a full transcription on a CPU-only device.
- **Why it hurts:** voice is the fast path for the target user (one-handed, no signal, Iraqi Arabic); no level means no chance to correct a bad capture, and a 2-minute cap that arrives silently truncates long thoughts.
- **Fix:** drive a small amplitude bar from the recorder's existing level callback, show "لا صوت" after ~3 s of silence, and count toward the cap with a visible warning at 90 %.
- **Verify:** cover the mic → flat meter + silence hint within 3 s; a long recording shows the cap warning before stopping.

### DIC-03 — Every fatal dictation state ends in a strip whose only action is "إخفاء" — including the no-model state a fresh install hits first
- **Severity:** P2  **Confidence:** high  **Effort:** S
- **Where:** feature/voice/src/main/java/app/sirrak/feature/voice/DictationControls.kt:314-346 FailureStrip — only onDismiss; the state machine emits typed reasons with no action behind them: NO_MODEL at core/speech/src/main/java/app/sirrak/core/speech/Dictation.kt:87-88 and :117-126 (a fresh install has an empty speech_models/, EVIDENCE.md baseline), NO_PERMISSION at :155-157 with the re-request at DictationControls.kt:91-97 silently no-oping once permanently denied; the copy for the first case already names the destination — dictation_reason_no_model: "استورد نموذج إملاء أولًا من الإعدادات" (feature/voice/src/main/res/values/strings.xml:43) — but no control leads there
- **What happens now:** on the baseline device the mic is present and enabled (node "بدء الإملاء", 48 dp, shot 15 / s1-nodes.txt node 16) and the first tap produces an error strip telling the user to import a model from Settings, with one button: dismiss. A permanent permission denial produces the same strip shape, also with only dismiss.
- **Why it hurts:** the two most likely first encounters with dictation are dead ends that name work the user must perform elsewhere but give them no way to start it; the mic then reads as broken rather than unconfigured, and Android users do not know the app-settings trampoline.
- **Fix:** give each fatal reason a primary action — NO_MODEL → "افتح إعدادات الإملاء" deep-linking the ASR door, NO_PERMISSION → "فتح إعدادات التطبيق" (ACTION_APPLICATION_DETAILS_SETTINGS), ENGINE_LOAD_FAILED → "اختر نموذجًا آخر" — next to dismiss. Alternatively disable the mic and put the import route on the mic's first tap.
- **Verify:** fresh state → tap mic → strip's button opens the import section and returns with the model selected; permanent denial → the settings action appears and the next tap records.

### DIC-04 — A dictated transcript is appended without moving focus or the cursor
- **Severity:** P3  **Confidence:** high  **Effort:** S
- **Where:** ChatScreen.kt:95-98 — input = input.trimEnd() + " " + text; the field is not focused and no scroll-to-cursor happens (Chat.kt:120-140, field is 6 lines / 160 dp max)
- **What happens now:** the transcript lands at the end of the draft; with a multi-line draft the user sees no change (the new text is below the field's visible area) and the cursor stays where it was.
- **Why it hurts:** "did it hear me?" — the user re-records or edits manually, and a later keystroke can land in the middle of the inserted sentence.
- **Fix:** request focus, move the cursor to the end and scroll the field to it after insertion; briefly highlight the inserted span.
- **Verify:** dictate into a 5-line draft → the field is focused, the end of the text is visible, typing continues at the end.

---

## LIB — the conversation library and recovery

### LIB-01 — Search reads and parses every conversation file on the main thread, per keystroke
- **Severity:** P2  **Confidence:** high  **Effort:** M
- **Where:** feature/chat/src/main/java/app/sirrak/feature/chat/ChatHistory.kt:79-81 — remember(revision, query, store.conversationId) { store.searchConversations(query) } inside composition → ChatStore.kt:614-616 → FileConversationLibrary.search (app/src/main/java/app/sirrak/FileConversationLibrary.kt:79-87: synchronized, and readConversationLocked does a file read + JSON decode per entry); no debounce, no dispatcher
- **What happens now:** every typed character triggers a synchronous scan of all transcript files. With 50 conversations (Omar's case) each keystroke does 50 file reads + parses on the UI thread; on a 6 GB mid-range phone this janks, and a long library can ANR.
- **Why it hurts:** the library's only find mechanism stalls exactly while it is used; users stop searching and scroll. A 1.1.0 "durable library" that freezes on search reads as unreliable.
- **Fix:** debounce ~200 ms, run on Dispatchers.IO with a small loading state, and keep a searchable text index per conversation (updated on save) so search never hits the disk per keystroke.
- **Verify:** seed 100 conversations, type a 6-character query → no frame over 32 ms (gfxinfo/Perfetto), no ANR in logs.

### LIB-02 — JSON export is a backup you cannot restore, and neither export is the conversation you saw
- **Severity:** P2  **Confidence:** high  **Effort:** M
- **Where:** ChatHistory.kt:87-100 (export menu → ConversationExport.markdown / json) · core/runtime/src/main/java/app/sirrak/core/runtime/Conversations.kt:144: markdown filters text.isNotBlank() (:151) so attachment-only questions vanish, and drops citations and attachment names; json embeds the full extractedText and the app-private localPath; ConversationExport.fromJson (:227) has **no production caller** (grep: tests only)
- **What happens now:** "تصدير JSON" writes a file the app can never read back and that contains the full extracted document text plus internal file paths; "تصدير Markdown" loses citations, attachment names and questions that consisted only of an attachment.
- **Why it hurts:** export is the promised exit path ("export/delete must be obvious"). A backup that cannot be restored is a false safety net; sharing the JSON shares more than the user saw on screen (a privacy surprise in a private-product pitch), and the markdown omits exactly the citations that make an answer verifiable.
- **Fix:** label the two actions honestly ("نسخة احتياطية (JSON)" vs "مشاركة (Markdown)"), add an import that uses the existing fromJson, strip localPath and cap or inline-document text in the export, and render citations as footnotes in markdown.
- **Verify:** export → delete the conversation → import → identical transcript; JSON contains no filesystem paths; markdown contains a sources section.

### LIB-03 — Tapping a conversation while an answer streams does absolutely nothing
- **Severity:** P2  **Confidence:** high  **Effort:** S
- **Where:** ChatStore.openConversation returns false while streaming (ChatStore.kt:638-640); the row ignores the result (ChatHistory.kt:182: if (store.openConversation(summary.id)) onOpened()), so the list stays open
- **What happens now:** the row is tappable, the tap produces no visual change, and nothing explains that a stream is running.
- **Why it hurts:** a dead tap reads as a broken app; users tap repeatedly, then force-quit. The information needed (an answer is still generating) is in the store but never shown in the list.
- **Fix:** disable rows while streaming with a footer note "جارٍ توليد إجابة…" and an explicit "إيقاف وفتح" action, or stop the stream on open with a confirmation.
- **Verify:** start a long answer, open the library → rows visibly disabled or a note shown; the action opens the target after stopping.

### LIB-04 — "+" kills a running answer without asking, and the confirmation was written but never wired
- **Severity:** P2  **Confidence:** high  **Effort:** S
- **Where:** ChatScreen.kt:213-219 — IconButton(onClick = { store.newConversation() }); ChatStore.newConversation calls stop() first (:629-635); the strings new_chat_title / new_chat_body / new_chat_confirm exist in feature/chat/src/main/res/values/strings.xml but are referenced nowhere (grep over *.kt: 0)
- **What happens now:** one tap on the header "+" stops the in-flight generation (the conversation is kept in the library, but the running answer is gone) and switches the view to a fresh conversation. The dialog that was written for exactly this case is dead code.
- **Why it hurts:** a destructive control adjacent to the title with no confirmation; on this device an answer can be a full minute of waiting, so an accidental tap costs the result of a long wait. Dead strings show the safety was planned and lost.
- **Fix:** when store.streaming, show the existing confirm dialog ("أوقف الإجابة وأنشئ محادثة جديدة؟" / "انتظر") before switching.
- **Verify:** tap "+" mid-stream → dialog; Cancel leaves the stream running; Confirm stops and opens the new chat.

### LIB-05 — Library rows carry no date, so "yesterday's chat" has no handle
- **Severity:** P3  **Confidence:** high  **Effort:** S
- **Where:** the row is title + preview + history_messages count only (ChatHistory.kt:266-291); ConversationSummary.updatedAt exists (core/runtime/src/main/java/app/sirrak/core/runtime/Conversations.kt) and app.json sets ui.showTimestamps = false
- **What happens now:** rows are sorted by recency but show no date or time; with similar titles/previews the list is a wall of near-identical rows.
- **Why it hurts:** returning to a conversation is the library's main job; recency without dates forces opening several rows to find the right one.
- **Fix:** show a relative date in the row ("اليوم", "أمس", "منذ ٣ أيام") in place of or beside the message count.
- **Verify:** seed conversations over several days → dates visible and correct in Arabic.

### LIB-06 — The open conversation has no name on the chat screen
- **Severity:** P3  **Confidence:** high  **Effort:** S
- **Where:** the chat header is wordmark + model chip + history + new chat (ChatScreen.kt:190-230); the conversation title lives only in the library list (ChatHistory.kt:246-274)
- **What happens now:** after opening an old conversation there is no on-screen confirmation of which one it is; renaming a chat has no visible effect in the chat itself.
- **Why it hurts:** "where am I" doubt after switching, and rename feels broken because nothing on the chat changes.
- **Fix:** show the title (or derived title) beside/under the wordmark, tappable to open the library.
- **Verify:** rename a conversation → the chat header shows the new name; open another → the header matches.

### LIB-07 — Every tap on "+" persists an empty conversation row
- **Severity:** P2  **Confidence:** high  **Effort:** S
- **Where:** ChatStore.newConversation:629-635 creates and activates a library conversation immediately (no lazy creation); FileConversationLibrary.create writes a transcript file (FileConversationLibrary.kt:94-108); the list renders every summary with messageCount = 0 (ChatHistory.kt:246,287)
- **What happens now:** tapping "+" and not typing anything leaves a persistent row titled with the fallback name and "٠ رسالة"; doing it a few times fills the library with empty rows (and each is a real file).
- **Why it hurts:** the library becomes clutter users must delete row by row, and the "conversations" they see do not correspond to anything they remember asking.
- **Fix:** create the conversation lazily on first send (or drop empty conversations when leaving) and hide 0-message rows from the list.
- **Verify:** tap "+" three times without sending → the library still has one row (or none added); sending creates exactly one.

---

## Appendix A — round-1 claims checked (brief §6, docs/32)

| Claim | Verdict in 1.1.0 |
|---|---|
| **V1** four-state honest chip, every state acts, "no Settings detour" | **Half true.** The chip is honest and its sheet routes to the Library (Panels.kt:59-97, :93), but the *failure* path still says "استورد ملف GGUF من الإعدادات" and opens Settings (ChatScreen.kt:356-360, ChatPlugin.kt:87) — the detour docs/32 said was removed exists on the higher-traffic surface; a loading model is mislabelled "no model" (CMP-01/02). |
| **V7** send→stream→copy/regenerate/new chat, meter, markdown/bidi | **Present, unsafe.** Streaming is real and Stop is reachable (device-confirmed: shots 15/16, lib1 stream state, 27.4 s to the first token twice — logcat-1906.txt:1286), markdown/bidi render per block; but copy emits raw Markdown (ANS-01), regenerate destroys the old answer first (ANS-03), new chat kills the stream with no confirm (LIB-04), auto-scroll fights the reader (SND-02), stop leaves an unmarked partial (SND-04), the meter's speed contradicts the engine log (SND-05). |
| **V8** dictation, vision, RAG citations reachable | **Reachable, promised falsely.** The mic is 48 dp and both modes exist; vision and documents are blocked states with typed reasons. But "المصادر المستخدمة" lists unchosen passages (CITE-01), [n] cannot be mapped to a chip (CITE-02), the picker promises passages "يمكنك فتحها" and the sheet cannot open them (CITE-03), image/no-vision and model errors route to the wrong destination (CITE-05). |
| product promise "Arabic-first answers" | **Contradicted on device.** An Arabic question was answered in English and the letter shipped with a literal "[Recipient]" (shot 15, s1-nodes.txt nodes 6-7, logcat-1906.txt:1286); the Arabic re-ask after typing "أجب بالعربية فقط." produced one ungrammatical clause with no greeting or sign-off (shot 16) — SND-08/SND-09. |
| docs/32 **lesson 16** (marketing claimed a live level meter; the strip shows a timer) | **Still true as a gap**: no level meter and no silence feedback (DIC-02); the strip now also shows a static equaliser icon, which is decoration, not input level. |
| docs/32 **lesson 12** (720×1280 composer leaves 218 dp of reading area; compact empty state) | **Partially verifiable.** From logs/10-chat-empty-nodes.txt the gap between the header and the scope row is ≈ 550 px (≈ 275 dp) at font scale 1.0; with the IME open that shrinks to roughly zero. No keyboard shot exists in the pack, so the IME case is **unverified** (Appendix C). |

## Appendix B — device evidence used, and its limits

- Shots available at write time: 00-baseline-settings-root-ar.png (in fact **Library → Models**), 10-chat-existing-ar.png, 11-chat-existing-top-ar.png, 12-history-ar.png, 13-history-menu-ar.png, 14-rename-dialog-ar.png, 15-live-state-1905.png (English answer to an Arabic question), 16-concurrent-generation-1907.png (the Arabic re-ask, completed), 20-library-models-ar.png; UI dumps build/ux-audit/logs/*.xml and *-nodes.txt.
- Live-stream state: lib1-nodes.txt (19:07:05, while the Arabic answer generated) shows the question, the meter "84 / 1536", the Stop button and **no node for the in-progress answer**; s1-nodes.txt (19:06:48) shows the finished English answer with the three action glyphs and the meter "114 / 1536".
- The two late turns were typed on the device by a third party (EVIDENCE.md interference log); the app outputs are genuine and cross-checked against the engine's STATS lines.
- Engine truth from the device's own log (build/ux-audit/logs/logcat-full-1901.txt): model load 1 273 ms; request prefill_ms=26719.2 ttft_ms=26723.8 generated=73 gen_ms=37781.9 tok_s=1.93 peak_rss_mb=1434 (lines 122331, 124605, 125305). Earlier 0.5B run: ttft_ms=8646.0 tok_s=6.35.
- The meter in shot 10 reads "128 / 1536 رمز تقديري" and "≈ 0.7 رمز/ث"; the engine's prompt token count for that turn was 87 and its decode speed 1.93 tok/s — the basis of SND-05 and a +47 % overestimate in the meter's token count.

## Appendix C — what I could not verify on device (honest gaps)

1. **Keyboard/IME overlap and focus** (CMP-04): no shot with the keyboard open; needs a capture with the composer focused and a long answer.
2. **Mid-stream scroll fight** (SND-02) and the **blank-answer trigger** (SND-01): code-proven; the streaming state itself was captured (lib1-nodes.txt, 19:07:05), but no run scrolled up during a stream and no oversized paste was sent. Needs a deliberate 8 000-char paste and a scroll attempt during a long answer.
3. **Dictation** (DIC-*): speech_models/ was empty at baseline (EVIDENCE.md §baseline) — no speech model, so no recording or transcription was exercised; findings are code-cited.
4. **Attachments, images, RAG document flows** (ATT-*, CITE-*): not yet driven in the pack; findings are code-cited and state their confidence.
5. **Hardware behaviour** (Abu Zaid's 6 GB mid-range, not an emulator): all timings here are emulator x86_64 CPU-only; treat 1.9 tok/s and 26.7 s TTFT as an upper bound to be re-measured on hardware.
6. **Search jank measurement** (LIB-01): needs a seeded library and a frame-time trace; the synchronous main-thread IO is proven in code, the jank magnitude is not measured.
