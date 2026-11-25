# 27 — Sirrak launch checklist (P3.7)

The concrete, ordered list that turns the repository into a published launch candidate. Each item
names the command or file that proves it. Nothing here needs a test phone (F8): the API 36
`Nawa_Phone` AVD is the device under test. `[x]` marks items proven on the P3.7 record (23 Nov 2026);
`[ ]` items still need an account, a host or a human review. The §1–§7 body is the **P3.7 baseline**
(567 tests, lint 36 warnings); the current `1.1.0` candidate's gates and artifact are in §P3.10 at the end.

## 1. Quality gates (must be green before cutting a build)

- [x] JVM unit tests: `./gradlew :app:testLibreDebugUnitTest testDebugUnitTest` → **567 tests, 0
      failures, 0 errors** (flavoured `app` + plain modules; release/stale result folders ignored).
- [x] Arabic eval: `./gradlew :core:rag:testDebugUnitTest --tests "app.sirrak.core.rag.ArabicEvalTest"`
      → **24/24** cases, printed as four `Arabic eval ... cases passed` lines.
- [x] Android lint: `./gradlew :app:lintLibreDebug` → **0 errors**, 36 accepted warnings (27 pinned
      dependency/AGP updates + 3 transitive third-party TLS warnings; see docs/26 P3.6 and §P3.9).
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
      About shows `1.0.0-rc2`, the correct distribution channel and 23 licensed components.
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
- [x] GitHub repository created, pushed and verified: <https://github.com/alshahia/sirrak> — public, Apache-2.0 detected, default branch `main`, description/topics/homepage set, secret scanning + push protection + Dependabot alerts enabled, branch protection on `main` with strict required checks (JVM tests, Arabic eval, lint, public site; linear history). Commits: `94aa16a` (P3.8 tree), `e8125f6` (Linux CI fixes), `ec78bae` (launch evidence in docs), `11faf01` (JVM test floor raised to 567) and `2b97f45` (published-facts validator + recorded artifact digests); the validator commit re-ran all four CI jobs and the Pages build green (CI `45162411487`, Pages `45162411708`).
- [x] GitHub Pages enabled (`build_type=workflow`, HTTPS enforced) and live URLs verified on 2026-11-23 13:55 +03:00 — `/`, `/ar/`, `/privacy/`, `/security/`, `/ar/privacy/`, `/ar/security/`, `/sitemap.xml`, `/robots.txt`, `/llms.txt`, `/manifest.webmanifest`, `/assets/og-image.png` all return 200; EN `lang="en" dir="ltr"`, AR `lang="ar" dir="rtl"`; canonical, hreflang, Open Graph image and JSON-LD present (SoftwareApplication on the landing page, PrivacyPolicy and WebPage on the policy pages); sitemap lists all six pages; robots.txt points at the sitemap; the committed bilingual 404 page is served for unknown paths. Pages run `45159519451` green (build → validate → deploy).
- [x] Release `v1.0.0-rc1` created with the signed `app-libre-release.apk` (145,711,750 B, SHA-256 `FD94EA31…`) and `app-play-release.aab` (64,938,455 B, SHA-256 `9326F505…`) attached, plus `SHA256SUMS`; certificate `4537ECEB…6806` — <https://github.com/alshahia/sirrak/releases/tag/v1.0.0-rc1>.
- [x] CI green on `main` (run `45159519429`): 567 JVM tests, Arabic eval 24/24, lint 0 errors, and the public-site job (build + 225/225 validation + committed-output drift) — all four status checks required by branch protection. Every later docs/validator commit re-ran the same four jobs green.
- [x] Public-site drift guard: `app/src/test/java/app/sirrak/WebsiteContentTest.kt` (7 JVM tests) keeps the generated site, the brand source, the shipped app config and the hosted policy copy in sync, so a data edit without a rebuild fails CI.
- [x] Published-facts validator: `scripts/validate-repo.ps1` (expectations in `store/repo-spec.json`, authenticated `gh` + network) proves the account-side state an offline check cannot see — description keywords/topics/homepage/license, Pages build type + HTTPS, secret scanning + push protection + Dependabot alerts, branch protection (strict four required checks, linear history, no force pushes/deletions), release assets with GitHub-computed SHA-256 digests matching `store/store-spec.json`, and all 11 live URLs returning 200 — **36/36 checks pass** at HEAD (the live-URL block is skipped only with `-SkipLive`). `release-check.ps1` also fails when a rebuilt artifact drifts from the recorded size/SHA-256.
- [x] Live limits recorded: Google Search Console / Bing Webmaster verification and the GitHub social-preview image are account-side settings that were not done; F-Droid and Play remain unpublished; Pages cannot set custom security headers and `robots.txt` lives under the project subpath.

