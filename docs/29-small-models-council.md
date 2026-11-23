# 29 — Compact models, formats and the low-RAM tier (Qiyas council record)

Date: 2026-11-23 · Status: shipped as data in `main`; needs a signed rebuild (`1.0.0-rc2`) to reach installs.

The owner asked four questions and asked for the council: does Sirrak support GGUF, can we get smaller models for
low-compute phones, is there anything at 50–100 M parameters or smaller, and do safetensors or other Hugging Face /
GitHub formats work? This record keeps the evidence, the council verdict, the device gate results and the conversion path.

## 1. Short answer

- **GGUF is the native text format.** The engine is llama.cpp (`:engine:llamacpp`) and it loads GGUF only. Vision
  uses a second GGUF (`mmproj`) next to the text model; document embeddings use `.litertlm` through LiteRT; dictation
  uses sherpa-onnx Whisper int8 ONNX (three-file layout). **Safetensors, PyTorch bins, ONNX LLMs and TensorFlow
  checkpoints are not loadable** — convert them to GGUF, or download a community GGUF.
- **Three ways to get a model in:** the curated catalogue (SHA-256 verified, resumable, Wi-Fi only), the system
  document picker (SAF, header-validated), and **Add from Hugging Face** (paste repo + file name; the app says in the
  dialog that this path is unchecksummed and has no known size).
- **Smaller than 0.5 B:** yes, and one is now in the catalogue. The verified floor for a model that still claims Arabic
  is **LFM2.5-350M Instruct Q4_K_M — 229,312,224 B (218.7 MiB)**, official language list en, ar, zh, fr, de, ja, ko, es.
  The next candidate, **Qwen3.5-0.8B Q4_K_M** (532,517,120 B / 507.8 MiB, Apache-2.0, `qwen35` architecture), **loaded
  but failed the device gate**: llama.cpp applies its template with thinking on by default, so the first answer started
  with a `<think>` block. It is not in the catalogue until the engine can pass `enable_thinking=false`.
- **True 50–100 M tier:** SmolLM2-135M-Instruct Q4_K_M is 105,454,432 B (100.6 MiB, Apache-2.0) and can be side-loaded
  through the Hugging Face dialog, but it is **English-only**, so it is not in the Arabic-first catalogue. No
  Arabic-capable model below ~350 M was found that is both loadable and worth offering.
- **Arabic-native but not loadable:** Jais-3-0.9B has a GGUF, but its header says `general.architecture = k2-horizon`;
  the vendored llama.cpp knows `jais` and `jais2` but not `k2-horizon` (`third_party/llama.cpp/src/llama-arch.cpp`),
  so it would fail at load. It is not offered.

## 2. Verified candidates

Sizes and SHA-256 come from the Hugging Face API LFS metadata (`?blobs=true`), not from a download of the full file;
architectures were read from the first 20 MB of each GGUF with a header parser (`general.architecture`,
`tokenizer.chat_template`). Verified 2026-11-23.

