# 34 — A model bigger than the phone: disk-backed offload research and council ruling

**Date:** 25 Nov 2026 · **Council:** Qiyas Mode C (merged, English) · **Status:** research record only — nothing is implemented and no capability is claimed
**Owner question:** recorded in [27](27-sirrak-launch-checklist.md) §P3.11 ("research running models larger than RAM/VRAM on a phone and bring the idea through a council before any implementation")
**Precedent records:** [29](29-small-models-council.md) · [31](31-ux-rework-council.md) · [33](33-ux-audit-1.1.0.md)
**Research method:** desk research plus reading the pinned engine (`third_party/llama.cpp`) and the app's own measured records. The web-search tool was unavailable in this session (provider credit limit), so nothing was fetched live: literature is cited by arXiv identifier from desk knowledge, and every claim that could not be checked against a source or this repository carries **[unverified]**. No URL, hash or benchmark number was invented.

**The question.** Can Sirrak run a model bigger than the phone can normally hold, using disk and loading only what a token needs — like the old "free token" PC project — if it is plausible; perhaps only on higher-compute phones, or by letting the user wait and accept a slow answer. Research and a council first, no implementation.

## 0. The answer in one paragraph

Android has no second memory tier to offload into: the only thing below RAM is the page cache of the model file, and the pinned engine already memory-maps every GGUF by default. "Running a bigger model" is therefore not a missing capability to add but a permission question with a simple cost law: when the working set no longer fits the reclaimable page cache, a dense model re-reads from flash everything that does not fit, on every token. The target phone has never run a model and its flash bandwidth has never been measured [unverified]; on this device class the arithmetic gives ≈0.1–0.6 tok/s for a 1.5B-class file and ≈0.02–0.1 tok/s for 3B — minutes per short answer, a warm phone and a drained battery. The council keeps the honest gate as the default, allows at most a default-off, double-confirmation "slow disk-backed attempt" for 1.5B-class models behind Advanced/debug, drops dense layer streaming and cloud offload, and requires two cheap measurements before any code.

## 1. The owner's question and Sirrak's constraints today

### 1.1 What is fixed (not debated below)

| Area | Fact | Source |
|---|---|---|
| Platform | Kotlin/Compose app, minSdk 28; phone = Redmi Note 7, Android 10/API 29, arm64-v8a; emulator = API 36, x86_64, 6 GB RAM, 16 GB `/data` | `engine/llamacpp/build.gradle.kts`, [07](07-test-environment-emulator.md), [31](31-ux-rework-council.md) §0.6, [32](32-ux-rework-build-record.md) |
| Engine | vendored llama.cpp `third_party/llama.cpp` (CMake 1.0.0 + dev flag), static into `libsirrak_llm.so`; CPU-only: `n_gpu_layers = 0` (`llama_jni.cpp:573`); `n_batch = n_ubatch = 512`; `n_threads = min(4, hw)`; `n_ctx` from the per-model setting; OpenMP/backends-DL off | `llama_jni.cpp`, `engine/llamacpp/src/main/cpp/CMakeLists.txt` |
| Memory gate | usable RAM = `MemAvailable − 2 × threshold`; footprint = file + KV(ctx) + 300 MB overhead; disk ≥ 1.1 × file; `isLowRamDevice` + file > 1 GB blocks; `WONT_RUN` disables the download action | `core/models/.../ModelFit.kt`, `LibraryScreen.kt:610`, `HfBrowseLogic.kt:108` |
| Devices | phone total RAM 3 826 256 kB, `MemAvailable` ≈ 1.35 GB, `/data` 107 GB with 15 GB free; **no model has ever been run on the phone** (physical-phone smoke still open) | [31](31-ux-rework-council.md) §0.6, [32](32-ux-rework-build-record.md) |
| Catalogue | 0.35B 229 312 224 B · 0.5B 491 400 032 B · 1B 807 000 000 B · 1.5B 1 117 320 736 B · SmolVLM 500M 436 806 912 B + 108 783 360 B projector | `app/src/main/assets/catalog/models_catalogue.json` |
| Privacy | exactly three declared, user-initiated endpoints, all model acquisition on huggingface.co; no inference traffic; offline switch; no telemetry | `app/src/main/assets/config/app.json` |
| Language | Arabic-first, strings in `values/` (AR) + `values-en/`, vocabulary from [30](30-arabic-terminology.md) | repo rule |

### 1.2 Fit facts already known on this phone

From the measured phone capacity and the real GGUF sizes in [31](31-ux-rework-council.md) §0.6:

| Model | arithmetic | verdict on the Redmi |
|---|---|---|
| Qwen2.5 0.5B Q4_K_M | 469 + 25 + 300 ≈ 794 MB | runs (usable ≈ 1.15 GB) |
| Llama-3.2 1B Q4_K_M | 808 + 67 + 300 ≈ 1 175 MB | tight (needs ≥ 1 305 MB at the 0.9 rule) |
| Qwen2.5 1.5B Q4_K_M | 1 117 + 59 + 300 ≈ 1 476 MB | won't run |

The gate's line on this phone therefore sits between 0.5B and 1.5B. Everything called "oversize" in this document is on the far side of that line: 1.5B, 2B, 3B, 7–8B, and a 20B-class MoE.

### 1.3 What does *not* exist today

