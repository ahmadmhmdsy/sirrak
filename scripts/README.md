# Scripts

Quality and packaging tools that GitHub CI cannot fully run (they need a booted emulator, a local
signing key or a headless browser). Everything is PowerShell, developed on Windows; data (limits,
paths, hashes, layout) lives in `store/store-spec.json` or `store/graphics/brand.json`, not in the
scripts, so changing a rule is a data edit.

| Script | Needs | What it proves |
| --- | --- | --- |
| `ui-regression.ps1` | booted emulator | 4 shell screens match the committed dHash baseline |
| `validate-store.ps1` | files only | listing copy obeys Play limits; screenshots, graphics and launch docs are complete and current |
| `release-check.ps1` | JDK/SDK (+ emulator unless `-SkipEmulator`) | the whole pre-release gate: Gradle build/lint/tests, signed artifacts + certificate, store assets, screenshots |
| `store-graphics.ps1` | Chrome or Edge | regenerates the 512×512 icon and 1024×500 feature graphic from `store/graphics/brand.json` |
| `build-site.ps1` | files only | regenerates the committed bilingual site (`website/dist/`) from `website/site.json`, `website/content/` and `store/graphics/brand.json` |
| `validate-site.ps1` | files only | 225 structural checks over the generated site: pages, copy parity, SEO (canonical/hreflang/JSON-LD/sitemap/robots/llms.txt), a11y, brand and committed-output drift |
| `validate-repo.ps1` | authenticated `gh` + network | the published GitHub facts: description/topics/homepage/license, Pages, secret scanning, branch protection, release assets and digests, live URLs |

## `ui-regression.ps1` — screenshot regression smoke test

Captures a fixed set of screens from a clean launch, crops the status bar and the navigation bar,
computes a 64-bit perceptual dHash (8x8 difference hash) for each screen and compares it with the
baseline in `ui-baseline/`. Anti-aliasing, the system clock and battery icon are either cropped out or
absorbed by the hash; a real layout, colour or content change moves the hash further than the default
tolerance (Hamming distance 8) and fails the run with a non-zero exit code.

```powershell
pwsh -File scripts/ui-regression.ps1                  # compare against the baseline
pwsh -File scripts/ui-regression.ps1 -UpdateBaseline  # accept the current screens as baseline
pwsh -File scripts/ui-regression.ps1 -Serial emulator-5556
```

Screens covered: `home` (empty conversation), `model-picker` (the no-model doorway sheet opened from the
header chip — the chip moved out of the composer when model management left Settings), `library-empty`
(Library tab, models segment) and `settings-top` (Settings root, first doors). Current captures are written
to `ui-current/` (git-ignored) so a failing run can be reviewed next to `ui-baseline/`. The chip tap is
`Tap-Scaled 0.64 0.166` (418,212 on 720x1280); the old 0.10 y-fraction now misses the chip.

Baseline provenance: refreshed on the API 36 `Nawa_Phone` AVD (720x1280) on 25 Nov 2026 from
`1.1.0` candidate debug build `3BDC4213…192E` (versionCode 14, universal APK 223,217,333 B), from a clean
launch with no model installed; the final artifact `4CD65A60…539A` then re-ran the plain compare and all
four screens still match at distance 0. The previous baseline was the signed `1.0.0-rc2` release. Refresh the baseline only together with an intended UI
change, and commit the new PNGs in the same change as the code.

## `validate-store.ps1` — Play/F-Droid listing validator

Data-driven launch gate for `store/`. It reads `store/store-spec.json` and checks:

- name ≤ 30, short description ≤ 80, full description ≤ 4000, What's new ≤ 500 characters, per locale;
- no leading/trailing whitespace, tabs or empty fields;
- the listing name equals the shipped `app_name` (Arabic) / the frozen Latin name (English);
- What's new mentions the current `versionName` read from `app/build.gradle.kts`;
- 2–8 phone screenshots, PNG, 320–3840 px sides, portrait;
- icon 512×512 and feature graphic 1024×500 with SHA-256 matching `store/graphics/manifest.json`
  (a stale manifest fails, so the PNGs cannot silently drift from `brand.json`);
- every launch document exists (`NOTICE`, `PRIVACY.md`, `SECURITY.md`, SBOM, F-Droid metadata, data
  safety answers, graphics README).

