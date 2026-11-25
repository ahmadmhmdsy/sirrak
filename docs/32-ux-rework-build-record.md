# UX rework build record (P1–P6)

Status: **candidate complete** — P1–P5 implemented, P6 gates green except the items that need a keystore, a
test phone or a push (signed release, physical-phone smoke, CI). Version **1.1.0** / versionCode 14; the
complete gate and artifact record is [docs/27](27-sirrak-launch-checklist.md) §P3.10.
Provenance: [docs/31](31-ux-rework-council.md) (review, council transcript, verdict and the six founder decisions)
and [docs/mockups/31-ux-rework.html](mockups/31-ux-rework.html). Target release **1.1.0** (versionCode 14).

## P1 — the model chip tells the truth and opens the door

The chat header chip used to conflate "no model" with "load failed" (defect G1) and gave no route to get a
model. Now `EngineModelChip` (`app/.../Panels.kt`) renders one of four honest states — Ready, Loading,
Failed, Unloaded — and every state opens a sheet that acts:

| State | Chip label | Sheet action |
|---|---|---|
| Ready | model label | model passport (facts, context, licence), per-model page, unload |
| Loading | "loading" | current load state |
| Failed | "could not load" | retry the selected model, open Library |
| Unloaded + selected | "model not loaded" | load it, open Library |
| Unloaded + none | "no model" | **open Library → models** (the download CTA) |

No state prints two contradictory sentences: the four branches are exhaustive and disjoint.

## P2 — Library owns device content, with a durable download queue

### Library: three segments
`app/.../LibraryScreen.kt` is one destination with the approved three segments:
**النماذج** (on-device models, catalogue cards with a computed fit verdict, GGUF import, HF doorway),
**التنزيلات** (the persisted queue + installed list) and **الوسائط** (P5). The fake
`content/models.json` reference cards are gone; every card is built from `ModelStore` and the catalogue,
and every card states an honest fit verdict for this device at this moment (`ModelFitEngine`).

### Durable queue (defects G3/G5)
Requested downloads are rows now, persisted across process death, at most one running:

| Layer | File | Responsibility |
|---|---|---|
| Queue state | `core/models/.../DownloadQueue.kt` | pure state: one JSON snapshot, one ACTIVE max, every mutation written through; repairs impossible states |
| Atomic store | `app/.../downloads/QueueFileStore.kt` | write-temp-then-rename; null on first launch; I/O errors surface |
| Bridge | `app/.../downloads/DownloadBridge.kt` | drives the single `ModelDownloader`, maps its phases onto durable rows, pumps the next QUEUED row only when unmetered, never auto-resumes after process death (ACTIVE → PAUSED on load) |
| Host wiring | `app/.../AppComposition.kt` | provides `DownloadBridge`; private `ModelDownloaderPort` adapter reads the same `NetworkPolicy` the downloader enforces |
| Adoption | `app/.../ModelAdoptionCoordinator.kt` | staging → store import, then `markInstalled`/`markFailed`; the notification's cancel action calls the bridge, so a row can never stay ACTIVE |

Consequences the review asked for: a second request for the same model is a visible duplicate row, not a
silent drop; leaving the app loses no intent; pause/resume/retry/cancel/remove operate on the durable row;
metered is a question the UI asks before `start(..., allowMetered = true)`, never a silent skip.

Honest limits: the queue rename is atomic but not fsync'd (power loss can lose the newest progress tick,
never a torn list); synthetic Hugging Face rows verify size only (no checksum unless HF publishes one);
the queue never probes reachability — an unreachable network surfaces as the transfer's own failure code.

## P4 — Settings: six doors with live summaries

The single long scroll became one root list of six doors in fixed order; each door shows a live summary of
its current state and opens a section page (language expands inline, as approved):

| Order | Door | Live summary (example) | Content |
|---|---|---|---|
| 0 | المظهر والقراءة | "داكن · حجم النص 115%" | mode, font scale, theme packs, preview |
| 5 | اللغة | "العربية" | inline Arabic/English choice + note |
| 10 | الخصوصية والشبكة | "دون اتصال فقط / متصل" | offline switch, network audit, reports |
| 20 | الصوت | "إملاء تلقائي · Qwen2.5 0.5B" / "لا نموذج" | dictation mode, Whisper model import |
| 30 | متقدم | embedding model name or status | embedding model, index maintenance |
| 40 | عن التطبيق | version | licences, credits, build facts |

