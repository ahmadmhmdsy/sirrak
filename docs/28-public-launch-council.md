# 28 — Public-launch council: repository, bilingual docs, website and discoverability

**Date:** 23 Nov 2026 · **Method:** Qiyas Council mode C (merged) · **Status:** adopted — implementation in this record's §5
**Goal:** decide how Sirrak becomes public on GitHub and on the web, without breaking the product, without money and without false claims.

> Grounding note: this session has no live web research (the search provider is unavailable). Every claim about
> the world is marked either **[stable]** for platform rules that have not changed in years and are verified
> against the local toolchain where possible, or **[verify-at-publish]** for anything that must be re-checked
> on the day of upload. Nothing below is presented as a fresh measurement it is not.

## 1. Frame and constraints

One line: a stranger who hears about Sirrak must be able to read a trustworthy Arabic and English page,
download a signed APK, read the privacy policy and security process, inspect the source and its build records,
and find the project through search — with everything generated from data, not hardcoded pages.

Done means: public repo pushed with clean history and no secrets; LICENSE/notices correct; README.md and
README.ar.md; docs/ published; website live on GitHub Pages with technical SEO; policies hosted and wired into
the app; CI and Pages workflows green; all existing gates still green; every claim verifiable from the repo.

Hard constraints:

- Solo builder, **no money** → free hosting only (GitHub Pages, GitHub Actions free minutes).
- Apache-2.0, no telemetry, no accounts, no third-party scripts on the site (privacy consistency).
- Arabic-first product; English is the secondary but equal-parity public surface.
- Emulator-only test device (F8); do not break M1–M11/P3; approve-all autonomy, no waiting.
- The GitHub account `ahmadmhmdsy` exists with `repo` + `workflow` scopes; `ahmadmhmdsy/sirrak` is available **[verified locally]**.
- No custom domain yet, so the canonical host is `https://ahmadmhmdsy.github.io/sirrak/`.
- `.` is not yet a git repository and has never been pushed anywhere.

## 2. Grounded state (facts read from the tree this session)

| Fact | Evidence |
|---|---|
| No root LICENSE, no .gitattributes | file listing |
| .gitignore misses `**/.cxx/` (~400 MB native build artifacts), `.evidence-*` (50 files, 2.6 MB), `build-evidence/`, `build-*.txt` | tree audit |
| No git repo; `gh` authenticated; repo name free | `gh auth status`, `gh repo view` |
| README is 42 KB / 481 lines of build journal; its doc links point at `...` (dead on GitHub); no badges, screenshots, install section or Arabic README | README read |
| 37 workspace docs (850 KB) exist outside the repo; none published | docs listing |
| No website, sitemap, robots, OG card, JSON-LD, hreflang anywhere | file audit |
| Store listing copy AR/EN + 8 screenshots + Play graphics exist; F-Droid metadata has 4 TODO fields | store/ + fdroid/ |
| PRIVACY.md and SECURITY.md are explicitly unpublished drafts; `app.json` policy/security/report URLs empty | file reads |
| 49.4 MB prebuilt sherpa-onnx AAR committed in `engine/whisper/libs`, 177 MB vendored llama.cpp, no nested `.git`; largest intended file 49 MB (<100 MB GitHub file limit); intended commit ≈ 280 MB | tree audit |
| CI workflow (JVM tests, Arabic eval, lint) has never run on GitHub; no Pages workflow | .github/workflows/ci.yml |
| Existing gates: 560 JVM tests, 24/24 Arabic eval, lint 0 errors/33 warnings, screenshot 4/4 distance 0, store validator 79/79, release-check all PASS on signed rc1 | prior records + build/release-check.json |

## 3. Council

### 3.1 Role cards

