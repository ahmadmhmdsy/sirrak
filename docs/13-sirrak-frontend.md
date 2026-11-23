# 13 — Sirrak frontend (M1): the product shell

**Status:** M1 built, installed and verified on the Nawa_Phone emulator, **18 November 2026**.
**Successor:** M2 (the real on-device engine) was built later the same day — see
[docs/14-sirrak-m2-engine.md](14-sirrak-m2-engine.md). Everything in this document still holds as the
M1 record; M2 only added a module, a plugin and engine state, and changed chat stop semantics.
**Product code:** `.` (Apache-2.0, offline, no telemetry) · **Brand:** Sirrak (سِرِّك)
**Decision record:** doc 12 (frontend council, mode C). **Step Zero evidence:** doc 10.
**Estimate:** ~2.8k lines of Kotlin in 36 files across five Gradle modules, plus five bundled font files
(two families).

This document is the durable record of what the M1 frontend is, why it is shaped this way, what was
verified, what is deliberately missing, and how to change it without breaking it. It is written so
that the next session (or a new contributor) can open the project cold and work safely.

## 0. What exists

A complete, Arabic-first Android app shell that runs without any model file: five plugins (chat,
library, settings, about, demo engine), a design system, a typed plugin/slot composition runtime,
asset-driven configuration and localisation in Arabic and English. It is a real installable product
surface — the engine behind it is a deterministic demo provider until M2 wires the Step Zero engines in
through the same interface.

| | Light, Arabic (default) | Dark | English (LTR) | Font scale 1.3 |
|---|---|---|---|---|
| Chat | ![Chat empty](assets/sirrak-m1-chat-empty.png) | ![Chat dark](assets/sirrak-m1-chat-dark.png) | ![Chat English](assets/sirrak-m1-chat-en.png) | ![Chat font scale](assets/sirrak-m1-chat-fontscale.png) |
| Settings | ![Settings](assets/sirrak-m1-settings.png) | ![Settings dark](assets/sirrak-m1-settings-dark.png) | ![Settings English](assets/sirrak-m1-settings-en.png) | ![Settings font scale](assets/sirrak-m1-settings-fontscale.png) |

More evidence: [a streamed answer](assets/sirrak-m1-chat-answer.png) ·
[a conversation kept across tab switch](assets/sirrak-m1-chat-return.png) ·
[the same conversation after an Arabic to English reload](assets/sirrak-m1-chat-en-conversation.png) ·
[model catalogue sheet](assets/sirrak-m1-model-sheet.png) ·
[engine sheet, dark](assets/sirrak-m1-model-sheet-dark.png) ·
[library](assets/sirrak-m1-library.png) ·
[about and licences](assets/sirrak-m1-settings-about.png).

## 1. Verdict to factory, ruling by ruling

| Doc 12 ruling | How M1 implements it |
|---|---|
| New product app `.`, five modules | `:app`, `:core:runtime`, `:core:design`, `:feature:chat`, `:feature:settings` — see §2 |
| Cordis-shaped runtime, hand-rolled, no DI, no runtime loading | `AppContext`, `Plugin`, `PluginScope`, `install()` with declared requirements and reverse-order disposal — §3 |
| Typed Compose slot registry, single and list | `Slots.kt` catalogue plus `slotSingle`/`slotList`; undeclared slot use and duplicate single-slot providers throw — §3.4 |
| Manifest + schema-validated asset config | `AppComposition` manifest in Kotlin; `assets/config/app.json` strict-parsed and validated; JSON is override data only — §6 |
| D1 Qalam world + D4 Mizan motif, not D2 Night Glass | The shipped world is **Girih & Ink**: warm ivory grounds, ink type, lapis for action, brass for one geometry, turquoise secondary, madder error; Amiri + IBM Plex Sans Arabic; one 8-point-star mark — §4 |
| H3c spike stays untouched; engine boundary is the seam | `InferenceEngine` interface with `DemoInferenceEngine`; the four spike directories are unmodified — §5.2 |
| Single module downgraded to fallback | Not used. Five modules are green. |
| Stock Material downgraded to platform floor | M3 structure and semantics kept; colour, type, shape, motion and mark are Sirrak's — §4 |
| Real engine, voice, RAG, NPU suspended-on-test | Not in M1; the interfaces and slots they will occupy exist — §8 |

