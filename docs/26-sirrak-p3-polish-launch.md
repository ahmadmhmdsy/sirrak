# 26 — Sirrak P3 polish & launch record

Phase P3 turns the M1–M11 build into a launch candidate. This document is the evidence log: every
increment records the design decision, the tests that prove it, and the device/build evidence. The
phase goal was `goal-0e8a68e5-cbf7-4b1f-8d8c-01a7d0ccb8d5` (P3.1 → P3.6) and closed; P3.7 ran under
`goal-7f3fa175-0c96-4cd8-851a-1d13f6c19a31` (P3.1 → P3.7).

Ground rules carried from M1–M11: no hardcoding (everything data-driven or injected), the app must keep
working at every step, every claim is backed by a test or a captured emulator screenshot, and the build
stays offline/Apache-2.0/no-telemetry.

## P3.1 — Privacy surfaces (PRV-2 audit, PRV-5 offline-only, PRV-6 AI reports)

**Status: complete** (22 Nov 2026).

### What the user gets

1. **Network audit (PRV-2).** Settings → *Privacy & data* → *Network log* lists the endpoints the app
   declares (read from `app.json`, not from code) and the calls that actually happened on this device:
   host, local time, outcome, bytes. The log is bounded to the 50 newest calls, stored in
   SharedPreferences JSON, newest-first, and never leaves the device. A clear action empties it.
2. **Offline-only switch (PRV-5).** One switch stops every new network request and cancels a running
   download. The block sits in `AndroidNetworkPolicy.blockedReason()`, i.e. *before* the Wi-Fi gate and
   before the transport opens a socket, so it cannot be bypassed by a metered/unmetered decision. The
   switch persists across restarts and is off by default.
3. **Report an AI answer (PRV-6 / Play AI policy).** Every assistant answer gains a flag action inside
   the existing answer-actions row (no second toolbar). The dialog offers six reasons, an optional note
   (500 chars), and stores an excerpt of the answer (2000 chars max) locally first — it works offline
   and queues up to 50 reports. Sharing is an explicit second step (explicit `ACTION_SEND` chooser, or
   `mailto:` when the config publishes a report address) and the shared text is self-contained and
   inspectable. Nothing is ever sent automatically.

### Architecture (Cordis-shaped, reusable by construction)

| Layer | Module | Contents |
| --- | --- | --- |
| Pure JVM core | `:core:privacy` (new) | `NetworkAudit` + `NetworkAuditStore`, `OfflineModeStore` + `InMemoryOfflineModeStore`, `AiReport`/`AiReportQueue`/`AiReportRules`/`AiReportStore`. No Android imports, seams injected. |
| UI feature | `:feature:privacy` (new) | `PrivacyPlugin` installs two slot contributions: `SettingsSections` (order 25) → `PrivacySection`, and `ChatAnswerActions` (order 10) → `ReportAnswerAction`. Sheets: `NetworkAuditSheet`, `AiReportsSheet`. Sharing in `ReportSharing.kt`. |
| Host wiring | `:app` | `SharedPrefsOfflineModeStore`, `SharedPrefsNetworkAuditStore`, `SharedPrefsAiReportStore` (versioned keys in `sirrak_privacy`), `AndroidNetworkPolicy` offline gate, `HttpDownloadTransport` audit instrumentation, services provided in `AppComposition`. |
| Config | `app.json` | `privacy` block: `offlineByDefault`, `policyUrl`, `securityUrl`, `reportEmail`, `reportIssueUrl`, `endpoints[]` (`id`, `host`, `purpose`, `whenUsed`, `userInitiated`, `purposeKey`). Validated by `validatePrivacy` (https-only URLs, host shape, duplicate/blank ids, text limits). |

Why the split: the audit/report rules are pure logic, so they are unit-tested on the JVM without an
emulator; the Android persistence and the real transport are exercised by device tests; the UI plugin
is a normal slot contribution, so a future build can disable or reorder it from `app.json` without
touching code.

Slot addition: `Slots.ChatAnswerActions` (list, owner `feature:chat`) + `ChatAnswerContext` /
`LocalChatAnswerContext` CompositionLocal + `AnswerActions(..., extraActions)` trailing lambda, so a
contributed action lands inside the same icon row as copy/regenerate and has the answer it belongs to.

### Tests

- `:core:privacy` — 18 JVM tests: `NetworkAuditTest` (8: newest-first, bound, persistence calls,
  clear, blank validation, outcome/bytes) and `AiReportTest` (10: excerpt/note bounds, queue order,
  capacity rejection, empty answer rejection, id collision, remove/clear, share text).
- `:core:models` — 41 tests, including the new
  `blocksBeforeTheTransportWhenTheUserChoseOfflineOnly` (policy wins over the Wi-Fi gate) and the
  `OFFLINE_MODE` reason path.
- `:app` — 161 JVM tests, including 7 new `AppConfigTest` privacy checks (defaults, JSON parse,
  duplicate endpoint ids, host shape, https-only links, report email shape) and the shipped-config
  assertion (single `model_download` → `huggingface.co`).
- Device (`:app` androidTest) — new `PrivacyDeviceTest` (4 tests, loopback only):
  transport records a completed call (host parsed from URL, bytes, persisted immediately); a 404 is
  recorded as FAILED; offline-only blocks `AndroidNetworkPolicy` and survives a fresh store instance;
  audit and report stores round-trip through SharedPreferences and share text is self-contained.

### Device evidence (emulator `Nawa_Phone`, API 36 x86_64)

`docs/screenshots/`:

| File | Shows |
| --- | --- |
| `p31-home.png`, `p31-home2.png` | app still working after the privacy build |
| `p31-settings.png`, `p31-privacy.png`, `p31-privacy*.png` | Privacy & data section in the settings slot order (after models/rag, before voice/about) |
| `p31-offline-on.png` | offline-only switch on (lapis track) |
| `p31-offline-block.png` | model download refused before any socket: "Offline-only mode is on; turn it off in Privacy & data before downloading." |
| `p31-audit.png`, `p31-audit2.png` | network log sheet: declared endpoint (`huggingface.co`), purpose, "user-initiated" chip, recorded-calls empty state |
| `p31-report-dialog.png` | report dialog with six reasons + optional note |
| `p31-report-saved.png` | saved-on-device state with explicit "Share report" |
| `p31-report-share.png` | Android chooser with the self-contained report text (id, created, reason, model, excerpt) |
| `p31-reports-sheet.png`, `p31-reports-sheet2.png` | reports sheet with count, reason, timestamp, model, excerpt; second capture after the bidi fix |

Bidi fix found by looking at the first capture: in an RTL screen an English answer excerpt had its full
stop reordered to the front. The excerpt/model labels now use `TextDirection.Content`, so direction
follows the first strong character (English stays LTR, Arabic stays RTL) instead of the screen locale.

### Build record

