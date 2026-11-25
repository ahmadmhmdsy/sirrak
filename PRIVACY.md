# Sirrak privacy policy

Last updated: 2026-11-25 · Policy version 2 · Applies to 1.1.0 (versionCode 14).

Published at <https://ahmadmhmdsy.github.io/sirrak/privacy/> — Arabic:
<https://ahmadmhmdsy.github.io/sirrak/ar/privacy/>. The app opens this page from
**Settings → Privacy & data → Privacy policy**.

Sirrak (سِرِّك) is a private AI assistant for Android that runs its language model on your phone. There is
no account, no sign-in and no cloud behind it, so there is no server holding your conversations. The app
works fully offline once a model is imported or downloaded. It touches the network only for three things
you start yourself: a model download, a Hugging Face index search, and a capped header probe that estimates
whether a model fits this phone.

## In short

- Chats, documents, media, models and settings stay in app-private storage on your device.
- There is no telemetry, no analytics, no crash reporting, no advertising and no account.
- The three network features each start with a tap: model download, Hugging Face search, capped header
  probe. Offline-only mode blocks all of them before a socket opens.

## What stays on your device

- **Chats** — conversations, prompts, answers, titles, system prompts and per-conversation settings.
- **Documents** — files you add to the Library (PDF, DOCX, EPUB, Markdown and plain text), their extracted
  text, chunks and embedding vectors.
- **Media** — images, audio clips and their on-device Whisper transcripts that you add to the Library's
  Media segment. Audio is transcribed on the phone and the recording is never uploaded. Each item is kept
  or used once according to your choice; a once-only item is never indexed and is deleted when its session
  ends.
- **Models** — GGUF model files you download or import, and any projector file paired with them.
- **Settings** — theme, language, text size, engine tuning, prompts and the offline-only switch.
- **Dictation audio** — held in memory or a temporary file only while a transcription runs, never uploaded;
  the transcript reaches the composer and is saved only if you send it.
- **Network log** — the calls this app actually made on this device: endpoint, host, local time, outcome and
  bytes. It is capped at the 50 newest entries and is never uploaded.
- **AI-output reports** — the reason, an optional note and a short excerpt of the answer, capped at 50
  reports. A report leaves the device only when you tap Share and pick an app in the Android chooser.

"App-private storage" means Android's per-app data directory. Other apps cannot read it on a non-rooted
device, and it is removed when you uninstall Sirrak or clear its app data.

## What leaves your device

Sirrak has three network features, and each starts with your tap: downloading a model, searching the
public Hugging Face model index, and reading a capped byte range of a GGUF header to estimate device fit.

- Every request is addressed to `huggingface.co`, the public model resolver. The same host is shown in
  **Settings → Privacy & data → Network log**.
- A **download** is a `GET` for the model repository and file you chose. It carries a standard user agent
  (`Sirrak/0.1 (Android; on-device model downloader)`) and — only if you typed a token for a gated model —
  an `Authorization: Bearer` header with that token. As with any HTTPS download, the server's redirects for
  the file are followed.
- A **search** sends the words you typed to the public model index. Opening a repository sends its public
  name to read the file list.
- A **header probe** asks for at most 12 MB of the header of the single GGUF file you inspect, using ranged
  reads, not the whole model. Its authorization token is dropped when the transfer moves to the download
  host.
- None of them carries chat text, prompt, document, media, report, contact, location, device identifier,
  advertising identifier or account credential. The network layer still reveals your IP address to Hugging
  Face, as any HTTPS request does, and its servers may log it with the time and the file or query requested.
- Hugging Face serves the file under its own terms and privacy policy. That logging and those terms are
  Hugging Face's, not Sirrak's.
- There are no analytics, no crash reporting, no ads, no tracking and no remote accounts, and no update
  check, font fetch or asset download that could phone home on its own.

The optional Hugging Face token is held in memory for the current session only; the app never writes it to
storage, the network log or a report. There is no "remember this token" option in 1.1.0.