Runtime: `core/runtime/.../SettingsDoors.kt` (`SettingsDoor`, `SettingsDoorRegistry`),
`AppContext.settingsDoors`, `PluginScope.door(...)`; `core/design/.../Page.kt` adds
`SettingsPageScaffold`; pages are the existing `PageRegistry` (`PageIds.SETTINGS_*`). The old
`Slots.SettingsSections` contributors are gone, so Settings order is plugin order, not slot order — the
defect G8 tie is structurally impossible now. Model management left Settings entirely (it lives in Library).

## P3 — Hugging Face acquisition

The approved defect: a model id could only be typed blind, with no search, no file list, no size and no
fit check before committing hundreds of megabytes (G10). Now the whole path is in the app:

| Step | Where | Behaviour |
|---|---|---|
| Search | `feature/models/.../HfBrowseScreens.kt` + `HfBrowseSession.kt` | debounced public index search, cached results, cursor page, explicit offline/blocked/failure/empty states each with its fixing action |
| Repository | same | file list, GGUF-only rows, honest "size unknown" when the hub omits it, one-entry cache so repo → Downloads → back does not refetch |
| Probe | `core/models/.../GgufProbe.kt` | 12 MB capped ranged GET of the GGUF header, manual resolver→CDN redirect with Range preserved, token dropped when the redirect leaves `huggingface.co`, 206 and 200 both honest, typed failure codes |
| Verdict | `ModelFitEngine` maths over the probed architecture | runs / tight (confirm dialog) / won't run (blocked with its reason) / **not verified** until probed — never "runs" from a guess; estimates carry **تقديري** |
| Download | `DownloadBridge.enqueue` + `start` | no second download path: a synthetic catalogue entry goes through the same durable queue as curated models |

Metered networks ask a real question on both the probe and the download (Yes keeps the row waiting for
Wi-Fi, No means no); nothing silently skips. A gated repository's token lives in memory only, is never
written to disk/logs/reports, and the search/file-list/probe calls are audited as `hf_search` and
`hf_probe` — both now declared in `app.json`, explained by the privacy policy and rendered from
`PrivacyFormat.kt`.

Honest limits: search rows expose only what the public API returns (no params/quant/size badge in the
list), the probe is skippable so an unprobed file stays unverified, and there is no device/network test
here — the host tests use fakes; the live path is exercised in the P6 emulator pass.

## P5 — Media V1

Defect G9 / decision 4: documents were the only content the agent could see, and a file's retention was
implicit. V1 ships one **الوسائط** segment with a deliberate, visible contract:

| Kind | Stored as | Agent access | Notes |
|---|---|---|---|
| documents | media copy + existing RAG index | per-item switch + policy default | indexed text only; cited answers keep working |
| images | media record | never indexed (view-only in V1) | access switch disabled with the reason on screen |
| audio | one audio record + on-device transcript | transcript indexed only when kept and access granted | Whisper runs on the phone; the recording is never uploaded |

The decision sheet offers exactly two consequence-labelled choices — **keep** (stays until you delete it,
can be indexed) or **once** (never indexed, deleted when its session ends) — plus the policy default.
Audio needs a Whisper model; a missing one is a typed failure that names the exact acquisition path
(Settings → الصوت → import encoder/decoder/tokens), not a dead button.

Storage is `filesDir/media/{index.json, files/}`, capped at 50 MB and 10 minutes of audio, with an
authoritative index written through an atomic store; corrupt index entries are pruned, files whose owner
is gone are pruned, and a KEEP item is never deleted by a purge (a defect the independent harness caught:
the once-only expiry originally deleted KEEP items too).

Honest limits in this build: media is not yet wired into conversation lifecycle, so a once-only item is
deleted at app close/restart rather than "with the conversation"; images are view-only and never indexed;
there is no OCR; indexed documents are stored twice (shelf copy + retrieval copy) as a V1 trade-off.