- `:app:assembleDebug` and `:app:installDebug` green after adding `:core:privacy` + `:feature:privacy`.
- Composition log at launch: `Installed plugins: [library, engine, about, chat, settings, asr, voice,
  models, privacy, rag]` — privacy installs, no crash, no ordering regression.
- JVM suite before the phase: 529 tests / 0 failures (P3.1 counts above).
- Device suite: `:app:connectedDebugAndroidTest` → **40 tests, 0 failures, 0 skipped** (6m 43s) on
  `Nawa_Phone(AVD) - 16`, including the 4 new `PrivacyDeviceTest` tests. The suite uninstalls the app
  afterwards (expected; onboarding state was re-created for the P3.2 captures).

### Honest limits (P3.1)

- Policy and security links show "not published yet" until P3.2 publishes the drafts and fills
  `privacy.policyUrl` / `privacy.securityUrl`.
- The audit records the model-download transport (the only network path the app has today); a future
  endpoint must be instrumented the same way, which is why the endpoint registry is data-driven.
- The audit is a device log, not a remote telemetry feed — by design there is no server to send it to.
- Reports are shared through the Android chooser; the app does not upload them.

## P3.2 — licence/SBOM registry, NOTICE, policy drafts

**Status: complete** (22 Nov 2026).

### What the user gets

Settings → About now has a **Licences and credits** row that shows the real count (`23 components` on
this build) and opens a grouped sheet: App, Frameworks, Libraries, Inference engines, Fonts, Models,
Test tools. Each row shows name, version, SPDX licence, a Bundled / Downloaded-separately badge, and
copyright. Tapping a bundled component opens its **full licence text from inside the APK, offline**
(Apache-2.0, MIT, and the two font OFL texts are bundled); components without bundled text offer the
canonical https link. Models are listed as downloaded separately with their licence shown before
download — the honest statement replaces the old hardcoded four-row list.

### Architecture (one data source, three consumers)

- `:core:runtime/Licences.kt` — pure data + rules: `LicenceEntry`, `LicenceKind`, `LicenceRegistry`,
  `LicenceRules.validate/bundledByKind/externalEntries/noticeText`. No Android imports.
- `assets/config/licences.json` — 23 entries (app, Kotlin/AndroidX/Compose, llama.cpp, sherpa-onnx,
  LiteRT-LM, PDFBox-Android, Amiri, IBM Plex Sans Arabic, four model families, test tools). The asset
  path is declared in `app.json -> licences.assetPath` and validated (`AppConfigLoader.validate`).
- `assets/licenses/` — bundled full texts: `Apache-2.0.txt`, `MIT.txt`, `OFL-Amiri.txt`,
  `OFL-IBM-Plex.txt` (fetched from the canonical upstreams, not retyped).
- `:app/AssetLicenceRegistry` — strict JSON loader (fails loudly in tests, degrades to an honest empty
  state in release with a log), provided as the `LicenceRegistry` service in `AppComposition`.
- `:app/AboutPlugin` — now `requires(LicenceRegistry)` and renders the data; `AboutLicences.kt` holds
  the grouped sheet and the offline full-text view. No licence row is hardcoded anymore.
- `NOTICE` (repo root) — generated from the same registry by `LicenceRules.noticeText`; the unit test
  compares it byte-for-byte and writes `build/reports/notice-expected.txt` when it drifts.
- `PRIVACY.md` / `SECURITY.md` — publication-ready drafts. The app keeps `policyUrl`/`securityUrl`
  empty until they are hosted, so the UI says "not published yet" instead of showing a dead link.

### Tests

- `:core:runtime` — new `LicencesTest` (6): valid registry has no problems; duplicate ids/blank
  fields/unknown kinds/escaping asset paths rejected; empty registry rejected; kind grouping order;
  deterministic NOTICE text; unknown kind falls back to `LIBRARY` for forward compatibility.
- `:app` — new `LicenceRegistryAssetTest` (4): shipped asset parses and validates (≥20 entries, app +
  engines + fonts + models present, at least one external model, config points at the asset); every
  bundled `textAsset` exists inside `src/main/assets` and is non-trivial; root `NOTICE` equals
  `LicenceRules.noticeText` for the current `BuildConfig.VERSION_NAME`; every entry appears in NOTICE.

### Device evidence

`docs/screenshots/p32-licences.png` (grouped sheet, 23 components), `p32-licences2.png` (same after
re-layout), `p32-licence-text2.png`/`p32-licence-text3.png` (Apache-2.0 text opened from the APK with
the screen offline). Two bidi fixes came from looking at these captures: English licence prose is now
`TextDirection.Ltr`/full-width, so an RTL screen no longer reverses its URLs or wraps its header lines.

### Honest limits

- The registry lists top-level components, not every transitive Maven artifact; a full generated SBOM
  (e.g. CycloneDX from the resolved dependency graph) is a P3.5 release-engineering follow-up.
- Model licences are shown in the catalogue before download and listed here as external entries; the
  full Llama/Gemma texts are not bundled (they are long and change independently).
- Policy/security URLs stay unpublished until hosting exists; drafts are in the repo.


## P3.3 — Accessibility, RTL and touch-target audit (A11Y-1..4)

**Status: complete** (22 Nov 2026).

### What was audited and fixed

1. **Lint accessibility checks.** Android Lint ran for the seven modules that carry UI; there is no
   `ContentDescription`, `ClickableViewAccessibility`, `KeyboardInaccessibleWidget`, `LabelFor`,
   `RtlHardcoded` or `RtlEnabled` finding. Remaining lint findings are dependency/plural/dash/unused
   resource warnings deferred to P3.6; none is accessibility.
2. **Touch targets (≥48 dp without growing the visual).** Custom clickables that were only as tall as
   their text now own a real hit target while keeping the same look:
   - `ModelChip` (chat composer): the visual pill is unchanged; the outer `Box` adds
     `minimumInteractiveComponentSize()` + `clickable(role = Role.Button)`.
   - Documents scope chip (`RagUi.kt`): `heightIn(min = 48.dp)` + `Role.Button`, so the row is
     reachable on the composer strip.
   - `SettingsRow`, `SuggestionCard`, `LicenceRow`, `ConversationRow`: each clickable surface now
     declares `role = Role.Button`, so TalkBack announces it as a button and not as plain text.
3. **Icon semantics.** Every icon-only control has a resource content description: conversation
   history ("فتح قائمة المحادثات"), attach ("إرفاق"), dictation start ("بدء الإملاء"), send ("إرسال").
   Bottom-nav icons keep `contentDescription = null` because each has a visible label — the correct
   pattern, since a screen reader otherwise reads the label twice. The documents chip icon uses
   `Icons.AutoMirrored.Outlined.LibraryBooks`, so it mirrors in RTL.
4. **Font scale 2.0 sweep.** Home, Settings, Privacy & data, About and the licences sheet were captured
   at 200 % in-app/display scale: titles, rows, notes and list content all wrap and stay readable; only
   the normal scroll-viewport clipping remains (content continues below the fold). At that scale the
   uiautomator dump truncates large hierarchies, so verification used screenshots.
