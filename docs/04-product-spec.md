# 04 — Product specification (v1.0)

**Product:** Nawa (نواة) — working title. Android on-device AI assistant, Arabic-first, private by
construction.
**Owner:** the builder (solo). **Status:** draft for build. **Depends on:** [research](01-research-report.md),
[clearance](02-clearance-memo.md), [council verdict](03-qiyas-council.md).
**Version:** 0.1 · 18 Nov 2026.

---

## 1. Vision and positioning

**Vision:** Give every Arabic-speaking Android user a capable, private AI assistant that works
without internet, without an account, without a subscription, and without sending a byte to anyone.

**One-line pitch:** *"Your phone's own AI — offline, in Arabic, with your documents, and it never
phones home."*

**Positioning statement:** For Arabic-speaking Android users who want AI but will not upload their
documents or pay a monthly fee, Nawa is an on-device assistant that chats, reads your files and
speaks Arabic — unlike PocketPal AI, which has no document Q&A and no F-Droid build, and unlike
cloud assistants, which require your data.

**What we are not:** not a chatbot wrapper, not a cloud service, not a model marketplace, not a
roleplay platform (v1), not a "fatwa engine", not a PocketPal clone or fork.

### 1.1 Product principles (used to settle every design argument)

1. **Offline is the default, not a mode.** Every core flow works in airplane mode. Network is only
   for fetching models and (opt-in) web search/leaderboard.
2. **Honest about capability.** Every speed number, device label and model limit is measured and
   shown. No "AI magic" marketing; no NPU claims without per-device evidence.
3. **Arabic is a first language, not a locale.** RTL layout, Arabic typography, Arabic-first prompts,
   Arabic voices, Arabic eval. English is the second language, not the reference.
4. **The user owns the data.** Chats and documents stay on-device; export/delete is one tap;
   no analytics SDKs; no cloud sync in v1.
5. **Fail soft.** A model that does not fit, a device that cannot accelerate, a document that will not
   parse — explain and degrade, never crash.
6. **Free core, fair extras.** No ads; no paywall on privacy; one-time Pro for optional extras later.

---

## 2. Users and jobs

### 2.1 Primary persona — "Abu Zaid" (student/professional, 27, Baghdad)

- Samsung Galaxy A54, 6 GB RAM, 128 GB storage (~35 GB free), mid-range Exynos/Snapdragon, metered
  mobile data, occasional VPN, uses ChatGPT/Claude at university but dislikes uploads and limits.
- **Jobs:** (J1) ask questions and get Arabic answers offline; (J2) understand lecture PDFs and
  summarise them in Arabic; (J3) dictate notes by voice in Arabic; (J4) use it on the bus with no
  signal; (J5) not pay a subscription.
- **Frustrations today:** ChatGPT needs internet and account; PocketPal has no document Q&A and weak
  Arabic voice; local apps crash on his phone; model choice is confusing; 2 GB downloads on metered
  data.
- **Success for him:** installs, gets a working Arabic model in under 5 minutes on Wi-Fi, asks a
  question about his PDF, gets a cited answer, uses it weekly.

### 2.2 Secondary persona — "Sara" (professional, 33, Dubai/Baghdad)

- Flagship Android, privacy-sensitive, handles client contracts, will pay once for trust.
- **Jobs:** offline contract Q&A, confidential document summarisation, auditable app (no network
  calls), Arabic/English mixed documents, export answers.
- **Success:** sees a "network activity" page proving zero calls; uses document RAG weekly; pays for
  Pro if it saves time.

### 2.3 Tertiary persona — "Omar" (developer, 22)

- Power user; imports GGUF from Hugging Face; benchmarks; wants an OpenAI-compatible local API,
  MCP later, F-Droid and per-model settings.
- **Success:** finds a model in-app, imports a custom GGUF, benchmarks it, shares a result; becomes a
  contributor/evangelist.

### 2.4 Jobs-to-be-done summary (v1 scope)

