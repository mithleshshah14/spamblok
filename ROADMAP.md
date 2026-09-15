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
- **2026-09-14: Phase 2 implemented, needs real-device verification.**
  - `SpamBlokCallScreeningService` registers as the system call-screening app and
    gets the incoming number instantly via Telecom; always allows the call
    (no blocking yet — that's a later phase) and runs it through
    `NumberHeuristics` for an immediate offline verdict.
  - `CallerInfoStore` is the in-process hand-off: the number arrives first from
    the screening service, the name/label arrive slightly later from
    `BannerReaderService`; whichever overlay listener is showing gets a live
    update either way.
  - `OverlayService` draws SpamBlok's own small `TYPE_APPLICATION_OVERLAY`
    banner (number + verdict immediately, name/label filled in once read),
    auto-dismissing after 45s.
  - `BannerReaderService` narrowed per the Phase 1 noise note: dropped the
    generic `"dialer"` package hint (it was matching the call-log screen, not
    just the in-call UI) and now only records/forwards events that carry an
    `id/call_state` node, i.e. an actual ringing/active call screen.
  - `MainActivity` now walks through all three setup steps: enable the
    accessibility service, grant "display over other apps", and request the
    `ROLE_CALL_SCREENING` role via `RoleManager`.
  - Builds and unit tests pass; **not yet verified on a real device** — same
    process as Phase 1: enable all three permissions, place a real incoming
    call, confirm the banner appears and updates with the name.
  - Added user-managed **prefix blocklist** (e.g. "+9180" or "80"): matching
    numbers are rejected outright by `SpamBlokCallScreeningService`
    (silent — no ring, no notification), before any heuristic/overlay logic
    runs. `PrefixMatcher` (pure, unit-tested) normalizes both the stored
    prefix and the incoming number across with/without-country-code and
    with/without-trunk-zero forms so "+9180", "9180", and "80" all match the
    same numbers; `BlockedPrefixStore` persists prefixes in SharedPreferences.
    Managed from `MainActivity` (add/remove rows); blocked calls are also
    noted in the on-device caller log.
- **2026-09-14: Phase 2 ✅ VERIFIED on a real Galaxy S25** — `SpamBlokCallScreeningService`
  confirmed actually invoked by Telecom (`dumpsys telecom` showed
  `SCREENING_BOUND (com.spamblok.app/...)` and `mCallScreeningAppName = SpamBlok`
  for a real incoming call). Two real bugs found and fixed along the way, both
  worth remembering for later phases:
  - **RoleManager's request-role intent needs `startActivityForResult`, not
    `startActivity`.** `RequestRoleActivity` reads the *calling* package via
    `getCallingPackage()`, which Android only populates for an activity-launched-
    for-result. Launched with plain `startActivity()` it silently no-ops
    (logcat: `RequestRoleActivity: Package name cannot be null or empty: null`) —
    no dialog, no error shown to the user. Fixed in `MainActivity` using
    `registerForActivityResult(ActivityResultContracts.StartActivityForResult())`.
    This is what looked like a Samsung/OneUI restriction on sideloaded apps at
    first — it wasn't.
  - **`READ_PHONE_STATE` was declared in the manifest but never requested at
    runtime**, so it stayed ungranted. On this device, Telecom silently refuses
    to ever bind a `CallScreeningService` whose app lacks that permission — no
    error, it just skips straight to the OEM's own screening, which looked
    identical to "the role holder is being ignored." Fixed with an explicit
    in-app runtime-permission step (`MainActivity`, step 3) using
    `ActivityResultContracts.RequestPermission()`.
  - **Real-device quirk confirmed, not a bug:** Samsung's Telecom skips 3rd-party
    call screening entirely for numbers already saved as a Contact on-device
    (`dumpsys telecom` shows a `contact exists` flag on those calls, and no
    `SCREENING_BOUND` for our service). Confirmed by A/B: an unsaved test number
    got properly screened by SpamBlok; a saved-contact number did not. To test
    Phase 2/3 behavior going forward, always call from a number NOT saved in
    the test phone's Contacts.
  - Local dev release signing was added (`app/build.gradle.kts` `signingConfigs`,
    self-signed key at `keystore/spamblok-release.jks`, gitignored) — turned out
    not to be the fix, but keeping it since a release-signed build is generally
    the more realistic thing to test call-screening/role behavior against.
  - Truecaller's/Samsung's own banner still shows regardless of what SpamBlok
    does — replacing/auto-dismissing it is explicitly **Phase 5**, not Phase 2.
    SpamBlok's banner is additive by design at this stage.
  - **Not yet verified:** the prefix-blocklist's actual silent-reject behavior
    on a real call (blocked-prefix `respondToCall` path) — needs a test call
    from an unsaved number matching a configured prefix. The screening service
    itself is now confirmed to fire correctly, so this should follow the same
    "unsaved number" pattern above.
- **2026-09-14: Phase 3 implemented (our own DB), needs real-device verification.**
  - `CallerRepository` is a plain SQLite table (`spamblok_callers.db`, private to
    the app) keyed by normalized number, storing name/label/source/verified/
    first-seen/last-seen/mismatch-count. `isMismatch()` (the only real judgment
    call — does a freshly observed name genuinely disagree with what's stored)
    is a pure function, unit-tested separately from the DB plumbing, following
    the same pattern as `PrefixMatcher` in Phase 2.
  - `BannerReaderService` now calls `CallerRepository.observe(...)` on every
    name/label it reads off a banner — "store every number→name we observe."
    A disagreement with an existing entry increments `mismatch_count` and logs
    a `MISMATCH` line to the on-device caller log; the original name is kept
    (first-seen wins) rather than overwritten, until Phase 4 gives us a real
    way to decide which source to trust.
  - `SpamBlokCallScreeningService` now looks the number up in `CallerRepository`
    right after screening, before showing the overlay: a DB hit shows the name/
    label immediately (`CallerInfoStore.onDbLookup`, overlay tags it "(from our
    DB)"), instead of waiting for the banner. A live banner read (if one still
    arrives) overrides the DB guess via the existing `onBannerCaptured` path —
    this *is* the "look up our DB first, fall back to the banner" roadmap goal.
  - Added a "Known callers (our own DB)" viewer to `MainActivity` for
    verification (number, name/label, source, last-seen, mismatch count).
  - **Not yet verified on a real device:** need two calls from the same
    (unsaved) test number — first to populate the DB via the banner, second to
    confirm the overlay shows the name instantly from the DB this time.
- **2026-09-14: Phase 4 implemented (real data sources), needs real-device verification.**
  - Pattern/heuristic flagging (`NumberHeuristics`, 140/160 series) was already
    done in Phase 1 — per `DATA_SOURCES.md`'s own recommendation, that's Option 1.
  - The remaining piece, bundling an open spam-number list (Option 2, e.g. Yet
    Another Call Blocker's crowdsourced DB): SpamBlok does **not** bundle or
    redistribute someone else's database itself (that project's own
    license/ToS governs redistribution, and we have no vetted snapshot to ship
    reliably). Instead, `SpamNumberListStore` is an **import** mechanism — the
    user downloads a plain-text number list themselves and imports it via a
    file picker in `MainActivity`, entirely on-device (same privacy guarantee
    as everything else). `parseLines()` (strip comments/blanks, digits-only,
    discard non-number junk) is pure and unit-tested.
  - `SpamBlokCallScreeningService` checks the imported list right after the
    heuristic classification: a match upgrades the verdict to `LIKELY_SPAM`
    with label "Known spam (imported list)" — shown on the overlay the same
    way a `140` prefix is. It does **not** block the call outright; only the
    user's own `BlockedPrefixStore` (Phase 2) does that.
  - TRAI DND/DLT and Chakshu/Sanchar Saathi remain **not usable as a feed**
    (no public API/downloadable dataset, per `DATA_SOURCES.md`) — nothing to
    integrate there yet; revisit if a public API ever appears.
  - **Not yet verified on a real device:** need to actually import a real list
    file and confirm a matching test call shows "Known spam (imported list)"
    on the overlay.
- **2026-09-15: Phase 2's overlay banner ✅ finally VERIFIED working on a real
  incoming call** — the actual bug, found by careful reading of a device
  logcat (not by any of the platform/OEM theories chased beforehand): a
  **race condition** in `OverlayService`. `showInternal()` (already running on
  the main thread, since `show()` posts it there) called `dismiss()` to clear
  any previous banner — but `dismiss()` itself *posted* its cleanup to the
  main-thread queue instead of running it immediately. That queued cleanup
  then executed *after* `showInternal()` had gone on to create and add the
  new window, so it tore down the brand-new view instead of a stale one —
  every single time, hence the window reliably coming back `attached=false`
  a few milliseconds after a successful `addView()`. Fixed by splitting
  `dismiss()` into a public, thread-hopping version and a private
  `clearCurrent()` that `showInternal()` calls directly (synchronously, since
  it's already on the main thread) instead of going through `dismiss()`.
  - Along the way, several *wrong* theories were chased and ruled out before
    finding this: Samsung/OneUI blocking third-party overlays during a call
    (disproven — Truecaller does show a live card here when it holds the
    call-screening role); the process not being "foreground" enough when
    `CallScreeningService.onScreenCall()` adds the window (disproven — a
    dedicated `OverlayForegroundService` didn't change anything, because that
    was never the actual bug). Lesson: read the device log's own timestamps
    and call stacks before generalizing to a platform limitation.
  - Also added a tap-to-dismiss "✕" on the banner — without the race, the
    only way to dismiss it early had been the 45s auto-timeout, and the user
    got stuck looking at one on-device before this was added.
  - `OverlayForegroundService` (owns the overlay window as a real foreground
    component, not directly from the `CallScreeningService` callback) is kept
    even though it turned out not to be the fix — it's a reasonable thing to
    have regardless.
- **2026-09-15: Overlay redesigned as a rounded card + verified over the lock
  screen.** Restyled from a full-width bar to a Truecaller-style floating
  card: 16dp side margins, 16dp corner radius, `#0066FF` background, a top
  bar (status tag + tap-to-dismiss "✕"), a body row (circular initial avatar +
  bold name/number + subtitle), and a footer (source of the info — "Live
  lookup" / "From our DB" — + "SpamBlok" branding). Deliberately doesn't show
  a fabricated SIM/carrier indicator ("Airtel · SIM 2") since we don't
  actually have that data — the footer shows real info instead.
  Also found and fixed a real second bug during this pass: the overlay never
  appeared while the phone was locked, because `TYPE_APPLICATION_OVERLAY`
  windows stay behind the lock screen by default (the system InCallUI draws
  on top of it) — needs `FLAG_SHOW_WHEN_LOCKED` explicitly. Both the new card
  design and lock-screen visibility are now confirmed working on a real call.
- **2026-09-15: App restructured from a settings-style single screen into a
  real caller app.** Raw activity-log viewer removed from the main screen
  (backend `CallLogStore` logging kept, just not displayed). All
  SpamBlok-specific protection features (setup status, blocklist, imported
  spam list, known callers, debug test-overlay button) moved out of
  `MainActivity` into a new `SettingsActivity`, reached via a gear icon —
  modeled after a Truecaller reference screenshot the user shared, where the
  primary screen is just calls/search/dialer and protection settings live
  behind a gear icon. `MainActivity` is now a 2-tab (Calls / Messages) app:
  - **Calls tab**: search bar + "Dial" button, a horizontal "recents" avatar
    strip, and the call list (`CallLogRepository`, reads `CallLog.Calls`).
    Tapping a row opens an action sheet (Call / Add name / Edit name).
  - **Messages tab**: message list (`SmsRepository`, reads `Telephony.Sms`).
  - New shared `UiKit.kt` for programmatic View-building helpers (cards,
    avatars, pills, buttons) used by both `MainActivity` and
    `SettingsActivity`.
  - Fixed a crash on both tabs: `CallLogRepository`/`SmsRepository` originally
    passed `"$COLUMN DESC LIMIT $limit"` as the ContentResolver `sortOrder`,
    which this device's provider rejects (`IllegalArgumentException: Invalid
    token LIMIT`). Fixed by sorting only in `sortOrder` and capping via
    `out.size < limit` while iterating.
  - Added the ability to add/edit a name for a number directly from the Calls
    tab (`CallerRepository.setManualName()`), since system Contacts isn't
    always populated for every caller.
- **2026-09-15: [RESOLVED] Caller-name priority bug fixed** — a number saved
  to system Contacts after a call was still showing our own DB's name instead
  of the live Contacts name. Root cause (confirmed via suspect #1 from this
  same day's earlier entry): `READ_CONTACTS` was never actually granted on
  this device. `MainActivity.updateCallsPermissionCard()` only checked
  `READ_CALL_LOG` before showing the "Grant access" card — since the user had
  already granted `READ_CALL_LOG` before `READ_CONTACTS` was added to the
  same button, the card never reappeared and the user was never asked for
  Contacts access, so `resolveCaller()`'s `hasPermission()` gate silently
  skipped the live lookup every time. `ContactsRepository.lookupName()` and
  `resolveCaller()`'s priority order were both already correct — no bug there.
  Fix: `updateCallsPermissionCard()` now keeps the card visible (with
  Contacts-specific copy) until *both* permissions are granted, not just
  `READ_CALL_LOG`. Verified on-device: card reappeared, user granted
  `READ_CONTACTS`, Calls tab now shows the live Contacts name.
  - Installed via **release** build (`./gradlew assembleRelease` +
    `adb install -r app-release.apk`), not debug — this device had a
    release-signed APK installed already, so `assembleDebug` failed to
    install with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (signature mismatch).
    This project installs release builds on-device, not debug.
- **2026-09-15: Calls-tab search extended two ways — local DB/Contacts lookup
  for any typed number, and an on-demand Truecaller search fallback.**
  1. **Local:** search previously only filtered existing call-log rows. Now,
     when the typed text looks like a number (`looksLikeNumberSearch()`),
     `renderCallLog()` runs `resolveCaller()` directly against it (live
     Contacts + `CallerRepository`) and shows a result card even for a number
     that's never appeared in the call log, with Call/Add-name actions.
  2. **Truecaller fallback:** when that local lookup can't resolve a number,
     the same card now offers a "Search Truecaller" button. There's no
     documented public deep-link scheme for querying Truecaller — confirmed by
     web search and by `adb shell dumpsys package com.truecaller`, which lists
     `truecaller://` only for specific authorities (`premium`, `offerhub`,
     `truecallersdk`), not a generic number-search path. Instead, found (by
     testing live against the installed app) that firing an explicit
     `ACTION_VIEW` intent with `tel:<number>` at the exported component
     `com.truecaller/.DialerActivityAlias` opens Truecaller's own dialer
     screen **with a live caller-ID card already rendered for that number**
     (verified via `uiautomator dump` + a real search — resolved "Zomato" /
     "+918035735864" for a real number in testing). New `TruecallerSearchBridge`
     (one-shot callback + 4s timeout) arms before the intent fires;
     `BannerReaderService.onAccessibilityEvent()` — already reading
     Truecaller's screens for the live-call banner — now also checks
     `id/title`/`id/subtitle` (the caller-ID card's resource ids, found via
     the same `uiautomator dump`) whenever the bridge has a pending search,
     delivers the result, and `MainActivity` snaps back to the foreground
     (`FLAG_ACTIVITY_REORDER_TO_FRONT`) and saves it into `CallerRepository`
     (source `"truecaller-search"`) so future lookups are instant/local.
     Since Android can only expose a window's content once it's actually
     rendered, this needs a brief (~hundreds of ms) visible flash to
     Truecaller before returning — confirmed acceptable trade-off with the
     user given no free API returns real caller names (a real Truecaller API
     plan starts around $299/mo; free tiers like NumVerify only validate
     numbers, no name data).
     **Caveat:** `DialerActivityAlias` / `id/title` / `id/subtitle` are
     undocumented internals of the installed Truecaller build — not a stable
     public API. This can break silently on a Truecaller update; if the
     "Search Truecaller" button stops returning results, re-run
     `uiautomator dump` after firing the same test intent to check whether
     the resource ids changed.
- **2026-09-15: [BUG, found post-release via user report] Live-call overlay
  showed carrier spam-warning text ("Suspected Spam", "SPAM Alert from Jio")
  instead of the real name Truecaller itself displays for the same number
  (confirmed by the user checking Truecaller directly — "naukri.com" and
  "swiggy instamart" for two real calls).** Diagnosed using a new temporary
  "Debug: view captured caller log" button in Settings (there was no
  on-device viewer for `CallLogStore`'s existing capture log) — inspecting
  real captured events from the user's actual calls showed
  `com.samsung.android.incallui:id/name = "Suspected Spam"`. Root cause, two
  layers:
  1. Samsung's stock incallui reuses the *same* `id/name` accessibility field
     for three different things: a real caller name, its own carrier-sourced
     spam warning, or (when it has no identification at all) the bare phone
     number echoed back as a fallback. `BannerReaderService` treated all
     three as a real name — capturing, displaying, and persisting whichever
     one showed up.
  2. Once a bad "name" (spam label or bare number) got stored,
     `CallerRepository`'s existing mismatch-protection (intentionally keeps
     the first-seen name and only flags disagreement — see `isMismatch()`)
     then permanently blocked the *correct* name from ever overriding it,
     even one fetched later via "Search Truecaller".
  Fixed with new `SpamLabelHeuristics.looksLikeSpamWarning()` /
  `looksLikeBareNumber()`, used in three places: `BannerReaderService`
  (don't capture either as a name — spam-label text still surfaces live as
  a *label*, just never persisted), `MainActivity.resolveCaller()` (don't
  trust the call log's own `CACHED_NAME` column when it's either — the
  system writes the same bad values there too, independent of our own
  capture), and new `CallerRepository.purgeSpamLikeNames()` (one-time
  cleanup for already-polluted rows — clears the name and resets the
  mismatch count, run once from `MainActivity.onCreate`, no-op once clean).
  - While diagnosing, also found and fixed a related bug in the "Search
    Truecaller" flow (separate from the incallui issue above): Truecaller's
    own dialer screen briefly shows the searched number itself as a
    placeholder `id/title` before its network lookup resolves the real name
    — `TruecallerSearchBridge.deliver()` was accepting that placeholder as
    the final result and consuming the one-shot bridge before the real name
    ever arrived. Now it ignores an echoed-back number and keeps the bridge
    armed (timeout bumped 4s → 6s to give the real lookup more room).
  - Also added "Search Truecaller" directly to the existing per-row tap menu
    (Call / Add-name / Edit-name), not just the search-bar's not-found card
    — user-requested, so a call-log row can be looked up without retyping
    the number into search.
  - Verified end-to-end on-device against both numbers the user reported.
- **2026-09-15: Messages tab categorized (Personal/OTP/Bank/Other/Spam), tappable
  to read full text + Reply, and a new "Links" tab for URL safety checks.**
  - New offline `MessageClassifier.kt` (same no-network philosophy as
    `NumberHeuristics`): a dedicated **OTP** category (checked ahead of Bank —
    a bank's own OTP text mentions both, but the point of the tab is finding
    any OTP fast regardless of sender) but behind spam-keyword matching (so
    fake-OTP phishing still lands in Spam). **Personal** requires an actual
    mobile-number-shaped address (10, or 12/13 digits with country code) —
    a short numeric code (5-9 digits, common for Indian OTP/alert senders)
    is never treated as a person just for being all-digit. Messages tab gets
    a filter-chip row (`UiKit.chip`) and a category icon per row
    (`UiKit.iconAvatar` — bank/building/warning glyph; Personal still uses
    the name-initial avatar).
  - Message rows had no tap handler at all and truncated to 2 lines with no
    way to read the rest. Tapping now shows the full text (selectable) with
    **Reply** (hands off to the default SMS app via `ACTION_SENDTO`/`smsto:`,
    same pattern as Calls' "Call" handing off to the dialer — SpamBlok stays
    read-only, no `SEND_SMS` permission) and **Copy** buttons.
  - Real SMS auto-delete/expiry (user's original OTP-cleanup ask) was ruled
    out — only the default SMS app can delete from the system inbox on
    Android, out of scope; user chose to skip it and just get the OTP tab.
  - New third bottom-nav tab **Links**: paste a URL, check it against
    **Google Safe Browsing** (`LinkSafetyChecker.kt`, plain
    `HttpURLConnection` + `org.json`, no new dependencies) using the user's
    own free API key (`SafeBrowsingKeyStore.kt`, entered in Settings — the
    *only* feature in SpamBlok that sends anything off-device; explicitly
    chosen over offline-only heuristics after discussing the trade-off with
    the user). Needs `INTERNET` permission, added and scoped in the
    manifest comment to just this feature.
    - Google API key gotcha: an "Android apps"-restricted key needs the
      `X-Android-Package` / `X-Android-Cert` (SHA-1, no colons) headers set
      manually on a raw REST call — Google's official SDKs add these
      automatically but our plain `HttpURLConnection` didn't, so every
      request was silently rejected (400/403) even with a valid key until
      this was added.
    - Settings' API key field now hides the key once saved (masked "API key
      saved (••••••••)" + Edit/Delete) instead of always showing it in
      plain text.
  - Verified end-to-end on-device: Google's official Safe Browsing test URL
    (`testsafebrowsing.appspot.com/s/malware.html`) correctly flagged.
- **2026-09-15/16: System-wide link protection (LinkInterceptorActivity) —
  built, debugged through several real Android platform gotchas, and its
  actual coverage limits established with hard evidence, not guesswork.**
  Opt-in: registers for http/https `VIEW` intents; once the user holds
  Android's Browser role (`RoleManager.ROLE_BROWSER` — Settings card now
  requests this directly, see below), a tapped link anywhere checks via
  `LinkSafetyChecker` first, then forwards to the real browser (safe/
  couldn't-verify → fails open) or shows an "⚠️ Dangerous link" dialog
  (Open anyway / Cancel) for a flagged one. Animated "Checking link
  safety…" interstitial (pulsing shield + spinner in a card) replaced a
  plain white placeholder screen.
  - **Bug 1:** `launchMode="singleTask"` with no taskAffinity override
    merged the activity into SpamBlok's own task — cancelling a link check
    from another app would have surfaced SpamBlok's main Calls screen
    instead of returning to that app. Fixed with `android:taskAffinity=""`.
  - **Bug 2:** once SpamBlok actually held the Browser role, forwarding
    broke ("No browser found") — a generic `ACTION_VIEW` http/https query
    resolves to SpamBlok *only* once it's the assigned role holder (Android
    suppresses other candidates from that query), so excluding "self" from
    the results left nothing. Fixed by looking up real browsers via
    `CATEGORY_APP_BROWSER` (how launchers identify actual browser apps,
    independent of current default-handler resolution) and targeting each
    explicitly with `setPackage()`.
  - **Bug 3:** that fix alone still found zero browsers — Android 11+
    restricts `PackageManager` visibility into other apps' components unless
    declared via a manifest `<queries>` element. Added one for
    `ACTION_MAIN` + `CATEGORY_APP_BROWSER`. Confirmed via
    `adb shell cmd package query-activities` (full shell visibility, found
    Chrome/Brave/Samsung Internet) vs. the app's own query (found nothing)
    that this was exactly the gap.
  - **Bug 4 (Settings UX, not the interceptor):** the "link protection"
    Settings button linked to `ACTION_APPLICATION_DETAILS_SETTINGS` (App
    Info), which only covers Android App Links (verified website domains) —
    there's no generic-link "Open by default" entry there. The user found
    the actual control under Settings → Apps → Default apps → Browser app
    instead. Replaced the button with a direct
    `RoleManager.createRequestRoleIntent(ROLE_BROWSER)` request, mirroring
    the existing call-screening role request.
  - **Established limit (hard evidence, not assumption):** WhatsApp and
    Google Messages do **not** route through this at all. Confirmed via
    `adb shell dumpsys window` immediately after a real link tap:
    `mFocusedApp=com.whatsapp/.iab.IABWebCoreActivity` — WhatsApp's *own*
    in-app browser activity, inside WhatsApp's own package. This is not
    Chrome Custom Tabs (which would show a different app's activity) and
    not fixable via `RoleManager` or any Intent-based mechanism — no
    external Android Intent is ever dispatched, so no app (SpamBlok
    included) can intercept it. (A user-pasted alternative diagnosis
    claimed this was fixable via Custom Tabs support; checked against the
    actual on-device evidence and it doesn't hold up for WhatsApp
    specifically — see git log for the full back-and-forth.) The only
    remaining path for WhatsApp/Messages links is manually pasting into the
    Links tab; a real fix would mean accessibility-scraping WhatsApp's own
    in-app browser and drawing a warning overlay (same fragile pattern as
    the Truecaller-search hack, bigger scope) — user declined to build this
    for now.
  - Settings hint text corrected to state the WhatsApp/Messages limitation
    directly rather than imply full coverage.
- **2026-09-16: [UNRESOLVED — fix next session] Overlay banner shows even for
  a call from a number already saved with a name (should only show for
  unknown/spam-risk numbers, or should show a "known contact" variant
  instead of the generic verdict banner).** Not yet investigated — start
  here. Likely in `SpamBlokCallScreeningService.onScreenCall()` /
  `OverlayForegroundService` — currently the overlay always fires
  regardless of whether `CallerRepository.lookup()`/Contacts already
  identifies the number as a known, presumably-legitimate contact.
