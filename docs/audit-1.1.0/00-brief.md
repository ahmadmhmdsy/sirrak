# UX audit brief — Sirrak 1.1.0 (round 2)

**Date:** 25 Nov 2026 · **Owner:** Lead · **Audience:** the founder, reviewing "where the app stands"
**App under review:** Sirrak 1.1.0 (versionCode 14), debug build installed on emulator `emulator-5554`
(720x1280 @ 320 dpi, API 36, x86_64, 6 GB RAM, en-US system locale), currently showing the Arabic UI.

## 1. Mission

Find every gap, defect, contradiction or missed opportunity in the **user experience of the front end** —
especially **how the user gets an answer and interacts with it** — and record it so the founder can decide
what to fix. Round 1 (`docs/31`) was a rework driven by five founder complaints; this round is an
independent, evidence-based audit of what 1.1.0 actually feels like, at every level from P0 to P3.

Two jobs, equally weighted:
1. **Verify** the round-1 claims in `docs/32` against the shipped build (did the fix actually land? is it
   discoverable, complete, honest under failure?).
2. **Find what round 1 never looked at** — first-run, result rendering, message actions, streaming,
   dictation, media, RAG citations, keyboard, scrolling, empty/error states, Arabic typography and bidi,
   accessibility, performance perception, copy quality, and the honesty of every status surface.

## 2. What the product promises (the yardstick)

From `PRODUCT.md` / `README.md`: private, offline, **Arabic-first**, on-device assistant on a mid-range
phone; no account, no cloud, no telemetry; chats, documents with citations, dictation; honest about limits
("honest about its limits (speed, memory, model fit) rather than magical"); one-handed use, often no signal;
model downloads are 2–4 GB on metered data; Arabic/English mixed life; export/delete must be obvious.
Target users: Abu Zaid (Baghdad student, 6 GB RAM, metered, Arabic), Sara (professional, mixed
Arabic/English documents), Omar (power user, imports GGUFs).
Constraints that are not debatable: Arabic-first RTL, terminology law (`docs/30`: model = نموذج,
prompt = تعليمات, projector = ملحق الرؤية), five theme packs, WCAG contrast gate, 48 dp targets,
no telemetry, offline-gated network, no user-visible English-only strings in the Arabic build.

## 3. Ground rules (all members)

- **Evidence or it didn't happen.** Every finding cites code `path/to/File.kt:line` and/or a screenshot from
  the evidence pack, and/or a measured number from adb. No speculation presented as fact; mark confidence.
- **Never invent.** If you could not verify, say "unverified" and say why.
- **Read `docs/32` first** so you do not re-report round-1 work as new. If a round-1 claim is false or
  incomplete in the shipped build, say so explicitly ("claimed fixed in docs/32 P2 — actually ...").
- **No app source changes.** Nobody edits `app/`, `core/`, `feature/`, `engine/` or assets. Console is
  read-only except your own report file, the brief, and Lead-owned files.
- **Emulator is exclusive.** Only `journey-runner` runs adb / touches the device until it reports done.
  Everyone else consumes `build/ux-audit/` (screenshots + `EVIDENCE.md`). Need a capture? Ask the Lead —
  do not open adb yourself.