## P3.9 — `1.0.0-rc2` refresh (2026-11-24)

Publication record for `1.0.0-rc2` (versionCode 13), cut on 2026-11-24 (+03:00) so the public surfaces can
truthfully describe the shipped build after two post-rc1 source changes landed: the Qiyas-reviewed Arabic
terminology normalization ([docs/30](30-arabic-terminology.md)) and the compact 2 GB catalogue tier
(LFM2.5 350M Instruct, [docs/29](29-small-models-council.md)). The tree is commit `3b63f1f`; rc1's tag,
artifacts, screenshots and record were left untouched.

- [x] Version facts: `versionCode 13` / `versionName 1.0.0-rc2` in `app/build.gradle.kts`; the About screen, NOTICE, SBOM, F-Droid metadata, repo spec, templates and both READMEs read rc2.
- [x] Signed artifacts rebuilt and re-recorded: `app-libre-release.apk` **145,712,378 B (138.96 MiB)**, SHA-256 `0B53BD7157CA9CC1A2D9DE42ADD44FE46F58D42C908ABB0E7E40E32B23693182`; `app-play-release.aab` **64,938,759 B (61.93 MiB)**, SHA-256 `BCB25473BEFF23E2EE3AC5E45C570A6C97C92B4A8331FAC70DF5A266C9997FCC`; APK v2-signed with certificate `4537ECEB…6806`, AAB jarsigner-verified. Sizes and digests recorded in `store/store-spec.json` and `store/README.md`.
- [x] Eight store screenshots recaptured from the signed rc2 `libre` release installed on the API 36 `Nawa_Phone` emulator (720×1280, density 320) on 2026-11-24, replacing the rc1 files under the same names; provenance in `store/screenshots/README.md`. The rc2 set shows the fixed wording (the no-model chip, the licences sheet, the privacy log) and the compact-tier catalogue row.
- [x] UI regression baseline refreshed from a clean launch of the same signed build (4 screens, dHash `scripts/ui-baseline/`); provenance in `scripts/README.md`. Comparison passes at distance 0.
- [x] Store listing updated: `whats_new.txt` EN 492 / AR 479 characters (Play limit 500), screenshot manifest and repo spec carry rc2.
- [x] Bilingual site regenerated from the data files: `1.0.0-rc2` / release date 2026-11-24 in `website/site.json`, content and policies (`policies.json.updated` = 2026-11-24); OG card re-rendered at 1200×630 from the new home screenshot; `build-site.ps1` clean, `validate-site.ps1` **225/225** at HEAD.
- [x] `scripts/release-check.ps1` passes over the signed artifacts: Gradle release build + lint + full JVM suite verified in the full run, then the complete report re-produced with `-SkipBuild` against a rebooted AVD (the emulator process died after the Gradle step) — **567 tests, 0 failures, 0 errors**, **lint 0 errors / 36 reviewed warnings**, both artifacts size/SHA/certificate-verified, `validate-store.ps1` **79/79**, site **225/225**, screenshot regression **4/4 at distance 0**. Report written to `build/release-check.json` (not committed).
- [x] Release `v1.0.0-rc2` published with the signed APK/AAB and `SHA256SUMS`; GitHub-computed asset digests match `store/store-spec.json` — <https://github.com/alshahia/sirrak/releases/tag/v1.0.0-rc2>.
- [x] CI green on `3b63f1f` (run `45373203824`): JVM unit tests, Arabic eval 24/24, Android lint and the public-site job (build + 225/225 + committed-output drift) all success; Pages deployment run `45373203860` success.
- [x] Live surface verified after deployment: `validate-repo.ps1` **36/36** (repository facts, Pages, secret scanning, branch protection, release digests, all 11 live URLs 200); the live EN and AR landing pages serve `1.0.0-rc2` and the rc2 evidence rows.
- [x] Limits unchanged and still explicit in §6: F-Droid and Play remain unpublished, performance is emulator-measured only, and no test phone was used (F8) — the rc2 device evidence is the emulator, as recorded above.

