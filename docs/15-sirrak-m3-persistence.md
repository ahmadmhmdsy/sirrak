# 15 — Sirrak M3, increment 1: chat persistence (19 Nov 2026)

**Status:** **M3 item 1 closed** — the conversation now survives process death, app restart and
rebuilds. 12 new JVM tests (47 total, all green). Verified end-to-end on the emulator in both the
debug build and the R8 release build. Version stays `0.1.0-m2` until the M3 milestone closes as a
whole; this record covers one increment.

This closes the M2 limit *"Conversations are process-scoped (no persistence yet)"* (doc 14 §7) and
item 1 of the M3 queue (doc 14 §8): **chat persistence + conversation store**.

Product app: `.` (six modules, unchanged). No new module, no dependency added — the whole
increment is one interface, one app-side implementation and the wiring that was already designed for
it.

---

## 1. What the user can now do

- Send a question, close the app (or let Android kill it), reopen — **the whole conversation is still
  there**, exactly as it was, in Arabic or English.
- Kill the app **while the model is answering** — the question is already on disk; the partial answer
  is not (see §7). Reopening shows the question alone; there is no corrupt half-turn.
- **Start a new chat** from the header `+` icon. A confirmation dialog explains that the conversation
  is deleted from this device and cannot be undone; confirming clears the screen, the input, and the
  saved file. Cancelling changes nothing.
- Nothing else about the app changed — same shell, same engine, same chat, same motion.

The behaviour is deliberately *system-native*: no accounts, no cloud, one file inside the app's
private storage. `android:allowBackup="false"` (set since M1) plus the new `data_extraction_rules`
means the transcript never leaves the device through Android auto-backup either.

---

## 2. Design

### 2.1 A new seam: `ConversationStore`

`core/runtime/.../Contracts.kt` now declares the persistence contract next to the other services:

```kotlin
/** Persists one conversation transcript. Implementations own storage and failure policy. */
interface ConversationStore {
    fun load(): List<ChatTurn>
    fun save(turns: List<ChatTurn>)
    fun clear()

    companion object { const val MAX_TURNS = 200 }
}
```

`ChatTurn` is the same type chat already streams, so no mapping layer leaks into the feature.

Why an interface in `core:runtime` and not a database in `feature:chat`:

- **The feature never owns storage.** `feature:chat` declares `requires = [InferenceEngine, ContentRepository, ConversationStore]`
  and receives whatever the host provides. A future SQLite/room/export implementation replaces one
  app-host line; chat code is untouched.
- **It is testable.** The feature tests inject an in-memory fake; the file implementation has its own
  JVM tests under `app/src/test`. No Android APIs in either test.
- **It is optional by design.** `ChatStore` takes `conversation: ConversationStore? = null`. `null`
  means memory-only — the composition keeps working with the demo engine or in a unit test without a
  file system. Persistence is an added capability, not a new hard dependency of the chat state
  machine.

### 2.2 ChatStore: restore, save, never lose newer state

`feature/chat/.../ChatStore.kt` changed in five ways:

1. **Restore in `init`.** Loaded turns are appended as normal `User`/`Assistant` items (blank text is
   skipped); ids are generated fresh so *restored* and *new* turns are indistinguishable to the UI and
   never collide.
2. **Persist at well-defined points** — never on every token:

   | Event | What is persisted |
   |---|---|
   | user turn added in `send()` | question, immediately |
   | `EngineChunk.Completed` | question + finished answer |
   | `EngineChunk.Failed` (incl. stop/typed reasons) | finished turns only |
   | unclosed stream fallback | finished turns only |
   | `retry()` after removing the failed assistant | remaining finished turns |
   | `stop()` | the answer-so-far is *not* persisted (turn never completed) |
   | `clear()` (only from the new-chat dialog) | `conversation.clear()` — file deleted |

