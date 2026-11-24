# 06 — Roadmap, budget and launch plan

**Product:** Nawa (نواة) · **Start:** 18 November 2026 (Step Zero Day 1) · **v1.0 target:** week 12–13 (mid-February 2027)
· **Owner:** solo builder · **Status:** execution plan; public name **Sirrak** approved 18 Nov 2026 — formal clearance + domain pending (doc 08 rev 2, ADR-13); emulator expansion first (doc 07). Step Zero Day 1-2 hello-token milestone reached on the emulator 18 Nov 2026 (doc 10); H1/H2 real numbers and human voice WER wait for the phone, while H2 API, H3a retrieval, the H3b voice round-trip and the H3c integrated voice→retrieval→LLM→TTS loop are already proved on the emulator (doc 10 §3.5-§3.8). The P1 frontend shell was pulled forward and built on 18 Nov 2026 (doc 13) — five modules, demo engine, 18 green tests, R8 release 2.8 MiB.

---

## 1. Timeline at a glance

| Phase | Weeks | Duration | Goal | Exit gate |
|---|---|---|---|---|
| **P0 — Step Zero** | 1–2 | 10 working days | Falsify the plan's assumptions (H1–H5) | Go / Plan B / stop decision |
| **P1 — Core skeleton** | 3–5 | 3 weeks | Engine service, chat, model manager, storage | Alpha: chat works on 2 devices |
| **P2 — Features** | 6–9 | 4 weeks | RAG, vision, Arabic dictation, Arabic UX, downloads hardening | Beta: 6 core jobs work |
| **P3 — Polish & launch** | 10–12 | 3 weeks | Performance, policy, closed beta, store assets | v1.0 on Play + GitHub + F-Droid |
| **P4 — v1.1** | 13–18 | 6 weeks | TTS, voice mode, assistant role, benchmark, NPU packs, personas | v1.1 release |
| **P5 — Growth** | 19–24 | 6 weeks | Local API, MCP, community, Arabic eval publication | v1.2 + retention decision |

Calendar assumption: 25–35 productive hours/week; no full-time. If availability drops below 20 h/week,
stretch by 30–40% rather than cut quality gates.

---

## 2. Phase 0 — Step Zero (weeks 1–2)

