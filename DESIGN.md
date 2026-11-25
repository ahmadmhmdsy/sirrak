---
name: Sirrak (سِرِّك)
description: Girih & Ink — a private, Arabic-first assistant drawn as a quiet research desk; ivory paper, ink, lapis action, brass geometry. Girih & Ink is the pinned default of five data-only colour packs (M4).
colors:
  ground: "#F7F3EA"
  surface: "#FDFAF3"
  surface-low: "#F3EEE2"
  surface-container: "#EDE7D8"
  surface-high: "#E6DFCE"
  surface-highest: "#DED6C2"
  ink: "#1B2430"
  ink-muted: "#5C6672"
  lapis: "#1D3F6E"
  lapis-deep: "#10305A"
  lapis-container: "#D8E2F0"
  brass: "#B08D3E"
  brass-text: "#6F561A"
  turquoise: "#1F6F6B"
  madder: "#A63A2E"
  hairline: "#E3DCCB"
  night: "#0F1416"
  night-surface: "#121A1D"
  night-surface-low: "#162024"
  night-surface-container: "#1A262A"
  night-surface-high: "#1F2D31"
  ivory: "#E9E4D6"
  ivory-muted: "#A9B1AF"
  lapis-night: "#9DB8DD"
  brass-night: "#D6B568"
  turquoise-night: "#7FC0B9"
  madder-night: "#E8A79C"
  hairline-night: "#243134"
typography:
  display: { fontFamily: Amiri, fontSize: 56sp, fontWeight: 400, lineHeight: 64sp }
  display-medium: { fontFamily: Amiri, fontSize: 44sp, fontWeight: 400, lineHeight: 52sp }
  headline-small: { fontFamily: Amiri, fontSize: 24sp, fontWeight: 400, lineHeight: 34sp }
  title-large: { fontFamily: IBM Plex Sans Arabic, fontSize: 20sp, fontWeight: 600, lineHeight: 28sp }
  title-medium: { fontFamily: IBM Plex Sans Arabic, fontSize: 16sp, fontWeight: 600, lineHeight: 24sp }
  body: { fontFamily: IBM Plex Sans Arabic, fontSize: 16sp, fontWeight: 400, lineHeight: 26sp }
  body-medium: { fontFamily: IBM Plex Sans Arabic, fontSize: 14sp, fontWeight: 400, lineHeight: 22sp }
  label: { fontFamily: IBM Plex Sans Arabic, fontSize: 12sp, fontWeight: 600, lineHeight: 16sp }
  assistant-prose: { fontFamily: Amiri, fontSize: 19sp, fontWeight: 400, lineHeight: 33sp }
  wordmark: { fontFamily: Amiri, fontSize: 26sp, fontWeight: 700, lineHeight: 34sp }
rounded:
  xs: "6dp"
  sm: "10dp"
  md: "14dp"
  lg: "20dp"
  xl: "28dp"
motion:
  quick: "140ms"
  standard: "240ms"
  slow: "360ms"
  easing: "FastOutSlowIn"
components:
  send-disc: { backgroundColor: "{colors.lapis}", textColor: "{colors.ground}", rounded: "{rounded.xl}", size: "48dp" }
  composer-bar: { backgroundColor: "{colors.surface-low}", border: "1dp {colors.hairline}", rounded: "{rounded.xl}" }
  user-note: { backgroundColor: "{colors.surface-container}", textColor: "{colors.ink}", rounded: "{rounded.md}" }
  source-chip: { backgroundColor: "{colors.surface-low}", textColor: "{colors.ink-muted}", rounded: "{rounded.sm}" }
  nav-indicator: { backgroundColor: "{colors.lapis-container}", textColor: "{colors.lapis}", rounded: "{rounded.lg}" }
---

# Sirrak — DESIGN.md

**Status:** implemented (M1 design system; M4 user-selectable theme packs; M5 added the data-driven model-control settings surface; **M6 added the Markdown reading surface, the conversation-history patterns, the token meter and the font-scale registry**; **M7 added attachment chips, the capability-gated attach sheet and the shared-text dialog**; **M8 added the documents library, citation chips, the excerpt sheet and the embedding-model card**; **M9 added the image chip and source dialog, the VIS-4 prompt-shortcut chips and the vision (mmproj) settings card**; **M10 added the composer mic, the listening/transcribing/failed status strip and the Settings → Dictation card**; **M11 added the three-step onboarding flow, the model-manager settings surface, the catalogue/recommendation cards and the model detail sheet**, 21 Nov 2026; **P3.6–P3.7 added the plural-copy, locale, icon and screenshot-baseline rules, and P3.8 added the public surface and brand-asset section**). This
file documents the design world and the default pack; the source of truth is code — palette data and the
contrast gate live only in `core/design/src/main/java/app/sirrak/core/design/ThemePacks.kt`, type in
`Type.kt`, and shape/motion in `Tokens.kt`. If this file and the code disagree, the code wins and this
file is a bug.

