# 18 — Sirrak M4, appearance: curated theme packs (19 Nov 2026)

**Status:** **M4 closed** — appearance is now data, not code: five curated colour packs ship in a
registry, every painted text/UI pair is gated by a production contrast list, the picker is composed from
the registry, and the selection is remembered. **17 new JVM tests (72 total, all green)**, verified on
the emulator in the debug build **and** in the R8 release build. Version advanced
`0.1.0-m2` → **`0.1.0-m4`** (the first version bump since M2; M3 was delivered as increments under m2,
see docs 15/16). No engine, persistence, navigation or chat behaviour changed.

This is the record promised at the end of [doc 17](17-customization-council.md): the founder asked for
user-selectable themes, the Customization Council ruled **curated data-only packs** (F1: no Material You,
no colour editor), and M4 executed that verdict under the standing build gate — reusable, expandable,
editable later.

---

## 1. What the user can now do

- **Settings → Appearance** shows the theme mode (day/night/system) and, below it, a **Theme pack** group
  listing all five packs. Each row carries the localised name, a one-line description, and a live
  two-tile preview of its light and dark palettes.
- Tapping a pack **recomposes the whole app instantly** — every screen, sheet and state — with no
  Activity recreate (unlike language).
- The choice is **remembered across restarts and process death**; the app opens in the chosen pack.
- **Girih & Ink is the default** for a fresh install or cleared data, pinned byte-for-byte to the M1–M3
  palette.
- Every pack is a complete light + dark palette. Switching day/night inside any pack keeps the pack.
- **Arabic and English** both list the packs with localised names and descriptions; the picker mirrors
  RTL/LTR like every other settings row.
- **Paper** is the maximum-clarity pack: pure-white/pure-black grounds, AAA body contrast (7.19:1 light /
  7.48:1 dark), no decorative colour.
- A corrupted, renamed or unknown saved pack id is **not** a crash: the registry resolves it to the brand
  default. A blank `theme.defaultPack` in `app.json` **is** rejected by config validation.
- The version row in About also got a small correctness fix found by this milestone's evidence: Latin
  version strings are now laid out LTR inside the RTL screen, so it reads `0.1.0-m4` instead of the
  bidi-mangled `m4-0.1.0` (§3.7).

Nothing else changes: same shell, same chat, same engine, same persistence rules, same 48 dp targets.

---

## 2. The pack catalogue

Names and descriptions are UI copy (localised resources), never marketing names. Ids are stable persisted
keys — never localised, never reused for a different pack.

| pack id | Arabic name | English name | Light ground · ink · action | Dark ground · ink · action |
|---|---|---|---|---|
| `girih_ink` | جِرِه والحِبر | Girih & Ink | `#F7F3EA` · `#1B2430` · `#1D3F6E` | `#0F1416` · `#E9E4D6` · `#9DB8DD` |
| `lapis_night` | ليل لازوردي | Lapis Night | `#EFF2F6` · `#182333` · `#1E4173` | `#0E1526` · `#EDE7D8` · `#9DBBE4` |
| `turquoise_dawn` | فجر فيروزي | Turquoise Dawn | `#F3F2E5` · `#1A2726` · `#14615E` | `#0C1A18` · `#E8E9DC` · `#6FD0C4` |
| `madder_dusk` | غروب الفُوَّة | Madder Dusk | `#F6EFE9` · `#241A1B` · `#9C3A2E` | `#17100F` · `#EFE3D9` · `#DE9182` |
| `paper_contrast` | ورق | Paper | `#FFFFFF` · `#101214` · `#14417A` | `#000000` · `#F5F2EA` · `#9EC2EC` |

Localised copy shipped in `:core:design` resources:

| pack | Arabic copy | English copy |
|---|---|---|
| Girih & Ink | جِرِه والحِبر — الأصلي: عاجي وحِبري، لازوردي ونُحاسي. | The original: ivory and ink, lapis and brass. |
| Lapis Night | ليل لازوردي — أرضية لازوردية عميقة للقراءة الليلية. | Deep lapis grounds for night reading. |
| Turquoise Dawn | فجر فيروزي — ورق دافئ وفيروزي عميق، كفجرٍ على دجلة. | Warm paper and deep teal, like dawn on the Tigris. |
| Madder Dusk | غروب الفُوَّة — دفء السوق: عاجي ورديّ، فُوَّة ونُحاس. | Bazaar warmth: rose ivory, madder and brass. |
| Paper | ورق — أقصى وضوح: حبرٌ على ورق، بلا زخرفة لونية. | Maximum clarity: ink on paper, no colour decoration. |

The full 36-role palettes (containers, inverse roles, on-colours, outline, error, decorative girih/brass/
hairline) live in one place: `core/design/src/main/java/app/sirrak/core/design/ThemePacks.kt`. This doc
deliberately shows only the identity colours; the code is the single source of truth.

---

## 3. Design

### 3.1 What a pack may and may not change

The council split "theme" into data and identity (doc 17 §7.2):

| A pack controls | Fixed across every pack |
|---|---|
| ground, surface and the 5-step surface-container ladder | geometry, radii, spacing, 4 dp grid |
| ink / muted ink and the on-* colours that pair with them | type families, sizes, line heights (Amiri + IBM Plex Sans Arabic) |
| the accent families: primary, secondary, tertiary, error and their containers | motion durations and the inking star |
| outline / outline-variant / inverse roles | 48 dp touch targets, slot and screen layout |
| the four decorative tokens (girih line, brass, brass-text, hairline) | the **lapis-as-action rule**: exactly one action colour per pack |

The lapis-as-action rule is re-expressed rather than dropped: in each pack the `primary` family is the
only action colour, and the picker preview shows it as the middle bar. A pack never introduces a second
competing accent. This is what keeps five palettes looking like one product.

### 3.2 Three types, one registry, zero call sites that know a pack

```
ThemePalette   36 ARGB long roles — pure Kotlin, JVM-testable, no Compose Color in the data
   ↑ light + dark
ThemePack      id + nameRes + descriptionRes + light + dark
   ↑ registry order = picker order
ThemePacks     all, default (first), byId(raw), isKnown(raw), ids()
```

- Data is **ARGB longs**, not Compose `Color`: the registry, the contrast gate and any future
  import/export stay pure Kotlin and run on the JVM without a device.
- `ThemePack.colorScheme(dark)` maps the palette onto Material 3 roles; `ThemePack.decorations(dark)`
  maps girih/brass/brass-text/hairline onto Sirrak's own tokens. **Call sites keep using
  `MaterialTheme.colorScheme` and `LocalSirrakTokens.current`** — the M1 rule "no hex at call sites"
  survives exactly; `Tokens.kt` now holds no palette values at all.
- `SirrakTheme(mode, packId)` resolves the pack (`ThemePacks.byId`) and provides tokens + MaterialTheme.
  `ThemePacks.byId` trims and falls back to the default for `null`, blank, unknown, case-mismatched or
  removed ids — **never crash on a stale preference**.
- There are no pack names, ids or colours anywhere in feature code. The picker iterates
  `ThemePacks.all`; `:core:runtime` persists an opaque `String` and does not know packs exist.

### 3.3 The contrast gate is production code, not a test fixture

`ThemePack.contrastPairs(dark)` returns the 20 pairs the UI actually paints, each with a WCAG tier:

| tier | minimum | count | pairs |
|---|---|---|---|
| BODY | 4.5:1 | 16 | onBackground/background · onSurface/surface · onSurfaceVariant/surface · onSurfaceVariant/surfaceVariant · onSurfaceVariant/surfaceContainerLow · onSurface/surfaceContainerLow · onSurfaceVariant/surfaceContainer · onPrimary/primary · onPrimaryContainer/primaryContainer · onSecondary/secondary · onSecondaryContainer/secondaryContainer · onTertiary/tertiary · onTertiaryContainer/tertiaryContainer · onError/error · onErrorContainer/errorContainer · brassText/surface |
| UI | 3.0:1 | 4 | primary/background · secondary/background · error/background · outline/surface |