| # | Job | Priority | Feature |
|---|---|---|---|
| J1 | Chat with a local model in Arabic/English | P0 | Chat |
| J2 | Ask questions about my own documents with citations | P0 | Document RAG |
| J3 | Dictate in Arabic | P0 | STT (Whisper) |
| J4 | Understand an image/screenshot | P0 | Vision |
| J5 | Get a working model without breaking my phone/data plan | P0 | Model manager + recommender |
| J6 | Hear answers read aloud in Arabic | P1 (v1.1) | TTS |
| J7 | Hands-free conversation | P1 (v1.1) | Voice mode |
| J8 | Custom assistant personality/system prompt | P1 (v1.1) | Personas (local) |
| J9 | Know if my phone/model is fast | P2 (v1.1) | Benchmark |
| J10 | Summon it from anywhere / make it my assistant | P2 (v1.1) | Assistant role, QS tile, share target |
| J11 | Serve models to other apps | P2 (v1.2) | Local API |
| J12 | Generate images | Out (v1.2+) | Image gen |

---

## 3. Functional requirements

Priority legend: **P0** = v1.0 must ship; **P1** = v1.1; **P2** = later; **OUT** = not planned.

### 3.1 Onboarding and first run (P0)

| ID | Requirement | Acceptance |
|---|---|---|
| ONB-1 | 3 screens max: privacy promise, pick language (Arabic default if system locale is Arabic), pick a model | completes in under 60 s; skip path exists |
| ONB-2 | Device capability check: RAM, storage, SoC, Android version, GPU/NPU hints | shows "Recommended model for your phone" and a warning if storage < 3x model size |
| ONB-3 | Download flow with size, speed estimate from benchmark DB, Wi-Fi-only default, pause/resume, background notification | survives kill/restart; no partial corrupt file accepted |
| ONB-4 | Model licence consent for gated models (Gemma/Llama) with links; never auto-accept | consent stored with model record |
| ONB-5 | "Try without a model" path → UI tour + sample prompts; clear call to action | user reaches chat screen |

### 3.2 Chat (P0)

| ID | Requirement | Acceptance |
|---|---|---|
| CHT-1 | Streaming token rendering, stop button, keep screen awake optionally | stop within 200 ms |
| CHT-2 | Edit and re-generate from any message; branch conversation | branch persisted; no data loss |
| CHT-3 | Markdown rendering incl. code blocks, tables, RTL paragraphs; copy/select | long Arabic text renders correctly |
| CHT-4 | Conversation history list, search (FTS), rename, delete, export (JSON/MD) | search returns < 100 ms for 1k messages |
| CHT-5 | Per-conversation system prompt + temperature/top-p/max tokens overrides | persisted |
| CHT-6 | Context-window management: token counter, truncation policy, warning near limit | no silent context overflow |
| CHT-7 | Stop sequences, regeneration, token/s display (opt-in) | accurate within 10% |
| CHT-8 | Share conversation as text/image; copy answer | works offline |

### 3.3 Model manager (P0)

| ID | Requirement | Acceptance |
|---|---|---|
| MOD-1 | Curated catalogue: Arabic-capable Apache-2.0 models first (Qwen3.5-0.8B/2B, LFM2.5, SmolLM3); Gemma 4 / Llama via user-consented download | catalogue metadata: size, RAM, licence, quant, languages |
| MOD-2 | Hugging Face search + download by repo/file; token support for gated repos | download resumes; checksum verified |
| MOD-3 | Local import via SAF (device/SD card); GGUF validation (magic, metadata, arch) | invalid file rejected with reason |
| MOD-4 | Model detail: size, RAM need, context, quant, speed estimate for this device, licence, delete/move | estimate within 25% of measured |
| MOD-5 | Storage manager: app-private vs SAF, move model, free space check, orphan cleanup | no silent deletion |
| MOD-6 | Recommended default for device class; "why this model?" explanation | shown on first run |

### 3.4 Document RAG (P0 — the differentiator)

| ID | Requirement | Acceptance |
|---|---|---|
| RAG-1 | Import PDF (text layer), DOCX, EPUB, TXT, MD via SAF or share intent | each format imports with progress + cancel |
| RAG-2 | Chunking with overlap; Arabic-aware segmentation (sentence boundaries) | chunk stats shown |
| RAG-3 | Embeddings with EmbeddingGemma 2 (bundled ~300 MB, or downloaded) | index built offline |
| RAG-4 | Local vector index (sqlite-vec or equivalent) + BM25 hybrid; citations to page/paragraph | answers cite source chunk/page |
| RAG-5 | Library screen: documents, size, chunks, last indexed, delete/reindex | reindex on model change warning |
| RAG-6 | Per-document chat: "ask this document" and cross-document "ask library" | scope selector in composer |
| RAG-7 | Arabic PDFs: text layer first; OCR fallback in v1.1; scanned PDF message | explains limitation |
| RAG-8 | No document content leaves device; index encrypted at rest (optional v1.1) | verify with network monitor |

