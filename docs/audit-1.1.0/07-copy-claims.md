# 07 — Copy & claims audit (Sirrak 1.1.0, versionCode 14)

Target: installed 1.1.0 candidate (debug-signed universal APK, app.json provider `llamacpp`).
Scope: in-app Arabic/English copy (Part A) and public/product claims vs shipped code and behaviour (Part B).
Method: resource XML, `app/src/main/assets/config/app.json`, hooks/screens that render the strings, PRIVACY.md/README*/PRODUCT.md, `store/`, `website/content`. Every claim finding names the contradicting code or doc.
Evidence limitation: 29 screenshots and `build/ux-audit/EVIDENCE.md` existed when this was written, but the time-box did not allow inspecting them; all findings below are proven from strings, config, code and committed docs, and visual-only questions are listed under "Not verified".

## Executive summary

- **Strengths:** the Arabic terminology law (docs/30) is genuinely enforced in the main surfaces (`نموذج`, `تعليمات`, `ملحق الرؤية`, `التكميم`, `نافذة السياق`), and the honest-copy habit is real: fit verdicts say "تقديري، لا قياس", unprobed files say "غير متحقق", and destructive actions carry consequence-labelled confirmations.
- **Biggest risk:** the product's central privacy promise is stated as an absolute on three public surfaces — About (`privacy_statement`), the English Play listing and the Play Data-safety answers — while 1.1.0 ships three declared network features; this is the one theme that can destroy trust in a single reading.
- **Second risk:** several guidance strings still describe the pre-1.1.0 information architecture (model import in Settings, a demo engine you can "start with"), so a first-run user who skips the model is sent to a dead end and the error action lands on a page that no longer holds models.
- **Third risk:** retention/behaviour copy is internally contradictory: the media shelf's "once" choice says deleted "with the conversation" in the chip and sheet but at app close in the note right below it, and the downloads note implies an automatic resume that code explicitly never performs.
- **Counts:** 0 P0 · 6 P1 · 16 P2 · 12 P3 = 34 findings (Part A 20, Part B 14). All P1 are high confidence. No P0 is claimed because every defect has a working path elsewhere in the app; CLAIM-03 becomes P0 the day the Play listing is submitted.

---

# Part A — In-app copy

### COPY-01 — Onboarding promises a demo engine the build never selects (≤60)
**Severity:** P1 · **Confidence:** high · **Effort:** S
**Where:** `feature/models/src/main/res/values/strings.xml:93,95,96`; `feature/models/src/main/java/app/sirrak/feature/models/OnboardingScreen.kt:203-208,287`; `app/src/main/assets/config/app.json:25`; `app/src/main/java/app/sirrak/AppComposition.kt:303-312`.
**What happens now:** The model step offers "التجربة بدون نموذج" (`try without`) and "التجربة بدون نموذج" is followed by "بدون نموذج يعمل محرك العرض: استكشف المحادثة والإعدادات"; tapping it only calls `finish(true)` and closes onboarding. The shipped config selects the llama.cpp provider, and the demo engine is only built when `engine.provider` is `demo` or unknown. Chat with no model then shows the NO_MODEL error.
**Why it hurts:** A cautious first-run user takes the explicitly offered "try without a model" path and lands on an error instead of the interface, which is the worst possible first five minutes.
**Fix:** Either wire the demo engine selection to this button (persist a provider override) or delete the demo promise and rewrite the button as "تخطّي الآن" with no claim about a working interface.
**Verify:** Fresh install → onboarding → model step → "التجربة بدون نموذج" → send any prompt; expected today: NO_MODEL error, not demo replies.

### COPY-02 — Media "once" says deleted with the chat; note says at app close (≤60)
**Severity:** P1 · **Confidence:** high · **Effort:** S
**Where:** `app/src/main/res/values/media_strings.xml:49` (`media_state_once` "مؤقت · يُحذف مع المحادثة ولا يُفهرس"), `:75` (`media_sheet_once` "مؤقت: يُحذف مع المحادثة ولا يُفهرس") vs `:79` (`media_once_note` "…ويُحذف ملفها عند إغلاق التطبيق. لا يوجد ربط بالمحادثات في هذا الإصدار بعد، لذلك يُحذف المؤقت عند الإغلاق."); corroborated by `docs/32-ux-rework-build-record.md:117-118` and `README.md:243-244`.
**What happens now:** The same screen shows both statements: the chip and the retention choice say the file is deleted with the conversation, then the note below admits media is not wired to conversations and is deleted when the app closes.
**Why it hurts:** The retention choice is the only control over whether personal media stays; its consequence text is wrong at the exact moment of decision, and a user who reads both lines cannot know which is true.
**Fix:** Make `:49`/ `:75` say "يُحذف عند إغلاق التطبيق" and remove the "with the conversation" clause until the lifecycle exists; keep the note.
**Verify:** Library → Media → import a file → choose "مؤقت": compare the chip text and the note on the same screen (both strings above).

