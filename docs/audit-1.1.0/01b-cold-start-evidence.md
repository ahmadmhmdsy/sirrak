# 01b — Cold-start evidence on a fresh device (clean run)

**Owner:** fresh-install-runner · **Date:** 25 Nov 2026, 19:08–19:33 (Asia/Baghdad) · **Build:** Sirrak
1.1.0 (versionCode 14), `app/build/outputs/apk/libre/debug/app-libre-debug.apk` (223,217,333 B, debug).
**Device:** second emulator `emulator-5556` (AVD `review36b`, API 36 x86_64, 4096 MB RAM → Android reports
3919 MB, 1080×2400 @420 dpi). **The founder's `emulator-5554` was never touched.** Every command used
`adb -s emulator-5556`. System locale en-US; app language Arabic (default, no in-app change made);
Wi-Fi `AndroidWifi` **UNMETERED + VALIDATED** (RSSI -50), mobile also connected; free storage 5.36 GB.
**Raw evidence:** 45 screenshots `build/ux-audit/shots-clean/clean-NN-*.png`, 45 UI hierarchies + node tables
`build/ux-audit/logs-clean/clean-NN-*.xml|-nodes.txt`, streaming dumps `stream-0..8.xml`/`regen-0..9.xml`,
logcat extracts `logcat-*.txt`, emulator logs `emulator-*.log`, and the downloaded file facts captured with
`run-as app.sirrak` (`files/download_queue.json`, `files/models/`, `files/model_keys.json`).
This is the first launch ever of the app on this AVD: no data, no model, no notification permission.

## Executive summary

1. **Onboarding is genuinely good and honest** (3 steps: privacy → language → model, Arabic-first, one clear
   promise per screen) but it is a promise the first hour does not keep. Step 3 has no Back, and the fourth
   empty-chat suggestion mixes an English subtitle into an otherwise Arabic set.
2. **The no-model failure copy routes the user into a dead end.** «لم يُحمَّل أي نموذج بعد. استورد ملف GGUF من
   الإعدادات ثم حاول مجددًا.» + «فتح الإعدادات» lands on a Settings root that has **no model management at
   all** (it moved to Library in this release). The only working route is the model chip, which the error
   never mentions. (P1, screenshot clean-06/07)
3. **The Hugging Face download path is broken on a fresh install.** Search → repo → probe → verdict → download
   enqueues fine, the foreground service starts, then dies after ~4 s, three times, with **0 bytes written**
   and `errorCode:"network"`, UI «تعذّر التنزيل: انقطع الاتصال.» The connection is validated and the *same
   file* downloads fine from the curated card seconds later — so the failure is in the HF path, not the
   network. This is the flag-ship "other sources" route and it fails 3/3. (P0, clean-23/24/27/28/29)
4. **Curated download lifecycle works** (491,400,032 B in ≈3 min 32 s including a pause: 5 MB → 18 MB →
   paused → resumed → INSTALLED), model auto-selected and adopted; chip went from «لا يوجد نموذج · غير
   محمّل» to «Qwen2.5 0.5B Instruct · جاهز على …». Pause/resume preserved bytes; no restart.
5. **The first real answer took ~30 s for ~65 tokens (~2.1 tok/s as reported by the app) and it is
   incoherent** — a word salad with a Latin fragment («وأomething»), not an answer to the question. It is the
   first thing a first-time user sees after the recommended 491 MB download. (P1, clean-42)
6. **A user who leaves the conversation cannot see the download at all.** Chip/empty-chat stay in the
   no-model state; on this fresh install **POST_NOTIFICATIONS was never requested and is denied**
   (`importance=NONE`), so even the foreground-service notification is invisible (notification shade shows
   only unrelated apps, clean-33). (P2, clean-32/33)
7. **Persistence is correct**: force-stop → relaunch (COLD, 3.47 s) restored the conversation, the partial
   answer and the selected/ready model.
8. **No crash and no ANR** for `app.sirrak` in any buffer; release-only jank sample at launch was 5/5 janky
   frames with p90 2,550 ms (small sample; see caveats). Memory with the 0.5B model loaded and one answer:
   **PSS 661,114 KB (~646 MB), RSS 790,052 KB**, native heap 75.9 MB — consistent with the app's own
   "831 MB estimated total" warning, but on a 4 GB device that is ~16 % of RAM before anything else.
9. **The first answer's action row is copy / regenerate / report** — no share action (the docs claim a full
   result experience; flag for the council to confirm the intended set). Regenerate + stop works, but a
   stopped answer is not marked as stopped and survives as a truncated sentence (clean-44).