| Role | Mandate | Signature question | Qiyas tool | Red line |
|---|---|---|---|---|
| **Expert / الخبير المتخصص** (open-source distribution archetype) | second-order effects of publishing | What breaks in 6–24 months? | Takhrij al-Manat | no claim without a mechanism |
| **Engineer / المهندس** | build the public surface from data | What is the precedent and the cause? | Al-Far' | no hand-maintained duplicate page |
| **Devil's Advocate / محامي الشيطان** | attack every cause | Where does the cause break? | Al-Qawadih | first draft must be rejected |
| **Naive User / الزبون الغبي** (Arabic phone user scanning for an offline assistant) | reality check | What is in it for me, why would I bother? | Tahqiq al-Manat | no page that assumes a developer |
| **Maestro / المايسترو** | silent judge | — | — | speaks only at the end |

Dynamic roles (3, each owns a dimension that can kill this launch):

| Role | Mandate | Signature question | Red line |
|---|---|---|---|
| **SEO/Growth Strategist / استراتيجي النمو** | discoverability and search intent | Can an Arabic user typing the search find this page, and does it deserve to rank? | no JS-only content, no orphan pages |
| **Policy & Distribution Guardian / حارس السياسات والتوزيع** | F-Droid/Play/legal honesty | Does the listing make a claim the store or a reviewer will reject? | no 'F-Droid ready' claim while a prebuilt AAR blocks it |
| **Security & Supply-Chain Auditor / مدقق الأمن وسلسلة التوريد** | secrets, history, actions, artifacts | What single mistake would make the public repo dangerous? | no secret ever in history; no unpinned action |

### 3.2 Qiyas cards

**Card A — repository packaging.** *Al-Far':* one public git repo with full source, vendored llama.cpp and the
prebuilt AAR committed directly (plain git, no LFS). *Al-Asl':* llama.cpp and many Android projects vendor
native source and build offline from the repo. *Al-'Illa:* a reviewer or contributor can build from one
checkout with no network fetch; F-Droid mirrors git source, not LFS objects. *Tanqih:* the incidental cloned
directory layout is not the cause; vendoring is. *Conditions:* ≤100 MB per file, no nested `.git`, clean
ignores for build outputs.

**Card B — README architecture.** *Al-Far':* README becomes a landing page (EN + AR parity) and the build
journal moves to `docs/`. *Al-Asl':* successful SDKs split README → docs/ (Docusaurus, Apache project sites).
*Al-'Illa:* GitHub renders README for discovery, not for archiving; docs/ keeps the evidence and is
crawlable/indexable. *Tanqih:* the specific docs generator is incidental; the split is the cause.
*Conditions:* no local absolute paths survive; every link resolves inside the repo.

**Card C — website.** *Al-Far':* a data-driven static site: one `website/site.json` content file + one
`scripts/build-site.ps1` renderer + two generated language trees (`/` EN and `/ar/` AR), no framework.
*Al-Asl':* Hugo/Astro/Jekyll doc sites separate content data from templates. *Al-'Illa:* content edits stay
one-file, so a solo builder does not rot N HTML pages; matches the project's standing no-hardcode rule.
*Tanqih:* the framework is incidental; content/template separation is the cause. *Conditions:* the renderer
is small, deterministic, and validated by a script + unit test reading the same JSON.

**Card D — hosting.** *Al-Far':* GitHub Pages project site deployed from Actions. *Al-Asl':* free OSS
project pages on `*.github.io`. *Al-'Illa':* free, HTTPS, same account, no new accounts or cookies; artifact
upload deploy keeps the repo clean. *Tanqih:* the `github.io` host is incidental; free static hosting is the
cause. *Conditions:* absolute canonical/hreflang URLs (project-site subpath), no custom domain promise.

**Card E — policies.** *Al-Far':* publish PRIVACY.md/SECURITY.md as bilingual Pages pages now, then put
those URLs into `app.json` and rebuild the signed artifacts. *Al-Asl':* F-Droid/Play both require a public
policy URL; GitHub Pages is an accepted stable host. *Al-'Illa':* the launch is blocked on a live URL, not on
a domain's brand. *Tanqih:* the domain is incidental; a stable https URL is the cause. *Conditions:* page is
live and verified before the artifact rebuild; policies state date and version.

