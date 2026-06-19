package com.spamblok.app

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Phase 1 — the whole point of this build.
 *
 * Whenever a window from Truecaller appears or changes (which includes its
 * incoming-call / caller-ID overlay shown WHILE the phone is ringing), we walk
 * that window's view tree and log every piece of text we find. We then read the
 * log (`adb logcat -s SpamBlokA11y`) and check: is the caller's NAME in there?
 *
 * If yes -> the SpamBlok approach is viable, proceed to Phase 2.
 * If no  -> the name is likely drawn as an image / secure surface; rethink.
 *
 * This service ONLY logs. It stores nothing and sends nothing.
 */
class BannerReaderService : AccessibilityService() {

    companion object {
        private const val TAG = "SpamBlokA11y"
        private const val TRUECALLER_HINT = "truecaller"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkg = event.packageName?.toString().orEmpty()
        // Match com.truecaller and any regional variant, ignore everything else.
        if (!pkg.contains(TRUECALLER_HINT, ignoreCase = true)) return

        Log.d(TAG, "==== event from '$pkg' type=${AccessibilityEvent.eventTypeToString(event.eventType)} ====")

        val found = mutableListOf<String>()

        // 1) Text carried directly on the event.
        event.text?.forEach { cs ->
            cs?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { found.add("event.text: $it") }
        }

        // 2) Walk the active window's node tree.
        val root: AccessibilityNodeInfo? = rootInActiveWindow
        if (root != null) {
            collectText(root, found)
        } else {
            Log.d(TAG, "(rootInActiveWindow was null)")
        }

        if (found.isEmpty()) {
            Log.d(TAG, "(no readable text found — name may be an image/secure surface)")
        } else {
            found.forEach { Log.d(TAG, it) }
        }
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
        // No-op: we don't hold any interruptible work.
    }
}