5. **RTL / LTR parity.** Arabic RTL and English LTR captures of the same screens show mirrored layout
   with equal hierarchy and no clipped Latin model names or licence URLs.
6. **Contrast.** The existing `core:design` contrast gate is a unit test over every role of every theme
   pack (5 packs × light/dark, WCAG body 4.5:1, UI 3.0:1), so the palette cannot regress without a red
   test; it did not change in P3.

### Device evidence (emulator `Nawa_Phone`, API 36 x86_64)

`docs/screenshots/`:

| File | Shows |
| --- | --- |
| `p33-font2-home.png` | home at 200 % scale: hero, suggestions, composer all readable |
| `p33-font2-settings2.png`, `p33-font2-privacy.png` | settings rows and the privacy note wrapping at 200 % |
| `p33-about3.png`, `p33-about4.png` | About rows at 200 %, including the licences row |
| `p33-font2-licences.png` | licences sheet opened and read at 200 % |
| `p33-bottom.png` | composer + bottom navigation under the IME at large scale |
| `p33-en-settings.png`, `p33-en-chat.png`, `p33-en-lang.png` | English LTR screens for parity |
| `p33-ar-home-final.png` | Arabic RTL home after the sweep |

Semantics evidence from `uiautomator dump`: the icon buttons expose Arabic content descriptions and the
conversation-history button is announced as a button; the navigation items expose their visible labels.

### Tests

JVM suite at this increment: **539 tests / 0 failures / 0 skipped** (before the four P3.4 tests):
`:app` 165, `:core:runtime` 87, `:feature:chat` 73, `:core:rag` 62, `:core:design` 47,
`:core:models` 41, `:core:speech` 40, `:core:privacy` 18, `:engine:embeddings` 6.

### Honest limits

- Verification is on the API 36 emulator only (no test phone under F8). A physical-device TalkBack pass
  is still open; structural semantics are verified, spoken order is not.
- Lint checks code, not painted pixels; custom-drawn text contrast is covered by the JVM contrast gate
  for theme roles, not for every possible colour expression.
- No automated accessibility CI job yet; P3.6 adds the CI workflow that runs this lint configuration.

## P3.4 — targetSdk 36, edge-to-edge, baseline profile, background downloads

**Status: complete** (22 Nov 2026).

### What shipped

1. **compileSdk/targetSdk 36 on all 15 modules.** The build stays on the pinned Gradle 8.10.2 +
   AGP 8.8.0 toolchain; `android.suppressUnsupportedCompileSdk=36` is set in `gradle.properties` with a
   comment explaining that upgrading AGP mid-project would force Gradle 8.11.1 and a larger risk than the
   warning it silences. Edge-to-edge was already opted-in via `enableEdgeToEdge()`; P3.4 verifies on
   API 36 that the status bar, navigation bar and IME never overlap content.
2. **Background model downloads (foreground service).** `:core:modelsModelDownloader` stays pure JVM:
   it gained one optional seam, `onActiveChanged: (Boolean) -> Unit`, reported when a run becomes active
   (queued/downloading/verifying) or idle (paused, installed, failed, cancelled). The host implements the
   seam with `DownloadService` (`dataSync` foreground type): a low-importance notification shows the
   model name, percent and a **Cancel** action; tapping it opens the app; the service stops itself as
   soon as the downloader reports a terminal state, so it cannot outlive a transfer. No per-model code:
   any future download trigger inherits the behaviour.
   `POST_NOTIFICATIONS` is requested **contextually** when a download starts (Android 13+), never at
   launch. A denial is non-fatal — the transfer still completes, only the shade notification is hidden.
3. **Baseline profile in release.** The release APK already ships `assets/dexopt/baseline.prof`
   (4 956 B) and `baseline.profm` (662 B), aggregated by AGP/R8 from the dependency profiles; the
   `androidx.profileinstaller` dependency is present (`META-INF/androidx.profileinstaller_profileinstaller.version`),
   so the profile is installed on device at install time. No hand-written rules were invented for the
   measurement; a custom interaction profile is a later job.
4. **Startup and jank measurement (emulator, debug).** Five cold launches on the API 36 emulator:
   4 577 / 4 851 / 4 598 / 4 609 / 4 640 ms → median **4 609 ms**. A scroll pass after resetting
   `gfxinfo`: 39 frames, 16 janky (41 %), 50th 36 ms, 90th 57 ms. These are debug-build emulator numbers
   and are recorded only as a baseline for comparison; phone performance is open under F8.

### Background-download evidence (device)

While the app was in the background (launcher focused), `dumpsys activity services app.sirrak` showed:

```
ServiceRecord{... app.sirrak/.DownloadService}
  isForeground=true foregroundId=4101 types=0x00000001 foregroundNoti=Notification(channel=model_downloads ...)
```

The transfer kept running: `files/model_downloads/*.part` grew 247 637 079 → 433 451 492 B while only
the launcher was visible, then finished at exactly **436 806 912 B**, was verified, imported and loaded
(`SirrakLlama: MODEL_LOADED ms=646 ... vision=0`), and the service disappeared from `dumpsys` on its
own. The notification was expanded in the shade: `Sirrak · now | Model download | Qwen2.5 0.5B Instruct ·
29 % | Cancel`. Pressing **Cancel** removed the service, deleted the `.part` file and removed the
notification. `docs/screenshots/p34dl-*.png` capture the flow (models list, 2 % start, 56 % after
backgrounding, notification, cancel; plus `p34-home/settings/ime/licences-sheet` for edge-to-edge).

### Tests

`:core:models` — 4 new JVM tests for the activity seam: active on enqueue then idle after install;
pause reports idle and resume reports active again; cancel reports idle; a blocked run that never opens
the transport still reports idle (no stuck notification). Full JVM suite: **543 tests / 0 failures /
0 skipped** (`:app` 165, `:core:runtime` 87, `:feature:chat` 73, `:core:rag` 62, `:core:design` 47,
`:core:models` 45, `:core:speech` 40, `:core:privacy` 18, `:engine:embeddings` 6).

### Honest limits

- The foreground type is `dataSync` (the closest platform category for a user-initiated file download).
  Android 12+ only allows starting it from a foreground user action, which is the only way a download
  starts in this app; a scheduled/WorkManager download is not implemented.
- If `POST_NOTIFICATIONS` is denied the service still runs and the transfer completes, but the platform
  hides the notification from the shade (it stays visible in the task manager); there is no in-app
  fallback banner while the app is backgrounded.
- `gfxinfo`/cold-start numbers are debug-on-emulator and not a phone claim; a release-build cold start
  is captured in P3.5.
- A custom baseline profile and macrobenchmark suite are not authored; the shipped profile is the
  dependency-derived one.

## P3.5 — Release signing, store flavours, F-Droid and listing assets

**Status: complete** (23 Nov 2026).

### What shipped

