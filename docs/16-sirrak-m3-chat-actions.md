# 16 — Sirrak M3, increment 2: chat answer actions — copy + regenerate (19 Nov 2026)

**Status:** **M3 increment 2 closed** — every finished answer can be copied, and the newest answer can
be asked for again without retyping the question. 8 new JVM tests (55 total, all green). Verified
end-to-end on the emulator in the debug build and in the R8 release build. Two layout defects found
during verification were fixed in the same increment (see §2.6). Version stays `0.1.0-m2` until the M3
milestone closes as a whole; this record covers one increment.

This is the "chat actions" slice that sits naturally between M3 item 1 (chat persistence, doc 15) and
the M3 queue from doc 14 §8: persistence made an answer worth keeping, so the next useful thing a reader
can do with a kept answer is **copy it** or **ask again**. No new module and no new dependency — the
whole increment is one reusable design component, one `ChatStore` function, the wiring in
`ChatScreen`, three strings per language, and one shell-level inset rule.

---

## 1. What the user can now do

- Under **every finished answer** there is a quiet row of two 48 dp icon targets: **copy** and, on the
  newest answer only, **regenerate**.
- **Copy** puts the answer's plain text on the system clipboard. The copy icon turns into a lapis
  tick for 1.8 s (Android 13 also shows its own clipboard confirmation overlay); the answer itself is
  unchanged.
- **Regenerate** re-runs the same question with the same history, replacing the answer on screen. While
  it runs the normal streaming state is visible (inking mark, stop button); **stop** still works and
  leaves the partial answer exactly as in M2.
- **Arabic RTL and English LTR are mirrored**: the actions sit at the answer's reading edge, under the
  prose, in both directions. Labels and content descriptions are localised in both languages.
- **Light/dark, font scale 1.3 and restart** keep the actions rendered and usable — the actions are
  part of the restored transcript, not transient chrome.
- Nothing else about the app changed: same shell, same engine, same persistence rules, same new-chat
  confirmation.

---

## 2. Design

### 2.1 Two actions, two different scopes

The scope decision is the whole design:

| Action | Available on | Why |
|---|---|---|
| Copy | every completed answer, old or new | copying is read-only; any answer is worth taking out of the app |
| Regenerate | **the newest answer only** | re-running an older turn would have to discard every turn after it, silently destroying context the user can see |

That is why the row is rendered per answer but only the **last** item receives the regenerate callback:
`isNewest = item.id == store.items.lastOrNull()?.id` gates the second icon. Older answers get copy
only. There is no "regenerate from here" yet; that is branching (see §7/§9) and needs an explicit
confirmation before it deletes later turns.

### 2.2 `ChatStore.regenerate` — replace one answer, never duplicate the question

`feature/chat/.../ChatStore.kt` adds one function next to `retry()`:

```kotlin
/**
 * Asks for a different answer to the last question, replacing the answer already on screen.
 * No-op while streaming and when the last turn is not a finished answer: [retry] owns failed
 * turns, so this action never silently repeats one.
 */
fun regenerate(callerScope: CoroutineScope) {
    if (streaming) return
    val lastUser = items.lastOrNull { it is Item.User } as? Item.User ?: return
    val lastAssistant = items.lastOrNull() as? Item.Assistant ?: return
    if (lastAssistant.streaming || lastAssistant.failed || lastAssistant.text.isBlank()) return
    // history() contains the answer being replaced; the question travels as the prompt, exactly
    // as it did on the first send, so both last entries are dropped and nothing is asked twice.
    val history = history().dropLast(2)
    items.remove(lastAssistant)
    // A rejected answer must not survive a process kill mid-stream: drop it from disk now.
    persist()
    if (engine.status.value !is EngineStatus.Ready) {
        items += failedTurn(NO_MODEL)
        return
    }
    generate(lastUser.text, history, appScope ?: callerScope)
}
```

The semantics, pinned by `ChatStoreRegenerateTest`:

| Situation | Behaviour |
|---|---|
| last completed exchange, Ready engine | answer removed from memory **and disk**, question re-sent as the prompt, history = everything before the question (no duplicate turn) |
| engine not Ready | the old answer is still removed, a typed `no-model` failure takes its place — same rule as `send()` |
| answer still streaming | no-op (the user has stop; a second generation would race the first) |
| last turn failed/stopped | no-op — `retry()` owns failed turns, so the two actions never both claim the same turn |
| empty conversation / last item is a user turn | no-op |
| completed answer blank text | no-op (nothing to replace or copy meaningfully) |
| sources on the old answer | cleared with the replaced answer; the new completion supplies its own |

The **drop-before-stream** detail is deliberate and tested: the moment the user asks again, the rejected
answer is gone from `conversation.json`. If Android kills the app mid-regeneration, reopening shows the
question with no answer — the same honest state as a killed first send (doc 15), never "the old answer
came back" and never a half-answer persisted as complete.

### 2.3 The reusable component: `AnswerActions` in `core:design`

The row lives in the design module, not the chat feature, and it is dumb: it receives strings and
callbacks, renders, and owns no state.

```kotlin
@Composable
fun AnswerActions(
    copyLabel: String,
    copiedLabel: String,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
    copied: Boolean = false,
    regenerateLabel: String? = null,
    onRegenerate: (() -> Unit)? = null,
)
```

- **No hardcoded copy, no hardcoded colour, no hardcoded size in the feature.** The labels come from the
  feature's `strings.xml`; the component uses the theme's `onSurfaceVariant` for the muted glyphs and
  `primary` for the momentary tick. Adding share/export/speak later means adding one parameter and one
  `IconButton` here, not touching every call site.
- **48 dp targets, 8 dp gaps, 20 dp glyphs.** Material minimum, no nested cards, no shadow.
- **Mirroring is physical, not accidental.** The row is pulled 14 dp toward the prose edge so the 20 dp
  glyph sits under the text column instead of inboard of the 48 dp touch padding; the direction is read
  from `LocalLayoutDirection` and flipped for LTR. Verified: Arabic copy action at x ≈ 584, English at
  x ≈ 40, regenerate at x ≈ 472 vs 152 — the same layout mirrored.
- **The copied state is a momentary tick, not a snackbar.** There is no snackbar host in the shell
  (nothing in M1–M2 needed one), and Android 13's own clipboard overlay already tells the user what
  happened. A 1.8 s lapis check is the quiet, in-place confirmation; the label swap (`Copy answer` →
  `Copied`) is carried by the `contentDescription`, so TalkBack sees the state change too.
- **Both icons are muted until used.** Lapis is reserved for the copied tick only — the prose keeps the
  surface, actions do not compete with the answer.

### 2.4 Wiring in `ChatScreen`

- `var copiedAnswerId by remember { mutableStateOf<Long?>(null) }` plus a `LaunchedEffect` that clears
  it after 1.8 s. `remember` (not `rememberSaveable`) is correct: a rotation while the tick is visible
  should forget a transient confirmation.
- The row is rendered only for `!item.streaming && !item.failed && item.text.isNotBlank()` — a failed
  turn shows its error + Retry row instead, a streaming turn shows the inking mark.
- Copy: `clipboard.setText(AnnotatedString(item.text))`, then `copiedAnswerId = item.id`.
- Regenerate: `store.regenerate(scope)`; rendering is gated by `isNewest` as described in §2.1.

### 2.5 Clipboard choice

Compose's `LocalClipboardManager` is used rather than an Android `ClipboardManager` call through a
context: it is the platform-idiomatic path in Compose, it needs no permission, and it keeps the screen
free of Android framework APIs. Note for the next Compose upgrade: `LocalClipboardManager` is deprecated
in newer Compose releases in favour of the suspend `Clipboard`; on the pinned BOM (2024.10.00) it is the
supported API. The migration is a one-line swap inside the same callback when the BOM moves.

Only the answer's **plain text** is copied — no markdown, no sources, no question. If the user pastes into
another app they get exactly what is on screen as text.

### 2.6 Two defects found while verifying this increment (fixed here)