### COPY-03 — "No model" error and button still point at Settings (≤60)
**Severity:** P1 · **Confidence:** high · **Effort:** S
**Where:** `feature/chat/src/main/res/values/strings.xml:18-19` ("لم يُحمَّل أي نموذج بعد. استورد ملف GGUF من الإعدادات…" / "فتح الإعدادات"); `feature/chat/src/main/res/values-en/strings.xml:18-19`; `feature/chat/src/main/java/app/sirrak/feature/chat/ChatScreen.kt:337,352-360`; `feature/chat/src/main/java/app/sirrak/feature/chat/ChatPlugin.kt:87` (`navigator.select("settings")`); model import now lives on the model page: `app/src/main/java/app/sirrak/Panels.kt:396-399`, and Settings has only appearance/language/privacy/voice/advanced/about doors (`feature/settings/src/main/java/app/sirrak/feature/settings/SettingsPlugin.kt:47,76` + `feature/privacy/.../PrivacyPlugin.kt:54`, `app/.../VoicePlugin.kt:82`, `app/.../RagPlugin.kt:58`, `app/.../AboutPlugin.kt:43`).
**What happens now:** After a failed or absent model the answer bubble says import GGUF "from Settings" and its action opens Settings, where no model import exists in 1.1.0; the model chip's own sheet opens the Library (`Panels.kt:93`).
**Why it hurts:** This is the recovery path for the app's most common error; it sends the user to a screen where the instruction cannot be followed.
**Fix:** Change the copy to "من المكتبة ← النماذج" and route the action to `navigator.select("library")` (or to the model page); mirror in values-en.
**Verify:** Clear app data → start onboarding → skip model → send a prompt → tap "فتح الإعدادات": no model import is on the page.

### COPY-04 — Embedding-model import is described in two different places (≤60)
**Severity:** P2 · **Confidence:** high · **Effort:** S
**Where:** `feature/chat/src/main/res/values/strings.xml:67` (`attachment_blocked_document` "استورده من تبويب المكتبة") vs `:86` (`documents_picker_embedding_missing` "قبل استيراد نموذج التضمين من الإعدادات"); actual import: Settings → متقدم (`app/src/main/java/app/sirrak/RagPlugin.kt:58` door, `:84,102-208` `EmbeddingModelSettingsSection`).
**What happens now:** Two capability-gate strings for the same prerequisite send the user to Library in one case and Settings in the other; only Settings is correct.
**Why it hurts:** The first-document flow is blocked until an embedding model is imported; half the users follow the wrong instruction and conclude the app is broken.
**Fix:** Both strings should say "الإعدادات ← متقدم" and keep the same wording.
**Verify:** Attach a document from the chat attachment button and from the document picker; compare the two gate messages.

### COPY-05 — "Choose a vision model in Settings" points at the wrong screen (≤60)
**Severity:** P2 · **Confidence:** high · **Effort:** S
**Where:** `feature/chat/src/main/res/values/strings.xml:118` (`image_attach_no_vision` "اختر نموذجًا يدعم الرؤية من الإعدادات") and `values-en/strings.xml:118`; the projector is imported on the model page: `app/src/main/java/app/sirrak/Panels.kt:358-363`, reached from the chip passport `:162-169`.
**What happens now:** With a non-vision model loaded, the image path tells the user to pick a vision model in Settings; Settings has no vision or model-management section in 1.1.0.
**Why it hurts:** The user cannot act on the instruction; the actual control (a paired mmproj) is two taps away on the model page.
**Fix:** "أضف ملحق رؤية (mmproj) لهذا النموذج من صفحة النموذج" and link there.
**Verify:** Load a text-only GGUF → attach an image → read the gate line, then search Settings for any vision control.

### COPY-06 — "Nothing runs in the background" contradicts the download service (≤60)
**Severity:** P2 · **Confidence:** high · **Effort:** S
**Where:** `feature/models/src/main/res/values/strings.xml:81` (`models_network_note` "لا شيء يعمل في الخلفية…") and `:85` (`onboarding_privacy_network` "لا يتصل التطبيق بالشبكة في الخلفية…"); contradicting behaviour: `app/src/main/res/values/strings.xml:231-238` (download notification channel/title/cancel), `PRIVACY.md:83-84` ("keep a download alive when you leave the app"), `docs/32-ux-rework-build-record.md:41`.
**What happens now:** The privacy page and onboarding state as an absolute that the app does not work in the background, while a user-started download continues in a dataSync foreground service when the app is backgrounded.
**Why it hurts:** In a privacy product, an absolute that the user can personally disprove (a progress notification while another app is open) discounts every other honesty claim.
**Fix:** "لا يتصل التطبيق بالشبكة إلا لطلب بدأته أنت، وقد يكمل التنزيل الذي بدأته في الخلفية حتى ينتهي." 
**Verify:** Start a download → press Home → notification keeps updating (app strings 232-238) while `models_network_note` claims otherwise.

