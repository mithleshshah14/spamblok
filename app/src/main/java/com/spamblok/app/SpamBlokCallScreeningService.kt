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
 * shows our own overlay banner; [BannerReaderService] fills in the caller NAME a
 * little later, once Truecaller's/the in-call UI's overlay actually renders.
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

        val verdict = NumberHeuristics.classify(number)
        Log.d(TAG, "onScreenCall: $number -> ${verdict.verdict} (${verdict.label})")

        CallerInfoStore.onNumberScreened(number)
        OverlayService.show(this, number, verdict)
    }
}
