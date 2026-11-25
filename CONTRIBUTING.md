# Contributing to Sirrak (سِرِّك)

Sirrak is a private, Arabic-first Android assistant that runs its models on the phone. It is Apache-2.0,
built by one maintainer, and reviewed in the open. A good contribution here is small, tested, and easy to
revert.

Read [README.md](README.md) for what the app does, [DESIGN.md](DESIGN.md) for the interface rules, and
[docs/README.md](docs/README.md) for the build records. If a rule below looks arbitrary, the milestone doc
that introduced it is the argument for it.

## Before you write code

- **Bug fix or small improvement:** open an issue or send the pull request directly.
- **New feature, or a change to existing behaviour:** open an issue first. `1.0.0-rc2` is feature-complete;
  scope changes are decisions, not drive-bys. The scope is in [PRODUCT.md](PRODUCT.md).
- **Security problem:** stop and read [SECURITY.md](SECURITY.md). Never open a public issue for it.
- **Translation or wording:** both locales move together (see [Arabic, RTL and accessibility](#arabic-rtl-and-accessibility)).

## Development environment

| Tool | Version | Needed for |
| --- | --- | --- |
| JDK | 21 | every Gradle task; bytecode still targets 17 |
| Android SDK | platform 36, build-tools 36+ | `compileSdk`/`targetSdk` 36 |
| NDK | 27.2.12479018 | `:engine:llamacpp` |
| CMake | 3.22.1 | `:engine:llamacpp` |
| Gradle | wrapper 8.10.2 | no local Gradle install needed |
| PowerShell | `pwsh` | the `scripts/*.ps1` gates |

llama.cpp is vendored under `third_party/llama.cpp` and the sherpa-onnx runtime is a pinned AAR under
`engine/whisper/libs`, so the build fetches no native dependency. The first native build takes a few
minutes; later builds are incremental.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
```

Those variables are only needed when your JDK or SDK is outside the default location. A device or emulator
at API 28+ is required for anything beyond the JVM suite; `arm64-v8a` is what a phone runs, `x86_64` is the
emulator.

## Build and test

Every task is variant-qualified because there are two flavours (`libre` is the F-Droid/self-hosted
default, `play` is the Play build):

| What | Command |
| --- | --- |
| Debug APK | `.\gradlew :app:assembleLibreDebug` |
| JVM suite (744 tests) | `.\gradlew :app:testLibreDebugUnitTest testDebugUnitTest` |
| Arabic eval only | `.\gradlew :core:rag:testDebugUnitTest --tests "app.sirrak.core.rag.ArabicEvalTest"` |
| Android lint | `.\gradlew :app:lintLibreDebug` |
| Release artifacts (signs only with `keystore.properties`) | `.\gradlew assembleLibreRelease bundlePlayRelease` |
| Store/listing validator | `pwsh -File scripts/validate-store.ps1` |
| Screenshot regression (needs a booted emulator) | `pwsh -File scripts/ui-regression.ps1` |
| Full local release gate | `pwsh -File scripts/release-check.ps1` |

The debug APK lands in `app/build/outputs/apk/libre/debug/app-libre-debug.apk`. GitHub Actions runs the JVM
suite, the Arabic eval and lint on every push and pull request (`.github/workflows/ci.yml`); device and
screenshot gates do not run there because the hosted runners have no emulator acceleration, so run them
locally when your change can affect them.

## The three rules

1. **Test first.** Write the failing test, run it, and watch it fail for the reason you expect before you
   write the implementation. A test added after the code proves the code does what it does, not what it
   should.
2. **No hardcoded values.** Behaviour belongs in data or config, not in a call site.
3. **Compose, do not import sideways.** A feature contributes through a typed slot; it never imports
   another feature.

The composition model — plugins, slots, services and disposal order — is described in
[docs/05-architecture.md](docs/05-architecture.md).

### Test first, and test the seam

- A policy, parser, reducer or state machine lives in a pure-JVM module and is tested there; wiring is
  tested in `:app`; a screen test should assert intent, not layout constants.
- Prefer a failing test that names the bug ("a prompt longer than `n_batch` must not abort the engine") to a
  test that repeats the implementation.
- Iterate over data instead of copying examples: every theme pack in every mode, every config field, every
  catalogue entry. If a new entry can break the app, a loop should catch it.
- No production hooks that exist only for a test. If a branch cannot be reached from the seam, the seam is
  wrong.

### No hardcoded values

| Kind of value | Where it lives |
| --- | --- |
| Feature order or on/off, theme default, engine parameters, privacy endpoints | `app/src/main/assets/config/app.json` |
| Colours and palettes | `core/design/src/main/java/app/sirrak/core/design/ThemePacks.kt` (one pack, one value) |
| Shape, spacing and motion tokens | `core/design/src/main/java/app/sirrak/core/design/Tokens.kt` |
| Catalogues and demo content | `app/src/main/assets/content/`, `app/src/main/assets/catalog/` |
| Extension points | `core/runtime/src/main/java/app/sirrak/core/runtime/Slots.kt` |
| User-visible text | `values/` (Arabic) and `values-en/` (English) string resources |

- No hex literal at a composable call site: a role comes from the applied pack, and a decorative token from
  `Tokens.kt`.
- No user-visible string literal in Kotlin, including content descriptions, notifications and error text.
- `app.json` is validated at startup and fails loudly in debug: an invalid value is a bug, never a silent
  fallback.

## Adding a feature or plugin

A feature is a Gradle module plus one line in the composition. Concretely:

1. **Create the module** under `feature/<name>/`, modelled on `feature/voice/`: `build.gradle.kts`,
   `src/main/AndroidManifest.xml` if it needs one, its own Arabic `src/main/res/values/strings.xml` and
   English `src/main/res/values-en/strings.xml`, and a `Plugin` implementation.
2. **Register it** in `settings.gradle.kts` with `include(":feature:<name>")`.
3. **Depend on it once** in `app/build.gradle.kts`.
4. **Add one line** to the `manifest` list in `app/src/main/java/app/sirrak/AppComposition.kt`.
5. **Contribute UI through a slot.** Declare the `SlotKey` in `core/runtime/.../Slots.kt` with the owner
   that renders it, and provide into it from your plugin. Registering into an undeclared slot, or two
   providers for a single slot, fails at composition.
6. **Expose services through contracts.** An interface in `core/runtime/.../Contracts.kt`, provided by a
   plugin, required with `Plugin.requires`. Install order is dependency-resolved; disposal is reverse order.
7. **Make it switchable in data, if it is a switch.** `features.order` and `features.enabled` in
   `app/src/main/assets/config/app.json` reorder or disable without a rebuild; unknown ids are logged and
   ignored while compile-time membership stays authoritative.
8. **Pin it with tests.** `AppCompositionTest` pins the add/remove rule; add JVM tests for the new policy,
   not just for the screen.

The whole wiring is three lines:

```kotlin
// settings.gradle.kts
include(":feature:notes")

// app/build.gradle.kts
implementation(project(":feature:notes"))

// app/src/main/java/app/sirrak/AppComposition.kt — the manifest list
NotesPlugin(),
```

An engine is the same shape: one `InferenceEngine` provider per plugin, selected by `engine.provider` in
`app.json`. Chat never names a concrete engine, so swapping one is a module, a plugin and a config value.

## Adding a theme pack

1. Add one `ThemePack` value to `ThemePacks.all` in
   `core/design/src/main/java/app/sirrak/core/design/ThemePacks.kt`: a stable `id` (never localised, never
   reused for different colours), `nameRes`, `descriptionRes`, and a complete `light` and `dark`
   `ThemePalette` — every role, including the decorative tokens.
2. Add the four string resources: name and one-line description, Arabic in
   `core/design/src/main/res/values/strings.xml` and English in `values-en/strings.xml`.
3. Run the `:core:design` tests. `ThemePacksTest` pins the current five ids (add yours to that list),
   fails a duplicate id or a pack without localised copy, and the WCAG gate fails a pack whose body text is
   below 4.5:1 or whose meaningful UI is below 3:1.
4. Do not touch geometry, type scale or motion: a pack is colour and decoration only, so it can never
   change how the app is laid out or how Arabic renders.
5. Change `theme.defaultPack` in `app.json` only if the default actually moves. Saved ids resolve through
   the registry and fall back automatically when a pack is removed.

A pack is data, not a theme function: no screen knows any pack by name, and no call site holds a hex value.

## Arabic, RTL and accessibility

Arabic is the default: UI copy lives in `values/strings.xml`, English in `values-en/strings.xml`. Both are
edited in the same change — a missing English string silently falls back to Arabic.

- Test in Arabic first, then English. Check RTL mirroring of chevrons, arrows and progress, and check that
  mixed Arabic/Latin values (model names, versions, numbers) still read in the right direction;
  `core/design/.../Settings.kt` has the bidi rule for settings values.
- Touch targets are at least 48 dp. Text must survive 200 % scaling and the in-app 85/100/115/130 % text
  size without clipping or overlapping.
- Every icon-only control needs a content description that names the action, from a resource, in both
  locales. A colour or an icon alone is never the only signal.
- Contrast is not chosen by hand: the pack plus the WCAG gate is the authority. If a design needs a colour
  the gate rejects, the design changes, not the gate.
- Accessibility is a release gate, not a polish pass: a change that regresses TalkBack, RTL or 48 dp is a
  bug.

## Licence registry

Sirrak ships a licence registry at `app/src/main/assets/config/licences.json`. It feeds **Settings →
About**, the committed `NOTICE`, and `sbom.cdx.json`; `:app` tests parse the asset strictly and fail when
the SBOM drifts from the registry.

- Every new runtime dependency needs an entry: id, name, version, licence, kind, copyright and URL.
- A bundled licence text goes under `app/src/main/assets/licenses/` and is referenced with `textAsset`.
- Bundled fonts, engines and model runtimes are components too, not build details.
- User-downloaded models are listed as `model` entries with their licence and source; they are never
  bundled.
- When the drift test fails, it writes the expected document to `app/build/reports/sbom-expected.json`;
  update `sbom.cdx.json` and `NOTICE` from that, not by hand-counting entries.
- A new Gradle dependency without a registry entry is a review blocker even if the build is green.

## Commits and pull requests

- **Subject:** short and imperative, no trailing period — `Fix chunked prefill when the prompt exceeds
  n_batch`.
- **Body:** explain *why*. The constraint, the alternative you rejected, and the evidence (test name,
  device run, log line). A diff already says what changed.
- One logical change per pull request. A refactor and a behaviour change are two pull requests.
- Reference the issue (`Closes #123`). If there is none, say why the change does not need one.
- UI changes: attach before/after screenshots for both themes and both languages, and commit a refreshed
  `scripts/ui-baseline/` in the same change if the baseline legitimately moved.
- A version bump (`versionName`/`versionCode`) is a release decision, not part of a feature pull request;
  the store validator checks that What's new names the current version.

The [pull request template](.github/PULL_REQUEST_TEMPLATE.md) carries the checklist: tests, lint, the
no-hardcode rules, licences, Arabic/RTL, and screenshots for UI changes.

## What not to do

- **Do not commit build outputs:** `build/`, `.gradle/`, `local.properties`, APKs, AABs, `.cxx/`,
  `*.hprof`.
- **Do not commit keystore material:** `keystore.properties`, `keystore/`, `*.jks`, `*.keystore`. If you
  think you committed a key, treat it as compromised and say so in the pull request.
- **Do not commit evidence:** `.evidence-*`, `build-evidence/`, `build-*.txt`, `*.log`,
  `scripts/ui-current/`, `store/graphics/.render/`. Written records belong in `docs/`.
- **Do not hardcode** user-visible text, colours, spacing or behaviour that belongs in config or data.
- **Do not add telemetry** — analytics, crash reporting, ads, accounts, or any network call that is not a
  user-started model download. This is the product's promise, not a preference.
- **Do not weaken tests:** no `@Ignore`, no deleted assertion, no loosened gate to make a build green.
- **Do not add a binary** without provenance, its licence, and a licence-registry entry.
- **Do not reformat** files you did not otherwise change; it buries the real diff.

## Security

Report vulnerabilities privately through the
[advisory form](https://github.com/alshahia/sirrak/security/advisories/new) — never a public issue, never a
pull request that describes the flaw. [SECURITY.md](SECURITY.md) lists what is in scope and the response
times. A dependency with a known critical vulnerability is worth an issue even before a fix exists.

## Good first contributions

- **Add an Arabic eval case.** One entry in `core/rag/src/test/resources/arabic/eval_cases.json` (the
  format is documented by the existing cases), then run the Arabic eval command above. Cases are data, so
  the change needs no Kotlin.
- **Cover an untested branch in a pure-JVM module.** `:core:speech` (WAV resampling edges, silence
  thresholds) and `:core:privacy` (network-audit ring bounds, report-queue limits) need no device.
- **Fix a stale command or path in the docs.** `docs/README.md`, `scripts/README.md` and the milestone
  records are part of the deliverable; a wrong command wastes every reader's time.
- **Improve a content description or an English string** that reads like a translation. Bring the Arabic
  and English halves together and say what was wrong with the old one.
- **Measure on a real phone.** The README states plainly that its performance numbers come from an
  x86_64 emulator. A reproducible report with the build, model, tokens per second, time to first token and
  peak memory is a contribution.
- **Report a crash properly.** A logcat capture with the version, steps and model identified is worth more
  than a fix without a reproduction.
