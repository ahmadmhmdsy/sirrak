# Support

Sirrak is maintained by one person (@alshahia) in the open. Support is best effort: there is no
support contract, no paid tier and no bug bounty, and no answer is guaranteed.

## Where to ask

| You have | Where it goes |
| --- | --- |
| A bug in the app | [Issues](https://github.com/alshahia/sirrak/issues/new?template=bug_report.yml) — the bug form asks for everything needed to reproduce it |
| A feature request | [Issues](https://github.com/alshahia/sirrak/issues/new?template=feature_request.yml) — the feature form |
| A question about the build, the architecture or a milestone claim | [docs/](docs/README.md) first; if the answer is not there, open an Issue |
| A security vulnerability | the [private advisory form](https://github.com/alshahia/sirrak/security/advisories/new) — never a public issue, see [SECURITY.md](SECURITY.md) |
| A privacy question | [PRIVACY.md](PRIVACY.md), then the in-app **Settings → Privacy & data** screen; if both miss it, open an Issue |

GitHub Discussions is not the support channel and may be disabled on the repository. If you are unsure
where something belongs, open an Issue: it will be answered there or pointed at the right place.

## What build are you running?

Store availability is not claimed: F-Droid submission is planned and not done, and Play closed testing
has not happened. Builds are published on [Releases](https://github.com/alshahia/sirrak/releases) or built
from source. When you report anything, include:

- **Version, versionCode and flavour** from **Settings → About** (for example `1.0.0-rc1`, versionCode 12,
  `libre`). The flavour matters: `libre` and `play` are built from one source but are not the same artifact.
- **Device and Android version**, and the ABI — `arm64-v8a` on a phone, `x86_64` on an emulator.
- **Steps** from a known state: fresh launch, empty library, which model is loaded, which document is in scope.
- **Expected and actual behaviour**, with a screenshot for anything visual.
- **Logs** where they exist: `adb logcat -s SirrakLlama` for chat, dictation and model loading. Remove prompts,
  document text and tokens before posting.

Reports that omit the version and the steps usually cannot be reproduced, and will be closed with a
request for them rather than guessed at.

## What to expect

- Bug reports and pull requests are read in the order they arrive; a first response usually takes a few
  days, sometimes longer.
- A fix ships when it is tested, not when it is reported. There is no release calendar.
- Security reports follow [SECURITY.md](SECURITY.md): acknowledgement within 7 days, status update within
  30 days. Security fixes land on the newest release only.
- Requests for telemetry, accounts, ads, cloud processing or a hosted service will be declined: they are
  outside what this app is.

## What is not offered

- Paid support, consulting or priority handling.
- Bug bounties.
- Patches for older builds.
- Help with third-party model weights, sherpa-onnx, llama.cpp or Android itself — report those upstream.