10. **Timing caveat:** all speeds are from an emulator (swiftshader, no NEON/GPU acceleration); treat absolute
    tok/s as a lower bound for the target phone class, while structural gaps (no TTFT, no progress, no
    notification) are device-independent.

## Timeline — the first-time user's story (minute 0 = first tap on the icon)

| minute | what happens (screenshot) |
|---|---|
| **0:00** | launch; **first frame after 2.77 s** (TotalTime 2769 ms / WaitTime 2773 ms, cold, first ever launch); onboarding 1/3 privacy visible (clean-01) |
| 0:15 | 2/3 language: English / العربية, العربية preselected; Next/Back/Skip (clean-02) |
| 0:30 | 3/3 model choice: device facts (RAM 3919 MB, free 5.36 GB, API 36), recommended Qwen2.5 0.5B Instruct 491 MB «يتناسب مع ذاكرة جهازك ومساحته», buttons download / try-without-model / skip; **no Back** (clean-03) |
| 0:45 | «التجربة بدون نموذج» → empty chat, chip «لا يوجد نموذج · غير محمّل», 4 Arabic suggestions, «اسأل مستنداتك», disabled send (clean-04) |
| 0:50 | chip → sheet «لا يوجد نموذج» + «احصل على نموذج» (clean-05) |
| 1:10 | taps suggestion 1 → **failure**: «لم يُحمَّل أي نموذج بعد. استورد ملف GGUF من الإعدادات ثم حاول مجددًا.» + «إعادة المحاولة» / «فتح الإعدادات» + meter «81 / 1536 رمز تقديري» (clean-06) |
| 1:20 | «فتح الإعدادات» → Settings root: مظهر / لغة / خصوصية / صوت / متقدم / عن — **no model door**, dead end (clean-07) |
| 1:40 | Library → النماذج: المحرّك «لا يوجد نموذج», استيراد GGUF, 5 curated cards with fit verdicts (2 of them «غير متحقَّق»), HF doorway below (clean-08/11) |
| 2:10 | Library → التنزيلات (empty), → الوسائط (empty; Whisper path «افتح الإعدادات ← الصوت …») (clean-09/10) |
| 2:30 | Hugging Face search, debounce hint, results for "qwen2.5 0.5b" sorted by downloads (clean-12/13) |
| 2:45 | Qwen repo: license/context/architecture facts, GGUF file list with sizes, estimation panel «غير مؤكَّد» (clean-14/15); **top-bar repo title overflows and clips** |
| 3:00 | q4_k_m selected (491 MB); estimate 806 MB total (clean-16/17) |
| 3:15 | probe «جارٍ فحص ترويسة الملف…» ~14 s → header facts qwen2 / 24 layers / 14 heads / 2 KV / 896 / 32768, «قرأنا 13 MB», «من ترويسة الملف، لا من بيانات الناشر» (clean-18/19) |
| 3:30 | verdict «يُتوقع أنه يعمل»; CTA download; tap → toast «أُضيف إلى التنزيلات. تابع التقدّم من صفحة التنزيلات.» — **no size confirm on unmetered** (clean-20/21/22) |
| 4:00 | download row «…% · x MB من 491 MB» with pause/cancel (clean-23/24); then **«تعذّر التنزيل»** «انقطع الاتصال» with retry/remove (clean-27); retry ×2, each dies ≈4 s; 0 bytes on disk, attempts=3 (clean-28/29) |
| 9:00 | abandons HF, downloads from the curated card instead: «0% · 5 MB من 491 MB» (clean-30); pause at «3% · 18 MB» (clean-31), resume; chat while downloading shows **nothing** (clean-32); notification shade shows no Sirrak notification (clean-33) |
| 12:00 | «أُضيف النموذج: Qwen2.5 0.5B Instruct» snackbar; Library: «جاهز على الجهاز», «المحدد · 491 MB» (clean-34/35); chip «Qwen2.5 0.5B Instruct · جاهز على …» (clean-36); model sheet «جواز النموذج» (clean-37) |
| 13:00 | new chat, suggestion 1 → **first real answer**; stop control <1 s, first visible text ≤7 s, done ~30 s, meter 146/1536, **«≈ 2.1 رمز/ث»**; answer is incoherent (clean-39/40/41/42) |
| 14:00 | copy tapped (no confirmation visible), regenerate → stopped at 6.4 s, partial text kept, meter 95/1536 (clean-43/44) |
| 16:00 | force-stop, relaunch: **COLD 3,472 ms**; conversation + partial answer + ready model restored (clean-45) |