## P6 — version, gates, evidence

`versionCode 14` / `versionName 1.1.0` in `app/build.gradle.kts`; About, NOTICE, SBOM, store copy, both
READMEs, the site and the roadmap amendment read 1.1.0. Gates were run on the built candidate, not on a
hoped-for later one:

| Gate | Result |
|---|---|
| JVM suite | **744 tests, 0 failures, 0 errors** / 75 suites / 10 module result folders (`:app` 253) |
| Arabic eval | **24/24** |
| Android lint | **0 errors, 39 reviewed warnings** (30 dependency pins + 6 AGP + 3 transitive TLS) |
| Config / drift | endpoints `{model_download, hf_search, hf_probe}`; NOTICE + SBOM 1.1.0 green (23 registry entries, 22 CycloneDX components) |
| Store validator | **79/79** |
| Site | build clean, **225/225** |
| UI regression | baseline refreshed on this build, **4/4 at distance 0** |
| Candidate APK | debug-signed universal 223,217,333 B / `4CD65A60…539A`, 7 native libs per ABI |

The candidate artifact is debug-signed because the tree has no release keystore; a signed 1.1.0 APK/AAB is
the one gate that cannot be produced here, and the rc2 digests stay recorded for the signing host.

Screenshot provenance is split on purpose: six shots come from `2C6F…32C7` and two (`05`, `08`) from
`3BDC…192E`, after the privacy footer and the Advanced-door summary were corrected; the shipped artifact
`4CD6…539A` differs from `3BDC…192E` only in two in-app network sentences (first-run privacy slide and the
model-section note) that appear in no listing screenshot. The plain screenshot-regression compare still
passes 4/4 at distance 0 on `4CD6…539A` against the `3BDC…192E` baseline.

### Device evidence (API 36 `Nawa_Phone`, 720×1280)

Eight screenshots were recaptured from installed candidate builds (`store/screenshots/`, split provenance
in its README). Besides the pictures, the capture pass checked the behaviours the review complained about:

- the chip on the empty chat reads «لا يوجد نموذج · غير محمّل», and tapping it opens a sheet whose CTA lands on
  **Library → النماذج** — no dead grey text, no Settings detour;
- the empty chat now fits inside the 218 dp reading area left by the composer: promise, privacy line and the
  first suggestion card are fully visible (card bottom above the document row), with no token-meter row until
  the first turn; the compact branch triggers below 300 dp;
- Library shows its three segments and the models segment carries the catalogue, the GGUF import and the HF
  doorway; a Qwen2.5-0.5B download ran from that UI, was verified on disk (491,400,032 B), adopted as the
  selected model and produced a real background notification;
- the privacy page lists exactly the three declared endpoints and the recorded one-request download, not a
  speculative list;
- About shows 1.1.0 and the six-door Settings root renders its doors with live summaries (English shot, RTL
  layout preserved);
- dictation ran on the staged whisper-tiny model: the recording strip showed the listening state and its
  live timer, and the transcript was appended to the composer without sending it;
- the first-run privacy slide was re-checked on the final artifact in Arabic and English after its network
  sentence was corrected: the new line wraps without clipping, and Next / Back / Skip all work.

### What is intentionally not in this record

- **Physical-phone smoke**: the Redmi Note 7 was never attached to adb in this session; the emulator is the
  only device evidence. The arm64 artifact is ready for that run (Qwen2.5-0.5B ≈ 794 MB is the intended
  model), and docs/27 P3.10 spells out the walk.
- **Signed release**: no keystore here, so no signed 1.1.0 artifact, no `release-check.ps1` pass and no
  release tag. The store/site surfaces are ready but still describe rc2 until that build exists.
- **Push/CI**: the tree is uncommitted; no claim in this record depends on a remote run.

## Implementation lessons

1. A compile that fails because a teammate is mid-write is not a defect; the fix is to coordinate write
   scopes, not to "fix" their file.
