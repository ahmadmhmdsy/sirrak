# Listing screenshots (P3.5)

Captured on the API 36 emulator (`Nawa_Phone`, 720×1280) from the signed `libre` release build
`1.0.0-rc2` (versionCode 13, 24 Nov 2026); the screenshots are the exact PNGs of the shipped build,
not mockups. They were refreshed for rc2 so the store listing matches the released app: the no-model
chip, privacy log, licences sheet and version row all show current wording.

| File | Screen | Use |
| --- | --- | --- |
| `01-home-ar.png` | Arabic RTL home with the "try asking" empty state and the "لا يوجد نموذج · غير محمّل" chip | phone 1 |
| `02-chat-answer-ar.png` | chat with an on-device Qwen2.5 0.5B answer and the copy/regenerate/report actions | phone 2 |
| `03-model-manager-ar.png` | Settings → model manager: device card, Qwen2.5 0.5B recommendation, catalogue incl. the compact LFM2.5 350M tier | phone 3 |
| `04-background-download.png` | expanded download notification: Qwen2.5 0.5B Instruct, progress, Cancel | phone 4 (the P3.4 story) |
| `05-privacy-ar.png` | Privacy & data: offline-only switch, network log (1 request), AI output reports, policy links | phone 5 |
| `06-licences-ar.png` | Licences and credits sheet over About (version 1.0.0-rc2, 23 components, offline texts) | phone 6 |
| `07-dictation-ar.png` | dictation recording with the live level meter, timer and cancel/stop controls | phone 7 |
| `08-settings-en.png` | English LTR settings with the language switch (parity shot) | phone 8 |

For Play: upload phone screenshots in this order, Arabic locale first. The feature graphic and icon are
ready in `store/graphics/` (generated from `brand.json`, hashes in `manifest.json`, documented in
`store/graphics/README.md`).