## 2. Architecture

```
                       +-----------------------------------------------+
                       |                    :app                       |
                       | SirrakApplication -> AppComposition           |
                       | MainActivity -> SirrakApp -> SirrakTheme      |
                       | SirrakShell (nav) + slot hosts                |
                       | AssetContentRepository, SharedPrefsSettings   |
                       | LibraryPlugin, DemoEnginePlugin, AboutPlugin  |
                       +------------+-------------------+--------------+
                                    |                   |
                    +---------------+--+     +----------+-----------+
                    |   :core:design   |     |   :core:runtime      |
                    | tokens, type,    |     | AppContext, Plugin,  |
                    | theme, shell,    |     | PluginScope, Slots,  |
                    | chat/settings    |     | AppConfig, Contracts,|
                    | components       |<----+ Destinations, Config  |
                    +------------------+     +----------+-----------+
                                                    ^        ^
                                    +---------------+--+  +--+----------------+
                                    |   :feature:chat   |  | :feature:settings |
                                    | ChatPlugin        |  | SettingsPlugin    |
                                    | ChatStore, screen |  | SettingsScreen    |
                                    +-------------------+  +-------------------+
```

Dependency direction is one-way: features depend on `core:runtime` and `core:design`; the app
depends on everything; no core module depends on a feature. `:feature:chat` does not know the settings
screen exists, and `:feature:settings` does not know chat exists.

| Module | Files / lines | Responsibility |
|---|---|---|
| `:core:runtime` | 8 / 453 | Composition container, plugin contract, typed slots, routes, config schema and validation, service contracts, Android helpers. No Compose UI. |
| `:core:design` | 9 / 1,143 | Colour/type/shape/motion tokens, Material theme mapping, shell/nav, chat components, settings components, brand mark, shared composables. |
| `:feature:chat` | 3 / 313 | `ChatPlugin`, `ChatStore` (conversation state, streaming, cancellation), `ChatScreen`. |
| `:feature:settings` | 2 / 125 | `SettingsPlugin`, `SettingsScreen` (appearance, language, engine group, host section slots). |
| `:app` | 14 / 743 | Application, manifest, composition, content repository, preferences, demo engine, library, about, shell wiring, unit tests. |

### 2.1 Adding a feature (the recipe)

1. **Create the module** `:feature:weather` in `settings.gradle.kts` and the version catalog if it
   needs new libraries.
2. **Write the plugin** in the new module:
```
   class WeatherPlugin : Plugin {
       override val id = "weather"
       // Declare the services it needs, not the plugins that provide them.
       override val requires = listOf(ContentRepository::class.java)
       override fun install(scope: PluginScope) {
           val content = scope.service(ContentRepository::class.java)
           scope.destination(
               Destination(
                   id = "weather",
                   labelRes = R.string.nav_weather, // add the string and a NavIcon entry too
                   icon = NavIcon.LIBRARY,
                   order = 15,
                   content = { WeatherScreen(content) },
               ),
           )
       }
   }
```
3. **Add one line** to the manifest in `app/src/main/java/app/sirrak/AppComposition.kt`:
```
   WeatherPlugin(),
```
4. **Add the module dependency** in `app/build.gradle.kts` (`implementation(project(":feature:weather"))`).
5. **Optional:** reorder or disable it in `app/src/main/assets/config/app.json` without a rebuild.
6. **Verify:** the build, `AppCompositionTest`, and the app on the emulator. The nav bar, the
   destination and back behaviour are built from installed plugins; no other file names the feature.

Removing a feature is the same list in reverse: delete the manifest line and the module. M1 verified
this with the About feature — see §7.4.

### 2.2 Adding to an existing screen (without touching the screen)

Screens expose declared slots. A plugin anywhere can contribute:

```
// In some plugin's install():
scope.slotList(Slots.ChatTopBarTrailing) { ModelChip() }
scope.slotList(Slots.ChatComposerTrailing, order = 10) { VoiceButton() }
```

| Slot | Kind | Host | Current providers |
|---|---|---|---|
| `ChatTopBarTrailing` | list | Chat header, trailing edge | `DemoEnginePlugin` (model chip that opens the engine sheet) |
| `ChatComposerTrailing` | list | Composer trailing row, before send | None in M1 — declared and rendered, reserved for voice and attachments |
| `SettingsSections` | list | Settings screen body (all groups are contributions) | `SettingsPlugin` (appearance order 0, language order 5), `DemoEnginePlugin` (engine order 10), `AboutPlugin` (about + licences order 30) |