The pair list is production code, so **adding a pack extends the gate for free**; adding a role that
carries text means adding its pair in the same commit.

Why decorative tokens are intentionally **not** gated: `girihLine` is a printed-texture tone (1.7–2.2:1
by design), `brass` is geometry that never carries text, `hairline` is a 1 dp structural rule, and the
picker's preview swatches are decoration (the selected row is marked by tint + check + radio semantics,
not by colour alone). `brassText` exists precisely because `brass` must never carry text.

The test also checks the **inverseSurface/inverseOnSurface** pair (used by inverse state), plus the
contrast maths itself against known WCAG anchors (black/white = 21:1, identical colours = 1:1).

### 3.4 Measured contrast (production values, JVM gate + independent re-check)

| pack | mode | min body | tightest body pair | min UI | tightest UI pair | inverse |
|---|---|---|---|---|---|---|
| girih_ink | light | 4.73 | onSurfaceVariant/surfaceVariant | 3.13 | outline/surface | 14.61 |
| girih_ink | dark | 6.90 | onSecondary/secondary | 4.94 | outline/surface | 14.13 |
| lapis_night | light | 5.87 | onSurfaceVariant/surfaceVariant | 3.85 | outline/surface | 12.63 |
| lapis_night | dark | 6.71 | onError/error | 4.98 | outline/surface | 12.32 |
| turquoise_dawn | light | 6.14 | onError/error | 4.15 | outline/surface | 12.69 |
| turquoise_dawn | dark | 6.82 | onError/error | 5.09 | outline/surface | 12.32 |
| madder_dusk | light | 6.42 | onPrimary/primary | 4.44 | outline/surface | 13.96 |
| madder_dusk | dark | 6.71 | onPrimary/primary | 4.81 | outline/surface | 13.90 |
| paper_contrast | light | **7.19** | onError/error | **6.10** | outline/surface | 16.45 |
| paper_contrast | dark | **7.48** | onError/error | **5.98** | outline/surface | 16.01 |

Worst cases: 4.73:1 body (default pack, muted ink on surface-variant) and 3.13:1 UI (outline on surface)
— both above their floor. Paper is AAA for body text in both modes. The default pack's tightest body pair
is unchanged from M1–M3, so the gate documents the existing design rather than lowering it.

### 3.5 Selection: contract → config → store → theme

| layer | change |
|---|---|
| `:core:runtime` SettingsService contract | `val themePackId: StateFlow<String>` + `fun setThemePack(packId: String)`. It persists an opaque string; `:core:runtime` must not know the pack list (KDoc says exactly that). |
| `AppConfig` | `ThemeConfig(defaultMode = "system", defaultPack = "girih_ink")`; validation adds "blank `theme.defaultPack`" as a loud config error. Unknown ids are **not** rejected at parse time — they are resolved by the registry at read time, so the config never has to track code changes (and a pack can be removed without touching validation). |
| `SharedPrefsSettingsService` | first read: `ThemePacks.byId(saved ?: config.theme.defaultPack)`; `setThemePack` resolves through the registry and persists the resolved id. Old installs without the key get the config default. |
| `SirrakApp` | collects `settings.themePackId` and passes it into `SirrakTheme(mode, packId)`. |
| `app.json` | `{"theme": { "defaultMode": "system", "defaultPack": "girih_ink" }}` — shipped default; a different default is a config edit, not a code edit. |

The persisted value is the **id**, never the palette: changing a pack's colours later re-themes existing
users, which is what a "Girih & Ink stays the brand" promise requires.

### 3.6 Picker: `ThemePackOption`

One reusable component in `:core:design` (`ThemePackPicker.kt`), composed by `SettingsScreen` from the
registry — the settings feature contains no pack list:

- full-width row, min 56 dp, `selectable(role = Role.RadioButton)` with correct selected semantics;
- selected row: `secondaryContainer` tint, primary check badge (22 dp) and the row's radio state — so the
  selection never relies on colour alone;
- two 32×28 dp preview tiles per pack (light + dark), each showing ground + primary + secondary +
  tertiary bars; the preview itself is `clearAndSetSemantics` decoration (the row's label and selected
  state carry the meaning for TalkBack);
- no hex, no pack names, no editor affordance. Adding a sixth pack makes it appear automatically.

### 3.7 A bidi defect found by the evidence, fixed at the shared component

While capturing the release About screen, the version row rendered **`m4-0.1.0`**: the Latin value
`0.1.0-m4` was laid out in an RTL paragraph, so bidi reordered its LTR runs. The same hole existed for
every `SettingsRow(value = …)` (engine rows, licence rows) — `SettingsKeyValueRow` already forced LTR for
Latin values, the plain row did not.

Fix: one shared rule, `settingsValueTextDirection(value)` in `:core:design/Settings.kt` — Latin-only
values are laid out `TextDirection.Ltr`, values containing Arabic keep `TextDirection.Content` — used by
**both** row types. It was driven RED→GREEN by four new JVM tests (§4.2); the release About screenshot now
reads `0.1.0-m4` (§5).

---

## 4. TDD record

### 4.1 RED — the types do not exist yet

Tests were written first, against the intended API (`ThemePacks`, `ThemePack`, `ThemePalette`,
`Contrast`, `contrastPairs`, `colorScheme`, `decorations`, `byId`, `isKnown`). Both test source sets
failed to compile with unresolved references — the typed RED failure:

```
e: ThemePacksTest.kt: Unresolved reference: ThemePacks      (:core:design:compileDebugUnitTestKotlin)
e: AppConfigTest.kt:  Unresolved reference: ThemePacks      (:app:compileDebugUnitTestKotlin)
```

### 4.2 GREEN — 17 new tests for packs, config and direction

| module | new tests | what they pin |
|---|---|---|
| `:core:design` `ThemePacksTest` (9) | registry ids/order/default · localised copy + distinct grounds per pack · unknown/blank fallback + `isKnown` trimming · **WCAG gate over every pack × mode × 20 pairs** · surface ladder is 5 distinct steps · default pack reproduces the M3 scheme (10 light + 6 dark pinned roles) · every palette role reaches its scheme slot (34 roles × 5 packs × 2 modes) · decorations follow the pack + legacy default pinned · WCAG maths anchors |
| `:app` `AppConfigTest` (+4) | shipped config default pack is girih_ink · a config pack id resolves through the registry · an unknown id stays in config, validates, then resolves to default at read time · blank default pack is reported by validation |
| `:core:design` `SettingsValueDirectionTest` (4) | Latin version → LTR · Arabic value → Content · Latin licence line → LTR · Arabic value with Latin digits → Content |

One test is deliberately a *pin*, not a property: `defaultPackReproducesTheM3ColourSchemeExactly` — if
someone edits the brand default, M4's promise ("the original looks exactly like M1–M3") fails loudly.

### 4.3 Test matrix after M4

| module | tests | files |
|---|---|---|
| `:core:runtime` | 16 | AppContextTest (8) · GgufFileTest (8) |
| `:core:design` | **13** | ThemePacksTest (9) · SettingsValueDirectionTest (4) |
| `:feature:chat` | 21 | ChatStoreTest (7) · ChatStorePersistenceTest (6) · ChatStoreRegenerateTest (8) |
| `:app` | **22** | AppCompositionTest (5) · AppConfigTest (11) · FileConversationStoreTest (6) |
| **total** | **72** | all green (`BUILD SUCCESSFUL`; XML: 0 failures / 0 errors) |

(55 before M4; +9 pack/contrast +4 config +4 direction = 72.)

Commands (from `.`):

