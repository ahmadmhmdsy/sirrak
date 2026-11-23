# 19 — Sirrak M5, model control: generation parameters, templates, prompts (19 Nov 2026)

**Status:** **M5 closed** — the model is no longer a black box with one hardcoded personality. The user can
edit every generation parameter, choose the chat template, and set global/per-model system prompts; the
engine applies exactly the layers the council specified (doc 17 §7.2–7.4, §8.2) and every value is
verifiable in one logcat line. **60 new tests since M4 (132 total, all green)**, verified on the emulator
in debug and release. Version advanced `0.1.0-m4` → **`0.1.0-m5`**.

This is the record promised at the end of [doc 17](17-customization-council.md): the founder wanted a
"settings page where the user controls the model", the Customization Council ruled for **data-driven
layers with sparse persisted overrides** (F2/F3/F5), and M5 executed that verdict under the standing build
gate — reusable, expandable, editable later, and never breaking the working app.

---

## 1. What the user can now do

- **Settings → Engine** gained a "Model control" block for the selected model:
  - **Current model**, **defaults source** ("where do the current defaults come from: code / app.json /
    family preset / GGUF metadata / your edits"), and **Reset all settings for this model**.
  - **Simple / Advanced** switch. Simple shows the five parameters almost everyone needs (temperature,
    Top P, Top K, max tokens, repetition penalty). Advanced adds the other eleven (repetition window,
    presence/frequency penalties, min-P, typical-P, Mirostat τ/η, XTC probability/threshold, DRY
    multiplier, Top-n σ) plus the **sampling mode** (default / greedy / Mirostat v2), the **seed**, and up
    to four **stop sequences**.
  - Every slider is data-driven: label, range, step, decimals, simple/advanced grouping, and the
    persisted field id all come from one catalogue row. Each row gets a per-field **reset** the moment it
    differs from the lower layers; a reset-all exists too.
  - **Chat template**: source row (model / built-in / fallback / none), template hash and first line from
    the GGUF, a **template chooser** listing every template the bundled llama.cpp knows (chatml, llama2,
    llama3, mistral, phi3, deepseek, chatglm4, …), **prompt preview** that renders the exact next request
    with the current template/prompt/params, and **Back to the model's template**.
  - **System prompt**: global prompt (with seven localised prompt presets — balanced, concise, teacher,
    researcher, writer, translator, coder), per-model prompt that overrides it, and a resolved-source row
    ("app default / global / this model").
  - **Model facts** block: architecture, training context, parameter count, vocabulary.
- All M5 edits are **persisted per model** (keyed by the model fingerprint), so a different GGUF gets its
  own settings; they survive restart and process death.
- The chat screen and every other feature are unchanged and keep working with **no** model loaded
  (demo engine, failing turns, regenerate, stops, sources).
- Unknown/corrupt persisted settings never crash: values are clamped into native-safe ranges before they
  reach llama.cpp, and unknown family/template ids fall back to the model's own template.

---

## 2. The layer model (the core of M5)

One resolver in `:core:runtime` computes the effective values; the UI only ever edits one layer of it.

**Precedence, strongest last-writer wins (per field):**

| # | Layer | Where it lives | Notes |
|---|---|---|---|
| 1 | Code defaults | `GenerationParams.DEFAULT` | the shipped base; used only where nothing else declares a value |
| 2 | App defaults | `app.json` → `engine.{temperature,topP,topK,maxTokens,repeatPenalty,samplingMode,…}` | **sparse**: every M5 field is nullable, so only values the file actually declares apply; a value equal to the code default counts as undeclared |
| 3 | Family preset | `assets/content/model_presets.json` | six shipped families (qwen, llama, gemma, phi, mistral, deepseek), matched by GGUF architecture; data-only, add a family by editing JSON |
| 4 | GGUF metadata | `general.sampling.*` in the model file | mapped by `GgufSampling.fromMetadata`; malformed/out-of-range values are ignored, not trusted |
| 5 | Per-model user edits | `files/model_settings.json`, `touchedParams` | only the field ids in `touchedParams` override lower layers; the stored `params` is a full snapshot for editing/display, not a second source of truth |
| 6 | Conversation override | reserved hook (`ModelControlResolver.resolveParams(conversation=…)`) | no UI consumes it yet; wired so a future per-chat override never needs a resolver change |

**Key invariant:** a field only overrides when it is *touched*. Dragging temperature to 1.1 writes
`touchedParams=["temperature"]`; Top P keeps coming from the family preset (0.80 for qwen) instead of being
frozen at whatever was on screen. Resetting the last touched field clears `params` again and returns the
model to the lower layers.

**Templates** resolve separately: per-model forced built-in (strongest) → model's own GGUF template →
built-in fallback (chatml) → none. The facts' `template_hash` always describes the model's *own* template,
so the UI can tell "you are using chatml instead of the model's template".

**Prompts** resolve: conversation (reserved) → per-model prompt → global prompt → `app.json` prompt →
packaged resource fallback. Prompt presets are content: `assets/content/prompt_presets.json` ships seven
bilingual presets, each with a localised name, description, system prompt, tags and an optional sparse
parameter layer. Applying one sets the **global** prompt in the current language, clears any per-model
prompt (so the preset cannot be shadowed), and merges the preset's parameter layer into the selected
model's touched overrides. A preset without a parameter layer changes no generation parameter.

All of this is pure Kotlin, unit-tested without Android:

- `core/runtime/.../ModelControl.kt` — enums, ranges, `GenerationParams`, `GenerationOverrides`,
  `GgufSampling`, `ModelControlResolver`, `ModelFacts`, `ModelFingerprint`, `ModelSettings`,
  `ModelSettingsStore`, `PresetRepository`, `GenerationOptions`, `GenerationSettingsProvider`.
- `app/.../AppModelControlService.kt` — the app-level orchestration: joins config + store + presets +
  engine facts + settings, exposes `effectiveParams()`, `defaultsSource()`, `resolvedTemplate()`,
  `resolvedPrompt()`, `options()`, and the edit/reset/preset operations.
- `core/runtime/.../Contracts.kt` — `InferenceEngine.stream(prompt, history, options)` has a default
  implementation that falls back to the simple overload, so adding generation options never breaks an
  engine implementation, and `SettingsService` gained `modelAdvanced` (persisted UI mode).
- `feature/chat/.../ChatStore.kt` — sends `generationSettings.options()`; a wiring test pins that the
  resolved layers reach the engine.

---

## 3. Design

This doc deliberately keeps the precedence table as the single reference; the code is the source of truth.

### 3.1 Reusable UI, not one composable per parameter

- `core/design/.../ModelControls.kt` adds reusable settings primitives: `SettingsSliderRow` (label,
  formatted value, optional source chip and per-row reset), `SettingsSwitchRow`, `SettingsChipRow`,
  `SettingsLongTextDialog`, `SettingsTextBlock`. They are used by M5 and available to later milestones.
- `app/.../ModelParameterCatalog.kt` is the single source of truth for the sliders:
  `ModelParameterSpec(id, labelRes, min, max, step, integer, advanced, get, set)`. Adding a parameter is
  one catalogue entry + one label string; no composable changes. `ModelParameters.ALL` drives the
  Simple/Advanced lists and the reset logic.
- `app/.../ModelControlPanels.kt` composes sections from that data: control summary, parameter sliders,
  sampling mode, seed, stop sequences, template, prompts, facts. It reads state from the service and
  writes through it; it holds no model-control truth of its own.

### 3.2 Template choice

The chooser lists what `llama_chat_builtin_templates` actually returns in the bundled llama.cpp build, so
the list can never drift from the engine. Picking one persists `"template":"<name>"`; `ModelDefault`
persists `"template":null`. The prompt preview calls the same native formatting path as generation
(`nativeFormatPrompt`), so what the user previews is byte-for-byte what the model receives.

### 3.3 Prompt presets as content

`content/prompt_presets.json` and `content/model_presets.json` are validated when loaded: invalid JSON,
missing ids, or non-bilingual entries fail loudly in tests rather than silently disappearing at runtime.
Presets are additive data — new entries need no code change.

### 3.4 Review pass (fixed vs deferred)

An independent review read the whole milestone (25 findings); the fixes that shipped here:

- `SettingsSliderRow` takes a required `resetLabel` (no hardcoded English default) and exposes TalkBack
  semantics: the slider's content description is the setting name and its state description is the
  formatted value. `SettingsSwitchRow` is one toggleable node with `Role.Switch` instead of two focus
  targets; the dialog text field carries the dialog title as its label.
- All hardcoded control text moved to resources: `SAMPLE_PROMPT` became a caller-supplied, localised
  `model_preview_sample`; fact placeholders ("—") and compact unit suffixes (B/M) became `fact_unknown`,
  `fact_count_billion`, `fact_count_million`; the seed dialog explains its input and opens the numeric
  keyboard; the supervisor's 4/64 stop limits are string arguments, not baked into the hint.
- One source of truth: `ModelParameterCatalog` reads min/max from `GenerationRanges`; the five parameter
  layers are gathered once by `AppModelControlService.layers()` so `effectiveParams()` and
  `defaultsSource()` cannot drift; sampling modes come from `SamplingMode.entries`.
- `paramsSource` reports USER by *touched* state, not snapshot equality, so editing a field back to the
  code default still honestly says "your edits".
- A stale built-in template name (stored by an older build) is ignored and resolves to the model template.
- Long technical values (the template hash) use the new reusable `SettingsStackedRow` so the full SHA-256
  is shown without wrapping or code-unit truncation; the template's first line is a separate note. The
  prompt preview follows content direction instead of forcing LTR; Arabic parameter names use correct word
  order ("احتمال XTC", "عتبة XTC", "مضاعِف DRY").
- `applyPromptPreset` now applies the preset's sparse params and clears the per-model prompt.
- The model-control sections gained the same horizontal padding as the rest of Settings.

Deferred deliberately (documented, not forgotten): enum/text/bool variants of the parameter spec (the three
bespoke editors — seed, stop, sampling mode — stay hand-written until M6 needs more), a fully id-keyed
parameter map, the shared `SettingsSheet`, and conversation-level overrides (no UI until chat supports it).

### 3.5 Native layer

`engine/llamacpp/src/main/cpp/llama_jni.cpp` was rewritten for M5:

- **Facts**: `nativeModelInfo` emits canonical keys (`architecture`, `n_ctx_train`, `n_params`,
  `n_vocab`, `bos_token_id`, `eos_token_id`, every `general.sampling.*`, template hash/first line).
- **Prompt building**: `nativeFormatPrompt` + `build_prompt` apply the chosen template (model template
  unless forced; falls back to the model template then chatml), include the resolved system prompt, and
  drop the oldest turns when the context is full (logged as `dropped_turns`).
- **Samplers**: the native chain now honours temperature, top-k, top-p, min-p, typical-p, top-n σ, XTC,
  DRY, presence/frequency/repetition penalties, seed, sampling mode (greedy / default / Mirostat v2) and
  **stop sequences**.
- **Stop handling**: a robust `StopFilter` withholds the longest stop prefix, never emits a broken
  multi-byte UTF-8 sequence, and reports whether a stop matched (`stop_seq`).
- **Stats**: every generation logs one `SirrakLlama: STATS` line with prompt tokens, dropped turns,
  prefill/ttft/generation timing, tokens/s, peak RSS, stop flags, template, and the effective sampler
  parameters — the evidence channel for this milestone.
- JNI string handling converts UTF-8 to UTF-16 explicitly, so 4-byte emoji and Arabic text stream safely.

---

## 4. Persistence

`files/model_settings.json` (atomic write + corrupt-file backup, schema version 1):

```json
{
  "schemaVersion": 1,
  "settings": [{
    "modelKey": "sha256:…",
    "params": { "temperature": 0.7, "topP": 0.8, "topK": 20, "maxTokens": 512, … },
    "touchedParams": ["seed", "stop"],
    "systemPrompt": null,
    "template": "chatml",
    "userModifiedAt": 1795078260366
  }]
}
```

- `modelKey` is the fingerprint `sha256:` over size + first 64 KiB + last 64 KiB, so renaming a file does
  not lose its settings and a different GGUF can never inherit another model's edits.
- The Advanced UI mode is a global preference (`model_advanced` in SharedPreferences), not per model.
- Prompt presets write the plain global system prompt (`system_prompt`), so they remain editable by hand,
  and their optional parameter layer lands in the same `touchedParams` vocabulary as manual edits, so every
  field stays individually resettable.

---

## 5. Tests

**132 JVM tests, 0 failures** (`:core:runtime` 34, `:core:design` 13, `:feature:chat` 22, `:app` 63).
New or extended for M5:

| Suite | Tests | What it pins |
|---|---|---|
| `ModelControlTest` | 18 | ranges/clamping, override sparse semantics, GGUF sampling mapping, resolver precedence and sources, settings JSON round-trip, fingerprint, presets, template/prompt resolution, prompt-preset localisation |
| `AppModelControlServiceTest` | 16 | end-to-end wiring with fakes: GGUF > preset > user touched, save/reload, per-field reset, reset-template keeps params, reset-all, options carry resolved prompt |
| `ModelParameterCatalogTest` | 6 | every `GenerationParams` field is editable through exactly one path; catalogue ranges mirror runtime clamps; snap/format round-trip |
| `ChatStoreTest` | 8 | (new) a send hands the provider's resolved `GenerationOptions` to the engine |
| `ModelControlConfigTest` | 8 | sparse app.json layer, sampling-mode round-trip, preset assets exist/valid/bilingual, app.json preset paths |
| `FileModelSettingsStoreTest` / `FingerprintModelIdentityTest` | 6 + 5 | atomic persistence, corrupt backup, stable keys, fingerprints |
| Existing suites | 65 | no regression in composition, config, conversation persistence, chat behaviour, theme packs |

This milestone's evidence also fixed real defects the tests now guard: the GGUF facts key mismatch
(architecture/tokens silently empty), a second source of truth for Top P's lower bound, and a settings row
that squeezed its trailing reset button off-screen behind a long prompt.

---

## 6. Device evidence (emulator, API 36, x86_64)

Environment note: per F8 there is **no test phone**; the user approved the emulator for the "Test phone"
role, so all evidence below is from `Nawa_Phone` (Android 16, 6 GB, RTL Arabic).

Model: `qwen2.5-0.5b-instruct-q4_k_m.gguf`, 491,400,032 bytes, imported through the SAF picker into
app-private storage.

Screenshots in `docs/assets/`:

| file | shows |
|---|---|
| `m5-c2.png` | Settings → Engine, model control summary: selected model, defaults source "family preset", advanced switch, temperature 0.70 (preset) |
| `m5-c3.png` | Advanced mode ON, full parameter list begins |
| `m5-c4.png` | advanced sliders (Top K 20, max tokens 512, repetition penalty 1.05, window 64) |
| `m5-c5.png` | XTC/DRY/Top-n σ and the sampling-mode segmented control |
| `m5-preview.png` | prompt preview sheet rendering the exact chatml request (system + user) |
| `m5-templates.png` | template chooser listing the engine's built-in templates |
| `m5-presets.png` | prompt presets applied; global prompt row with per-row reset |
| `m5-seed-answer.png` | chat answer with the M5 settings active |
| `m5-answer.png` | chat with the concise prompt preset active |
| `m5fix-top.png`, `m5fix-mid.png`, `m5fix-bottom.png` | after the review pass: aligned padding, full-height sliders, advanced rows |
| `m5fix-template2.png` | after the review pass: full SHA-256 stacked under its label, template source "built-in", chatml choice |

**Native proof line** (`adb logcat -s SirrakLlama`) after setting seed 42, stop `DONE`, forcing chatml, and
keeping the family preset:

```
STATS prompt_tokens=84 dropped_turns=0 prefill_ms=5638.1 ttft_ms=5639.6 generated=5 gen_ms=551.9
tok_s=9.06 peak_rss_mb=792.5 stopped=0 stop_seq=0 stops=1 template=chatml temp=0.70 top_k=20
top_p=0.80 min_p=0.05 repeat_penalty=1.05 seed=42 mode=0
```

Every field in that line is an M5 layer made visible: family preset (temp/topK/topP/minP/repeat),
per-model touched overrides (seed, stop), forced template (chatml). `model_settings.json` on the device
showed `touchedParams:["seed","stop"]`, `template:"chatml"`, `userModifiedAt` fresh after each edit, and
`params:null` again after resetting the temperature override.

---

## 7. Release build

- `assembleRelease` (R8) succeeded; signed with the debug keystore via `apksigner` and installed over the
  debug build (same certificate) so data survived the install.
- Release APK: `app/build/outputs/apk/release/app-release-signed.apk`, versionName `0.1.0-m5`.
- Rebuilt after the review pass (R8) and installed over debug again; versionName stays `0.1.0-m5`.
- Verified after install: version row shows `0.1.0-m5`, model still loaded, model-control settings still
  present, chat still streams. See `m5-release-home.png`, `m5-release-settings.png`, and the
  `m5fix-*.png` captures above.

---

## 8. Lessons recorded

1. **Two sources of truth surface as UI bugs.** The catalogue advertised Top P min 0.01 while the runtime
   clamp said 0.0; the new catalogue test caught it and the runtime became the only owner.
2. **Native keys must be canonicalised at the JNI boundary.** M5's first build showed "architecture —"
   because the native side emitted GGUF-style keys while `ModelFacts.parse` expected canonical names;
   the fix belongs in `llama_jni.cpp`, not in a tolerant parser.
3. **A long value can starve a trailing action.** The shared settings row needed a single-line,
   width-limited value path when a trailing button exists; found on device, fixed in `:core:design`.
4. **Sliders with dozens of steps read as dotted rulers.** The UI now renders a continuous track and snaps
   the value in code; the data still defines the step grid.
5. **Stop sequences need a real filter, not `contains`.** Holding back the longest stop prefix is the only
   way to avoid emitting a partial sequence or splitting a multi-byte character.

---

## 9. Limits and next

- **M5b (next)**: Jinja/chat-template compilation spike. Today only llama.cpp's built-in template list is
  available; models whose GGUF ships a custom Jinja template render through llama.cpp's own fallback.
- Mirostat/XTC/DRY parameters are exposed and passed to llama.cpp, but only default-mode sampling has been
  exercised on device so far; greedy/Mirostat behaviour will be sampled during M6 chat work.
- Conversation-level parameter overrides were wired in the resolver in M5 and gained their UI and
  persistence in M6 ([doc 20](20-sirrak-m6-chat-library.md) §2.8).
- 0.5B is the only model exercised on the emulator; the 1.5B GGUF is staged in the emulator's Download
  folder for the M6 reading tests.

Per the roadmap ([doc 06](06-roadmap.md)) M6 is the chat library and reading experience; the goal keeps
running toward M6–M11 until the founder stops it.