The slot contract is typed by `SlotKey` plus `SlotKind`; contributing into a key that was never
declared, registering the wrong kind, or giving two destinations the same id is a loud failure
(unit-tested; §7.1). The M1 catalogue is all list slots; the runtime also supports single slots for
future header actions where exactly one provider is required.

## 3. The Cordis technique, transferred statically

DSH's Cordis engine composes services and UI from plugins with owned lifecycle and loud failures. Sirrak
takes the *cause* — declarative composition with owned cleanup and typed extension points — and drops
what a phone app does not need (runtime loading, dynamic manifests, hot reload). The runtime is
hand-rolled, ~450 lines, no DI framework.

### 3.1 Container lifecycle

```
val context = AppContext(config)                    // root: the only mutable registry
context.provideService(Context::class.java, app)    // host services
context.provideService(AppNavigator::class.java, AppNavigator(initial = null), owner = "host:navigation")
val order = context.install(manifest)               // dependency-ordered; returns installed ids
```

- **Services** are provided once; a duplicate provider is a loud failure.
- **Install** resolves `Plugin.requires` (service classes provided by earlier plugins) in dependency
  order; missing providers and dependency cycles fail loudly, naming what each pending plugin still
  needs. Duplicate plugin ids are rejected before anything installs.
- **Effects** registered in a `PluginScope` are disposed in reverse installation order on
  `context.close()`. In M1 the app does not close the root in normal use (process death is the
  boundary), but tests exercise teardown and `ChatPlugin` registers a scope effect that clears its
  store when the container closes.
- **Debug builds fail loudly** on composition errors; release builds log and degrade rather than crash.

### 3.2 What was deliberately not transferred

| Cordis / DSH feature | Sirrak decision | Why |
|---|---|---|
| Runtime plugin loading from disk | Not transferred | A Play Store app ships one composition; dynamic loading adds attack surface and review risk for no benefit. |
| Fibers, hooks, hot reload | Not transferred | Developer-only; M1 needs a clean process lifecycle, not a devserver. |
| YAML bundles, agent presets | Replaced by Kotlin manifest + JSON overrides | Membership must be compile-checked; JSON is only safe overrides (theme, order, enabled, limits). |
| Service injection by string | Replaced by typed `service(Class)` | Kotlin type safety; a typo is a compile error. |
| Context-scoped listeners | Replaced by `PluginScope.effect` | Compose lifecycle already owns UI effects; effects cover the non-UI cases. |
| Dynamic theme, locale services | Static Compose locals + resource qualifiers | Fewer moving parts; language reload is an Activity recreate. |

### 3.3 Slots and routes

`Destinations` is the route registry: `Destination(id, labelRes, icon, order, content)`. The bottom bar in
`SirrakShell` is generated from installed destinations sorted by `order`; tab state and back stack are
owned by `AppNavigator` (a host service, so tab switches and Activity recreation keep the current tab
and the chat conversation). "Chat" is the fallback route; there are no hardcoded route strings outside
the destination declarations and config.

## 4. Design system: Girih & Ink

**Concept.** A quiet research desk in Baghdad: warm paper, dark ink, a lapis seal, a brass compass. One
authored geometric signature — the eight-point girih star, drawn with a Canvas — appears only where the
app is thinking or where the brand signs its name. No gradients, no glass, no AI sparkles, no chat
bubbles for prose.

**Why these constraints** (from doc 12; the Curator objection survives): lapis is the only action colour;
brass is geometry and hairlines only, never text on light (contrast) — the text-safe brass is a separate
token `BrassTextLight #6F561A`; turquoise is a secondary state colour only; madder is error only; ivory
and night grounds are warm-neutral, never pure white/black. Dynamic colour is off: the palette is the
brand.

