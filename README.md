 <p align="center">
  <img src="store/graphics/icon-512.png" width="112" alt="Sirrak app icon: lapis girih mark on an ivory ground">
</p>

<h1 align="center">سِرِّك · Sirrak</h1>

<p align="center">
  <strong>Private, Arabic-first AI assistant for Android. The model runs on your phone.</strong><br />
  مساعد ذكاء اصطناعي عربي خاص يعمل على جهازك: محادثة وإملاء وإجابة من مستنداتك، دون اتصال.
</p>

<p align="center">
  <a href="https://github.com/alshahia/sirrak/actions/workflows/ci.yml"><img src="https://github.com/alshahia/sirrak/actions/workflows/ci.yml/badge.svg" alt="CI status"></a>
  <a href="https://github.com/alshahia/sirrak/actions/workflows/pages.yml"><img src="https://github.com/alshahia/sirrak/actions/workflows/pages.yml/badge.svg" alt="Site deploy status"></a>
  <img src="https://img.shields.io/badge/license-Apache--2.0-blue.svg" alt="License: Apache-2.0">
  <img src="https://img.shields.io/badge/Android-9%2B%20(API%2028)-3ddc84.svg" alt="Android 9 or newer">
  <img src="https://img.shields.io/badge/JVM%20tests-567%20green-brightgreen.svg" alt="567 green JVM tests">
  <img src="https://img.shields.io/badge/telemetry-none-success.svg" alt="No telemetry">
</p>

<p align="center">
  <b>English</b> ·
  <a href="README.ar.md">العربية</a> ·
  <a href="https://alshahia.github.io/sirrak/">Website</a> ·
  <a href="https://alshahia.github.io/sirrak/privacy/">Privacy</a> ·
  <a href="https://alshahia.github.io/sirrak/security/">Security</a> ·
  <a href="https://github.com/alshahia/sirrak/releases/latest">Download</a> ·
  <a href="docs/README.md">Docs</a>
</p>

---

Sirrak (Arabic for *your companion*) is a private assistant for Arabic and English that runs a real language
model on your phone. Chat, dictation, document grounding and model management all work offline; the only
network request the app can make is a model download you start yourself. No account, no cloud, no ads, no
telemetry, no analytics. Apache-2.0 source.

It is built Arabic-first, not translated as an afterthought: a right-to-left layout designed from the start,
Amiri and IBM Plex Sans Arabic typography, Arabic and English dictation, and five hand-written theme packs
instead of a stock Material palette.

