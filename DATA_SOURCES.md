# SpamBlok — Data Sources Analysis (Phase 4 groundwork)

> The "make-or-break" question from the README: **where does the spam / caller-ID
> data come from?** This documents what actually exists for India as of June 2026,
> how usable each option is, and a recommended strategy. Research-backed (sources at
> the end), not from memory.

---

## TL;DR recommendation

For a **personal, sideload-only** app, the cold-start problem that kills crowdsourced
apps for the public **does not apply to us** — because the phone already has Truecaller
and Samsung Smart Call (Hiya) installed, and we proved in Phase 1 we can read both. So
the strategy is a **layered hybrid**, cheapest/most-private first:

1. **Heuristics (zero DB, instant, perfectly private)** — flag by Indian number
   patterns, esp. the **`140` series reserved for telemarketing** and `1600`/`160`
   series for transactional/service calls. No data source needed.
2. **Bundled open offline DB** — ship + delta-sync the **Yet Another Call Blocker**
   crowdsourced database for base spam coverage (ethically sourced, numbers only).
3. **Read on-device caller ID via accessibility** — Truecaller **and** Samsung Smart
   Call / Hiya (both already on the device). Highest coverage; already proven in Phase 1.
4. **Own local cache** — remember every number→name/label we observe, building a
   private verified DB over time (on-device only).
5. **Government data (Chakshu/Sanchar Saathi)** — monitor for a future public API;
   **not usable as a feed today** (no API / no downloadable dataset).

This gives instant flagging (heuristics) + broad coverage (read existing apps) +
offline base (open DB) + a growing personal DB — all without harvesting anything.

---

## Option-by-option

### 1. Heuristics / number-pattern detection  ⭐ use first
**What:** flag "likely spam/telemarketing" purely from the number, no database.
- India reserves the **`140`xxxxxxx series exclusively for telemarketing/promotional
  calls** — a near-perfect signal to label/auto-handle.
- The **`160`/`1600` series** is being rolled out for **transactional & service calls**
  (banks, govt) — useful to label as "likely legit service call".
- Other signals: unusually long/short numbers, international prefixes you never deal
  with, premium ranges, repeated short-duration calls.

**Pros:** zero data dependency, perfectly private, works offline, instant at launch,
never breaks. **Cons:** only flags *likely* category — can't *name* a caller, and
spammers using normal mobile ranges slip through. **Verdict:** free win, build it
regardless. Foundational layer.

### 2. Yet Another Call Blocker (YACB) open DB  ⭐ best pre-built ethical source
**What:** open-source (AGPL-3.0) call blocker with a **crowdsourced offline database**
downloaded from a public GitLab repo, with **daily incremental (delta) updates**;
detailed reviews via a "Should I Answer"-style 3rd-party backend. Sends no
user-identifiable info except the number being looked up.

**Pros:** real, downloadable, ethically sourced (numbers only), offline, low bandwidth.
**Cons:** **India coverage is admittedly immature** (the dev says it improves with
usage); AGPL-3.0 means if we reuse their DB/code our app inherits AGPL (fine for
personal use; matters only if ever distributed). **Verdict:** bundle it as the offline
base layer; don't expect great India coverage alone.

### 3. Government — Sanchar Saathi / Chakshu / TRAI DND-DLT  ⚠️ not usable as a feed (yet)
**What:** DoT's **Chakshu** (under Sanchar Saathi) lets citizens report fraud calls/SMS.
Real scale: **5.19 lakh reports in 2025**, **39.43 lakh connections disconnected**,
2.27 lakh handsets blacklisted. TRAI's **DND/DLT** governs commercial SMS sender
registration.

**Why not usable now:**
- **No public API and no downloadable number dataset.** Chakshu is report-in only;
  outputs are enforcement actions, not a lookup feed.
- **DLT/DND is about SMS sender/template registration**, not a caller-ID lookup, and
  also has no open lookup API.
- Privacy note: govt states Chakshu stores only the reported number, no other personal
  data / call logs.

**Verdict:** strategically aligned (govt is doing the crowdsourcing) but **no
integration path today**. Track for a future API; benefit is indirect (bad numbers get
disconnected upstream).