3. **Snapshot rules.** Before saving, `persist()` keeps only turns that are **not failed and not
   streaming**, not blank, and takes the **newest `MAX_TURNS` (200)**. This is the single place that
   defines "what is worth keeping"; a future "keep failed turns" toggle changes one predicate, not
   the call sites.
4. **Async, ordered writes.** Saves are small but disk I/O must never run on the main thread in the
   app, so the scheme is:

   - `persistLock` guards counters `requestedSave` / `writtenSave`;
   - every `persist()` snapshots the turns and increments `requestedSave`;
   - on the IO dispatcher (app: `appScope.launch(Dispatchers.IO)`; tests: synchronously when no
     scope is injected) the writer skips the write if a **newer** snapshot has been requested since;
   - `clear()` increments the generation *before* deleting, so a queued write from before the clear
     can never resurrect the transcript.

   The result, pinned by `ChatStorePersistenceTest`, is that out-of-order IO completion can never
   overwrite newer history with older history — the failure mode a naive "save in a coroutine" has.

5. **Teardown stops, never wipes.** `ChatPlugin`'s disposal effect is now
   `appScope.cancel(); store.stop()` (it used to call `store.clear()` when chat was the only owner of
   memory). Deleting user data is now *only* the explicit new-chat action; leaving the screen or
   tearing the composition down must not erase anything.

### 2.3 The app-side implementation: `FileConversationStore`

`app/.../FileConversationStore.kt` (host-provided service, owner `host:conversation`, wired in
`AppComposition.kt`):

- one file: `files/conversation.json`, JSON array of `{"role":"USER","text":"…"}` (`Role.name`, so a
  future role is a tolerant parse, not a crash);
- **atomic replaces**: write `conversation.json.tmp`, then rename over the target, with a fallback
  path for file systems where rename-over fails;
- **tolerant load**: missing file, blank file or corrupt JSON all mean "empty conversation" and a
  single `SirrakConversation` log line — a storage failure must never take the app down;
- filters blanks and trims to `ConversationStore.MAX_TURNS`;
- `clear()` deletes the target *and* a leftover temp file;
- the file is created with the app sandbox's private permissions (`-rw-------`, single user).

No schema-version field yet; the parser tolerates unknown roles/turn shapes by dropping them (see
§7 — the migration point is named there).

### 2.4 UI: "new chat" with a confirmation

- The header shows a **new-chat icon** (`Icons.Outlined.AddComment`) only when the conversation is
  non-empty — an empty screen needs no destructive action.
- Tapping it opens a Material 3 `AlertDialog`: title "بدء محادثة جديدة؟" / "Start a new chat?", body
  "ستُحذف هذه المحادثة من هذا الجهاز. لا يمكن التراجع." / "This conversation will be deleted from
  this device. This cannot be undone.", confirm "محادثة جديدة / New chat", dismiss "إلغاء / Cancel".
- Confirm: `store.clear()` (memory + disk via the version guard) and the input draft resets.
  Dismiss: nothing changes.
- All five strings live in `values/strings.xml` + `values-en/strings.xml`; the dialog is localised in
  both languages and survives the existing language-switch recreation path.
- The header chip keeps its weighted slot and ellipsises as before; with the icon added the Arabic
  chip now shows `qwen2.5 1.5b instruct · جاهز على…`. That is the existing ellipsis rule doing its
  job — a polish item (§7), not a regression.

---

## 3. TDD record (RED → GREEN)

Written first, and observed failing before implementation (this project is typed Kotlin, so RED is a
compile failure rather than a red assertion):

```
:feature:chat:compileDebugUnitTestKotlin FAILED
e: ChatStorePersistenceTest.kt: Unresolved reference 'ConversationStore'
e: ChatStorePersistenceTest.kt: No parameter with name 'conversation' found
```

Then the implementation, then:

```
:core:runtime:testDebugUnitTest  :feature:chat:testDebugUnitTest  :app:testDebugUnitTest
BUILD SUCCESSFUL
```

**47 distinct JVM tests, 0 failures, 0 errors** (XML test-results aggregated; the release variant
duplicates the runtime ones in the report, the distinct count is 47):