**Palette tokens** (single source since M4: `core/design/.../ThemePacks.kt` — the `girih_ink` pack;
no hex at call sites. The values below are that pack's light/dark palettes):

| Role | Light | Dark |
|---|---|---|
| Ground / surface | `#F7F3EA` / `#FDFAF3` | `#0F1416` / `#121A1D` |
| Ink / muted ink | `#1B2430` / `#5C6672` | `#E9E4D6` / `#A9B1AF` |
| Lapis (action) | `#1D3F6E` | `#9DB8DD` |
| Brass (geometry) / brass text-safe | `#B08D3E` / `#6F561A` | `#D6B568` / `#D6B568` |
| Turquoise (secondary) | `#1F6F6B` | `#7FC0B9` |
| Madder (error) | `#A63A2E` | `#E8A79C` |
| Hairline | `#E3DCCB` | `#243134` |

**Type.** Amiri (SIL OFL 1.1) for display, Arabic prose and the wordmark; IBM Plex Sans Arabic (SIL OFL
1.1) for UI, body, labels and Latin. Display 56/64, headline small 24/34, title large 20/28, title
medium 16/24, body 16/26, body medium 14/22, labels semibold; assistant prose is 19/33 Amiri with
relaxed line height. Sizes are declared once in `Type.kt` and consumed as M3 text styles.

**Shape, elevation, motion.** 6/10/14/20/28 dp radii; flat surfaces with hairline outlines and a soft
surface-container ladder instead of shadows; the only authored motion is the inking star (assistant
"thinking" mark) and a 240 ms fade/slide between destinations, using `FastOutSlowInEasing` at
140/240/360 ms. Reduced-motion handling and animation polish are M2 items (§8).

**Components** (all in `:core:design`, reused by features): `SirrakTheme`, `SirrakShell` (nav), `Wordmark`,
`GirihMark`/inking star, `ScreenTitle`, `ReadingColumn`, `SettingsSection`/`SettingsKeyValueRow`/
`SettingsChoiceRow`, `ChatComposer`, `AssistantProse`, `UserNote`, `SourceChip`, `SuggestionCard`,
`EmptyConversation`, `ModelSheet`, `SettingsGroup`, and since M4 `ThemePackOption`/`ThemePackPreview`
(the theme-pack picker, composed from the `ThemePacks` registry).

**RTL and localisation.** Arabic is the default and primary target; the whole UI mirrors correctly with
logical start/end padding and `LayoutDirection.Rtl`. Latin values (quantisation, versions) are forced
LTR inside RTL rows. Arabic shaping was checked on-device at 1.0 and 1.3 font scale in every screenshot
above; English mirrors to LTR with the same components. Strings live in `values/strings.xml` and
`values-en/strings.xml` — no user-visible string is hardcoded in Kotlin.

## 5. Feature plugins

### 5.1 Plugin table (M1)

| Plugin | id | order | Requires | Provides |
|---|---|---|---|---|
| `LibraryPlugin` | `library` | 10 | `ContentRepository` | Library destination (model catalogue cards, honest "Step Zero" status labels) |
| `DemoEnginePlugin` | `engine` | — | `ContentRepository` | `InferenceEngine` service (demo provider), chat model-chip slot, engine settings group |
| `AboutPlugin` | `about` | — | — | About + licences group on Settings (proves a settings group can live outside `:feature:settings`) |
| `ChatPlugin` | `chat` | 0 | `InferenceEngine`, `ContentRepository` | Chat destination, store, cleanup effect |
| `SettingsPlugin` | `settings` | 20 | `SettingsService` | Settings destination plus its appearance and language groups |

Order is the nav order: chat (0), library (10), settings (20); slot orders place engine details (10)
and about (30) below the appearance/language groups. `DemoEnginePlugin` and `AboutPlugin` contribute no
destination — exactly the point of slots.

### 5.2 Engine boundary

`InferenceEngine` is the seam:

```
interface InferenceEngine {
    val id: String
    val displayName: String
    class Provider(val engine: InferenceEngine, val source: String)
    fun reply(prompt: String, history: List<Message>): Flow<String>
}
```

`DemoInferenceEngine` streams deterministic Arabic demo answers from `assets/content/demo_chat.json`
(token by token with a small delay), with source references, so every UI state can be exercised offline
with no model file. What M2 replaces: wire the Step Zero llama.cpp / LiteRT-LM engines behind the same
interface, selected by `config.engine.provider`; the UI does not change.

### 5.3 Chat state

`ChatStore` is process-scoped, created by `ChatPlugin` with an app-owned `CoroutineScope`, and exposed
through the runtime. It owns messages, streaming state, cancellation, retry and clearing. Switching tabs
or recreating the Activity (language change) keeps the conversation; force-stop or process death clears
it (persistence is M2). Sending while streaming is prevented; stop cancels the flow and cannot leave a
stale streaming bubble behind (regression-fixed; see §7.5).

### 5.4 Settings

Settings is deliberately built from sections, not a settings framework. The screen renders a header and
the `SettingsSections` slot host; every group — appearance (light/dark/system segmented), language
(Arabic/English, saves and recreates), engine (demo provider status, honest "no model loaded" copy),
about (from the separate `about` plugin) — is a slot contribution, including the settings plugin's own
appearance and language groups. That is how a feature can add a settings group without editing the
screen. It reads/writes through `SettingsService` (`SharedPreferences`), whose defaults come from
`app.json` — so the config file remains the one place that decides first-run theme and language.

## 6. Configuration and content

| File | What it decides | Safe to edit without rebuild? |
|---|---|---|
| `app/src/main/assets/config/app.json` | schema version, feature order/enabled, default theme and language, engine provider, content paths, max content width, timestamps | Yes (strict-parsed, validated) |
| `app/src/main/assets/content/demo_chat.json` | suggested prompts (Arabic + English glosses), demo answers with source refs, fallback | Yes |
| `app/src/main/assets/content/models.json` | model catalogue cards in Library: id, display name, parameter/quant/size/RAM labels, status | Yes |
| `AppComposition.kt` manifest | which feature plugins exist in this build | No — that is the point |
| `core/design/.../ThemePacks.kt` | the five theme-pack palettes and the WCAG contrast gate (M4; `girih_ink` is the brand default) | No — design is compiled |
| `core/design/.../Tokens.kt`, `Type.kt` | shape, motion, decorative tokens composed from the selected pack; type (spacing follows the 4dp grid with local dp values; a spacing-token object is still deferred) | No — design is compiled |

Config semantics: `features.enabled` empty means "all manifest plugins"; `features.order` wins over
manifest order; unknown/mismatched keys are rejected by the strict parser (a typo cannot silently disable
something) while stale ids after a feature removal are logged and ignored so removal stays one line.
Validation covers schema version, theme mode, language, duplicate/unknown order ids, content paths and
UI limits. Debug builds throw on invalid config; release logs and uses defaults.

## 7. Verified on the emulator

Emulator Nawa_Phone: API 36 x86_64, 720x1280, 320 dpi, 6 GB. Build: Gradle 8.10.2, AGP 8.8.0,
Kotlin 2.0.21, JDK 21, Compose BOM 2024.10.00. `compileSdk/targetSdk 34`, `minSdk 28`.

### 7.1 Tests — 18 green

| Suite | Tests | Covers |
|---|---|---|
| `:core:runtime` `AppContextTest` | 8 | install dependency order, missing service, duplicate plugin ids, duplicate destination ids, undeclared slot, list-slot ordering, reverse-order disposal, config validation |
| `:app` `AppConfigTest` | 5 | shipped asset parses and validates, unknown key fails, malformed JSON fails, defaults valid, enabled must be in order |
| `:app` `AppCompositionTest` | 5 | shipped order, removing a manifest line with untouched config, stale enabled entry ignored, disable via config, config order wins |

### 7.2 Build and artifact sizes

| Artifact | Size | Note |
|---|---|---|
| Debug APK | 58,224,534 B (55.5 MiB) | Unminified, includes Compose tooling and the extended Material icon set; development only. |
| Release APK, R8 | 2,934,240 B (2.80 MiB) | `isMinifyEnabled` + `isShrinkResources`; unsigned artifact in `app/build/outputs/apk/release/`. |

The M1 target was ≤8 MB; the release build is well inside it. The debug size is recorded honestly as the
reason to keep release the size gate. The release APK was signed with the local debug key, installed,
launched, and exercised (demo answer streamed, plugins installed, no crash) to prove R8 and the
serialization rules are correct — the production signing key is P3 work.

### 7.3 Cold start

| Build | Cold start (stopped) |
|---|---|
| Release (R8, signed debug key) | Final build: 1,201 / 1,248 / 1,410 ms. Earlier build: 1,506 / 1,508 / 1,543 / 1,547 ms; 1,813 ms on the very first launch after install (dexopt) |
| Debug | 4,215 / 4,340 / 4,761 ms |

M1 target was ≤1.5 s. The final release build meets it on this emulator across three runs (1.20–1.41 s),
but earlier measurements of the same shell sat at 1.51–1.55 s, so treat it as at the threshold until a
baseline profile lands and the test phone gives real numbers. Debug is not a meaningful timing
(debuggable, JIT). Baseline profiles and a real-device measurement are M2 items.

### 7.4 Add/remove acceptance: one manifest line

Verified live, not merely asserted: removing the `AboutPlugin()` line from the manifest and rebuilding
booted with `Installed plugins: [library, engine, chat, settings]`, the About/licences section
disappeared from Settings (screenshot:
[about-removed.png](assets/sirrak-m1-about-removed.png)), and nothing else changed. Restoring the one
line restored five plugins and the section. While removed, the untouched `app.json` still named
`about`; the resolver logged and ignored the stale id instead of crashing — exactly why the shipped
config now uses `enabled: []` ("all manifest plugins"). `AppCompositionTest` pins this behaviour.

### 7.5 Screen and behaviour checks

- **Arabic shaping:** all 20 screenshots above, no clipped or mis-joined glyphs at 1.0 and 1.3 font scale.
- **RTL:** bottom bar order, composer, settings rows, source chips, nav indicator; English LTR mirrors correctly.
- **Dark theme:** chat, settings and engine sheet in dark (screenshots above); contrast checked on device.
- **Language switching:** Arabic → English → Arabic recreates the Activity; current tab and conversation
  survive (screenshots `chat-return` and `chat-en-conversation` show the same answer in both UIs).
- **Streaming and stop:** demo answer streams token by token with the inking star; stop cancels; retry works.
- **Navigation:** destination tabs are generated from plugins; back exits cleanly; no crash in logcat.
- **Engine sheet:** model chip in chat and engine settings group open the same sheet; catalogue labels are honest.
- **Theme packs (M4):** five packs × light/dark × AR/EN screenshots, restart persistence, font scale 1.3 and
  the R8 release are recorded in [doc 18](18-sirrak-m4-theme-packs.md) §5.

## 8. Limits (what M1 does not claim) and next

**Not true yet — do not demo as if it were:**

- The chat engine is the deterministic demo provider. No model is loaded, no inference happens.
- Demo content is Arabic-only; UI is fully localised. English demo replies are M2.
- Chat is process-scoped: lost on force-stop/process death (tab switch and Activity recreate survive).
- No microphone input yet (the composer keeps an optional mic affordance, unwired), no TTS, retrieval,
  downloads, persistence, accounts or network. The app is offline and permission-free by construction.
- No release signing, no baseline profile, no crash reporting (by policy), no Play submission work.
- The "arch shape" and richer composer (attachments/voice) are deferred; no reduced-motion handling beyond
  Compose defaults; no tablet/foldable layout pass yet.
- Tested on API 36 emulator only; physical arm64 phone validation is pending the device.

**M2 queue, in order:** (1) wire the H1 llama.cpp engine behind `InferenceEngine` with model
download/import and honest loading states; (2) Room persistence for conversations and settings; (3)
English demo content + language-correct demo answers; (4) voice input/TTS through the H3 stack, with
permission and privacy UX; (5) retrieval over a user library; (6) baseline profile + cold-start work;
(7) release signing and Play internal testing; (8) reduced-motion and tablet layout passes.

## 9. Working in the project

```
.
├─ app/                 application, manifest, composition, host services, demo engine, assets
├─ core/runtime/        container, plugin/slot contracts, config, routes, services
├─ core/design/         tokens, theme, shell, chat/settings components, mark
├─ feature/chat/        chat plugin, store, screen
├─ feature/settings/    settings plugin, screen
├─ PRODUCT.md           product truth (positioning, users, non-negotiables)
├─ DESIGN.md            design system record (world, tokens, components, deferred items)
└─ README.md            build/run/test commands and code map
```

Common commands (PowerShell, from `.`):

```
# build + install the debug app
$env:JAVA_HOME = '<path-to-jdk-21>'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
& .\gradlew.bat :app:assembleDebug
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app/build/outputs/apk/debug/app-debug.apk

# all JVM tests and the minified release artifact
& '...\gradle.bat' :core:runtime:testDebugUnitTest :app:testDebugUnitTest :app:assembleRelease

# change a label: app/src/main/res/values/strings.xml (Arabic) and values-en/strings.xml
# change a pack colour: core/design/.../ThemePacks.kt (registry data; the contrast gate re-runs)
# change shape/motion: core/design/.../Tokens.kt (never hex at call sites)
# add/remove a feature: AppComposition.kt manifest +/- one line (+ Gradle module)
# reorder/hide a tab without rebuild: app/src/main/assets/config/app.json features.order / enabled
```

**Five rules that keep it unbreakable:** colours and type only from tokens (no hex literals in features;
spacing follows the 4dp grid); strings only from resources (no user-visible text in Kotlin); one manifest
line per feature; slots for cross-feature UI, never direct feature-to-feature imports; loud failure in
debug, graceful log in release.

## 10. Amendment — 19 Nov 2026: theme packs, landed in M4 (doc 18)

The M1 design record in §4 stands: Girih & Ink, Amiri + IBM Plex Sans Arabic, lapis-as-action, 48 dp,
"no dynamic colour: brand fidelity wins". The founder then asked for user-selectable themes; the
Customization Council (doc 17) ruled **curated data-only packs**, not a colour editor and not Material You
([doc 17 §7.1](17-customization-council.md)), and M4 delivered exactly that. The build record with
screenshots, contrast measurements and limits is [doc 18](18-sirrak-m4-theme-packs.md).

What changed in the design system, and what stayed the same:

- The M1 palette now lives as `ThemePalette` data in the `ThemePacks.kt` registry. Call sites still use
  tokens through `MaterialTheme.colorScheme` and `LocalSirrakTokens` — the rule "no hex at call sites"
  is preserved exactly; `Tokens.kt` holds no palette values.
- **Girih & Ink is `ThemePacks.default` with its M1–M3 hexes byte-identical**, pinned by
  `defaultPackReproducesTheM3ColourSchemeExactly`. The M1 evidence above therefore remains valid for the
  default pack.
- Four packs joined it: Lapis Night, Turquoise Dawn, Madder Dusk, Paper. Packs change the ground, surface,
  accent, error and outline families plus the four decorative tokens only; geometry, type scale, spacing,
  motion and the lapis-as-action rule are fixed across packs.
- Appearance gained a preview picker composed from the registry; a pack switch recomposes instantly (no
  `recreate()`, unlike language) and is remembered across restarts.
- Acceptance was met with the M1 evidence standard: every pack × light/dark × AR/EN screenshots,
  production contrast tests on every painted pair (20 per pack/mode, 4.5:1 body / 3:1 UI; Paper is AAA),
  font scale 1.3, RTL mirroring, restart persistence, and the R8 release executed on the emulator.
- **One defect found during that evidence run and fixed centrally:** Latin settings values (the About
  version, licence ids) were laid out in RTL bidi and read `m4-0.1.0`. A shared
  `settingsValueTextDirection()` rule now LTRs Latin-only values and keeps Arabic values content-directed,
  for both settings row types, covered by 4 JVM tests (doc 18 §3.7).

*M4 and M5 are closed; the app at `0.1.0-m5` is the current build (M5 record: doc 19). The next
milestone is the timeboxed M5b template-build spike, then M6 chat library and reading surface.*

---

## Appendix — evidence index

- Chat: `sirrak-m1-chat-empty.png`, `-streaming`, `-answer`, `-return`, `-ar-conversation`, `-en-conversation`, `-dark`, `-en`, `-fontscale`
- Settings: `sirrak-m1-settings.png`, `-dark`, `-en`, `-fontscale`, `-about`, `sirrak-m1-about-removed.png`
- Library / engine: `sirrak-m1-library.png`, `sirrak-m1-model-sheet.png`, `sirrak-m1-model-sheet-dark.png`
- Release build: `sirrak-m1-release-empty.png`, `sirrak-m1-release-answer.png`
- M4 theme packs: see [doc 18](18-sirrak-m4-theme-packs.md) §5.1 — `sirrak-m4-*` screenshots (five packs ×
  light/dark, AR/EN, font scale, persistence, R8 release)