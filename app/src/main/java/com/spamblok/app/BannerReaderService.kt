package com.spamblok.app

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Reads caller info shown by the in-call UI / Truecaller overlay and records it.
 *
 * When a window from Truecaller or the in-call UI appears or changes, we walk its
 * view tree and collect every piece of text, keyed by view id where available. We
 * log it (adb logcat -s SpamBlokA11y), append it to an on-device file (CallLogStore)
 * for on-phone review, and (Phase 2) push the structured name/label into
 * [CallerInfoStore] so it can be joined with the number from [SpamBlokCallScreeningService]
 * and shown on our own overlay.
 *
 * The file lives in the app's private internal storage. Nothing is uploaded.
 */
class BannerReaderService : AccessibilityService() {

    companion object {
        private const val TAG = "SpamBlokA11y"
        // Only the in-call UI and Truecaller — NOT the generic "dialer" package, whose
        // call-log/contacts screens produced ~1,400 lines of irrelevant noise in Phase 1.
        private val PACKAGE_HINTS = listOf("incallui", "truecaller")
    }

    /** Last block written, to skip the many duplicate CONTENT_CHANGED events. */
    private var lastSignature: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkg = event.packageName?.toString().orEmpty()
        if (PACKAGE_HINTS.none { pkg.contains(it, ignoreCase = true) }) return

        val typeName = AccessibilityEvent.eventTypeToString(event.eventType)

        val found = mutableListOf<String>()
        val byId = mutableMapOf<String, String>()

        event.text?.forEach { cs ->
            cs?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { found.add("event.text: $it") }
        }

        val root: AccessibilityNodeInfo? = rootInActiveWindow
        if (root != null) {
            collectText(root, found, byId)
        }

        if (found.isEmpty()) return

        // On-demand number search (MainActivity's "Search Truecaller" button): while
        // armed, Truecaller's own dialer screen shows a caller-ID card under these ids
        // for the number we just launched it with — hand it back and stop, regardless
        // of the live-call gate below (this isn't a call, so there's no call_state).
        if (TruecallerSearchBridge.hasPending() && pkg.contains("truecaller", ignoreCase = true)) {
            val title = byId["id/title"] ?: byId["id/name"]
            if (title != null) {
                val subtitle = (byId["id/subtitle"] ?: byId["id/location_info"] ?: byId["id/number_type"])?.trim()
                Log.d(TAG, "[search] resolved '$title' / '$subtitle' from Truecaller")
                TruecallerSearchBridge.deliver(title, subtitle)
            }
        }

        // Only care about screens that actually look like a live call (ringing or
        // active) — this is what the Phase 1 roadmap flagged for the noise fix.
        val callState = byId["id/call_state"]
        val looksLikeLiveCall = callState != null || byId.keys.any { it.contains("call_state") }
        if (!looksLikeLiveCall) return

        Log.d(TAG, "==== event from '$pkg' type=$typeName state=$callState ====")
        found.forEach { Log.d(TAG, it) }

        // Dedupe: skip if identical to the last block we recorded.
        val signature = "$pkg|$typeName|" + found.joinToString("|")
        if (signature != lastSignature) {
            lastSignature = signature
            val block = buildString {
                append("── ${CallLogStore.timestamp()}  $pkg  $typeName\n")
                found.forEach { append("   $it\n") }
                append("\n")
            }
            CallLogStore.append(this, block)
        }

        // Samsung's stock incallui (Jio and others' carrier-network spam detection)
        // reuses this SAME "id/name" field to show a warning like "Suspected Spam" or
        // "SPAM Alert from Jio" instead of a real caller name — it isn't Truecaller,
        // and there's no separate field to tell the two apart structurally. Treat
        // anything that looks like a spam warning as a label, never a name: showing
        // it as the "name" overwrote the real (Truecaller-sourced) identification and
        // stored the warning text itself into our DB as if it were the caller's name.
        val rawName = byId["id/name"]
        val isSpamWarning = SpamLabelHeuristics.looksLikeSpamWarning(rawName)
        // Also reject a "name" that's just the phone number echoed back — Samsung's
        // incallui does this as a fallback when it has no real identification, and
        // once stored, CallerRepository's mismatch-protection would otherwise lock
        // it in and refuse to let a later, real name (e.g. from Search Truecaller)
        // ever override it.
        val isBareNumber = SpamLabelHeuristics.looksLikeBareNumber(rawName)
        val name = rawName?.takeIf { !isSpamWarning && !isBareNumber }
        val locationLabel = byId["id/location_info"]
        val number = byId["id/phone_number"]
        if (name != null || locationLabel != null || isSpamWarning || callState != null) {
            CallerInfoStore.onBannerCaptured(name, locationLabel ?: rawName.takeIf { isSpamWarning }, callState, number)
        }

        // Phase 3: store every number->name/label we observe off the banner, so a
        // later call from the same number can be answered from our own DB first.
        // Never persist the carrier spam-warning text itself as a name/label.
        if (number != null && (name != null || locationLabel != null)) {
            CallerRepository.observe(this, number, name, locationLabel, source = "banner:$pkg")
                ?.let { note -> CallLogStore.append(this, "── ${CallLogStore.timestamp()}  DB  $note\n\n") }
        }
    }

    private fun collectText(
        node: AccessibilityNodeInfo?,
        out: MutableList<String>,
        byId: MutableMap<String, String>,
    ) {
        if (node == null) return

        val id = node.viewIdResourceName
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
            out.add("text [${id ?: "?"}]: $text")
            id?.let { byId[shortId(it)] = text }
        }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { desc ->
            out.add("desc [${id ?: "?"}]: $desc")
        }

        for (i in 0 until node.childCount) {
            collectText(node.getChild(i), out, byId)
        }
    }

    /** "com.samsung.android.incallui:id/name" -> "id/name" */
    private fun shortId(fullId: String): String = fullId.substringAfter(':', fullId)

    override fun onInterrupt() {
        // No-op.
    }
}
