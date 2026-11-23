# 27 — Sirrak launch checklist (P3.7)

The concrete, ordered list that turns the repository into a published launch candidate. Each item
names the command or file that proves it. Nothing here needs a test phone (F8): the API 36
`Nawa_Phone` AVD is the device under test. `[x]` marks items proven on the P3.7 record (23 Nov 2026);
`[ ]` items still need an account, a host or a human review.

## 1. Quality gates (must be green before cutting a build)

- [x] JVM unit tests: `./gradlew :app:testLibreDebugUnitTest testDebugUnitTest` → **567 tests, 0
      failures, 0 errors** (flavoured `app` + plain modules; release/stale result folders ignored).
- [x] Arabic eval: `./gradlew :core:rag:testDebugUnitTest --tests "app.sirrak.core.rag.ArabicEvalTest"`
      → **24/24** cases, printed as four `Arabic eval ... cases passed` lines.
- [x] Android lint: `./gradlew :app:lintLibreDebug` → **0 errors**, 33 accepted warnings (pinned
      dependency/AGP updates + a transitive third-party TLS warning; see docs/26 P3.6).
- [x] Screenshot regression (device): `pwsh -File scripts/ui-regression.ps1` → **4/4 screens match**
      (`-UpdateBaseline` only together with an intended UI change).
- [x] Store assets: `pwsh -File scripts/validate-store.ps1` → **79 checks pass** (Play character
      limits, names, version in What's new, screenshots, graphics hashes, launch documents).
- [x] SBOM drift: the committed `sbom.cdx.json` must equal the licence-registry render (covered by
      `app.sirrak.LicenceRegistryAssetTest` in the JVM suite; regenerate it when a component changes).
- [x] One-command gate: `pwsh -File scripts/release-check.ps1` → Gradle build/lint/tests, artifact
      signatures/certificate, store assets and screenshots all pass and `build/release-check.json` is
      written; add `-SkipBuild -SkipEmulator` to re-verify an existing build.
- [ ] CI on the pushed commit is green (`.github/workflows/ci.yml`: jvm-tests, arabic-eval, lint).

## 2. Build and verify the artifacts

```powershell
# signed release APK (needs keystore.properties) and Play bundle
./gradlew assembleLibreRelease bundlePlayRelease

# signature, identity and contents; the release gate wraps all of this
pwsh -File scripts/release-check.ps1
apksigner verify --print-certs app/build/outputs/apk/libre/release/app-libre-release.apk
aapt2 dump badging app/build/outputs/apk/libre/release/app-libre-release.apk
jarsigner -verify app/build/outputs/bundle/playRelease/app-play-release.aab
```

- [x] `versionCode` / `versionName` bumped in exactly one place (`app/build.gradle.kts`).
- [x] `NOTICE`, `sbom.cdx.json` and the licence tests agree (`LicenceRegistryAssetTest`).
- [x] Record size + SHA-256 of both artifacts in `store/README.md` (P3.8 rebuild: APK 145,711,750 B /
      `FD94EA31…`; AAB 64,938,455 B / `9326F505…`; certificate `4537ECEB…` unchanged). The rebuild wired
      the hosted policy URLs into app.json, so Settings -> Privacy policy / Security disclosure open the
      published pages.)
- [x] Install the signed release on a clean emulator and walk §4.
- [ ] Keep `keystore/` and `keystore.properties` out of the repository and backed up offline.

## 3. Publishing steps (accounts, outside the repo)

### F-Droid

1. Publish the source repository and tag the release commit.
2. Fill every `TODO-` in `fdroid/metadata/app.sirrak.yml` (SourceCode, IssueTracker, Changelog, Repo,
   commit) and copy it to a fork of `fdroiddata` as `metadata/app.sirrak.yml`.
3. Run `fdroid lint app.sirrak` and `fdroid build app.sirrak:<versionCode>` with the fdroiddata tooling.
4. Open the merge request; F-Droid signs the APK with its own key. Keep the `NonFreeNet` anti-feature
   for the Hugging Face download — it is honest and expected for this app.

### Google Play

