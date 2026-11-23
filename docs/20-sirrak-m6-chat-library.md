# 20 — Sirrak M6, chat library & reading surface (19 Nov 2026)

**Status:** **M6 closed** — chat is no longer a single transcript. The user gets a real conversation
library (list, full-text search, rename, delete, export, share), rich Markdown/code/table rendering, a
live token budget with an explicit truncation warning, a measured generation speed, per-conversation
generation overrides and system prompts, and a persisted text-size preference. The M3 conversation
migrated without loss, and the M3/M4/M5 surfaces still work unchanged. **235 JVM tests, 0 failures**
(was 132 at M5 close), verified on the emulator. Version advanced `0.1.0-m5` → **`0.1.0-m6`**.

This is the record promised in the roadmap ([doc 06](06-roadmap.md)) for M6: multiple conversations,
history and renaming, markdown rendering, token meter, per-conversation overrides, font size. The
standing build gate applies unchanged — reusable, expandable, editable later, and never breaking the
working app.

---

## 1. What the user can now do

- **History screen** (clock icon in the chat header): every conversation, newest first, each row showing
  title, a one-line preview, the turn count and a marker for the open chat.
- **New conversation** from the header or history; the current one is kept, never overwritten.
- **Switch** by tapping a row (the open conversation is restored exactly, including its overrides).
- **Full-text search** across every title and every turn — case-insensitive, Arabic-aware, filtering the
  library live.
- **Rename** any conversation; the title is persisted and becomes the export filename.
- **Delete** with a destructive confirmation; the active conversation falls back to the newest remaining
  chat (or the empty state), never to a dangling id.