### COPY-07 — Metered data has three names and one ambiguous antonym (≤60)
**Severity:** P2 · **Confidence:** high · **Effort:** M
**Where:** `app/src/main/res/values/strings.xml:283` ("الاتصال محدود الاستخدام") vs `:299-300` ("تنزيل على اتصال محدود؟" / "عبر اتصال الجوال (أو اتصال محدود)") vs `feature/models/src/main/res/values/hf_strings.xml:60` ("عبر اتصال غير محدود"); Wi-Fi action "`انتظر واي فاي`" `app/.../strings.xml:302`.
**What happens now:** The same decision (will this cost mobile data?) is worded as "limited-usage connection", "limited connection", "mobile data", and "unlimited connection" for the opposite. "غير محدود" reads as "unlimited" to most users, not "unmetered".
**Why it hurts:** This is the one prompt where a non-technical Baghdad student on metered data can incur a 2 GB charge; ambiguous wording defeats the confirmation.
**Fix:** Pick two words and use them everywhere: "بيانات الجوال (مدفوعة)" and "واي فاي (غير محسوبة)".
**Verify:** Trigger the metered sheet for a catalogue download and for an HF probe; compare the three strings in the two sheets.

### COPY-08 — Counted nouns bypass the project's own plural rule (≤60)
**Severity:** P2 · **Confidence:** high · **Effort:** S
**Where:** `feature/chat/src/main/res/values/strings.xml:40` ("%1$d رسالة"), `feature/privacy/src/main/res/values/strings.xml:10` ("%1$d طلب"), `:12` ("%1$d بلاغ"); the rule is written in `DESIGN.md:504` ("counted copy as `<plurals>` with every Arabic form").
**What happens now:** Conversation rows and the privacy dashboard print "2 رسالة", "3 رسالة", "2 طلب" — grammatically wrong Arabic for 2 and 3–10, while other counts in the same app already use six-form `<plurals>` (`app/.../strings.xml:11-18`, :249-255).
**Why it hurts:** Arabic-first is the brand's differentiator; a wrong dual/plural on every row is the kind of error native readers notice immediately and reads as machine translation.
**Fix:** Convert the three strings to `<plurals>` with zero/one/two/few/many/other and keep the counts.
**Verify:** Create two and three conversations; read the row meta ("2 رسالة" should be "رسالتان").

### COPY-09 — Digits, units and percent signs switch style mid-app (≤60)
**Severity:** P2 · **Confidence:** high · **Effort:** M
**Where:** `feature/models/src/main/res/values/strings.xml:33` ("%1$d٪" Arabic percent) vs `app/src/main/res/values/strings.xml:319` ("%1$d%%"); units: `feature/models/.../strings.xml:106` ("219 م.ب … 2 غ.ب"), `feature/models/.../hf_strings.xml:58,60,62` ("12 م.ب"), `app/.../media_strings.xml:16,85,95` ("50 ميجابايت", "10 دقائق"), `app/.../strings.xml:304` ("%1$d ميغابايت"); Arabic-Indic digits only in `feature/chat/.../strings.xml:34` ("٤") and `:102` ("٥٠").
**What happens now:** Sizes are "م.ب/غ.ب" in one surface and spelled "ميغابايت/جيجابايت" in another; percent is "٪" once and "%%" elsewhere; two strings use Arabic-Indic digits while everything else is Western.
**Why it hurts:** For a non-technical user "12 م.ب" is an unknown abbreviation, and mixed conventions undermine the calm, precise voice the brand commits to.
**Fix:** One rule: Western digits, sized units spelled in full on first use ("12 ميغابايت"), abbreviations only in dense tables; unify percent to "%%".
**Verify:** Compare a catalogue row, an HF probe sheet, and the media import note side by side.

### COPY-10 — The assistant is called "الوكيل" only in the media shelf (≤60)
**Severity:** P2 · **Confidence:** high · **Effort:** S
**Where:** `app/src/main/res/values/media_strings.xml:40,42,50,51,52,74` ("لا يفهرسها الوكيل", "قراءة الوكيل", "مفهرس · يقرؤه الوكيل", "احفظه ليقرأه الوكيل"); everywhere else the actor is "المساعد" or "النموذج" (`feature/chat/.../strings.xml:13,118`; `app/.../strings.xml:266,264`). `docs/30-arabic-terminology.md` has no glossary entry for "agent".
**What happens now:** The media screen invents "الوكيل" (a word that also means attorney/deputy) for the same assistant the rest of the app calls "المساعد".
**Why it hurts:** One concept now has two names on adjacent screens; the user must infer they are the same actor, and the term is not in the shipped terminology law.
**Fix:** Use "المساعد" in all five media strings, or add "الوكيل" to docs/30 with a single definition and apply it everywhere.
**Verify:** Open the media shelf and the chat error/gate copy; collect the nouns used for the model's actor.

