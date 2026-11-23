# Store listing assets (P3.5–P3.7)

One source of truth for the launch listing, split by channel. The Arabic files are the primary
listing (Arabic-first product); the English files are the translation. Copy the fields into the
console of the channel you are publishing to and keep them in sync here — `scripts/validate-store.ps1`
checks the platform limits before upload.

| Folder / file | For | Notes |
| --- | --- | --- |
| `listing/ar/` | Google Play (Arabic), F-Droid `Name:ar` | primary |
| `listing/en/` | Google Play (English), F-Droid | translation |
| `play/data-safety.md` | Play Console → App content → Data safety | answers match the shipped code, not aspirations |
| `screenshots/` | Play, F-Droid, README | captured on the API 36 emulator, see `screenshots/README.md` |
| `graphics/` | Play feature graphic (1024×500) + app icon (512×512) | generated from `graphics/brand.json`; sizes/hashes in `graphics/manifest.json`; see `graphics/README.md` |
| `store-spec.json` | machine-readable launch rules | Play limits, locales, required files, screenshot rules, release artifacts, signing fingerprint — read by both validators |
| `../fdroid/` | F-Droid submission | metadata + build recipe |
| `../sbom.cdx.json` | CycloneDX 1.5 SBOM | generated from the licence registry, drift-tested (see below) |

## SBOM (`sbom.cdx.json`)

The SBOM is generated from the same `LicenceRegistry` that feeds Settings → About and `NOTICE`, so the
four consumers cannot disagree: `core/runtime/.../Sbom.kt` renders CycloneDX 1.5 JSON, the app entry
becomes the metadata subject, every other registry entry becomes a component with its SPDX id (or the
licence name for non-SPDX models such as Llama-3.2-Community), a `pkg:generic/...` purl, and
`sirrak:kind` + `sirrak:bundled` properties (bundled → `scope: required`, runtime-downloaded →
`excluded`). `LicenceRegistryAssetTest` fails when the committed SBOM drifts from the registry or misses
an entry, and rewrites `app/build/reports/sbom-expected.json` to make the fix mechanical.

Scope, stated honestly: the SBOM lists the **top-level components in the licence registry** (what the
about screen can name and what the notices cover), not the full transitive Maven/Gradle graph. A
dependency-graph SBOM is a separate build-time generator and a future increment.

## Validation

```powershell
pwsh -File scripts/validate-store.ps1    # listing limits, screenshots, graphics hashes, launch docs
pwsh -File scripts/release-check.ps1     # gradle gates + artifacts + signatures + store + screenshots
```

`validate-store.ps1` currently runs **79 checks** (character limits for name/short/full description/
What's new in both locales, shipped-name consistency, current version present in What's new, 2–8
portrait screenshots with Play's 320–3840 px side bounds, graphics dimensions + SHA-256 against the
manifest, and every launch document). Fixing it found and fixed two real launch blockers: the Arabic
and English short descriptions were 84/89 characters (limit 80) and the English What's new was 545
(limit 500).

## Artifacts (final `1.0.0-rc1` build, 23 Nov 2026, rebuilt after P3.7)

| Artifact | Path | Size | SHA-256 |
| --- | --- | --- | --- |
| Signed release APK (libre, F-Droid/self-hosted) | `app/build/outputs/apk/libre/release/app-libre-release.apk` | 145,711,750 B (138.96 MiB) | `FD94EA313EBA7D71E6E10C3BD4B6BFBC288696EBD2DC4F05E6FEBD0C038A8CCE` |
| Signed release AAB (play) | `app/build/outputs/bundle/playRelease/app-play-release.aab` | 64,938,455 B (61.93 MiB) | `9326F505C25916D5FD1A888408DBA73C8EFA355AF5893EC76A23829CBB5B5D81` |

Signing certificate (local release key, not F-Droid's): certificate SHA-256
`4537ECEB9952AC9020EFFCEB8B50CB10AEB795772792D632CFC3D0B9F7106806`, 4,096-bit RSA, valid
10,000 days, alias `sirrak`. `keystore.properties` and `keystore/` are git-ignored; a checkout
without them builds an unsigned release, which is what F-Droid wants. **Every rebuild changes these
hashes** — `scripts/release-check.ps1` prints and records fresh ones, re-record them here before
attaching a release to a store or F-Droid MR.

## Still needed before publishing

- Host `PRIVACY.md` and `SECURITY.md` and fill `privacy.policyUrl` / `privacy.securityUrl` in
  `app/src/main/assets/config/app.json` (the UI truthfully says "not published yet" today).
- Choose the source repository URL and fill every `TODO-` in `fdroid/metadata/app.sirrak.yml`.
- Register the Play upload key and run closed testing; upload `store/graphics/*.png` and the eight
  screenshots from `store/screenshots/` with the listing copy.
- Walk `docs/27-sirrak-launch-checklist.md` in the workspace docs.