1. Register the upload key / Play App Signing for the `play` AAB.
2. App content: Data safety answers from `store/play/data-safety.md`; content rating questionnaire
   (no user-generated sharing, on-device AI client); privacy policy URL (see §5).
3. Store listing: copy `store/listing/ar/*` as the primary locale, `store/listing/en/*` as English;
   upload the eight screenshots in `store/screenshots/` (Arabic first), plus the generated
   `store/graphics/icon-512.png` (512×512) and `store/graphics/feature-graphic-1024x500.png`
   (1024×500). Sizes/hashes and provenance are in `store/graphics/manifest.json` + `README.md`;
   `scripts/validate-store.ps1` is the pre-upload check.
4. Closed testing track first: install from Play, verify the language switch still works (the AAB
   disables language splits for exactly this reason), then promote.

## 4. Device acceptance walk (signed release on the AVD)

- [ ] Fresh install: onboarding appears, privacy step reads correctly, skip and model install paths work.
- [ ] Model download: permission prompt context, progress notification, background continues, cancel
      deletes the partial file, completion verifies the checksum and loads the model.
- [ ] Chat: streaming answer, regenerate, copy, conversation history search/rename/delete, report action.
- [ ] Documents: import PDF/TXT, index locally, ask with the documents scope, citation opens.
- [ ] Dictation: mic permission rationale, live meter, Arabic + English transcription.
- [ ] Vision: attach camera/gallery image, answer describes it (when a projector is installed).
- [ ] Privacy: network log records the download only, offline-only blocks a download, reports are local.
- [ ] Settings: five theme packs × light/dark, text size 200 %, language switch (Arabic ↔ English),
      About shows `1.0.0-rc1`, the correct distribution channel and 23 licensed components.
- [ ] Accessibility: TalkBack pass over home/settings/composer, 48 dp targets, no clipped text at 200 %.

## 5. Documentation to host before publishing

- [ ] Publish `PRIVACY.md` and `SECURITY.md`, then fill `privacy.policyUrl` / `privacy.securityUrl` in
      `app/src/main/assets/config/app.json`; the About screen then shows real links instead of
      "not published yet".
- [ ] Fill `privacy.reportEmail` / `privacy.reportIssueUrl` if AI-output reports should offer email
      instead of only the system share sheet.
- [ ] Keep `README.md` (build/run), `PRODUCT.md` (features), `DESIGN.md` (UI system), `sbom.cdx.json`
      and `docs/06-roadmap.md` (status) in step with the release.

## 6. Known gaps that are disclosed, not hidden

- No physical-device validation yet (F8); all device evidence is the API 36 emulator.
- Phone WER and real-arm64 performance are unmeasured; only emulator x86_64 numbers exist.
- No ABI splits: the self-hosted APK is universal (arm64-v8a + x86_64, ~145.7 MB); Play delivery splits.
- APK is v2-signed only; v3 key-rotation is not enabled.
- A custom baseline profile and macrobenchmark suite are not authored; the shipped profile is
  dependency-derived.
- The APK downloads models from Hugging Face; F-Droid declares `NonFreeNet`. Fully offline use
  requires a locally imported GGUF file.
- Move-model, gated-token persistence, and multi-ABI packaging remain future work.
- **SBOM scope:** `sbom.cdx.json` inventories the 23 licence-registry entries (top-level bundled
  components), not the full transitive Maven graph; it is CycloneDX 1.5 JSON only.
- **Store graphics:** generated static assets (headless Chrome, recorded renderer version), not
  device-rendered; Play still masks the icon and may crop the feature graphic; the hero is Arabic-first
  and the English listing reuses it.
- **Validators check structure, not quality:** limits, names, hashes, documents and screenshot bounds
  are enforced; translation/marketing review stays human.

## 7. Go / no-go

Cut `1.0.0` only when §1 is fully green (including `pwsh -File scripts/release-check.ps1` on a
rebuilt signed release — APK/AAB hashes re-recorded), §2 artifacts are recorded, §3 F-Droid metadata is
accepted locally and the Play closed test installs, §4 passes on a clean install, and §5 has at least
the privacy policy hosted. Otherwise ship `1.0.0-rcN` and keep the gaps in §6 explicit.