**Objective:** prove or kill three assumptions before writing product code. Budget: 10 working days,
under 150 USD plus a used test phone (recommended purchase: ~250–350 USD for a Snapdragon
7-series/8 GB device; keep the E: drive out of it — build on D: (46.7 GB free after the AVD
relocation; 66.9 GB before it).

**Day 0 — done 18 Nov 2026:** emulator expansion applied per
[docs/07-test-environment-emulator.md](07-test-environment-emulator.md) — stale test_avd deleted,
`ANDROID_AVD_HOME` set to a dedicated AVD directory on the D: drive, **Nawa_Phone** created (16 GB data / 8 GB sdcard / 6 GB RAM /
6 cores, cold boot) and boot-verified (/data 15G free, 6 GB RAM, 6 cores). C: free 46.0 → 59.7 GB.

**Day 1-2 milestone — done ahead of schedule, 18 Nov 2026:** `nawa-spike` (a throwaway spike project) created (Gradle 8.10.2 + AGP 8.8.0 + Kotlin 2.0.21; NDK 27.2.12479018 + CMake 3.22.1 installed), llama.cpp pinned at release tag v1.0.0 and built static into libnawa_llm.so, and the hello-token run on **Nawa_Phone** with Qwen2.5-0.5B-Instruct Q4_K_M produced "Salam" (Peace be upon you) at 1.70-1.74 tok/s decode (emulator, pipeline proof only). Day 2 was then fully closed the same day: 1.5B functional model with TTFT + peak-RSS logging (H1 harness, 3 runs: TTFT 4.0-4.1 s, 6.1-6.3 tok/s, peak RSS ~1.34 GB on the emulator), an Arabic-instruction run with correct RTL rendering (grammar drift at 1.5B — 2B default justified), and arm64-v8a compile/link validation. Emulator numbers are shape-only; phone H1 remains the gate. Raw numbers, fixes and reproduce steps: [docs/10-step-zero-log.md](10-step-zero-log.md).

**Emulator-first adjustment (user decision, 18 Nov):** the physical phone is bought later. Days 1–2
therefore run on **Nawa_Phone with a small model (Qwen3.5-0.8B or LFM2.5-1.2B, ~0.5–0.8 GB)** for
functional, streaming and rough speed sanity; the 2B default model and all H1/H2/H3 numbers wait for
the phone. **Order the phone by Day 2** so H1 measurements start as soon as it arrives (5–7 day ship)
— still pending on 18 Nov; actual models used on the emulator were Qwen2.5-0.5B (pipeline) and
Qwen2.5-1.5B (functional), see doc 10.

**H2 early progress — 18 Nov 2026:** the LiteRT-LM path was proved on the emulator ahead of the
phone. A new disposable spike, `nawa-litert` (Java-only harness, `litertlm-android:0.19.1`,
Qwen3-0.6B pack) loads and generates Arabic on the x86_64 CPU backend: 5.1-5.8 tok/s decode, peak RSS
~1.9 GB, 3 cold runs (doc 10 §3.5). It also surfaced the Qwen3 `thought`-channel gotcha — without
`enable_thinking=false` the token budget is consumed by hidden reasoning and `Message.contents` stays
empty, which matters for the product's "no raw chain-of-thought" UI rule. The Gemma 4 E2B pack, NPU
backend and CPU-vs-NPU comparison stay phone-day items (no vendor NPU on the emulator; 6 GB RAM).

**H3a early progress — 18 Nov 2026:** the RAG half of H3 was proved on the emulator without the
phone. The LiteRT spike gained an `EmbeddingEngine` retrieval harness: EmbeddingGemma-2 270M
(157 MB, 768 dims, no extra build step beyond the existing AAR) retrieves the right Arabic document
for 20/20 questions over an 8-doc corpus with adjacent-topic distractors, and 4/4 English queries hit
the Arabic docs; ~160-200 ms/query, 572 MB peak RSS (doc 10 §3.6). The eval is saturated at this
size — a real PDF/chunk corpus with paraphrase questions is the product-phase follow-up. The voice
half is now covered by the round-trip below; human WER and listener ratings still need the phone and
the 20 recorded sentences.

**H3b early progress — 18 Nov 2026:** the voice half of H3 was also proved on the emulator without a
microphone. `nawa-voice` (sherpa-onnx **v2.0.1** + **piper-phonemize 1.5.9** AARs) synthesises the
20 doc-11 sentences with Piper `ar_JO-kareem-medium-int8` → 16 kHz WAV → Whisper int8 →
Arabic-normalised WER. Best run: **27.4% overall WER / 15.0% MSA** with whisper-small and
**diacritized** TTS input (whisper-base: 46.6%; raw unvocalized text: 55.5%). Two findings changed
the plan: (a) espeak/Piper Arabic needs a **diacritizer** — unvocalized script phonemizes wrong
(سهلة → `shlt`, كم → `kilometer`); (b) Iraqi dialect is the ASR weak spot (46.8% even on synthetic
audio, errors on گ/شنو/شگد) and whisper-small runs at RTF 0.82 — borderline realtime. Synthetic WER
is an optimistic floor; the 20 human recordings + listener ratings remain the gate (doc 10 §3.7).
The phone-day scoring path is built and validated by replay: WAVs + `refs.tsv` go into
`nawa-voice/recordings/` and one harness command returns WER overall/MSA/Iraqi/Gulf.

**H3c early progress — 18 Nov 2026:** the *integrated* loop is no longer a paper design.
`nawa-assist` runs staged WAV question → Whisper int8 → keyword retrieval → Qwen2.5-1.5B grounded
answer (the H1 llama.cpp JNI lib) → Piper answer WAV in one app process. Whisper-small run: **3/3
retrieval hits, grounded answers, ~57 s/turn, 1.39 GB peak RSS**, engines released cleanly (RSS
~220 MB after turns). whisper-base garbled the dialect question → retrieval miss → a confidently
wrong answer, so **ASR quality decides the chain** (matches H3b's base-vs-small gap). The cost is
prefill: a 250–330-token grounded prompt costs **35–43 s TTFT** on the x86_64 emulator (shape-only),
so the plan adds a **context budget** (top-1 chunk 80–120 tokens), streaming, and an arm64 prefill
re-measure on phone day before locking prompt/turn UX (doc 10 §3.8).

### Deliverables

1. **H1 spike:** Kotlin + Compose hello-token app with llama.cpp JNI; Qwen3.5-2B Q4_K_M; 2k context;
   measure prefill, decode, TTFT, peak RSS on the target device; same model on PocketPal for baseline.
2. **H2 spike:** LiteRT-LM integration on one Snapdragon 8 Gen 2+/MediaTek device with Gemma 4 E2B
   .litertlm; CPU vs NPU comparison; backend actually used. (Emulator hello-world already proved the
   API path with Qwen3-0.6B on CPU — doc 10 §3.5; the E2B pack, NPU and arm64 runs are phone-day.)
3. **H3 spike:** Arabic voice and RAG — Whisper-small WER on 20 Iraqi/Gulf/MSA sentences; Piper
   ar_JO TTS rated by 5 native listeners; EmbeddingGemma RAG on 3 Arabic PDFs, 20 questions. (RAG
   half already proved on the emulator with EmbeddingGemma-2 270M: 8 docs, 20 Arabic questions,
   top-1 20/20, 4/4 cross-lingual, doc 10 §3.6 — PDF extraction/chunking still untested. Voice half:
   the TTS→ASR round-trip is proved on the emulator (whisper-small 27.4% overall / 15.0% MSA on
   diacritized Piper, doc 10 §3.7); the human-recorded WER and Piper listener ratings are the
   remaining phone-day items, with the 20-sentence recording sheet ready in doc 11. The integrated
   voice→retrieval→LLM→TTS loop also runs end-to-end on the emulator (whisper-small: 3/3 retrieval
   hits, ~57 s/turn, 1.39 GB peak, doc 10 §3.8); the phone must re-measure prefill for grounded
   prompts before the turn UX is locked.)
4. **H4 spike:** 15 user interviews/demos (airplane mode, Arabic voice, PDF Q&A) with the install
   intent question.
5. **H5 checklist:** Play closed-test + AI policy + Data safety + model licence consent plan.
6. **Decision memo:** Go / Plan B (fork LM Playground) / stop, with raw numbers.

### Day-by-day

| Day | Work |
|---|---|
| 1 | Create the `nawa-spike` project; Gradle + CMake + llama.cpp submodule pinned; NDK build works; x86_64 dev ABI build — **done 18 Nov 2026** |
| 2 | JNI load + generate on **Nawa_Phone with the small model**; single screen; log tok/s and memory; fix crashes — **fully done 18 Nov 2026**: hello-token (0.5B), 1.5B functional model, TTFT + peak-RSS logging, H1 harness (3 runs; doc 10), Arabic-instruction RTL sample, arm64 link validated |
| 3 | Run H1 on target device once the phone arrives (3 runs warm); PocketPal baseline on same model/device; write numbers — until then, emulator smoke only (harness + shape numbers ready; PocketPal v1.24.0 baseline method proved on the emulator: 3.81 tok/s / 15.1 s TTFT vs our 6.1-6.3 tok/s / 4.0 s TTFT, uncontrolled settings — doc 10 §3.4; phone order still pending) |
| 4 | LiteRT-LM HelloWorld; download Gemma 4 E2B pack; first NPU attempt — **HelloWorld done early on the emulator, 18 Nov 2026** (LiteRT-LM 0.19.1 CPU, Qwen3-0.6B pack, Arabic output; doc 10 §3.5); E2B pack + NPU still phone-day |
| 5 | H2 measurements CPU vs NPU; record backend; fallback behaviour — pending phone (emulator x86_64 CPU shape only: 5.1-5.8 tok/s, peak RSS ~1.9 GB, doc 10 §3.5) |
| 6 | Whisper-small Arabic WER harness + 20 recorded sentences; Piper ar_JO synthesis samples — **round-trip done on the emulator 18 Nov 2026** (TTS → 16 kHz WAV → Whisper int8 → Arabic WER on the 20 doc-11 sentences; whisper-small **27.4% overall / 15.0% MSA** with diacritized TTS, base 46.6%, Piper RTF 0.34, small RTF 0.82; doc 10 §3.7); the 20 human recordings + phone still pending |
| 7 | Listener ratings (5 people) + Arabic RAG mini-spike (EmbeddingGemma, 3 PDFs, 20 questions) — **RAG half done early on the emulator, 18 Nov 2026** (EmbeddingGemma-2 270M: 8 docs incl. 5 adjacent distractors, 20 Arabic Q top-1 20/20, 4/4 cross-lingual, ~160–200 ms/query, RSS 572 MB; doc 10 §3.6); **voice round-trip also done** (doc 10 §3.7); **integrated voice→retrieval→LLM→TTS loop done on the emulator** (whisper-small 3/3 retrieval hits, ~57 s/turn, 1.39 GB peak; doc 10 §3.8); listener ratings still need people + real TTS samples |
| 8 | H4 interviews (5 users) + demos |
| 9 | H4 interviews (10 users) + compile evidence |
| 10 | Decision memo + update this roadmap; confirm the test device has arrived (order by Day 2) |

**Kill/pivot rules:** H4 fails → stop and re-scope. H1 fails → Plan B (fork LM Playground) confirmed
before P1. H2 fails → NPU becomes v1.2, message on CPU/GPU. H3 voice fails → Arabic text/RAG wedge,
voice beta. (Early signal: synthetic MSA WER 15% at whisper-small is viable; Iraqi 46.8% and
RTF 0.82 mean the dialect/phone numbers decide — do not build the voice UI before that.) H3c adds a
latency rule: do not ship grounded voice answers until arm64 prefill for a ≤120-token context is
measured — if TTFT cannot get under a few seconds, ship text-first with voice as read-aloud (v1.1)
and keep the grounded RAG answer in text.

---

## 3. Phase 1 — Core skeleton (weeks 3–5)

**Goal:** a working, reliable local chat app on mid-range hardware. No RAG, no vision, no voice yet.

**Frontend shell — done early, 18 Nov 2026 (doc 13):** the founder directed product-frontend work and the
full M1 shell now exists at `.`: `:app`, `:core:runtime`, `:core:design`, `:feature:chat`,
`:feature:settings`; Cordis-shaped composition, typed UI slots, Girih & Ink design system, Arabic-first
RTL UI with English, light/dark, a deterministic demo engine behind `InferenceEngine`, and config-driven
features. Verified on the emulator: 18 JVM tests, debug 55.5 MiB / R8 release 2.8 MiB, release cold start
1.2–1.5 s, 20 screenshots, add/remove-one-feature acceptance test. Week-3 deliverables still open on the
engine side: Room/DataStore persistence, real engine service (llama.cpp) and model loader, model
catalogue/download, onboarding. The remaining frontend work for P1 is M2: wire the engine, add
persistence, then a real-device pass.

| Week | Deliverable |
|---|---|
| 3 | Project/modules, DI, theme, navigation, Room, DataStore; engine process + AIDL; model loader with GGUF metadata; chat screen streaming from engine |
| 4 | Model catalogue + download manager (WorkManager, resume, sha256, licence gate); storage manager; model detail + recommender v0; onboarding 3 screens |
| 5 | Chat completion: history, edit/regenerate, branch with KV snapshot, search (FTS), export; settings (generation, backends, theme); diagnostics; first alpha on 2 devices |

**Alpha gate (end week 5):** chat works end-to-end on the mid-range device; model load success
10/10; decode within 20% of Step Zero numbers; cold start under 2 s; no crash in 30-minute session.

**Parallel tasks:** set up GitHub repo + CI, buy test phones, start the Arabic eval corpus and
recruit closed-test candidates. Play developer account: personal account planned later (12-testers /
14-days closed test gate — verify current rules); create it when the app is beta-ready, not before.

---

## 4. Phase 2 — Features (weeks 6–9)

**Goal:** parity on the six core jobs + Arabic-first UX.

| Week | Deliverable |
|---|---|
| 6 | Document import (PDF/DOCX/EPUB/TXT/MD), extraction, chunking, EmbeddingGemma embedding, sqlite-vec index, library UI |
| 7 | RAG retrieval + grounded prompt + citations + document chat; vision attach (mmproj) + image pre-processing; import hardening |
| 8 | Whisper dictation (Arabic/English, hold/toggle), permission UX, audio focus; Arabic-first strings, RTL audit, typography, dialect presets, numerals setting |
| 9 | Downloads/storage hardening (SAF move, orphans, low storage), failure/degradation paths, privacy screen (network audit, offline toggle, export/delete), AI output reporting, About/licences/SBOM |

**Beta gate (end week 9):** 6 core jobs work on 2 devices; crash-free 99%+ over a 5-day dogfood;
Arabic eval baseline recorded; RAG citations correct on golden set; no network calls in offline flows.

---

## 5. Phase 3 — Polish and launch (weeks 10–12)

| Week | Deliverable |
|---|---|
| 10 | Performance pass (baseline profile, streaming jank, memory), thermal handling, accessibility audit, screenshot tests, Arabic eval CI, bug bash |
| 11 | Closed beta (50 testers: Arabic-speaking friends/communities + 12 Play testers), crash/ANR triage, fix list, **name clearance sign-off (Sirrak) + package ID frozen (doc 08/ADR-13)**, store listing (English + Arabic), screenshots, privacy policy, Data safety form, AI policy checklist |
| 12 | Play staged rollout 5% → 20% → 100%; GitHub release APK; F-Droid MR; launch posts (Arabic/English), README with honest benchmarks; watch crash-free and ratings |

**Launch gate (must all be true):** crash-free 99.5%+; model load success 90%+; rating-ready build;
policy checklist green; privacy policy live; licences screen complete; no P0 bug open; name approved + ADR-13 recorded + package ID frozen (doc 05/08); closed testing period satisfied (verify current Play
requirement).

**Launch checklist**

- [ ] Name cleared (Sirrak approved 18 Nov 2026; formal search + counsel sign-off still outstanding) and package ID frozen in ADR-13 (doc 05)
- [ ] Privacy policy + security disclosure published
- [ ] Data safety form accurate (no collection, no sharing)
- [ ] AI-generated content reporting implemented (Play requirement)
- [ ] Model licence consent screens (Gemma/Llama gated)
- [ ] Third-party licences screen + NOTICE file complete
- [ ] Content rating questionnaire
- [ ] Target API 36, 64-bit, arm64 only
- [ ] Closed testing completed per Play rules
- [ ] Store listing: Arabic + English, honest screenshots, no brand confusion
- [ ] GitHub release: signed APK, checksums, source tag, build instructions
- [ ] F-Droid: libre flavour builds, metadata MR, no proprietary deps
- [ ] Support channel (GitHub issues + email)
- [ ] Backups: keystore offline, Play App Signing configured, CI secrets documented

---

## 6. Phase 4 — v1.1 (weeks 13–18)

| Weeks | Deliverable |
|---|---|
| 13–14 | TTS: Piper Arabic + Kokoro English + system fallback; voice selection; auto-read |
| 15 | Hands-free voice mode (VAD, barge-in, continuous), foreground service, battery/thermal tuning |
| 16 | Default-assistant role + QS tile + share target; Assistant settings + privacy UX |
| 17 | Benchmark screen + optional public leaderboard upload; sustained vs burst numbers |
| 18 | NPU packs (LiteRT-LM) on supported SoCs with capability matrix; personas (local presets); v1.1 release |

**v1.1 gate:** voice usage ≥15% of active users; crash-free ≥99.5%; NPU does not regress battery;
leaderboard opt-in rate ≥5%.

---

## 7. Phase 5 — Growth (weeks 19–24)

- Local OpenAI-compatible API server (loopback + token) with docs.
- MCP client (tools) — only if maintenance cost acceptable.
- Publish "Nawa Arabic Eval" methodology + results; invite community contributions.
- Community: GitHub discussions, Arabic tutorials, short videos, F-Droid/Reddit/HN posts.
- Decide on image generation, marketplace, iOS based on data (D30 retention, RAG/voice usage).
- Explore partnerships: Arabic content packs (licensed), universities, privacy NGOs.

---

## 8. Budget

### One-time

| Item | Cost (USD) | Notes |
|---|---|---|
| Test phone 1 (mid-range Snapdragon 7-series/8 GB, used) | 250–350 | Primary H1/target device |
| Test phone 2 (flagship Snapdragon 8 Gen 2+/used) | 300–450 | NPU/vision path; optional at start |
| Play developer account | 25 | one-time |
| Domain (sirrak.app; fallback getsirrak.app — doc 08) | 12–40 | **RDAP 404 (unregistered) for sirrak.app/.com/.ai/.chat on 18 Nov 2026**; buy sirrak.app (+ sirrak.com defensive) before package ID |
| Trademark search (basic) | 0–150 | self-search then counsel if needed |
| Legal/scholar review (light) | 300–800 | licence notices + launch review |
| Design assets (icons/type licences) | 0–100 | OFL fonts free; icon commission optional |
| **Subtotal** | **~900–1,900** | |

### Recurring

| Item | Cost/month | Notes |
|---|---|---|
| CI (GitHub Actions minutes) | 0–30 | native builds are heavy; cache aggressively |
| Hosting (static site/leaderboard) | 0–10 | can be GitHub Pages/Cloudflare free tier |
| Domain renewal | ~1 | annual |
| Device farm (release testing) | 0–25 | optional; only when needed |
| **Subtotal** | **~1–65** | |

**Time cost:** 13 weeks x 30 h = ~390 hours to v1.0; v1.1 +180 h; growth +180 h. Opportunity cost at
15 USD/h is ~5,900 USD to v1.0 — the dominant cost. Treat the project as a strategic asset (skills,
portfolio, community, possible product), not a short-term income source.

### Revenue model (v1.1+)

| Option | Price | Notes |
|---|---|---|
| Pro one-time (optional extras: voice pack, image gen later, local API, advanced benchmark export) | 7.99–14.99 USD | no subscription for core; Play Billing |
| Donations/sponsors | voluntary | GitHub Sponsors, Open Collective |
| Licensed content packs (later) | TBD | requires licences + scholar review |
| **Not doing** | ads, data sale, subscriptions, paid model access | trust + halal constraints |

Realistic revenue: 10k installs x 2% conversion x 9.99 USD x 0.7 (Play cut) ≈ 1,400 USD in year one
from a small base; the value is in retention/community/portfolio, not quick revenue.

---

## 9. Device and test matrix

| Device class | Example | Role | Tests |
|---|---|---|---|
| Mid-range (primary) | Snapdragon 7-series / Exynos 1480, 6–8 GB | H1 target, day-to-day | everything, performance budget |
| Flagship Qualcomm | Snapdragon 8 Gen 2/3/Elite, 12–16 GB | H2 NPU, heavy models | NPU packs, vision, benchmark |
| MediaTek | Dimensity 8300/9300, 8–12 GB | NPU breadth | LiteRT NPU path, fallback |
| Low-end (guard) | 4 GB RAM, Helio G-series | crash safety | 0.8B models only, graceful limits |
| Emulator (dev) | Nawa_Phone on D: — x86_64 API 36.1, 16 GB data / 8 GB sdcard / 6 GB RAM (doc 07) | fast iteration | UI/RAG/unit tests, RTL and screenshot matrix (never performance) |

**19 Nov 2026 decision (F8, doc 17 §9): emulator-only for this phase — no phone purchase now.** Development,
TDD and visual evidence continue on Nawa_Phone; phone H1–H3 numbers, thermal, assistant-role device
verification and human dictation WER are **deferred** until a real device exists, and every performance
number remains emulator-shape-only. Phone-day gates do not block M4–M13; they are re-inserted as a single
verification milestone when a device is available.

Physical devices: 2 minimum, 4 ideal (buy used over time). Use a device farm only for release regressions.

---

## 10. Arabic eval plan (the moat, run monthly)

1. **Corpus:** 300 prompts — facts (60), reasoning (60), Arabic grammar/style (40), dialect
   understanding (40), refusal/safety (40), RAG grounded answers (60); 150 English control prompts.
2. **Scoring:** rubric-based human review for style/accuracy on a 50-prompt sample + automated
   heuristics (refusal rate, citation rate, language consistency).
3. **Voice:** WER on 50 Arabic sentences (MSA + Iraqi + Gulf), TTS intelligibility ratings.
4. **Publish:** a public report per release ("Nawa Arabic Eval vX") with methodology and raw
   aggregates; never cherry-pick.
5. **CI gate:** no regression > 2% on automated subset before release. *(Implemented in P3.6 as
   `core/rag/.../ArabicEvalTest` + the `arabic-eval` CI job with 24 versioned cases; the broader human
   rubric and voice pass remain monthly work — see doc 26 P3.6.)*

---

## 11. Ongoing operations (post-launch)

| Cadence | Activity |
|---|---|
| Weekly | Triage GitHub issues + Play reviews; release cadence biweekly |
| Biweekly | Patch release (bug fixes only); staged rollout |
| Monthly | Arabic eval run + report; dependency/security updates; device benchmark refresh |
| Quarterly | Roadmap review against metrics; decide v1.x scope; trademark/licence audit |

**Response SLA (solo):** crash/security within 72 h; data loss within 24 h; feature requests best
effort. Publish this in the README.

---

## 12. Risks calendar

| Risk | Likely window | Mitigation |
|---|---|---|
| Step Zero H1 fails | week 3 | Plan B fork decision within 3 days |
| Native build failures eat Phase 1 | weeks 3–5 | fall back to prebuilt libs; timebox 3 days |
| Arabic PDF extraction worse than expected | week 6 | OCR route in v1.1; scope "text-layer PDFs" for v1 |
| Play review rejection (AI policy) | week 11–12 | pre-submission checklist + in-app reporting |
| Trademark conflict on name | week 10–11 | doc 08 Round 3 shortlist (Sirrak → Najwi → Qalman); formal search by week 10; rename before package ID |
| Closed-testing rule blocks launch | week 11–12 | recruit 12–20 testers by week 6; create Play account when app is beta-ready |
| Thermal throttling ruins benchmarks | week 10 | sustained measurements; honest copy |
| Burnout / availability drop | any | phase gates; cut v1.1 scope, never v1.0 quality |

---

## 13. What success looks like

**Minimum success (12 months):** 10k+ installs, rating ≥4.0, D30 ≥15%, Arabic voice + RAG used by
a meaningful minority, zero data incidents, an Arabic eval suite others cite, a codebase one person
can maintain.

**Stretch:** 100k+ installs, F-Droid listing, assistant-role adoption, a small Pro revenue stream,
community contributions to Arabic eval/catalogue, and a recognised "private Arabic AI" brand.

**Failure that is still worth it:** a clean Apache-2.0 codebase, a documented Arabic eval, an honest
benchmark dataset, and deep on-device AI skills — all transferable even if the app does not break out.
---

## 14. Amendment — 19 Nov 2026: customization, model control and agent scope

**Source:** [doc 17 — Customization, model control and agent scope: Qiyas Council Mode C](17-customization-council.md).
Trigger: the founder asked for multi-theme customization, per-model hyperparameters/chat template/system
prompt, agent/assistant support, and the supported input kinds, and asked what the plan had missed. The
council's verdict is doc 17 §6; this section records only what changes in **this** roadmap.

### 14.1 Phase 1 is redefined — it was not finished at M3

The phase table (§1) and §3 read as if P1 closed with the M1–M3 increments. It did not. §3's own week-5
line — *"Chat completion: history, edit/regenerate, branch, sqlite FTS search, export; settings
(generation, backends, theme)"* — is still open, and M3 closed only chat persistence and answer actions.
P1 is therefore redefined as **the control and reading layer**, delivered as three milestones:

| New milestone | Scope | Effort | Exit gate |
|---|---|---|---|
| **M4 — Appearance packs** | `ThemePack» registry in `:core:design» (Girih & Ink default unchanged, Lapis Night, Turquoise Dawn, Madder Dusk, Paper/High-Contrast), light+dark palettes, preview picker in Appearance, persistence, contrast tests, screenshot matrix | 1.5–2 d | T1 (day 17 §6.2) passes; default screenshots unchanged; 55 + 8–10 tests green |
| **M5 — Model control** (closed 19 Nov 2026, `0.1.0-m5`, record doc 19) | GGUF facts (arch, n_ctx_train, template source, `general.sampling.*») via JNI; `GenerationParams»/`ModelSettings» + precedence + persistence; expanded native sampler chain (top_k → top_p → min_p → typical → penalties → temp → dist, greedy/mirostat modes); Settings > Model UI (Simple/Advanced, model-default markers, reset); template source + formatted-prompt preview + forced built-in template; global + per-model system prompt; prompt presets (personas-lite) | 6–8 d | T2/T3 pass; effective params logged == UI; two models exercised; no regression |
| **M6 — Chat library and reading surface** (closed 19 Nov 2026, `0.1.0-m6`, record doc 20) | multiple conversations (migration from the M3 single file), list/search/rename/delete/export/share; markdown/code/table rendering; token meter and truncation warning; stop sequences + tok/s; per-conversation overrides; font scale | 5–7 d | **passed:** no data loss on migration; export round-trip; long Arabic bidi; M3 evidence intact (235 tests, 0 failures) |
| **M7 — Attachments foundation** (closed 19 Nov 2026, `0.1.0-m7`, record doc 21) | one `Attachment` model (document/image/audio-transcript/shared text) in `:core:runtime`; streaming validation caps; app-private per-conversation storage; untrusted-data prompt block; capability-gated composer attach sheet with honest why-disabled copy; transcript + backup persistence; cascade delete; shared-text flow end-to-end | 2–3 d | **passed:** INP-1/2 (no orphan files, cascade verified on device); disabled states explained; shared-text attach→send→answer end-to-end; 286 tests, 0 failures |
| **M8 — Document RAG** (closed 19 Nov 2026, `0.1.0-m8`, record doc 22) | pure-JVM `:core:rag` (PDF/DOCX/EPUB/TXT/MD detection + extractors, Arabic normalisation, page-aware chunker, hybrid cosine+BM25 RRF retrieval, numbered-source grounded prompt, citation parser); `:engine:embeddings` EmbeddingGemma 2 270M via LiteRT-LM (user-imported); chat grounding + citation chips + excerpt sheet; documents library with SAF import, reindex, delete and cascade; every tuning value in `RagLimits`/`RagPromptPolicy`; ADR-4 engine process-isolation spike | 4–6 d | **passed:** 20/20 Arabic questions top-3 over a 60-document library; PDFs indexed with real page numbers; grounded answer generated on device with citations (807-token prompt, chunked prefill, 133 s); citation fallback for marker-less small models; native abort root-caused and fixed; ADR-4 spike green (engine pid dies, UI survives and recovers); tests green, 0 failures |
| **M9 — Vision** (closed 21 Nov 2026, `0.1.0-m9`, record doc 23) | pure-JVM vision seam (`ImageInput`, `ImagePreparer`, `VisionProjector` naming rule, `ImageSizing` policy); `InstalledModel.visionProjectorPath` + attach/detach in the model store; vendored llama.cpp **libmtmd** (`LLAMA_BUILD_MTMD`) with native vision JNI (marker → tokenize → chunk eval, chunked prefill preserved); bounded EXIF-correct 12 MP preprocessing (2000×1500 JPEG); capability-gated gallery/camera attach; image turns bypass document grounding; typed vision errors (`vision-projector-failed`, `vision-projector-no-vision`, `vision-unavailable`, `vision-image-failed`); vision settings card with native projector validation before saving; VIS-4 data-driven OCR-style prompt shortcuts; 13 new JVM tests + 5 device tests | 5–8 d | **passed:** supported SmolVLM-500M + mmproj loads and answers about an image ("Red."); a text-only model keeps the image rows visible-disabled with the reason; a 12 MP photo preprocessed in 0.198 s with no OOM; projector validated before saving; the OCR-style shortcut extracts text from a screenshot (model-bound accuracy, documented); 415 JVM tests, 0 failures; final device suite OK (20 tests, 236.5 s); signed 74.2 MiB release installed over debug with data preserved; six screenshots (doc 23 `7) |
| **M10 — Dictation** (closed 21 Nov 2026, `0.1.0-m10`, record doc 24) | pure-JVM `:core:speech` seam (recorder/engine/store/settings contracts, WAV codec + resampler, Whisper three-file layout rule, silence filter, dictation state machine), `:engine:whisper` over the pinned sherpa-onnx 2.0.1 AAR (Apache-2.0), `:feature:voice` mic + status strip + Settings card through typed slots, `ComposerTextRequests` transcript→composer bridge (never auto-sent), on-device ar/en/auto Whisper int8, permission rationale + typed failures, R8 keep rule for the JNI-reflected config; 40 new JVM tests in `:core:speech` (+6 `:app`, +3 `:core:runtime`) + 8 device tests | 5–7 d | **passed:** emulator replay WER 28.77% ≤ 35%, RTF 0.64 ≤ 1.2, 982 MB RSS on the 20-file Arabic gate; English WER 0.0, auto-detect, 8 kHz resample, silence → NO_SPEECH, recorder→engine→composer pipeline; 464 JVM tests, 0 failures; device suite OK (28 tests, 263.7 s); signed 138.63 MiB release re-verified after the suite reset app data; phone WER remains open under F8 (emulator-only) |

M5b (1–2 d, timeboxed) is the **template build spike**: a narrow `common/chat» Jinja target to decide
whether custom templates (CHT-11) and grammar-constrained tool calls are buildable without dragging half
of `common/» into the APK. It is a test, not a milestone: fail = park, no sunk cost.

### 14.2 Effect on the phase calendar

| Phase | Before | After (this amendment) |
|---|---|---|
| P0 Step Zero | weeks 1–2 | unchanged; add tests T1–T6 (doc 17 §6.2) to the falsification set |
| P1 | weeks 3–5: engine + chat + model manager + onboarding | weeks 3–7: **M4 → M5 → M6** (control/reading layer) + existing engine work; model manager and onboarding move into P2's M11 |
| P2 Features | weeks 6–9 | **M7 attachments foundation → M8 RAG → M9 vision → M10 dictation (closed 21 Nov 2026) → M11 model manager + onboarding (closed 21 Nov 2026)**; end-of-P2 beta gate unchanged |
| P3 Polish & launch | weeks 10–12 | unchanged; adds the launch gates named in §3.5 A16/A19/A20 of doc 17 |
| P4 v1.1 | weeks 13–18 | **M12 skills/tools + assistant role** (T5/T6 gated; previously SET-7/SET-9 without a tool runtime), **M13 TTS/voice**, full personas, benchmark, NPU |
| P5 Growth | weeks 19–24 | unchanged; MCP stays conditional (v1.2) |

**Time reality:** M4–M6 ≈ 13–17 working days in the plan; in fact M4, M5 and M6 all closed on 19 Nov.
The v1.0 target was **re-checked at the M6 gate on 19 Nov 2026: the gate passed**, so the themes-to-v1.0.1
fallback is not needed and P2 starts immediately (M7 attachments first). M7 also closed on 19 Nov
(`0.1.0-m7`, doc 21), so the attachment seam RAG/vision/dictation rely on is in place before M8 starts.
M8 document RAG then closed on 19 Nov 2026 (`0.1.0-m8`, doc 22): document import/extraction, hybrid
retrieval, grounded answers with citations and the ADR-4 isolation spike are in, with the native
prefill crash root-caused and fixed. M9 vision closed on 21 Nov 2026 (`0.1.0-m9`, doc 23): gallery/camera
image attach behind the capability gate, the vendored llama.cpp libmtmd path with a user-imported mmproj
projector, bounded EXIF-correct 12 MP preprocessing, on-device image Q&A and the VIS-4 prompt shortcuts are
in, with two evidence-driven UI fixes (settings-row squeeze, stale attach copy) and the English-centric
SmolVLM limit documented for the M11 model choice. M10 dictation then closed on 21 Nov 2026 (`0.1.0-m10`, doc 24): the pure-JVM `:core:speech` seam, the pinned sherpa-onnx Whisper engine, the mic/status/Settings UI through typed slots, on-device ar/en/auto transcription with the silence gate and the permission flow are in, with the release-only R8 JNI keep-rule fix and the `(static)` hallucination fix documented; the Arabic replay gate passes at WER 28.77% / RTF 0.64 on synthetic TTS audio (phone WER still open under F8). M11 model manager + onboarding then closed on 21 Nov 2026 (`0.1.0-m11`, doc 25): the pure-JVM `:core:models` seam (catalogue + validator, device profile/recommender, storage math, licence consent, onboarding flow, resumable checksum-verified downloader), the `:feature:models` onboarding screens and Settings model manager, the asset catalogue, SharedPreferences stores and the launch gate are in; a fresh signed release downloaded the real 491 MB Qwen2.5-0.5B from the public Hugging Face resolver, verified it, imported it and answered a chat turn on device (36/36 device tests, 504 JVM tests). P2 is complete; P3 polish and launch begins.
Budget (§8), device matrix (§9), Arabic eval (§10) and operations (§11) do not change.

### 14.3 Step Zero test list (added; details doc 17 §6.2)

| Test | Timebox | Pass → | Fail → |
|---|---|---|---|
| T1 theme packs | 0.5 d | M4 as specced | 3 packs; if still failing, mode-only |
| T2 per-model settings | 1 d | M5 as specced | Kotlin presets + log-only (no fake slider) |
| T3 template display/override | 0.5 d | M5 CHT-9/10 | display-only in v1.0 |
| T4 custom Jinja `common/chat» build | 1–2 d | custom template + tool parsing in v1.1 | park; tools become explicit buttons |
| T5 BFCL-lite tool gate (v1.1) | 1 d | AGT-2/3 in v1.1 | <50% no tools; 50–70% explicit-action skills only |
| T6 assistant role probe (v1.1) | 0.5 d | AGT-5 scope confirmed | QS tile + share target only; role marked phone-day verification |

### 14.4 Gate changes (added to the existing gates; nothing removed)

- **M6 gate (new) — cleared 19 Nov 2026:** conversations/export/markdown/token-meter accepted
  (doc 20); P2 starts now. This was the commit point for the v1.0-date-or-themes decision and the date
  holds with all P1 milestones delivered.
- **M8 exit gate (strengthened) — cleared 19 Nov 2026:** document RAG accepted on device (20/20
  Arabic retrieval, PDF page numbers, grounded answer with citations); the inference processes
  isolation decision (ADR-4) is spiked and green: the engine runs in `:engine`, a killed engine
  process leaves the UI alive, and retry recovers in a fresh process. Production adoption stays on
  the launch gate: a native crash must not take the UI down in a public build (ADR-4, doc 22 §5).
- **Launch gate (strengthened, P3) — M11 part cleared 21 Nov 2026:** model manager + onboarding (M11) complete
  (doc 25) — a public build cannot require a sideloaded GGUF, and the signed release now downloads a model from
  the public Hugging Face resolver and chats with no sideload; attachments security invariant in place before M8; AI-reporting, Data
  safety, licence consent and closed-testing mechanics as already listed in §5.
- **Continue/kill:** the milestone-specific continue/kill table is doc 17 §6.4 and applies here.

### 14.5 New risks added to the calendar (§12)

| Risk | Likelihood | Impact | Mitigation (owner: Engineer) |
|---|---|---|---|
| Theme packs fail contrast/RTL and delay P2 | medium | medium | data-only packs, contrast unit test, 5-pack cap, M4 timebox |
| Sampler UI does not reach the native chain ("theatre") | medium | high | T2 asserts effective params and logs them; mapping unit tests |
| Template override produces plausible garbage | high if ungated | high | model default recommended; warning + preview; T3; custom Jinja only after T4 |
| `common/chat» CMake expansion balloons the APK/build | medium | medium | T4 timebox, narrow sources, park on fail |
| Per-model settings lost on rename/reimport | medium | medium | fingerprint key (size + head/tail hash), migration test |
| M4–M6 delay the moat | medium | high | hard timeboxes + M6 gate + themes-to-v1.0.1 fallback; no RAG quality cuts |

*Previous record: doc 17 is the council; this section is the roadmap consequence. Build records:
doc 18 = M4 appearance packs, doc 19 = M5 model control, doc 20 = M6 chat library and reading surface,
doc 21 = M7 attachments foundation, doc 22 = M8 document RAG.*

---

## 15. Amendment — 23 Nov 2026: P3 executed to a launch candidate

The calendar slipped by design, not by accident: the founder chose depth over deadline (M4–M11 each closed
with device evidence), so P3 was executed as increments P3.1–P3.7 between 21 and 23 Nov 2026 instead of in
the planned weeks 10–12. The engineering phase now ends at **`1.0.0-rc1` (versionCode 12)**; what remains
is publishing work with accounts, URLs and review queues, not feature work.

### 15.1 What P3 delivered

| Increment | Result | Where
|---|---|---|
| P3.1 | Privacy surfaces: local network audit, offline-only switch enforced before sockets, local-first AI-output report queue + explicit share | doc 26 §P3.1 |
| P3.2 | Licence/NOTICE registry (23 components, About screen), privacy policy + security disclosure drafts | doc 26 §P3.2 |
| P3.3 | Accessibility/RTL/touch-target audit fixes (48 dp, 200 % text, TalkBack labels, contrast) | doc 26 §P3.3 |
| P3.4 | targetSdk 36 + edge-to-edge, generated baseline profile, `dataSync` foreground download service | doc 26 §P3.4 |
| P3.5 | Release signing, `libre`/`play` flavours, signed APK + AAB, F-Droid metadata, Play listing/data-safety/screenshots | doc 26 §P3.5 |
| P3.6 | Gradle wrapper + GitHub CI, 24-case Arabic eval in CI, lint 0 errors, screenshot regression gate, plurals/ExifInterface/cleanup fixes, launch checklist | doc 26 §P3.6 |
| P3.7 | CycloneDX 1.5 SBOM from the licence registry (drift-tested), generated store icon + feature graphic (drift-tested), `validate-store.ps1` (79 checks) + `release-check.ps1` one-command gate, rebuilt signed artifacts re-recorded | doc 26 §P3.7 |

Evidence at the close: 567 green JVM tests (0 failures, 0 errors), Arabic eval 24/24, lint 0 errors /
33 accepted warnings (pinned dependency and AGP updates plus a third-party Bouncy Castle trust-manager
warning), screenshot regression 4/4 with a committed baseline, signed `libre` release cold start 1.11 s
median (debug 4.61 s), release executed on the API 36 emulator. P3.7 additionally leaves a drift-tested
CycloneDX 1.5 SBOM (`sbom.cdx.json`), data-generated Play graphics (`store/graphics/`, manifest hashes),
and two green launch validators (`validate-store.ps1` 79/79, `release-check.ps1` all gates) over the
rebuilt signed artifacts. P3.8 additionally published the project: a public GitHub repository with health
files and branch protection, a bilingual static site generated from data and validated by 225 checks
(`validate-site.ps1`) plus 7 JVM drift tests (`WebsiteContentTest`), root privacy/security policies wired
into `app.json` with the signed artifacts rebuilt (APK 145,711,750 B / AAB 64,938,455 B), GitHub Pages live
(EN + AR, HTTPS), and the `v1.0.0-rc1` release with both signed artifacts attached.

### 15.2 §5 launch checklist — actual status (23 Nov 2026)

| Item | State | Remaining action |
|---|---|---|
| Name cleared + ADR-13 recorded + package ID frozen | package frozen (`app.sirrak`); name approved provisionally | formal search + counsel sign-off (doc 08) |
| Privacy policy + security disclosure published | published bilingual on the hosted site (`/privacy/`, `/security/`, `/ar/...`) and wired into `privacy.policyUrl` / `securityUrl`; the rebuilt APK opens them from Settings | — |
| Data safety form accurate | answers written (`store/play/data-safety.md`) | paste into Play Console |
| AI-generated content reporting implemented | done (P3.1, per-answer report action) | — |
| Model licence consent screens | done (M11) | — |
| Third-party licences + NOTICE complete | done (P3.2, registry test regenerates NOTICE) | — |
| Content rating questionnaire | notes ready (`store/play/data-safety.md`) | complete in Play Console |
| Target API 36, 64-bit | done (P3.4: targetSdk 36; arm64-v8a + x86_64) | — |
| Closed testing completed per Play rules | not started | create closed track, recruit ≥12 testers |
| Store listing AR + EN, honest screenshots | copy + 8 screenshots + generated icon/feature graphic ready (`store/`, `store/graphics/`) | upload them in Play Console; closed testing remains |
| GitHub release: signed APK, checksums, source tag | live: <https://github.com/alshahia/sirrak/releases/tag/v1.0.0-rc1> — signed APK + AAB + `SHA256SUMS`, tag `v1.0.0-rc1`; SHA-256 recorded in `store/README.md` and the release notes | — |
| F-Droid: libre builds, metadata MR | repository + tag published; metadata completed (source/commit/changelog URLs, NDK/CMake recipe) with the prebuilt sherpa-onnx AAR caveat | open the `fdroiddata` MR — blocked on a source-buildable sherpa-onnx |
| Support channel | live: GitHub issue chooser (`reportIssueUrl`), security advisories for private reports, `SUPPORT.md`; no email is offered (`reportEmail` empty by design) | — |
| Public repository + bilingual site on Pages (P3.8) | live: <https://github.com/alshahia/sirrak>, <https://alshahia.github.io/sirrak/> (EN/AR, policy pages, sitemap/robots/llms.txt, bilingual 404); CI and Pages green; branch protection with required checks | — |
| Backups: keystore offline, Play App Signing, CI secrets | keystore created locally and git-ignored | back up offline, register Play upload key |

Ordered execution: `docs/27-sirrak-launch-checklist.md`. The launch gate in §5 still applies unchanged;
none of its quality bars were lowered to reach the candidate.

---

## 16. Amendment — 24 Nov 2026: `1.0.0-rc2` refresh

Two source changes landed after rc1 was published and needed a new signed build, so `1.0.0-rc2` (versionCode 13)
was cut on 24 Nov 2026 as the delivery vehicle: the Qiyas-reviewed Arabic terminology normalization
([docs/30-arabic-terminology.md](30-arabic-terminology.md)) and the compact 2 GB catalogue tier
(LFM2.5 350M Instruct, [docs/29-small-models-council.md](29-small-models-council.md)). rc1's tag, artifacts,
screenshots and record were left untouched.

The refresh also rebuilt every public surface from the same tree so the published facts match the shipped app:
the signed APK/AAB were re-recorded in `store/store-spec.json` and `store/README.md`, eight screenshots were
recaptured, the UI regression baseline was refreshed, the store listing and both READMEs were updated, the
bilingual site was regenerated (OG card included), and the SBOM/NOTICE version stamps were bumped. The gates
were rerun on the rc2 tree with `scripts/release-check.ps1`; the release and CI evidence is recorded in
[docs/27-sirrak-launch-checklist.md](27-sirrak-launch-checklist.md) §P3.9.
