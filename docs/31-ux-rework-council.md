# 31 — UX review round 1: honest surfaces (model chip, library, settings, downloads, Hugging Face)

**Date:** 24 Nov 2026 · **Council:** Qiyas Mode C (merged) · **Status:** decisions proposed, awaiting founder sign-off
**Scope:** the founder's first hands-on product review of `1.0.0-rc2` on a Redmi Note 7, and the rework it implies.
**Precedent record:** [17-customization-council.md](17-customization-council.md), [28-public-launch-council.md](28-public-launch-council.md), [29-small-models-council.md](29-small-models-council.md).

---

## 0. Frame and ground

### 0.1 What this council must decide

The founder used the shipped app on the phone and filed five complaints plus one working principle. Verbatim, condensed:

1. **Top-bar chip** — "It only shows the model name or *no model loaded*. When I press on it, I need it to tell me there is no model loaded and take me to a page to get one."
2. **Library is misleading** — it "shows the properties of several models as if they are real, but nothing is downloaded or usable or analyzable". Wanted: a multi-section Library — **Models** (downloaded + downloadable, Hugging Face and other future sources) and **Media** (PDF, text, MD, image, audio the user gave the app), with user control over what the agent may access, upload before or during chat, a keep-vs-one-time choice on upload, and a per-content **policy** (save to the media section / never save / ask each time). Tapping a downloaded model must show its full information and let the user configure it (chat template, temperature, any supported setting).
3. **Settings is one very long scroll** — organise it: sections, panels, collapsible groups; long/complex surfaces become their own page, one-action surfaces become a slide/expand panel. "Show me your creative."
4. **Hugging Face import only takes a repo path** — needs in-app search/browse, must show what is usable/compatible (RAM, disk), and **block downloads that exceed free disk or could not run in this memory**. "The app should be smart enough to warn the user and block him for processes that may not be compatible or could not run on his machine."
5. **No download manager** — several downloads at once cannot be tracked, the user sees nothing and assumes failure. Wanted: a visual download section/manager.
6. **Working principle** — assume the user does not know what a model or a format is; add hints and descriptions, automate everything automatable, make the default take the least user work, never remove full access/control, keep advanced options per item. Tell the founder (and the council) anything they forgot.

### 0.2 Hard constraints (given, not debated)

- **Privacy by design stays.** No telemetry. The only network use is user-started model acquisition; offline-only must be enforced *before* the socket opens; every request stays in the on-device network audit. HF *search* is a new network flow and must be audited and offline-gated like a download.
- **Arabic-first, RTL, terminology law.** strings live in `values/` (Arabic, default) and `values-en/`; vocabulary per [30-arabic-terminology.md](30-arabic-terminology.md): *model = نموذج, prompt = تعليمات, projector = ملحق الرؤية*. Contrast gate and five theme packs are non-negotiable.
- **Compose, don't import sideways.** Feature modules never import each other; UI is contributed through typed slots declared in `Slots.kt`. A screen that needs another feature's content needs a new slot, not an import.
- **Test first, no hardcoded values.** Policy/parsers/reducers live in pure-JVM modules with tests; catalogues and switches are assets/config; no user-visible string in Kotlin.
- **No fake data, ever.** A catalogue is a catalogue, not "your models". This is the founder's real complaint and the council treats it as a defect class, not a polish item.
- **Feature-complete `rc2` is over.** This round is a scope decision: the shipped build's scope claims in `PRODUCT.md`/README have to be updated with the work.

### 0.3 What exists today (verified in code, 24 Nov 2026)

| Surface | Reality |
|---|---|
| Chip | `Panels.kt:51` `EngineModelChip`: label `engine_no_model` or loaded name, status word, tap opens `ModelSheet` (`Panels.kt:81`) listing `content.models()` — the **3 hardcoded demo cards** in `assets/content/models.json` (incl. "Whisper Small · Planned"). No action, no navigation. |
| Library | `LibraryPlugin.kt:63` one scroll: the same 3 fake model cards (5 read-only rows each, not tappable) + `DocumentsLibrarySection` (the real RAG shelf: import/retry/delete). No library slot type exists. |
| Settings | `SettingsScreen.kt:33` one `verticalScroll` over `SlotList(SettingsSections)`: appearance(0), language(5), engine(10), models(12), embedding(20), privacy(25), about(30), voice(30 — **tie**, resolved only by plugin install order). |
| Model manager | Exists and is good: device card, recommendation, curated catalogue, licence consent, resumable download, storage manager — **buried inside Settings at order 12** (`ModelManagerSection.kt`, 725 lines). |
| Per-model config | Exists: 16 params (5 simple + advanced), sampling mode/seed/stop, chat-template picker + preview, per-model system prompt/presets — **inside Settings > Engine**, not attached to a model. Context size is **not editable at all** (global `app.json engine.contextTokens = 2048`). |
| HF import | An `AlertDialog` with 3 text fields (repo, file, token). Validation: non-blank + `.gguf`. No existence check, no size (entry built with `sizeBytes = 0`), no RAM check, no checksum, **and the progress row never matches the HF id, so the only feedback is the system notification**. |
| Downloads | `ModelDownloader` = **one** download, one `StateFlow`, `enqueue` silently drops a second request. No queue, no history, no persisted state, no partial-file marker. Progress renders only inside the Settings catalogue row. Foreground `DownloadService` + notification (percent, Cancel) exists. |
| Adoption | A finished download is imported by the **UI that happens to be composed** (`ModelManagerSection.kt:110` / `OnboardingScreen.kt:107`). If the user is on another tab or the process dies, the verified file sits in staging uninstalled. |
| Catalogue assets | Two: `catalog/models_catalogue.json` (5 real curated GGUF entries, sizes/sha/licence/minRam — used for real downloads) and `content/models.json` (3 fake display cards — used by chip + library). |
| Navigation | Bottom nav only. No back stack, no `BackHandler` anywhere; sub-surfaces are sheets/dialogs. There is no way for the chip to "take the user to a page". |
| Tests | No Compose/UI tests exist, so a redesign breaks no UI test. Contract tests do pin: slot order (`AppContextTest`), manifest order (`AppCompositionTest`), downloader phases/reasons (`ModelDownloaderTest`), catalogue values on device (`ModelManagerDeviceTest`), param catalogue (`ModelParameterCatalogTest`). |