Fastest plausible path if the user takes the recommended download on step 3 and goes straight to chat:
**≈ 4–5 minutes from first launch to first answer** (~3.5 min download + ~30 s first answer).

## Measurements

### Cold start (first frame)
| # | command | result |
|---|---|---|
| 1 | first ever launch `am start -W -n app.sirrak/.MainActivity` | `LaunchState: COLD` · **TotalTime 2,769 ms** · WaitTime 2,773 ms · `Displayed app.sirrak/.MainActivity +2s769ms` |
| 2 | `am force-stop` then same launch (process dead) | `LaunchState: COLD` · **TotalTime 3,472 ms** · WaitTime 3,473 ms |

Install of the 223 MB debug APK: **9.5 s** (streamed install, Success). Emulator cold boot to
`sys.boot_completed=1`: ≈2 min (not an app number).

### Download lifecycle (curated Qwen2.5 0.5B Instruct, Q4_K_M, 491,400,032 B)
| event | time | bytes |
|---|---|---|
| tap download | 19:19:08 | 0 |
| first row update | 19:19:13 | «0% · 5 MB من 491 MB» |
| user paused | 19:19:33 | «3% · 18 MB من 491 MB» (18,000,000-ish) |
| user resumed | 19:19:40 | — |
| progress samples | 19:21:26 → 19:22:34 | 301,655,408 → 489,973,104 |
| **INSTALLED** | 19:22:40 | **downloadedBytes=491,400,032**; file `files/models/Qwen2.5 0.5B Instruct.gguf` 491,400,032 B; `model_keys.json` sha256 recorded |
Effective ≈2.3 MB/s including the pause; screen offered only % + MB, **no speed and no ETA**.

### HF download failure (the same file, via Hugging Face)
`files/download_queue.json` after 3 attempts:
```
{"id":"hf:Qwen/Qwen2.5-0.5B-Instruct-GGUF:qwen2.5-0.5b-instruct-q4_k_m.gguf",
 "sizeBytes":491400032,"downloadedBytes":0,"phase":"FAILED","errorCode":"network","attempts":3}
```
`files/model_downloads/` empty; each retry starts `app.sirrak/.DownloadService` FGS and stops it ~4 s later
(`logcat-downloadfail1.txt`); UI body «تعذّر التنزيل: انقطع الاتصال.». Wi-Fi validated, probe had just
downloaded 13 MB over Range, and the curated download of the identical file completed later. The app logs
no cause.

### First answer (Qwen2.5 0.5B Instruct Q4_K_M, one suggestion prompt)
- Prompt (suggestion): «ما الفرق بين القياس والاستنباط في أصول الفقه؟» — prompt tokens meter 81/1536.
- Tap → **stop control visible < 1 s** (stream-0, 19:24:06, no answer text yet); **first visible streamed
  text ≤ 7.1 s** (28 chars, clean-40); 13.9 s = 73 chars; 17.1 s = 99; 23.9 s = 178; 27.1 s = 215;
  **complete at ≈30.2 s**. Final meter **146 / 1536 رمز تقديري**, app reports **«≈ 2.1 رمز/ث»**
  (65 completion tokens / 30 s ≈ 2.2 — consistent).
- **TTFT is not shown by the app anywhere** and was not instrumented below ~1 s in this run: bounded
  0.5–7 s, most of it model load on first send. (P2: the app prints tok/s after the fact but never
  time-to-first-token, although the brief expects it.)
- Answer verbatim (with the Latin fragment):
  «القياس والاستنباط في أصول الفقه هو مسؤوليتان عن الأشياء، وليس الهدفان. فالقياس هو اجراء اختبار أو تقييم
  للتأكد من أن شيء ما ينطبق على شيء ما الآخر، بينما الاستنباط هو إيجاد علاقة معينة بين شيء ما وأomething ما
  آخر، أو تحليل ما يُعرف بأنه مرتبط.»
  It is not an answer to the question; it is fluent-looking but factually empty and mixes scripts.
- Answer actions (content-desc): **نسخ الإجابة**, **إعادة التوليد**, **الإبلاغ عن هذه الإجابة**; no share.
- Copy tapped: no visible confirmation in the capture 0.8 s later (clean-43) — unverified.
- Regenerate then **stop at 6.39 s**: partial «القياس والانسان التنبؤ هو اختلاف كبير» (32 chars) is kept on
  screen with the same three actions and meter 95/1536; **nothing marks it as stopped/truncated** (clean-44).
- The answer (and the stopped partial) persisted through force-stop/relaunch (clean-45).