1. **Release signing.** A local release keystore (`keystore/sirrak-release.jks`, RSA-4096, 10 000 days,
   alias `sirrak`, DN `CN=Sirrak, OU=Release, O=Sirrak, L=Baghdad, C=IQ`) with credentials in
   `keystore.properties`. Both are git-ignored and `keystore.properties.example` documents the shape;
   a checkout without them still builds (as an unsigned release), which is exactly what F-Droid needs.
   The signing config is conditional in `app/build.gradle.kts`, so no developer is forced to own the key
   to compile the app.
2. **Two product flavours, one source tree.** `flavorDimensions += "store"` with:

   | Flavour | Purpose | Notes |
   | --- | --- | --- |
   | `libre` | F-Droid / self-hosted APK | default build; no Play-only dependency may ever enter shared code |
   | `play` | Google Play | same source, same identity, `BuildConfig.STORE = "play"` |

   Both carry `BuildConfig.STORE`, displayed as *Distribution channel* in About: a build-time fact, not a
   hardcoded string. Version is `1.0.0-rc1` / versionCode `12` in one place (`app/build.gradle.kts`).
3. **Release artifacts built and verified.**

   | Artifact | Size | SHA-256 |
   | --- | --- | --- |
   | `app-libre-release.apk` (signed, self-hosted/F-Droid input) | 145 695 314 B (138.95 MiB) | `7BA254AA90115EE79C360013968F2DEB315B8BC9008C89534F9FE7B4F97DED2D` |
   | `app-play-release.aab` (signed, Play upload) | 64 933 346 B (61.93 MiB) | `9A4880715283C813B3698AFC2D17D339D2492FB89EF3FB479B81E19D6F0F3E22` |

   `apksigner verify` passes with the v2 scheme and shows certificate SHA-256
   `4537ECEB9952AC9020EFFCEB8B50CB10AEB795772792D632CFC3D0B9F7106806`; the AAB passes
   `jarsigner -verify`. Release manifest facts from `aapt2 dump badging/xmltree`: `app.sirrak`,
   versionCode 12, versionName `1.0.0-rc1`, minSdk 28, targetSdk 36, launcher `MainActivity`, ABIs
   `arm64-v8a` + `x86_64`, label `سِرِّك`, permissions INTERNET / ACCESS_NETWORK_STATE / RECORD_AUDIO /
   FOREGROUND_SERVICE / FOREGROUND_SERVICE_DATA_SYNC / POST_NOTIFICATIONS, service
   `foregroundServiceType=dataSync`, `usesCleartextTraffic` absent (the debug-only cleartext override
   never reaches release). The APK still contains `assets/dexopt/baseline.prof` + `baseline.profm`,
   7 native libraries and both config assets (`app.json`, `licences.json`).
4. **Release install and startup measurement (emulator, signed release).** Debug was uninstalled (signature
   differs) and the signed `libre` release installed with `adb install`: five cold launches
   **1 110 / 1 000 / 1 108 / 910 / 1 120 ms → median 1 108 ms** (`am start -W` TotalTime; P3.6 re-measured
   **1 229 ms** median on the rebuilt artifacts after a clean emulator boot), versus
   **4 609 ms** for the debug build measured in P3.4. The release composition log is identical to debug
   (`Installed plugins: [library, engine, about, chat, settings, asr, voice, models, privacy, rag]`),
   the native LLM library loads (`libsirrak_llm.so`) and the R8-shrunk UI reaches the home screen,
   Settings, Privacy & data, About and the licences sheet. `ProfileInstaller` installed the baseline
   profile on first launch, so part of the improvement is profile + R8, as expected; it is an emulator
   number, not a phone claim.
   *Final rebuild:* these two rows were refreshed by P3.6 (`assembleLibreRelease bundlePlayRelease`, 23 Nov
   2026); the P3.5 first-build numbers were 145 662 087 B / 64 900 545 B and are superseded.
5. **Release UI evidence.** About shows *Version 1.0.0-rc1*, *Distribution channel libre* and
   *Licences & credits · 23 components* (the channel row is new in this increment). Captures:
   `p35-release-home.png`, `p35-release-after-skip.png`, `p35-release-about.png`,
   `p35-release-licences.png`, `p35-release-settings-bottom.png`.
6. **F-Droid metadata.** `fdroid/metadata/app.sirrak.yml` is submission-ready: categories, license,
   English + Arabic name/summary/description, build stanza (`gradle: - libre`, versionCode 12,
   `TODO-release-tag`), and one honest anti-feature — `NonFreeNet` for the Hugging Face model download.
   `fdroid/README.md` lists the exact release steps and the two files that must never ship (keystore).
7. **Store listing assets.** `store/listing/{ar,en}/` hold name, short description (≤80 chars),
   full description and what-is-new; Arabic is the primary locale, English the translation.
   `store/play/data-safety.md` contains the Play Console answers that match the code (no collection, no
   sharing, no analytics; the only request is a user-initiated public-file download), the permissions
   table and the on-device data inventory. `store/screenshots/` holds eight real emulator captures
   (`01-home-ar` … `08-settings-en`) with a README explaining Play upload order; `store/README.md`
   records artifacts, hashes and what is still missing before publishing.

### Why this is not hardcoded

The distribution channel is a build-time `BuildConfig` field per flavour, the version lives only in
`app/build.gradle.kts`, signing is conditional on a git-ignored properties file, and listing text lives
as plain files under `store/` instead of being pasted into code. Adding a third channel (for example
`foss` or an enterprise build) is one `create("…")` block; changing a store description edits one text
file. The About screen reads `BuildConfig.STORE`, so every future flavour automatically shows the right
channel without touching UI code.

### Tests and build checks at this increment

- Full JVM suite still green after the flavour + version change: **543 tests / 0 failures / 0 skipped**
  (`:app` 165, `:core:runtime` 87, `:feature:chat` 73, `:core:rag` 62, `:core:design` 47,
  `:core:models` 45, `:core:speech` 40, `:core:privacy` 18, `:engine:embeddings` 6). The flavoured app
  task is now `:app:testLibreDebugUnitTest` (and `:app:testPlayDebugUnitTest`).
- The NOTICE guard test caught the version bump (`LicenceRegistryAssetTest > noticeFileMatchesTheRegistry`)
  and passed again after regenerating `NOTICE` from `app/build/reports/notice-expected.txt`; the file is
  byte-for-byte the generated registry output with only the product line changed.
- `:app:lintLibreDebug` and the module lints were run in P3.3/P3.4 with no a11y findings; P3.6 wires the
  same configuration into CI.

### Honest limits

- The APK is signed with the **v2 scheme only**. That is valid for API 28+ (minSdk 28) and installs on
  every supported device, but key rotation via v3 is not enabled; enabling it means setting
  `enableV3Signing = true` in the real release pipeline.
- The AAB is signed with the local key for verification only. Google Play requires the upload key to be
  registered with Play Console / Play App Signing; that account step is outside the repo.