```powershell
$env:JAVA_HOME = '<path-to-jdk-21>'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = '<path-to-gradle-home>'
& .\gradlew.bat -p . --console=plain `
  :core:runtime:testDebugUnitTest :core:design:testDebugUnitTest `
  :feature:chat:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
```

---

## 5. Verified on the emulator

**Harness:** Nawa_Phone AVD, API 36 x86_64, 720×1280, 320 dpi, 6 GB RAM, RTL Arabic, cold boot.
Debug build installed from `:app:assembleDebug`; release is the R8/minified `:app:assembleRelease` APK
signed with the debug key for the internal install test (production signing is P3). **Emulator-only
evidence** — the founder's F8 decision keeps the test phone deferred, so every number here is
emulator-shape.

### 5.1 Screenshot evidence (in `docs/assets/`)

| what | files |
|---|---|
| default pack, light (picker + chat) | `sirrak-m4-picker-girih_ink-light-final.png`, `sirrak-m4-chat-girih-ink-light.png`, `sirrak-m4-release-picker-default.png`, `sirrak-m4-release-chat-default.png` |
| each pack, light, selected in the picker | `sirrak-m4-picker-lapis_night-light.png`, `-turquoise_dawn-light.png`, `-turquoise_dawn-light-selected.png`, `-madder_dusk-light.png`, `-paper_contrast-light.png` |
| each pack, dark, selected | `sirrak-m4-picker-girih_ink-dark.png`, `-lapis_night-dark.png`, `-turquoise_dawn-dark.png`, `-madder_dusk-dark.png`, `-paper_contrast-dark.png` |
| pack applied to the whole app (chat) | `sirrak-m4-chat-madder-dusk-dark.png`, `sirrak-m4-chat-girih-ink-light.png` |
| English LTR | `sirrak-m4-picker-en-light.png`, `sirrak-m4-chat-en-light.png` |
| Arabic after language reload | `sirrak-m4-after-arabic-reload.png` |
| persistence after force-stop | `sirrak-m4-persistence-restart-ar.png` (Turquoise Dawn still selected) |
| font scale 1.3 (settings + chat) | `sirrak-m4-picker-font-scale-1_3.png`, `-bottom.png`, `-dark.png`, `sirrak-m4-chat-font-scale-1_3.png` |
| release build (R8) | `sirrak-m4-release-picker-default.png`, `-picker-bottom.png`, `-picker-dark-girih.png`, `-picker-dark-paper.png`, `-chat-default.png`, `-chat-dark-paper.png` |
| release version + licences | `sirrak-m4-release-settings-about.png` (`الإصدار 0.1.0-m4`) |

The font-scale screenshots confirm the chat header and the picker reflow without clipping: the wordmark
keeps its Amiri lockup and the model chip ellipsises inside its weighted box (checked against the M1/M3
font-scale evidence; the apparent "clip" in the M4 captures is Amiri's calligraphic ascender, not layout
overflow).

### 5.2 Behaviour checks

| check | result |
|---|---|
| pack switch is instant, no `recreate()` | chat stays on screen, colours change; conversation and tab preserved |
| selection persists across process death | Turquoise Dawn selected → `force-stop` → relaunch → Turquoise Dawn still selected and applied |
| stale/unknown saved id | registry falls back to Girih & Ink (JVM test); no crash path exists in the store |
| language AR → EN → AR | Activity recreates; selected pack survives (id is independent of language) |
| light/dark inside each pack | both modes screenshot for all five packs |
| font scale 1.3 | picker + chat render, no clipped labels, rows grow to fit the description |
| RTL/LTR | picker and chat mirrored; preview tiles and check badge on the correct side; English LTR verified |
| TalkBack semantics | rows expose radio/selected; preview swatches are decoration-only |
| release build (R8) | renders every screen above; no missing keep rule, no crash in logcat; app process alive after pack switches |

### 5.3 Release measurements

