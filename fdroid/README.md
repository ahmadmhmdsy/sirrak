# F-Droid release notes (P3.5)

The app is F-Droid-compatible by construction: Apache-2.0, no proprietary dependencies, no Google
Play services, no telemetry, no ads, no accounts. The only remote service is Hugging Face for the
model download the user explicitly starts, which is declared as the `NonFreeNet` anti-feature in
`metadata/app.sirrak.yml`.

## What is in this folder

- `metadata/app.sirrak.yml` — a submission-ready metadata file. Copy it into a fork of
  `fdroiddata` as `metadata/app.sirrak.yml`, replace every `TODO-` with the published URL/tag,
  then open a merge request.
- The `libre` product flavour is the F-Droid build: same source as `play`, no Play-only code.

## Release steps

1. Publish the source repository and a signed tag (for example `v1.0.0-rc1`).
2. Fill `SourceCode`, `IssueTracker`, `Changelog`, `Repo` and `commit` in the metadata file.
3. Check that F-Droid can fetch the native engines: llama.cpp, sherpa-onnx and LiteRT-LM are consumed
   as Maven artifacts from public repositories; no NDK build is required from F-Droid's side.
4. Run `fdroid lint app.sirrak` and `fdroid build app.sirrak:12` with the fdroiddata tooling before
   submitting.
5. F-Droid signs the APK with its own key. Keep `keystore.properties` out of the public repo — the
   `libre` build works without it (release stays unsigned in that case).

## Anti-features and why

| Anti-feature | Declared | Why |
| --- | --- | --- |
| NonFreeNet | yes | The optional model download comes from huggingface.co, a non-free network service. The transfer is user-initiated and the app is fully usable offline with a locally imported model. |

No `Tracking`, `Ads`, `NonFreeDep` or `NonFreeAdd` anti-features apply.