```powershell
pwsh -File scripts/validate-store.ps1          # human summary, exit 1 on failure
pwsh -File scripts/validate-store.ps1 -Json    # machine-readable result
```

## `release-check.ps1` — one-command pre-release gate

Runs the local half of `docs/27-sirrak-launch-checklist.md` in order and writes
`build/release-check.json`:

1. Gradle: `assembleLibreRelease`, `bundlePlayRelease`, `lintLibreDebug`, the full JVM suite;
2. JVM test results summed per module from `build/test-results/` (flavoured `app` uses
   `testLibreDebugUnitTest`, plain modules use `testDebugUnitTest`) against the minimum in the spec;
3. lint XML: 0 errors, warnings reported;
4. artifacts: size/SHA-256 compared with the digests recorded in the spec (drift fails the gate),
   `apksigner verify --print-certs` + certificate fingerprint for the APK, `jarsigner -verify` for the AAB;
5. `validate-store.ps1`;
6. `build-site.ps1` + `validate-site.ps1` (the generated site must be complete and match the data);
7. `ui-regression.ps1` when the serial is online (skip with `-SkipEmulator`);
8. pass/fail summary and exit code. `-SkipBuild` re-verifies existing artifacts without rebuilding.

```powershell
pwsh -File scripts/release-check.ps1
pwsh -File scripts/release-check.ps1 -SkipBuild -SkipEmulator
```

## `store-graphics.ps1` — store graphics renderer

Generates the Play icon and feature graphic from data: palette roles and copy keys come from the
shipped theme pack and strings, the mark paths are imported from `ic_launcher_foreground.xml`, the
TTFs are embedded from `core/design`, and headless Chrome shapes the Arabic with HarfBuzz before
screenshotting exact 512×512 / 1024×500 PNGs. `store/graphics/manifest.json` records the renderer
version, brand source hash and each output's size + SHA-256; `StoreGraphicsBrandTest` fails when any
of those drift. Details and limits: `store/graphics/README.md`.

```powershell
pwsh -File scripts/store-graphics.ps1
```

## `build-site.ps1` and `validate-site.ps1` — the bilingual public site

The site is generated, never hand-written: `website/site.json` holds URLs, release facts and palette
roles; `website/content/en.json` and `ar.json` hold the copy; `website/content/policies.json` holds
the privacy/security pages that `PRIVACY.md` and `SECURITY.md` also render from; `store/graphics/brand.json`
supplies the mark. The build writes the committed `website/dist/` (six pages plus the bilingual 404,
sitemap, robots, llms.txt, manifest, icons and OG image) with LF endings, so CI can prove the committed
output matches the sources via `git diff --exit-code -- website/dist`.

```powershell
pwsh -File scripts/build-site.ps1
pwsh -File scripts/validate-site.ps1
```

Live address: <https://ahmadmhmdsy.github.io/sirrak/>. The `WebsiteContentTest` JVM suite independently
checks the same contract app-side (data sources, EN/AR parity, brand tokens, URLs, no local paths), so a
drift fails both the Gradle suite and the PowerShell gate. Raster assets are rendered once by
`site-assets.ps1` from the brand data and committed.

## `validate-repo.ps1` — published repository and release validator

`validate-site.ps1` is offline and structural; `validate-repo.ps1` checks the account-side facts that
only exist on GitHub and on the web. Expected values are data too (`store/repo-spec.json`), so a rename
or a new required CI job is a spec edit: risk visibility, default branch, license, homepage, description
keywords and topics; Pages build type + HTTPS; secret scanning, push protection and Dependabot alerts;
branch protection (strict required checks = the four CI jobs, linear history, no force pushes/deletions);
the published release's required assets with GitHub-computed SHA-256 digests matching `store/store-spec.json`;
and every live URL returning 200. Read-only; needs an authenticated `gh` CLI.

```powershell
pwsh -File scripts/validate-repo.ps1              # includes the live Pages URLs
pwsh -File scripts/validate-repo.ps1 -SkipLive    # repository + release only
```

## Why these are not in GitHub CI

GitHub-hosted runners have no KVM acceleration for an Android emulator, no signing key and no
headless-browser font stack, and this project rules out paid device farms. The JVM half of the gate
(unit tests, Arabic eval, lint) and the offline site build/validation run in `.github/workflows/ci.yml`;
the rest runs locally before a release cut. A future self-hosted runner with KVM could call these scripts unchanged.