**Card F — docs publication.** *Al-Far':* publish the build-product record set (04,05,06,07,13–28) in
`docs/`; keep research/clearance/naming/marketing-adjacent memos (01,02,03,08,09,10,11,12) in the private
workspace. *Al-Asl':* OSS projects publish design and build records (RFDs, ADRs), not legal memos.
*Al-'Illa:* evidence builds trust; unfiltered deliberation invites noise and stale claims. *Tanqih:* which
documents are 'legal' is incidental; publish evidence, not attorney work product. *Conditions:* docs/README
states the boundary and how the private record is maintained.

**Card G — discoverability scope.** *Al-Far':* full technical SEO: per-language crawlable HTML, canonical,
hreflang + x-default, sitemap, robots (with honest subpath note), JSON-LD SoftwareApplication/WebSite/FAQ,
OG/Twitter cards + generated 1200×630 image, semantic headings, self-hosted fonts, no JS required for content.
*Al-Asl':* app landing pages of OSS Android apps. *Al-'Illa:* Arabic long-tail queries are the realistic
discovery path; F-Droid/Play listings are the conversion path. *Tanqih:* content marketing/blog is
incidental; a correct machine-readable page is the cause. *Conditions:* no third-party scripts, no analytics,
every claim true and dated.

## 4. Deliberation

### Round 1 — first drafts rejected

- **Devil (Naqd):** Card C duplicates the store validator. *Engineer revises:* the site validator checks a
  different contract (metadata/hreflang/links/paths) and both read data files, so they share a
  discipline rather than code. The site JSON is the single source; the validator and the app unit test
  read it. Not dropped.
- **Naive User (Tahqiq al-Manat):** 'I am an Arabic phone user with a 30 % battery. Your README starts with
  Gradle. Where is the APK?' *Engineer revises:* install-first section with direct release link, file size,
  Android 9+/arm64 requirement, and a model note before any build instructions; build instructions move
  behind a link.
- **Security Auditor (Invalid premise):** 'The risk is not push, it is what you push first.' Confirmed
  blockers: `.cxx/` outputs (hundreds of MB) and milestone evidence screenshots are not ignored; no secret
  scan has run; Actions are not SHA-pinned; no branch protection. *Revisions accepted:* harden .gitignore,
  clean-room initial commit, secret scan before the first commit, enable GitHub secret scanning + push
  protection, SHA-pin every action in the new Pages workflow (and update CI while there), protect `main`.
- **Policy Guardian (Material difference):** 'Your F-Droid metadata calls the app F-Droid-friendly but the
  49.4 MB prebuilt sherpa-onnx AAR is exactly the prebuilt-binary problem F-Droid reviewers reject — unless
  built from source in the fdroiddata recipe.' *Engineer concedes:* source was not vendored for sherpa-onnx;
  building it from source is out of scope for this round. **Claim downgraded:** site and docs say GitHub APK
  now, F-Droid submission planned; docs/27 records the AAR source-build gap as the remaining F-Droid blocker.
  Also recorded: a personal Play account needs closed testing before production **[verify-at-publish]**.
- **SEO Strategist (Reality check):** 'If language switching is JavaScript-only, the English and Arabic
  pages do not exist to a crawler and hreflang is meaningless.' *Engineer revises:* two generated static
  trees (`/` EN, `/ar/` AR) with links, not a JS toggle. Absolute URLs are derived from one configured site
  root in `site.json`.

### Round 2 — fixes attacked

- **Devil:** 'A generator for 4 pages is over-engineering.' *Engineer defends with the standing user rule:*
  every asset must be reusable/editable from data; the generator also renders `privacy/`, `security/` and the
  404 page from the same JSON, so it pays for itself in this round. **Downgrade:** no templating engine, no
  markdown parser — the renderer is a small deterministic PowerShell function set.