- The APK is **universal** (arm64-v8a + x86_64, 145 MB). AAB delivery splits per device (64.9 MB upload);
  explicit ABI splits / an arm64-only F-Droid artefact are a later packaging job.
- Cold-start numbers are emulator numbers with the profile installed and R8 enabled; the F8 caveat (no
  physical test phone) still applies.
- The store screenshots were captured on the debug build from the P3.3/P3.4 increments; the release build
  renders identically and `p35-release-*.png` proves the release surfaces. Re-capture from the release
  build if the Play listing needs bit-identical provenance.
- `fdroid/metadata/app.sirrak.yml` cannot be linted without the fdroiddata tooling; it is
  submission-ready but unvalidated against the live F-Droid builder. Repository URLs are explicit TODOs.
- The release build produced one non-fatal R8 warning ("Could not parse Kotlin metadata"); R8 continues,
  the release installs and runs, and it is a known AGP/Kotlin-metadata interaction, not a regression.

### Documentation added

`fdroid/README.md`, `store/README.md`, `store/play/data-safety.md`, `store/screenshots/README.md`,
`store/listing/{ar,en}/*.txt`, `keystore.properties.example`.

---

## P3.6 — CI, Arabic eval, lint and visual-regression gates

**Status: complete** (23 Nov 2026). The last P3 increment adds the quality gates a launch candidate needs and
cleans up everything lint could prove was stale. No feature behaviour changed; every working surface from
M1–P3.5 still passes its tests and the screenshot gate.

### What shipped

1. **Gradle wrapper.** `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` and
   `gradle-wrapper.properties` (8.10.2-bin) are now committed, so a fresh checkout and CI build without a
   local Gradle install. The version stays pinned in one place, next to the existing AGP/Kotlin pins.
2. **GitHub Actions CI** (`.github/workflows/ci.yml`). Ubuntu, Temurin JDK 21, `android-actions/setup-android`
   with `platforms;android-36` + `build-tools;36.0.0`, `gradle/actions/setup-gradle`. Three jobs:
   `jvm-tests` (`:app:testLibreDebugUnitTest testDebugUnitTest`), `arabic-eval` (the focused Arabic eval
   test) and `lint` (`:app:lintLibreDebug`), with test/lint reports uploaded as artifacts. The workflow
   deliberately runs no instrumented or screenshot tests: GitHub-hosted runners have no KVM, so the device
   gates stay local (see §Honest limits).
3. **Arabic eval suite.** `core/rag/src/test/resources/arabic/eval_cases.json` (versioned data, currently
   version 1) holds **24 cases**: 12 orthography normalisations, 4 sentence-splitting contracts, 5 retrieval
   rankings and 3 negative controls (a query that must retrieve nothing). `ArabicEvalTest` loads the JSON
   through the classloader with kotlinx.serialization, asserts each case against the production contract and
   prints a per-section count, so a report is one command. This is the automated half of the roadmap §10 CI
   gate; cases are data, so growing the suite is one JSON entry.
4. **Lint to zero errors.** `:app:lintLibreDebug`: **0 errors, 45 → 33 warnings**. Fixed in this increment:
   - 5 `PluralsCandidate` strings (component count, stop-sequence hint, indexed-document count, pending-
     document note, page/chunk detail) became `<plurals>` in **both** locales — Arabic carries all six
     forms (zero/one/two/few/many/other), English one/other — and the five call sites now use
     `pluralStringResource`. Number-plus-noun copy is no longer formatted by hand.
   - `AppBundleLocaleChanges`: the Play bundle disables language splits (`bundle { language {
     enableSplit = false } }`) because the in-app language switch needs both locale resources in the base
     APK.
   - `DataExtractionRules`: added `android:fullBackupContent="false"` beside the existing
     `dataExtractionRules` (backups were already off; the API 28–30 rule was missing).
   - 2 `ObsoleteSdkInt`: removed the dead API-26 branch in `DownloadService.start` (minSdk 28) and merged
     `mipmap-anydpi-v26` into `mipmap-anydpi`.
   - `ExifInterface`: switched `AndroidImagePreparer` to AndroidX ExifInterface (`androidx.exifinterface`
     1.3.7 in the version catalog); the framework class has known security bugs.
   - 5 `UnusedResources`: `sirrak_lapis`, `sirrak_icon_foreground`, `action_models`, `action_close`,
     `action_edit` deleted after confirming no consumer in source.
   - Also fixed the only Kotlin compiler warning in the app module: `Locale(String)` →
     `Locale.forLanguageTag`.
   - Accepted and classified, not silenced: 24 `GradleDependency` + 6 `AndroidGradlePluginVersion`
     warnings (the toolchain is deliberately pinned; upgrading AGP/Kotlin/R8 is a separate change) and 3
     third-party `TrustAllX509TrustManager` warnings from the transitive `bcpkix-jdk15to18-1.72.jar` that
     PDFBox-Android pulls in — not on the app's network path.
5. **Screenshot regression gate.** `scripts/ui-regression.ps1` launches the app from a clean state,
   captures four shell screens (home, model picker, empty library, settings top), crops the status/navigation
   bars, and compares an 8×8 dHash against `scripts/ui-baseline/` (JSON metadata + four PNGs). Distance > 8
   fails the run; `-UpdateBaseline` refreshes deliberately. Proven both ways: clean run `PASS 4/4
   (distance 0)`; a deliberately corrupted baseline hash produced `FAIL library-empty (distance 33 > 8)` and
   exit code 1, then was restored. Provenance (API 36 `Nawa_Phone`, signed `libre` release, 720×1280) and
   usage are in `scripts/README.md`; `scripts/ui-current/` is git-ignored, the baseline is committed.
6. **Launch paperwork and docs truth.** `docs/27-sirrak-launch-checklist.md` turns the §5 roadmap checklist
   into an ordered go/no-go path (quality gates → artifacts → F-Droid/Play → device walk → hosting →
   disclosed gaps). `docs/06-roadmap.md` §15 records what P3 actually delivered and the real status of every
   launch-gate item. README/PRODUCT/DESIGN were updated for the launch-candidate state, the new modules,
   plurals rule and distribution story.

### Tests and build checks at this increment