- No override of any kind, no "load anyway", no experimental load mode; the engine is loaded exactly as the JNI above describes.
- No layer-by-layer loading, no expert cache, no per-tensor placement, no disk prefetch policy of our own.
- No phone measurement of anything: every number in §4 is emulator, and [07](07-test-environment-emulator.md) already says emulator tok/s must never be reported as phone performance.
- No 3B quality claim: Qwen2.5-3B showed prompt-sensitive failures (one English word inside an Arabic answer; one repeated-character degeneration elsewhere) and answered a short factual question correctly ([27](27-sirrak-launch-checklist.md) §P3.11). That is small-model quality behaviour, not a proven engine defect, and it is neither a capability nor a fix-first blocker.
- `WONT_RUN` currently folds "cannot allocate" and "will page slowly" into one verdict. The second is exactly the owner's question, so the wording will need a new reason code whatever the decision.

## 2. What actually happens on a phone

### 2.1 There is no second memory tier

Android is unified memory: there is no VRAM to offload *from*. "Disk offload" can only mean the kernel's page cache for a memory-mapped file. Clean file-backed pages are reclaimable under pressure; anonymous memory (KV cache, ggml compute buffers, the Kotlin heap) is not — it is either swapped to zram (if the vendor enabled it [unverified on this phone]) or the low-memory killer ends the process. The app already demonstrates the mapping: `dumpsys meminfo` after a first answer shows `Other mmap ≈ 476 847 KB` for a 1.12 GB GGUF and total RSS 790 MB (`docs/audit-1.1.0/01b-cold-start-evidence.md:136-137`). mmap is live; residency is partial; the file is not copied into RAM.

### 2.2 What the engine does at load (verified in this tree)

- `llama_model_default_params()` sets `load_mode = LLAMA_LOAD_MODE_AUTO`, `lazy_mode = AUTO`, and `n_gpu_layers = -1`; our JNI overrides only the last to `0` (`llama_jni.cpp:573`). AUTO resolves to mmap for a file-backed model.
- With mmap the loader calls `init_mappings(true)` (`src/llama-model.cpp:2012`), which passes "prefetch the whole file" (`prefetch_size = -1` at `src/llama-model-loader.cpp:1524`). On Linux that becomes `MAP_POPULATE` plus `POSIX_FADV_SEQUENTIAL` (`src/llama-mmap.cpp:718-723`), except for tensors marked lazy. So a load does not stream "only the layers needed": today it asks the kernel to prefetch the entire mapping and then faults pages as decode touches them. For a 2–3 GB file on a 1.35 GB-free phone that is a long load stall and immediate cache churn before the first token [unverified: MAP_POPULATE is best-effort and reclaim interleaves in ways no run here measured].
- CPU-only means every tensor is a host tensor. Upstream's tensor-placement flags (`-ot`, `--n-cpu-moe` → `tensor_buft_overrides`, `common/arg.cpp:2717,2730`) and the experimental MoE host cache (`moe_cache_size` / `moe_cache_cpu_share`, `include/llama.h:399-400`) exist to move weights between GPU VRAM and host RAM. This build has no GPU backend and does not compile `common`/`tools`, and on a phone both sides of that split are the same RAM anyway.
- The one genuine on-demand read in the tree is `lazy_mode` for tensors an architecture flags `TENSOR_READ_LAZY`: rows are read from the mapping on demand, AUTO only for tensors > 4 GiB, and only `deepseek41`, `gemma4` and `qwen4exp` mark per-layer token embeddings that way (`src/llama-model-loader.cpp:1188-1198,1437-1440`; `src/models/deepseek41.cpp:163`, `gemma4.cpp:65`, `qwen4exp.cpp:217`). No dense 1–8B GGUF uses it, and it is not a public "stream arbitrary layers" mode.

### 2.3 Decode is a working-set law, not a loading problem

A dense decoder reads every weight once per generated token (and once per 512-token ubatch during prefill). If file + KV fits the reclaimable cache, decode runs at CPU speed and the mapping is almost free. If it does not, the kernel evicts pages the next token needs; for a cyclic scan larger than the cache, strict LRU gives close to 0% reuse, so the share that does not fit is re-read from flash every token:

    tokens/s  ≈  effective flash bandwidth / bytes that do not fit per token

Our own cold/warm evidence bounds the page-in rate: first prefill after a cold boot 26.7 s vs 8.6 s warm on a 1.12 GB file ([33](33-ux-audit-1.1.0.md) §P0-6; `docs/audit-1.1.0/06-performance-motion.md`) — roughly 62 MB/s effective through the emulator's virtual disk even on a desktop host [derived]. The phone's flash rate is unmeasured [unverified].

### 2.4 What breaks is heat, battery and patience — rarely allocation