- **Naive User:** 'Two policy pages in two languages — I just want to read why my chats are private.'
  *Content constraint accepted:* each policy page opens with a 3-line Arabic + English summary, then details;
  no legal maze above the fold.
- **Security Auditor (Second-order):** 'GitHub Pages cannot set response headers, so a strict CSP is a
  meta-tag approximation.' *Accepted limit:* record it; mitigate with zero third-party requests, no inline
  handlers, self-hosted fonts/images; where a real header is required, note that a custom domain/proxy is
  the future fix.
- **Policy Guardian:** 'Do not wire policy URLs into a signed artifact until the live URL is verified, or you
  ship a dead link.' *Ordering constraint accepted:* publish → verify HTTP 200 + content → update app.json →
  rebuild artifacts → re-record hashes → re-run release-check.

### Round 3 — stop condition

No new fatal objection. The accepted revisions are in §5. What fell or was downgraded: git LFS (dropped),
a JS framework (dropped), hand-edited per-page HTML (dropped), analytics/cookies (dropped), custom domain
now (downgraded to future), 'F-Droid ready' claim (downgraded), blog/newsletter/content marketing (dropped),
Dependabot auto-updates (suspended — toolchain is deliberately pinned), full release build in CI
(suspended-on-test; CI stays JVM-only and the release gate stays local).

## 5. Maestro verdict

| Branch | Verdict | Asl | Illa | Constraint the objections imposed |
|---|---|---|---|---|
| Plain-git full-source repo, no LFS | **Survived** | vendored-source Android/llama.cpp projects | one-checkout offline build; F-Droid mirrors git | ≤100 MB/file; no nested .git; harden ignores first |
| README landing + docs/ split + README.ar.md | **Survived** | README→docs split in mature OSS | discovery vs evidence separation | no absolute local paths; every link resolves in-repo |
| Data-driven static site generator | **Survived** | content/template separation (Hugo/Astro class of tools) | one-file content edits, no page rot | renderer small + deterministic; JSON is single source; validator + JVM drift test |
| GitHub Pages + Actions deploy | **Survived** | free OSS project pages | free HTTPS static hosting, no new account | canonical/hreflang absolute; robots subpath limitation recorded |
| Host policies now, wire + rebuild | **Survived** | store policy-URL requirement | launch blocked on live URL, not domain | live URL verified before artifact rebuild; dated policy |
| Curated build-record docs/ | **Survived** | OSS design/build records | publish evidence, not deliberation | docs/README states boundary + private copy note |
| Full technical SEO + JSON-LD + OG + hreflang | **Survived** | OSS app landing pages | Arabic long-tail discovery + trustworthy metadata | no third-party JS/analytics; crawlable static trees |
| Git LFS | **Dropped** | — | bandwidth quota and mirror complexity outweigh 49 MB | — |
| JS framework (Next/Astro) | **Dropped** | — | build chain + dependency surface for 4 static pages | — |
| Hand-edited HTML pages | **Dropped** | — | guaranteed drift from brand/copy data | — |
| Analytics / cookies / newsletter | **Dropped** | — | violates no-telemetry promise; no banner needed | — |
| Custom domain now | **Downgraded** | — | no budget; github.io is valid and HTTPS | revisit after first releases |
| 'F-Droid ready' claim | **Downgraded** | — | prebuilt AAR needs a source build in fdroiddata | site/docs state 'submission planned'; gap in docs/27 |
| Dependabot auto-updates | **Suspended-on-test** | — | pinned toolchain is a deliberate reproducibility choice | revisit if a security advisory lands |
| Full release build in CI | **Suspended-on-test** | — | NDK build cost + no signing key in CI | keep local release-check; CI = tests/eval/lint |

### 5.1 Step Zero — the falsification test

Before claiming the launch surface done, all of these must be observed, not assumed:

1. `git log` on the fresh repo shows no `keystore/`, no `keystore.properties`, no `.cxx/`, no evidence image,
   and a clean secret scan (manual pattern scan + GitHub push protection enabled).