| Model | Quant | File size | SHA-256 (LFS) | Licence | Languages | Arch | Verdict |
|---|---|---|---|---|---|---|---|
| LFM2.5-350M Instruct | Q4_K_M | 229,312,224 B / 218.7 MiB | `7e6f72643caafc9a68256686638c4d7916f2cec76d1df478d4c3ddcd95a6aed4` | LFM Open License v1.0 | en, ar, zh, fr, de, ja, ko, es | `lfm2` | **Added** — 2 GB tier, load-verified |
| Qwen3.5-0.8B | Q4_K_M | 532,517,120 B / 507.8 MiB | `bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517` | Apache-2.0 | 201 languages (card) | `qwen35` | **Rejected after Step Zero** — thinking on by default |
| Qwen2.5-0.5B Instruct (existing) | Q4_K_M | 491,400,032 B / 468.6 MiB | `74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db` | Apache-2.0 | ar, en | `qwen2` | **Unchanged default** |
| Qwen3-0.6B | Q4_K_M | 396,705,472 B / 378.3 MiB | `ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a` | Apache-2.0 | 100+ languages (card) | `qwen3` | Verified, **not added** (same thinking-mode risk; Qwen2.5 remains the safe default) |
| SmolLM2-135M Instruct | Q4_K_M | 105,454,432 B / 100.6 MiB | `2e8040ceae7815abe0dcb3540b9995eaa1fa0d2ca9e797d0a635ae4433c68c2d` | Apache-2.0 | English | `llama` | Side-load only — no Arabic |
| MiniCPM4-0.5B QAT Int4 | Q4_0 | 265,307,040 B / 253 MiB | `fa4ad3f448355578ce5e4021204be319e5a3cb665fb173607f92bc139c96a290` | Apache-2.0 | zh, en | `minicpm` | Not added — no Arabic |
| Gemma 3 270M IT | Q8_0 | 291,545,600 B / 278 MiB | `0ef57d2c838458a1952664260dcba38e5bdda37494f3af732f06e4add24068e3` | Gemma licence | English-centric | `gemma3` | Not added — licence + Arabic quality |
| Jais-3-0.9B | Q4_K_M | 666,192,384 B / 635.3 MiB | `e56a4b769c397a1fd2620cf1eec94513debb1664092d0dfad4af01083c2ac3b0` | Apache-2.0 | ar, en | `k2-horizon` | **Rejected** — architecture unsupported by the vendored llama.cpp |

Reading the table: the smallest file is not the best model. Below ~350 M a chat model loses enough Arabic to be a demo,
not a product; LFM2.5-350M is the practical floor and Qwen2.5-0.5B remains the honest default. Qwen3.5-0.8B is newer and
multilingual, but the app's current template path turns its thinking mode on, which is a worse experience on a slow
phone — so it was rejected at the device gate instead of shipped on paper.

## 3. Council (Qiyas Mode C, condensed)

**Roles.** Expert (on-device inference architect), Engineer, Devil's Advocate, Naive User (owner of a 2 GB phone),
Maestro; dynamic roles: Licence & Supply-Chain Guardian and Arabic Quality Reviewer.

**Global invariants.**
1. A format is "supported" only if the shipped runtime executes it — no aspirational rows.
2. Arabic quality is a product constraint: a model that cannot hold a short Arabic conversation is not "supported".
3. Adding a model is a data change (catalogue JSON + two strings), never a code change.
4. The default recommendation must stay honest and cheap; a new model joins only with a recorded size and SHA-256.

**Transcript, compressed.**
- *Expert:* GGUF is the only text format the engine reads; safetensors needs an external conversion. For the format
  question the answer is no at runtime, yes as a documented pipeline.
- *Engineer:* the nice property is that a catalogue row is pure data. We can offer a 2 GB tier without touching the
  engine or the recommender; the recommender already takes the first row that fits RAM and storage, so order is the
  product decision.
- *Devil's Advocate:* "supports smaller models" is false if the engine rejects the architecture. Jais-3-0.9B is the
  proof: a GGUF exists and it still will not load, because the file says `k2-horizon`. Do not list it.
- *Arabic Quality Reviewer:* LFM2.5-350M lists Arabic, but "listed" is not "measured". It must be described as a
  short-answer, lower-quality tier, not sold as Arabic-capable in the same breath as Qwen2.5. The load/Arabic gate must
  be recorded, not assumed.
- *Licence Guardian:* LFM Open License v1.0 is not Apache-2.0; the catalogue shows licence before download, and that
  stays. Gemma's licence is the reason Gemma 3 270M is not in the list. Digests must come from the LFS metadata so the
  downloader can verify them.
- *Naive User:* "I have a 3 GB phone, which one do I tap?" — the answer must still be one obvious card, not a menu.
- *Engineer (revision):* then keep Qwen2.5-0.5B first, add LFM2.5-350M after it (only a <3 GB phone reaches it), and try
  Qwen3.5-0.8B as the next row with `minRamMb: 4096` so it is opt-in, not the default. Qwen3-0.6B becomes redundant.
- *Devil's Advocate (named objection):* *Naqd* — "Qwen3.5-0.8B is a vision-language model; loading its text GGUF is an
  untested claim." Engineer answers with the header (arch `qwen35`, 320 tensors, template embedded) and accepts that
  only a real device load settles it. **The device load did run: the model loaded, but thinking mode was on.**