### 3.5 Vision (P0)

| ID | Requirement | Acceptance |
|---|---|---|
| VIS-1 | Attach image from gallery/camera to chat | works offline |
| VIS-2 | Supports a VL model (Gemma 4 E2B/E4B or Qwen3.5 VL) via mmproj or litertlm | model selection warns if no VL |
| VIS-3 | Image pre-processing: resize/EXIF rotate, size limit | no OOM on 12 MP photo |
| VIS-4 | OCR-style "extract text" prompt shortcut (Arabic/English) | reasonable accuracy on screenshots |
| VIS-5 | Multi-image per message (v1.1) | — |

### 3.6 Speech (P0 dictation / P1 TTS+voice mode)

| ID | Requirement | Acceptance |
|---|---|---|
| STT-1 | Hold-to-dictate + toggle; Whisper small/base; Arabic + English + auto | WER target per Step Zero H3 |
| STT-2 | Audio never leaves device; permission requested with rationale | verified |
| STT-3 | Live partial transcript (v1.1); language picker | — |
| TTS-1 | Read answer aloud: Piper Arabic voice (bundled optional pack), Kokoro English, system TTS fallback | offline; speed control |
| TTS-2 | Voice selection, speed, auto-read toggle | persisted |
| VM-1 | Hands-free mode: continuous STT → LLM → TTS with barge-in | v1.1 |
| VM-2 | Wake-word optional; push-to-talk default (battery/privacy) | documented |

### 3.7 Arabic-first UX (P0)

| ID | Requirement | Acceptance |
|---|---|---|
| AR-1 | Full RTL layout; logical properties; mirrored icons where needed | no LTR bleed in Arabic |
| AR-2 | Arabic typography (e.g. IBM Plex Sans Arabic / Noto Naskh Arabic), correct line height, diacritics rendering | visual QA on 2 devices |
| AR-3 | Arabic system prompts + dialect presets (MSA, Iraqi, Gulf, Levant, Egyptian) selectable | prompt templates shipped |
| AR-4 | Arabic answer formatting: numerals option (٠١٢ vs 012), punctuation direction | setting |
| AR-5 | Arabic eval suite run in CI (300 prompts: factual, reasoning, dialect, refusal, RAG) | score regression gate |
| AR-6 | Mixed Arabic/English/Latin text renders correctly (bidi) | test cases in CI screenshot tests |

### 3.8 Privacy and trust (P0)

| ID | Requirement | Acceptance |
|---|---|---|
| PRV-1 | Zero telemetry/analytics/crash SDKs in the free flavour | dependency audit in CI |
| PRV-2 | Network audit page: list of every network call and why; toggle to block all networking | user can verify offline operation |
| PRV-3 | No account, no email, no cloud sync in v1 | install → chat without signup |
| PRV-4 | Data export/delete all; local-only storage | delete removes DB + files |
| PRV-5 | Privacy policy + security disclosure policy published | links in About |
| PRV-6 | AI output reporting (Play requirement): report offensive output → local record + optional email/issue | works offline (queues) |

### 3.9 Settings and power features

| ID | Requirement | Priority |
|---|---|---|
| SET-1 | Generation defaults per model (temp, top-p, top-k, repeat penalty, max tokens) | P0 |
| SET-2 | Theme (system/light/dark), font size, language | P0 |
| SET-3 | Backend selection: CPU / GPU (OpenCL) / NPU pack; show detected capability | P0 |
| SET-4 | Offload layers slider, threads, batch size; "auto" recommended | P1 |
| SET-5 | Benchmark screen: prompt-processing and decode tok/s, memory; save result | P1 |
| SET-6 | Optional public leaderboard upload (anonymous device/model metadata) | P1 |
| SET-7 | Personas: local system-prompt presets only (no marketplace) | P1 |
| SET-8 | Local API server with bearer token, loopback default | P2 (v1.2) |
| SET-9 | Default assistant role, QS tile, share target | P1 |
| SET-10 | Import/export settings + chat backup | P1 |

