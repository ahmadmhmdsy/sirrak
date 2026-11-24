# 30 — Arabic terminology: why "لا طراز" was wrong, and the glossary that replaces it (Qiyas Council, Mode C)

Date: 2026-11-24 · Status: source change on **main**; reaches phones in the next signed build (**1.0.0-rc2**).

The owner asked what "لا طراز" meant, whether it meant "no model loaded", and asked for a council plus dictionary
research so the product stops using Arabic technical words that are unknown or easy to misunderstand. This record keeps
the answer, the evidence, the council ruling, the glossary that now governs Arabic copy, and the verification.
The council follows the Qiyas Council skill, Mode C (merged): frame and ground first, then Qiyas cards, an adversarial
transcript, named objections, and a silent Maestro who rules at the end.

## 1. The short answer

**"لا طراز" was the engine-status string for "no model" — the state where no model is selected or loaded.** It was not
the load-failure message. The English string for the same state is engine_no_model = "No model", and the load-failure
string is engine_status_failed (now "تعذّر تحميل النموذج", English "Could not load the model"). The status chip now
reads **"لا يوجد نموذج"** = "there is no model".

The real problem was the word **طراز**. Arabic dictionaries define طراز as **pattern, shape, form, style, make** — the
sense used for a car make or an architectural style ("سيارة من الطراز الحديث", "طراز معماري"). It is not the Arabic term
for a scientific or machine-learning model; that is **نموذج** (a prototype/sample/model), the word used in "نموذج رياضي"
and "نموذج لغوي كبير". So "لا طراز" read to a normal reader as "no make/style", not "no model".

The same audit found five more calques worth fixing, all corrected in this change: "مشروع الرؤية" (a projector is a
projection layer, not a project) → **ملحق الرؤية (mmproj)**; "التحويل الكمي" → **التكميم**; "الملفات المعلّقة" (suspended
files?) → **الملفات غير المرتبطة**; "معمارية المعالج" for ABI → **بنية المعالج (ABI)**; "المشفّر / فكّ التشفير" for
encoder/decoder (which reads as encryption/decryption) → **المُرمِّز (encoder) / مُفكِّك الترميز (decoder)**. Prompt-family
copy was also inconsistent ("مطالبة النظام" vs "موجّه المحادثة") and is now one plain family: **تعليمات النظام /
تعليمات المحادثة / التعليمات العامة / التعليمات النهائية**.

## 2. Method header — the seated council