| Check | Command | Result |
| --- | --- | --- |
| JVM suite | `./gradlew :app:testLibreDebugUnitTest testDebugUnitTest` | **548 tests, 0 failures, 0 skipped** — `:app` 165, `:core:runtime` 87, `:feature:chat` 73, `:core:rag` 67, `:core:design` 47, `:core:models` 45, `:core:speech` 40, `:core:privacy` 18, `:engine:embeddings` 6 |
| Arabic eval | `./gradlew :core:rag:testDebugUnitTest --tests "app.sirrak.core.rag.ArabicEvalTest"` | **24/24 cases** (12 normalisation, 4 sentence splitting, 5 retrieval, 3 negative controls) |
| Android lint | `./gradlew :app:lintLibreDebug` | **0 errors, 33 warnings** (24 dependency, 6 AGP, 3 third-party TLS) |
| Screenshot gate | `pwsh -File scripts/ui-regression.ps1` (signed release on emulator) | **PASS 4/4, distance 0** |
| Release build | `./gradlew assembleLibreRelease bundlePlayRelease` | signed APK 145 695 314 B, AAB 64 933 346 B; `apksigner verify` PASS (v2), `jarsigner -verify` OK, same certificate SHA-256 `4537ECEB…` |
| Release cold start (emulator) | five `am start -W` runs after a clean boot | **1 210 / 1 448 / 1 190 / 1 419 / 1 229 ms → median 1 229 ms**; debug 4 609 ms (P3.4). The long-running emulator measured 1 739 ms median before the reboot, so the probe is run on a clean boot |
| Device plural proof | uiautomator dump on the signed release | About → *Licences & credits* renders **"23 مكوّنًا"** through `pluralStringResource` (Arabic many form) with version `1.0.0-rc1` and channel `libre` |

The release APK and AAB were rebuilt after the lint changes and re-verified end to end: uninstall, fresh
install, onboarding, Settings → About, screenshot gate. The emulator still carries the signed `libre`
`1.0.0-rc1` build.

### Why this is not hardcoded

- Eval cases are a versioned JSON resource, not assertions scattered across a test file; adding a case is
  one entry.
- The CI pipeline is declarative YAML that calls the same Gradle tasks a developer runs; no check exists
  only in CI.
- Screenshot geometry, tolerance and screen list are parameters of one script; the baseline is data
  (`baseline.json` + PNGs) and can be refreshed deliberately.
- Counted copy is plural resources in both locales; the loss-of-singular bug class cannot come back through
  a hand-formatted `%1$d noun`.
- Distribution/locale behaviour is Gradle configuration (`bundle`, flavours) read through `BuildConfig`, not
  a runtime branch on package name.

### Honest limits

- **CI has no device leg.** GitHub-hosted runners have no KVM, so instrumented tests, screenshot regression
  and the release cold-start probe run locally against `Nawa_Phone`. The workflow proves JVM/compile/lint
  quality only; the device gate remains a human-run command before a release cut.
- **The Arabic eval is the automated subset.** 24 cases cover normalisation, splitting, retrieval ordering
  and no-match behaviour; the roadmap §10 human rubric (style, accuracy sample, 300 prompts) and the voice
  WER pass are still monthly manual work and need a phone under F8.
- **Accepted warnings are real debt.** The 24 dependency + 6 AGP warnings are pinned on purpose and tracked
  as a deliberate toolchain upgrade (R8 even reports its Kotlin-metadata mismatch with the pinned Kotlin);
  the Bouncy Castle trust-manager warning is transitive and unused by app code. Neither blocks the
  candidate, both stay visible in every lint report.
- **The screenshot baseline covers four shell surfaces**, not content screens (chat answers, model cards,
  document rows depend on installed data). Layout regressions in those screens rely on the human device walk
  in doc 27 §4.
- **Cold-start numbers are emulator numbers** and state-dependent (1.23 s clean boot, 1.74 s on a dirty
  long-running emulator); the F8 caveat (no physical phone) still applies.
- **Plural forms are device-proven for one quantity** (many, 23 components). The other Arabic forms compile
  from resources and are exercised by the resource compiler and the lint plural checks; a full per-form
  screenshot matrix is not part of the gate.

### Documentation added / updated

`docs/27-sirrak-launch-checklist.md` (new), `docs/06-roadmap.md` §10 + §15, `README.md`,
`PRODUCT.md`, `DESIGN.md`, `scripts/README.md`, `scripts/ui-baseline/*`, `.github/workflows/ci.yml`,
`core/rag/src/test/resources/arabic/eval_cases.json`, and this section.

## P3.7 — CycloneDX SBOM, generated store graphics and launch validators (23 Nov 2026)

P3.7 closes the last *local* launch-readiness gaps without touching the shipped UI: the record for the
candidate gets machine-checkable supply-chain and store metadata, the two remaining Play graphics are
generated instead of hand-drawn, and the manual launch checklist becomes two reusable commands. Because
no screen, string used on screen or runtime path changed, the P3.6 device evidence and screenshot
baseline stay valid; only the packaging record changed.

### What shipped

1. **Deterministic CycloneDX 1.5 SBOM from the licence registry** (`core/runtime/.../Sbom.kt`,
   `sbom.cdx.json`, root). The registry (23 entries: the app plus 22 licensed components) already fed
   Settings → About and the generated `NOTICE`; it now feeds a third consumer instead of a fourth
   hand-maintained list. The document declares `bomFormat: CycloneDX`, `specVersion: 1.5`, the app as
   the metadata subject (`application`, Apache-2.0, purl `pkg:generic/sirrak@1.0.0-rc1`), and one
   component per non-app entry with `type` (framework / library / file / machine-learning-model /
   application), SPDX `license.id` where the licence has one and `license.name` otherwise
   (`Llama-3.2-Community`), a `pkg:generic/...` purl, the `website` external reference, and
   `sirrak:kind` + `sirrak:bundled` properties (`bundled` → `scope: required`, runtime-downloaded →
   `excluded`). Output is pretty-printed, newline-terminated and byte-deterministic; `encodeDefaults`
   is deliberately off so required spec fields are passed explicitly and cannot be silently omitted
   (a real bug found by the roundtrip test). `SbomTest` covers document shape, mapping, SPDX-vs-name
   licensing, blank versions, determinism and the "exactly one app entry" failure;
   `LicenceRegistryAssetTest` fails when `sbom.cdx.json` drifts from the registry or misses an entry,
   and writes `app/build/reports/sbom-expected.json` so the regeneration fix is mechanical.
2. **Store graphics generated from data** (`store/graphics/brand.json`, `scripts/store-graphics.ps1`).
   The renderer reads one brand file (palette roles, font files, copy keys, adaptive-icon mark paths,
   layout), embeds the **shipped** Amiri / IBM Plex Sans Arabic TTFs from `core/design` as data URIs,
   renders the HTML with the mark inline as SVG, and screenshots it with headless Chrome
   (`--headless=new --screenshot`, exact window size). Arabic is shaped by the browser's
   HarfBuzz/Bidi stack — a Windows GDI/WPF `FormattedText` probe was tried first and produced displaced
   Amiri glyphs, so it was discarded. Outputs: `icon-512.png` (512×512, 7,665 B, SHA-256
   `582d02E2…`) and `feature-graphic-1024x500.png` (1024×500, 32,749 B, SHA-256 `5089DFAB…`);
   `manifest.json` records renderer name/version, the brand source hash and each output's
   width/height/bytes/SHA-256; transient HTML and the throwaway browser profile live in the
   git-ignored `store/graphics/.render/`. The feature graphic keeps its content inside Play's
   croppable safe area (~y 50–450) and puts the trust line under the icon, leaving the hero copy to
   the wordmark block. `StoreGraphicsBrandTest` (JVM) fails when the palette no longer equals
   `ThemePacks.default.light`, when the mark paths differ from `ic_launcher_foreground.xml`, when the
   copy no longer matches the shipped `app_name` / `empty_promise` / `empty_privacy` / listing names,
   or when a PNG/manifest hash is stale.