2. Runtime modules without compose-foundation must keep composable hooks as `(@Composable () -> Unit)?`
   rather than `@Composable ColumnScope.() -> Unit`; the latter also makes layout constraints travel
   across module boundaries, which the plugin runtime should not own.
3. A persisted queue only removes the silent-drop defect if every entry point goes through it; the
   notification cancel action and the adoption coordinator both had to change, or the row stayed ACTIVE.
4. Ordering matters at the adoption boundary: clear the downloader snapshot **before** `markInstalled`,
   because that call pumps the next transfer.
5. Door summaries compose their text at render time from the real stores; a summary that caches the
   boot snapshot lies after the user changes something (the G8 class of defect).
6. The founder's six decisions were implementable without new strings for monetization, telemetry or
   accounts: the whole rework is UI, state and persistence over existing seams.
7. A retention flag is not a deletion policy: the once-only purge originally expired KEEP items too, which
   the independent JVM harness caught before Gradle did. Tests written against the consequence labels ("kept
   stays, once disappears") found it; tests written against the implementation would not have.
8. A network feature added to the UI is not declared until it is declared three times: `app.json`
   (`privacy.endpoints`), the privacy formatter/strings, and the policy copy. The AppConfig test hard-coded
   `1` endpoint and caught the omission on the next run — keep such counts exact rather than range-y.
9. Version bumps touch generated evidence, not just the manifest: NOTICE and `sbom.cdx.json` are generated
   from the licence registry and must be regenerated; the site's sitemap carries the release date; the
   hosted policy date must equal it; and Play's What's new has a hard 500-character ceiling. These are
   data gates, not "publish later" chores.
10. The phone build is a build flag, not a second codebase: the engine's debug ABI filter now honours
    `-PsirrakArm64Debug=true`, so the exact universal debug artifact that runs on the emulator can be
    installed on a real arm64 phone; the default stays x86_64-only for CI speed.
11. "Universal" must be checked library by library, not assumed from the build type: the first 1.1.0 APK
    had both ABI folders but `arm64-v8a` was missing `libsirrak_llm.so`, because the arm64 debug engine sits
    behind `-PsirrakArm64Debug=true`. `aapt2 dump badging` plus a zip listing proved it before the artifact
    reached the phone; a package that installs is not a package that runs.
12. A scrollable screen can still be a broken screen: at 720x1280 the composer left a 218 dp reading area,
    so the empty state clipped its first suggestion card no matter where it was scrolled. The fix is a
    height-aware compact variant (drop the girih mark, tighten spacing) plus an honest rule that the token
    meter disappears while there is no conversation — the emulator capture verified the card's bottom edge
    above the document row.
13. Candidate evidence is not release evidence: bumping to 1.1.0 with no keystore in the tree makes the
    validated artifact a debug-signed APK. Every surface that says "signed release" (README status, store
    artifact table, docs/27) must say which build is which, or the honesty of the rest of the record is
    borrowed against a claim that cannot be reproduced here.
14. A network-usage sentence is a claim, not a caption, and it drifts across surfaces: the privacy footer,
    the endpoint list, the first-run slide and the model-section note each restated "only model downloads"
    while `app.json` already declared three endpoints. Grep every such sentence (`الإنترنت`, "network",
    "model download") in strings, assets, READMEs, store copy and site content, not only the privacy page.
15. A late copy fix rebuilds the artifact after the screenshots were taken, so provenance must name the
    capture build and the shipped artifact separately: state which hashes produced the images, why the sets
    differ, and re-run the read-only regression compare on the final artifact instead of re-baselining it.
16. Product copy in README/store/site can overstate the UI the code actually ships ("live level meter" for a
    recording strip that only shows a timer). Validate marketing sentences against the composables, not
    against earlier product intent: overclaiming is the same honesty defect as stale privacy copy.

## Founder feedback — what the review did not ask for

- The "downloads" segment needs a **visible queue**, not only the active card: a queued row the user can
  see is what stops the silent second-tap drop.
- The model chip's "no model" state must open the **Library**, not model instructions in Settings; the
  Settings detour was part of the original defect.
- A per-model **context size** control belongs on the model page (already there), not in Settings.
