<!--
  Thanks for the pull request. Keep the sections that apply and delete the rest; an empty heading is
  noise. Rules and the environment table are in CONTRIBUTING.md:
  https://github.com/alshahia/sirrak/blob/main/CONTRIBUTING.md
-->

## What changed

<!-- One short paragraph, in the imperative: what the diff does. -->

## Why

<!--
  The problem, the user impact, or the record that required it. Link the issue (`Closes #123`).
  If the change is a refactor, say what it makes possible and confirm behaviour is unchanged.
-->

## How it was verified

Paste the commands you ran and the result. Delete lines that do not apply; an unticked relevant box is a
question the reviewer has to ask.

- [ ] `.\gradlew :app:testLibreDebugUnitTest testDebugUnitTest` — all green, with the count pasted above
- [ ] `.\gradlew :app:lintLibreDebug` — 0 errors (list any new warning and why it is accepted)
- [ ] `pwsh -File scripts/validate-store.ps1` — store/listing or version changes only
- [ ] `pwsh -File scripts/release-check.ps1` — release-affecting changes (`-SkipBuild -SkipEmulator` re-verifies an existing build)
- [ ] `pwsh -File scripts/ui-regression.ps1` — UI changes; the new baseline is committed with the code
- [ ] Device or emulator check — state the model, the Android version and what you did

## No hardcoded values

- [ ] Behaviour lives in data or config (`app/src/main/assets/config/app.json`), the pack registry
      (`core/design/.../ThemePacks.kt`) or a typed slot (`core/runtime/.../Slots.kt`), not in a call site.
- [ ] No user-visible string literal in Kotlin: copy is in `values/` (Arabic) and `values-en/` (English).
- [ ] No colour, spacing, shape or motion literal where a pack role or token exists.
- [ ] A new knob is validated at startup and fails loudly in debug, not silently.

## Licence and dependency

- [ ] No new runtime dependency, **or** the dependency has an entry in
      `app/src/main/assets/config/licences.json` with its licence text, and `NOTICE` + `sbom.cdx.json` are
      updated from the same registry (the `:app` licence tests fail on drift).
- [ ] No keystore material, `keystore.properties`, build output, evidence directory or log in the diff.

## Arabic, RTL and accessibility

- [ ] Arabic (`values/`) and English (`values-en/`) strings added or updated together.
- [ ] Layout verified in RTL; mixed Arabic/Latin values still read in the right direction.
- [ ] Touch targets ≥ 48 dp; text at 200 % still fits; TalkBack labels name the action.
- [ ] Contrast comes from the pack and passes the WCAG gate — no hand-picked colour that bypasses it.

## Screenshots

<!-- Required for visible UI changes: before/after, light and dark, Arabic and English. -->

| | Before | After |
| --- | --- | --- |
| Light, Arabic | | |
| Dark, English | | |

## Notes for the reviewer

<!--
  Trade-offs, follow-ups that are deliberately out of scope, and anything you would test first.
  Say if the change is incomplete, and what is missing.
-->
