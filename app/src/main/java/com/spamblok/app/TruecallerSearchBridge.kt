package com.spamblok.app

import android.os.Handler
import android.os.Looper

/**
 * One-shot bridge from [BannerReaderService]'s accessibility read of Truecaller's own
 * dialer/search screen back to whoever triggered an on-demand number search from
 * SpamBlok's UI ([MainActivity]). Nothing leaves the device or is transmitted anywhere —
 * this only carries a result already rendered on-screen in Truecaller's own app back
 * into ours, in-process.
 */
object TruecallerSearchBridge {
    private const val TIMEOUT_MS = 6000L

    private var pendingNumber: String? = null
    private var callback: ((name: String?, subtitle: String?) -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null

    /** Arms the bridge for one result. [onResult] fires exactly once, either with what
     * BannerReaderService captures or (name=null) after [TIMEOUT_MS] if nothing arrives —
     * e.g. Truecaller isn't installed, or its layout changed and nothing matched. */
    fun startSearch(number: String, onResult: (name: String?, subtitle: String?) -> Unit) {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        pendingNumber = CallerRepository.normalize(number)
        callback = onResult
        val runnable = Runnable { deliver(null, null) }
        timeoutRunnable = runnable
        handler.postDelayed(runnable, TIMEOUT_MS)
    }

    fun hasPending(): Boolean = callback != null

    /** Called from [BannerReaderService] when it reads Truecaller's caller-ID card.
     * Delivers once, then clears — a later Truecaller screen (e.g. the user browsing
     * around afterwards) won't trigger a second, stale callback. */
    fun deliver(name: String?, subtitle: String?) {
        val cb = callback ?: return
        // Truecaller's own screen briefly shows the searched number itself as a
        // placeholder "title" before its network lookup resolves the real name —
        // don't treat that echo as a result. Keep the bridge armed so a later,
        // real-name event (or the timeout, if none ever comes) can still deliver.
        if (name != null && CallerRepository.normalize(name) == pendingNumber) return
        callback = null
        pendingNumber = null
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
        handler.post { cb(name, subtitle) }
    }
}