With a capped context the anonymous side is modest: by the fit formula a 3B/512-token KV is ≈19 MB (36 layers, 2 KV heads, head dim 128 — configuration not verified [unverified]), plus a few hundred MB of compute buffers and app memory. An oversize dense model may therefore load and run without the LMK killing it: clean file pages are reclaimed, not counted against the process. What the user gets is tens of minutes of sustained flash reads plus CPU at 100%, a warm phone, a drained battery, and a screen that has to stay on (Android may freeze a backgrounded app; this app's generation is not a foreground service). Whether *this* phone pages or kills under that pressure has never been tested [unverified]. Storage endurance is also not free, though one experiment is negligible against the device's lifetime; the user's photo/video space is the real budget (15 GB free today).

### 2.5 The gate is right, its wording is incomplete

Today's `WONT_RUN` is honest about the outcome (the app would not be usable) but not about the mechanism: the model is not impossible, it is slow. A future reason code — `SLOW_DISK_BACKED` — would let the UI say "it would run from storage, slowly" without weakening the default block, and is worth adding even if no override ever ships.

## 3. Literature and projects

| Work | Mechanism | What transfers to Sirrak | What does not | Certainty |
|---|---|---|---|---|
| llama.cpp mmap page-cache loading (this tree) | file-backed pages faulted on use; capacity beyond RAM, speed = flash | the engine already does this; an override would be a permission, not a feature | nothing about speed; our path prefetches the whole file at load | verified in tree |
| *LLM in a flash* (arXiv:2312.11514, Apple) | windowed weight loading + row-column bundling for flash-resident weights; models up to ~2× DRAM, faster than naive flash loading | proves mobile flash streaming is measurable, and that the trick is a narrow window per step | custom engine/hardware; exact speedups, model sizes and device configs not verified here [unverified]; no GGUF/llama.cpp equivalent | [unverified] |
| *FlexGen* (arXiv:2303.06865) | LP-scheduled offload of weights and KV to CPU disk/NVMe, throughput-optimised with large batches | shows the tier arithmetic honestly | its objective is batched throughput, the opposite of one-token-at-a-time chat; server NVMe is not phone flash [unverified figures] | [unverified] |
| DeepSpeed *ZeRO-Inference* (arXiv:2207.00032) | weight/KV offload to CPU and NVMe to run huge models on small GPUs | proves offload at scale | throughput-oriented, no mobile port or latency story [unverified details] | [unverified] |
| *PowerInfer* (arXiv:2312.12456) and *PowerInfer-2* (arXiv:2406.06282) | exploit activation sparsity/locality: hot neurons/experts in fast memory, cold ones on CPU or flash; PowerInfer-2 targets smartphones with a custom runtime and sparse-ised models | the only published route to interactive-class speed for >RAM models on a phone; the cause is a **narrow active set**, not the disk | needs sparse models and a custom engine; flagship-phone class; not llama.cpp, not a dense 1.5–3B GGUF [unverified models/devices/figures] | [unverified] |
| *MoE-Infinity* (arXiv:2401.14361) | expert-activation-aware caching and prefetch across requests | confirms MoE is where offload can pay | its expert cache lives in host RAM of a GPU server; on a CPU-only phone "host RAM" is the only RAM, so nothing new; a 20B-class MoE does not fit 15 GB of disk *and* its active set does not fit cache [unverified sizes] | [unverified] |
| AirLLM (open project) | load one layer at a time, compute, free | the simplest mental model of layer streaming | seconds-to-minutes per token, Python/GPU stack, no Android or llama.cpp port; not interactive [unverified] | [unverified] |
| llama.cpp `-ot` / `--n-cpu-moe`, `moe_cache_size` | tensor placement between devices, host expert cache | upstream is moving toward hybrid expert memory | CPU-only phone has no faster tier; none of it touches disk | verified in tree, unusable here |
| MNN-LLM and other mobile stacks | quantised mobile engines with static memory planning | the normal answer for phones is "fit the model", not "stream it" | no published oversize/disk-streaming mode; absence claim [unverified] | [unverified] |

**Synthesis.** Every technique that makes a >RAM model *interactive* narrows the bytes read per token — sparsity, MoE routing, lazy tensors, a designed window. Every technique that simply moves bytes to slower storage (AirLLM, FlexGen, ZeRO-Inference) accepts latency that is fine for batch jobs and fatal for a chat reply. Our dense catalogue has no narrow active set, and our engine has no custom streaming scheduler. That leaves two honest options: a measured permission experiment (§7), or a research spike on sparse models (§5-D) that the numbers below currently fail.

## 4. Measured and reported expectations

### 4.1 Our own numbers — emulator only

| Model (Q4_K_M unless noted) | file | emulator observation | source |
|---|---|---|---|
| Qwen2.5 0.5B | 0.49 GB | 4.35–9.06 tok/s across builds; peak RSS 711–793 MB; load ~1.3 s | [14](14-sirrak-m2-engine.md), [19](19-sirrak-m5-model-control.md), [25](25-sirrak-m11-model-manager.md) |
| LFM2.5 350M | 0.23 GB | 5.6–5.9 tok/s | [29](29-small-models-council.md) |
| Qwen2.5 1.5B | 1.12 GB | 4.35–6.35 tok/s in early/warm runs; 1.93 tok/s cold after launch; **0.3–1.4 tok/s** in the late 1.1.0 finding; peak RSS 1.38–1.43 GB | [14](14-sirrak-m2-engine.md), [22](22-sirrak-m8-document-rag.md), [27](27-sirrak-launch-checklist.md), [33](33-ux-audit-1.1.0.md) |
| Qwen2.5 3B | 2.10 GB | **0.1–0.4 tok/s**; prompt-sensitive quality failures in two of three prompts (one mixed-script word; one repeated-character degeneration), one short factual answer correct — small-model behaviour, not a proven engine defect | [27](27-sirrak-launch-checklist.md) §P3.11 |
| SmolVLM 500M Q8 + projector | 0.44 GB | 3.07–31.47 tok/s on tiny samples; peak RSS 2 182 MB (vision buffers) | [23](23-sirrak-m9-vision.md) |
| Redmi Note 7 | — | **no model has ever been loaded on it** | [32](32-ux-rework-build-record.md) |

**Honesty note.** The same 1.5B model on the same emulator spans 0.3–6.35 tok/s depending on build, cold/warm page cache and what else ran; the spread is 20×, which is why §4.2 gives bands, not promises. These are x86_64 host numbers, not phone numbers ([07](07-test-environment-emulator.md)).

### 4.2 What oversize would cost on this phone — estimates, not measurements

Assumptions, all labelled: usable RAM ≈ 1.15 GB (fit engine, §1.2); cache available for weights ≈ 0.9–1.1 GB after system and app anon; page-in from phone flash **80–150 MB/s [unverified — never measured on this phone]**; dense decode reads the non-resident share of the file per token; battery/SoC 3 W sustained [estimate], battery ≈ 15.4 Wh (4 000 mAh, nominal [unverified]); 100-token answer.

| Candidate | file | non-resident share per token | est. decode | 100-token answer | est. battery | verdict |
|---|---|---|---|---|---|---|
| Qwen2.5 1.5B | 1.12 GB | ~0.2–0.4 GB | **0.1–0.6 tok/s** | 3–17 min | ~3–15 % | the only defensible experiment |
| Qwen2.5 2B (Q4) | ~1.4 GB | ~0.4–0.6 GB | 0.05–0.3 tok/s | 6–33 min | 5–30 % | borderline; unmeasured |
| Qwen2.5 3B | 2.10 GB | ~1.0–1.5 GB | **0.02–0.1 tok/s** | 17–83 min | 10–40 % | lab curiosity only |
| Llama 7–8B (Q4) | ~4.7 GB | ~3.6–4.0 GB | 0.01–0.04 tok/s | 42 min–2.8 h | > 50 % | impractical |
| 20B-class MoE (gpt-oss-20b, ~12 GB [unverified size], ~3.6B active [unverified]) | ~12 GB | ~2–3 GB of active experts | 0.01–0.05 tok/s | 33 min–2.8 h | > 50 % | also blocked today: architecture not in `ModelFit.DEFAULT_ARCHITECTURES`; 13.2 GB with the 1.1× margin fits the 15 GB disk but leaves almost nothing |

Every row also pays a first-token wait of roughly one full-file pass (14–35 s for 2.1 GB at these rates) plus compute, and a load stall while the loader's own whole-file prefetch runs [unverified: real kernel behaviour]. On this hardware the difference between 1.5B and 3B is not quality, it is whether the answer comes in minutes or in an hour.

### 4.3 What "acceptable" would have to look like

| Use | Floor for a real user | On this phone |
|---|---|---|
| Chat | ≥ 3 tok/s | unreachable for oversize dense |
| Occasional long task while charging | ≥ 1 tok/s | unreachable for oversize dense |
| Experimental, owner/testers only | ≥ 0.2 tok/s and 20 tokens ≤ 2 min | plausible only for 1.5B-class, unmeasured |

The council's ruling in §6 follows this table: no public feature, at most a labelled experiment for the last row.

## 5. Options A–E

| # | Option | Cost | Risk | Council status |
|---|---|---|---|---|
| A | Keep the gate, change nothing | 0 | none technical; the owner's question stays unanswered | **Survived** (default) |
| B | Fit-gate override: default-off, double-confirmed "slow disk-backed attempt", hard RAM/disk floors, ctx 512–1024, warm/heat/battery warning | S–M (2–4 days: pure-JVM policy, assets, strings, tests, one Advanced switch) | user harm, bad reviews, support load, battery/heat complaints; misuse on low-RAM devices | **Survived, downgraded** (debug/Advanced experiment only) |
| C | Dense layer streaming in the engine: custom loader, per-layer read/unload, prefetch control, GGUF correctness | L–XL (weeks–months forked against a pinned engine) | ceiling is still storage-bandwidth-bound: 0.02–0.1 tok/s for 3B on phone flash [unverified band]; heavy maintenance | **Dropped** for this milestone |
| D | Sparse/MoE offload: expert cache in RAM + disk prefetch, requiring a sparse model and engine work (candidate: a 20B-class MoE) | XL | 15 GB disk vs ~12 GB model; active set > cache; llama.cpp has no such scheduler for our build | **Suspended on test** |
| E | Meet the goal honestly: bigger-RAM phones where 3B–8B simply fit, 2–3-bit quants to fit this phone, better small models, real measured speed/ETA in the UI, and the audit's P0-8 Arabic-quality promise (recommend ≥1.5B for Arabic or label the 0.5B, enforce response language) | S–M | low; existing fit engine already measures bigger phones | **Survived** (mainline) |
| F | Cloud offload | — | breaks the privacy promise outright | **Dropped** before debate (§8) |

## 6. Qiyas council

**Mode C (merged): method header, ground rules, transcript, then the Maestro's ruling.**

### 6.1 Seated roles

| Role | Qiyas duty | Signature question |
|---|---|---|
| **Product** (composite Expert: on-device AI product architect for low-end, Arabic-first) | Takhrij al-Manat — why the precedent really worked, and what it means for this product | Did the PC project work because of disk, or because of its RAM-to-bandwidth ratio? |
| **Android Engineer** | Al-Far' — builds only with a precedent and a stated cause | What is the cause, and what does it cost in this JNI? |
| **Low-RAM User** (Naive User, this device specifically) | Tahqiq al-Manat — does the cause exist in the real user's day? | What is in it for me, and why would I wait 30 minutes? |
| **Skeptic** (Devil's Advocate) | Al-Qawadih — attacks the cause, not the wording | Where does the cause break on an eMMC phone? |
| **Privacy Guardian** (dynamic) | Al-Athar al-Thani — second-order effects on files, prompts and the network audit | Who can read the prompt, and where does it travel? |
| **Maestro** | silent judge | — |

    Role: The Privacy Guardian
    Mandate: the privacy promise across every new flow (files, prompts, network, storage)
    Signature question: "What leaves the phone, and can the user point at where it went?"
    Qiyas tool: Al-Athar al-Thani (second-order effect)
    Red line: no prompt, no generated token, no model content on the network; storage stays user-owned

    Role: The Low-RAM User
    Mandate: the actual experience on 4 GB of RAM with a 2019 battery
    Signature question: "How long until a word appears, and how hot does my pocket get?"
    Qiyas tool: Tahqiq al-Manat (does the cause exist here?)
    Red line: never trade a working answer for a "capability" that stalls the app

### 6.2 Global invariants (Product, before branches)

1. **Privacy is not negotiable.** No cloud inference, no new endpoint, no telemetry — a bigger model may not buy speed with the user's data.
2. **No capability without a measured number.** Estimates are labelled; phone numbers do not exist until the phone runs.
3. **Measured capacity is the source of truth.** Overrides are explicit, per-model, revocable, and never the default.
4. **A 30-minute answer is a different product**, not a slow one; the user's time is a feature cost.
5. **The app owns what it starts**: a long generation is visible, cancellable, and honest about its state.
6. **One copy per fact**: measured tok/s, heat and battery live in one local diagnostics place, not in marketing copy.

### 6.3 Transcript

**Round 1 — positions and precedents.**

**Android Engineer** presents two cards. Q1: *Asl* — llama.cpp on desktop mmaps models larger than free RAM and they "work"; *'illa* — the file's pages live in the page cache, so capacity is decoupled from RAM, but speed is not; *Tanqih* — desktop has 8–64 GB of RAM against 1.35 GB here, and NVMe-class flash against a 2019 phone's storage, so the ratio that made it acceptable is absent; *Condition* — measure the phone's page-in rate and the working-set share before believing any speed. Q2: *Asl* — PowerInfer-2/MoE-Infinity run models whose per-token active set is small; *'illa* — narrow active set, not disk, is why they are fast; *Tanqih* — they need sparse models and custom runtimes; our catalogue is dense and our engine generic; *Condition* — a supported sparse model whose active experts fit in ≈1 GB. Proposes B, C and D.

**Product** applies Takhrij al-Manat to the owner's own precedent: the old PC project worked because the desktop had 4–16× the RAM *and* an SSD/NVMe with several times a phone's random-read behaviour [unverified]; the owner remembers the capability, not the ratio. Product's proposal E (bigger-RAM phones and honest measurement) is the product-shaped answer; B is acceptable only as a labelled experiment; C and D are engine bets, not product bets.

**Low-RAM User** (reality check): "I have 15 GB free, fine — but if I tap answer and get a spinner for 20 minutes, I don't think 'slow model', I think 'broken app', and I uninstall." Rejects C outright; accepts B only if the app says a time, shows progress, and stops instantly.

**Skeptic** attacks: (invalid premise) "mmap means it runs" — the app already mmaps; the gate is not about loading. (Non-causation) "the PC project proves the phone can do it" — the mechanism is bandwidth-to-working-set ratio, which fails by an order of magnitude. (Counter-case) Desktop llama.cpp does this every day — the material difference is exactly the ratio, and it touches the cause. (Second-order) reviews, thermal complaints, support tickets, a reputation for "the app that hangs". Demands Step Zero before any code.

**Privacy Guardian** accepts that local disk streaming adds no network flow — and draws the red line: the experiment must add zero endpoints (the declared three in `app.json` stay exactly three, asserted by the config test), no diagnostics upload (not even "anonymous tok/s"), and no "try the cloud" fallback anywhere. Storage retention: a 12 GB MoE downloaded to a 15 GB phone would be an anti-feature even if it ran.

**Revisions in-line.** Engineer: C is reduced from a roadmap item to an optional spike — even perfect streaming is bandwidth-bound below 0.1 tok/s on this phone; D is suspended until a model's active set fits the cache. Product: E becomes mainline; B survives only behind Advanced + debug and only with a measured number. Low-RAM User: B gains two separate confirmations (enable the experiment; then accept this model) and a visible ETA. Skeptic: gives B a conditional pass — Step Zero (flash bandwidth + a cold oversize run) or nothing.

**Round 2 — attacks on the fix.**

**Skeptic** (Naqd): the estimated 0.1–0.6 tok/s band is still a product failure for anyone but the owner; who is B for? **Product** revises further: B is not a Library feature and never appears on a curated card; it is an Advanced/debug tool for the owner and testers, with a per-model consent. **Android Engineer**: the loader prefetches the whole file at load, so the load itself may take minutes; the override must cap file size and require free disk after download, and the context cap is not optional because large contexts grow anonymous KV (3B at 32 768 would need ~1.2 GB of KV alone by the fit formula [config unverified]). **Low-RAM User**: the 3B quality failures would make any 3B attempt look like a broken feature — run a fixed prompt set in a fresh process and fix the Arabic-quality/response-language issue first if 3B is ever attempted. **Privacy Guardian**: accepts local diagnostics, rejects any upload; wants delete-model in one tap after the experiment.

**Round 3 — no new objection.** The plan changed twice: C fell out of scope, B lost its user-facing position and gained floors/consents, E became the recommendation. Stop condition met; Maestro speaks.

### 6.4 Maestro's verdict

| Branch | Verdict | Asl (precedent) | 'Illa (cause) | Constraint imposed by objections |
|---|---|---|---|---|
| A — keep the gate | **Survived** (default) | every on-device assistant that fits models | a refused model cannot disappoint | must gain the `SLOW_DISK_BACKED` wording so "won't run" is not a lie |
| B — default-off, double-confirmed experiment | **Survived, downgraded** | llama.cpp mmap + owner's PC experience | page cache gives capacity; the user accepts the cost only after seeing it | Advanced/debug only; hard RAM/disk floors; ctx 512–1024; no auto-enable; measured estimate; instant cancel; owner/testers only |
| C — dense layer streaming in-engine | **Dropped** | AirLLM / LLM in a flash | narrow window per step | even ideal streaming is bandwidth-bound below 0.1 tok/s here [unverified]; months of engine fork for a non-feature |
| D — sparse/MoE offload | **Suspended on test** | PowerInfer-2 / MoE-Infinity | narrow active set | no supported sparse model whose active set fits ≈1 GB; disk headroom; requires a scheduler llama.cpp does not have for this build |
| E — honest ceiling | **Survived** (mainline) | fit engine in [31](31-ux-rework-council.md) | measured capacity is the true product limit | bigger-RAM phones and 2–3-bit quants do the job without a new tier |
| F — cloud | **Dropped** | — | privacy promise | not debated further |

**Ruling for the owner, in five lines.**

1. This is a permission problem, not a capability problem: the engine already maps oversized files; the fit gate is refusing a slow model, not an impossible one.
2. Dense 1.5B+ on the 4 GB phone becomes flash-bound: ≈0.1–0.6 tok/s (1.5B) and ≈0.02–0.1 tok/s (3B) by arithmetic — no phone measurement exists yet, so these are estimates, not claims.
3. Real layer/expert streaming is custom-engine work whose only fast form needs sparse models and a narrow active set; this engine and this catalogue do not provide it, and cloud is not on the table.
4. Ship at most B: default-off, two confirmations, Advanced/debug only, 1.5B-class, context 512–1024, hard RAM/disk floors, estimated tok/s + heat/battery/time warning, never recommended, never auto-enabled.
5. The mainline answer is E: bigger-RAM phones where 3B–8B simply fit, quantization ladder, and measured speed surfaced honestly; "big model on a small phone" never becomes marketing.

**Step Zero — the cheapest tests that could kill this** (run before any engine or fit-gate code):

| Test | Cost | Pass | Fail → decision |
|---|---|---|---|
| **T1 phone flash ceiling**: after a reboot, read a freshly pushed ≥1 GB file once over adb (`dd if=... of=/dev/null bs=1M`) and compute MB/s; repeat warm to confirm caching | 30 min | ≥ 80 MB/s cold | < 80 MB/s → dense 3B/7B are dead (≤0.04 tok/s); keep only the doc, no MVP |
| **T2 low-RAM emulator shakedown**: 3 GB AVD, 1.5B at ctx 512 through the B policy, measure decode, RSS, LMK, load time | 1 h | loads, answers, cancels, no kill | thrash/LMK without a usable band → stop |
| **T3 phone oversize run** (after T1 + T2 + a debug arm64 build): 1.5B, 20 tokens, measure decode tok/s, load time, battery delta, thermal status; 3B once for the record | 2 h | ≥ 0.2 tok/s and 20 tokens ≤ 2 min | < 0.1 tok/s → do not ship B even as an experiment; publish the numbers |

**The solution** is §7 exactly as written, gated on T3.

**Continue/kill metrics** (decided on the phone, not the emulator): median decode ≥ 0.2 tok/s for the 1.5B-class; 20 tokens in ≤ 2 min; battery ≤ 10 % per 20-token answer; no LMK kill in 5/5 runs; cancel within 2 s; no thermal status above MODERATE during the run. Any failure kills B and leaves the gate untouched; the record of the measured number is the deliverable either way.

**Risks and mitigations.** Privacy: zero new endpoints and local-only diagnostics (Privacy Guardian's surviving objection — asserted by the config test). Low-RAM user: two consents + ETA + cancel (their surviving objection). Skeptic: no marketing, debug-only, data-driven kill (their surviving objection). Engineer: debug arm64 build flag, storage headroom, model deletion, and a fixed-prompt quality baseline before any 3B test.

**First action (today):** record this document; open T1 and T2 as measurement tasks; do not touch the engine or the fit gate until T1/T3 exist. If the answer must be "no", the measured number is the honest deliverable.

## 7. Recommended MVP (only if Step Zero passes)

### 7.1 What it is

A **default-off experimental override** in the fit gate — an explicit, labelled permission to attempt a model the gate would otherwise refuse, not a new engine and not a speed feature. The engine already mmaps; the override changes what the app is allowed to do, and what it tells the user.

| Element | Specification |
|---|---|
| Switch | `engine.experimentalOversize` in the config asset, default **false**; visible only in Advanced with the warning text |
| Gate rules | Applies only when: disk free ≥ 1.1 × file **and** ≥ 2 GB remains after download; usable RAM ≥ 768 MB floor; file ≤ min(4 GB, 3 × usable RAM); context ∈ {512, 1024}; architecture supported; `isLowRamDevice` stays a hard block; model fully downloaded and verified |
| Two confirmations | (1) enabling the experiment globally; (2) per-model consent that names the file, the estimated band and the consequences; both revocable; neither is remembered across a different model |
| Never | Auto-enabled, recommended, preselected, on a curated card, in onboarding, in release builds before T3 |
| Context | Forced to 512 by default, 1024 maximum; KV recomputed with the fit formula; the user sees the number |
| Estimate shown | tok/s band from the measured table (labelled تقديري), expected time for a 20-token answer, "the phone will get warm and the battery will drain", "keep the screen on", "cancel stops immediately" |
| During generation | A persistent slow-mode banner with elapsed time and stop; the existing stop path must work in < 2 s |
| After | One local diagnostics record (model, ctx, tok/s, battery %, thermal status); no upload, no telemetry |
| Copy | AR + EN strings in `values/` and `values-en/`; vocabulary per [30](30-arabic-terminology.md); e.g. AR «تشغيل بطيء من التخزين (تجريبي)» / EN "Slow disk-backed run (experimental)"; the word "big model" does not appear anywhere user-visible |

### 7.2 Policy as pure JVM (test-first)

`OversizeAllowance` (in `core/models`) — no Compose, no Android types: takes file bytes, device capacity, free disk, architecture and requested context; returns `ALLOW_WITH_WARNING` or `BLOCK` with the same stable reason codes the fit gate already uses plus `OVERSIZE_DISK_FLOOR`, `OVERSIZE_RAM_FLOOR`, `OVERSIZE_CONTEXT_CAP`, `OVERSIZE_SIZE_CAP`, `OVERSIZE_EXPERIMENT_OFF`; thresholds are config/catalogue data, not Kotlin constants. Default behaviour must be byte-identical to today's verdict.

### 7.3 Acceptance tests

- JVM: flag off ⇒ the existing verdict for 1.5B/3B is unchanged (WONT_RUN, same reasons) — regression against the current `ModelFitTest`; flag on + floors met ⇒ allowed with warning; each floor/cap reason has a failing case; low-RAM device always blocked; unsupported architecture always blocked; recommended-model selection never returns an override-only model; no new endpoint in `app.json` (the exact-count config test stays green); every new string exists in AR and EN.
- Emulator: 1.5B with the flag off is blocked, with it on requires both confirmations, loads at 512, answers, cancels; the banner shows elapsed/stop.
- Phone (debug arm64 build, `-PsirrakArm64Debug=true`): T3 numbers recorded in the doc; delete-model works; no LMK; battery/thermal recorded.

### 7.4 Non-goals

No layer streaming, no expert cache, no new native code, no context above 1024, no quality claim, no source beyond existing GGUF paths, no change to the default gate.

## 8. What NOT to do

- **No cloud offload, ever, not even as an option.** The privacy promise is three user-initiated acquisition endpoints, no inference traffic, an offline switch and no telemetry (`app.json`; PRIVACY). A network inference path would be a different product.
- **No marketing of "big models on a small phone".** Not in README, store copy, site, screenshots or About; the feature's own banner says "experimental and slow" or it does not ship.
- **No silent or automatic override**, no "smart" upgrade of the gate, no changing the selected/recommended model.
- **No engine fork for dense streaming in this milestone**, no new inference engine, no `mlock` (forcing pages resident invites the LMK), no direct-I/O mode (it removes the page cache, which is the only thing making oversize possible at all).
- **No "RAM expansion"/zram advertising** — it is not an inference feature.
- **No invented numbers**: no desktop benchmark copied onto the phone, no literature speedup repeated as ours, no [unverified] figure repeated without the label.
- **No 3B claims until its quality is measured under a fixed prompt set in a fresh process** (the observed failures were prompt-sensitive, not proven engine defects).
- **No touching the signed release path**: this experiment lives in debug/pre-release until it has phone evidence.

## 9. Open questions for the owner, signing and debug-build implications

### 9.1 Questions (recommended answers)

1. **Which goal matters?** "Wait 10+ minutes per answer on the 4 GB phone" vs "bigger models on an 8–12 GB phone where they simply fit". Recommendation: the second is the real product (option E); the first only as a labelled experiment.
2. **Who gets the experiment?** Owner + testers in a debug/pre-release build, or all users behind Advanced? Recommendation: debug/pre-release first; shipping it in a signed release requires T3 numbers and a new consent screen review.
3. **How much model may the experiment put on disk?** Recommendation: ≤ 4 GB and ≥ 2 GB left free; that excludes 20B-class MoEs on this device by policy, not by accident.
4. **Which model class?** Recommendation: 1.5B-class only (the only band the arithmetic leaves plausible); 3B measured once for the record, not shipped.
5. **Fix the small-model Arabic quality/response-language issue first?** Recommendation: yes — the audit's P0-8 and this capture pass both show the obtainable models can answer in the wrong language or badly on some prompts; a fixed prompt set in a fresh process is the baseline before any 3B conclusion.
6. **When is the decision made?** At the signed-phone smoke, from T1/T3, not from this document.

### 9.2 Signing and debug-build implications

- The rc2 keystore is still pending ([27](27-sirrak-launch-checklist.md) §P3.11); the 1.1.0 candidate is debug-signed. A debug/experimental build **cannot install over the signed rc2 build** — testers must export conversations and uninstall first, and any shared APK must be labelled DEBUG / not latest.
- Debug builds target x86_64 only unless `-PsirrakArm64Debug=true`; the phone experiment therefore needs that flag, and the arm64 `libsirrak_llm.so` must be verified inside the APK before installing ([32](32-ux-rework-build-record.md) lesson 11).
- No store copy, site claim or README line changes for this experiment; the privacy policy needs no change (no new endpoint), and the exact endpoint count in the config test must stay 3.
- If the answer is "not now", that is a valid outcome: the measured Step Zero number and this document are the deliverable, and the gate stays honest.

## Sources

- Repository: [27](27-sirrak-launch-checklist.md) §P3.11 (owner follow-up and the emulator findings), [31](31-ux-rework-council.md) §0.6 (measured phone/emulator capacity, fit arithmetic, memory model), [32](32-ux-rework-build-record.md) (1.1.0 record and lessons), [33](33-ux-audit-1.1.0.md) and `docs/audit-1.1.0/` (cold/warm prefill, `dumpsys` mmap evidence), [07](07-test-environment-emulator.md) (emulator limits), [14](14-sirrak-m2-engine.md), [19](19-sirrak-m5-model-control.md), [22](22-sirrak-m8-document-rag.md), [23](23-sirrak-m9-vision.md), [25](25-sirrak-m11-model-manager.md), [29](29-small-models-council.md) (measured tok/s).
- Code: `core/models/src/main/java/app/sirrak/core/models/ModelFit.kt`, `engine/llamacpp/src/main/java/app/sirrak/engine/llamacpp/LlamaCppEngine.kt`, `engine/llamacpp/src/main/cpp/llama_jni.cpp`, `engine/llamacpp/src/main/cpp/CMakeLists.txt`, `app/src/main/assets/catalog/models_catalogue.json`, `app/src/main/assets/config/app.json`, `LibraryScreen.kt`, `HfBrowseLogic.kt`.
- Pinned engine: `third_party/llama.cpp/include/llama.h` (load/lazy modes, MoE cache params), `src/llama-mmap.cpp` (MAP_POPULATE, prefetch), `src/llama-model-loader.cpp` (lazy_read, whole-file prefetch), `src/llama-model.cpp` (init_mappings, defaults), `src/models/{deepseek41,gemma4,qwen4exp}.cpp` (TENSOR_READ_LAZY), `common/arg.cpp` (-ot, --n-cpu-moe).
- Literature identifiers (desk knowledge, not fetched live — see method note): arXiv:2312.11514 (LLM in a flash), arXiv:2303.06865 (FlexGen), arXiv:2207.00032 (Zero-Inference), arXiv:2312.12456 (PowerInfer), arXiv:2406.06282 (PowerInfer-2), arXiv:2401.14361 (MoE-Infinity). AirLLM and MNN-LLM are named as projects only, without a URL claim.
- Android behaviour: `ActivityManager.MemoryInfo`, `ActivityManager.isLowRamDevice`, `StatFs.getAvailableBytes` and the low-memory-killer/PSI model are cited at the level used in [31](31-ux-rework-council.md) §0.6 and sources; zram presence on the Redmi was not checked.

## Uncertainty register (every [unverified] claim, and what would verify it)

| # | Claim | Verification |
|---|---|---|
| 1 | Literature results and figures (LLM in a flash ~2× DRAM/window speedups; FlexGen/ZeRO-Inference throughput; PowerInfer/PowerInfer-2 speedups; MoE-Infinity gains; AirLLM behaviour) | fetch the arXiv papers / project docs when the search tool is available |
| 2 | Phone flash page-in band 80–150 MB/s; Redmi storage is eMMC-class | T1: cold dd read of a ≥1 GB file on the phone |
| 3 | Any phone tok/s band in §4.2 | T3: run the model on the phone |
| 4 | Battery/thermal estimates (3 W, 15.4 Wh, 4 000 mAh) | T3: battery delta + thermal status + skin temperature |
| 5 | MAP_POPULATE best-effort behaviour and reclaim interleaving on this vendor kernel | profile the load on the phone (page-fault rate, load time) |
| 6 | mmap is active on the phone as it is on the emulator | `dumpsys meminfo` on the phone during a load |
| 7 | zram/swap presence and vendor policy on the Redmi | `adb shell cat /proc/meminfo` / vendor config |
| 8 | The owner's "free token" PC project details | ask the owner for the tool name/behaviour; no artifact exists in this repo |
| 9 | The 3B configuration used only for the KV illustration (36 layers, 2 KV heads, head dim 128) | GGUF header probe |
| 10 | gpt-oss-20b size (~12 GB) and ~3.6B active parameters | Hub tree API / GGUF metadata when probed |
| 11 | "8–12 GB phones run 3B–8B well" | fit engine on such a device + T3-style measurement |
| 12 | Whether this vendor's lmkd kills or only reclaims under sustained page-cache pressure | T3 with stress + logcat/`dumpsys` |
| 13 | PowerInfer-2's exact models/devices/RAM | paper |
| 14 | The in-tree engine snapshot's exact upstream vintage beyond "1.0.0-dev" | submodule/commit record; the docs pin "v1.0.0" but the tree contains newer experimental features |
| 15 | Other mobile stacks (MNN-LLM, PocketPal, llama.rn) have no disk-streaming/oversize mode (absence claim) | source/docs review |
| 16 | 2–3-bit quantization quality on Arabic | a benchmark, not an assumption; not part of this MVP |