### 3.10 Content and safety (P0)

| ID | Requirement | Acceptance |
|---|---|---|
| SAF-1 | System-prompt guardrails: no medical/legal/financial definitive advice without disclaimer; no religious rulings | prompt template + UI disclaimer |
| SAF-2 | "Adab" mode (opt-in): stricter tone/content filter for family use | documented limits |
| SAF-3 | Report AI output (Play AI policy) + block-list of prompts? (no) | PRV-6 |
| SAF-4 | No CSAM/illegal content generation support; model licence acceptable-use enforced by model choice | policy page |
| SAF-5 | Clear "AI can be wrong" disclaimer on first run and in About | shown once |

---

## 4. Key flows

### 4.1 First run → first answer (target: under 5 minutes on Wi-Fi)

1. Splash → choose language (Arabic if system Arabic) → privacy promise (3 bullets, no walls of text).
2. Device check (RAM/storage/SoC) → recommended model card: "Qwen3.5-2B Q4 (1.4 GB) — works well on
   your phone; ~12 tok/s estimated". Alternative "smaller/faster" and "better/slower".
3. Tap download → Wi-Fi check → progress with pause; licence screen if gated model.
4. Download done → "Start chatting" → chat screen with 3 Arabic sample prompts + mic button.
5. First message → streaming answer → after 3 messages, gentle tip: "Add a document to ask about your
   files".

Failure paths: low storage → explain + offer smaller model; download fails → retry/alternate source;
model load fails → diagnostics screen (backend, memory) + fallback model suggestion; no model → tour.

### 4.2 Document Q&A

1. Library tab → "+" → SAF picker → PDF chosen → progress (parse, chunk, embed) → "Ready. Ask anything."
2. In composer: attach icon shows "Ask this document" chip → question → retrieval → answer with
   inline citations [1] [2] → tap citation → jump to page/paragraph text.
3. If answer not in document → "Not found in this document" rather than hallucinating (grounded mode
   toggle: strict / balanced / creative).

### 4.3 Arabic dictation

1. Mic button (hold or tap) → permission rationale → record → waveform → transcribe → text inserted in
   composer (replace or append choice).
2. Arabic language auto-detected from system, override in settings; dialect note in settings.

### 4.4 Failure/degradation rules

| Failure | Behaviour |
|---|---|
| Model does not fit RAM | block download, show estimated need vs available, suggest smaller quant/model |
| Model load crash | catch, log locally, offer diagnostics + alternate backend/model; never loop-crash |
| Document parse fails | show reason (scanned PDF, protected, corrupt), offer OCR (v1.1) |
| Thermal throttle | show "device warm — slowing down" instead of silent slowdown |
| Storage full mid-index | pause, keep partial, ask to free space |
| No network | everything works except model download/search/leaderboard; explicit banner |

---

## 5. Screen inventory (v1.0)

| # | Screen | Notes |
|---|---|---|
| 1 | Splash / init | fast, no logo animation > 500 ms |
| 2 | Language + privacy onboarding (3 steps) | RTL |
| 3 | Device check + recommended model | capability matrix |
| 4 | Model download / progress | resumable |
| 5 | Chat list (history) | search, rename, delete |
| 6 | Chat detail | streaming, attachments, citations, regenerate |
| 7 | Model manager (catalogue / installed / downloads) | filters, size, licence |
| 8 | Model detail | speed estimate, delete/move |
| 9 | Library (documents) | import, reindex, delete |
| 10 | Document detail / chunks preview | citations source |
| 11 | Settings root | sections |
| 12 | Settings: generation defaults | per model |
| 13 | Settings: backends/performance | CPU/GPU/NPU, threads |
| 14 | Settings: language/theme/typography | Arabic options |
| 15 | Privacy & network audit | call list, offline toggle |
| 16 | About / licences / report AI output | policy compliance |
| 17 | Benchmark (v1.1) | opt-in upload |
| 18 | Personas (v1.1) | local presets |
| 19 | Voice mode (v1.1) | full screen |
| 20 | Diagnostics | export report for bug reports |