## P3.8 - public repository and launch surface (2026-11-23)

Publication record for `1.0.0-rc1`, completed on 2026-11-23 (+03:00). A checked item below is proven by a
committed file in this tree; the repository, Pages, CI and release rows are additionally proven by the live
evidence recorded next to them (GitHub run ids and public HTTP responses, measured after the first push).

- [x] root Apache-2.0 LICENSE, NOTICE, public README (EN) + README.ar.md, CONTRIBUTING, CODE_OF_CONDUCT, SUPPORT, CHANGELOG, CITATION.cff, issue/PR templates, .editorconfig, .gitattributes
- [x] hosted bilingual site with privacy/security pages; app config wired to https://alshahia.github.io/sirrak/privacy/ and /security/
- [x] F-Droid metadata completed (version/commit/source URLs) + documented prebuilt sherpa-onnx AAR limitation; submission not made yet
- [x] store listing copy refreshed for GitHub-only distribution
- [x] GitHub repository created, pushed and verified: <https://github.com/alshahia/sirrak> — public, Apache-2.0 detected, default branch `main`, description/topics/homepage set, secret scanning + push protection + Dependabot alerts enabled, branch protection on `main` with strict required checks (JVM tests, Arabic eval, lint, public site; linear history). Commits: `94aa16a` (P3.8 tree) and `e8125f6` (Linux CI fixes).
- [x] GitHub Pages enabled (`build_type=workflow`, HTTPS enforced) and live URLs verified on 2026-11-23 13:55 +03:00 — `/`, `/ar/`, `/privacy/`, `/security/`, `/ar/privacy/`, `/ar/security/`, `/sitemap.xml`, `/robots.txt`, `/llms.txt`, `/manifest.webmanifest`, `/assets/og-image.png` all return 200; EN `lang="en" dir="ltr"`, AR `lang="ar" dir="rtl"`; canonical, hreflang, Open Graph image and JSON-LD present (SoftwareApplication on the landing page, PrivacyPolicy and WebPage on the policy pages); sitemap lists all six pages; robots.txt points at the sitemap; the committed bilingual 404 page is served for unknown paths. Pages run `45159519451` green (build → validate → deploy).
- [x] Release `v1.0.0-rc1` created with the signed `app-libre-release.apk` (145,711,750 B, SHA-256 `FD94EA31…`) and `app-play-release.aab` (64,938,455 B, SHA-256 `9326F505…`) attached, plus `SHA256SUMS`; certificate `4537ECEB…6806` — <https://github.com/alshahia/sirrak/releases/tag/v1.0.0-rc1>.
- [x] CI green on `main` (run `45159519429`): 567 JVM tests, Arabic eval 24/24, lint 0 errors, and the public-site job (build + 225/225 validation + committed-output drift) — all four status checks required by branch protection. Every later docs/validator commit re-ran the same four jobs green.
- [x] Public-site drift guard: `app/src/test/java/app/sirrak/WebsiteContentTest.kt` (7 JVM tests) keeps the generated site, the brand source, the shipped app config and the hosted policy copy in sync, so a data edit without a rebuild fails CI.
- [x] Published-facts validator: `scripts/validate-repo.ps1` (expectations in `store/repo-spec.json`, authenticated `gh` + network) proves the account-side state an offline check cannot see — description keywords/topics/homepage/license, Pages build type + HTTPS, secret scanning + push protection + Dependabot alerts, branch protection (strict four required checks, linear history, no force pushes/deletions), release assets with GitHub-computed SHA-256 digests matching `store/store-spec.json`, and all 11 live URLs returning 200 — **36/36 checks pass** at HEAD (the live-URL block is skipped only with `-SkipLive`). `release-check.ps1` also fails when a rebuilt artifact drifts from the recorded size/SHA-256.
- [x] Live limits recorded: Google Search Console / Bing Webmaster verification and the GitHub social-preview image are account-side settings that were not done; F-Droid and Play remain unpublished; Pages cannot set custom security headers and `robots.txt` lives under the project subpath.
