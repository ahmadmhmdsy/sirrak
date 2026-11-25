# Listing screenshots (P3.5)

Captured on the API 36 emulator (`Nawa_Phone`, 720×1280) from the `libre` debug build `1.1.0`
(versionCode 14, debug signing, 24–25 Nov 2026); they are real emulator PNGs, not mockups. They were
refreshed for 1.1.0 so the listing matches the candidate app: the no-model chip, the three library
segments, privacy log, licences sheet and version row all show current wording.

Provenance: `01-home-ar.png`, `03-model-manager-ar.png`, `04-background-download.png`,
`06-licences-ar.png` and `07-dictation-ar.png` are from candidate build SHA-256 `2C6F3A81…32C7`;
`05-privacy-ar.png` and `08-settings-en.png` were recaptured on build `3BDC4213…192E` after the
privacy footer and the Advanced-door summary were corrected. The shipped candidate artifact is
`4CD65A60…539A`; it differs from `3BDC4213…192E` only in two in-app network-copy strings (the first-run
privacy slide and the model-section note) that appear in no listing screenshot.

`02-chat-answer-ar.png` was recaptured on 2026-11-25 evening on that same shipped candidate artifact
(`4CD65A60…539A`, pulled from the emulator and hash-verified before the capture) so the chat shot is
Arabic-first rather than English. The capture used the third-party `ADBKeyboard` test IME only to type
Arabic into the emulator: the API 36 AOSP image ships no Arabic keyboard (`ime list -a` shows only
`com.android.inputmethod.latin/.LatinIME`) and `adb shell input text` is ASCII-only. The IME was hidden
before the screenshot and is not part of the app or of any shipping build; no other app or keyboard is
visible in the file.

| File | Screen | Use |
| --- | --- | --- |
| `01-home-ar.png` | Arabic RTL home: the "لا يوجد نموذج · غير محمّل" chip, the promise, and the "جرّب أن تسأل" suggestion card | phone 1 |
| `02-chat-answer-ar.png` | Arabic RTL chat: an Arabic prompt answered on-device by Qwen2.5 1.5B Instruct (model chip, token meter, copy/regenerate/report actions); Arabic typed with the emulator-only `ADBKeyboard` test IME, hidden before capture | phone 2 |
| `03-model-manager-ar.png` | Library → Models: device card, Qwen2.5 0.5B recommendation, catalogue incl. the compact LFM2.5 350M tier | phone 3 |
| `04-background-download.png` | expanded download notification: Qwen2.5 0.5B Instruct, progress, Cancel | phone 4 (the P3.4 story) |
| `05-privacy-ar.png` | Privacy & data: offline-only switch, network log (1 request), AI output reports, policy links, and the note naming the three user-started network features | phone 5 |
| `06-licences-ar.png` | Licences and credits sheet over About (version 1.1.0, 23 components, offline texts) | phone 6 |
| `07-dictation-ar.png` | dictation recording strip with the timer and the cancel/stop controls | phone 7 |
| `08-settings-en.png` | English LTR settings root with the six doors and their live summaries | phone 8 |

For Play: upload phone screenshots in this order, Arabic locale first. The feature graphic and icon are
ready in `store/graphics/` (generated from `brand.json`, hashes in `manifest.json`, documented in
`store/graphics/README.md`).
