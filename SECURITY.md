# Sirrak security policy

Last updated: 2026-11-23 · Applies to 1.0.0-rc1 (versionCode 12, released 2026-11-23).

Published at <https://alshahia.github.io/sirrak/security/> — Arabic:
<https://alshahia.github.io/sirrak/ar/security/>. The app opens this page from
**Settings → Privacy & data → Security disclosure**.

Sirrak runs a language model on the phone. There is no server, no account and no telemetry endpoint, so the
attack surface is the device itself: app-private data, document parsing, the native inference engines, the
model download path, and the signed artifacts people install. This page says how to report a problem in any
of those and what to expect afterwards.

## Supported versions

| Version | versionCode | Supported |
| --- | --- | --- |
| 1.0.0-rc1 | 12 | Yes — current release; security fixes land here |
| earlier builds, other release candidates, builds from arbitrary commits | — | No |

Sirrak is pre-1.0: fixes land on the newest release only, and older builds are not patched. If you are not
on 1.0.0-rc1, reproduce the issue there before reporting it.

## Reporting a vulnerability

Please do not open a public issue for a security problem. Use GitHub's private vulnerability reporting on
this repository:

**<https://github.com/alshahia/sirrak/security/advisories/new>**

The report stays private between you and the maintainer until an advisory is published.

## What to include

- The app version from **Settings → About** — 1.0.0-rc1 is versionCode 12 — and the distribution channel
  shown beside it (`libre` or `play`).
- Device or emulator model, Android version and ABI (`arm64-v8a` on a phone, `x86_64` on an emulator).
- Steps from a known state: fresh launch or existing data, which model is loaded, which document is in
  scope, whether offline-only was on.
- The affected component: chat, library/RAG, dictation, model manager, settings and privacy surfaces, the
  website, or a release artifact.
- The impact: what an attacker gains, and what they need first — a malicious document, a hostile network, a
  crafted model file.
- A minimal proof of concept: the smallest file, command or recording that reproduces the issue.
- Relevant logs with secrets removed. Delete prompts, document text, tokens and personal file paths before
  posting.

## What to expect

Sirrak is maintained by one person in the open. Reports are handled on a best-effort basis; there is no
support contract, no paid tier and no service-level agreement.

- **Acknowledgement** within 7 days.
- **Status update** within 30 days, and again when a fix ships.
- **No bug bounty.** There is no payment or reward for reports.
- **Fix policy.** Fixes land on the newest release only; older builds are not patched.

## Coordinated disclosure

We ask for **90 days from acknowledgement, or until a fix is published, whichever comes first**, before you
disclose publicly. We will agree a date with you and keep you informed; if a fix takes longer, we will say so
and agree a new date rather than let the report go silent. Once a fix is available we publish a short
advisory, and we credit reporters who ask to be credited.

## Safe harbour

The project will not pursue or support legal action against you for security research that follows this
policy. That covers testing on your own devices and accounts, avoiding privacy violations, data destruction
and disruption, not exfiltrating other people's data, and giving us a reasonable window to fix the issue
before disclosure. It does not authorise testing third-party services — Hugging Face, GitHub, your carrier
or your device vendor — whose own policies apply. If you are unsure whether something is in scope, ask
through the advisory form first.

## Scope

In scope:

- **App code** — chat, library/RAG, dictation, model manager, settings and privacy surfaces, and the way data
  is stored on the device.
- **Native engine integration** — the JNI bridge, GGUF loading, projector pairing and memory handling around
  llama.cpp and sherpa-onnx.
- **Document handling** — PDF, DOCX, EPUB, Markdown and text extraction, chunking, embedding and retrieval,
  including hostile or malformed files.
- **Model manager** — download, resume, redirect handling, SHA-256 and GGUF verification, import and
  installation.
- **Website** — the static pages and policy content generated from this repository.
- **Release artifacts** — the APK and AAB, signing, `sbom.cdx.json` and the licence registry.

Out of scope:

- The content or behaviour of third-party model weights, and their licences — report those to the model's
  publisher.
- Hugging Face infrastructure, its CDN, accounts and tokens — report those to Hugging Face.
- Android itself, device firmware, OEM software, and attacks that require a rooted device or physical access
  with an unlocked bootloader.
- Social engineering, phishing and physical access to an unlocked device.
- Upstream llama.cpp, sherpa-onnx or LiteRT-LM code — report those upstream. Bugs in how Sirrak integrates
  or packages them are in scope.

## Security-relevant facts about 1.0.0-rc1

**Signing.** Release APKs are signed with the project certificate, SHA-256:

```
4537ECEB9952AC9020EFFCEB8B50CB10AEB795772792D632CFC3D0B9F7106806
```

Signing is **v2 only**; v3/v4 key rotation is not configured. Verify a download before installing it:

```powershell
apksigner verify --print-certs app-libre-release.apk
```

The fingerprint must match the value above. `scripts/release-check.ps1` runs this check, plus
`jarsigner -verify` on the AAB, for every release build. A package signed by a different certificate is not
a Sirrak release. Play App Signing is a Play Console step that has not been taken, because the app is not
distributed through Google Play.

**SBOM.** `sbom.cdx.json` is a CycloneDX 1.5 inventory of the 23 licence-registry entries — the app plus 22
top-level components. It is a reviewed component list, not the full transitive Maven graph, and it is
drift-tested against the registry.

**Native engines.** llama.cpp is built from the vendored source in `third_party/llama.cpp`. The sherpa-onnx
runtime is a pinned prebuilt AAR, `engine/whisper/libs/sherpa-onnx-2.0.1.aar` (Apache-2.0), vendored so the
build needs no network. It is not rebuilt from source here, which is the open item for an F-Droid recipe;
the caveat is tracked in [docs/27](docs/27-sirrak-launch-checklist.md).

**Download verification.** Catalogue downloads are checked against the catalogue SHA-256 when the entry
publishes one, and every model must pass a GGUF header check before it becomes visible. A GGUF you import
yourself is checked for the GGUF header only, so treat the source you chose as trusted.

**TLS and dependencies.** Downloads use HTTPS with the platform trust store; there is no certificate pinning
and no proxy configuration. Android lint keeps 33 accepted warnings visible in every report, including three
transitive Bouncy Castle `TrustAllX509TrustManager` warnings pulled in by PDFBox-Android, which is not on
the network path. Pinned dependency and AGP versions are upgraded deliberately and tracked rather than
patched silently; a dependency advisory that affects a reachable code path is treated as a security report.

**Build hardening.** Release builds are minified with R8. Cleartext HTTP exists only in the debug manifest,
for loopback device tests, and is absent from release builds; app data stays in app-private storage.

## References

- Hosted policy: <https://alshahia.github.io/sirrak/security/> (Arabic:
  <https://alshahia.github.io/sirrak/ar/security/>)
- [PRIVACY.md](PRIVACY.md) — what is stored and what leaves the device.
- [docs/27-sirrak-launch-checklist.md](docs/27-sirrak-launch-checklist.md) — release gates and the open
  F-Droid item.
- [sbom.cdx.json](sbom.cdx.json) — component inventory.
- [scripts/release-check.ps1](scripts/release-check.ps1) — the signing and release gate.