### COPY-11 — The same fit verdict is named differently in two sheets (≤60)
**Severity:** P2 · **Confidence:** high · **Effort:** S
**Where:** `feature/models/src/main/res/values/hf_strings.xml:88,90` ("يعمل بضيق", "غير مؤكَّد") vs `app/src/main/res/values/strings.xml:291,293` ("يعمل بصعوبة", "غير متحقَّق").
**What happens now:** The catalogue and the Hugging Face sheet show the same four-state device verdict with two different vocabularies; the EN side is likewise "Runs tightly" vs "Tight".
**Why it hurts:** The verdict is the product's honesty mechanism; renaming it between doors makes users think they are different judgements.
**Fix:** Put the four labels in one shared resource set and render the same words in both doors.
**Verify:** Compare a catalogue row's verdict chip with an HF repo file's verdict card.

### COPY-12 — Downloads note implies an automatic resume the code forbids (≤60)
**Severity:** P2 · **Confidence:** medium · **Effort:** S
**Where:** `app/src/main/res/values/strings.xml:282` (`library_download_note`: "تنزيل واحد في كل مرة. تُتابع الملفات من حيث توقفت بعد عودة التطبيق."); actual: `docs/32-ux-rework-build-record.md:41` ("never auto-resumes after process death (ACTIVE → PAUSED on load)"), manual action "`متابعة`" `app/.../strings.xml:275`.
**What happens now:** After a restart the row sits paused until the user taps متابعة, but the note reads as if the file continues by itself when the app returns.
**Why it hurts:** A user who checked the note and walked away finds a paused download and lost data/time; the note erodes the honest-download claim.
**Fix:** "تُستأنف من حيث توقفت بلمسة «متابعة» بعد إعادة فتح التطبيق." 
**Verify:** Start a download → force-stop → reopen Library → downloads: row is "متوقف مؤقتًا" and needs متابعة (per docs/32:41).

### COPY-13 — Error says "switch engines" but no engine switch exists (≤60)
**Severity:** P2 · **Confidence:** high · **Effort:** S
**Where:** `feature/chat/src/main/res/values/strings.xml:17` ("أعد المحاولة أو بدّل المحرّك"), `values-en/strings.xml:17`; engine provider is fixed by data: `app/src/main/assets/config/app.json:25` (`"provider": "llamacpp"`), `AppComposition.kt:303-312`; no UI writes the provider (only read in `resolveEnginePlugin`).
**What happens now:** The generic engine failure offers an action the UI cannot perform.
**Why it hurts:** An unactionable instruction in an error is noise at the moment the user is already stuck.
**Fix:** "أعد المحاولة" only, plus a secondary "افتح المكتبة" when no model is loaded.
**Verify:** Trigger `error_engine` (e.g. load failure), then look for any engine/provider switch in Settings, Library or the chip sheet.

### COPY-14 — Probe diagnostics expose HTTP internals to a non-technical user (≤60)
**Severity:** P3 · **Confidence:** high · **Effort:** M
**Where:** `feature/models/src/main/res/values/hf_strings.xml:72-73` ("استُخدم طلب Range…", "استجاب الخادم بالملف من أوله (200) بدل مقطع (206)").
**What happens now:** The probe result sheet explains redirects/status codes 200/206 and "Range request" in Arabic prose.
**Why it hurts:** It is developer diagnostics on a consumer decision screen; the same sheet already has a plain "قراءة جزئية داخل حدّ 12 م.ب" that says what matters.
**Fix:** Keep one plain sentence ("قُرئ جزء صغير فقط من الملف") and move the codes into the advanced log.
**Verify:** Probe an HF file whose server answers 200; read the note card.

### COPY-15 — "In this build" is developer jargon in a user error (≤60)
**Severity:** P3 · **Confidence:** high · **Effort:** S
**Where:** `app/src/main/res/values/media_strings.xml:92` (`media_reason_no_transcriber`: "محرّك التفريغ غير متاح في هذا البناء.").
**What happens now:** A user whose audio cannot be transcribed is told about "this build".
**Why it hurts:** "البناء" means nothing outside engineering; the honest meaning is "this version does not include the transcription engine".
**Fix:** "التفريغ غير متاح في هذا الإصدار." 
**Verify:** Remove the Whisper engine (or run media import on a build without it) and read the failure line.

### COPY-16 — Three stale "new chat deletes this conversation" strings remain (≤60)
**Severity:** P3 · **Confidence:** high · **Effort:** S
**Where:** `feature/chat/src/main/res/values/strings.xml:22-24` (`new_chat_title/body/confirm`, "ستُحذف هذه المحادثة من هذا الجهاز. لا يمكن التراجع."); no Kotlin reference anywhere (grep of `new_chat_title|new_chat_body|new_chat_confirm` finds only the resource definitions); actual behaviour keeps transcripts: `feature/chat/src/main/java/app/sirrak/feature/chat/ChatStore.kt:628-635` ("keeping the current transcript in the library").
**What happens now:** The original destructive-dialog copy survives in resources even though the 1.1.0 new-chat button never destroys a conversation.
**Why it hurts:** Dead copy is a trap for the next change: re-wiring the dialog would reintroduce a false deletion warning.
**Fix:** Delete the three strings (or rewrite them if a dialog is re-introduced).
**Verify:** `grep -r "new_chat_title\|new_chat_body" --include=*.kt` returns nothing.