### 4. Third-party caller-ID APIs  ➖ mostly reintroduce a data partner / cost
- **Hiya** — powers **Samsung Galaxy Smart Call** (flags spam in the native dialer
  *before* you pick up). **Key for us:** it's already on the user's S25, so instead of
  paying for Hiya's API we can just **read Smart Call's result via accessibility** (we
  already captured `com.samsung.android.incallui:id/name` in Phase 1). Hiya's commercial
  API is enterprise/paid.
- **BharatCaller** — Indian-built, privacy-friendly (no contact upload, encrypted, staff
  can't access the number DB). It's a competitor app; no clear public API.
- **Truecally** — community DB tuned for South Asian numbers; another app, not an API.
- **Reverse-lookup APIs (e.g. Searchbug ~$1.95/lookup)** — US-centric, pay-per-lookup,
  poor India coverage, and reintroduce a paid data partner. Not worth it.

**Verdict:** don't pay for an API. Instead **read the caller-ID apps already on the
device** (Hiya/Smart Call + Truecaller) — same data, free, on-device, private.

### 5. Reading on-device caller ID via accessibility  ⭐ highest coverage (proven)
**What:** our Phase 1 mechanism — read what Truecaller and Samsung Smart Call (Hiya)
already display. Phase 1 confirmed both expose the **name + rich metadata as readable
text** (`com.truecaller:id/nameOrNumber`, business status, category, call reason;
`com.samsung.android.incallui:id/name`).

**Pros:** by far the best coverage for India, free, on-device, leverages databases we
could never build. **Cons:** depends on those apps being installed; fragile to their UI
changes (acceptable for personal use, per the README decision). **Verdict:** primary
*coverage* source for the personal build.

### 6. Our own crowdsourced DB  ➖ not for a single user
Cold-start: empty at launch and a single user never reaches critical mass. Only makes
sense if this ever became multi-user with a backend. **Verdict:** skip for personal use;
the "own DB" we build is really the **local cache** of what we observe (Option 5 + 1).

---

## Coverage reality check (honest)

- **Naming an arbitrary unknown caller** privately, from scratch, for Indian numbers is
  **not realistically achievable** — that's exactly Truecaller's moat (built on harvested
  contacts). We don't try to replicate it.
- **For this personal app it doesn't matter**, because we read the names Truecaller/Hiya
  already resolve on the device. Our coverage ≈ Truecaller's coverage, for free.
- **Spam *flagging*** (vs naming) is very achievable: heuristics (140-series etc.) +
  open DB + the spam labels we read from Truecaller/Hiya.
- So: **flagging-first is solid; naming rides on the installed apps.** Matches the
  README's lean.

---

## How this maps to the build phases

- **Phase 1 (now):** reading Truecaller + Smart Call — *done/validating*. This is
  Option 5, our coverage backbone.
- **Phase 3 (own DB):** local cache (Option 5 output) + tagging. 
- **Phase 4 (data sources):** add **Option 1 heuristics** (140/160 series — quick win)
  and **bundle Option 2 (YACB offline DB)**. Revisit **Option 3 (govt API)** only if one
  appears.

---

## Sources
- [Sanchar Saathi — Chakshu fraud reporting (ANI, Feb 2026)](https://aninews.in/news/business/chakshu-facility-of-sanchar-saathi-enables-citizens-to-report-suspected-fraud-communications-govt20260205163602/)
- [Sanchar Saathi only flags user-reported spam (India.com)](https://www.india.com/technology/sanchar-saathi-only-flags-user-reported-spam-calls-govt-reiterates-amid-growing-privacy-concerns-8212069/)
- [Sanchar Saathi portal](https://sancharsaathi.gov.in/)
- [Yet Another Call Blocker — F-Droid](https://f-droid.org/packages/dummydomain.yetanothercallblocker/)
- [YACB — XDA thread (crowdsourced DB, India coverage discussion)](https://xdaforums.com/t/app-4-0-yet-another-call-blocker-open-source-app-with-crowdsourced-database.4150957/)
- [Best Truecaller alternatives in India 2026 (Hiya/Smart Call, BharatCaller, Truecally)](https://www.hirobin.ai/blog/blog-best-truecaller-alternatives-india)
- [DLT registration / TRAI compliance guide 2026](https://www.messagecentral.com/sms-guideline/india)
- [Reverse phone lookup services + pricing 2026](https://www.searchbug.com/info/best-reverse-phone-lookup-tools-with-pricing/)