| Module | Suite | Tests |
|---|---|---|
| core:runtime | `AppContextTest` (container/slot lifecycle) | 8 |
| core:runtime | `GgufFileTest` (model-store header parsing) | 8 |
| app | `AppCompositionTest` (manifest + add/remove-one-line rule) | 5 |
| app | `AppConfigTest` (config parsing/validation incl. engine ranges) | 7 |
| app | **`FileConversationStoreTest` (new)** | 6 |
| feature:chat | `ChatStoreTest` (streaming/stop/retry/no-model) | 7 |
| feature:chat | **`ChatStorePersistenceTest` (new)** | 6 |

New `ChatStorePersistenceTest` (fake store; deliberately sync — this is why `appScope` is nullable):

1. a saved transcript is restored when the store is created (order, roles, texts);
2. a completed exchange is saved in order (question + answer, exactly once);
3. the user turn is saved even when the engine fails before any token;
4. a stopped partial answer is **not** saved, the question is;
5. `clear()` empties memory **and** the store;
6. restored turns and new turns get distinct ids (no collision in the list key).

New `FileConversationStoreTest` (temp dir, JVM):

1. missing file → empty conversation;
2. round trip through a new instance (write, read back, same turns);
3. corrupt JSON → empty conversation, no throw;
4. `clear()` removes the file and a later load is empty;
5. only the newest `MAX_TURNS` survive a save;
6. blank text is never written.

Both suites run on the JVM in milliseconds — no emulator needed for the persistence contract.

---

## 4. Verification on the emulator (19 Nov 2026)

Debug build first, then the R8 release build installed over it (same signature, data kept).

| Check | Result |
|---|---|
| Debug build + install over the previous install | `BUILD SUCCESSFUL`; install succeeded, existing model store and selection kept |
| Send a question with the 1.5B model | file `files/conversation.json` appears **immediately** (110 B), contains only the USER turn, permissions `-rw-------` |
| Answer completes | file grows to 1,354 B with USER + full ASSISTANT text (the log line: `STATS prompt_tokens=86 prefill_ms=14579.8 ttft_ms=14581.5 generated=237 gen_ms=72740.5 tok_s=3.26 peak_rss_mb=1417.0 stopped=0`) |
| `am force-stop` + cold launch | `LaunchState: COLD TotalTime: 5069`, `MODEL_LOADED ms=751`, the conversation is on screen exactly as left (screenshot `sirrak-m3-persist-restored.png`) |
| New chat → confirm | UI returns to the empty state, input cleared, `conversation.json` gone (cat: no such file), relaunch still empty — the clear is not undone by a queued save |
| New chat → cancel | dialog dismisses, conversation and file unchanged (re-verified on the final release install) |
| English UI | dialog strings in English; same clear path; switching language back and forth keeps the persisted conversation |
| Stop before any token | the question is on disk, no assistant turn is; the UI shows the quiet "أوقفت الإجابة. أعد المحاولة إن شئت." + Retry row, no error (screenshot `sirrak-m3-stop-quiet.png`); reopening shows the question alone |
| Stop mid-answer (M2 display behaviour retained) | the partial answer stays on screen (M2 behaviour); `persist()` snapshots only finished turns, so the saved transcript keeps the question — the persistence rule is pinned by `ChatStorePersistenceTest` |
| Typed English question while Arabic UI | "Hello" saved as a user turn; stop before any token → file has USER only; restore shows it as a lone user turn (failed turns are not persisted) |
| Background streaming while on Settings tab | answer completes and is persisted (`generated=196 tok_s=2.95`); returning to chat shows it |
| Release build (`:app:assembleRelease`, 2 m 10 s) | unsigned 17,010,337 B; signed with the debug key for internal testing → **17,026,088 B (16.2 MiB)**; installed over the debug build with data preserved |
| Release cold start, restored conversation | `LaunchState: COLD TotalTime: 1705`, `MODEL_LOADED ms=524`, persisted conversation rendered on screen (screenshot `sirrak-m3-release-restored.png`), `versionName=0.1.0-m2` |
| Debug re-installed over the release build (same debug-key signature, data kept) | `LaunchState: COLD TotalTime: 4338`, `MODEL_LOADED ms=641`, conversation restored again; file readable via `run-as`: **584 B, `-rw-------`** — the transcript is ordinary app-private data, independent of build type |

