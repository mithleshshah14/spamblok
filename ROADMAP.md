# SpamBlok — Build Roadmap (phased)

> Working plan. Each phase is a **self-contained, testable slice** you can run on a
> real phone before moving to the next. We deliberately build the *riskiest* part
> first (Phase 1) so we find out early if the whole approach is viable.
> See `README.md` for vision + the architecture DECISION this plan implements.

---

## Phase 1 — Read the banner  *(the risky proof / feasibility)*

**Goal:** prove we can read the caller's **name as text** off Truecaller's
**incoming-call (ringing) overlay** — the pre-answer screen, NOT the after-call one.

**Scope (minimal):**
- A bare Android app with a single **AccessibilityService**.
- It listens for window events from the Truecaller package while a call is ringing.
- It traverses the on-screen node tree and **logs every text string** it finds.
- We make a test call and check the log: did the caller's name appear?

**Done when:** caller name from a ringing Truecaller overlay shows up in our log on
the real device.
- ✅ = approach is valid → continue to Phase 2.
- ❌ (name is an image / secure window / unreadable) = stop and rethink; fall back
  to relying only on our own DB (see README deferred risks).

**RESULT — ✅ PROVEN (2026-06-20).** On a Galaxy S25 (SM-S938B), the caller
name/label is fully readable as plain text **during the ring, before answering**.
Definitive capture at 10:58:47 while `call_state: "Incoming call"` with Answer/
Decline controls present:
`id/name: "Potential Fraud"`, `id/location_info: "Reported as Fraud"`,
`id/phone_number: "+917669456375"`.
Key learnings:
- On this device the ring-time caller ID comes from **Samsung Smart Call (Hiya)**
  via `com.samsung.android.incallui` (`id/name`, `id/phone_number`, `id/call_state`,
  `id/location_info`). Truecaller showed its panel mostly *after* the call.
- Spam labels read fine too ("Potential Fraud"). Names like "Zomato Delivery
  Partner", "eKart Delivery" all came through.
- Real validation of the layered strategy: the fraud number is a normal 10-digit
  mobile (heuristics rate it NEUTRAL) — only the read-the-banner layer caught it.
- Noise to fix in Phase 2: the `com.samsung.android.dialer` call-log screen
  produced ~1,400 lines of irrelevant capture. Narrow the filter to `incallui` +
  `truecaller`, ideally only when `call_state` is incoming/active.

**Explicitly NOT in this phase:** no own DB, no CallScreeningService, no
reconciliation. Just read + log (+ a basic on-device viewer added for convenience).

---

## Phase 2 — Get the number + show our own banner

- Register as the system **CallScreeningService** → gives us the incoming **number**.
- Combine: number (from system) + name (read from Truecaller in Phase 1).
- Show it in our **own simple overlay** during the ring.
- Result: caller info in *our* UI, even though the "data" is still just Truecaller's.

---

## Phase 3 — Our own DB

- Local on-device database (number → name, with `source` + `verified/unverified` tag).
- Store every number→name we observe.
- On an incoming call: look up our DB **first**; fall back to reading Truecaller's
  banner only when our DB has nothing.
- Reconcile / flag mismatches between our DB and Truecaller.

---

## Phase 4 — Real data sources (makes the DB actually useful)

- Bring in the legit sources from the README: public Indian datasets, spam-number
  lists, TRAI DND / DLT data, and **pattern/heuristic** spam flagging.
- This is what turns the DB from "a cache of Truecaller answers" into an independent,
  privacy-respecting product.

---

## Phase 5 — Polish

- Replace / auto-dismiss Truecaller's intrusive banner with our clean one.
- Settings, spam auto-block for known scam numbers, overall UX cleanup.

---

## Current status

- **2026-06-19:** roadmap written. Starting **Phase 1**.
- **2026-06-20:** **Phase 1 ✅ COMPLETE & PROVEN** on a real Galaxy S25 — caller
  name + spam label are readable pre-answer (see Phase 1 RESULT). Heuristics engine
  + data-sources analysis also done. Next: **Phase 2** (CallScreeningService +
  our own banner), starting with narrowing the capture filter.
