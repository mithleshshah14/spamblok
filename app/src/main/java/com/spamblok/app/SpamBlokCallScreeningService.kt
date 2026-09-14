package com.spamblok.app

import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log

/**
 * Phase 2 — registers SpamBlok as the system call-screening app so we get the
 * incoming NUMBER the moment a call rings, straight from Telecom (no accessibility
 * tricks needed for this part).
 *
 * A number matching a user-defined prefix in [BlockedPrefixStore] is rejected
 * outright and silently (no ring, no notification, no overlay). Everything else is
 * always allowed through — SpamBlok doesn't act on its own heuristic/Truecaller
 * verdicts yet, it just records the number + a quick offline heuristic verdict and
 * shows our own overlay banner.
 *
 * Phase 3 — before showing the overlay, we check [CallerRepository] (our own
 * on-device DB of numbers we've previously seen via the banner). A hit shows the
 * name/label immediately, with no "Looking up name…" wait; either way,
 * [BannerReaderService] still reads the live banner a little later, which fills
 * the DB in for next time and overrides a DB guess if it ever disagrees.
 *
 * Phase 4 — a number found in the user-imported [SpamNumberListStore] (an
 * offline spam-number list the user downloaded and imported themselves — see
 * DATA_SOURCES.md) upgrades the heuristic verdict shown on the overlay, the
 * same way a `140` telemarketer prefix does. It doesn't block the call outright;
 * only the user's own [BlockedPrefixStore] does that.
 */
class SpamBlokCallScreeningService : CallScreeningService() {

    companion object {
        private const val TAG = "SpamBlokScreening"
    }

    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart

        if (number.isNullOrBlank()) {
            respondToCall(callDetails, CallResponse.Builder().build())
            Log.d(TAG, "onScreenCall: no number available")
            return
        }

        val matchedPrefix = BlockedPrefixStore.matches(this, number)
        if (matchedPrefix != null) {
            Log.d(TAG, "onScreenCall: $number BLOCKED (matched prefix '$matchedPrefix')")
            respondToCall(
                callDetails,
                CallResponse.Builder()
                    .setDisallowCall(true)
                    .setRejectCall(true)
                    .setSkipNotification(true)
                    .build(),
            )
            CallLogStore.append(
                this,
                "── ${CallLogStore.timestamp()}  BLOCKED  $number (matched prefix '$matchedPrefix')\n\n",
            )
            return
        }

        // Not blocked — Phase 2 is "show info" for everything else, not "block".
        respondToCall(callDetails, CallResponse.Builder().build())

        var verdict = NumberHeuristics.classify(number)
        if (SpamNumberListStore.contains(this, number)) {
            verdict = verdict.copy(
                verdict = NumberHeuristics.Verdict.LIKELY_SPAM,
                label = "Known spam (imported list)",
                reason = "Matches a number in the imported spam-number list.",
                confidence = 95,
            )
        }
        Log.d(TAG, "onScreenCall: $number -> ${verdict.verdict} (${verdict.label})")

        CallerInfoStore.onNumberScreened(number)

        val known = CallerRepository.lookup(this, number)
        if (known != null && (known.name != null || known.label != null)) {
            Log.d(TAG, "onScreenCall: $number known from our DB -> ${known.name ?: known.label}")
            CallerInfoStore.onDbLookup(known.name, known.label)
        }

        // Started as a real foreground service (own notification), not called
        // directly here — see OverlayForegroundService's doc comment for why.
        OverlayForegroundService.start(this, number, verdict)
    }
}