The release check matters beyond the smoke: R8 keeps the generated kotlinx.serialization serializer
and the file store's parse path, so persistence also works in the minified build — no extra keep rule
was needed.

Notes on the numbers: emulator only, x86_64-on-desktop. 5.1 s cold start in debug vs 1.7 s in release
is the debug/Compose-tooling difference documented in M2, not a regression. Generation throughput in
this session (2.95–3.26 tok/s) is lower than M2's 4.5–5.1 tok/s because the conversation prompt has
grown (86 prompt tokens, ~14.6 s prefill on this emulator); it is still shape-only data.

---

## 5. Evidence screenshots

In `assets/`:

| File | Shows |
|---|---|
| `sirrak-m3-persist-restored.png` | after `force-stop` + cold launch: the full question and answer back on screen |
| `sirrak-m3-release-restored.png` | the R8 release build restoring the same conversation on cold launch |
| `sirrak-m3-new-chat.png` | Arabic new-chat confirmation dialog over an existing conversation |
| `sirrak-m3-new-chat-en.png` | English confirmation dialog |
| `sirrak-m3-cleared.png` | empty state after confirm — header `+` gone, no conversation |
| `sirrak-m3-stop-quiet.png` | restored conversation with a stopped turn at the end: quiet "stopped" copy + Retry, earlier completed turns intact and the file still holding only finished turns |
| `sirrak-m3-final-state.png` | final installed debug build after the release round-trip: the same conversation restored; the last known `files/conversation.json` is 584 B, private |

Log excerpts (from `adb logcat -s SirrakLlama` and `am start -W`):

```
LaunchState: COLD   TotalTime: 5069        (debug, restore)
MODEL_LOADED ms=751 n_ctx=2048 n_threads=4
STATS prompt_tokens=86 prefill_ms=14579.8 ttft_ms=14581.5 generated=237 gen_ms=72740.5 tok_s=3.26 peak_rss_mb=1417.0 stopped=0

LaunchState: COLD   TotalTime: 1705        (R8 release, restore)
MODEL_LOADED ms=524 n_ctx=2048 n_threads=4
versionName=0.1.0-m2
```

File on device (debug and release share the same app data):

```
/data/user/0/app.sirrak/files/conversation.json   -rw-------   1,354 B (after a full turn)
[{"role":"USER","text":"…"},{"role":"ASSISTANT","text":"…"}]
```

---

## 6. Testing-method note (a false alarm worth recording)

While testing, a tap meant for the composer's stop button appeared to navigate to Settings. It was
**not an app defect**: on the 720×1280 window the composer row is at y ≈ 960–1040 and the bottom
navigation bar's touch target starts at y ≈ 1072, so the stale tap coordinate (y ≈ 1070) landed on the
Settings tab. The fix is method, not code: derive coordinates from
`adb shell uiautomator dump` (Compose exposes `contentDescription`, e.g. `إرسال`, `إيقاف`,
`محادثة جديدة`, with exact bounds) instead of eyeballing a screenshot; re-verified stop, send, typing and navigation
afterwards. Recorded here so the next session does not spend time re-diagnosing it.

---

## 7. Limits (what this increment does not claim)

- **One conversation, not many.** There is one transcript and one file; no conversation list, rename,
  search, export, delete-one-message, edit, regenerate-from-here or branching. Those are separate M3
  items; the `ConversationStore` contract is the seam they will grow behind (an id-scoped store
  replaces the singleton).