Both were pre-existing layout bugs, invisible in earlier screenshots because the conversation was short or
because the keyboard was closed. They are fixed in this increment rather than logged, because both are
about *reading the answer* — the thing the actions were added to serve.

**(a) Keyboard-open: blank band under the composer, transcript squeezed to ~72 dp.**

- Observed: with the keyboard up, the composer sat at y ≈ 324–452 px of a 1280 px window while the IME
  started at y ≈ 730 px — a blank ivory band ≈ 278 px under the composer, leaving a 72–82 dp transcript
  viewport (almost nothing visible while typing).
- Cause: the shell's `Scaffold` `bottomBar` (the navigation bar + its system inset, ≈ 128 dp) stayed
  composed **behind** the keyboard, so the Scaffold reserved its height, and `ChatScreen`'s
  `imePadding()` subtracted the full IME height as well. Both insets applied at once.
- Fix in `core/design/.../Shell.kt`: while `WindowInsets.isImeVisible`, the bottom bar is not composed.
  The system keyboard covers the tab bar anyway; hiding it gives the transcript its height back exactly
  when the user is typing. It is a shell rule, so any future feature with a text field inherits it,
  instead of every screen re-solving the inset problem.
- After: composer text at y ≈ 572–624 px directly above the IME top (≈ 700 px), one normal 25 dp gap,
  transcript viewport ≈ 250 dp; the latest answer and its actions are visible while typing
  (screenshot `sirrak-m3-actions-typing.png`). The tab bar returns when the keyboard closes (verified).

**(b) Restored long conversation opened with its actions row just below the fold.**