## P3.10 — `1.1.0` UX rework candidate (2026-11-25)

Record for the `1.1.0` candidate (versionCode 14), built on 2026-11-25 (+03:00) from the UX rework in
[docs/31](31-ux-rework-council.md) / [docs/32](32-ux-rework-build-record.md). This is a **candidate**, not a
signed release: this checkout contains no release keystore (`keystore.properties` / `*.jks` are absent), so the
validated artifact is a debug-signed universal APK. The signed `1.0.0-rc2` release remains the last published
build, and its tag, artifacts and record are untouched.

- [x] Version facts: `versionCode 14` / `versionName 1.1.0` in `app/build.gradle.kts`; About, NOTICE,
      `sbom.cdx.json`, What's new, the site, `store/store-spec.json` floor and both READMEs read 1.1.0; the
      licence registry still renders 23 entries (22 CycloneDX components + the app subject) and
      `LicenceRegistryAssetTest` is green.
- [x] UX rework delivered in the approved P1→P6 order: honest four-state chip that routes to a model (P1);
      three-segment Library with a durable single-transfer download queue (P2); in-app Hugging Face search +
      bounded GGUF probe + RAM/disk fit verdict (P3); six Settings doors with live summaries (P4); Media V1 —
      documents / images / audio-to-on-device-transcript with keep-or-once and per-item agent access (P5);
      no download-manager UI; hints without removing controls. Deviations are itemised in docs/32.
- [x] JVM suite: `./gradlew :app:testLibreDebugUnitTest testDebugUnitTest` → **744 tests, 0 failures,
      0 errors** across 75 XML suites in 10 module result folders (`:app` contributes 253 tests / 26 suites).
- [x] Arabic eval: **24/24** cases inside the suite (normalisation, sentence splitting, BM25, negative controls).
- [x] Android lint: `./gradlew :app:lintLibreDebug` → **0 errors, 39 reviewed warnings** (30 pinned
      dependency updates, 6 AGP version warnings, 3 transitive third-party TLS warnings).
- [x] Configuration test: `privacy.endpoints` declares the three real network features — `model_download`,
      `hf_search`, `hf_probe` — and `AppConfigTest` asserts exactly that set.
- [x] Store assets: `pwsh -File scripts/validate-store.ps1` → **79/79 checks**, version 1.1.0 present in
      What's new (EN 491 / AR 483 characters, Play limit 500; the first line names 1.1.0 as a UX rework
      rather than claiming a signed APK already exists, because the signed 1.1.0 release is still pending).
- [x] Bilingual site: `website/site.json` version 1.1.0 / release date 2026-11-25, policies updated to
      policy version 2; `build-site.ps1` clean and `validate-site.ps1` **225/225**; `WebsiteContentTest`
      (7 tests, forced past the build cache) keeps the app-side facts in sync. The published page labels
      1.1.0 as a **candidate**, names signed `1.0.0-rc2` as the downloadable build and states that the
      signed 1.1.0 release is pending; the hero names all three user-started network features. The first
      rework push (5b344f2) deployed it to Pages; CI and Pages were green (runs 45551546176 / 45551546067).