2. The pushed `main` exists, `gh repo view` shows the intended description/topics/homepage, and the CI run
   for the pushed commit concludes success.
3. Pages deployment concludes success and these return HTTP 200 with the right `<title>`, `lang`, canonical,
   description, hreflang and JSON-LD: `/sirrak/`, `/sirrak/ar/`, `/sirrak/privacy/`, `/sirrak/security/`,
   `/sirrak/sitemap.xml`, `/sirrak/robots.txt`, `/sirrak/assets/og-image.png`.
4. The site validator passes and an app JVM test proves `website/site.json` and the shipped brand/copy do not
   drift; the grammar/limit checks hold for both languages.
5. The rebuilt signed artifacts embed the live policy URLs; release-check passes again and the recorded
   hashes in `store/README.md` match the files on disk.
6. If Pages is blocked by account settings, fallback: policy URLs may temporarily point at the GitHub file
   view (`github.com/ahmadmhmdsy/sirrak/blob/main/PRIVACY.md`) and the site deployment is suspended-on-test —
   the app must never ship a dead policy link.

## 6. Items the user asked about that were missing from the first draft

Accepted unless marked otherwise: LICENSE text (Apache-2.0) **was absent**; .gitattributes for line endings
and binaries; .cxx/evidence/log ignores; curated docs publication; bilingual README; README badges + hero
graphic + screenshots; install-first section; GitHub repo description/topics/homepage; social-preview image
(manual upload — API cannot set it); issue templates + PR template + CONTRIBUTING + CODE_OF_CONDUCT +
SUPPORT; SECURITY/PRIVACY hosted and dated; app.json policy/security/report URLs wired + rebuild; CHANGELOG;
CITATION.cff; F-Droid TODO URLs; store listing SEO pass (both languages); generated OG image from brand data;
favicon/apple-touch-icon/web manifest; sitemap/robots; JSON-LD; hreflang + x-default; accessible RTL layout;
404 page; site validator + drift test; SHA-pinned actions; secret scanning + push protection + branch
protection; release tag + bilingual release notes; docs 26/27/06 update with this record. Screenshots for
the site come from the committed store/screenshots (no new phone). Analytics, custom domain, blog, newsletter,
LFS, framework, and Dependabot are explicitly out (see §5).

## 7. Accepted implementation plan (this round)

| # | Step | Main files |
|---|---|---|
| 1 | Harden repo + license + attributes | `LICENSE`, `.gitattributes`, `.gitignore` |
| 2 | Publish curated docs + index | `docs/` (04–07, 13–28 + docs/README.md) |
| 3 | Rewrite public README (EN) + Arabic parity | `README.md`, `README.ar.md` |
| 4 | Community/metadata files | `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`, `SECURITY.md`/`PRIVACY.md` update, `CHANGELOG.md`, `CITATION.cff`, `.github/ISSUE_TEMPLATE/*`, `PULL_REQUEST_TEMPLATE.md`, `SUPPORT.md` |
| 5 | Site data + renderer + pages | `website/site.json`, `scripts/build-site.ps1`, generated `website/dist/**`, `website/README.md` |
| 6 | Site assets from brand data | OG image 1200×630, favicons, fonts copied, `website/dist/assets/*` |
| 7 | SEO + validation | sitemap/robots/manifest, `scripts/validate-site.ps1`, `WebsiteContentTest.kt` |
| 8 | Live policies + wire app | Pages live → `app.json` URLs → rebuild signed APK/AAB → hashes → release-check |
| 9 | F-Droid + store metadata | `fdroid/metadata/app.sirrak.yml`, listing copy SEO, docs 27 gap note |
| 10 | Publish repo + Pages + release | git init/commit/push, `gh repo create/edit`, tag `v1.0.0-rc1`, release notes + assets, workflows |

Exit: every Step Zero check observed; 560+ tests green; store validator green; release-check green; CI and
Pages green on the pushed commit; this record and docs 26/27/06 updated.