**Evidence:** the M1 screenshots and build record are in [docs/13-sirrak-frontend.md](docs/13-sirrak-frontend.md);
the five packs, their light/dark screenshots and the contrast measurements are in
[docs/18-sirrak-m4-theme-packs.md](docs/18-sirrak-m4-theme-packs.md); the M5 settings surface
(slider/switch/chip/dialog primitives, stacked technical values, data-driven parameter rows) is in
[docs/19-sirrak-m5-model-control.md](docs/19-sirrak-m5-model-control.md); the M6 library/Markdown/token-meter/
font-scale surfaces are in [docs/20-sirrak-m6-chat-library.md](docs/20-sirrak-m6-chat-library.md); the M7
attachment chips, attach sheet and shared-text dialog are in
[docs/21-sirrak-m7-attachments.md](docs/21-sirrak-m7-attachments.md); the M8 documents library, citation chips
and excerpt sheet are in [docs/22-sirrak-m8-document-rag.md](docs/22-sirrak-m8-document-rag.md); the M9 image
chip, source dialog, prompt-shortcut chips and vision settings card are in
[docs/23-sirrak-m9-vision.md](docs/23-sirrak-m9-vision.md); the M10 composer mic, dictation status strip and
Dictation settings card are in [docs/24-sirrak-m10-dictation.md](docs/24-sirrak-m10-dictation.md); the M11
onboarding screens, model-manager cards, catalogue badges, storage manager and model detail sheet are in
[docs/25-sirrak-m11-model-manager.md](docs/25-sirrak-m11-model-manager.md).

## World: Girih & Ink

A durable visual order borrowed from the audience's own built world: the eight-fold *girih* strapwork of
Iraqi tilework, the ivory plaster of Baghdad courtyards, lapis-glazed brick, brass detail, and the ruled
page of an Arabic manuscript. The product is *a private Arabic assistant that never leaves the phone*;
the world's mechanism is **geometry as order** — a strict 4dp lattice under calm surfaces, rules and
margin annotations instead of cards, one saturated accent that means *action*, one metal tone reserved
for geometry. The assistant is not a chatbot in a bubble; it is a scholar writing on a page you share.

Derivation (18 Nov 2026, concept-seed candidate 3): seven grounded systems were ranked; girih tilework
and the mihrab won on audience identification and product clarity. Donations kept: action-only accent and
thumb-zone navigation from consumer canon; ruled captions from character goods; segmented, tabular model
catalogue from the boarding-pass world. Declined: wallpaper ornament, glass, gradient text, second accent.

## Colour roles

Light is primary (ivory plaster, daylight, lecture use). Dark is a first-class *night majlis* scheme, not
an inversion. Dynamic colour is off: the palette is the brand. The table below is the **default pack**
(Girih & Ink) — the only pack whose values are pinned; the other four packs must pass the same executable
contrast gate, and may change only the families listed in the theme-packs section below.

| Role | Light | Dark | Rule |
|---|---|---|---|
| ground / surface | #F7F3EA / #FDFAF3 | #0F1416 / #121A1D | app background, reading page |
| surface-low / container / high | #F3EEE2 / #EDE7D8 / #E6DFCE | #162024 / #1A262A / #1F2D31 | composer, notes, sheets, chips — the elevation ramp |
| ink / ink-muted | #1B2430 / #5C6672 | #E9E4D6 / #A9B1AF | primary text and rules / metadata, captions |
| lapis (action) | #1D3F6E | #9DB8DD | the only action colour: send, primary, active nav |
| brass (geometry) | #B08D3E | #D6B568 | 1dp girih lines, the inking star, hairlines accents — geometry only |
| brass text-safe | #6F561A | #D6B568 | the ONLY brass allowed to carry text, and only on light |
| turquoise (secondary) | #1F6F6B | #7FC0B9 | source/citation emphasis only |
| madder (error) | #A63A2E | #E8A79C | errors and destructive confirmation only |
| hairline | #E3DCCB | #243134 | 1dp structural rules |

Contrast: body and placeholder ≥4.5:1 against their surface, large text ≥3:1. Secondary text is tinted
from the ground hue, never neutral grey. **Brass never carries text on light** — use brass-text there.
No gradients, no glass, no coloured side borders, no nested cards, no second accent.

## Theme packs (M4)

Appearance is user-selectable **without forking the design**. Five curated packs ship; the default is
pinned to the M1–M3 Girih & Ink values above. Packs are pure data in
`core/design/.../ThemePacks.kt` — no call site, screen or setting knows a pack by name.