- *Maestro:* accept the data change for LFM2.5, reject Jais and the 50–100 M Arabic myth, and let the device gate — not
  the model card — decide Qwen3.5. It did: the row was removed.

**Objections and answers.**

| Objection | Type | Answer |
|---|---|---|
| "A GGUF file means the model works" | *Naqd* (contradiction) | False: architecture string must be in the vendored llama.cpp. Jais-3-0.9B proves it. |
| "Smaller always fits a low-end phone" | *Farq mu'aththir* (distinguishing difference) | Fit is RAM + storage + KV cache, not just file size; that is why `minRamMb` exists and the recommender uses it. |
| "List every small model" | *'Adam ta'thir* (irrelevant condition) | A long catalogue makes the choice harder for the Naive User; only one row was added, default unchanged. |
| "Call LFM2.5 an Arabic model" | *Fasad i'tibar* (invalid premise) | It is an *Arabic-listed* model with a lower quality tier; the description says so and the device gate recorded its actual output. |
| "Qwen3.5 template says thinking off" | *Tahqiq manat* (verify the condition) | The template's default branch is off, but llama.cpp applies the template with `enable_thinking` on; the device probe showed `<think>` in the answer, so the condition failed. |
| "Supports safetensors via conversion" | *Tahqiq manat* | Conversion is external and documented; on-device the answer stays no. |

**Maestro verdict.**

| Branch | Status | Ruling |
|---|---|---|
| GGUF support | Proven | Native format; keep documenting it as the only text format. |
| Safetensors / PyTorch / ONNX LLM | Rejected on device | Document the conversion pipeline; no runtime work. |
| LFM2.5-350M Q4_K_M | **Accepted and load-verified** | 2 GB tier, catalogue row + string, licence shown. |
| Qwen3.5-0.8B Q4_K_M | **Rejected after the device gate** | Loads (1,659 ms) but answers start with a `<think>` block; row removed. Follow-up: pass `enable_thinking=false` (or strip thinking) in the engine, then re-run the gate. |
| Qwen3-0.6B | Verified, not added | Same thinking-mode risk; no catalogue row. |
| Jais-3-0.9B | Rejected | Unsupported architecture string; not offered. |
| 50–100 M Arabic | Not found | No verified Arabic-capable option; English 135 M stays side-load only. |

**Step Zero evidence (2026-11-23, `Nawa_Phone` API 36, x86_64).**
- The real app parsed the catalogue asset in `ModelManagerDeviceTest#catalogueAssetIsValidAndRecommendsAModel`:
  **5 models, 0 validation errors**, recommended `qwen2.5-0.5b-instruct-q4_k_m` on a 5,923 MB profile — the default
  is unchanged, as designed.
- A temporary probe (not committed, because it needs a multi-hundred-megabyte staged file) loaded both GGUFs through the
  production isolated engine and asked one Arabic and one English question:

| Model | Load | First token (AR) | Arabic answer | Speed |
|---|---|---|---|---|
| LFM2.5-350M Q4_K_M | 579–894 ms | 1,233–1,312 ms | `السماء تغني أغنية.` (10 tokens) | 5.6–5.9 tok/s |
| Qwen3.5-0.8B Q4_K_M | 1,659 ms | 1,972 ms | started with `<think> Thinking Process: …` and was cut at 48 tokens | 6.8 tok/s |

LFM2.5 passed (loads, answers Arabic, no thinking block); Qwen3.5 failed the experience gate (thinking block first).
The 2 GB RAM profile and real arm64 phones are still open; the emulator has 5,923 MB, so the low-RAM path is verified by
the recommender's unit tests and the catalogue's `minRamMb`, not by a 2 GB device.

**Metrics recorded at the gate.** load time (ms), first-token latency, tokens/s, peak RSS (not captured — follow-up),
SHA-256 verification (both files match the catalogue values), and the recommender's chosen row (Qwen2.5-0.5B on the
emulator profile).

## 4. What changed in the repository

`app/src/main/assets/catalog/models_catalogue.json` now has five rows in this order:

1. `qwen2.5-0.5b-instruct-q4_k_m` — unchanged default for 3 GB+ devices.
2. `lfm2.5-350m-instruct-q4_k_m` — 218.7 MiB, `minRamMb: 2048`, so a 2 GB device gets a fitting recommendation
   instead of `NOTHING_FITS`; also the first fit when a 3 GB phone is short on storage.
3. `qwen2.5-1.5b-instruct-q4_k_m` — unchanged.
4. `smolvlm-500m-instruct-q8_0` — unchanged (vision, English-centric).
5. `llama-3.2-1b-instruct-q4_k_m` — unchanged (gated).

One string was added in both locales (`feature/models/.../values/strings.xml` and `values-en/strings.xml`):
`catalogue_lfm25_350m`. The row carries the LFS SHA-256 value, so catalogue downloads keep the resume + checksum path.
No Kotlin, no engine, no Gradle change: the recommender's documented rule (first row that fits RAM and storage) does the
tiering, exactly as `ModelRecommenderTest` expects.

**Reaching phones.** The catalogue is an APK asset, so these rows ship in the **next signed build** (`1.0.0-rc2`,
versionCode 13, new tag — never re-tag `v1.0.0-rc1`). On the current `rc1` build the same model can already be tried
today through **Settings → Models → Add from Hugging Face**: repo `LiquidAI/LFM2.5-350M-GGUF`, file
`LFM2.5-350M-Q4_K_M.gguf` (that path is unchecksummed by design; the dialog says so). Qwen3.5-0.8B can likewise be
side-loaded for inspection, but expect a thinking block until the engine change lands.

## 5. Converting a safetensors model to GGUF (external, documented)

The app will not load safetensors; the supported path is an offline conversion on a PC, then import the GGUF.

1. Download the HF repo (`hf download <repo> --local-dir model-src`) — or use a community GGUF if one exists.
2. Convert with llama.cpp's converter:
   `python convert_hf_to_gguf.py model-src --outtype f16 --outfile model-f16.gguf`
3. Quantise: `llama-quantize model-f16.gguf model-Q4_K_M.gguf Q4_K_M` (Q4_K_M is the catalogue default;
   `Q3_K_M`/`IQ4_XS` when storage is tighter, `Q5_K_M`/`Q6_K`/`Q8_0` when quality matters more).
4. Check the architecture is in the vendored runtime: the GGUF's `general.architecture` string must appear in
   `third_party/llama.cpp/src/llama-arch.cpp`. Unknown arch = the app cannot load it (this is why Jais-3-0.9B is out).
5. Record the file size and SHA-256, add the catalogue row (params, quant, context, `ramMb`, `minRamMb`, licence,
   languages, description key) and the two description strings, then run the load/Arabic gate before release.
6. `sha256sum model-Q4_K_M.gguf` is the value the downloader verifies; GitHub-hosted releases work the same way (the
   dialog takes a repo + file name, and the file import path accepts any GGUF from storage).

## 6. Honest limits

- LFM2.5-350M is load-verified on the **API 36 x86_64 emulator only**: load 579–894 ms, 5.6–5.9 tok/s, one short Arabic
  sentence. No arm64 phone, no 2 GB RAM device, no thermals/battery numbers, and no multi-turn quality evaluation yet.
- Qwen3.5-0.8B loads but is rejected from the catalogue because llama.cpp's template application enables its thinking
  mode; the Arabic answer in the probe was a truncated thinking block. Follow-up: engine support for
  `enable_thinking=false` (or stripping reasoning blocks) and then re-run the gate.
- LFM2.5 is a vision-capable family too, but only its text GGUF is offered; the app's vision path stays SmolVLM 500M
  (English-centric) until a projector for another family is validated.
- LFM Open License v1.0 is not Apache-2.0; the licence is shown before download and no weights are redistributed by us.
- The Add-from-Hugging-Face path remains unchecksummed and unknown-size by design; the curated catalogue is the
  verified path.
- No Arabic-capable model at 50–100 M parameters was verified; below ~350 M the quality claim would be dishonest.
- Peak RSS was not captured in the probe (follow-up for the next device pass).
