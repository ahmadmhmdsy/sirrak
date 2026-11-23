# Google Play — Data safety answers (P3.5)

These answers match the shipped code. They are written to copy-paste into Play Console → App content →
Data safety, and to survive a reviewer reading `PRIVACY.md`.

## Section: Data collection and sharing

| Question | Answer | Evidence |
| --- | --- | --- |
| Does your app collect or share any of the required user data types? | **No** | No account, no analytics, no ads, no crash-reporting SDK, no server. All data stays in app-private storage. |
| Does your app share data with third parties? | **No** | The only network request is a user-initiated model download served by Hugging Face. It is a GET for a public file: no device or user data is sent. A gated model sends only the user's own Hugging Face token in an Authorization header, kept in memory and never persisted. |

If a reviewer asks about the download: it is a file transfer, not collection or sharing. Do not
invent a data category for it.

## Data stored on device (never transmitted)

| Data | Where | Leaves device? |
| --- | --- | --- |
| Chat messages, titles, settings | app-private storage | no |
| Imported documents, chunks, local embeddings | app-private storage | no |
| Dictation audio | memory / temp file during transcription | no |
| Installed model files | app-private storage | no |
| Network audit log (host, time, outcome, bytes) | SharedPreferences | no |
| AI-output reports (excerpt, reason, note) | SharedPreferences | no, until the user taps Share (system chooser) |

## Security practices (Play section)

- Data encrypted in transit? **Not applicable** for a public-file download; HTTPS is used.
- Users can delete data? **Yes** — uninstalling removes everything; in-app actions clear history, the
  network log, reports and models.
- Committed to the Play Families policy? **No** (not directed at children).
- Independent security review? **No**.
- Security contact: see repository `SECURITY.md` (draft until the address is published).

## Permissions and why

| Permission | Why | When asked |
| --- | --- | --- |
| INTERNET, ACCESS_NETWORK_STATE | download a model the user selects | first download |
| RECORD_AUDIO | dictation, transcribed on device | first dictation |
| FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC | keep a model download alive while backgrounded | automatic with a download |
| POST_NOTIFICATIONS | show the download progress/cancel notification | when a download starts (Android 13+) |

No location, contacts, camera (the system camera app is used for photos), storage, SMS or phone
permissions.

## Content rating notes

- User-generated content shared by the app: none (conversations stay on device unless the user
  copies them).
- Model answers are generated on device by a model the user installed; the app ships no model and no
  network AI service. This affects the "AI-generated content" disclosures: the app is an on-device
  client, not a hosted generative service.