### COPY-17 — Media segment still says images/audio are "in progress" (≤60)
**Severity:** P3 · **Confidence:** medium · **Effort:** S
**Where:** `app/src/main/res/values/strings.xml:312` (`library_media_soon`: "الصور والصوت قيد التنفيذ…") vs shipped Media V1: `app/src/main/java/app/sirrak/LibraryScreen.kt:482-507` renders the real `MediaLibrarySection` with documents/images/audio and `media_library_documents_note`.
**What happens now:** With the media service present the "coming soon" card is not rendered; if the host ever fails to provide the service the user is told the feature is being built although it shipped.
**Why it hurts:** A stale "coming soon" is exactly the partial-feature promise docs/32 warns against; it also contradicts the Media rows in onboarding/whats-new.
**Fix:** Delete `library_media_soon` or change the fallback to an honest error ("تعذّر تحميل الوسائط").
**Verify:** Force the Library's media service to null (or inspect `LibraryScreen.kt` around 482-507); the "soon" branch is the only renderer of this string.

### COPY-18 — About shows the raw flavour id as a value (≤60)
**Severity:** P3 · **Confidence:** high · **Effort:** S
**Where:** `app/src/main/java/app/sirrak/AboutPlugin.kt:76-79` renders `BuildConfig.STORE` ("libre"/"play") under the localised label "قناة التوزيع"; the value itself is never localised or explained.
**What happens now:** The Arabic About page shows a Latin build-flavour token next to a version number.
**Why it hurts:** It is an English developer token where the user expects "إصدار F-Droid" / "إصدار المتجر"; "libre" is meaningless to the target user.
**Fix:** Map the flavour to two localised strings (e.g. "نسخة مجانية (F-Droid)" / "نسخة المتجر") and keep the raw id out of the UI.
**Verify:** About → read the "قناة التوزيع" row in the debug build ("libre").

### COPY-19 — Same action, two names; context terminology drifts (≤60)
**Severity:** P3 · **Confidence:** high · **Effort:** S
**Where:** unload: `app/src/main/res/values/strings.xml:54` ("إلغاء تحميل النموذج") vs `:340` ("أفرغ من الذاكرة"); context: `:246` ("وطلب السياق") vs `:248` ("نافذة السياق") and docs/30's "السياق/نافذة السياق".
**What happens now:** The same engine action is two different buttons in two surfaces, and "طلب السياق" (requesting context) is used for the context-window setting.
**Why it hurts:** Two names for one control force the user to re-learn the interface between the chip sheet and the model page.
**Fix:** One label each ("أفرغ من الذاكرة" for the model chip, "إعدادات نافذة السياق" for the page).
**Verify:** Compare the chip passport's unload button with the Library model page's unload row.

### COPY-20 — English reads as translated Arabic in several key lines (≤60)
**Severity:** P3 · **Confidence:** medium · **Effort:** S
**Where:** `feature/chat/src/main/res/values-en/strings.xml:13` ("Your own assistant, kept on your device." — "kept" is unnatural for software), `:15` ("Try asking"), `:29` ("tok/s" where the AR is "رمز/ث"); `feature/models/src/main/res/values/hf_strings.xml:88` ("Runs tightly").
**What happens now:** The English voice alternates between idiomatic and literal ("kept on your device" translates "لا يغادره" word for word); units use opaque abbreviations.
**Why it hurts:** English is the secondary locale but still shipped; the brand voice ("calm, precise, honest") is lost on the strings a reviewer reads first.
**Fix:** Rewrite the EN promise as "Your own assistant, running on your device." and spell out "tokens per second" on first use.
**Verify:** Switch the app to English and read the chat empty state and the token meter.

---

# Part B — Claims vs reality

### CLAIM-01 — About says the app sends nothing to any server (≤60)
**Severity:** P1 · **Confidence:** high · **Effort:** S
**Where:** `app/src/main/res/values/strings.xml:10` (`privacy_statement`: "لا يرسل سِرِّك أي بيانات إلى أي خادم. المحادثات والملفات تبقى على جهازك."), EN `app/src/main/res/values-en/strings.xml:14` ("Sirrak sends nothing to any server."), rendered `app/src/main/java/app/sirrak/AboutPlugin.kt:91-93`; contradicting shipped code: 3 declared endpoints `app/src/main/assets/config/app.json:44-69`, network audit records `app/src/main/java/app/sirrak/HfPlugin.kt:79-89` and `app/src/main/java/app/sirrak/HttpDownloadTransport.kt:86`, policy states three features `PRIVACY.md:11-13,44-58`.
**What happens now:** The About screen — reachable from Settings and from the same app that lists "Hugging Face" in its Library — states an absolute that the app's own Privacy page disproves.
**Why it hurts:** Privacy is the product's entire differentiation (PRODUCT.md:75); a false absolute on the About screen turns an honest disclosure elsewhere into something the user cannot trust.
**Fix:** "لا يرسل سِرِّك محادثاتك أو ملفاتك إلى أي خادم. الطلبات الشبكية الثلاثة (تنزيل، بحث Hugging Face، فحص ترويسة) مذكورة في الخصوصية والشبكة." and mirror in EN.
**Verify:** About → read the privacy note, then Settings → الخصوصية → سجل الشبكة / سياسة الخصوصية.