Design language: calm, utilitarian, Arabic-first typography, no gradients/glassmorphism excess; dark
and light; Material 3 dynamic colour optional but default brand palette; accessible contrast AA.

---

## 6. Non-functional requirements

| Area | Target | Measurement |
|---|---|---|
| Decode speed (mid-range, 2B Q4) | at least 12 tok/s (Step Zero gates the target) | benchmark harness |
| TTFT | at most 1.5 s at 2k context | benchmark harness |
| Cold start to chat list | at most 1.5 s | Macrobenchmark |
| Chat screen frame drops | < 1% janky frames while streaming | JankStats/Perfetto |
| Memory | no OOM in 100-message chat on 6 GB device | stress test |
| Crash-free sessions | at least 99.5% closed beta; at least 99.7% at launch | Play vitals / local |
| Model-load success | at least 90% per device class | diagnostics |
| APK size | core APK under 60 MB; models separate; App Bundle | CI size gate |
| Battery | 30-min chat < 8% on mid-range; thermal note | Battery Historian |
| Storage | model + index visible, deletable, no orphans | storage manager |
| Accessibility | TalkBack labels, 48 dp targets, contrast AA, font scaling | a11y audit |
| RTL | full Arabic layout, bidi correctness | screenshot tests |
| Security | no exported components without permission; local API auth; no secrets in repo | manifest lint + review |
| Privacy | zero network calls in airplane-mode flows | network audit test |
| Licence | third-party page complete; SBOM generated | CI check |

---

## 7. Metrics (local-first, no telemetry)

We cannot use analytics SDKs. Therefore metrics come from: (a) opt-in local diagnostics export, (b)
opt-in benchmark upload, (c) Play Console aggregate stats (installs, ratings, crashes if Play
vitals), (d) closed-beta surveys, (e) GitHub issues/stars.

| Metric | Source | Target at launch |
|---|---|---|
| Installs | Play/GitHub | 1k month 1; 10k month 6 |
| Rating | Play | at least 4.0 |
| D7 retention (cohort estimate) | closed beta survey + optional in-app local counter shown to user | at least 25% |
| Document RAG usage | opt-in diagnostics | at least 20% of active users |
| Dictation usage | opt-in diagnostics | at least 15% |
| Crash-free | Play vitals / local | at least 99.5% |
| Arabic eval score | CI + published report | no regression > 2% |

---

## 8. Out of scope for v1.0 (explicit)

- iOS, desktop, web.
- Persona marketplace/PalsHub-like UGC; image generation; MCP client; local API server; default
  assistant role (v1.1); multi-image; OCR for scanned PDFs (v1.1); model fine-tuning; cloud sync;
  accounts; subscriptions; ads; analytics; voice cloning; Quran/tafsir content; prayer times;
  game/manga roleplay features; LoRA import; speculative decoding; parallel decoding; vision video.

## 9. Open product questions

| # | Question | Owner | Resolve by |
|---|---|---|---|
| P1 | Default model: Qwen3.5-2B vs LFM2.5-1.2B vs Gemma 4 E2B (licence/size/Arabic quality) | Expert | Step Zero |
| P2 | Arabic TTS: Piper ar_JO quality vs Supertonic vs system fallback | Expert | H3 |
| P3 | OCR approach for Arabic scanned PDFs (ML Kit vs Tesseract vs PaddleOCR) | Engineer | v1.1 planning |
| P4 | EmbeddingGemma 2 270M vs 440M for Arabic RAG | Engineer | RAG spike |
| P5 | Whether to show tok/s by default (may confuse users) | Naive User feedback | closed beta |
| P6 | Name/trademark final choice | Policy Guardian | before code freeze |
| P7 | Pro pricing and what is behind it | Accountant | before launch |
---

## 10. Amendment — 19 Nov 2026: customization, model control, agent scope, inputs

**Source:** [doc 17 — Customization, model control and agent scope: Qiyas Council Mode C](17-customization-council.md).
Existing IDs are unchanged; the IDs below are **added** to §3 and the out-of-scope list in §8 is amended
only where stated.

### 10.1 Generation and model control (§3.9 supersession for SET-1/SET-2)