| pack id | name (AR / EN) | character | light ground · action | dark ground · action |
|---|---|---|---|---|
| `girih_ink` | جِرِه والحِبر / Girih & Ink | the original: ivory, ink, lapis, brass | `#F7F3EA` · `#1D3F6E` | `#0F1416` · `#9DB8DD` |
| `lapis_night` | ليل لازوردي / Lapis Night | deep lapis grounds for night reading | `#EFF2F6` · `#1E4173` | `#0E1526` · `#9DBBE4` |
| `turquoise_dawn` | فجر فيروزي / Turquoise Dawn | warm paper and deep teal, dawn on the Tigris | `#F3F2E5` · `#14615E` | `#0C1A18` · `#6FD0C4` |
| `madder_dusk` | غروب الفُوَّة / Madder Dusk | bazaar warmth: rose ivory, madder, brass | `#F6EFE9` · `#9C3A2E` | `#17100F` · `#DE9182` |
| `paper_contrast` | ورق / Paper | maximum clarity: ink on paper, no colour decoration | `#FFFFFF` · `#14417A` | `#000000` · `#9EC2EC` |

**What a pack may change:** ground/surface and the 5-step surface-container ladder; ink and muted ink
(and their on-colours); the primary/secondary/tertiary/error families and containers; outline/inverse
roles; the four decorative tones (girih line, brass, brass-text, hairline).

**What no pack may change:** geometry, radii, spacing, the 4 dp grid; type families, sizes and line
heights; motion; 48 dp targets; the lapis-as-action rule (exactly one action colour per pack — the
`primary` family; the picker preview shows it as the middle bar). This is what keeps five palettes looking
like one product.

**The gate:** `ThemePack.contrastPairs(dark)` lists the 20 pairs the UI actually paints (16 body at
4.5:1, 4 UI at 3:1) and the JVM test iterates every pack × mode. Decorative tones are deliberately not
gated: `girihLine` is a 1.7–2.2:1 printed texture, `brass` never carries text, `hairline` is a structural
1 dp rule; `brassText` exists as the text-safe brass tone. Paper passes AAA body contrast (7.19:1 light /
7.48:1 dark); the default pack's tightest body pair is 4.73:1.

**Edit rules:** add a pack = one `ThemePack` value in `ThemePacks.all` + four strings (AR+EN); remove a
pack = delete that value (saved ids fall back to the default); change colours = edit that pack's
`ThemePalette` literals and let the gate re-check; change the shipped default = `theme.defaultPack` in
`assets/config/app.json` (no rebuild). Selection is persisted by id and resolved defensively. Full record,
screenshots and measurements: [docs/18-sirrak-m4-theme-packs.md](docs/18-sirrak-m4-theme-packs.md).

## Typography

Two bundled OFL families, never fetched and never system fallback:

- **Amiri** (SIL OFL 1.1) — display, the wordmark, the empty-state promise and assistant prose. Serif
  voice; not controls, not labels.
- **IBM Plex Sans Arabic** (SIL OFL 1.1) — the working voice for Arabic UI at every size, and its Latin
  glyphs cover Latin UI text (no separate Latin family is bundled in M1). Weights 400/500/600 only.

Sizes are Material type roles defined once in Type.kt: displayLarge 56/64, displayMedium 44/52,
displaySmall 36/46, headlineSmall 24/34 (all Amiri normal); titleLarge 20/28, titleMedium 16/24,
titleSmall 14/20 (Plex semibold); bodyLarge 16/26, bodyMedium 14/22, bodySmall 12/18; labels 14/20,
12/16, 11/16 (semibold, 0.3sp on small). Assistant prose is a dedicated 19/33 Amiri style; the wordmark
is 26/34 Amiri bold. All sizes in sp; every screen must survive font scale 1.3.

## Layout

4dp baseline grid. Phone margins 20dp. Content never wider than 680dp (config `ui.maxContentWidthDp` in
`assets/config/app.json`). Vertical rhythm 4/8/12/16/20/24/32/40/56; space above a heading is always
larger than below. Three top-level destinations (Chat, Library, Settings) generated from installed
plugins in an M3 navigation bar that sits on the ground tone. Chat: compact header with the wordmark and
one trailing action (the model chip, a slot), transcript fills the body, composer pinned above the IME.
Settings: grouped sections, each a slot so features add their own without editing the screen.

## Shipped components

All in `:core:design`; features compose them and never re-implement them.

- **Wordmark** — Amiri bold; the wordmark is text, not an image, and is read as the brand lockup.
- **Girih mark / inking star** — one eight-point star drawn with a Canvas in brass; appears as the
  assistant's thinking mark before the first token and in the empty state. The only procedural ornament.
- **Empty conversation** — wordmark, a two-line promise, a privacy line, two ruled suggested prompts.
- **Chat composer** — one flat tonal bar, 26dp radius, hairline border; a leading slot (the M7 paperclip)
  and a trailing slot row; pending attachment chips sit above the bar; the lapis send disc becomes a madder
  stop disc while streaming and stays enabled when the text is blank but an attachment is pending.