- [x] Candidate artifact: `app-libre-debug.apk`, **223,217,333 B (212.9 MiB)**, SHA-256
      `4CD65A603A32E829032DBAB5FD390F73FA201FA15F97CEAA75F3E9F4568D539A`, versionCode 14, minSdk 28,
      universal `arm64-v8a` + `x86_64` with all seven native libraries per ABI (including
      `libsirrak_llm.so`), built with `:app:assembleLibreDebug -PsirrakArm64Debug=true`. Signed with the
      Android debug key — not installable as an update over the signed rc2 build (different certificate).
- [x] Screenshot regression: baseline refreshed from a clean launch of candidate `3BDC4213…192E` on the
      API 36 `Nawa_Phone` AVD (720×1280) — home, model doorway, empty Library and Settings root compare at
      distance 0 (**4/4**); the chip tap in `scripts/ui-regression.ps1` moved to `0.64 0.166` because the
      chip left the composer. The final artifact `4CD6…539A` re-ran the plain read-only compare against that
      baseline: **4/4 at distance 0** again.
- [x] Eight store screenshots (720×1280): home empty state, chat answer, Library → models, background
      download notification, privacy network log, licences, dictation recording strip (listening state,
      timer, stop/cancel), and the six-door Settings root in English. `05`/`08` were recaptured on
      `3BDC4213…192E` after copy corrections; the final artifact differs only in two in-app network
      sentences that appear in no shot. Split provenance in `store/screenshots/README.md`.
- [x] Final copy audit after the capture pass: fixed the privacy footer and endpoint line, the Advanced door
      summary ("semantic search not set up" instead of "not imported"), the first-run privacy slide and the
      model-section note, so no in-app sentence claims the network is used only for model downloads. The
      onboarding slide was checked in Arabic and English on the final artifact (no clipping, Next/Back/Skip
      pass). README, store listing and site copy no longer claim a "live level meter": the recording strip
      shows the listening state and a timer. `validate-store.ps1` **79/79** and `validate-site.ps1`
      **225/225** were re-run on the final tree.
- [ ] Signed `1.1.0` APK/AAB and their recorded hashes — **blocked on the release keystore**; re-run
      `scripts/release-check.ps1` and re-record `store/store-spec.json` / `store/README.md` where the
      keystore lives. `release-check.ps1` is expected to fail here because its artifact digests are rc2's.
- [ ] Physical-phone smoke: **not run** — the Redmi Note 7 (`ef008a2`, Android 10 / API 29, arm64-v8a) was
      not attached to adb during this session, so all device evidence is the API 36 emulator. When attached,
      install this APK and run the small-model path: Qwen2.5-0.5B-Instruct Q4_K_M (~794 MB, fits the phone's
      ~3.7 GB RAM) — download or adb-push it, load, ask one Arabic and one English question, dictate once,
      import one PDF, and confirm offline-only blocks the network.
- [x] CI / pushed tree / live Pages: commit 5b344f2 pushed to `origin/main`; GitHub Actions CI (JVM
      tests, Arabic eval, Android lint) and the Pages workflow both passed (45551546176 / 45551546067).
      The live site is served at `https://ahmadmhmdsy.github.io/sirrak/`; the project Pages host used by
      older links (`alshahia.github.io/sirrak`) no longer resolves and `siteUrl`/`repoUrl` still name the
      old owner path — a URL-migration decision, recorded as an open item rather than a build gate.
- [x] Docs and data aligned to 1.1.0: docs/31, docs/32, PRODUCT.md, CHANGELOG.md, README.md and
      README.ar.md, PRIVACY.md (policy version 2), SECURITY.md, website policies, store copy, NOTICE, SBOM,
      store/README candidate-artifact table and the docs index.

**Known gaps carried into this candidate** (also in docs/32 and README): media is V1 (once-only items are
deleted at app close because chat cannot reference shelf media; images are view-only; no OCR; audio is not
played from chat); HF rows cannot show per-file parameters/quantisation and an unprobed file stays `UNKNOWN`;
downloads run one at a time; gated HF repos need a token that is held in memory only; settings search was not
shipped; the APK is debug-signed and has no signing provenance.
