# Sirrak docs — product and build records

These are the public build records for Sirrak `1.1.0` (the UX rework that followed the first hands-on
review of `1.0.0-rc2`): what was built, how it was verified, what was rejected and which limits are still
open. They are shipped in the repository because a launch claim is only worth what its evidence is worth.

| Doc | Records |
|---|---|
| [04-product-spec.md](04-product-spec.md) | product scope, surfaces, user promises |
| [05-architecture.md](05-architecture.md) | module map, Cordis-shaped composition, seams |
| [06-roadmap.md](06-roadmap.md) | milestone history and the evidence for each gate |
| [07-test-environment-emulator.md](07-test-environment-emulator.md) | emulator setup + device-test runbook |
| [13-sirrak-frontend.md](13-sirrak-frontend.md) | M1 frontend: design system, plugins, slots, RTL |
| [14-sirrak-m2-engine.md](14-sirrak-m2-engine.md) | M2 llama.cpp engine |
| [15-sirrak-m3-persistence.md](15-sirrak-m3-persistence.md) | M3.1 persistence + restore |
| [16-sirrak-m3-chat-actions.md](16-sirrak-m3-chat-actions.md) | M3.2 copy/regenerate/new chat |
| [17-customization-council.md](17-customization-council.md) | theme packs and model-control design council |
| [18-sirrak-m4-theme-packs.md](18-sirrak-m4-theme-packs.md) | M4 five curated packs + contrast gate |
| [19-sirrak-m5-model-control.md](19-sirrak-m5-model-control.md) | M5 sampler/template/prompt layers |
| [20-sirrak-m6-chat-library.md](20-sirrak-m6-chat-library.md) | M6 conversation library, Markdown, font scale |
| [21-sirrak-m7-attachments.md](21-sirrak-m7-attachments.md) | M7 attachment seam and composer gate |
| [22-sirrak-m8-document-rag.md](22-sirrak-m8-document-rag.md) | M8 document RAG, citations, prefill fix |
| [23-sirrak-m9-vision.md](23-sirrak-m9-vision.md) | M9 on-device vision path and honest limits |
| [24-sirrak-m10-dictation.md](24-sirrak-m10-dictation.md) | M10 sherpa-onnx Whisper dictation, WER/RTF gate |
| [25-sirrak-m11-model-manager.md](25-sirrak-m11-model-manager.md) | M11 onboarding, downloads, model manager |
| [26-sirrak-p3-polish-launch.md](26-sirrak-p3-polish-launch.md) | P3 privacy, a11y, signing, SBOM, graphics, validators |
| [27-sirrak-launch-checklist.md](27-sirrak-launch-checklist.md) | ordered pre-publish checklist and honest gaps |
| [28-public-launch-council.md](28-public-launch-council.md) | this repository/site/SEO launch council and decisions |
| [29-small-models-council.md](29-small-models-council.md) | compact model tiers, supported formats, verified candidates, safetensors→GGUF conversion |
| [30-arabic-terminology.md](30-arabic-terminology.md) | Arabic terminology council, dictionary evidence, and the glossary that governs model/prompt/projector copy |
| [31-ux-rework-council.md](31-ux-rework-council.md) | first hands-on UX review: model chip, library (models/media), settings IA, Hugging Face search, download manager; Qiyas council decisions and phased plan |
| [32-ux-rework-build-record.md](32-ux-rework-build-record.md) | P1–P6 build record of the rework: four-state model chip, three-segment Library with a durable queue, six-door Settings, in-app Hugging Face probe/acquisition, Media V1 and the gates |
| [33-ux-audit-1.1.0.md](33-ux-audit-1.1.0.md) | round-2 UX audit of 1.1.0: state of the app, 31 deduplicated P0/P1 defects (8 P0) ranked, the council's ten fixes in order, and the evidence pack — with the eight area reports and the adversarial verdict in [audit-1.1.0/](audit-1.1.0/README.md) |
| [34-sirrak-oversize-model-offload.md](34-sirrak-oversize-model-offload.md) | the founder's "run a model bigger than the phone" question: what Android permits (unified RAM, mmap page cache, LMK/thermal/UFS), the offload literature and projects, a qiyas-council ruling, and a default-off experimental MVP proposal — research only, nothing shipped |

## What is not published here

The private working record (research notes, clearance memo, naming/trademark work and raw session logs) stays
out of the repository on purpose: publishing evidence is useful, publishing deliberation invites stale claims.
The published records are copied from that working record; [28](28-public-launch-council.md) documents the
boundary and the sync rule.
