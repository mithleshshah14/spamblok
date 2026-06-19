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

**Explicitly NOT in this phase:** no own DB, no CallScreeningService, no UI, no
reconciliation. Just read + log.

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