### 0.4 Defects found while grounding (the founder did not list these; they are part of the rework)

| # | Defect | Where | Why it matters |
|---|---|---|---|
| G1 | Failed load renders **"لا يوجد نموذج · تعذّر تحميل النموذج"** — label contradicts status | `Panels.kt:56-66` | The one surface that must be honest lies about state |
| G2 | HF downloads show **no in-app progress** (entry id `hf-*` never matches a catalogue row) | `ModelManagerSection.kt:176,276` | Exactly the founder's "I assume it failed" |
| G3 | Second download request is **silently dropped** | `Downloads.kt:209-216` | Invisible failure, no busy feedback |
| G4 | Finished download is **adopted by the UI**, not the app | `ModelManagerSection.kt:110`, `OnboardingScreen.kt:107` | Download can complete and never be installed |
| G5 | No download state survives process death; a `.part` file is invisible | `Downloads.kt:259` | Resume is accidental, not offered |
| G6 | Paused state prints the pause **action** string as its **state** label; `models_download_queued` string is unused | `ModelManagerSection.kt:477` | Copy bug |
| G7 | Onboarding reports every failure as "network" | `OnboardingScreen.kt:304` | Wrong recovery advice |
| G8 | Settings order tie at 30 (about vs voice) | `AboutPlugin.kt:35`, `VoicePlugin.kt:72` | Render order depends on install order |
| G9 | Catalogue `descriptionKey` copy exists but is **never rendered** | `Catalogue.kt:47` | Free explanation the novice never sees |
| G10 | No back navigation primitives at all | `Shell.kt`, grep `BackHandler` = 0 | Blocks every "own page" decision below |
| G11 | Debug APK builds **x86_64 only** for llama.cpp; arm64 is release-only | `engine/llamacpp/build.gradle.kts` | "Test on the phone" needs a build change first |
| G12 | HF details API returns `gguf.architecture`, `gguf.context_length`, `gguf.chat_template` — unused | live probe | Template can be configured automatically |

### 0.5 Evidence rule

Every claim about the outside world below was probed live on 24 Nov 2026 (HTTP calls from this machine, sizes from the HF tree API, GGUF headers parsed from real range requests) and every claim about the app is a `file:line`. Anything not probed is labelled **UNVERIFIED** and becomes a test, not a fact. No invented numbers.

### 0.6 New primary evidence gathered for this council

**HF Hub API (live):**
- `GET /api/models?search=<q>&filter=gguf&sort=downloads&direction=-1&limit=20` → array with `id, author, downloads, likes, tags[]` (`license:apache-2.0` …), `pipeline_tag, gated, lastModified`. Anonymous works. `filter=gguf` is the de-facto "llama.cpp-compatible" marker. `library=gguf` does **not** filter.
- `GET /api/models/{repo}/tree/main?recursive=true` → per file: `path, size, lfs.oid` (**size is exact**; `lfs.oid` is the sha256). This gives size + checksum before download.
- `GET /api/models/{repo}` → `gated`, `cardData.license`, `siblings`, and a `gguf` object with `architecture`, `context_length`, `chat_template`, `total`.
- `GET /{repo}/resolve/main/{file}` → 302 to CDN, `X-Linked-Size`, `Accept-Ranges: bytes`; `Range: bytes=100-199` → **206 Partial Content** with correct `Content-Range` (resume is real). Gated repos answer `401 X-Error-Code: GatedRepo`.
- Rate limits: anonymous **500 API / 3 000 resolve calls per 5 minutes**, `429` + `RateLimit-Policy` headers. A search UI needs debounce + cache; a probe per keystroke is abuse.

**GGUF header probe (live, this machine) — Step Zero T1, PASSED 6/6.** A `Range: bytes=0-8388607` (8 MB) request parsed the **complete** metadata block of every probed repo, across six architectures/files by five publishers:

| File (Q4_K_M) | exact bytes | metadata ends | arch | layers | heads | kv heads | embd | ctx train | template |
|---|---|---|---|---|---|---|---|---|---|
| Qwen2.5-0.5B | 491 400 032 | 5.93 MB | qwen2 | 24 | 14 | 2 | 896 | 32 768 | 2 509 B |
| LFM2.5-350M | 229 312 224 | 2.38 MB | lfm2 | 16 | 16 | array | 1 024 | 128 000 | 2 553 B |
| Gemma-3-270m | 253 115 424 | 6.51 MB | gemma3 | 18 | 4 | 1 | 640 | 32 768 | 1 654 B |
| Qwen2.5-1.5B | 1 117 320 736 | 5.93 MB | qwen2 | 28 | 12 | 2 | 1 536 | 32 768 | 2 509 B |
| Llama-3.2-1B | 807 694 464 | **7.82 MB** | llama | 16 | 32 | 8 | 2 048 | 131 072 | 3 827 B |
| TinyLlama-1.1B | 668 788 096 | 1.70 MB | llama | 22 | 32 | 4 | 2 048 | 2 048 | 410 B |

A 1 MB prefix is **not** enough (tokenizer arrays dominate; worst case seen 7.82 MB). **Cap the probe at 12 MB** to keep headroom. The probe also yields the **chat template** (`tokenizer.chat_template`, present in all six), so template auto-configuration needs no HF API call and works for repos the API does not describe. `attention.head_count_kv` can be an array (per-layer, LFM2) — the parser must read values, not skip them. Therefore: the app can compute an **exact** KV-cache cost and a ready-to-use template before committing to a 0.2–2 GB download, at the price of one 2–12 MB range request.