- **Assistant prose** — no bubble: full-width 19/33 Amiri, generous leading, inking star while streaming,
  source chips in the margin below in label style.
- **User note** — start-aligned tonal note (surface container, 14dp radius), no tail, no avatar;
  attached-file chips render beneath it in the transcript.
- **Attachment surfaces (M7)** — `AttachmentChip` / `AttachmentChipRow`: one-line tonal chip (surface-low,
  10dp radius, label style) with a 48dp remove target and file-kind glyph; `AttachmentSheet`: a
  `ModalBottomSheet` whose rows come from one kind registry, disabled kinds show the localised gate reason
  instead of hiding (no ghost buttons, no colour-only state); `SharedTextDialog`: one editable Amiri-free
  body field with a live character counter, keyboard resizing and an explicit attach action — the clipboard
  is read only after the user taps the shared-text row, never on sheet open.
- **Citation surfaces (M8)** — `CitationChips`: a wrapping row of one-line tonal chips under assistant
  prose (surface-low, 10dp radius, label style, document glyph, 48dp target, "صفحة n"/"page n"); the row is
  labelled "المصادر المستخدمة"/"Sources used" because the deterministic fallback lists what the answer was
  grounded on, not only literal model markers. Tapping a chip opens the excerpt sheet: document name in
  titleSmall, page label in label style, excerpt in Amiri body on ivory with content direction — reading,
  not a second conversation.
- **Documents library (M8)** — the Library tab's document section: ruled rows with format badge
  (PDF/DOCX/EPUB/TXT/MD), page and chunk counts, import status and row actions; one lapis import FAB opens
  SAF multi-select; an empty state explains that a document is imported from the device and that the
  embedding model is required first, with the real gate reason when it is missing — no ghost import.
- **Embedding model card (M8)** — Settings → Model: one card in the M5 control language for the user-imported
  `.litertlm` model (status, import via SAF, remove, licence note "not bundled; Gemma terms"); same
  key-value rows and honest error codes as the engine model card, no second design idiom.
- **Image input surfaces (M9)** — the image attachment uses the same chip language as every other kind, with a
  thumbnail-free picture glyph and a 48dp remove target; `ImageSourceDialog` is a two-row choice (gallery /
  camera) in the shared dialog language, not a second sheet. Once an image is queued, `ImagePromptSuggestionRow`
  renders one-line tonal shortcut chips (`AssistChip` geometry) above the composer — VIS-4's "extract text" and
  "describe" prompts; a chip only fills the composer, it never sends on its own.
- **Vision settings card (M9)** — Settings → Model: the M5 key-value language reused for status, selected model,
  the projector row (label + mmproj file name), a remove row and one explanatory note; the projector picker runs
  the native capability inspection before saving, so an unsupported projector is refused with a reason instead of
  becoming a silent "vision" model.
- **Suggestion card** — full-width ruled row: Arabic prompt in titleMedium, one-line gloss in bodyMedium.
- **Settings section / row / key-value row / divider / note** — ruled headers, hairline separators,
  LTR-forced Latin values (`settingsValueTextDirection`: Latin-only values LTR, Arabic values content-
  directed), segmented choice rows in lapis.
- **Theme-pack picker** (`ThemePackOption` / `ThemePackPreview`) — registry-composed rows with a
  light/dark preview pair, selected tint + check + radio semantics; the preview is decoration only for
  TalkBack. No colour-editor affordance exists by design.
- **Model sheet, model catalogue card, library screen, engine settings section** — catalogue is data from
  assets/content/models.json; the sheet is the shared detail surface.
- **Shell / navigation** — destinations from plugins; active indicator lapis container, label always visible.

## Motion

One authored moment: the **inking star** — the girih mark rotates/draws while an answer streams. Destinations
cross-fade and slide 240ms (FastOutSlowIn). Press feedback is Material defaults. Durations live in
SirrakMotion (140/240/360ms). Reduced-motion handling is **not implemented** in M1 (deferred); until then
do not add new decorative motion.

## RTL and localisation

Arabic is the default and primary target. All layout uses start/end semantics; the whole UI mirrors in
RTL. Latin technical values (quantisation, versions, numbers) are forced LTR inside RTL rows. Strings
live only in res/values/strings.xml (Arabic) and res/values-en/strings.xml (English); no user-visible
string is hardcoded in Kotlin. Timestamps are disabled in M1 (config ui.showTimestamps = false).

## Implementation notes (M1, 18 Nov 2026)

- Shipped exactly as coded: palette and type above, hairlines instead of shadows, one authored motion,
  no dynamic colour, no gradients.
- **Deltas from the original seed (intentionally deferred, not abandoned):** the arch plate, the 6%
  girih lattice behind the empty state, the model passport card, per-token line-reveal animation and
  reduced-motion support did not ship in M1; the inking star and hairline/tonal surfaces did. A spacing
  token object is also deferred — spacing follows the 4dp grid with local dp values.