### Stability, memory, jank, notifications
- Crash buffer: empty. `/data/anr`: only an old system trace from 2026-11-21, nothing for app.sirrak.
  No FATAL/ANR/kotlin exception attributable to the app in the captured buffers.
- `dumpsys meminfo app.sirrak` after first answer: **TOTAL PSS 661,114 KB**, TOTAL RSS 790,052 KB,
  native heap 75,908 KB (455 MB heap size), `Other mmap` ≈ 476,847 KB (the mapped GGUF). No swap.
- `dumpsys gfxinfo app.sirrak framestats` immediately after the force-stop relaunch: **5 frames total, 5
  janky (100 %)**, p50 200 ms, p90 2,550 ms — i.e. the launch frames; steady-state scroll jank not measured
  (see "not completed").
- Notification permission: `POST_NOTIFICATIONS: granted=false`, app ops `POST_NOTIFICATION: ignore`,
  `AppSettings importance=NONE`. The app **never asks** during onboarding; the download FGS notification is
  therefore invisible (clean-33).

## Findings (schema: AREA-NN — title · Severity · Confidence · Effort · Where / What happens now / Why it hurts / Fix / Verify)

### HF-01 — Hugging Face downloads fail 0 bytes with a false "connection dropped" message · **P0** · high · M
**Where:** Library → النماذج → Hugging Face → repo → probe → تنزيل النموذج (Qwen/Qwen2.5-0.5B-Instruct-GGUF, q4_k_m).
**What happens now:** enqueue works; DownloadService starts, dies ~4 s later; 3/3 attempts write 0 bytes;
row shows «تعذّر التنزيل» + «تعذّر التنزيل: انقطع الاتصال.»; queue JSON `phase=FAILED, errorCode=network,
attempts=3, downloadedBytes=0`; no cause is logged or shown. The curated download of the identical file works
seconds later on the same validated Wi-Fi.
**Why it hurts:** the documented "other sources" journey is dead on a fresh install, and it blames the user's
connection; a first-time user concludes the app or their network is broken and gives up on the only source of
models beyond the 5 curated cards.
**Fix:** fix the HF fetch path (Range header/URL/user-agent/redirect handling — the probe's Range read works,
so compare its HTTP client with the downloader's); surface the HTTP status/exception in the row and log it;
retest 5 consecutive HF downloads.
**Verify:** clean install → HF → same repo → download reaches INSTALLED with 491,400,032 B; pull-the-plug during
transfer produces a *different*, truthful error.

### CHAT-01 — "Import a GGUF from Settings" is a dead route · **P1** · high · S
**Where:** chat, send with no model (clean-06) → «فتح الإعدادات»; Settings root (clean-07).
**What happens now:** error copy says «استورد ملف GGUF من الإعدادات ثم حاول مجددًا»; the action opens a Settings
root with مظهر/لغة/خصوصية/صوت/متقدم/عن only. Model management and import live in Library → النماذج; nothing in
the error or Settings points there.
**Why it hurts:** the very first failure in the app's core loop sends the user to a door that does not exist;
they must find the unlabelled model chip to recover.
**Fix:** route the error + action to Library → النماذج (direct download CTA), or update copy to say «من المكتبة ←
النماذج», and add the model row to Settings only if it is genuinely there.
**Verify:** no-model send → error names a target that exists; tapping it opens a screen with a working
download/import control.

### MODEL-01 — The recommended model's first answer is incoherent · **P1** · medium · L (product choice)
**Where:** onboarding 3/3 «النموذج الموصى به» Qwen2.5 0.5B Instruct → download → first answer (clean-42).
**What happens now:** after a 491 MB download the assistant answers the first suggested question with a word
salad that ends by defining "something" («وأomething»); a second attempt (regenerate/stop) also produced
nonsense. The onboarding said the model «يتناسب مع ذاكرة جهازك ومساحته» — true for RAM, misleading for quality.
**Why it hurts:** the first answer is the product; a first-time user judges the whole promise ("your private
assistant") on this output and concludes it is fake/broken. This is the single biggest trust risk after the
broken HF path.
**Fix:** do not recommend 0.5B for Arabic answers as-is — either recommend a 1.5B+ model with a clear
size/speed trade-off, or keep 0.5B for "tutorial/demo" with an explicit expectation set («مختصر وقد يخطئ»),
and add a one-line honesty note in onboarding. Validate with 10 Arabic prompts on target hardware.
**Verify:** fresh install → recommended path → first 3 answers are readable, on-topic Arabic; if the default
stays 0.5B, the UI sets quality expectations before download.

### HF-02 — Repo title overflows the app bar, clips and collides with Back · **P2** · high · S
**Where:** Hugging Face repo screen, every repo (clean-14/16/21).
**What happens now:** the repo id is rendered in a huge display face in the top bar, wraps to 3 lines and is
clipped («Qwen/» / «Qwen2.5-0.5B-Instruct-GGU» / «F»), pushing the back arrow inline and eating ~330 px of
the first screen.
**Why it hurts:** looks broken at the exact moment the user is being asked to trust the probe/verdict machinery;
a truncated name is also a scannability problem when comparing repos.
**Fix:** cap the app-bar title to one ellipsized line and put the full id in the repo card (it is already
repeated there); smaller style for Latin ids.
**Verify:** any repo screen: one-line title, back arrow untouched, no clipped glyph.

### DL-01 — A running download is invisible everywhere except Library → التنزيلات · **P2** · high · M
**Where:** chat chip/empty state during download (clean-32); notification shade (clean-33); permission state.
**What happens now:** during the 3.5-minute download the chip still says «لا يوجد نموذج · غير محمّل», chat shows
no banner/progress, and the system notification is suppressed because **POST_NOTIFICATIONS was never requested**
on this fresh install (granted=false, importance=NONE). A user who taps «إغلاق» on the snackbar and chats sees
nothing happening.
**Why it hurts:** they either wait blindly, think the download died, or start it again; the durable-queue
promise ("يُتابع من حيث توقّف") is invisible in the surface they sit in.
**Fix:** chip state «جارٍ التنزيل 42%» (tap → downloads), and request POST_NOTIFICATIONS in context on the
first download (or show an in-app banner), so the FGS notification is actually visible.
**Verify:** start a download → leave the app/chat: chip shows progress and the shade shows a Sirrak
notification with % and cancel.

### DL-02 — One tap starts a 491 MB download on unmetered Wi-Fi with no size confirmation · **P2** · medium · S
**Where:** HF repo CTA (clean-22) and curated card.
**What happens now:** the only acknowledgement is toast «أُضيف إلى التنزيلات. تابع التقدّم من صفحة التنزيلات.»
— no size/free-space confirmation and no consent step on an unmetered network. (On metered the app does ask;
that path could not be reproduced here.)
**Why it hurts:** 491 MB is a large first expense on many users' plans; the only place to learn the size is the
card label, and the toast does not say what was started or where.
**Fix:** a compact confirmation sheet (name, size, free space, Wi-Fi/metered, "start") or at least a snackbar
with name + size + an "عرض التنزيلات" action.
**Verify:** every download start states name/size before transfer begins.

### CHAT-02 — A stopped answer looks like a finished answer; no TTFT · **P2** · high · S
**Where:** answer stream, stop button (clean-44).
**What happens now:** tapping stop at 6.4 s keeps 32 characters of a half-sentence as the assistant's turn with
copy/regenerate/report actions; nothing says «تم الإيقاف» or marks it partial. The meter row (95/1536,
≈2.1 tok/s) is the only hint. The app never shows time-to-first-token even though the brief's chat experience
expects it.
**Why it hurts:** the user can copy or keep a truncated, unfinished sentence believing it is the answer; the
"result experience" claim is not met without TTFT/stop markers.
**Fix:** append a "stopped" marker to the turn («توقّف التوليد — إعادة التوليد») and show TTFT (e.g. «أول رمز
بعد 2.1 ث») next to tok/s.
**Verify:** stop mid-stream: turn visibly marked stopped, TTFT + tok/s both visible.

### MODEL-02 — Model chip truncates its own state · **P2** · high · S
**Where:** chip on chat, after install (clean-40/42/45): «Qwen2.5 0.5B Instruct · جاهز على …».
**What happens now:** with the ready label, the chip ellipsizes the state («جاهز على الجهاز» → «جاهز على…»), and
long model names shrink the state further.
**Why it hurts:** the chip is the only global model state; a truncated state is exactly the thing the
four-state chip was introduced to communicate.
**Fix:** give state its own non-truncating segment (dot + «جاهز» / «جارٍ التنزيل 42%»), or truncate the model
name instead.
**Verify:** at 100 % font scale and 130 %: full state word visible for every model name.

### SYS-01 — Cold start ~2.8–3.5 s and visibly janky launch frames · **P2** · medium · M
**Where:** first launch (TotalTime 2,769 ms) and force-stop relaunch (3,472 ms, COLD); 5 launch frames all
janky, p90 2,550 ms (tiny sample).
**What happens now:** launch is slow for an app whose first screen is three static onboarding pages; the
relaunch is *slower* than the first-ever launch, so nothing is being cached/warmed.
**Why it hurts:** the first impression gate; 2.8 s blank/placeholder start is a P2 on the target devices this
app is sold for.
**Fix:** baseline profile + lazy init of engine/DB; move onboarding to a cheap first frame; profile cold start
with `am start -W` in CI.
**Verify:** p90 cold start ≤1.5 s on a mid-range device; `gfxinfo` launch frames <5 % janky.

### MEM-01 — ~646 MB PSS / 772 MB RSS with one 0.5B answer · **P2** · medium · M
**Where:** `dumpsys meminfo` after first answer (model loaded).
**What happens now:** TOTAL PSS 661,114 KB, RSS 790,052 KB, native heap 75.9 MB, GGUF mapped (~476 MB
`Other mmap`). The app estimates «831 MB تقديري» in the probe — close enough — but nothing on the chat or
Library screen shows current memory use or offers "unload" visibly (it is inside the model sheet:
«أفرغ من الذاكرة»).
**Why it hurts:** on 4 GB devices ~16 % of RAM is gone before media/Whisper; the app's own warning is shown
only pre-download and never afterwards.
**Fix:** show a small memory chip in the model sheet/chat menu when a model is resident, with the unload
action; keep the estimate-vs-real note.
**Verify:** resident 0.5B model: memory visible in-app, unload frees PSS back near baseline.

### ONB-01 — Step 3 has no Back · **P3** · high · S
**Where:** onboarding 3/3 (clean-03) vs 2/3 has «رجوع».
**What happens now:** the only ways off step 3 are download / try-without-model / skip; language cannot be
revisited.
**Why it hurts:** a user who wants English after seeing the Arabic model screen must skip forward and find
Settings, or restart.
**Fix:** add the same back affordance as step 2.
**Verify:** step 3 → رجوع → step 2 with state preserved.

### ONB-02 — One English subtitle in an all-Arabic suggestion set · **P3** · high · S
**Where:** empty chat (clean-04/38), 4th card: «لخّص أطروحة ابن خلدون عن العصبية في ثلاث نقاط» / subtitle
«Ibn Khaldun on 'asabiyyah, in three moves».
**What happens now:** three cards have Arabic subtitles, the fourth switches to English mid-list.
**Why it hurts:** small but visible inconsistency on the first screen after onboarding; reads as an unfinished
translation.
**Fix:** Arabic subtitle (e.g. «ابن خلدون — العصبية في ثلاث نقاط»).
**Verify:** all four cards read Arabic at 100 % and 130 %.

### LIB-01 — Media screen content is clipped by the bottom navigation · **P3** · high · S
**Where:** Library → الوسائط, at rest (clean-10).
**What happens now:** the last policy row is cut mid-label under the nav bar («السياسة الافتراضية — الاحتفاظ
الافتراضي»), requiring a scroll before the user knows what it says.
**Why it hurts:** the keep/once contract is a trust point; it is half-hidden exactly where it is explained.
**Fix:** content padding = nav height + insets (same for all three Library segments).
**Verify:** every Library segment scrolls to a fully visible last row at 100/130 %.

### LIB-02 — Curated catalogue cards can read "not verified — cannot judge" · **P3** · medium · S
**Where:** Library → النماذج cards (clean-08): LFM2.5 350M and SmolVLM 500M show «غير متحقَّق» + «لم يتحقّق
التطبيق من بيانات هذا الملف بعد، فلا يمكن الحكم.», while neighbours show «يعمل».
**What happens now:** a first-party curated entry presents the same "unknown" verdict as an unprobed HF file.
**Why it hurts:** the verdict is the app's core differentiator; an unknown verdict on its own list undermines
the promise and makes the good cards less credible.
**Fix:** run the probe at curation time and ship the facts, or label these as "unverified source" cards
explicitly (not as a verdict).
**Verify:** no curated card has an "unknown" fit verdict.

### DL-03 — Download row has no speed or ETA · **P3** · high · S
**Where:** downloads row during transfer (clean-23/30/31): «3% · 18 MB من 491 MB».
**What happens now:** only % and bytes; no MB/s, no remaining time, no total remaining storage line.
**Why it hurts:** a 3.5-minute wait with no forecast feels longer and makes stalls unidentifiable (the HF
failure showed no notion of "stalled at 0 B" either).
**Fix:** add «2.3 MB/ث · ~3 د» derived from the last N seconds; freeze it when paused.
**Verify:** during a download the row shows speed + ETA; when paused both are replaced by «متوقف مؤقتًا».

### MET-01 — «1536 رمز تقديري» meter is unexplained and survives a failed turn · **P3** · medium · S
**Where:** chat meter row (clean-06 failure shows «81 / 1536 رمز تقديري»; clean-42 done shows «146 / 1536» +
«≈ 2.1 رمز/ث»).
**What happens now:** a token meter with a 1536 cap appears even when no model exists and nothing was generated
(81 tokens on a failed turn); no label explains what 1536 is (context cap?) or that the numbers are estimates.
**Why it hurts:** misleading telemetry on an error screen; a privacy-first app should explain a token budget
otherwise it reads as telemetry.
**Fix:** hide the meter on failed/no-model turns; add an info affordance for «1536» and «تقديري»; keep TTFT
next to tok/s.
**Verify:** no-model failure shows no meter; meter info explains the budget.

### CHAT-03 — Answer action row lacks share · **P3** · medium · S
**Where:** finished answer actions (clean-42): copy / regenerate / report only.
**What happens now:** no share affordance (docs describe a full result experience including share).
**Why it hurts:** share is the organic-growth loop for a private assistant; users copy-paste manually (no
confirmation either — clean-43).
**Fix:** add «مشاركة» (text share intent) and confirm copy with a snackbar.
**Verify:** done answer exposes share; copy shows confirmation.

## Screenshot index (build/ux-audit/shots-clean/)

| file | one line |
|---|---|
| clean-01-first-launch-ar.png | onboarding 1/3 privacy promise + Next/Skip, first frame of a brand-new install |
| clean-02-onboarding-step2-ar.png | onboarding 2/3 language, العربية preselected, Next/Back/Skip |
| clean-03-onboarding-step3-ar.png | onboarding 3/3 model choice, device facts, 491 MB recommendation, three forward CTAs, no Back |
| clean-04-chat-empty-no-model-ar.png | empty chat with no model: chip, promise, 4 suggestions, «اسأل مستنداتك», disabled send |
| clean-05-no-model-chip-sheet-ar.png | chip sheet «لا يوجد نموذج» + «احصل على نموذج» |
| clean-06-send-no-model-failure-ar.png | send with no model: error «استورد ملف GGUF من الإعدادات…» + retry/settings + meter 81/1536 |
| clean-07-failure-open-settings-ar.png | Settings root reached from the error — no model door (dead end) |
| clean-08-library-models-empty-ar.png | Library → models: engine none, import GGUF, curated cards with fit verdicts |
| clean-09-library-downloads-empty-ar.png | Library → downloads empty + durable-queue contract copy |
| clean-10-library-media-empty-ar.png | Library → media empty, Whisper path, keep/once policy clipped by nav |
| clean-11-library-models-scrolled-ar.png | catalogue bottom + Hugging Face doorway |
| clean-12-hf-search-empty-ar.png | HF search empty state |
| clean-13-hf-search-results-ar.png | HF results for "qwen2.5 0.5b", sorted by downloads |
| clean-14-hf-repo-files-ar.png | repo page: facts, GGUF list, «غير مؤكَّد» estimate, broken top-bar title |
| clean-15-hf-file-list-scrolled-ar.png | full GGUF quant list with sizes |
| clean-16-hf-q4km-selected-verdict-ar.png | q4_k_m 491 MB selected, estimate updated |
| clean-17-hf-estimate-after-select-ar.png | estimation panel after selection (file/KV/overhead/total vs available) |
| clean-18-hf-probe-dialog-ar.png | probe running «جارٍ فحص ترويسة الملف…» |
| clean-19-hf-probe-verdict-ar.png | probe result with header facts and provenance sentences |
| clean-20-hf-verdict-download-cta-ar.png | verdict «يُتوقع أنه يعمل» + download CTA |
| clean-21-hf-download-button-ar.png | download button/state before tap |
| clean-22-hf-download-tapped-ar.png | enqueue toast «أُضيف إلى التنزيلات…» (no size confirm) |
| clean-23-download-row-running-ar.png | HF download row running with pause/cancel |
| clean-24-downloads-running-ar.png | downloads segment while transfer running |
| clean-25-after-hf-back-ar.png | back out of HF to Library |
| clean-26-library-models-after-download-ar.png | models segment after HF enqueue (no model installed) |
| clean-27-downloads-active-row-ar.png | HF row after failure «تعذّر التنزيل» «انقطع الاتصال» + retry/remove |
| clean-28-download-retry-ar.png | first retry (FGS starts) |
| clean-29-download-retry2-ar.png | second retry, still 0 bytes / failed |
| clean-30-curated-download-tapped-ar.png | curated Qwen2.5 0.5B download starts «0% · 5 MB من 491 MB» |
| clean-31-download-paused-ar.png | paused at «3% · 18 MB من 491 MB», button «متابعة» |
| clean-32-chat-during-download-ar.png | chat during download — chip still no-model, zero progress shown |
| clean-33-notification-during-download-en.png | notification shade during download — no Sirrak notification (permission denied) |
| clean-34-download-completed-ar.png | «أُضيف النموذج: Qwen2.5 0.5B Instruct» + model in on-device list |
| clean-35-model-installed-library-ar.png | Library models: «جاهز على الجهاز», «المحدد · 491 MB» |
| clean-36-chat-model-ready-chip-ar.png | chat chip after install, state truncated «جاهز على…» |
| clean-37-model-ready-sheet-ar.png | model sheet «جواز النموذج» (facts, unload, open Library) |
| clean-38-new-chat-ready-ar.png | fresh chat with ready model, suggestions |
| clean-39-stream-first-token-ar.png | ~0.5 s after send: stop control, no text yet (misnamed "first token") |
| clean-40-stream-5s-ar.png | streaming: partial answer, blinking marker, red stop button |
| clean-41-stream-15s-ar.png | streaming at ~15 s (99 chars) |
| clean-42-answer-done-ar.png | finished answer + copy/regenerate/report + «146/1536» + «≈ 2.1 رمز/ث» |
| clean-43-copy-answer-ar.png | after copying — no confirmation toast captured |
| clean-44-answer-stopped-ar.png | after stop at 6.4 s: 32-char truncated answer kept, no "stopped" marker |
| clean-45-relaunch-persistence-ar.png | after force-stop/relaunch: conversation, partial answer, ready model restored |

## What I did NOT complete (and why)

Deadline from the Lead (10 minutes to deliver this file) ended the run; remaining items were planned and are
**not covered by evidence**, so they must not be assumed verified:
1. **English answer** in English and the English UI pass (language switch). The app UI was only captured in
   Arabic; English onboarding/settings/chat copy is unverified.
2. **Dark theme** pass and **font scale 130 %** on chat + Settings — not captured (only 100 % portrait).
3. **Conversation library**: rename / delete / export, and the history drawer — not exercised.
4. **Media import** (document / image / audio + Whisper) — empty state only; the actual import/keep-once
   behaviour and the disabled-Whisper path are unverified.
5. **Metered-network consent dialog** — the emulator's Wi-Fi is unmetered and could not be switched within
   the window; the metered question was not observed.
6. **Steady-state scroll jank** — `gfxinfo` was reset by the force-stop relaunch, so only 5 launch frames
   (all janky) were measured; a scroll sample on the chat/Library is not available.
7. **Long-press on an answer** and the full action set (only the visible icon row was used).
8. **Repeatability** of the HF failure after the deadline (3 attempts is a strong but finite sample).
9. **A second first-run variant** (skip on step 1/2 instead of "try without model").
10. The copy confirmation and clipboard content were not verified.
**Caveat on speeds:** emulator CPU (swiftshader, no hardware acceleration). Absolute latencies (2.8–3.5 s cold
start, 2.1 tok/s) are upper bounds for real phones; the UX gaps (false error copy, broken HF path, invisible
download, incoherent first answer, truncated chip, missing TTFT) are independent of emulator speed.

## Appendix — verbatim artifacts

- Queue after HF failure: `{"id":"hf:Qwen/Qwen2.5-0.5B-Instruct-GGUF:qwen2.5-0.5b-instruct-q4_k_m.gguf",
  "sizeBytes":491400032,"downloadedBytes":0,"phase":"FAILED","errorCode":"network","attempts":3}`
- Model file after curated download: `-rw------- u0_a150 u0_a150 491400032 "Qwen2.5 0.5B Instruct.gguf"`;
  `model_keys.json` sha256 `1116dc870159d5b2b5212c94c0a24f158c0bcc562fcca78857e6207022c01ca0`.
- Notification state: `POST_NOTIFICATIONS: granted=false`, `POST_NOTIFICATION: ignore`,
  `AppSettings: app.sirrak importance=NONE`.
- Crash buffer empty; `/data/anr` only `anr_2026-11-21-22-48-26-458` (system, pre-dates this run).
- Raw logs: `boot-5556.log`, `emulator-out.log`, `emulator-err.log`, `logcat-19-17.txt`,
  `logcat-app-pid.txt`, `logcat-downloadfail1.txt`, stream/regen UI dumps.