**Memory model:** GGUF weights are mmapped (clean, file-backed; reclaimable), KV cache and compute buffers are anonymous (not reclaimable; LMK kills). `required ≈ file + KV(n_ctx) + compute + headroom`; `KV = 2 × n_layer × n_kv_heads × head_dim × n_ctx × 2 B`, `head_dim = embd / n_head` (validated against HF's published Llama-3.1 table). Android: gate on `availMem − threshold` (not `totalMem`), `isLowRamDevice`, `StatFs.getAvailableBytes()` (never `getFreeBytes()`).
**Small-model trap (measured):** effective bits/weight is much higher than nominal on sub-1B models because the embedding dominates — Qwen2.5-0.5B Q4_K_M is **7.96 bpw** (491 400 032 B / 0.494 B params), Gemma-3-270m Q4_K_M ≈ 7.2 bpw, Llama-3.2-1B Q4_K_M ≈ 4.97 bpw. **Never estimate size from params × bpw; always read the real `size`.**
**This phone (measured over adb):** Redmi Note 7, Android 10/API 29, arm64-v8a, **total RAM 3 826 256 kB, MemAvailable ≈ 1.35 GB**, /data 107 GB with **15 GB free**, 1080×2340 @440 dpi. With the probed numbers and the 300 MB overhead estimate, the fit engine must output: Qwen2.5-0.5B `469 + 25 + 300 ≈ 794 MB` → **يعمل** (usable ≈ 1.15 GB); Qwen2.5-1.5B `1 117 + 59 + 300 ≈ 1 476 MB` → **لن يعمل**; Llama-3.2-1B `808 + 67 + 300 ≈ 1 175 MB` → **يعمل بضيق** (needs ≥ 1 305 MB at the 0.9 rule). That is a real acceptance test on real measured capacity, not a hypothetical.
**Precedents:** PocketPal AI does in-app HF GGUF search + optional token for gated repos; ChatterUI offers copy-vs-use-in-place (storage decision); Jan splits hub/download/chat with per-model settings; LM Studio ships RAM-tiered advice; Ollama treats context as a memory cost. **None of them shows a computed "runs here / won't run" verdict** — that is the gap this rework fills.

---

## 1. Seated roles

**Core:** Expert (composite world-class archetype: on-device AI product architect for low-end, Arabic-first Android apps), Engineer, Devil's Advocate, Naive User, Maestro.

**Dynamic (3 — each owns a dimension that could kill the rework):**

```
Role: مجلس الجهاز / The Device Guardian
Mandate: memory, storage, thermals, load failures on a 3.8 GB phone
Signature question: "Where is the number that proves this will actually run here?"
Qiyas tool: Tahqiq al-Manat (does the cause exist on THIS device?)
Red line: never show a download as "recommended" without a measured-capacity verdict
```

```
Role: The Arabic UX Editor
Mandate: copy that a first-time Arabic reader understands without a computer degree; RTL
Signature question: "Would my mother know what this word means, and where it leads?"
Qiyas tool: Tanqih al-Manat (strip the technical noise the precedent carried)
Red line: no untranslated jargon on a primary surface; every term explained once, in place
```

```
Role: The Privacy Archivist
Mandate: user files, retention, access grants, and the network audit of new flows
Signature question: "Who can read this file later, and can the user point at where it went?"
Qiyas tool: Al-Athar al-Thani (second-order effect: what happens once the file is kept?)
Red line: a kept file is never readable by the agent without an explicit grant; a one-time file leaves no copy
```

---

## 2. Global invariants (the Expert, before any branch)

1. **A catalogue is not device state.** Data assets may only render as "available"; every "yours / installed / ready" claim must come from a device store.
2. **The front door never dead-ends.** Any chip/empty state that says "nothing" must carry the action that fixes it.
3. **The user's long operations are the app's responsibility.** Anything the user started is visible, resumable, persisted, and completed by the app — never by whichever screen happened to be composed.
4. **Gate on measured capacity.** Total RAM is marketing; `availMem − threshold` and `getAvailableBytes()` are physics. A verdict without a number is a guess, and guesses get a label.
5. **Least work by default, full control on demand.** One recommended action + advanced options one level down, per item, always reachable.
6. **No dead or duplicate sources of truth.** One model registry, one catalogue, one download queue, one media store. Delete what lies.
7. **Terminology is architecture.** One vocabulary (doc 30), glossed on first use, consistent across chip, library, settings, notifications.

---

## 3. Branch audits

### 3.1 The chip (fold #1, G1, G10)

Today it is a status pill that opens a dead-end sheet. Target: a **state-honest doorway**.

| State | Pill | Tap opens |
|---|---|---|
| No model | `لا يوجد نموذج · ابدأ` | **Model quick sheet**: plain-language why + `اختر نموذجًا` → Library > Models (recommended card focused); `استيراد ملف GGUF`; `ما هو النموذج؟` note |
| Loading | `<name> · يُحمَّل` | sheet: progress + Cancel |
| Ready | `<name> · جاهز` | **Model passport sheet**: name/size/quant gloss/fit verdict; `التفاصيل والإعدادات` → model detail page; `إلغاء التحميل`; `تبديل النموذج` |
| Failed | `<name> · تعذّر التحميل` (never "no model") | sheet: reason + `إعادة المحاولة` + `اختر نموذجًا أصغر` |

Requires: a router with back support (G10) and cross-destination navigation (`open("library", page="models")`).

### 3.2 Library (fold #2, G9, fake-data defect)

Today: one scroll of fake cards + a real document shelf. Target: **Library = what is on this device**. Three segments:

1. **النماذج** — engine card (current model: load/unload/status/ˈfit" for this device) → installed models (real `ModelStore`, tappable → model detail) → downloads summary (active/paused/failed counts → التنزيلات) → available: the curated catalogue with per-entry fit badge, `البحث في Hugging Face`, `استيراد GGUF`. `content/models.json` is removed from every display path; the "planned" Whisper/Piper cards are deleted (voice models belong to the Voice page).
2. **التنزيلات** — the queue: queued/active/paused/verifying/failed/done, per-item progress, speed, pause/resume/cancel/retry, storage math, "فتح التنزيلات" from notification, badge on the Library tab while active.
3. **الوسائط** — the user's files: documents (existing RAG formats) + images (V1); per-item status, access switch, delete; upload → keep-vs-once decision; policy row → `السياسة الافتراضية` (احفظ / لا تحفظ / اسألني). Audio is honestly deferred: the app has dictation but no file-transcription path, so the media page says so instead of listing a fake "planned" row.

Model detail page (tap a model): identity + licence + repo/file/sha + languages + description (finally rendering `descriptionKey`) + fit verdict with the arithmetic + actions (download/select/load/unload/delete) + **per-model settings** moved here from Settings: temperature & 4 simple params, advanced 11 more, sampling mode, seed, stop sequences, **chat template** (auto-suggested from HF `gguf.chat_template` or GGUF metadata + presets), per-model system prompt, **context size** (new; bounded by the model's trained context and the RAM verdict).

### 3.3 Settings (fold #3, G8)

Today: 8 inline groups in one scroll; the two heaviest (engine, models) are full applications. Target: **preferences live in Settings; content lives in Library.** Root = grouped rows with a live one-line summary; tap = page with back; one-action choices = inline accordion.

```
الإعدادات
  المظهر والقراءة        فاتح · خط كبير            → page (theme mode, pack, font scale)
  اللغة                 العربية                    ▾ in-place (one choice + note; recreates activity)
  الخصوصية والشبكة        وضع عدم الاتصال            → page (offline, network log, AI reports, policies)
  الصوت والإملاء         النموذج غير مثبّت           → page (whisper model, language, mode)
  متقدم                 —                          → page (engine provider, API/ABI, reset onboarding, orphan cleanup)
  عن التطبيق            1.0.0 · 12 رخصة            → page (about, licences, links)
```

Removed from Settings: the engine group, the whole model-manager group, embedding-model import (moves to Media as the "semantic search" prerequisite card). Kept as a shortcut only: nothing — the chip and Library cover model work. Advanced engine details stay reachable (Advanced page) so control is never lost.

### 3.4 Hugging Face acquisition (fold #4, G2, G11)

Today: two text fields. Target: **search → repo → file → verdict → queue**.

- Search: `filter=gguf&sort=downloads`, 400 ms debounce, page of 20, cached; each row shows name, params/quant (glossed), exact size, likes/downloads, licence, gated badge, and a fit badge (fits / tight / won't run / unknown).
- Repo page: file list with exact sizes and quant labels, recommended file first (Q4_K_M), licence and gated state, `ستحتاج توكن` for gated (memory-only token field, never stored).
- **Verdict card before download**: the arithmetic in plain Arabic — `0.47 GB على القرص + ~0.03 GB ذاكرة للسياق (2048) + ~0.30 GB للتشغيل ≈ 0.80 GB من 1.15 GB متاحة` — with `سيعمل على هذا الجهاز` / `يعمل بضيق` / `لن يعمل` and the reason. For HF files the app runs the **12 MB header probe** (Wi-Fi only, counted in the network audit, skippable when metered) to replace guesses with exact layer/head/context numbers; for curated entries the same numbers live in the catalogue asset (data, not code).
- Block rules: disk `< 1.1 × size` → **blocked**; estimated need `> 0.9 × (availMem − threshold)` → **warn + explicit override**; `isLowRamDevice` and file > 1 GB → **blocked**; unsupported `architecture` → **blocked** with the reason ("المحرك لا يدعم هذه البنية").
- No full website browsing (search + repo files only). No auto-download of gated repos. Token never persisted.

### 3.5 Downloads (fold #5, G3–G7)

Today: a singleton with no memory. Target: a **persisted queue** — `DownloadTask` list in JSON, one active at a time (V1), FIFO with reorder, pause/resume/cancel/retry per task, reasons mapped to copy, one notification summarising the queue, tap → التنزيلات. Adoption moves out of the UI into a host coordinator that imports a verified task into `ModelStore` whatever screen is visible; process death keeps the list and offers `متابعة التنزيلات` (never silently resumes a network transfer). Partial files are shown as "متوقف" with their size. Concurrency stays single-active in V1, but the data model is per-task so 2-at-a-time is a policy change, not a rewrite.

### 3.6 Media and access policy (fold #2, new domain)

Grounded in what exists: `RagService` (documents: pdf/txt/md/docx/epub, status/retry/delete), `FileAttachmentStore` (chat images/text/audio-transcript, per-conversation), `AttachmentGate` (capability blocks with reasons), `PrivacySection` (offline, audit). Missing: retention policy, listing of non-document media, per-item access grant, one-time items.

- Upload (from Media or from chat): SAF picker → if policy = ask → **decision sheet**: `أضفه إلى مكتبتي` (kept, indexed, listed) or `استخدمه الآن فقط` (processed for the current turn, stored only for that conversation, deleted when the conversation is deleted; never indexed).
- Policy setting: `السياسة الافتراضية للملفات`: احفظ دائمًا / اسألني (default) / لا تحفظ.
- Per-item: access switch `متاح للمساعد` (default on for kept documents the user uploaded, off is meaningful), type + size + source + status, retry/delete, `احذف بعد هذه المحادثة`.
- Global per-type switches (documents/images/audio) with `AttachmentGate` reasons shown in place ("الصور تحتاج ملحق رؤية — ثبّته من صفحة النموذج").
- One-time audio: the honest V1 answer is "not yet" — dictation input is live-mic only; the media page must not offer a fake path.

### 3.7 UX-first principles, applied (fold #6)

Every primary row gets a one-line Arabic gloss of what it means/does (doc 30 vocabulary); numbers are labelled `تقديري` unless measured; every empty state carries the next action; advanced options are collapsible per item, never removed; defaults are automated (recommended model, Q4_K_M file, context that fits) with the override one tap away; every long operation shows progress and survives leaving the screen; failure copy says what to do, not what broke.

---

## 4. Qiyas cards

**Q1 — Chip as a doorway (Survived)**
*Al-Asl:* PocketPal/Jan model pickers open the model hub, not a dead panel. *Al-'Illa:* a status surface must convert the user's intention ("use a model") into the acquisition path; the cause is *intent routing*, not prettiness. *Tanqih:* their hub is a separate destination; ours must stay inside the same 3-tab shell. *Condition:* a router with back exists.

**Q2 — Library as home of models + media (Survived, after attack)**
*Al-Asl:* Ollama's model list lives where content lives; Jan separates hub/chat; LM Studio's separate "My models" page. *Al-'Illa:* users look for *their stuff* in one place and *knobs* in another; the failure today is mixing catalogue (marketing) into "mine". *Tanqih:* none of them has documents+media in the same surface — our library must keep media separate from models. *Condition:* per-item data comes from stores; no asset rendered as state.

**Q3 — In-app HF search with computed fit verdict (Survived; browse downgraded)**
*Al-Asl:* PocketPal's HF search; Ollama's context-as-memory surfacing; LM Studio's RAM tiers. *Al-'Illa:* the user cannot evaluate a 4 GB download; the app must convert opaque sizes into a device verdict. *Tanqih:* full repo browsing, stars, filters and per-keystroke queries are website behaviour we cannot afford under 500 API calls / 5 min. *Condition:* debounce + cache + probe budget; HF is a source, not a destination.

**Q4 — The partial GGUF header probe (Survived; T1 green 6/6)**
*Al-Asl:* llama.cpp itself reads these fields from the file; HF exposes them per repo. *Al-'Illa:* the exact KV cost is only knowable from GGUF metadata; a size-only heuristic is wrong on small models by 60 %+ (measured 7.96 bpw vs 4.89 nominal on Qwen2.5-0.5B). *Tanqih:* we do not need the whole file, only the metadata block (measured 1.7–7.8 MB across six repos). *Condition:* 12 MB cap, Wi-Fi only, audited, skippable; partial parse tolerated (fill what arrived, band the rest).

**Q5 — Persisted single-active download queue (Survived; concurrency downgraded)**
*Al-Asl:* Android's DownloadManager queue; F-Droid's download list. *Al-'Illa:* visibility and resumability — not parallelism — are what stop "I assume it failed". *Tanqih:* multi-concurrent transfers contend for the same radio and flash on a 3.8 GB phone. *Condition:* task model is per-item from day one.

**Q6 — Settings as pages + accordions (Survived; search dropped)**
*Al-Asl:* Android Settings' grouped pages; iOS Settings' drill-down. *Al-'Illa:* frequency of use separates "set once" from "tune often"; one scroll punishes the second and hides the first at the bottom. *Tanqih:* iOS-style deep trees hide things too far; keep a one-line live summary on every row so the page is readable without entering it. *Condition:* back works; summaries are honest state.

**Q7 — Keep/one-time with a default policy (Survived, simplified)**
*Al-Asl:* camera apps' "save / don't save" and browser download retention; messaging apps' disappearing media. *Al-'Illa:* the user makes the retention decision at the moment they know the intent, and the app must not re-ask every time. *Tanqih:* a three-option sheet on every upload is friction; the policy setting carries the default. *Condition:* one-time files are deleted with the conversation and never indexed.

---

## 5. Council transcript (tight)

**Round 1 — is this redesign or debt?**

**Expert:** The complaints are one failure seen from five angles: the app presents *catalogue data* as *device state*, and has no door from "nothing" to "something". The invariant is #1 and #2; everything else follows. Second-order effect: once model work moves to Library, Settings shrinks enough that pages become possible with no information loss.

**Devil's Advocate (Fasad al-I'tibar):** The premise "users expect models in the library" is asserted, not proven. Settings is where every Android app puts such things; moving the manager could make it *less* discoverable. And turning Settings into pages adds taps to settings people use daily (offline mode).

**Naive User (Tahqiq al-Manat):** I opened the app; it said "لا يوجد نموذج". I pressed it and got cards about models I do not have. I do not know what "GGUF" or "Q4_K_M" is, and I have 15 GB free on a phone that stutters. I do not want a settings book — I want the app to hand me the smallest thing that works and show me a bar while it arrives.

**Engineer:** The library-as-home works only if Library can host plugin content; today it is a hardcoded host screen with no slot. We must declare a Library slot and a router, or move models into Settings pages and accept the mixed model. Also `ModelChip` lives in the host app, not a feature — host can own the routing.

**Arabic UX Editor:** "مكتبة" means library; users will accept "models" inside it the way a phone's gallery contains photos and videos. But the root must read as Arabic sentences, not field names: "النماذج المثبّتة" is fine; "Q4_K_M" alone is not — gloss it once ("مستوى ضغط متوسط — حجم أقل، جودة قريبة").

**Revision after round 1:** Library becomes home; Settings keeps a *shortcut* is rejected (two doors to one room confuses); instead the chip is the second door and it targets Library > Models. New slots + router become prerequisites, not optional.

**Round 2 — acquisition, compatibility, trust**

**Device Guardian (Naqd):** "Recommended" is currently computed against **total** RAM (3.8 GB). This phone has 1.35 GB available. Score the 1.5B entry as recommended on total RAM and you ship an OOM. The cause of the recommendation is broken at the data; fix the measurement before touching the UI. Also: every HF file is `sizeBytes = 0`, so "disk check" is a no-op and checksum never happens.

**Devil's Advocate (Al-Farq al-Mu'aththir):** In-app HF search inherits a licence and trust problem the app does not have today: arbitrary repos, gated weights, GGUF parser attack surface, and 500 API calls/5 min. The precedent (PocketPal) works because it is a power-user app; Sirrak's principle #6 is "least work for a novice". Searching a lawless catalogue is the opposite.

**Privacy Archivist:** And the probe is a network request. If it fires on every repo tap on mobile data, we have added an unlogged bulk download path to an app whose selling point is "only user-started model downloads". It must be Wi-Fi-gated, audited, and skip-able.

**Naive User:** "Gated" means what? If a model asks for a token I will close the app.

**Engineer (Tahqiq):** Then we do three things: (a) the curated catalogue stays the default front — five known-good, licence-clear, sha-pinned models; HF search is the "more" door, clearly labelled; (b) gated rows show "يتطلب حساب Hugging Face" and never look like a normal download; (c) the probe is an explicit step inside the file page with its own switch ("افحص الملف قبل التنزيل — حتى 12 م.ب") and is skipped on metered networks.

**Revision after round 2:** HF search survives but as a *secondary* door; full browse dropped; probe gated + audited; fit engine must add the numeric breakdown and block/warn thresholds; the catalogue asset gains the arch fields so curated entries need no probe.

**Round 3 — downloads, and what "keep" means**

**Devil's Advocate (Naqd):** Persisting the queue creates a download history on the device. That is new personal data in a privacy-first app. And "one active at a time" is exactly what the founder complained about ("I cannot track when there are several").

**Privacy Archivist:** A queue is not a history: keep only what the user started and has not finished; on completion, remove the task from the queue and let the installed-model list be the record. Deleting a task deletes its `.part`. That satisfies both.

**Naive User:** "Keep it or one-time use" — I do not understand "one-time use" for a PDF. Is it deleted? Can I still ask about it in this chat?

**Arabic UX Editor:** Then the sheet must say the consequence, not the mode: `احفظه في مكتبتي` (يبقى للمحادثات القادمة) vs `استخدمه في هذه المحادثة فقط` (يُحذف معها). Two options, consequences spelled out, default remembered.

**Engineer:** Adoption must move out of the UI. A host `DownloadCoordinator` subscribes to the queue, imports on verify, and the UI only renders state. Otherwise we keep G4 even with a pretty screen.

**Revision after round 3:** queue persists tasks-not-history; concurrency downgraded to single-active with per-task model; adoption fixed in the host; keep/once copy states consequences; one-time items are conversation-scoped and never indexed.

**Round 4 — closure**

**Devil's Advocate:** What falls? If nothing falls, this is theatre.
**Maestro (silence broken only on stop):** It fell already: full HF browsing (dropped), concurrent downloads (downgraded to queue), settings search (dropped), a Settings shortcut to models (dropped), audio media in V1 (suspended), three-option retention sheet (downgraded to two options + policy). The probe passed its Step Zero (6/6) between rounds and is in. Scope that remains is the founder's six asks plus G1–G12.

---

## 6. Maestro's verdict

### 6.1 Verdict table

| # | Branch | Verdict | Asl | 'Illa (cause) | Constraint imposed |
|---|---|---|---|---|---|
| D1 | Chip becomes an intent doorway (quick sheet → Library>Models; passport when loaded; honest failed state) | **Survived** | PocketPal/Jan model pickers | status surfaces must route intent | needs router+back; fix G1 |
| D2 | Library = device content, 3 segments (Models, Downloads, Media); fake cards deleted | **Survived** | Ollama model list, LM Studio "my models" | "mine" vs "available" must be separate sources | new `LibrarySections` slot; stores only |
| D3 | Model management leaves Settings; Library is the single home | **Survived** | Jan hub/chat split | frequency & purpose separation | chip is 2nd door; Advanced keeps engine details |
| D4 | Model detail page carries identity + fit + per-model config, incl. new context size | **Survived** | Jan per-model settings | config belongs to the object it configures | move `ModelControlSections`; bounded ctx |
| D5 | HF: in-app search + repo files as secondary door; no full browse | **Downgraded** (browse dropped) | PocketPal HF search | novice default = curated; HF = "more" | debounce/cache; gated labelled; token memory-only |
| D6 | Pre-download partial GGUF probe (12 MB cap) for exact KV/template | **Survived** (T1 6/6) | llama.cpp metadata; GGUF carries the template | exact numbers beat guesses | Wi-Fi only, audited, skippable; tolerant partial parse; fallback band |
| D7 | Fit engine: availMem−threshold, storage, lowRam, arch, disk block, RAM warn+override | **Survived** | LM Studio RAM tiers, Ollama ctx cost | gate on measured capacity | new pure-JVM `ModelFit`; catalogue arch data |
| D8 | Persisted download queue, single active, per-task states, notification → list | **Survived** (concurrency downgraded) | Android DownloadManager | visibility/resumability, not parallelism | JSON persistence; tasks-not-history |
| D9 | Adoption moved from UI to host coordinator | **Survived** | Worker/queue completion handlers | the app owns what the user started | host service; integration test |
| D10 | Media library: documents+images V1, keep/once decision, policy, per-item access | **Survived** (audio suspended) | camera save/don't-save, disappearing media | decide retention when intent is known | policy store; conversation-scoped once-items |
| D11 | Settings: grouped rows → pages + inline accordion for one-choice groups | **Survived** (search dropped) | Android/iOS settings | set-once vs tune-often | live summaries; fix G8; router pages |
| D12 | Remove every fake/planned row from display; `content/models.json` retired | **Survived** | invariant #1 | lying is the defect | update tests/assets |
| D13 | Context size editable per model, bounded by trained ctx and fit | **Added** | Ollama ctx-as-cost | RAM cost is a user-visible knob | clamp + verdict recompute |

### 6.2 Step Zero tests (falsify cheaply before building)

| Test | Cost | Pass | Fail → decision |
|---|---|---|---|
| **T1 — probe reality**: range parse on 6 diverse GGUF repos (qwen2.5-0.5b, lfm2.5-350m, gemma-3-270m, llama-3.2-1b, tinyllama-1.1b, qwen2.5-1.5b) | **done** | **6/6 parsed**, metadata ≤ 7.82 MB (12 MB cap), templates present | ✅ decision: build the probe |
| **T2 — phone load**: arm64 build + Qwen2.5-0.5B Q4_K_M, ctx 2048 on the Redmi Note 7 | 2 h | loads, answers one prompt, no LMK kill | If OOM: ctx 1024 default for `isLowRam`, re-run; if still OOM: block 0.5B on lowRam (honest, harsh) |
| **T3 — verdict accuracy**: fit engine predicts "won't run" for Qwen2.5-1.5B on this phone; predicted need vs real `availMem` at load | 1 h | prediction matches the load attempt | widen margin; gate is advisory only |
| **T4 — HF budget**: 20 searches + 20 tree calls in 5 min from the app | 30 min | no 429 with debounce+cache | lower page size, add disk cache |
| **T5 — adoption**: finish a download while on Chat, force-stop during verify, reopen | 1 h | model installed / task shows paused, resume offered | fix coordinator before UI work |

### 6.3 The target experience in one page

```
CHAT  ─ chip ──▶ [no model] sheet ──▶ Library > النماذج ──▶ recommendation ──▶ download
        │                     └──▶ استيراد GGUF
        └── [ready] passport ──▶ model detail ──▶ إعدادات النموذج (قالب/حرارة/سياق)

LIBRARY
  النماذج   |  التنزيلات  |  الوسائط
   ├ المحرك الحالي (تحميل/إلغاء)        ├ قائمة الانتظار + شريط لكل مهمة   ├ رفع ملف → احفظ / هذه المحادثة فقط
   ├ المثبّتة → صفحة النموذج             ├ إيقاف/متابعة/إلغاء/إعادة          ├ وصول المساعد (مفتاح لكل ملف)
   ├ المتاحة (منسّقة) + بحث HF + استيراد ├ حذف + رياضيات التخزين            ├ السياسة الافتراضية
   └ بطاقة التوصية مع سببها              └ تنبيه النظام → هنا               └ نموذج التضمين (سياقي)

SETTINGS
  المظهر والقراءة ▸ · اللغة ▾ · الخصوصية والشبكة ▸ · الصوت ▸ · متقدم ▸ · عن التطبيق ▸
```

**Visual reference:** [mockups/31-ux-rework.html](mockups/31-ux-rework.html) (rendered: `mockups/31-ux-rework.png`) draws the eight key screens — chip empty/passport, library models/downloads/media, settings root, Hugging Face verdict card, and the dark model page — in the real Girih Ink palette.

**Model acquisition in ≤ 3 taps from a cold start**: chip → sheet → "اختر نموذجًا" → download row (the recommendation is already focused). **Any download visible in ≤ 1 tap** from anywhere: Library tab badge → التنزيلات.

### 6.4 Phased build plan

Each phase is a separate reviewable change; tests first; every phase ends with the JVM suite green and no new lint errors.

| Phase | Content | Key files | Acceptance |
|---|---|---|---|
| **P0** (this doc) | Council record, decisions requested, repo docs index updated | `docs/31`, `docs/README.md` | reviewable; no code |
| **P1 — foundations (pure JVM)** | `AppRouter` (destination+page+back, StateFlow, no UI); `DeviceCapability` (availMem−threshold, lowRam, StatFs, ABI); `ModelFit` (verdict, KV math, breakdown, block rules); `GgufMetadata` parser (partial+full, arrays); `HfClient` (search/tree/detail/resolve,`429` backoff, offline gate, audit hook); `DownloadQueue` (persisted JSON, single active, adoption callback); `MediaPolicy` (default policy, per-item retention+grant); catalogue asset arch fields; supported-arch asset | `core/runtime/nav`, `core/models/*`, `core/media/*`, `assets/catalog` | unit tests per policy; no UI yet; existing suite untouched |
| **P2 — Library + chip** | `LibrarySections` slot; Library screen with 3 segments; models from `ModelStore`; engine card; model detail page (move `ModelControlSections`); downloads segment (renders queue); chip quick/passport sheets + routing; delete fake asset usage; empty states; strings AR+EN | `app/LibraryPlugin.kt`, `feature/models/*`, `feature/settings/*`, `Panels.kt`, `Slots.kt` | from no-model cold start: chip → model downloaded → loaded → first reply, no dead end; fake rows gone |
| **P3 — HF acquisition** | search page, repo/file page, verdict card, gated/token flow, probe (T1 green first), queue integration | `core/models/hf`, `feature/models/*`, `app/HfClient` | T4 green; blocked oversized/unrunnable file shows reason; probe Wi-Fi/audited |
| **P4 — Settings rework** | 6 rows → pages, accordion language, remove engine/models/embedding groups, Advanced page, About/licences page, fix G8; router pages wired | `feature/settings/*`, `app/*`, plugins' slot registrations | every former section reachable ≤ 2 taps; summaries honest |
| **P5 — Media** | media segment, upload decision sheet, policy setting, per-item access switch, images, one-time deletion, documents moved in, embedding card | `core/media`, `feature/chat/DocumentPicker`, `app/DocumentsLibrarySection.kt`, privacy strings | kept vs once behaviour verified by test; access grant enforced in RAG/attachment paths |
| **P6 — verification** | full JVM suite, lint, store/site gates, emulator visual pass, phone smoke (T2/T3), docs update (04/06/README claims), version bump | repo | all gates green + phone evidence recorded in docs/26-style appendix |

**Build-flag prerequisite:** `-PsirrakArm64Debug=true` (or an equivalent variant task) so the debug APK ships arm64 `libsirrak_llm.so` for the phone; default stays x86_64 for CI speed (G11). This is P1 build config, not a product change.

### 6.5 Continue / kill metrics

- **Model acquisition**: ≤ 3 taps from cold start to a started download; kill the layout if the tester hesitates > 15 s on any screen.
- **Download awareness**: in a 10-minute session with 2 downloads, the tester can state each download's state without leaving the screen they are on (badge/notification).
- **Verdict honesty**: 0 downloads started that the fit engine marked "لن يعمل" without an explicit override; 0 "recommended" entries that fail to load on the test phone.
- **Fake data**: grep for catalogue assets in display composables = 0.
- **Settings**: every setting reachable in ≤ 2 taps; no row without a live summary.
- **Regressions**: 567 existing JVM tests green; lint 0 errors; store 79/79, site 225/225; new policy cores ≥ 90 % branch coverage by tests.

### 6.6 Risks and mitigations

| Risk | Mitigation |
|---|---|
| HF rate limits / 429 during search | 400 ms debounce, 20/page, on-disk cache, backoff on `RateLimit` header, no probe on metered |
| Malicious/broken GGUF (parser attack surface) | curated default; sha from tree API verified; probe parses metadata only with bounds checks; engine load errors humanised; offline switch still blocks everything |
| Gated-model licence misuse | gated rows labelled, token memory-only, consent dialog unchanged |
| Probe wrong/large on some repos | T1 green 6/6; 12 MB cap; tolerant partial parse; fallback to labelled band |
| Fit engine too conservative → nothing "fits" | warn+override path; catalogue `minRamMb` values stay as product data, not physics |
| Router refactor destabilises shell | pure-JVM router + contract tests; shell renders one page at a time; no existing destination rewritten in P1 |
| Media access grants leak into chat | grants checked in the one retrieval path (`RagService`/`AttachmentGate`), not in UI |
| Scope explosion vs `rc2` claims | P0 sign-off on scope; docs 04/06/README updated in P6; version bump decision D9 |
| Phone testing blocked by x86_64 debug | build flag in P1; device test uses Qwen2.5-0.5B only (low compute, as the founder asked) |

### 6.7 First action

**Tomorrow, before any UI code:** P1's pure-JVM `ModelFit` + `DeviceCapability` with tests driven by *this phone's measured numbers* (3 826 256 kB total, 1.35 GB available, 15 GB disk, arm64) and by the two GGUF headers already parsed. If the engine cannot call Qwen2.5-0.5B "يعمل بضيق" and Qwen2.5-1.5B "لن يعمل" on those inputs, nothing above it is trustworthy.

### 6.8 Decisions requested from the founder

1. **D1 Library shape**: 3 segments (النماذج / التنزيلات / الوسائط) as proposed, or 2 segments + downloads page, or 4th bottom-nav tab? (recommend 3 segments)
2. **D2 Settings creative**: the 6-row drill-down above vs keeping engine/model groups in Settings as pages? (recommend the split — content in Library)
3. **D3 Concurrency**: single active download + queue in V1? (recommend yes; per-task model keeps 2-at-a-time cheap later)
4. **D4 HF probe**: auto on Wi-Fi with a visible "فحص الملف" step, skipped on metered? (recommend yes, and it is the feature that makes the app "smart" as asked)
5. **D5 Media V1**: documents + images now, audio honestly "غير مدعوم بعد" until file transcription exists? (recommend yes)
6. **D6 Retention sheet**: two consequence-labelled options + policy setting, not three modes? (recommend yes)
7. **D7 gated/token UX**: memory-only token field + "يتطلب حساب Hugging Face" rows, or hide gated models entirely? (recommend show+label; hiding loses Llama-3.2)
8. **D8 Phone test**: run the smoke test on the Redmi Note 7 with Qwen2.5-0.5B (0.49 GB) only, via a new arm64 debug flag? (recommend yes; low compute as requested)
9. **D9 Version/scope**: bump to `1.1.0` and update `PRODUCT.md`/README claims with this round? (recommend yes)
10. **D10 Order**: run P1→P2→P3→P4→P5, or ship P2+P4 first (visible wins) and P3/P5 after? (recommend the disciplined order; P1 is one focused day and unblocks everything)

---

### 6.9 Founder decisions (24 Nov 2026) — approved, with two refinements

| # | Decision | Answer |
|---|---|---|
| 1 | Library shape | **3 segments** (النماذج / التنزيلات / الوسائط) |
| 2 | Settings creative | **6 doors with live summaries** |
| 3 | HF probe | **Auto on Wi-Fi; on metered, ask the user whether to probe** (never silently skip, never auto-run on mobile data) |
| 4 | Media V1 | **Documents + images + audio** — audio means importing an audio file and transcribing it on-device with the existing Whisper engine (no cloud), then the transcript is the media item; no "audio understanding" beyond the transcript |
| 5 | Order | **Disciplined P1 → P6** |
| 6 | Version/scope | **Bump to 1.1.0**, update `PRODUCT.md`/README claims |

Consequences recorded now so the build cannot drift: every phase ticket must respect these four narrowings — one active transfer, search+repo only on HF (no browse), two consequence-labelled retention choices, and a probe prompt on metered links.

## 7. Lessons (durable rules this round adds to the project)

1. **A data asset is not device state.** `content/*` describes what *could be*; only stores describe what *is*.
2. **The front door never dead-ends.** Every "nothing here" surface carries the action that fixes it.
3. **The app, not a screen, owns what the user started.** Downloads, indexing and imports complete independently of what is composed.
4. **Gate on measured capacity, label every estimate.** `availMem − threshold`, `getAvailableBytes()`, real file sizes; "تقديري" on anything not measured.
5. **No long operation without visible, resumable state**; no silent drop, no pause-word as a state label.
6. **One copy per fact**: one registry, one catalogue, one queue, one media store; delete duplicate or decorative sources.
7. **Jargon gets glossed once, in place**, using doc 30 vocabulary; advanced never removed, only demoted.
8. **New network flow ⇒ audit entry + offline gate + Wi-Fi policy** — the privacy promise is a checklist item on every feature, not a legacy property.
9. **Test the policy core in JVM before the screen exists**; screens are tested for intent, not layout.
10. **A recommendation without arithmetic is a guess**; show the numbers that justify the verdict.

---

## 8. Sources

- Code citations are `file:line` in this repository at `387419f` (branch `main`), listed inline in §0.3–0.4.
- HF Hub API behaviour and rate limits: live probes 24 Nov 2026 against `huggingface.co/api/...`; endpoint docs index at <https://huggingface.co/.well-known/openapi.md>; rate limits <https://huggingface.co/docs/hub/rate-limits>; GGUF+llama.cpp usage <https://huggingface.co/docs/hub/gguf-llamacpp>.
- GGUF metadata probe: range requests against six repos (Qwen2.5-0.5B/Qwen2.5-1.5B, LFM2.5-350M, Gemma-3-270m, Llama-3.2-1B, TinyLlama-1.1B), parsed with a bounds-checked reader; metadata ended at 1.70–7.82 MB, chat templates 410–3 827 B; decision table in §6.2 T1.
- Memory model: llama.cpp `llama.h` load modes and `src/llama-model-loader.cpp` (mmap default) <https://github.com/ggml-org/llama.cpp>; llama.cpp quantize README bits/weight table <https://github.com/ggml-org/llama.cpp/blob/master/tools/quantize/README.md>; HF Llama-3.1 inference-memory table <https://huggingface.co/blog/llama31#inference-memory-requirements>.
- Android APIs: <https://developer.android.com/reference/android/app/ActivityManager.MemoryInfo>, <https://developer.android.com/reference/android/os/StatFs>, <https://developer.android.com/reference/android/os/Build#SUPPORTED_ABIS>, <https://developer.android.com/topic/performance/memory-overview>, <https://developer.android.com/training/data-storage/app-specific>.
- Precedents: PocketPal AI <https://github.com/a-ghorbani/pocketpal-ai>, ChatterUI <https://github.com/Vali-98/ChatterUI>, Jan <https://github.com/menloresearch/jan>, LM Studio system requirements <https://lmstudio.ai/docs/app/system-requirements>, Ollama FAQ <https://docs.ollama.com/faq>.
- Device measurements: `adb` against Redmi Note 7 (`ef008a2`), 24 Nov 2026 (`/proc/meminfo`, `df`, `wm size/density`).