- Engine chrome is honest: the model chip and sheets say the engine is a demo provider and that the model
  is not loaded; no screen pretends inference is happening.
- Fonts and licences are bundled and surfaced in Settings > About: Amiri and IBM Plex Sans Arabic (OFL
  1.1), Jetpack Compose (Apache-2.0).

## Implementation notes (M4, 19 Nov 2026)

- Shipped as coded: five packs, registry-composed picker in Settings → Appearance, id-based persistence,
  instant recompose, no editor and no Material You (council F1).
- The default pack is byte-identical to the M1–M3 palette and pinned by a JVM test; the M1 evidence above
  still describes the default look.
- Four extra packs extend the family (Lapis Night, Turquoise Dawn, Madder Dusk, Paper); each ships a full
  light + dark palette and passes the 20-pair gate.
- One defect was found by the M4 evidence and fixed centrally: Latin settings values (About version,
  licence ids) were bidi-reordered in RTL (`m4-0.1.0`); `settingsValueTextDirection()` now LTRs
  Latin-only values for both settings row types, covered by tests.
- Limits: no custom packs, no automatic high-contrast mode, no per-app pack scheduling; the gate covers
  painted pairs, not every possible composition. Full record: doc 18.

## Implementation notes (M5, 19 Nov 2026)

- Model control is **data-driven UI, not screens**: one `ModelParameterSpec` row per numeric parameter
  drives label, range, step, formatting, Simple/Advanced placement and per-field reset; adding a
  parameter is one catalogue entry + one string. Sampling modes come from the enum, not a hand-written
  list; catalogue ranges read `GenerationRanges` so the UI cannot drift from the runtime clamp.
- Shared primitives live in `:core:design/ModelControls.kt`: `SettingsSliderRow` (required localised
  reset label, TalkBack name + value semantics), `SettingsSwitchRow` (one toggleable node), `SettingsChipRow`,
  `SettingsLongTextDialog` (caller-supplied placeholder/keyboard), `SettingsStackedRow` (full-width label +
  long technical value, for SHA-256 hashes) and `SettingsTextBlock` (direction-aware read-only block).
- Rules kept: no hardcoded control text (all strings localised in Arabic+English, including units and
  hints), no LTR forcing on prose, long technical values stack instead of being code-unit truncated, and
  every value that can come from an edit says so from *touched* state, not by comparing values.
- Limits: the three bespoke editors (seed, stop sequences, sampling mode) are still hand-written; the
  parameter catalogue covers numeric fields only. Full record: doc 19.

## Implementation notes (M6, 19 Nov 2026)

- The library is a **sheet, not a screen** hanging off the composer header; rows are ruled, not cards.
- Markdown is rendered with the app's own tokenizer: headings stay in title styles, fenced code is an
  LTR monospace block on surface-low, tables scroll horizontally, quotes use the hairline rule; inline
  styles never replace a font family. Arabic prose keeps content direction.
- The token meter is a quiet label row under the composer that turns amber (near limit) and madder
  (over limit) with an explicit "older messages are not sent" warning — the only use of madder outside
  errors, because it is a destructive consequence.
- Font scale is a data-only 85/100/115/130 % registry scaling sp only; sp-only is a rule, dp layout never
  scales. Evidence in doc 20.

## Implementation notes (M7, 19 Nov 2026)

- One attachment vocabulary, one visual language: every kind renders the same chip, the same sheet row and
  the same transcript placement; only the glyph, label and enabled state differ. Adding a kind = one
  enum value + policy entry + icon + strings, not a new screen.
- The attach sheet is **capability-gated**: disabled rows stay visible, faded, with the localised reason and the concrete next step (M9: a document row
  says the embedding model is required first; an image row says a vision model is required first). The gate
  comes from the engine's `capabilities`, never from a screen constant.
- Shared text is **explicit and untrusted**: the clipboard is read only after the row tap, the dialog is
  editable, the attachment is labelled and sent as quoted data inside a question-first block, and the UI
  calls it "shared text", never "document". Chips in the transcript make it visible what the model saw.
- Sheet/dialog strings, reasons and accessibility labels are all localised AR+EN; remove targets are 48dp.
  Limits: no camera/file-picker entry yet; document/image/audio kinds are visible-disabled by design.
  Evidence in doc 21.

## Implementation notes (M8, 19 Nov 2026)

- **Provenance is a reading surface**: citation chips sit under the assistant prose in the same margin as the
  M6 source chips, and the excerpt sheet is plain text on ivory — document name, page, quoted excerpt. The
  chip label is deliberately "Sources used" (not "cited"): small quantized models often omit inline markers,
  so the deterministic fallback lists the retrieved sources the answer was grounded on (doc 22 §2.6).
- **The documents library is the M7 attachment vocabulary at document scale**: one row language, one import
  gate, one empty state; a format is data (badge + label), not a new screen. Import is SAF-based and the
  gate reason ("embedding model required") is shown before the picker, never after a failed tap.