| ID | Requirement | Priority | Milestone |
|---|---|---|---|
| SET-11 | Per-model generation settings: temperature, top_p, top_k, max tokens (Simple) + repeat penalty/last-n, min_p, typical_p, presence/frequency penalty, seed, stop sequences, sampling mode (Advanced); layered over GGUF `general.sampling.*» and family presets; reset; validation; effective values visible and logged | P0 | M5 |
| SET-12 | Model facts surfaced: architecture, n_ctx_train, parameter count, template source, sampling metadata, fingerprint; "model default" vs "preset default" never conflated | P0 | M5 |
| SET-13 | Sampler ranges/step/defaults and Simple/Advanced/Experimental layering (mirostat/XTC/DRY/top_n_sigma hidden in v1.0) | P0 | M5 |
| SET-14 | System prompt: global editor with reset-to-app-default; per-model override; app default stays Arabic-first | P0 | M5 |
| SET-15 | `ModelSettings» persistence keyed by model fingerprint (rename/reimport safe), schemaVersion + migration, corrupt-file recovery | P0 | M5 |
| SET-16 | Prompt presets (personas-lite): built-in local assets (general, Arabic writing tutor, translator, summarizer, code explainer, study quiz), duplicate/edit/delete locally, applied per chat; no marketplace, no downloads | P1 | M5 |
| SET-1 (as amended) | "Generation defaults per model" is superseded by SET-11..13; the ID stays reserved | — | — |
| SET-2 (as amended) | "Theme (system/light/dark), font size, language" becomes: mode control (existing) + theme packs THM-1..5 (M4) + font scale THM-6 (M6) | P0 | M4/M6 |
| SET-7 (as amended) | Personas stay local prompt presets (no marketplace); the v1.0 subset is SET-16, full personas stay v1.1 | P1 | M5/v1.1 |

### 10.2 Themes (§3.9 addition, new THM block)

| ID | Requirement | Priority | Milestone |
|---|---|---|---|
| THM-1 | Curated theme packs (≥4 + default): Girih & Ink (default, unchanged), Lapis Night, Turquoise Dawn, Madder Dusk, Paper/High-Contrast; each with light+dark palette, AR/EN name and one-line description | P0 | M4 |
| THM-2 | Appearance picker with visual preview and instant apply; no colour editor; default pack from config | P0 | M4 |
| THM-3 | Contrast-tested palettes (AA: body 4.5:1, UI/large 3:1 on every pair the UI uses) and screenshot matrix per pack × mode × AR/EN | P0 | M4 |
| THM-4 | Unknown/removed pack id → safe default + logged warning, never a crash; selection persists across restart | P0 | M4 |
| THM-5 | High-contrast "Paper" pack for sunlight/low-vision/monochrome preference | P0 | M4 |
| THM-6 | In-app font scale (85/100/115/130%) with layout audit; no clipped Arabic at 130% | P1 | M6 |

Design invariants: packs are palettes only — geometry, Amiri typography, spacing, motion and the
lapis-as-action rule are fixed, and RTL/dark evidence is part of each pack's acceptance.

### 10.3 Chat surface additions (§3.2)