- **Skills to load yourself** (tool `skill`), as fits your area: `impeccable` (UX/visual critique),
  `a11y-audit` (accessibility), `frontend-design` (typography/layout intent), `review-room` (adversarial),
  `qiyas-council` (the project's council method), `systematic-debugging` (when a claim is suspicious).
- **One report file per member**, path fixed below. Do not write to another member's file.
- **Deliverable length:** 25–70 findings per area, each 4–10 lines. Quality over quantity; a padded list of
  duplicates is worse than 30 sharp findings.

## 4. Finding schema (mandatory)

```
### AREA-NN — short title (max ~60 chars)
- **Severity:** P0 | P1 | P2 | P3   **Confidence:** high | medium | low   **Effort:** S | M | L
- **Where:** screen → element (`file.kt:line`, and screenshot name if you have one)
- **What happens now:** one or two sentences, observable.
- **Why it hurts:** the user consequence (task abandoned, trust lost, doubt about state, extra work).
- **Fix:** the smallest change that removes the pain (concrete: label, state, layout, ordering).
- **Verify:** how to prove the fix works (device step, test, or measurement).
```

Severity: **P0** = breaks/ blocks the core promise or actively misleads (data loss, dead end, false state,
unusable control); **P1** = user cannot complete or does not discover the main path, or trust breaks;
**P2** = friction, hesitation, extra steps, poor feedback; **P3** = polish, consistency, delight.
Report every P0/P1 found; P2/P3 should still be real and specific (no "improve typography" hand-waving).

## 5. Evidence pack and baseline (owned by journey-runner)

- Screenshots: `build/ux-audit/shots/NN-name-{ar|en}.png` (keep both directions where relevant).
- Index + observations: `build/ux-audit/EVIDENCE.md` (what was driven, what was seen, timings, jank data).
- Raw commands/logs: `build/ux-audit/logs/`.
- Baseline already known: fresh install state, no model downloaded, one empty-ish conversation, app language
  Arabic while the system is English, Settings root visible. Storage `files/` = conversations, media,
  models (empty), rag, speech_models (empty).

## 6. Round-1 items to verify, not repeat (from docs/31 + docs/32)

| # | Round-1 claim | Verify in 1.1.0 |
|---|---|---|
| V1 | Four-state honest model chip, every state acts | all four states reachable/legible; does the "no model" chip really route to the download CTA in ≤1 tap? |
| V2 | Library = 3 segments (models/downloads/media), no fake cards | empty-state quality, first-run discoverability, per-card fit verdict legibility |
| V3 | Durable download queue, one active, pause/resume/cancel/retry | what the row shows, what happens after process death, metered question |
| V4 | Settings = 6 doors with live summaries | summary accuracy, door ordering, back behaviour, depth |
| V5 | HF in-app search → file list → probe → fit verdict → download | honesty of "not verified", gated-repo flow, failure copy |
| V6 | Media V1 with keep/once contract | the contract is understandable without reading docs; once-only deletion timing |
| V7 | Chat: send → stream → copy/regenerate/new chat, token meter, markdown/bidi | the actual result experience: first token latency, stop, regenerate scope, scroll |
| V8 | Onboarding, dictation, vision, RAG citations | whether a first-time user can reach a first answer without docs |

## 7. Ownership matrix

| Member | Report file | Owns |
|---|---|---|
| journey-runner | `docs/audit-1.1.0/01-user-journey.md` + evidence pack | live device walkthrough, first-run→first-answer path, all screens captured, timings/jank, model download + real generation |
| screen-auditor | `docs/audit-1.1.0/02-screens-ia.md` | per-screen visual/hierarchy/IA critique, all 15-ish screens + every settings subpage, theme packs, typography, spacing, affordances |
| interaction-auditor | `docs/audit-1.1.0/03-result-interaction.md` | the result itself: composer, streaming, message actions, citations, attachments, dictation, keyboard, selection, long-press, scrolling, stop/regenerate/copy/share |
| a11y-i18n-auditor | `docs/audit-1.1.0/04-a11y-i18n.md` | TalkBack, semantics, touch targets, contrast, font scale, RTL mirroring, bidi/digits, string parity AR/EN, terminology law |
| state-integrity-auditor | `docs/audit-1.1.0/05-states-errors.md` | every empty/loading/error/offline/denied/cancel/permission state, persistence, process death, honesty of status surfaces |
| perf-motion-auditor | `docs/audit-1.1.0/06-performance-motion.md` | startup, recomposition, streaming smoothness, jank numbers, animations, memory/thermal messaging, perceived speed |
| copy-claims-auditor | `docs/audit-1.1.0/07-copy-claims.md` | all user-facing copy incl. Arabic naturalness/terminology, errors, empty states, marketing vs reality (README/PRODUCT/store vs app) |
| council-reviewer | `docs/audit-1.1.0/08-council-verdict.md` | dedupe/cross-check all reports, kill false positives, rank by impact×effort, council verdict + first-10 fixes |
| Lead | `docs/33-ux-audit-1.1.0.md` + `docs/audit-1.1.0/README.md` | brief, consolidation, index updates, final answer |

## 8. Definition of done for each member

1. Your report file exists at the fixed path, in the schema above, with a 5-line executive summary at the top
   (biggest strengths, biggest risks, count by severity).
2. Every P0/P1 has high confidence and concrete evidence.
3. You post a ≤12-line summary to the Lead via `send_message` (target `lead`) and mark your shared task
   complete.
4. You did not modify app code, did not touch the emulator (unless you are journey-runner), and did not edit
   another member's file.