- **Grounded answers keep the reading surface**: no bubble, no card, the same Amiri prose; sources are
  quieter than the answer and the empty-retrieval state says "no relevant sources in your documents"
  instead of letting the model guess.
- **Honest limits in the UI**: no OCR promise on scanned PDFs (rejected as NO_TEXT with the reason), no
  citation that was not retrieved, no model bundled with the APK — the card says the model is user-imported.
- **Verified on the signed release, not only debug**: final device suite OK (15 tests, 208 s); the release APK
  (71.1 MiB, versionCode 8) installed over the debug build with data preserved, then the five M8 surfaces were
  captured from seeded production data — grounded answer + citation chip, excerpt sheet, documents sheet,
  embedding-model card, conversation history (doc 22 §8).

## Implementation notes (M9, 21 Nov 2026)

- **Vision is a capability, not a screen.** The attach sheet's image row, the send path and the settings card all
  read the same engine-reported `capabilities.images`; a text-only model keeps the camera and gallery visible but
  disabled with the reason, and the turn path re-checks at send time. No screen decides on its own whether vision
  works.
- **The image chip is the M7 chip.** One attachment vocabulary across shared text, documents and images; only the
  glyph and the hint change. The source dialog is two rows, not a new sheet, and the error dialog reuses the
  shared alert language instead of inventing a toast.
- **Prompt shortcuts are data, and they only fill the composer.** VIS-4's OCR-style shortcut is a chip whose text
  comes from a string resource through a one-entry enum; it never sends automatically, never runs a second engine
  and never claims OCR accuracy. Adding a shortcut is one enum value + two strings.
- **A long value may never squeeze a label again.** The M9 settings screenshot exposed the shared `SettingsRow`
  primitive starving its label when the value (a 36-character mmproj filename) had no width bound. The row now
  gives the value a weight when there is no trailing action (two lines, then ellipsis, end-aligned) and keeps the
  220 dp / one-line cap when a trailing action must stay reachable; the label ellipsises at two lines. Every
  settings screen inherits the fix (doc 23 `4.1).
- **Honest model-facing limits stay in the UI.** No model weights are bundled, the projector card says the file is
  user-imported and validated, and the vision feature does not promise Arabic image Q&A quality that a 500M
  English-centric model cannot deliver (doc 23 `4.3).