3. **Two reusable launch validators** driven by `store/store-spec.json` (limits, locales, required
   files, screenshot rules, release artifacts, signing fingerprint, minimum test count).
   `scripts/validate-store.ps1` runs **79 checks**: name ≤ 30 / short ≤ 80 / full ≤ 4000 / What's new
   ≤ 500 characters per locale, no leading/trailing whitespace or tabs, listing name equals the shipped
   `app_name` (Arabic) / frozen Latin name (English), What's new mentions the current `versionName` read
   from `app/build.gradle.kts`, 2–8 portrait PNG screenshots with Play's 320–3840 px side bounds, icon
   and feature-graphic dimensions + SHA-256 against the graphics manifest, and every launch document
   present. `scripts/release-check.ps1` is the one-command pre-release gate (Gradle build/lint/tests →
   summed JVM results → lint XML → artifact size/SHA-256 + `apksigner` certificate fingerprint +
   `jarsigner` → store validator → screenshot gate → `build/release-check.json`, non-zero exit on any
   failure). Running them found and fixed two real blockers: the Arabic and English short descriptions
   were 84/89 characters (Play limit 80) and the English What's new was 545 characters (limit 500).
4. **Release rebuilt and re-recorded.** The signed `libre` APK is now 145,711,750 B (SHA-256
   `FD94EA31…`) and the `play` AAB 64,938,455 B (SHA-256 `9326F505…`), same certificate
   (`4537ECEB…`). Both were verified by `apksigner verify --print-certs` / `jarsigner -verify` through
   `release-check.ps1`, the signed APK was installed on the API 36 emulator and the screenshot gate
   passed 4/4 with distance 0.

### Why this is not hardcoded

- The licence registry stays the single source of truth: About, `NOTICE`, the SBOM and the tests all
  read it, so a component can never be added to one consumer and forgotten in another.
- Store graphics read `brand.json` only; no hex, font path, copy line or coordinate is duplicated in
  the renderer. Changing a theme token or a shipped string is enough, and the drift test fails until the
  PNGs are regenerated. The mark is imported from the launcher vector instead of redrawn.
- Both validators read `store/store-spec.json`; platform limits, required files, artifact paths and the
  signing fingerprint are data. Bumping a version or changing the release shape is not a script edit.
- `release-check.ps1` composes the same Gradle tasks and scripts a developer runs; no gate exists only
  inside it, and it emits a JSON record instead of a prose claim.

### Tests and build checks at this increment

| Check | Command | Result |
| --- | --- | --- |
| JVM suite | `./gradlew :app:testLibreDebugUnitTest testDebugUnitTest` | **567 tests, 0 failures, 0 errors** — `:app` 178, `:core:runtime` 93, `:feature:chat` 73, `:core:rag` 67, `:core:design` 47, `:core:models` 45, `:core:speech` 40, `:core:privacy` 18, `:engine:embeddings` 6 (+19 vs P3.6: 6 `SbomTest`, 2 licence-registry SBOM tests, 4 store-graphics tests, 7 website-content tests) |
| SBOM drift | `:app` licence-registry tests | committed `sbom.cdx.json` equals the registry render; 22 components + app subject, CycloneDX 1.5, 14,733 B |
| Store graphics drift | `:app` store-graphics tests | palette == Girih & Ink light, mark == launcher vector, copy == shipped strings/listing names, PNG dims + manifest hashes current |
| Arabic eval | `./gradlew :core:rag:testDebugUnitTest --tests "app.sirrak.core.rag.ArabicEvalTest"` | **24/24 cases** (normalisation 12, sentence splitting 4, retrieval 5, negative controls 3) |
| Android lint | `./gradlew :app:lintLibreDebug` | **0 errors, 33 warnings** (unchanged accepted set) |
| Store validation | `pwsh -File scripts/validate-store.ps1` | **79/79 checks pass** |
| Release QA | `pwsh -File scripts/release-check.ps1 -SkipBuild -SkipEmulator` | **all gates PASS**; APK v2 signature + cert `4537ECEB…`, AAB jarsigner OK, `build/release-check.json` written |
| Screenshot gate | `pwsh -File scripts/ui-regression.ps1` (rebuilt signed release installed) | **PASS 4/4, distance 0** |
| Release build | `./gradlew :app:assembleLibreRelease :app:bundlePlayRelease` | APK 145,711,750 B / AAB 64,938,455 B; sizes + SHA-256 recorded in `store/README.md` |

### Honest limits

- **The SBOM covers the licence registry, not the transitive Maven graph.** It is a reviewable
  top-level inventory (what the app tells users it bundles) rendered with the CycloneDX 1.5 JSON
  schema; a full dependency-graph SBOM (Gradle plugin, CycloneDX XML/protobuf, serial-number scheme)
  is a separate future increment and not claimed here.
- **Store graphics are static assets rendered on a developer machine.** They are not device-rendered,
  not pixel-compared by the screenshot gate, and the renderer's hashes depend on the recorded headless
  browser version (Chrome/Edge; the manifest names it). Play still applies its own icon mask and may
  crop the feature graphic on some surfaces — content is kept inside the safe area but a crop is not
  simulated. The feature graphic is Arabic-first; the English listing reuses it rather than shipping a
  second English hero, and a designer review is still worthwhile before upload.
- **The validators enforce structure, not quality.** They catch character limits, mismatched names,
  stale hashes, missing documents and screenshot bounds; they cannot judge translation quality,
  marketing tone or whether a screenshot tells the story. Those stay human review items in doc 27.
- **The screenshot gate still covers four shell surfaces** on the emulator; content screens depend on
  installed models and remain part of the human walkthrough.
- **Cold start was not re-measured.** The rebuild changes packaging only (no UI/runtime behaviour
  change), so the last clean-boot measurement (1.23 s median, P3.6) stands as the recorded figure; a
  re-measure belongs to the next release cut that changes runtime code.

### Documentation added / updated

`store/README.md` (new artifact hashes, SBOM + validation sections), `store/graphics/README.md` (new),
`store/screenshots/README.md`, `scripts/README.md` (validators + graphics renderer, CI rationale),
`docs/27-sirrak-launch-checklist.md` (P3.7 gates), `docs/06-roadmap.md` §15.1/§15.2, `README.md`,
`PRODUCT.md`, `.gitignore` (`store/graphics/.render/`), and this section. Exit criteria for the
milestone: `release-check.ps1` green on a rebuilt signed release, `validate-store.ps1` green, and the
committed SBOM/graphics matching their sources — all three are true as of this record.

---

## P3.8 — public launch surface (23 Nov 2026)

