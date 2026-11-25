# The 1.1.0 UX audit — report index

This folder is the raw record of the round-2 UX audit of Sirrak 1.1.0. **Start with [../33-ux-audit-1.1.0.md](../33-ux-audit-1.1.0.md)** — it is the founder-facing synthesis (state of the app, ranked backlog, council ruling, evidence). Everything here is the evidence behind it.

- **Brief and rules:** [00-brief.md](00-brief.md) — mission, product promises, ground rules, finding schema, severity definitions, ownership, definition of done.
- **Audited build:** `app.sirrak` 1.1.0 (versionCode 14), debug APK, HEAD `e28a9b6` (whose diff touches no app source). Two emulators: 5554 (founder's, 720×1280) and 5556 (clean AVD, 1080×2400).
- **No app source was modified by this audit.** `git status` shows only `docs/33-ux-audit-1.1.0.md` and this folder as new; the working-tree changes (`alshahia` → `ahmadmhmdsy` URL sweep) predate the audit.

## Area reports

| Report | Owner | Findings | What it covers |
|---|---|---|---|
| [01-user-journey.md](01-user-journey.md) | journey-runner | 28 — P0 1 · P1 7 · P2 15 · P3 5 | The journey end to end: first open, no model, getting a model, first answer, keeping the result, honesty of the surface. Includes the interference log and the measured-number appendix. |
| [01b-cold-start-evidence.md](01b-cold-start-evidence.md) | fresh-install-runner | 15 — P0 1 · P1 2 · P2 9 · P3 3 | The clean-install run on emulator 5556: minute-by-minute timeline, two cold-start measurements, the download lifecycle, the HF failure forensics, the first real answer, 45-shot index. **The strongest evidence in the audit.** |
| 02-screens-ia.md — **not delivered** | screen-auditor | — | Screens and information architecture. The auditor went inactive before writing; screens/IA was folded into 01, 03 and 05. A screen-by-screen visual pass is still owed before a polish release (08 §B.3, §F). |
| [03-result-interaction.md](03-result-interaction.md) | interaction-auditor | 46 — P0 1 · P1 12 · P2 25 · P3 8 | The answer path: composer, streaming, message actions, citations, attachments, dictation, keyboard, selection, stop/regenerate/copy/share. |
| [04-a11y-i18n.md](04-a11y-i18n.md) | a11y-i18n-auditor | 33 — P0 0 · P1 7 · P2 13 · P3 13 | Accessibility and RTL/EN: semantics tree, live regions, names/roles, contrast across packs, font scale, bidi, localisation. |
| [05-states-errors.md](05-states-errors.md) | state-integrity-auditor | 35 — P0 4 · P1 10 · P2 19 · P3 2 | Every gate and failure state: model states, downloads/queue, HF tokens, save failures, corrupt state, process death. |
| [06-performance-motion.md](06-performance-motion.md) | perf-motion-auditor | 30 — P0 1 · P1 5 · P2 19 · P3 5 | Startup, first paint, model load, streaming cost, download costs, motion. Engine STATS, `Displayed`, `Davey`/`Skipped` evidence. |
| [07-copy-claims.md](07-copy-claims.md) | copy-claims-auditor | 34 — P0 0 · P1 6 · P2 16 · P3 12 | Every user-facing string and every public claim: onboarding, errors, privacy statements, store listing, Play data safety, docs vs build. |
| [08-council-verdict.md](08-council-verdict.md) | council (perf-motion-auditor, reassigned) | 31 distinct — P0 8 · P1 23 + ~120 P2/P3 themes | The dedupe, the verification of every P0/P1 in the shipped tree, the adversarial pass, the ranking, the ten fixes, the blind spots, the unverified list. |
| [09-lead-cross-check.md](09-lead-cross-check.md) | lead | LC-01…LC-14, LV-01…LV-08 | The Lead's independent verification pass, including the items the Lead found while reviewing the clean install (HF header break, bidi list check that turned out healthy). |

**Raw counts:** 221 findings filed by the areas + 22 Lead items; the council merged them into **31 distinct P0/P1 defects** (8 P0) and ~120 P2/P3 themes. Duplicates were expected — the same defect is seen by different specialists — and the merge map is in 08 §0.

## Evidence pack

All of it is under `build/ux-audit/` (git-ignored, local):

- `shots/` — 9 screenshots from the 5554 audit install (with node tables and raw logcat in `logs/`). **Known defect:** `00-baseline-settings-root-ar.png` is actually Library → Models; the clean pack is authoritative.
- `shots-clean/` — 45 screenshots from the clean install on 5556 (onboarding → Library → HF → downloads → installed model → first answer), each with a `logs-clean/clean-NN-*.xml` hierarchy and a `*-nodes.txt` table (tap centres, bounds, roles). The narrative timeline is `EVIDENCE-clean.md`, the driver `tools-clean/cap.ps1` + `tap.ps1`.
- `zoom/` — the Lead's 2× crops used to settle two disputed visual claims (`answer-list.png`: Arabic ordered lists are correct; `hf-top.png`: the HF repo header is broken).
- `tools/ux.ps1` — the 5554 capture helper. `EVIDENCE.md` — the 5554 narrative plus the third-party interference log.

## How to reproduce a few load-bearing facts

```powershell
# the engine's own timings (first token, tok/s) on the audited install
Select-String -Path build/ux-audit/logs/logcat-full-1901.txt -Pattern "PREFILL|STATS|MODEL_LOADED" | Select-Object -Last 12

# the HF download failure on the clean install
Get-Content build/ux-audit/EVIDENCE-clean.md | Select-String -Pattern "clean-2[3-9]"

# what the composer and the failure actually say
Select-String -Path feature/chat/src/main/res/values/strings.xml -Pattern "error_no_model|action_open_settings"
Select-String -Path app/src/main/assets/config/app.json -Pattern "provider|systemPrompt"
```
