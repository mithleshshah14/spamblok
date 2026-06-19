# SpamBlok — privacy-first caller ID & spam blocker

> Vision / context doc. Written 2026-06-19 so a future session can resume with full
> context. This is **not** a finalized design — it captures intent and the open
> questions to resolve before building. Next step is a proper brainstorming +
> design pass (the data-source question below is the crux).

## What we're building

An Android app that tells you **who's calling** and **flags spam/scam calls** —
a Truecaller alternative — **without** harvesting your contacts or reading your
SMS, and without selling user data.

## Why (the motivation)

The owner (and target users) are in **India**, where spam/scam calls are constant.
Truecaller is the only thing with good coverage there, but:
- It harvests your entire contact book (that's *how* it built its database).
- It requests SMS access; the owner believes it profiles users financially from
  balance SMS (low balance → loan-spam calls, high balance → credit-card calls)
  and that data is sold/targeted.
- Its after-call screen is intrusive (needs multiple taps to dismiss; disabling
  it for unknown callers is paywalled).

So: **keep the genuinely useful part (caller ID + spam flagging) and drop the
data-harvesting and the spammy UX.**

## Core principles (non-negotiable)

1. **No contact upload.** Never read/transmit the user's address book.
2. **No SMS access.** Caller ID doesn't need it.
3. **No selling data / no ads built on user data.** Privacy is the product.
4. Minimal permissions; transparent about what each is for.
5. Spam labels appear **on the incoming-call screen** (so the user decides before
   answering), and ideally auto-block known scam numbers.

## THE hard problem — where does the spam/caller-ID data come from?

This is the make-or-break question. In India, this is exactly why Truecaller wins
and privacy-friendly apps (Hiya, Should I Answer, Google) have weak coverage.
Options to evaluate next session:
- **Community/crowdsourced reports** — users *report* spam numbers (numbers only,
  never contacts). Builds a shared DB ethically. Cold-start problem: empty at launch.
- **Open / public datasets** — e.g. "Should I Answer" style offline DBs, TRAI DND
  / DLT registrations, public scam-number lists, telecom spam feeds. Coverage TBD.
- **Pattern/heuristic detection** — flag by number patterns (telemarketer prefixes,
  unallocated ranges, high call frequency reported), not identity. Privacy-friendly,
  no DB needed, but only flags "likely spam", won't *name* a legit unknown caller.
- **Third-party API** (Hiya/others) — fastest coverage, but reintroduces a data
  partner; vet their privacy stance.
- **Hybrid** — heuristics + community reports + any clean public dataset.

Realistic early scope: probably **spam *flagging*** (heuristics + community reports)
rather than full *name* identification of arbitrary unknown numbers, since naming
requires a large identity DB that's hard to build privately. Naming legit unknown
callers may be a later/never goal.

## DECISION (2026-06-19) — architecture chosen

We picked a **hybrid: own DB (primary) + Truecaller banner as a personal
on-device cross-check/enrichment layer.**

**Key context that unlocked this:** the app is **personal / sideload-only**, never
going to the Play Store. That removes the AccessibilityService policy ban (the main
reason this approach would be a non-starter for a public app).

Flow on an incoming call:
1. Our **CallScreeningService** gets the **number** directly from the system (no
   scraping needed for the number).
2. Look the number up in **our own DB** (built from the legit sources above —
   community reports + public Indian datasets + heuristics). This is the primary
   answer.
3. In parallel, an **AccessibilityService reads the caller name off Truecaller's
   incoming-call overlay** — the one shown **while the phone is still ringing,
   before answering** (NOT the after-call screen). The whole point is the user
   decides before picking up (principle #5). Truecaller shows its caller-ID overlay
   during the ring and accessibility window events fire for it, so it's readable at
   that moment. (Purely local UI-tree read — invisible to Truecaller, no network
   signal reaches them, so undetectable for a single user.)
4. Reconcile:
   - Both agree → high confidence, show it.
   - Our DB has it, Truecaller differs → trust ours, flag the mismatch.
   - Our DB empty, Truecaller has a name → store it tagged
     `source: truecaller, unverified` (never confuse a scraped guess with our own
     verified data).
5. **Replace/dismiss Truecaller's intrusive banner** with our own clean UI (dismiss
   its close affordance via accessibility, or draw our own overlay on top).

This keeps our own DB as the real product and uses Truecaller only to verify / fill
gaps — not as the foundation (so no data-laundering of their harvested data).

**Caveats / deferred risks (decided to handle later, not pre-solve):**
- *Fragility:* if Truecaller redesigns its overlay, our text selector breaks → we
  fix it then. Low stakes for a personal app.
- *Image/secure rendering:* if Truecaller ever renders the name as an image or uses
  a secure window, accessibility can't read it. **Decision: cross that bridge if/when
  it happens** — try to fix, and if not possible, fall back to our own DB. Low
  probability (it would break Truecaller's own accessibility support).
- **Early feasibility spike (do first):** confirm on our actual device that the
  caller name on Truecaller's **incoming-call (ringing) overlay** is readable text
  via accessibility — specifically the pre-answer screen, since that's the one that
  matters. Reading the after-call screen is not enough.

## Likely technical shape (to confirm)

- **Android, Kotlin.** Register as the system **CallScreeningService** + **"Caller ID
  & spam app" role** (Android 10+) so spam labels show on the native call screen
  with no overlay permission needed.
- Optionally an **after-call** experience that is *opt-in* and instantly dismissable
  (learn from what annoyed us about Truecaller).
- Backend only if we do community reports (a simple number→report-count store);
  otherwise fully on-device with a bundled/updatable offline DB.
- Reuse hard-won Android lifecycle/service knowledge from the YTDownloader project.

## Open questions for next session

1. Primary goal: **spam *blocking/flagging*** vs **full caller *identification***?
   (Strongly lean flagging-first given the data reality.)
2. Data source decision (see above) — pick one or a hybrid.
3. Backend or fully on-device? (Community reports imply a small backend.)
4. Monetization that doesn't betray principle #3 (donations? optional paid features
   that aren't data-based? none?).
5. Coverage reality check: how good can a privacy-respecting DB get for Indian
   numbers, honestly?

## Status

- 2026-06-19: folder created, vision doc written, **architecture decided** (see
  DECISION section above). Code **not started** — next is the accessibility
  feasibility spike.
- Predecessor project: YTDownloader (paused in good shape on branch
  `feature/player-ui`).

## Next step

Architecture is decided (see DECISION). Immediate next step: a small
**accessibility feasibility spike** — confirm we can read the caller name off
Truecaller's overlay as text on the real device. If yes, scaffold the Kotlin app
(CallScreeningService + AccessibilityService) and start the own-DB data-source work
in parallel.