- **A stopped answer is not saved.** The partial text stays on screen until the next action, but the
  persisted transcript keeps only completed answers. Reopening after a stop shows the question alone.
  (This is the honest trade: a half answer that looks like a full one is worse than no answer.)
- **Loss window = answer in flight.** If the process is killed after the question is saved but before
  the answer completes, the question survives and the answer does not. There is no write-ahead partial
  answer, by design.
- **200-turn cap is silent.** Older turns are dropped when the cap is hit; the UI does not warn yet.
- **No schema version field yet.** The parser is tolerant (unknown/corrupt → empty) but a future format
  change needs an explicit version/migration; that is the named migration point before export/sync.
- **No encryption beyond the Android app sandbox.** The file is private to the app (and excluded from
  auto-backup), but it is plain JSON on disk; device-level encryption is the OS's. Encrypted storage is
  only worth doing together with an app lock, which is not in M3 scope.
- **Not benchmarked for large transcripts.** Saves are off the main thread and the file is small, but
  a 200-turn Arabic transcript write time has not been measured; if it ever matters, the same seam
  takes a SQLite implementation without touching chat.
- **Release is still unsigned for production** (debug-key signed here for internal testing), no
  baseline profile; Play closed test is later.
- **Emulator-only performance claims**, as everywhere in this dossier; the test phone is still the
  measurement gate.

## 8. Open polish noticed (not blocking, logged so it is not lost)

- Header chip ellipsises more heavily now that the new-chat icon shares the header
  (`qwen2.5 1.5b instruct · جاهز على…`); consider shortening the chip to model name only, with the
  state as an icon, on narrow widths.
- The Arabic dialog title/body looked slightly different from the app's Amiri/IBM Plex typography;
  not confirmed as a bug — could just be Material dialog defaults. A design pass should confirm the
  dialog uses the app's type scale.
- Delete-model still has no confirmation/undo (carried from M2); now that a confirmation pattern
  exists in chat, reuse it for models.

## 9. Next

M3 queue (doc 14 §8), with item 1 now closed (M3 increment 2 — chat answer actions — followed and is recorded in [doc 16](16-sirrak-m3-chat-actions.md)):

1. ~~Chat persistence + conversation store~~ — **closed by this record**.
2. **Model catalogue + downloader** with licence display (needs the hosting decision; offline flag
   stays).
3. **Voice**: Whisper STT + Piper TTS plugins into the same composition (Step Zero harness exists).
4. **Arabic RAG**: EmbeddingGemma retrieval plugin + document import (Step Zero harness exists).
5. **arm64 phone measurement**: H1 numbers, grounded-prompt prefill/TTFT, thermal soak — needs the
   phone.
6. Release signing, baseline profile, Play closed test (12 testers / 14 days).
7. GPU/NPU exploration behind the same `InferenceEngine` interface.

## 10. Change map for persistence

| Change | File |
|---|---|
| Storage contract (what a store must do) | `core/runtime/src/main/java/app/sirrak/core/runtime/Contracts.kt` |
| When/what chat saves, restore, version guard | `feature/chat/src/main/java/app/sirrak/feature/chat/ChatStore.kt` |
| Which store the app provides, and its owner | `app/src/main/java/app/sirrak/AppComposition.kt` |
| File format, atomic write, tolerant load, cap | `app/src/main/java/app/sirrak/FileConversationStore.kt` |
| New-chat action + confirmation dialog | `feature/chat/src/main/java/app/sirrak/feature/chat/ChatScreen.kt` |
| Dialog copy | `feature/chat/src/main/res/values/strings.xml` + `values-en/strings.xml` |
| Swapping to SQLite/plain-text/export | implement `ConversationStore` and change one host line in `AppComposition.kt` |

---

*Previous record: [doc 14 — M2 engine](14-sirrak-m2-engine.md). M1: [doc 13](13-sirrak-frontend.md). Next: [doc 16 — M3 increment 2, chat answer actions](16-sirrak-m3-chat-actions.md).*