| item | value |
|---|---|
| Debug APK | **65,513,003 B** (62.48 MiB) — Compose tooling + unminified engine |
| Release APK (R8, unsigned) | **17,034,229 B** (16.24 MiB) |
| Release APK (debug-key signed, tested) | **17,046,568 B** (16.26 MiB) · SHA-256 `10FE73CB924F737E8A094A256774290CA0E4602C817418FE309F13E20B86D712` |
| `versionName` / `versionCode` | `0.1.0-m4` / `1` |
| Release cold start | `LaunchState: COLD TotalTime: 1884 ms` (fresh install + cleared data) |
| Architecture | arm64-v8a + x86_64 only; native stripped at link |
| R8 correctness | all screens rendered from the signed release; no `FATAL EXCEPTION`; app process alive after pack switch |

Version history note: M2's record used `0.1.0-m2`; M3 deliberately stayed on m2 while it closed in two
increments. With M3 closed and M4 delivered, the version is now `0.1.0-m4`; the About row visibly shows
it (and the bidi fix is what makes it readable in the Arabic UI).

---

## 6. Reuse, expand, edit, remove (the standing gate)

The founder's instruction — "never hardcode and make reusable/expandable/easy to edit/change/add/remove"
— maps to these concrete recipes:

| task | change | rebuild? |
|---|---|---|
| **add a pack** | one `ThemePack(...)` value in `ThemePacks.all` + its four strings (AR+EN). The picker, persistence, contrast gate and config resolution pick it up automatically. | yes (design is compiled, as decided in M1) |
| **remove a pack** | delete that `ThemePack` value. Saved ids fall back to `girih_ink`; no screen or store change needed. | yes |
| **change a pack's colours** | edit its `ThemePalette` literals in one file; the contrast gate fails the build-tests if a gated pair drops below its tier. | yes |
| **change the default pack** | `app/src/main/assets/config/app.json` → `theme.defaultPack` (strict-parsed/validated). No code change. | no (asset) |
| **add a colour role the app paints text on** | add the field to `ThemePalette` → the compiler forces all five packs × two modes to fill it → add its pair to `contrastPairs` → gate covers every pack automatically. | yes |
| **change what a pack is allowed to affect** | forbidden by design: geometry/type/motion are not in `ThemePack`. Adding them would be a new council decision, not a pack edit. | — |
| **reorder the picker** | reorder `ThemePacks.all` (default is always first). | yes |
| **persist a new appearance setting** | contract in `core/runtime/Contracts.kt` + store field + slot UI — the M4 pattern (opaque value in runtime, resolved against a registry in the owner). | yes |

Rules that keep it unbreakable: no pack name/id/hex in feature code; the registry is the only list;
persisted ids are stable and resolved defensively; the contrast promise is executable code; the default
is pinned by a test.

---

## 7. Limits (what M4 does not claim)

- **No colour editor, no user-created packs, no Material You.** Deliberate (council F1): the palette is
  the brand; curated packs are the product answer. Material You stays suspended before v1.0.
- **Packs re-colour, they do not re-typeset.** Type, spacing, geometry, motion and the lapis-as-action
  rule are shared — by design, so five packs cannot fork the layout.
- **Paper is a normal selectable pack**, not an automatic accessibility mode; system high-contrast
  detection is not wired yet (candidate for the P3 a11y pass).
- **The contrast gate covers the 20 painted pairs**, not every possible composition; decorative tokens are
  intentionally ungated, and no screenshot-based contrast audit runs in CI.
- **Emulator-only evidence** (F8): no real-phone sunlight/thermal check of any pack yet; Paper's outdoor
  claim is untested.
- Picker rows are fixed in the shipped order; no favourites/search (five packs do not need it).
- No per-app/per-time automatic pack switching (e.g. dark → Lapis Night) — the mode is system-driven only.

---

## 8. Next — M5 model control (the current milestone)

Per the council plan (doc 17 §8.2), the next milestone turns the engine's single global settings into
**model control**, TDD-first, without touching the seams established here:

1. **GGUF facts in JNI**: read `general.*`, tokenizer and `general.sampling.*` metadata (no new engine) →
   `ModelFacts` in Kotlin, plus a fingerprint (`sha256: size + first 64 KiB + last 64 KiB`) so a
   re-imported model is the same model.
2. **Typed generation contract**: `GenerationParams`, `SamplingMode`, `TemplateChoice`; precedence
   **conversation > per-model > GGUF `general.sampling.*` > family preset > shipped `app.json` > code**.
3. **`ModelSettings` + `ModelSettingsStore`**: atomic JSON, schemaVersion 1, corrupt-file backup instead
   of a crash; per-model keyed by fingerprint.
4. **Expanded native sampler chain** mirroring llama.cpp's `common/sampling.cpp` (top-k/top-p/min-p/
   temperature/penalties/seed and friends as the verdict scoped), keeping the existing tok/s stats.
5. **Settings → Model UI**: Simple/Advanced, model-derived defaults marked, reset per model; template
   source display + `nativeFormatPrompt` preview + forced built-in override; global and per-model system
   prompts; prompt presets from `content/prompt_presets.json`.

M5 starts with the RED compile failure for the new contracts, exactly as M4 did. M5b (timeboxed
`common/chat` Jinja spike) and M6 (conversation library/reading surface) follow.

---

## Appendix A — files changed in M4

| file | change |
|---|---|
| `core/design/.../ThemePacks.kt` | **new**: `ThemePack`, `ThemePalette` (36 roles), `ContrastTier`, `ContrastPair`, `Contrast`, `contrastPairs`, `ThemePacks` registry, `colorScheme`, `decorations`, five private pack values |
| `core/design/.../Tokens.kt` | rewritten: no palette hex; `SirrakDecorations` supplied by the pack, `SirrakMotion` global, `sirrakTokens(dark, pack)`; compatibility `LightColorScheme/DarkColorScheme` |
| `core/design/.../Theme.kt` | `SirrakTheme(mode, packId)`; resolves pack; provides tokens + MaterialTheme |
| `core/design/.../ThemePackPicker.kt` | **new**: `ThemePackOption`, `ThemePackPreview` |
| `core/design/.../Settings.kt` | `settingsValueTextDirection(value)` shared rule used by `SettingsRow` and `SettingsKeyValueRow` |
| `core/design/res/values/strings.xml`, `values-en/strings.xml` | **new**: 10 pack name/description strings |
| `core/design/build.gradle.kts` | JUnit for the design module; `unitTests.isReturnDefaultValues = true` |
| `core/runtime/.../Contracts.kt` | `SettingsService.themePackId` + `setThemePack` (opaque string; runtime does not know packs) |
| `core/runtime/.../AppConfig.kt` | `ThemeConfig(defaultMode, defaultPack)`; blank pack validation |
| `app/.../SharedPrefsSettingsService.kt` | theme-pack key, registry resolution on read/write |
| `app/.../SirrakApp.kt` | collects `themePackId`, passes it to `SirrakTheme` |
| `app/src/main/assets/config/app.json` | `theme.defaultPack: "girih_ink"` |
| `app/build.gradle.kts` | `versionName = "0.1.0-m4"` |
| `feature/settings/.../SettingsScreen.kt` | Appearance section: mode + registry-composed pack picker + note |
| `feature/settings/res/values*/strings.xml` | theme-pack group label + note |
| `core/design/.../ThemePacksTest.kt` | **new**: 9 tests |
| `core/design/.../SettingsValueDirectionTest.kt` | **new**: 4 tests |
| `app/.../AppConfigTest.kt` | +4 theme-pack tests |

## Appendix B — evidence index

All screenshots are 720×1280 on Nawa_Phone (API 36, 320 dpi, RTL Arabic unless `-en`). Files live in
`docs/assets/`; names start with `sirrak-m4-`. The full list with meanings is §5.1.