Mode C (merged): frame and ground → Qiyas cards → adversarial transcript → objection catalog → Maestro verdict.
Language of the session: English (the owner's request); product copy: Arabic.

| Seat | Qiyas duty | Signature question |
|---|---|---|
| Expert — Arabic terminologist (الخبير المتخصص في المصطلح) | Takhrij al-Manat (تخريج المناط) — extract the real cause from dictionary use and precedents | What does the dictionary actually say, and what will an ordinary reader hear? |
| Engineer (المهندس) | Al-Far' (الفرع) — build the change; every proposal needs a precedent and a cause | Where is the string, how many places, and what does it break? |
| Devil's Advocate (محامي الشيطان) | Al-Qawadih (القوادح) — attack the cause, not the wording | Where does the cause break? |
| Naive User (الزبون الغبي) | Tahqiq al-Manat (تحقيق المناط) — verify the cause exists in the real user | What does this look like to me? |
| Maestro (المايسترو) | silent judge, speaks only in section 7 | — |

Dynamic roles (0–3, seated for dimensions that could kill this change):

Role: Localization Reviewer (المراجع اللغوي)
Mandate: cross-locale consistency and vendor-reference grounding.
Signature question: "Would an Arabic-first reader read this as a different word?"
Qiyas tool: Material difference (الفارق المؤثر).
Red line: never accepts a machine-translation artifact as precedent.

Role: Arabic QA Reviewer (مدقق الجودة العربية)
Mandate: protect established technical terms and the test gates.
Signature question: "Is this already the standard word in the product and the references?"
Qiyas tool: Invalid premise (فساد الاعتبار).
Red line: no churn of established vocabulary; the build and tests stay green.

Role: Owner Advocate (محامي المالك)
Mandate: product continuity and honesty toward the installed rc1 and frozen evidence.
Signature question: "Does this break the build, the screenshots, or the benchmark?"
Qiyas tool: Counter-case (النقض).
Red line: no rewriting frozen eval fixtures or history, and no claim that rc1 already shows the fix.

## 3. Step Zero — frame, constraints, grounding

### 3.1 Frame

Goal: decide the Arabic word families for model, prompt, projector and related calques, then normalize every
user-facing Arabic source in one change. Done means: zero ambiguous terms in app/site/store/README copy; the dictionary
evidence and council ruling recorded; site/store validators and the JVM suite green; the owner can read the status chip
without asking what it means.

### 3.2 Hard constraints

- Arabic-first Android app: a general Arabic reader must understand every label without English.
- The app is already bilingual; the Arabic register can be plain even where the English UI keeps technical words.
- Technical tokens stay Latin (GGUF, mmproj, llama.cpp, Qwen, Hugging Face, SHA-256, Wi-Fi).
- Established and working vocabulary must not churn (تضمين، تكميم، سياق، رموز، استدلال، بثّ، قالب).
- The frozen Arabic RAG evaluation fixture and the historical docs are evidence; they are not rewritten.
- rc1 is installed on the owner's phone; source fixes are invisible until the next signed build.
- Multi-gate repository: JVM 567 tests / 9 modules, site 225 checks, store 79 checks, plus resource compilation.

### 3.3 Grounding: dictionary and usage evidence (fetched 2026-11-24)

| Source | What it says | Consequence |
|---|---|---|
| [عرب ديكت — طراز](https://www.arabdict.com/ar/%D8%B9%D8%B1%D8%A8%D9%8A-%D8%B9%D8%B1%D8%A8%D9%8A/%D8%B7%D8%B1%D8%A7%D8%B2), quoting **معجم اللغة العربية المعاصرة** | "طِرَاز: ج أطرِزة وطُرُز: نمط، شكل، هيئة… «هذه السَّيارة من الطِّراز الحديث»… طراز معماريّ: طابع معماريّ… مِن الطِّراز الأوّل: من أحسن الأنواع" | طراز = style/pattern/make; the car-model sense, not an ML model. |
| Same page, quoting **معجم لغة الفقهاء** | "الطِّراز: الشكل، ومنه على طراز كذا، وأيضا سيارة طراز كذا — النمط" | Confirms the make/pattern register. |
| [عرب ديكت — نموذج](https://www.arabdict.com/ar/%D8%B9%D8%B1%D8%A8%D9%8A-%D8%B9%D8%B1%D8%A8%D9%8A/%D9%86%D9%85%D9%88%D8%B0%D8%AC), quoting **معجم اللغة العربية المعاصرة** | "نَموذَج: ج نَمُوذَجات ونَماذِجُ: مَن أو ما يُتّخذ مثالاً يُحتذى به… مثال يُقتدى به، أو مثال يُعمل عليه الشّيء «نموذَج صناعيّ/مُصغّر»… عَيّنة" | نموذج = model/prototype/sample, the domain sense we need. |
| [ويكيبيديا العربية — نموذج لغوي كبير](https://ar.wikipedia.org/wiki/%D9%86%D9%85%D9%88%D8%B0%D8%AC_%D9%84%D8%BA%D9%88%D9%8A_%D9%83%D8%A8%D9%8A%D8%B1) | The article for "large language model" is titled **نموذج لغوي كبير**; the body uses "نماذج" throughout | Community/encyclopedic standard for LLMs is نموذج. |
| [ويكيبيديا العربية — نموذج رياضي](https://ar.wikipedia.org/wiki/%D9%86%D9%85%D9%88%D8%B0%D8%AC_%D8%B1%D9%8A%D8%A7%D8%B6%D9%8A) | The scientific-model article is **نموذج رياضي** | The scientific sense is نموذج. |
| [ويكيبيديا العربية — طراز (توضيح)](https://ar.wikipedia.org/wiki/%D8%B7%D8%B1%D8%A7%D8%B2_(%D8%AA%D9%88%D8%B6%D9%8A%D8%AD)) | "طراز" is a disambiguation page for styles/types | No encyclopedic "AI model" sense for طراز. |
| [Microsoft Learn (ar-sa) — نماذج Foundry](https://learn.microsoft.com/ar-sa/azure/ai-services/openai/concepts/models) | Uses "كتالوج النماذج", "نماذج Foundry", "حد سياق النموذج", "نماذج الاستدلال"; one stray sentence says "جميع طرازات سلسلة GPT-4.1" | Industry Arabic copy normalizes to نموذج; the stray "طرازات" is a machine-translation leak, not a precedent. |
| [Microsoft Learn (ar-sa) — هندسة المطالبات](https://learn.microsoft.com/ar-sa/azure/ai-services/openai/concepts/prompt-engineering) | The same page mixes "الهندسة السريعة", "التوجيه", "الموجه", "المطالبة", "المحفز" | Even a vendor's Arabic page for "prompt" is machine-translated and inconsistent; we should not copy it. Use plain **تعليمات**. |

Method notes: quotes above are live-fetched dictionary/usage senses, not paraphrase. Two frequently cited sites were
unavailable at check time — almaany.com returned HTTP 403 and the Arabic Wiktionary pages returned 404 — so they are not
cited. The Wikipedia article on LLMs renders "prompt engineering" as "هندسة المطالبات"; that is a second defensible
technical rendering, but "مطالبة" also means "claim/demand" in everyday Arabic and the app already used "موجّه" on
another screen. The council chose the plain word **تعليمات** for the user-facing family.

### 3.4 What is still unverified

- No comprehension study has been run yet; the owner's own question is the first data point (n=1) and it failed for طراز.
- The vendor pages are machine-translated, so they are used only for direction (which word Arabic copy converges on),
  never as a style guide.
- The dictionary senses are quoted through arabdict pages; the print dictionaries themselves were not re-verified.

## 4. Qiyas cards

### Card 1 — the AI-model word

- **Al-Far' (proposal):** one family, **نموذج / نماذج**, for every AI model in Arabic copy.
- **Al-Asl (precedent):** Arabic Wikipedia titles "نموذج لغوي كبير" and "نموذج رياضي"; Microsoft ar-sa model page uses
  "كتالوج النماذج" and "حد سياق النموذج".
- **Al-'Illa (cause):** in Arabic a scientific or ML model is a نموذج (prototype/sample/model); that is the word readers
  meet for models, while طراز carries the car-make/style sense and collides with "phone model".
- **Tanqih (stripped noise):** the car/architectural sense of طراز, and the vendor's stray "طرازات GPT-4.1" sentence
  (machine-translation noise).
- **Conditions:** status labels are short and context-free, so the word alone must be unambiguous; the app stays
  bilingual and keeps Latin hints for technical tokens.

### Card 2 — the vision projector

- **Al-Far':** **ملحق الرؤية (mmproj)**.
- **Al-Asl:** the file and English docs call it mmproj / vision projector — a separate projection layer that maps image
  embeddings into the text model's space; the app's own M9 design (docs/23) already treats it as a separate file.
- **Al-'Illa:** it is an add-on projection layer, not a "project".
- **Tanqih:** don't copy "مشروع" (project) from a literal translation; keep the Latin token mmproj.
- **Conditions:** one mmproj file is attached per model; the label must not imply a second app or a feature branch.

### Card 3 — the prompt family

- **Al-Far':** **تعليمات** family — تعليمات النظام، تعليمات المحادثة، التعليمات العامة، التعليمات النهائية.
- **Al-Asl:** the functional concept is "instructions given to the model"; the English UI says system prompt, and the
  Arabic references disagree on the label (Wikipedia "هندسة المطالبات"; a vendor page mixes five words).
- **Al-'Illa:** the user's job is to write instructions; "مطالبة" reads as claim/demand and "موجّه" as guide/presenter.
- **Tanqih:** drop the vendor inconsistency and the "هندسة المطالبات" title (kept as a reference only); keep "قالب" for
  template, which is already correct.
- **Conditions:** the label appears in settings, the chat sheet and a preview, and the text field is multi-line
  instruction text — one family must fit all three places.

### Card 4 — frozen terms (why they are not in scope)

- **Al-Far':** keep تضمين، تكميم، سياق، رموز، استدلال، بثّ، قالب unchanged.
- **Al-Asl:** the app already uses these consistently and they are standard Arabic renderings.
- **Al-'Illa:** churn costs translation review, screenshots and user memory without fixing an ambiguity.
- **Tanqih:** does not apply; the proposal is the status quo.
- **Conditions:** keep the Latin hint in parentheses where the term is technical (e.g. tokens, encoder, decoder).

## 5. Transcript (compressed deliberation)

**Expert:** The dictionaries are unambiguous. طراز is style/make ("سيارة من الطراز الحديث", "طراز معماري"); نموذج is
the model/prototype/sample word used in "نموذج رياضي" and "نموذج لغوي كبير". "لا طراز" is a category error in an AI app.
Global invariant: a user-facing Arabic word must stand on its own; technical tokens may stay Latin.

**Engineer:** Root cause is a translation choice, not a missing string. engine_no_model says "لا طراز" while other
screens already say "النماذج" — the app contradicts itself. Proposed fix at first: rename only the status chip.
Revision after the Naive User: no — the same word sits in 158 places across app strings, READMEs, website and store
copy, so a chip-only fix would leave the app self-contradictory. I revise to one global normalization.

**Naive User (fatal flaw):** "I read 'لا طراز' as the phone model, or as 'no style'. I would not know it means the AI
model. And the models screen says نموذج, so the app is speaking two languages at once." The chip-only plan is dead.

**Devil's Advocate (Naqd):** "Microsoft itself wrote 'طرازات سلسلة GPT-4.1' on an AI page; why is طراز wrong?"
**Localization Reviewer (Material difference):** because that is one machine-translated sentence on a page that
otherwise says نماذج; the exception does not define the standard. The difference touches the cause: a reader who knows
the dictionary sense will read "make/style", not "model".

**Devil's Advocate (Material difference):** "طراز is correct Arabic for a car model, and the UI could mean 'model' the
same way." **Expert:** the object is an LLM, not a car. Using a car-make word for a language model is exactly the
ambiguity the owner reported. The objection is rejected as a material difference.

**Arabic QA Reviewer:** "Do not churn terms that already work: تضمين، تكميم، سياق، رموز، استدلال، بثّ، قالب. They are
standard and already consistent." Accepted — Card 4 is out of scope.

**Engineer (revision):** "مشروع الرؤية" is not just awkward, it is false: a projector is a projection layer, not a
project. I revise to "ملحق الرؤية (mmproj)". Also fix "التحويل الكمي" → "التكميم", "الملفات المعلّقة" → "الملفات غير
المرتبطة", the ABI label, and encoder/decoder, because "تشفير" is read as encryption.

**Owner Advocate (Counter-case):** "This must not break the app or rewrite history." Constraint accepted: no frozen
RAG fixture or historical doc rewrite; the change is source copy only, and rc1 stays honestly described as showing the
old words. The docs/30 note lists the deliberate non-changes.

**Devil's Advocate (Tahqiq manat):** "Users can infer 'model' from context." **Naive User:** "Not on a one-word status
chip — there is no context." The cause is verified absent: the label itself must carry the meaning.

**Arabic QA Reviewer (Invalid premise):** "Rename every technical word to plain Arabic." Rejected: established terms
stay; only ambiguous calques change. The final scope is six fixes plus one word family, not a dictionary purge.

**Localization Reviewer:** On "prompt" the Arabic references disagree; both "مطالبة" and "موجّه" are ambiguous in plain
Arabic. I propose تعليمات, which is accurate and already the product's job. Accepted.

**Maestro:** stop condition reached — a second pass produced no new objection; the plan visibly changed from a
chip-only edit to a global normalization, and three ideas fell (chip-only fix, keeping "مشروع الرؤية", keeping the
مطالبة/موجّه family). Ruling in section 7.

## 6. Objection catalog and answers

| Objection | Tool (Arabic) | Attack | Answer / outcome |
|---|---|---|---|
| "طراز is a real Arabic word, so it is not wrong" | Material difference (الفارق المؤثر) | The word exists, so the cause (clarity) may not break | Real word, wrong domain: style/make ≠ scientific model; the owner's confusion is the empirical proof. Fix accepted. |
| "Microsoft itself wrote طرازات on an AI page" | Counter-case (النقض) | If the precedent's authors use it, the cause is not the word | One machine-translated sentence on a page that otherwise says نماذج; the exception does not define the standard. Rejected. |
| "Users infer from context" | Non-causation / reality check (عدم التأثير / تحقيق المناط) | The cause may exist in longer prose | Status chips are context-free; the label must be unambiguous. Rejected. |
| "Rename every technical word to plain Arabic" | Invalid premise (فساد الاعتبار) | The argument assumes all technical terms are ambiguous | Established terms stay; only ambiguous calques and inconsistent prompt terms change. Rejected. |
| "A projector is project-like; مشروع is close enough" | Reality check (تحقيق المناط) | Does the cause (false meaning) actually exist? | The file is a projection layer, not a project; "ملحق" carries add-on without the false sense. Fix accepted. |
| "Prompt is مطالبة/موجّه in other products" | Reality check (تحقيق المناط) | The rendering may be standard | Renderings exist but both are ambiguous in plain Arabic; تعليمات is accurate and consistent. Fix accepted. |
| "Translations churn every year; just pick something and ship" | Second-order effect (الأثر الثاني) | The fix may itself age badly | Choose plain semantic words (نموذج، تعليمات، ملحق) over vendor calques, keep Latin tokens, and record the glossary; churn risk is reduced, not ignored. Noted as a risk in 7.5. |

## 7. Maestro verdict

### 7.1 Verdict table

| Branch | Asl (precedent) | 'Illa (cause) | Constraint from objections | Ruling |
|---|---|---|---|---|
| طراز for AI model | Dictionary usage; Wikipedia/Microsoft نماذج | Wrong domain (style/make) and collides with phone model | Must be unambiguous on a one-word status label | **Dropped** — 158 occurrences replaced by نموذج |
| نموذج / نماذج for the model | Wikipedia "نموذج لغوي كبير"; Microsoft "كتالوج النماذج" | Common scientific/ML model word | Keep Latin format tokens | **Survived** — the governing word |
| "طراز الجهاز" in the security page | Same collision | Device type, not AI model | Do not introduce a new ambiguous term | **Downgraded** to "نوع الجهاز أو المحاكي" |
| "مشروع الرؤية" | mmproj / vision projector in ML docs | Projection layer, not a project | Must not imply a second app/feature | **Dropped** — replaced by "ملحق الرؤية (mmproj)" |
| "التحويل الكمي" | App already uses "التكميم" | Same meaning, two labels | No churn beyond fixing inconsistency | **Dropped** — التكميم standardized |
| "الملفات المعلّقة" | Orphan-file concept, English "orphan files" | معلّقة = suspended, misleading | Keep the file-manager meaning clear | **Dropped** — "الملفات غير المرتبطة" |
| "معمارية المعالج" for ABI | The value is an ABI (arm64-v8a) | Architecture vs binary interface | Label must match the value shown | **Downgraded** — "بنية المعالج (ABI)"; English "CPU architecture (ABI)" |
| "المشفّر / فكّ التشفير" for encoder/decoder | English strings keep encoder/decoder | تشفير reads as encryption | Keep Latin hints | **Dropped** — "المُرمِّز (encoder) / مُفكِّك الترميز (decoder)" |
| مطالبة / موجّه for prompt | Concept: instructions given to the model | Both ambiguous in plain Arabic | One family across settings, chat, previews | **Dropped** — تعليمات family |
| Establish terms (تضمين، تكميم، سياق، رموز، استدلال، بثّ، قالب) | Already standard and consistent in-app | Consistency and user memory | No renames without ambiguity | **Kept unchanged** |
| Latin tokens (GGUF, mmproj, llama.cpp, SHA-256, Hugging Face, Wi-Fi) | Industry convention | Arabic wrappers must stay plain | Do not translate product/format names | **Kept** |
| Frozen RAG eval fixture and historical docs 23/24 | Evidence and benchmark integrity | Rewriting would falsify history | Owner Advocate red line | **Kept unchanged** |

### 7.2 Step Zero — falsification test

Cheapest test that could kill the ruling: a short comprehension check with **5 Arabic-first readers** (non-technical,
offline is fine), each shown the five new labels and asked "ماذا يعني هذا؟" without context:

1. "لا يوجد نموذج" (engine status)
2. "ملحق الرؤية (mmproj)" (vision attachment)
3. "تعليمات النظام" (system instructions field)
4. "الملفات غير المرتبطة" (orphan-file cleanup)
5. "التكميم" (quantization row)

Pass: at least **4 of 5 readers** explain each label correctly (or correctly describe the function). Fail: revise the
failing term within one iteration, then re-test.

| Result | Decision |
|---|---|
| All five labels pass | Keep the glossary as the copy standard; no further wording change before rc2. |
| One label fails for ≥2 readers | Replace that term with a descriptive phrase (e.g. "طبقة الرؤية" as a fallback for the projector label) and update the glossary + sources. |
| Two or more labels fail | The plain register is not landing; reconsider with the alternative community terms ("برومبت" for prompt) before the public launch. |
| Tests cannot be run before rc2 | Ship rc2 with the current fix and record the test as a post-launch gate; do not claim comprehension is proven. |

The owner's question is already the first data point: one reader (the owner) failed to decode "لا طراز", which is why
this change exists.

### 7.3 The solution (what ships)

1. One glossary (section 8) becomes the source of truth for Arabic copy reviews.
2. All user-facing Arabic sources normalized in one change: app strings (app, chat, voice, models), README.ar.md, the
   Arabic sample in README.md, website content (ar.json, policies.json), store listing (full description, what's new),
   and the demo chat asset.
3. Established terms and Latin tokens frozen; historical docs and the frozen RAG benchmark untouched.
4. Website regenerated from content and validated; app debug build compiles the changed resources; JVM suite green.
5. rc2 is the delivery vehicle: catalogue rows (docs/29) and this terminology fix both reach phones only in that signed
   build; store screenshots are recaptured then (rc1 screenshots remain rc1 evidence).

### 7.4 Continue / kill metrics

| Metric | Continue | Kill / revise |
|---|---|---|
| Ambiguous-term grep audit (app/site/store/README) | 0 occurrences of طراز, مطالبة, موجّه-prompt, مشروع الرؤية, التحويل الكمي, المعلّقة | Any new occurrence blocks the copy review; fix before commit |
| Site checks | 225/225 | Any failure blocks the site |
| Store checks | 79/79 | Any failure blocks the release assets |
| JVM suite | 567 tests, 0 failures, 0 errors across 9 modules | Any failure blocks the build |
| Comprehension test | ≥4/5 readers per label | <4/5 for one label → one revision; two labels → reconsider register |
| Decision date | during the rc2 build/capture window | after the test, before public launch |

### 7.5 Risks and mitigations

- **rc1 and the store screenshots still show the old wording.** Honest status: the installed rc1 APK and rc1 screenshots
  are release evidence, not current copy; docs/30 records the delta, and rc2 recaptures the screenshots. Do not claim
  the fix is on the phone yet.
- **Arabic readers may know "برومبت" or "توكن" from other AI apps.** The glossary keeps Latin hints where the term is
  technical, and the comprehension test can force the fallback if تعليمات fails.
- **Vendor pages are machine-translated and may change.** The glossary prefers plain semantic words over vendor calques,
  so vendor churn is less damaging; citations in section 3 are for direction only.
- **Frozen fixtures and historical docs use the old word.** Deliberate; they are listed in section 9 and explained here,
  so future readers do not "fix" the benchmark.
- **English UI keeps "Conversation prompt", "Vision projector".** Register differs per locale; the Arabic copy remains
  plain while English keeps established technical terms. English "CPU architecture (ABI)" was aligned for the ABI label.
- **Terminology debates recur.** The glossary and the comprehension test are the cheap resolution mechanism; no new term
  enters the UI without a dictionary/usage check and a grep.

### 7.6 First action

Rebuild and ship the source change now (site build + validators + tests + commit), then run the five-reader comprehension
test during the rc2 build/capture window. Nothing in this change should wait for the test; the test can only trigger one
iteration before public launch.

## 8. Glossary (source of truth for Arabic copy)

| English | Arabic (use this) | Avoid / note |
|---|---|---|
| model | **نموذج** | طراز (style/make); "نموذج" is also "form/sample" but context disambiguates. |
| AI model / LLM | **نموذج لغوي / نموذج لغوي كبير** | — |
| prompt | **تعليمات** | مطالبة (claim), موجّه (guide), محفز, "هندسة سريعة" (MT artifact). |
| system prompt | **تعليمات النظام** | — |
| conversation prompt | **تعليمات المحادثة** | — |
| global / per-model prompt | **التعليمات العامة / تعليمات خاصة بالنموذج** | — |
| chat template | **قالب المحادثة** | — |
| token / tokens | **رمز / رموز** (tokens) | "توكن" is common in the Arabic ML community; the app keeps رموز with the Latin hint. |
| context / context window | **السياق / نافذة السياق** | — |
| quantization | **التكميم** | "التحويل الكمي" (literal). |
| embedding | **التضمين** | — |
| retrieval / RAG | **الاسترجاع / الإجابة من المستندات** | — |
| inference | **الاستدلال** | — |
| streaming | **البثّ** | — |
| vision projector | **ملحق الرؤية (mmproj)** | "مشروع الرؤية" (project ≠ projection). |
| encoder / decoder | **المُرمِّز / مُفكِّك الترميز** (encoder / decoder) | "المشفّر / فكّ التشفير" reads as encryption. |
| orphan files | **ملفات غير مرتبطة** | "ملفات معلّقة" (suspended). |
| ABI | **بنية المعالج (ABI)** | "معمارية المعالج" (CPU architecture, not ABI). |
| gated model | **نموذج مقيّد الترخيص** | — |
| checksum / digest | **مجموع تحقق / بصمة SHA-256** | — |
| offline | **دون اتصال** | — |
| Wi-Fi only | **عبر Wi-Fi فقط** | — |
| Safetensors | **Safetensors** (لا يُحمَّل على الجهاز؛ يُحوَّل إلى GGUF) | Do not imply on-device support. |

## 9. What changed in the repository

Counts below are exact matches in the pre-change (rc1) files:

| Source | Old term occurrences removed |
|---|---|
| app/src/main/res/values/strings.xml | 50 × طراز (+5 مطالبة, +5 مشروع الرؤية) |
| feature/chat/src/main/res/values/strings.xml | 10 × طراز (+2 موجّه) |
| feature/voice/src/main/res/values/strings.xml | 11 × طراز (+ encoder/decoder fix) |
| feature/models/src/main/res/values/strings.xml | 6 × طراز (+1 مشروع الرؤية, +1 التحويل الكمي, +2 المعلّقة, +ABI) |
| README.ar.md | 22 × طراز |
| README.md (Arabic sample lines) | 6 × طراز |
| website/content/ar.json | 24 × طراز |
| website/content/policies.json | 16 × طراز (+ device-type line) |
| store/listing/ar/full_description.txt | 9 × طراز |
| store/listing/ar/whats_new.txt | 2 × طراز |
| app/src/main/assets/content/demo_chat.json | 1 × طراز |
| core/design/.../SettingsValueDirectionTest.kt | 1 test literal (direction assertion unchanged in intent) |

Totals: **158 occurrences of طراز**, 6 of "مشروع الرؤية", 5 of مطالبة, 2 of موجّه, 1 of "التحويل الكمي", 2 of
"المعلّقة" — all normalized in 11 copy files plus 1 test literal. Website regenerated from content: website/dist
(30 files, 2,312,253 bytes). Deliberately unchanged: app/src/androidTest/assets/rag-eval-ar.txt and its probe
assertions (frozen retrieval benchmark), docs/23 and docs/24 (historical quotes of what the UI said at the time).

## 10. Verification

- grep audit: zero طراز in app/site/store/README user-facing copy; the only remaining matches are the two frozen
  RAG-eval fixture lines, the two frozen Android probe assertions, the two historical docs quotes (23, 24), and this
  record's own discussion of the word.
- scripts/build-site.ps1 then scripts/validate-site.ps1: 225 checks, 0 failed.
- scripts/validate-store.ps1: 79 checks, 0 failed.
- Full JVM suite: 567 tests, 0 failures, 0 errors across 9 modules (app libre debug 178 + chat 73 + design 47 +
  models 45 + privacy 18 + rag 67 + runtime 93 + speech 40 + embeddings 6).
- :app:assembleLibreDebug: BUILD SUCCESSFUL, so the changed strings and the ABI label compile through aapt2.

## 11. Honest limits

- No user-comprehension test yet; the dictionary and the owner's own confusion are the evidence so far (see 7.2).
- Arabic technical vocabulary is genuinely unsettled; some ML communities prefer transliterations ("برومبت", "توكن").
- Vendor Arabic pages are machine-translated and cannot be used as style guides.
- Store screenshots and the installed 1.0.0-rc1 APK still show the old wording; they will be recaptured with rc2.
- "لا طراز" remains visible in the rc1 APK until that build; the source and the website are corrected now.
- The glossary is a maintained artifact: future copy changes should check section 8 before adding a new term.

## 12. Qiyas quality checklist (run before closing the session)

| Check | Result |
|---|---|
| Current-world facts researched or labeled unverified | Researched live (section 3.3); unverified items listed in 3.4 |
| Every surviving proposal has a real precedent and a stated cause | Cards in section 4; verdict table in 7.1 |
| Every role spoke and raised a real objection; dynamic roles stayed in character | Transcript section 5; Localization, QA and Owner Advocate objections all ruled on |
| At least one idea fell | Three fell: chip-only fix, keeping "مشروع الرؤية", keeping the مطالبة/موجّه family |
| Plan visibly evolved during the debate | Engineer revised from chip-only to global normalization |
| No invented numbers; assumptions labeled | Counts are exact greps; comprehension test is explicitly not yet run |
| Maestro answer actionable today | Section 7.3–7.6: apply source change, rebuild, ship rc2, run the 5-reader test |
| One language throughout | English record with Arabic evidence, matching the owner's request language |