### CLAIM-02 — English Play listing says the only network request is a download (≤60)
**Severity:** P1 · **Confidence:** high · **Effort:** S
**Where:** `store/listing/en/full_description.txt:1` ("nothing is sent to a server") and `:27` ("The only network request is a model download you start yourself."); Arabic listing is narrower at `:1` ("ولا تُرسل محادثاتك إلى أي خادم") but still says at `:26` "لا يوجد خادم تُرسَل إليه محادثاتك أو مستنداتك". 1.1.0 ships three features: `app.json:44-69`, `PRIVACY.md:11-13`, `feature/models/src/main/res/values/hf_strings.xml` (search/probe UI).
**What happens now:** A store listing for 1.1.0 (its What's new announces the HF doorway, `store/listing/en/whats_new.txt:4`) claims HF does not exist. Any reviewer or user who opens the app finds the opposite.
**Why it hurts:** This is the copy that would be published verbatim; a false network claim in a store listing is both a trust and a policy exposure, and it is contradicted by the app's own 1.1.0 feature list.
**Fix:** "The only network features are three you start: a model download, a Hugging Face search and a 12 MB header probe. No account, no telemetry." and align AR.
**Verify:** Search the listing for "only network request" and compare against the Library's "Hugging Face" row in the shipped build.

### CLAIM-03 — Data-safety answers describe the pre-1.1.0 app (≤60)
**Severity:** P1 · **Confidence:** high · **Effort:** M
**Where:** `store/play/data-safety.md:10` ("Does your app collect or share … **No**"), `:11` ("The only network request is a user-initiated model download served by Hugging Face"), `:3` ("These answers match the shipped code"); vs 1.1.0: HF search sends the user's typed query and repo names off-device (`app/src/main/java/app/sirrak/HfPlugin.kt:79-89`, `feature/models/src/main/java/app/sirrak/feature/models/HfBrowseSession.kt`), probe reads bytes from HF (`hf_probe` endpoint, `app.json:61-68`), policy lists all three (`PRIVACY.md:45-62`).
**What happens now:** The Play Data-safety draft asserts no data leaves the device and only a download ever happens; search queries are user-entered text transmitted to a third party.
**Why it hurts:** Play's data-safety declaration is a legal statement; submitting this answer would misdeclare the app, and the same document's own claim ("match the shipped code") is false.
**Fix:** Re-derive the answers for three user-initiated requests: no collection, but disclose "search terms sent to Hugging Face when you search" as app activity handled transiently; update permissions row for three features.
**Verify:** Diff `data-safety.md` against `app.json:44-69` and `PRIVACY.md:44-58`; the "only network request is a model download" line fails.

### CLAIM-04 — README says the name means "your companion" (≤60)
**Severity:** P2 · **Confidence:** high · **Effort:** S
**Where:** `README.md:33` ("Sirrak (Arabic for *your companion*)"); `PRODUCT.md:23` says "سِرِّك, 'your secret'"; the word is سِرّ (secret) + ك.
**What happens now:** The repository's primary English README mistranslates the product name against its own product document.
**Why it hurts:** The name's meaning is the brand story ("your secret"); a wrong etymology in the first paragraph is a credibility hit for an Arabic-first product.
**Fix:** "Sirrak (سِرِّك, 'your secret')".
**Verify:** Grep the repo for "companion" and "your secret"; the two public statements disagree.

### CLAIM-05 — "There is no server to send anything to" contradicts its own disclosure (≤60)
**Severity:** P2 · **Confidence:** high · **Effort:** S
**Where:** `README.md:67` and `README.ar.md:66` ("There is no server to send anything to." / "لا يوجد خادم تُرسل إليه شيئًا."), `website/content/en.json:207`/ `ar.json:206`; contradicted 30 lines earlier in the same README: `README.md:34-37` ("a model download, a Hugging Face catalogue search or repository browse, or a bounded GGUF header probe").
**What happens now:** The same document says both "no request except three" and "no server at all".
**Why it hurts:** Readers remember absolutes; a self-contradicting README gives a critic the exact quote to disqualify every other claim.
**Fix:** "There is no server that holds your chats or documents." 
**Verify:** Read README.md lines 33-37 and 67 in sequence; the two sentences cannot both be true.

### CLAIM-06 — Install docs and store listing offer a demo engine users cannot get (≤60)
**Severity:** P2 · **Confidence:** high · **Effort:** S
**Where:** `README.md:92` ("start with the built-in demo engine to see the interface"), `README.ar.md:91`, `store/listing/en/full_description.txt:22`, `store/listing/ar/full_description.txt:22`; reality: `app.json:25` selects `llamacpp`, `AppComposition.kt:303-312` only builds the demo engine for a `demo`/unknown provider, no UI changes the provider, and the demo note in onboarding is COPY-01.
**What happens now:** Every install surface promises a demo path; the shipped build has none, and following it produces the NO_MODEL error.
**Why it hurts:** It is the documented first-run path for people who have not downloaded a 2 GB model — the exact audience least able to recover.
**Fix:** Remove the sentence from README/README.ar/store listing, or ship the demo engine as a real selectable fallback and keep the copy.
**Verify:** Follow README install steps 1-3 in the candidate APK; step 3's demo promise cannot be fulfilled.

### CLAIM-07 — Policy says once-only media is deleted "when its session ends" (≤60)
**Severity:** P2 · **Confidence:** high · **Effort:** S
**Where:** `PRIVACY.md:28-30` and `:94-95` ("a once-only item is never indexed and is deleted when its session ends"); actual: deleted at app close (`app/src/main/res/values/media_strings.xml:79`, `docs/32-ux-rework-build-record.md:117-118`, `README.md:243-244`).
**What happens now:** The retention section of the privacy policy never says what "session" means and nowhere states the app-close deletion that the in-app note admits.
**Why it hurts:** Retention language is the part of a privacy policy users rely on; ambiguity here is indistinguishable from an inaccurate claim.
**Fix:** "delivered at app close (the app does not yet tie media to a conversation)".
**Verify:** Read PRIVACY.md retention and the in-app `media_once_note` side by side.

### CLAIM-08 — Policy contradicts itself on whether a token is sent (≤60)
**Severity:** P2 · **Confidence:** high · **Effort:** S
**Where:** `PRIVACY.md:50-53` says a download "carries … an `Authorization: Bearer` header with that token" (only if the user typed one), then `:59-61` says "None of them carries chat text, prompt, document, media, report, contact, location, device identifier, advertising identifier or account credential."; also `:67-68`.
**What happens now:** The "what leaves your device" list asserts no account credential is transmitted, two paragraphs after describing the token transmission.
**Why it hurts:** The policy is the document a skeptical user or reviewer checks line by line; an internal contradiction about credentials is the worst place to have one.
**Fix:** "No request carries your chat, documents or identifiers; a download carries your Hugging Face token only if you typed one for a gated model."
**Verify:** Read PRIVACY.md lines 48-61 as one passage.

### CLAIM-09 — PRODUCT.md lists a v1.1 scope that 1.1.0 does not ship (≤60)
**Severity:** P2 · **Confidence:** high · **Effort:** M
**Where:** `PRODUCT.md:39-40` says "v1.1: Arabic TTS, hands-free voice mode, full personas, benchmark, default-assistant role / QS tile / share target, skill bundles…"; the shipped 1.1.0 is the UX rework (`PRODUCT.md:59`, `docs/32-ux-rework-build-record.md`) and none of those v1.1 features exists (no TTS/voice-mode/personas/QSTile in `app.json` features list `app.json:4-14`, no such settings doors).
**What happens now:** The product document's version map is stale by one release, presenting planned scope as the 1.1 milestone.
**Why it hurts:** Contributors, reviewers and the council read PRODUCT.md as the source of truth; a wrong version map causes scope decisions based on a release that does not exist.
**Fix:** Move that line to "v1.2 (planned)" and record the actual 1.1.0 scope (the UX rework) in its place.
**Verify:** Search the built app for TTS, hands-free mode, personas, benchmark and QS tile; `grep -r "Tts\|QSTile" app core feature` returns no shipped UI.

### CLAIM-10 — README overstates the probe as "8–12 MB" (≤60)
**Severity:** P3 · **Confidence:** high · **Effort:** S
**Where:** `README.md:78` and `README.ar.md:77` ("an 8–12 MB GGUF header probe"); the probe is a fixed cap of 12 MB: `app.json:64` ("at most 12 MB"), `app/src/main/res/values/hf_strings.xml:58` ("حتى 12 م.ب"), `core/models/src/main/java/app/sirrak/core/models/GgufProbe.kt` (MAX_BYTES = 12 × 1024 × 1024).
**What happens now:** The docs describe a variable size range that does not exist in code; the user-facing strings say 12 MB.
**Why it hurts:** Small numeric drift in a privacy claim invites readers to distrust the precise parts (token handling, audit log).
**Fix:** "a 12 MB-capped GGUF header probe" in both READMEs.
**Verify:** Read the probe cap constant and the HF string; only 12 MB is enforced.

### CLAIM-11 — "Offline is the default, not a mode" is not the shipped default (≤60)
**Severity:** P3 · **Confidence:** high · **Effort:** S
**Where:** `PRODUCT.md:90` (principle 1: "Offline is the default, not a mode."), vs `app.json:39` ("offlineByDefault": false) and `PRIVACY.md:73-74` ("The switch is off by default").
**What happens now:** The product principle states offline is the default while the shipped switch ships off, so the app is allowed to make its three network calls without an opt-in.
**Why it hurts:** The principle is exported as the product's public promise; overnight it reads as marketing rather than design.
**Fix:** Either set "offlineByDefault": true in `app.json` (then onboarding must still explain model downloads) or reword the principle to "Offline is one switch away, and nothing needs the network to chat."
**Verify:** Fresh install → Privacy page: offline-only switch is off, matching `app.json:39`.

### CLAIM-12 — Store description still sells the pre-1.1.0 interface (≤60)
**Severity:** P3 · **Confidence:** high · **Effort:** M
**Where:** `store/listing/en/full_description.txt:11-17` and `store/listing/ar/full_description.txt:11-17` describe "Manage models: … Hugging Face import" and Settings-based tuning, with no mention of the three-segment Library, Media segment or six-door Settings; `store/listing/en/whats_new.txt:1-7` announces all of them for 1.1.0.
**What happens now:** The What's new block advertises the 1.1.0 rework while the body copy still describes rc2's navigation (and the network claim in CLAIM-02 is from that older world).
**Why it hurts:** A listing whose body contradicts its own release notes reads as unmaintained; users cannot tell which app the description describes.
**Fix:** Rewrite "WHAT YOU CAN DO" around Library (Models/Downloads/Media), Settings doors and the HF doorway, then re-run `scripts/validate-store.ps1`.
**Verify:** Read whats_new and full_description in the same locale; the navigation described differs between them.

### CLAIM-13 — Store README says the policy URL is unfilled (≤60)
**Severity:** P3 · **Confidence:** high · **Effort:** S
**Where:** `store/README.md:79-81` ("Host PRIVACY.md and SECURITY.md and fill `privacy.policyUrl` / `privacy.securityUrl` in app.json (the UI truthfully says 'not published yet' today)"); actual: URLs are filled `app.json:40-41` and rendered as live rows `feature/privacy/src/main/java/app/sirrak/feature/privacy/PrivacySection.kt:69-87`; README.md:43-50 also states the policies are published.
**What happens now:** The launch checklist in `store/README.md` lists work that is already done and describes a UI state that no longer exists.
**Why it hurts:** "Still needed before publishing" is the list a release engineer follows; stale items waste a launch pass and undermine docs-as-truth.
**Fix:** Remove the policy item, keep the F-Droid TODO/Play key items.
**Verify:** Open Settings → الخصوصية: the privacy and security rows are live links (PrivacySection.kt:69-87), not "not published yet".

### CLAIM-14 — Website says "no background calls" while downloads run backgrounded (≤60)
**Severity:** P3 · **Confidence:** medium · **Effort:** S
**Where:** `website/content/en.json:125` ("No background calls: every network request is one you start"); the app runs user-started downloads in a dataSync foreground service with a notification (`app/src/main/res/values/strings.xml:231-238`; `PRIVACY.md:83-84`; `docs/32-ux-rework-build-record.md:41`).
**What happens now:** The marketing line is strictly true about initiation but reads as "the app makes no network calls while backgrounded", which is false during a download.
**Why it hurts:** A visitor can disprove it with a progress notification on the home screen; same honesty discount as CLAIM-05.
**Fix:** "No calls you did not start. A download you start keeps going in the background until it finishes or you pause it."
**Verify:** Start a download on the site-linked build, background the app, watch the notification and the traffic.

---

## Not verified (stated honestly)

- **Screenshot-cited copy:** 29 PNGs and `build/ux-audit/EVIDENCE.md` existed at write time but were not inspected in the time-box, so no finding cites a rendered screen. Questions that need pixels: truncated labels at 200 % text (esp. German-length EN expansions), RTL arrow direction in "الإعدادات ← الصوت" (`media_strings.xml:80`), and whether `library_media_soon` is reachable in the installed build.
- **Runtime behaviour:** resume-after-process-death and offline-toggle effects on queue rows are taken from docs/32:41 and string/code evidence, not from a device run (device access belongs to journey-runner).
- **Quality-gate claims:** "744 JVM tests", "24/24 Arabic eval", "79/79 store checks", "lint 0 errors", "screenshot distance 0" (README.md:222-231) were not executed or re-measured; treat as unverified, not false.
- **Website policy pages** (`website/content/policies.json`) and store graphics (PNG/brand.json copy) were not audited; the website landing content in `website/content/en.json:47` actually discloses all three network features correctly (a strength worth keeping).
- **Model preset/demo JSON assets** (`app/src/main/assets/content/*.json`) were not read; any user-visible Arabic there is outside this report.