**Status:** the [repository](https://github.com/alshahia/sirrak) and the bilingual site are public, the
privacy and security policies are published in both languages, and the signed APK is served from
[GitHub Releases](https://github.com/alshahia/sirrak/releases/latest). `1.0.0-rc1` is the current
candidate; F-Droid and Play are prepared but have no store listing yet — see [Limits](#limits-honest).

## Why Sirrak

- **On-device by default.** Chat runs through a statically linked [llama.cpp](https://github.com/ggml-org/llama.cpp)
  engine on the CPU; conversations live in app-private storage.
- **Offline when you want.** Import a GGUF model and the app never needs the network again. An **offline-only**
  switch blocks every request and cancels a running download.
- **Arabic-first.** RTL layout, Arabic typography, Arabic dictation, Arabic document retrieval — tested with a
  24-case Arabic evaluation suite on every JVM test run.
- **Honest licences.** A bundled licence and credits registry (23 components, full texts offline) feeds
  Settings → About, [NOTICE](NOTICE) and a drift-tested [CycloneDX SBOM](sbom.cdx.json); every catalogue model
  shows its licence before download.
- **Small and inspectable.** 15 Gradle modules, typed UI slots, a dependency-ordered plugin runtime — a feature
  is one module plus one manifest line, not a fork of the shell.
- **No accounts, no ads, no tracking, no analytics.** There is no server to send anything to.

## What it does

| Surface | What you get |
|---|---|
| Chat | Streaming answers from a local GGUF model, multiple conversations with search/rename/delete, Markdown rendering, per-conversation system prompt and stop sequences, copy/regenerate/new-chat actions, token meter with measured tok/s |
| Dictation | On-device sherpa-onnx Whisper for Arabic and English with a live level meter; the transcript is appended to the composer, never sent automatically; audio never leaves the device |
| Documents | Import PDF, DOCX, EPUB, Markdown or text, index locally (embeddings + Arabic BM25 fused by RRF) and ask questions answered from numbered, explicitly untrusted sources with citation chips and excerpt sheets |
| Models | Curated catalogue with device-aware recommendations, resumable checksum-verified downloads, pause/cancel/retry, background progress notification, installed-model manager, storage manager, Hugging Face import |
| Tuning | Temperature, top-p, top-k, repeat penalty, context size, streaming, chat-template choice, global and per-model prompts — all provable in one engine log line |
| Themes | Five hand-written theme packs with a live preview and a WCAG contrast gate: Girih & Ink, Lapis Night, Turquoise Dawn, Madder Dusk, Paper |
| Privacy | Per-call network log, offline-only switch, local AI-output reports you choose to export, and an honest state instead of a dead link where no store listing exists yet |

## Install

1. Open [Releases](https://github.com/alshahia/sirrak/releases/latest) and download the signed **libre** APK
   (~139 MiB, universal arm64-v8a + x86_64). GitHub Releases is the only download path; the Play build is a
   separate signed bundle whose closed testing has not started.
2. Install it on **Android 9+ (API 28)**. Android may ask you to allow installing from this source; the APK is
   signed with the project certificate (SHA-256 <code>4537ECEB9952AC9020EFFCEB8B50CB10AEB795772792D632CFC3D0B9F7106806</code>,
   verify with <code>apksigner verify --print-certs</code>).
3. On first run, choose a model in onboarding, or start with the built-in demo engine to see the interface.
4. No model is bundled: download one from the curated catalogue or import a GGUF file. After that, switch on
   **offline-only** and the app cannot reach the network at all.

**F-Droid:** the metadata is prepared, but the submission is not open — the pinned prebuilt sherpa-onnx
runtime still needs a source build in the F-Droid recipe (tracked in
[docs/27](docs/27-sirrak-launch-checklist.md)).
**Google Play:** the signed bundle is built and verified; closed testing has not started, so there is no
Play listing yet.

[Build from source](#build-from-source) if you prefer to compile it yourself.

## Screenshots

<p align="center">
  <img src="store/screenshots/01-home-ar.png" width="190" alt="Arabic home screen">
  <img src="store/screenshots/02-chat-answer-ar.png" width="190" alt="Arabic chat answer">
  <img src="store/screenshots/05-privacy-ar.png" width="190" alt="Privacy and network log">
  <img src="store/screenshots/08-settings-en.png" width="190" alt="English settings">
</p>
<p align="center"><sub>Arabic-first interface, light and dark, five packs, 48 dp touch targets and 200% text support.</sub></p>

## Privacy by construction

- **No telemetry, no analytics, no ads, no crash reporting service.** Nothing is sent anywhere unless you start
  a model download.
- **Network audit that cannot be hidden.** The app declares every endpoint in configuration and logs the calls
  that actually happened on the device; the log never leaves the phone.
- **Offline-only mode is enforced before a socket opens**, not as a UI hint.
- **Documents, chats, models and settings stay in app-private storage.** Share exports happen only through the
  Android chooser you invoke.
- **Policies (published, bilingual):** [privacy](https://alshahia.github.io/sirrak/privacy/) and
  [security](https://alshahia.github.io/sirrak/security/), also in the repository as [PRIVACY.md](PRIVACY.md)
  and [SECURITY.md](SECURITY.md).

## العربية

<div dir="rtl" lang="ar">

سِرِّك مساعد خاص بالعربية والإنجليزية يعمل على جهازك: المحادثة والإملاء والإجابة من مستنداتك وإدارة الطرازات كلها
تعمل دون اتصال. الطلب الشبكي الوحيد الذي يمكن أن يصدره التطبيق هو تنزيل طراز تبدأه أنت بنفسك. لا حسابات،
ولا سحابة، ولا إعلانات، ولا تتبّع، ولا قياسات. المصدر مفتوح بترخيص Apache-2.0.

**لماذا سِرِّك؟** المحرّك على الجهاز أولًا (llama.cpp على المعالج)، ووضع "دون اتصال فقط" يمنع كل طلب ويوقف أي
تنزيل جارٍ، والواجهة عربية أولًا من اليمين إلى اليسار بخطوط أميري وIBM Plex Sans Arabic، وخمس حزم ألوان
مكتوبة يدويًا، وسجل تراخيص صادق لكل مكوّن، ولا خادم تُرسل إليه محادثاتك.

**ما الذي يفعله؟** محادثة بثّية مع طراز محلي مع سجل محادثات وبحث وإعادة تسمية وتصدير؛ إملاء عربي وإنجليزي عبر
sherpa-onnx Whisper على الجهاز؛ الإجابة من مستنداتك (PDF وDOCX وEPUB ونصوص) مع مراجع واقتباسات؛ إدارة طرازات
من كتالوج منسّق مع تنزيلات قابلة للاستئناف ومتحقَّقة من بصمتها؛ ضبط دقيق لمحرّك التوليد؛ وخصوصية بيدك: سجل
شبكة لكل طلب، ومفتاح "دون اتصال فقط"، وبلاغات محلية لمخرجات الذكاء الاصطناعي تصدّرها أنت فقط إن أردت.

**التثبيت:** نزّل ملف APK الموقَّع من صفحة [الإصدارات](https://github.com/alshahia/sirrak/releases) على
أندرويد 9 أو أحدث. لا يوجد طراز مضمَّن في التطبيق؛ اختر طرازًا من الكتالوج أو استورد ملف GGUF، ثم فعّل وضع
عدم الاتصال. F-Droid قيد الإعداد وليس منشورًا بعد، وGoogle Play قيد الاختبار المغلق.

اقرأ المزيد في [README.ar.md](README.ar.md).

</div>

## Build from source

Requirements: JDK 21, Android SDK platform 36 + build-tools 36, Android NDK **27.2.12479018** and CMake 3.22.1
for the native engine, Gradle wrapper 8.10.2 (bundled). The llama.cpp source is vendored under
<code>third_party/llama.cpp</code> — no network fetch is needed for the native build.

~~~powershell
# point these at your own JDK 21 and Android SDK
$env:JAVA_HOME = '<path-to-jdk-21>'
$env:ANDROID_HOME = '<path-to-android-sdk>'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME

# libre debug APK (libre = F-Droid/self-hosted default; play is the Play flavour)
.\gradlew :app:assembleLibreDebug

# 567 JVM tests (the same selection CI runs)
.\gradlew :app:testLibreDebugUnitTest testDebugUnitTest

# signed release APK + Play bundle (reads the git-ignored keystore.properties; unsigned without it)
.\gradlew assembleLibreRelease bundlePlayRelease

# quality gates
.\gradlew :app:lintLibreDebug
pwsh -File scripts/ui-regression.ps1     # 4-screen screenshot regression vs committed baselines
pwsh -File scripts/validate-store.ps1    # 79 checks: listing limits, graphics hashes, launch docs
pwsh -File scripts/release-check.ps1     # build + signatures + store assets + screenshots -> build/release-check.json
~~~

More detail: [CONTRIBUTING.md](CONTRIBUTING.md), [docs/05-architecture.md](docs/05-architecture.md) and
[docs/07-test-environment-emulator.md](docs/07-test-environment-emulator.md).

## Repository map

| Path | Contains |
|---|---|
| <code>app/</code> | application shell, composition manifest, host services, engine plugins, download service, settings/library/about plugins, assets |
| <code>core/runtime/</code> | <code>AppContext</code>, typed slots, config validation, service contracts, licence registry + SBOM builder |
| <code>core/design/</code> | theme packs + WCAG contrast gate, tokens, typography, shell, chat/settings components |
| <code>core/rag/</code> <code>core/speech/</code> <code>core/models/</code> <code>core/privacy/</code> | pure-JVM policy cores: retrieval, speech, model management, privacy |
| <code>feature/*</code> | chat, settings, voice, models, privacy plugins contributing through typed slots |
| <code>engine/llamacpp/</code> <code>engine/whisper/</code> | native llama.cpp engine; sherpa-onnx Whisper adapter |
| <code>docs/</code> | public product and build records (20 documents) |
| <code>store/</code> | Play/F-Droid listing copy, screenshots, generated graphics, store spec |
| <code>scripts/</code> | build/release/test/store/site quality scripts |
| <code>website/</code> | bilingual static site generated from data, deployed to GitHub Pages |

## Extending it without hardcoding

Everything a user can change is data, not code:

- **Features** are Gradle modules composed by [AppComposition.kt](app/src/main/java/app/sirrak/AppComposition.kt);
  order and enablement come from <code>app/src/main/assets/config/app.json</code> with no rebuild.
- **Cross-feature UI** goes through typed slots in [Slots.kt](core/runtime/src/main/java/app/sirrak/core/runtime/Slots.kt);
  a feature never imports another feature.
- **Theme packs** are one data object each in [ThemePacks.kt](core/design/src/main/java/app/sirrak/core/design/ThemePacks.kt)
  and the contrast gate re-checks every pack.
- **Copy** lives in the resource files; **models, prompts and demo content** are JSON assets; **store graphics**
  are rendered from [brand.json](store/graphics/brand.json); **the website** is rendered from
  [website/site.json](website/site.json).
- **Licences** are one registry entry: About, NOTICE and the SBOM all derive from it, and drift tests fail the
  build if they diverge.

The rules are written down in [CONTRIBUTING.md](CONTRIBUTING.md) and [docs/17-customization-council.md](docs/17-customization-council.md).

## Quality and evidence

| Gate | Result on <code>1.0.0-rc1</code> |
|---|---|
| JVM unit tests | **567 tests, 0 failures** across 9 modules |
| Arabic evaluation | **24/24** cases (normalisation, sentence splitting, BM25, negative controls) |
| Android lint | **0 errors**, 33 reviewed warnings |
| Screenshot regression | **4/4 screens, distance 0** against committed baselines |
| Store validator | **79/79 checks** (copy limits, graphics hashes, launch docs) |
| Release gate | [<code>scripts/release-check.ps1</code>](scripts/release-check.ps1) builds, verifies v2 signature + certificate, checks the AAB, validates store assets and runs the screenshot gate |
| SBOM | CycloneDX 1.5, 22 components, generated from the licence registry, drift-tested |
| Accessibility | 48 dp targets, 200% text, RTL mirroring, TalkBack labels, WCAG contrast gate |

## Limits (honest)

- **Performance** is measured on an emulator (API 36 x86_64), not a physical phone; arm64 builds are provided
  but not benchmarked here.
- The APK is **universal** (arm64-v8a + x86_64, ~139 MiB) with no ABI splits yet, and signing is **v2 only**
  (v3/v4 key rotation not configured).
- **Model quality varies.** The catalogue ships no bilingual vision model; Arabic vision questions are a known
  limitation. Small models can ignore citations; a deterministic fallback covers that.
- **F-Droid is not published** — the prebuilt sherpa-onnx AAR needs a source build first. **Play is not
  published** — closed testing has not started.
- The policy pages are published in both languages, but no store listing exists yet, so the app shows an
  honest state where that URL is still missing.
- CI runs JVM tests, the Arabic eval and lint; device tests, screenshot regression and release building are
  local (they need an emulator or signing keys) — see [docs/27](docs/27-sirrak-launch-checklist.md).

## Documentation

- [docs/README.md](docs/README.md) — every public build record, indexed.
- [docs/26-sirrak-p3-polish-launch.md](docs/26-sirrak-p3-polish-launch.md) — launch candidate record.
- [docs/27-sirrak-launch-checklist.md](docs/27-sirrak-launch-checklist.md) — what is done and what is left.
- [docs/28-public-launch-council.md](docs/28-public-launch-council.md) — repository/site/SEO council decisions.
- [DESIGN.md](DESIGN.md) — the Girih & Ink design system.
- [PRODUCT.md](PRODUCT.md) — product scope and promises.

## Licence, credits and security

Sirrak is licensed under the [Apache License 2.0](LICENSE). Third-party components, fonts and models are listed
in [NOTICE](NOTICE); the full licence texts ship inside the app. Model weights are not bundled: each model is
downloaded or imported by you under its own licence.

Security issues: please use the [private advisory form](https://github.com/alshahia/sirrak/security/advisories/new),
never a public issue. See [SECURITY.md](SECURITY.md). Bugs and feature requests go to
[Issues](https://github.com/alshahia/sirrak/issues); contributing rules are in [CONTRIBUTING.md](CONTRIBUTING.md).

Sirrak is not affiliated with, endorsed by, or sponsored by any model provider. "llama.cpp", "Whisper",
"Qwen", "Llama", "Gemma" and other names are trademarks of their respective owners.