- **Export** any conversation as **Markdown** (title, bold speaker labels, blank-line separated) or
  **JSON** (schema versioned, machine readable). On API 29+ the file lands in
  **Downloads/Sirrak/**; otherwise the system share sheet opens.
- **Markdown reading surface** — headings, paragraphs, bullet and ordered lists, block quotes, fenced
  code blocks (LTR, monospace, horizontally scrollable), GitHub-style tables, rules, and inline
  bold/italic/strikethrough/code/links. Arabic prose follows content direction; code never does.
- **Token meter** above the composer: estimated tokens / prompt budget, amber "near the limit" and red
  "older messages are not sent" warnings, plus measured **tokens per second** after a finished answer.
- **Per-conversation overrides**: a settings sheet per chat with a conversation system prompt and up to
  four stop sequences. They win over the per-model and global layers, only for that conversation, and are
  exported/restored with it.
- **Text size**: Settings → Appearance offers 85 / 100 / 115 / 130 %, persisted, applied app-wide to sp
  text only (dp layout is untouched, so nothing collides).

---

## 2. Architecture

M6 kept the M3 split: contracts in `:core:runtime`, the file implementation in `:app`, UI in
`:feature:chat`, reusable visual primitives in `:core:design`. No module gained a dependency it
should not have, and the chat feature still talks to interfaces only.

### 2.1 The conversation library contract

`core/runtime/.../Conversations.kt`:

- `Conversation(id, title, createdAt, updatedAt, turns, systemPrompt, overrides)` — one chat in full.
- `ConversationSummary(id, title, preview, turnCount, createdAt, updatedAt, isActive)` — what a list row
  needs, so the history screen never loads every transcript to draw.
- `ConversationLibrary` — list/summaries, find, create, save, rename, delete, active id, set active,
  search; every mutation returns the new summaries so UI state cannot drift.
- `ConversationTitles.derive(turns)` — first user question, single line, capped at 48 chars, localised
  fallback when the chat is empty.
- `ConversationSearch.matches(...)` — the same predicate for summaries and full transcripts.
- `ConversationExport` — Markdown and JSON (de)serialisation, including overrides.
- `TokenEstimate` / `ChatBudget` / `GenerationSpeed` (see §2.7).

### 2.2 File storage: transcripts are the source of truth

`app/.../FileConversationLibrary.kt`:

- `files/conversations/index.json` holds only the active id plus lightweight summaries for fast starts.
- `files/conversations/<id>.json` is the authoritative transcript (schema version, title, timestamps,
  prompt, sparse overrides, turns).
- Every load validates the index against the transcripts and **rebuilds it from the transcripts when it
  is missing, empty or corrupt**. The active id is authoritative when the index exists (an explicit
  `null` stays null); only a missing index falls back to the newest conversation.
- A corrupt transcript is skipped, not fatal; the corrupt index is backed up as `.corrupt` before the
  rebuild. Writes are atomic (temp file + rename), so a kill mid-save loses at most the in-flight write.
- The M3 single file `files/conversation.json` is migrated exactly once into the library (keeping its id
  and timestamps) and renamed `conversation.json.migrated`; a second launch never duplicates it.
- A 200-turn cap (`ConversationStore.MAX_TURNS`) bounds file size; the oldest turns are dropped, the
  same policy the engine already applies to the context window.
- `clock` and `nextId` are injectable, so every policy above is unit-tested without Android.

### 2.3 Search, preview, titles

Search is a full scan of title + every turn, case-insensitive, so it finds text anywhere in the chat, not
just in the preview. The preview shown in the list is derived, never stored: first non-blank line of the
last turn, Markdown block markers (`#`, `>`, `-`, `1.`) stripped, 96 characters. A title is explicit if
the user renamed it, otherwise derived from the first question, otherwise the localised "New chat"
fallback. All three rules live in `:core:runtime` as pure functions.

### 2.4 Chat integration

`feature/chat/.../ChatStore.kt` gained a conversation-library dependency and now owns the history
state machine: `conversations()`, `searchConversations()`, `openConversation(id)` /
`loadConversation(id)`, `newConversation()`, `renameConversation`, `deleteConversation`,
`updateConversationConfig`. Persistence happens on every completed exchange (not per token), and the
active id is set before the first turn is stored, so a process death mid-conversation can never leave a
transcript that the library cannot find.

The M3 behaviour is preserved: a send still shows the user turn immediately, streams the answer, keeps
partial text on failure, supports stop/regenerate, and restores the last conversation on cold start. The
existing M3 tests were not modified except where the storage moved; they pass unchanged.

### 2.5 Export and share

`feature/chat/.../ConversationFiles.kt` is the only file-touching code in the chat UI:

- Markdown: `# title`, then localised bold speaker labels followed by the text, blank line separated,
  trailing newline. Round-trips through the JSON export, which carries the same turns + overrides and is
  validated by tests.
- JSON: `ConversationExport.toJson` / `fromJson`, schema version 1 — external tools can read it, and an
  exported/imported chat keeps its system prompt, sparse overrides and timestamps.
- API 29+: MediaStore insert into Downloads/Sirrak, toast on success/failure. Below Q (API 28) the system
  share sheet is used instead of asking for a storage permission.

### 2.6 Reading surface: a small Markdown renderer

`core/design/.../Markdown.kt` is a dependency-free, token-based renderer (no WebView, no HTML, no
telemetry):

- `MarkdownParser.parse(markdown): List<MarkdownBlock>` — `Heading`, `Paragraph`, `CodeBlock`,
  `Quote`, `BulletList`, `OrderedList`, `Table`, `Rule`.
- `inlineMarkdown(...)` / `markdownLinks(...)` — bold, italic, strikethrough, inline code, links.
- `MarkdownText(markdown, modifier, style, color)` — the composable used by `AssistantProse`, so answers
  render richly everywhere the assistant speaks.
- Unterminated code fences are safe (streaming answers grow a fence token by token); code blocks are
  pinned `TextDirection.Ltr` + monospace, prose uses `TextDirection.Content` so an Arabic answer with an
  English identifier still reads correctly. Tables scroll horizontally instead of clipping.
- Explicitly out of scope for M6 (documented, deferred): nested lists, task lists, images, alternate
  fences, and clickable links (links render as styled text until a browser handoff micro-milestone).

### 2.7 Token budget, truncation warning, speed

`core/runtime/.../ChatBudget.kt`:

- `TokenEstimate.of(text)` — 4 chars/token approximation with 4 tokens per message overhead, the same
  estimator the meter shows.
- `TokenBudget(estimatedTokens, contextTokens, reservedForAnswer, promptBudget)` with `overflow`
  (prompt over the window) and `nearLimit` (≥ 85 %, amber warning).
- `ChatBudget.compute(turns, systemPrompt, contextTokens, maxAnswerTokens)` — the window comes from
  `ContextWindowProvider`, which the app supplies from `config.engine.contextTokens` (default 2048), so a
  new model size is a config value, never a UI constant.
- `GenerationSpeed.tokensPerSecond(text, durationMillis)` — measured on the actual completion, shown to
  one decimal below 10 tok/s, whole numbers above.

`PromptMeter` (`feature/chat/ChatMeter.kt`) renders the budget line, the warning line and the speed
line; it hides itself when there is nothing to say. The meter is informational — truncation is still
enforced by the native context window and by `ChatBudget` when assembling the request.

### 2.8 Per-conversation overrides go through layer 6

M5's doc 19 §2 reserved layer 6 ("conversation override") in `ModelControlResolver` but had no UI. M6
wires it end to end:

- `Conversation.systemPrompt` and `Conversation.overrides: GenerationOverrides?` are sparse and persisted.
- `GenerationSettingsProvider.options(conversationOverrides, conversationPrompt)` gained a **default
  implementation** that folds the conversation layer over `options()`, so every engine, fake and test
  keeps working; the app's `AppModelControlService` overrides it and passes the same layer into
  `ModelControlResolver`, where clamping and source reporting already live.
- ChatStore builds a request with `generationSettings.options(conversation.overrides, conversation.systemPrompt)`;
  the conversation wins over per-model > global > app.json > resource, and `params.clamped()` runs before
  the native call exactly as in M5.
- The conversation settings sheet edits the prompt and stop sequences; other override fields stay
  preserved untouched when saving (the dialog is a view over the same sparse record).

### 2.9 Text size as data

`:core:design` now owns a `FontScale` registry (`FontScales.all`): id, percent, AR/EN label resource.
`FontScales.byId` resolves a persisted id, clamps numeric fallbacks into the supported range and snaps to
the nearest preset, so an unknown/removed id can only ever open at 100 %. `ProvideFontScale(id)` overrides
`LocalDensity.fontScale` only — dp geometry is untouched — and is applied once at the app root.
`FontScaleOption` is the reusable, TalkBack-announced 56 dp selectable row; Settings → Appearance iterates
the registry, so adding 145 % is one entry plus two strings, no composable change.

---

## 3. Design rules honoured

- **Data over hardcoding**: conversation limits, preview length, title length, budget percentages, font
  presets, prompt/stop limits and export schema are constants/registries in `:core:runtime` or
  `:core:design`; the UI iterates them. Strings exist in Arabic and English.
- **One source of truth**: transcripts own the data, the index is a derived cache and is rebuilt from
  them; the token budget is computed in one place and displayed by one composable; the font registry is
  the only list of text sizes.
- **Reusable primitives**: `MarkdownText` (design), `PromptMeter`, `FontScaleOption`,
  `ConversationFiles` and the history dialogs are independently reusable; the history screen composes
  them instead of embedding behaviour.
- **Composition**: no new Cordis plugin was needed — chat already has a plugin; the library and context
  window are provided by the host composition (`AppComposition`) as contracts, so the feature stays
  testable and replaceable.
- **Accessibility**: 56 dp rows, TalkBack roles on list rows/actions (RadioButton for font scale), the
  meter reads as text, destructive actions are labelled and confirmed, colour is never the only cue.

---

## 4. Persistence formats

```json
{
  "schemaVersion": 1,
  "activeId": "c-1795081356448",
  "summaries": [
    { "id": "c-1795081356448", "title": "سياق طويل", "preview": "سؤال طويل رقم 40 …",
      "turnCount": 40, "createdAt": 1795081356448, "updatedAt": 1795081459010 }
  ]
}
```

```json
{
  "schemaVersion": 1,
  "id": "c-long-context",
  "title": "سياق طويل",
  "createdAt": 1795081356448,
  "updatedAt": 1795081459010,
  "systemPrompt": "brief",
  "overrides": { "temperature": null, "stop": ["END"], … },
  "turns": [ { "role": "USER", "text": "…" }, { "role": "ASSISTANT", "text": "…" } ]
}
```

- Sparse overrides: only non-null fields and non-empty stop lists are meaningful, so a conversation that
  was never customised stores `null` and follows the global layers.
- The visible device transcript above was produced by the real save flow (conversation settings dialog →
  Save → `run-as cat files/conversations/c-long-context.json`), not by a test harness.

---

## 5. Tests

**235 JVM tests, 0 failures** (was 132 at M5 close). Suite counts after M6:
`:core:runtime` 58, `:core:design` 47, `:feature:chat` 40, `:app` 90.

New or extended for M6:

| Suite | Tests | What it pins |
|---|---|---|
| `ConversationsTest` (runtime) | 15 | titles, search, export Markdown/JSON round-trip incl. overrides, summary derivation |
| `ChatBudgetTest` (runtime) | 6 | estimate, reserved answer, overflow, near-limit, zero window, speed math |
| `ConversationBackupTest` (runtime) | 3 | corrupt/partial data never crashes |
| `MarkdownTest` (design) | 25 | every block type, inline forms, bidi/LTR pinning, streaming fences, tables, safety |
| `FontScalesTest` (design) | 9 | registry uniqueness/order, id and numeric fallbacks, clamp/snap, factor math |
| `FileConversationLibraryTest` (app) | 18 | index/transcript recovery, corrupt index backup, active-id authority, atomic writes, migration, preview strip, overrides round-trip |
| `ChatStoreConversationsTest` (chat) | 12 | list/search/open/new/rename/delete/update-config, persistence on completion |
| `ChatStoreBudgetTest` (chat) | 6 | meter updates, conversation override wins, global layer unchanged, saved config, measured speed |
| `AppModelControlServiceTest` (app) | +1 | conversation overrides/prompt win at resolver level without writing the per-model layer |
| Existing suites | 132 | no M3/M4/M5 regression (composition, config, engine, settings, theme packs) |

RED evidence worth keeping: the preview test failed with `## ` still in the preview (implemented
`PREVIEW_MARKER` strip), and the index test failed with the active id defaulting to the newest transcript
until the index became authoritative.

---

## 6. Device evidence (emulator, API 36, x86_64)

Environment unchanged from M5: no test phone by F8, so `Nawa_Phone` (Android 16, 6 GB, RTL Arabic, dark
theme) is the approved test device. Model: `qwen2.5-0.5b-instruct-q4_k_m.gguf`.

Screenshots in `docs/assets/`:

| file | shows |
|---|---|
| `m6-history.png`, `m6-history2.png` | history list: titles, previews, turn counts, active marker, actions |
| `m6-search.png` | live search filtering the library |
| `m6-rename.png`, `m6-renamed.png` | rename dialog and the renamed row |
| `m6-markdown.png` | answer rendering headings, lists, quote, code (LTR) and a table in an Arabic RTL chat |
| `m6-meter.png` | normal token meter after sending |
| `m6-overflow.png` | long conversation: 2434 / 1536, red warning "older messages are not sent" |
| `m6-live-speed.png` | live model answer plus measured tok/s in the meter row |
| `m6-conversation-settings.png`, `-filled.png`, `-config-saved.png` | per-conversation settings sheet, filled values, saved state |
| `m6-delete-confirm.png` | destructive delete confirmation |
| `m6-fontscale-default.png` | Settings → Appearance: four text-size rows, 100 % selected |
| `m6-fontscale-130.png` | 130 % selected: text scales, layout does not (rows stay usable) |
| `m6-fontscale-chat130.png` | chat at 130 %: transcript, meter and composer rescale without clipping |
| `m6-fontscale-library130.png` | library at 130 % |
| `m6-fontscale-persisted.png` | after force-stop + relaunch: 130 % still selected |
| `m6-release-home.png` | release build 0.1.0-m6, data and model still loaded |
| `m6-release-history.png` | release build: conversations preserved after the signed-over-debug install |
| `m6-release-settings.png` | About row: version 0.1.0-m6 |

Device checks performed with `run-as app.sirrak`:

- `files/conversations/index.json` and per-conversation transcripts exist after the M3 migration; the
  legacy `conversation.json.migrated` is present.
- The conversation settings save flow persisted `"systemPrompt": "brief"` and `"stop": ["END"]` into
  `c-long-context.json`; the resolver wiring and clamping are covered by unit tests.
- Exports landed in `/sdcard/Download/Sirrak/`: `Renamed chat.md` (localised bold speaker labels) and
  `Renamed chat.json` (436 bytes, schema version 1). The Markdown and JSON round-trip is unit-tested.
- A live send after the rebuild streamed an answer, updated the meter and the speed line, and the new
  turn was persisted.

---

## 7. Release build

- `assembleRelease` (R8) succeeded; signed with the debug keystore via `apksigner` and installed over the
  debug build (same certificate) so the existing conversations survived.
- Release APK: `app/build/outputs/apk/release/app-release-signed.apk`, versionName `0.1.0-m6`.
- Verified after install: version row shows `0.1.0-m6`, conversations and overrides still present,
  Markdown rendering and the meter still work. See `m6-release-home.png`, `m6-release-history.png`,
  `m6-release-settings.png`; `dumpsys package app.sirrak` reported `versionName=0.1.0-m6` and an unchanged
  `firstInstallTime`.
- `dumpsys package app.sirrak` after install: `versionName=0.1.0-m6`, `firstInstallTime=2026-11-18`, so
  the signed APK replaced the debug build in place and the conversations survived.

---

## 8. Lessons recorded

1. **A derived cache must be rebuildable.** The first index implementation trusted the file; the test
   that deleted it proved the library only works if transcripts can regenerate every summary. The index
   is now a cache with one owner and one rebuild path.
2. **An explicit null is a value.** "No active conversation" and "no index yet" had to be different
   states; conflating them made the app reopen a deleted chat. The index's presence is now the signal.
3. **Streaming text is Markdown in progress.** Unterminated fences and half tables must render without
   crashing, so the parser treats "block still open" as a normal state instead of a parse error.
4. **Bidi is per-span, not per-screen.** Arabic prose with English code needs `Content` direction for the
   prose and forced `Ltr` for code; one global direction is always wrong somewhere.
5. **Speed needs a decimal.** 0.5B on an emulator generates under 10 tok/s; an integer "0 tok/s" reads as
   broken, so the formatter keeps one decimal below ten.
6. **The resolver layer was the right extension point.** Wiring conversation overrides through
   `ModelControlResolver` instead of merging in the UI kept clamping, source reporting and the tests in
   one place; the default provider method means no engine had to change.

---

## 9. Limits and next

- **Streaming renders Markdown per frame**; very long answers re-parse the block list on each chunk.
  Acceptable at 0.5B sizes; a cached parse is a future optimisation if a larger model shows jank.
- Markdown: nested lists, images, task lists, alternate fences and clickable links are deliberately
  deferred.
- The token estimate is a heuristic (4 chars/token); it is labelled as an estimate and the real limit is
  still the native window.
- Font scale is textual only by design; dp geometry stays fixed. A separate "compact spacing" preference
  can be added later as another registry without touching call sites.
- **M7 (next)**: attachments (image/document/camera) and multimodal plumbing.
- 0.5B remains the only model exercised on the emulator; the 1.5B GGUF is staged for the heavier reading
  and RAG work in M7–M8.

Per the roadmap ([doc 06](06-roadmap.md)) the goal keeps running toward M7–M11 until the founder stops it.