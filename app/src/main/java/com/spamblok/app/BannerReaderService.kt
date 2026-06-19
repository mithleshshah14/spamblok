package com.spamblok.app

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Phase 1 — reads caller info shown by the dialer / Truecaller and records it.
 *
 * When a window from Truecaller or the in-call UI appears or changes, we walk its
 * view tree and collect every piece of text. We log it (adb logcat -s SpamBlokA11y)
 * AND append it to an on-device file (CallLogStore) so it can be reviewed on the
 * phone itself without a laptop.
 *
 * The file lives in the app's private internal storage. Nothing is uploaded.
 */
class BannerReaderService : AccessibilityService() {

    companion object {
        private const val TAG = "SpamBlokA11y"
        // Match Truecaller + the in-call/dialer screens (covers the ring-time name).
        private val PACKAGE_HINTS = listOf("truecaller", "incallui", "dialer")
    }

    /** Last block written, to skip the many duplicate CONTENT_CHANGED events. */
    private var lastSignature: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkg = event.packageName?.toString().orEmpty()
        if (PACKAGE_HINTS.none { pkg.contains(it, ignoreCase = true) }) return

        val typeName = AccessibilityEvent.eventTypeToString(event.eventType)
        Log.d(TAG, "==== event from '$pkg' type=$typeName ====")

        val found = mutableListOf<String>()

        event.text?.forEach { cs ->
            cs?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { found.add("event.text: $it") }
        }

        val root: AccessibilityNodeInfo? = rootInActiveWindow
        if (root != null) {
            collectText(root, found)
        }

        if (found.isEmpty()) {
            Log.d(TAG, "(no readable text found)")
            return
        }

        found.forEach { Log.d(TAG, it) }

        // Dedupe: skip if identical to the last block we recorded.
        val signature = "$pkg|$typeName|" + found.joinToString("|")
        if (signature == lastSignature) return
        lastSignature = signature

        val block = buildString {
            append("── ${CallLogStore.timestamp()}  $pkg  $typeName\n")
            found.forEach { append("   $it\n") }
            append("\n")
        }
        CallLogStore.append(this, block)
    }

    private fun collectText(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        if (node == null) return

        val id = node.viewIdResourceName ?: "?"
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            out.add("text [$id]: $it")
        }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            out.add("desc [$id]: $it")
        }

        for (i in 0 until node.childCount) {
            collectText(node.getChild(i), out)
        }
    }

    override fun onInterrupt() {
        // No-op.
    }
}
