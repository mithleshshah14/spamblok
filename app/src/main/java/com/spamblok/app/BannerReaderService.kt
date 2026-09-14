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

        val name = byId["id/name"]
        val label = byId["id/location_info"]
        val number = byId["id/phone_number"]
        if (name != null || label != null || callState != null) {
            CallerInfoStore.onBannerCaptured(name, label, callState, number)
        }

        // Phase 3: store every number->name/label we observe off the banner, so a
        // later call from the same number can be answered from our own DB first.
        if (number != null && (name != null || label != null)) {
            CallerRepository.observe(this, number, name, label, source = "banner:$pkg")
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