- **Verified on the signed release, not only debug**: final device suite OK (20 tests, 236.5 s); the release APK
  (74.2 MiB, versionCode 9) installed over the debug build with data preserved, then the six M9 surfaces were
  captured from seeded production data — image answer, attach sheet, source dialog, prompt shortcuts, OCR-style
  answer and the vision settings card (doc 23 `7).

## Implementation notes (M10, 21 Nov 2026)

- **The microphone is a composer slot, not a screen.** The mic is a `ChatComposerTrailing` contributor (order 20)
  and the dictation strip is a `ChatComposerAbove` contributor (order 10) rendered only while dictation is not
  idle, so the composer keeps its exact height until the user touches the mic. The chat feature still owns the
  text field; the voice feature owns the mic, the state and the Settings card, and the transcript travels over
  the `ComposerTextRequests` channel. Adding another producer of composer text is another caller of that channel,
  not a second input.
- **The status strip is one component with three states, not three bars.** Recording shows a waveform glyph,
  "Listening…", an LTR `m:ss` timer and Stop/Cancel; transcribing shows the spinner and its label; failed shows
  the error glyph, the localised reason and Dismiss. Every failure is typed and dismissible — never a toast, never
  a silently pasted guess — and the strip disappears back into the composer when the state returns to idle.
- **Permission is explained before it is requested.** An in-app rationale dialog states why the mic is needed and
  that the audio stays on the device before the system prompt appears; the app remembers it asked once so a
  denial is not re-prompted in a loop, and the denied state is the same typed strip language as the settings
  card. Audio is never written to a public folder, uploaded or logged as audio.
- **Hold and tap are one control.** `TOGGLE` mode is a clickable button; `HOLD` mode uses a press/release gesture
  with the same content description, button role and an explicit click action for TalkBack. The icon becomes a
  stop square while recording and the control is disabled while transcribing, so a second tap cannot open a
  second recording.
- **The settings card is data, not copy.** Status, selected model and size come from the engine and store
  StateFlows; the import row goes through the shared document picker and the store's typed import reasons; the
  installed list carries the selected badge and delete-with-confirmation; language and mode use the shared
  `SettingsChoiceRow`. Adding a language or a mode is one enum value plus two strings, and every user-visible
  string lives in `values`/`values-en`.
- **The timer is forced LTR** so Arabic-Indic digits cannot reorder `m:ss` — the same bidi rule M4 applied to
  Latin settings values.
- **Honest limits are in the UI.** The model is user-imported as three files and the card says so; transcription
  runs on the device and no weights are bundled; the emulator has no microphone (`-no-audio`), so the release
  evidence is the permission flow, the replayed real-speech engine path and the typed silence path, not a
  real capture — that stays a phone-day item.
- **Verified on the signed release, not only debug.** The final device suite ran 28/28 tests (263.7 s); the
  signed release (138.63 MiB, versionCode 10) was reinstalled after the suite reset the app data, the Whisper and
  vision models were re-imported through SAF, and nine M10 surfaces were captured — mic in the composer,
  recording, transcribing, the silence failure, permission rationale, the system prompt, denied, and both
  Dictation settings states (doc 24 §7).

## Implementation notes (M11, 21 Nov 2026)

- **Onboarding is a flow, not a wall.** Three steps (privacy, language, model) render from the `OnboardingFlow`
  state machine in `:core:models`; a progress bar and "n of 3" label make position obvious, Skip is available at
  every step, and finishing recreates the activity so a language change applies immediately. The model step is the
  same downloader the Settings manager uses — one seam, two surfaces.
- **The recommendation explains itself.** The device card shows RAM/class and free storage; the recommended card
  names the model and says why (RAM fits, storage comfortable) and shows the honest speed hint. A `<3×` free-space
  warning appears before a download, never a silent failure; if the catalogue has no fit, the UI says so instead of
  recommending something that cannot run.
- **Licence consent is a gate, not a checkbox.** Gated entries open a consent sheet that names the licence, links
  the source repo and requires an explicit accept before the download can start; the accepted set is persisted per
  model, and a declined download leaves no state behind. Apache-2.0 entries carry a licence badge and link instead.
- **Download progress is one typed state.** The row and the onboarding card render from the same
  `DownloadProgress` flow — percent, bytes, speed and phase — with pause/resume/cancel in place; a failure shows
  the typed reason (network, HTTP, checksum, storage, invalid model) and a retry, never a spinner that lies. A
  verified download is imported into the model store automatically and the row becomes Installed/Selected.
- **The catalogue is data, not code.** `assets/catalog/models_catalogue.json` is validated at startup by
  `CatalogueJson` (required fields, SHA-256 shape, URL derivation); adding a model is one JSON entry plus three
  strings, and a malformed entry is rejected with a reason instead of crashing the app.
- **Settings gets one new section through the existing slot.** `ModelManagerSection` is a `SettingsSections`
  contributor (order 12) built from shared primitives — `SettingsRow`, `SettingsChoiceRow`, section headers, cards
  and dialogs — so it matches the M5/M10 surfaces exactly and inherits RTL, dark theme and font-scale behaviour.
- **Honest limits are in the UI.** The row says the download is large, the storage card reports what is used and
  what deleting frees, the HF dialog warns that a wrong repo/file will fail validation, and the installed card
  distinguishes a model the app owns from one it cannot find. No weights are bundled.
- **Verified on the signed release, not only debug.** The final device suite ran 36/36 tests (320.8 s); the signed
  release (138.78 MiB, versionCode 11) was reinstalled after the suite reset the app data and used for the end-to-end
  run — onboarding in Arabic, skip, Settings → Models, a public Hugging Face download of the 491 MB recommended
  model, automatic import/selection and a chat answer — with 21 M11 surfaces captured (doc 25 §8).

## Implementation notes (P3.6, 23 Nov 2026)

- **Count-bearing copy is plural data, not a formatted sentence.** Every string that says "N pages", "N
  components" or "N documents awaiting indexing" is a `<plurals>` resource with all six Arabic forms
  (zero/one/two/few/many/other) and English one/other, rendered by `pluralStringResource`. A comment count is
  never built by concatenating a number, a space and a noun; adding a count string means adding forms, which is
  exactly where Arabic grammar and English singular/plural differ.
- **The in-app language switch owns the locale.** `LocaleSupport` wraps the base context with the saved or
  configured language before resources load; the Play bundle disables language splits (`bundle { language {
  enableSplit = false } }`) so both locales are always present in the base APK instead of depending on the
  system locale.
- **Image orientation uses AndroidX ExifInterface**, not the framework class (lint: the platform one has known
  security bugs). Same API surface, so `AndroidImagePreparer` only changed its import.
- **Adaptive launcher icons live in `mipmap-anydpi`** (not `-anydpi-v26`): minSdk 28 makes the API-26 qualifier
  redundant, and a qualifier that can never filter out is a lint warning waiting to happen.
- **Design changes are gated by screenshots.** `scripts/ui-regression.ps1` captures the shell surfaces (home,
  model picker, empty library, settings top), crops the status/navigation bars and compares a dHash against
  `scripts/ui-baseline/`. Any intended visual change updates the baseline in the same change; an accidental one
  fails the run. Colour, spacing and type still come from tokens — the baseline only proves they render as
  intended, it never becomes the source of truth.

## Public surface & brand assets (P3.8)

The design system ships in three forms — the app, the hosted site and the store graphics — and all three
read the same palette and the same mark.

- **Hosted site:** <https://ahmadmhmdsy.github.io/sirrak/> is the English page and
  <https://ahmadmhmdsy.github.io/sirrak/ar/> its Arabic mirror; the published policies are `/privacy/` and
  `/security/`, with `/ar/privacy/` and `/ar/security/` in Arabic. The site reuses the app's ivory, ink,
  lapis and brass roles, Amiri display type and IBM Plex Sans Arabic UI type, so the page a visitor lands on
  is the product's own visual world rather than a marketing skin.
- **Content is data:** `website/site.json` holds product facts and URLs, `website/content/en.json` and
  `website/content/ar.json` hold the two languages, `website/content/policies.json` holds the policy text,
  and `website/theme/site.css` holds the styling. Copy is edited there, not in the generator; the only
  literals in `scripts/build-site.ps1` are the brand lockup and the 404 page.
- **One generator, both directions:** `scripts/build-site.ps1` reads that data plus
  `store/graphics/brand.json` and writes the committed site to `website/dist/` — no framework, no network,
  no third-party script. Each language gets a landing page plus its privacy and security pages, with
  `hreflang` alternates, a canonical URL, `sitemap.xml` and `robots.txt`; `dir="rtl"` and logical CSS
  properties (`margin-inline`, `inset-inline`) mean the Arabic page is built right-to-left, not flipped
  afterwards.
- **Brand data, one source:** `store/graphics/brand.json` is the source of truth for the palette roles, the
  girih mark path, the shipped fonts and the lockup copy. `StoreGraphicsBrandTest` pins it to the shipped
  Girih & Ink pack, the adaptive-icon path and the app's string resources, so the store icon cannot drift
  from the app.
- **Asset generators:** `scripts/store-graphics.ps1` renders the 512×512 icon and the 1024×500 feature
  graphic from `brand.json`; `scripts/site-assets.ps1` derives the site icons and the 1200×630 Open Graph
  image from the same palette and fonts. Both screenshot a real text engine, so Arabic shaping and the
  bundled fonts are what ships, not what the developer's machine happens to have installed.
- **App tokens stay in code:** colour packs live in
  `core/design/src/main/java/app/sirrak/core/design/ThemePacks.kt`, shape, spacing and motion in
  `Tokens.kt`, and type roles in `Type.kt`. The site and the graphics consume that palette and never
  define a second one: a brand change starts in the pack and propagates outward.

Arabic is the reference language on both surfaces. The Arabic page is a first-class page with its own
content file, its own right-to-left layout and the same typography as the app — not a translated afterthought.
The feature graphic and the Open Graph card lead with the Arabic wordmark and set the Latin one beside it at
smaller size; the launcher icon is the girih mark alone, with no type to mirror.

## Do's and Don'ts

- **Do** let text be the interface: rules, margins and type carry the page.
- **Do** use lapis only where the user can act; reading surfaces stay ink on ivory.
- **Do** keep the girih mark and brass to one structural moment each.
- **Do** verify Arabic shaping, RTL, dark theme and font scale 1.3 on the emulator before calling a
  screen done, and capture evidence at phone width.
- **Don't** introduce cards as page structure, nested cards, gradient text, glass, coloured side borders,
  emoji-as-icon or a second accent.
- **Don't** render the wordmark in a system fallback; Amiri is bundled and must be verified on device.
- **Don't** add decoration behind prose or lower body contrast.
- **Don't** hardcode user-visible strings, colours or feature wiring: strings → resources, pack palette →
  ThemePacks.kt, shape/motion/type → Tokens.kt/Type.kt, features → one manifest line.
- **Do** write counted copy as `<plurals>` with every Arabic form (zero/one/two/few/many/other) and let
  `pluralStringResource` choose; never format a number into a singular noun.
- **Do** render provenance as reading material: citation chips and the excerpt sheet use the same ivory,
  hairline and label language as the transcript; a citation is a page, not a badge.
- **Do** add or remove a theme pack as registry data + four strings; never by branching a screen.
- **Don't** give a pack its own type, spacing, motion or second action colour — a pack re-colours, it does
  not re-design. Every new pack must pass the contrast gate in both modes before it ships.

## Deferred to M2+ (design side)

Real engine loading states and errors beyond the ones now shipped, TTS/voice-output surfaces, multi-image
messages and an image-edit/preview surface (v1.1), live partial transcripts and a per-conversation language
(STT-3, v1.1 — the M10 language is global), a move-model-between-volumes action (in-app
download/pause/resume, SAF import, delete and the P3 `dataSync` foreground service with a progress notification
all ship), reduced-motion support, tablet/foldable layout, spacing tokens, and the arch/lattice/passport
moments listed above.