**Offline-only mode** is a switch in **Settings → Privacy & data**. When it is on, the app refuses every new
network request and cancels a running download; the check runs before a socket is opened, so neither a
metered nor an unmetered connection can bypass it. Chat, dictation, media transcription, document search
and reports keep working. The switch is off by default, and model downloads, searches and probes otherwise
wait for an unmetered connection (Wi-Fi or Ethernet) or ask you before using mobile data.

## Permissions

| Permission | Why it is declared | When it is used |
| --- | --- | --- |
| `INTERNET` | fetch a model file you chose, search the public model index, probe a file header | only while one of those actions you started runs |
| `ACCESS_NETWORK_STATE` | check the connection before a download, search or probe | same |
| `RECORD_AUDIO` | dictation, transcribed on the device | only while dictation is active |
| `POST_NOTIFICATIONS` | show download progress and a Cancel action | from the first download on Android 13+; optional |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | keep a download alive when you leave the app | only during a download |

The app declares no location, contacts, camera, phone, SMS, storage or accessibility permission. Photos and
documents are read only through the Android system picker, or through a one-shot camera intent you choose,
and only for the item you selected.

## Retention and deletion

- Delete a chat, document, media item or model from inside the app. Deleting a model removes its file from
  app-private storage.
- Media items are kept or used once according to your choice; a once-only item is never indexed and is
  deleted when its session ends.
- A Hugging Face token is discarded when the app process ends; it is never persisted.
- The network log and the report queue each have a clear action and a fixed cap (50 entries, 50 reports).
- Exports and shared reports leave only through the Android chooser you invoke.
- Uninstalling Sirrak deletes all app-private data; **Settings → Apps → Sirrak → Storage → Clear storage**
  does the same without uninstalling.

## Children

Sirrak is a general-purpose tool, not directed at children, and it collects no personal data. There is no
account, profile or age gate, and so no children's data for the app to hold. A parent or guardian with a
question can use the contact route below.

## Third-party models and services

No model is bundled with Sirrak. Each catalogue model is downloaded or imported by you under its own
licence, which the app shows before download; that licence is between you and the model's publisher, and
some licences restrict commercial use or require attribution. Sirrak is not affiliated with, endorsed by or
sponsored by any model provider.

Browsing, searching, probing and downloading from Hugging Face are also subject to Hugging Face's terms and
privacy policy, and a gated model's token belongs to your Hugging Face account and is governed by Hugging
Face's policies.

## The website

The project site at <https://ahmadmhmdsy.github.io/sirrak/> is static: generated HTML, CSS, fonts and
screenshots from committed data. It sets no cookies, runs no analytics, loads no third-party scripts, has no
forms and does not fingerprint visitors. It is hosted on GitHub Pages, so GitHub's infrastructure may log
requests (IP address, user agent, time) under GitHub's own privacy statement; that logging happens outside
the app and outside the project's control.

**To be plain about the framing:** the hosted policy page is a document, not a service. It has no account,
no form and no cookie, and it does not collect, store or process anything about you.

## Changes to this policy

This policy is versioned with the app: version 2 accompanies 1.1.0 (25 November 2026). Material changes
will be shown in the app before they take effect, and this page keeps the same URL. If a future version adds
a network feature or stores something new, this document will say so before that version ships.

## Contact

- **Privacy questions** — open an issue through <https://github.com/ahmadmhmdsy/sirrak/issues/new/choose> with
  a subject beginning `privacy:`; the hosted page also suggests the `privacy` label. Please do not include
  personal data.
- **Security vulnerabilities** — never a public issue. Use the private advisory form at
  <https://github.com/ahmadmhmdsy/sirrak/security/advisories/new>; see [SECURITY.md](SECURITY.md).

## Related documents

- [SECURITY.md](SECURITY.md) — how to report a vulnerability and what is in scope.
- [store/play/data-safety.md](store/play/data-safety.md) — the Play data-safety answers that match this
  policy.
- [docs/26-sirrak-p3-polish-launch.md](docs/26-sirrak-p3-polish-launch.md) — how the privacy surfaces and
  their tests were built.
- [README.md](README.md) — product overview, install and build instructions.