- Observed after reinstall + cold launch: the second answer's actions were missing from the view
  hierarchy (only the first answer's copy action was found); a manual scroll revealed them. `scrollToItem`
  + `scrollBy(10_000f)` ran before the restored LazyColumn had measured its long answers, so `scrollBy`
  clamped early.
- Fix in `ChatScreen`: after the first follow, wait one frame (`withFrameNanos { }`) and, when no stream
  is growing and the list can still scroll forward, follow to the true end a second time.
- After: cold launch of the final build restores both action rows without a touch — copy at y ≈ 315 and
  798, regenerate at y ≈ 798 (content descriptions from `uiautomator dump`).

Neither fix changes persistence, engine behaviour or the composition container.

---

## 3. TDD record (RED → GREEN)

Written first, observed failing before implementation (typed Kotlin, so RED is a compile failure):

```
:feature:chat:compileDebugUnitTestKotlin FAILED
e: ChatStoreRegenerateTest.kt: Unresolved reference 'regenerate'
e: ... (8 tests, one per call site)
```

Then the implementation, then:

```
:app:assembleDebug :core:runtime:testDebugUnitTest :feature:chat:testDebugUnitTest :app:testDebugUnitTest
BUILD SUCCESSFUL
```

**55 distinct JVM tests, 0 failures, 0 errors** (aggregated XML test results across the three test
tasks; the release variant duplicates the runtime report, the distinct count is 55):

| Module | Suite | Tests |
|---|---|---|
| core:runtime | `AppContextTest` (container/slot lifecycle) | 8 |
| core:runtime | `GgufFileTest` (model-store header parsing) | 8 |
| app | `AppCompositionTest` (manifest + add/remove-one-line rule) | 5 |
| app | `AppConfigTest` (config parsing/validation incl. engine ranges) | 7 |
| app | `FileConversationStoreTest` (JSON format/atomicity/tolerance) | 6 |
| feature:chat | `ChatStoreTest` (streaming/stop/retry/no-model) | 7 |
| feature:chat | `ChatStorePersistenceTest` (restore/save ordering) | 6 |
| feature:chat | **`ChatStoreRegenerateTest` (new)** | 8 |

New `ChatStoreRegenerateTest` (scripted engine, one chunk list per call, so the two generations can
produce different text; in-memory conversation fake; coroutines driven with `yield()`):

1. **replaces the last answer and reuses the question** — two turns after the run, answer text is the
   second generation, and the engine received the same prompt twice (one per generation);
2. **sends the history before the question, never the question twice** — two exchanges, regenerate the
   second: the third engine call gets prompt "الثاني" and history
   `[USER "الأول", ASSISTANT "واحد"]`;
3. **persists the replaced transcript** — the conversation fake ends with
   `[USER "سؤال", ASSISTANT "جديد"]`, exactly once each;
4. **drops the old answer from disk before the new one arrives** — while the second generation is still
   streaming, the fake already holds `[USER "سؤال"]` only, and the on-screen answer is streaming;
5. **ignored while an answer is streaming** — one engine call, two items, no second stream;
6. **does nothing on an empty conversation** — zero engine calls;
7. **leaves a failed answer to retry** — after a failed send, regenerate is a no-op (one engine call,
   the failed item is still the failed item);
8. **resets the sources of the replaced answer** — the old `SourceRef` is gone from the new answer.

All eight run on the JVM in milliseconds; the layout fixes were verified on the emulator because this
project has no instrumentation-test harness yet (see §6 for the method).

---

## 4. Verification on the emulator (19 Nov 2026)

Debug build first, then R8 release installed over it (same debug-key signature, data preserved), then
debug again for the next increment.

| Check | Result |
|---|---|
| `assembleDebug` + all three test tasks | `BUILD SUCCESSFUL`; 55 tests, 0 failures, 0 errors (aggregated XML) |
| New chat, send "Hello" with the 1.5B model | answer completes; copy icon appears under it; regenerate appears only when it is the last item |
| Regenerate the answer | stop button + inking mark visible while it runs; second generation completed in ≈ 6–9 s on this emulator session and the answer genuinely changed ("Hello! How can I assist you today?" → "I'm sorry, but I'm not sure what specific content…") |
| Copy → tick → paste | tap copy: icon becomes a lapis check for 1.8 s; Android 13 clipboard overlay shows the copied preview; `KEYCODE_PASTE` into the composer inserts the full answer text (screenshot `-pasted.png`), composer cleared afterwards |
| Persisted transcript after regenerate | `files/conversation.json` (268 B, `-rw-------`) keeps **one** user turn and the **new** answer in place of the old one: `[Hello, A, Write, A2]` — no duplicated question, no stale answer |
| `am force-stop` + cold launch | both action rows re-rendered on the restored transcript (screenshot `-restored.png`); copy at y ≈ 315 and 798, regenerate at y ≈ 798 |
| English UI (LTR) | labels "Copy answer" / "Regenerate answer"; actions mirrored: copy x ≈ 40, regenerate x ≈ 152 (Arabic: copy x ≈ 584, regenerate x ≈ 472) |
| Dark theme (app's own Apparence > ليلي) | same actions, muted glyphs legible on the dark ground; ground pixel `#0F1416` |
| Font scale 1.3 | labels/icons reflow without clipping, 48 dp targets unchanged |
| Keyboard-open layout (after §2.6a fix) | composer directly above the IME, transcript ≈ 250 dp, latest answer + actions visible; tab bar hidden while typing and back when the IME closes |
| Restored-scroll fix (§2.6b) | cold launch of the final build opens at the true bottom with both action rows present (verified by content descriptions, no manual scroll) |
| `:app:assembleRelease` | `BUILD SUCCESSFUL` in 1 m 8 s; **unsigned 17,028,081 B**; signed with the debug key for internal testing → **17,042,472 B (16.25 MiB)** |
| R8 release installed over debug | renders the restored transcript with both actions (release clash-free: R8 keeps the Compose/callback paths, no extra keep rule); `versionName=0.1.0-m2` |
| Debug reinstalled over release | conversation restored again, actions present, `files/conversation.json` 268 B `-rw-------` — evidence state for this record |

Regenerate was exercised twice (once on "Hello", once earlier on a stray "Write" turn); both runs showed
the same stream → replace behaviour. The numbers are x86_64 emulator shape numbers, not phone
performance; no new engine benchmark was run for this increment.

---

## 5. Evidence screenshots

In `assets/` (all from the final build unless noted):

| File | Shows |
|---|---|
| `sirrak-m3-actions-rtl.png` | Arabic RTL light: completed answer with copy under it; regenerate under the newest answer |
| `sirrak-m3-actions-streaming.png` | regenerate in flight: stop button in the composer + inking mark, the old answer already gone |
| `sirrak-m3-actions-copied.png` | copied state: lapis check tick + Android 13 clipboard confirmation overlay |
| `sirrak-m3-actions-pasted.png` | the answer text pasted back into the composer — proof the clipboard holds the full answer |
| `sirrak-m3-actions-en.png` | English LTR: labels "Copy answer" / "Regenerate answer", actions mirrored to the left edge |
| `sirrak-m3-actions-dark.png` | dark theme with the same action rows |
| `sirrak-m3-actions-font-scale.png` | font scale 1.3 reflow |
| `sirrak-m3-actions-restored.png` | after `force-stop` + cold launch: restored transcript with both action rows |
| `sirrak-m3-actions-typing.png` | keyboard-open after the §2.6a fix: composer above the IME, answer and actions visible, tab bar hidden |
| `sirrak-m3-actions-release.png` | the R8 release build rendering the restored conversation with actions |
| `sirrak-m3-actions-final.png` | final installed debug build state (268 B private transcript) |

---

## 6. Testing-method notes

**The clipboard overlay steals uiautomator's focused window.** On Android 13 the copy action shows a
system clipboard confirmation window. While that window is focused, `adb shell uiautomator dump` returns
the *overlay's* hierarchy, not the app's — so a naive "search the dump for the tick's content
description" says the tick is missing when it is working. The evidence that works: a screenshot (tick is
visible), plus pasting the clipboard back into the composer (`input keyevent 279` = `KEYCODE_PASTE`)
and seeing the full answer. Recorded so the next session does not re-diagnose a false negative.

**Derive tap coordinates from uiautomator, not from screenshots.** Carried over from doc 15: tap
coordinates read off a screenshot mis-landed during earlier increments (a stale y landed on the
Settings tab). The reliable method used here is `uiautomator dump` → `content-desc` bounds for
`نسخ الإجابة` / `إعادة التوليد` / `إرسال`, then tap the centre of the reported box. Screenshots are for
the record and for pixel checks (dark ground, tick colour), not for coordinate math.

**Measure layout from bounds, not impressions.** Both §2.6 defects were found by comparing composer
bounds in the keyboard-open dump with the IME top in the screenshot; "it looks cramped" would not have
named the 128 dp double inset.

---

## 7. Limits (what this increment does not claim)

- **Regenerate is newest-only, and it is not undoable.** The old answer is removed from memory and disk
  the moment regeneration starts; there is no "keep both" or A/B comparison. Re-running an older turn
  (branching) would delete later turns, so it is a separate item with its own confirmation design.
- **Regenerate can return the same text.** Sampling temperature is 0.7, but a 1.5B model on a short,
  near-deterministic prompt often reproduces it (observed: a trivial sentence echoed the greeting). This
  is model behaviour — there is no answer cache; the tests prove every regenerate calls the engine again
  with the same prompt and the correct history.
- **Failed/stopped turns are retry-shaped, not regenerate-shaped.** `retry()` owns them; the regenerate
  gesture deliberately does nothing there (pinned by test 7).
- **Copy is plain text only.** No markdown preservation, no sources, no question, no share sheet, no
  file export, no "copy all". Those are separate actions and will not fit this row forever; when the
  second batch arrives (share/export/speak), group it behind one overflow menu instead of growing a row
  of icons.
- **The tick is the only in-app confirmation.** No snackbar host is plumbed (by choice); on platforms
  without a clipboard overlay the 1.8 s tick is the whole feedback.
- **TalkBack semantics exist but were not manually exercised.** Both icons declare localised
  content descriptions and the copied state flips the copy description to "Copied"; a TalkBack pass on
  the test phone is still owed (the whole app's a11y pass is a phone-era item).
- **No instrumentation tests.** The regenerate contract is JVM-tested; the two layout fixes are
  emulator-verified only. A Compose UI test harness is worth adding before the action row grows.
- **Inset fix scope:** verified on this emulator (API 36, Gboard English/Arabic). Floating IMEs, split
  keyboards, hardware keyboards and OEM insets are untested; the rule itself is the standard
  `WindowInsets.isImeVisible` one.
- **Release was verified for render/restore, not for copy/regenerate interactions** (those were exercised
  in the debug build; the release check is that the row renders and the persisted transcript restores
  under R8).
- **Emulator only.** No phone performance, thermal or battery claims.
- No new permissions, no new dependency, no schema change — the transcript format is unchanged, so old
  files load without migration.

---

## 8. Open polish (not blocking, logged so it is not lost)

- Header chip ellipsis carried from doc 15 (`qwen2.5 1.5b instruct · جاهز على…`) — still the best
  candidate for a narrow-width redesign.
- Delete-model has no confirmation/undo (carried from M2); the new-chat dialog pattern now exists to
  reuse.
- The action row appears with the answer's final token (the whole column recomposes from streaming to
  complete). No transition is applied; the row "drops in" instantly. If it ever feels abrupt, a 120 ms
  fade is the smallest acceptable motion under the design's motion budget — not done without measuring.
- While streaming, the newest answer has no actions even after completion if the user scrolled up;
  actions are not sticky/per-answer toolbars — intentional.
- The keyboard fix hides the tab bar while typing. A future dictation bar or attachment strip must live
  inside the screen (above the composer), not in the shell bottom bar.

---

## 9. Next

M3 queue (doc 14 §8 / doc 15 §9), with **chat actions inserted after persistence and now closed**:

1. ~~Chat persistence + conversation store~~ — closed by [doc 15](15-sirrak-m3-persistence.md).
2. ~~Chat answer actions: copy + regenerate (this record)~~ — **closed**.
3. **Model catalogue + downloader** with licence display (needs the hosting decision; offline flag
   stays).
4. **Voice**: Whisper STT + Piper TTS plugins into the same composition (Step Zero harness exists).
5. **Arabic RAG**: EmbeddingGemma retrieval plugin + document import (Step Zero harness exists).
6. **arm64 phone measurements**: H1 numbers, grounded-prompt prefill/TTFT, thermal soak — needs the phone.
7. Release signing, baseline profile, Play closed test (12 testers / 14 days).
8. GPU/NPU exploration behind the same `InferenceEngine` interface.

The next chat-shaped items (edit, branch, search, export/share, speak) are not in the current M3 queue
order; they are candidates after the model catalogue, because a downloader is a prerequisite for a
usable out-of-the-box app and these actions are enhancements to chat that work best with real models.

---

## 10. Change map for chat answer actions

| Change | File |
|---|---|
| The actions row itself (copy/regenerate glyphs, 48 dp, mirroring, lapis tick) | `core/design/src/main/java/app/sirrak/core/design/Chat.kt` (`AnswerActions`) |
| When actions render, copy state + 1.8 s reset, newest-only gating | `feature/chat/src/main/java/app/sirrak/feature/chat/ChatScreen.kt` |
| Regenerate semantics (what is replaced, what history is sent, persist-before-stream, guards) | `feature/chat/src/main/java/app/sirrak/feature/chat/ChatStore.kt` |
| Action labels / content descriptions (ar + en) | `feature/chat/src/main/res/values/strings.xml` and `values-en/strings.xml` |
| Keyboard-open inset rule (bottom bar hidden while IME visible) | `core/design/src/main/java/app/sirrak/core/design/Shell.kt` |
| Restore-to-bottom settle pass | `ChatScreen.kt` (the `LaunchedEffect` that follows the list) |
| Regenerate tests | `feature/chat/src/test/java/app/sirrak/feature/chat/ChatStoreRegenerateTest.kt` |
| Adding the next action (share/export/speak) | new parameter in `AnswerActions` + one callback in `ChatScreen`; only touch `ChatStore` if the action changes conversation state |

---

*Previous record: [doc 15 — M3 increment 1, chat persistence](15-sirrak-m3-persistence.md). Earlier:
[doc 14 — M2 engine](14-sirrak-m2-engine.md), [doc 13 — M1 frontend](13-sirrak-frontend.md).*