P3.8 turns the launch candidate into a public project: a repository with health files, a bilingual product
site generated from data, published privacy/security policies wired into the app, and launch validators that
keep all of it honest. It does not change runtime behaviour beyond the policy links, so the P3.7 performance
evidence stands.

### What shipped

- **Repository health:** root Apache-2.0 `LICENSE` and `NOTICE`, `CONTRIBUTING.md`,
  `CODE_OF_CONDUCT.md`, `SUPPORT.md`, `CHANGELOG.md`, `CITATION.cff`, issue and PR templates,
  `.editorconfig`, `.gitattributes`, bilingual `README.md` + `README.ar.md`, and a curated `docs/`
  tree (04–28) with local machine paths and spike-app references removed.
- **Hosted site:** a static generator (`scripts/build-site.ps1`) renders `website/dist` from
  `website/site.json`, `website/content/{en,ar}.json`, `website/content/policies.json`,
  `website/theme/site.css` and `store/graphics/brand.json`. Thirty files: EN and AR landing pages, EN/AR
  privacy and security pages, a bilingual 404, sitemap, robots, `llms.txt`, web manifest, OG/icon assets and
  four self-hosted font files. No framework, no third-party scripts, no cookies.
- **Policies:** `PRIVACY.md` and `SECURITY.md` rewritten as published documents (dateline, hosted EN/AR
  URLs, in-app path, permissions table, reporting channels, signing facts, known limits). `app.json` now
  points `privacy.policyUrl`, `privacy.securityUrl` and `privacy.reportIssueUrl` at the hosted pages and the
  issue chooser, so Settings → Privacy & data opens real pages instead of a dead link. The signed artifacts
  were rebuilt after that wiring (new hashes below).
- **Launch validators:** `scripts/validate-site.ps1` (225 structural, SEO, accessibility, policy and drift
  checks, portable to Linux), `WebsiteContentTest.kt` (7 JVM drift tests), and `release-check.ps1` gained a
  public-site gate plus a recorded-digest check (a rebuilt artifact that no longer matches the SHA-256 in
  `store/store-spec.json` fails the gate). CI gained a `public-site` job; `.github/workflows/pages.yml`
  builds, validates and deploys `website/dist` to GitHub Pages.
- **Published-facts validator:** `scripts/validate-repo.ps1` covers what an offline check cannot see —
  repository description/topics/homepage/license, Pages settings, secret scanning and Dependabot alerts,
  branch protection (strict required checks, linear history, no force pushes/deletions), the release assets
  with their GitHub-computed SHA-256 digests, and every live URL returning 200. Expected values live in
  `store/repo-spec.json`, so nothing is hardcoded in the script.
- **Public distribution:** public repo, Pages site, and the `v1.0.0-rc1` GitHub Release with the signed APK,
  AAB and `SHA256SUMS`. Branch protection requires all four CI checks and linear history.

### Evidence (23 Nov 2026)

| Gate | Command / URL | Result |
| --- | --- | --- |
| JVM suite | `./gradlew :app:testLibreDebugUnitTest testDebugUnitTest` | **567 tests, 0 failures, 0 errors** (9 modules; +7 `WebsiteContentTest`) |
| Public site | `pwsh -File scripts/build-site.ps1` + `validate-site.ps1` | **225/225 checks**, 30 files, 2,312,218 B, zero failures |
| Site drift | `:app` `WebsiteContentTest` | 7 tests: data/version parity, EN/AR structure, brand palette + mark + fonts, policy rendering + dates, app-config URLs, SEO files, no local paths or drafts |
| Store assets | `pwsh -File scripts/validate-store.ps1` | **79/79 checks** |
| Repo + release facts | `pwsh -File scripts/validate-repo.ps1` | **36/36 checks**: repo metadata, Pages, security features, branch protection (4 required checks), release assets with digests matching the spec, 11/11 live URLs 200 |
| CI | GitHub Actions run `45159519429` | green: JVM tests, Arabic eval, lint, public-site |
| Pages | GitHub Actions run `45159519451` | green: build → validate → deploy |
| Live site | <https://ahmadmhmdsy.github.io/sirrak/> | 200 for all six pages, sitemap, robots, llms.txt, manifest, OG image; EN/AR `lang`+`dir`, canonical, hreflang, JSON-LD, bilingual 404 verified |
| Release | <https://github.com/ahmadmhmdsy/sirrak/releases/tag/v1.0.0-rc1> | signed APK 145,711,750 B (`FD94EA31…`), AAB 64,938,455 B (`9326F505…`), `SHA256SUMS`; certificate `4537ECEB…6806` |

### Honest limits

- **The site validators enforce structure, not taste.** They prove copy parity, canonical/hreflang/OG/JSON-LD
  correctness, policy accuracy, brand drift and absence of local paths; they cannot judge translation
  quality or visual design. Both were reviewed manually (EN/AR desktop + mobile captures).
- **GitHub Pages constraints:** a project site cannot set custom security headers (no CSP/HSTS control at the
  host level), and `robots.txt` is served under `/sirrak/` rather than the host root, which limits what it can
  say to crawlers for the whole domain.
- **Discovery is only technically prepared.** Search Console / Bing Webmaster verification, sitemap
  submission and the GitHub social-preview image are account actions that were not taken; `llms.txt` and
  `robots.txt` are present and correct but no indexing outcome is claimed.
- **Distribution is GitHub-only.** Play closed testing and the F-Droid `fdroiddata` MR remain external;
  F-Droid submission is blocked on a source-buildable sherpa-onnx (the shipped AAR is prebuilt).
- **Artifact size and performance unchanged:** the rebuild after policy wiring adds bytes only (APK +52 B,
  AAB +58 B versus P3.7); no runtime code changed, so P3.7's emulator-measured numbers stand and are not
  re-measured here. Emulator-only performance remains a limit.
- **The release notes are bilingual in one Markdown body.** GitHub renders Arabic paragraphs correctly but
  cannot set a per-section direction, so the English body comes first and the Arabic section follows.

### Documentation added / updated

`README.md` + `README.ar.md` (public status, live links, install path, 15 modules), `PRIVACY.md`,
`SECURITY.md`, `DESIGN.md` (public surface & brand assets), `PRODUCT.md` (P3.8 bullet), `docs/06` (§15.1
evidence + §15.2 launch table), `docs/27` (P3.8 publication record with live evidence), `docs/28` (council
record), `store/README.md` (rebuilt artifact hashes), `store/listing/` (EN/AR copy refreshed),
`fdroid/metadata/app.sirrak.yml` (source/commit/changelog URLs, NDK/CMake recipe, prebuilt-AAR caveat),
`CONTRIBUTING.md`, `CHANGELOG.md`, `store/repo-spec.json`, the site generator/validators under `website/`
and `scripts/` (including `validate-repo.ps1` and the updated `scripts/README.md`).
Exit criteria for the milestone: CI and Pages green on `main`, the release live with both artifacts, the site
validators green, and every recorded hash matching the files in `store/README.md` — all true as of this record.