| ID | Requirement | Priority | Milestone |
|---|---|---|---|
| CHT-9 | Chat-template source display (model template / none-fallback) with hash preview; formatted-prompt preview through the same formatter used for generation | P0 | M5 |
| CHT-10 | Forced built-in template override behind Advanced with warning and reset-to-model-default; unsupported name shows an error, never a silent fallback | P1 | M5 |
| CHT-11 | Custom Jinja template override, verified on save via `common_chat_verify_template», only after the T4 build test passes | P2 | v1.1 (conditional) |
| CHT-12 | Per-conversation system prompt/template-adjacent overrides (prompt only; template stays per-model), layered above global/per-model | P1 | M6 |
| CHT-5 (clarified) | "Per-conversation overrides" now explicitly includes the system prompt and generation params; requires conversation identity from CHT-4 | P0 | M6 |

### 10.4 Inputs and attachments (§3.4/§3.5 addition — one attachment seam)

| ID | Requirement | Priority | Milestone |
|---|---|---|---|
| INP-1 | `Attachment» model in `core:runtime» (document/image/audio-transcript/shared text): play name, MIME, size, local copy, optional extracted text, metadata; delete cascades with the conversation; no orphan files | P0 | M7 |
| INP-2 | Composer attach sheet gated by the selected model's capability, with honest "why disabled" copy and a route to choose a capable model; no ghost buttons | P0 | M7 |
| INP-3 | Document import → extraction → RAG (RAG-1..8); citations tap-through; document scope per conversation | P0 | M8 |
| INP-4 | Image input via a supported VL model + mmproj (libmtmd); capability gate; unsupported model → disabled with reason | P0 | M9 |
| INP-5 | Audio recording → offline dictation into the composer (STT-1..3); audio is never sent to the LLM | P0 | M10 |
| INP-6 | Share-intent text/image/file → attachment (v1.1); size/MIME validation; EXIF strip | P1 | v1.1 |

Audio-as-model-input, video input, OCR of scanned PDFs (v1.1) and any cloud upload remain out of scope.

### 10.5 Agent, assistant and skills (§3 new AGT block; SET-9 scope clarified)

| ID | Requirement | Priority | Milestone |
|---|---|---|---|
| AGT-1 | Skill bundles: named prompt + parameter preset (+ optional tool allowlist and doc scope); selectable in chat; visible active skill. v1.0 subset = prompt+params (SET-16) | P0/P1 | M5 (lite) / v1.1 |
| AGT-2 | Local deterministic tools only: clock/date, calculator, unit conversion, document search (conversation scope), conversation search; no network, no accessibility, no shell | P1 | v1.1 (T5) |
| AGT-3 | Grammar-constrained tool JSON, visible tool cards (requested → running → result/error), ≤3 calls/turn, 5 s/tool, honest failure ("couldn't use the tool"), tool output labelled as data | P1 | v1.1 (T5) |
| AGT-4 | Per-tool-category permission panel with revoke; document search asks once per conversation scope; nothing leaves the device | P1 | v1.1 |
| AGT-5 | Default-assistant role (`ROLE_ASSISTANT» via `RoleManager»), QS tile, share target, `ACTION_ASSIST» entry, session overlay via `VoiceInteractionService»; push-to-talk only | P1 | v1.1 (T6) |
| AGT-6 | Assistant access contract: only the explicit invocation's `AssistContent»/selected text/voice/attachments; no AccessibilityService/screen reading, no background observation, no persistence unless the user sends/saves, clipboard only on explicit paste | P1 | v1.1 |
| AGT-7 | MCP client (conditional): user-added servers only, per-tool permission, no auto-connect, audit log | P2 | v1.2 |

**Never:** free-form autonomous agents, accessibility screen control, background actions, always-listening
wake word, network tools in v1.1, autonomous app actions.

### 10.6 Amendments to §8 "Out of scope for v1.0"

- **Moved into v1.0:** curated theme packs (THM-1..5, M4); prompt presets (SET-16, M5); per-model
  settings/template display (SET-11..15, CHT-9/10, M5); conversations/search/export/markdown are already
  P0 and remain so (M6). SET-9's default-assistant role stays v1.1.
- **Moved into v1.1:** in-app skill bundles with local tools (AGT-1..4, gated by T5); assistant role
  (AGT-5/6, T6). Audio-as-model-input, video, screen control, free-form agents, MCP remain out of scope
  for v1.0 (MCP conditional at v1.2).
- **No change:** no accounts, no cloud, no telemetry, no image generation, no LoRA/fine-tuning, no persona
  marketplace, no subscription.

### 10.7 New open questions (§9 additions)

| # | Question | Owner | Resolve by |
|---|---|---|---|
| P8 | Theme pack final set and names (5 proposed: Girih & Ink, Lapis Night, Turquoise Dawn, Madder Dusk, Paper/High-Contrast) | Curator + founder | M4 kickoff |
| P9 | Whether "skill" to the founder means prompt preset (our v1.0 reading) or a file-type input — the answer renames the UI | Founder | before M5 UI |
| P10 | T5 outcome decides whether tool-calling skills ship in v1.1 or tools become explicit buttons | Engineer | v1.1 planning |
| P11 | Model download hosting/network policy for M11 (Hugging Face direct vs GitHub releases vs mirror; downloads-only network) | Founder + Engineer | before M11 |
| P12 | Test phone purchase/borrow timing (blocks phone H1–H3, thermal, assistant-role device verification, dictation WER) | Founder | now |

