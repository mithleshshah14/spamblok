package com.spamblok.app

/**
 * In-process, in-memory hand-off between the two Phase 2 data sources:
 *  - CallScreeningService: gets the incoming NUMBER first (system API, instant).
 *  - BannerReaderService (accessibility): gets the NAME/label a moment later,
 *    once the Truecaller/in-call overlay actually renders.
 *
 * There is no cross-process IPC here — both run in this app's own process, so a
 * simple synchronized singleton with a listener is enough to let the overlay
 * update live as the name arrives.
 */
object CallerInfoStore {

    data class CallerInfo(
        val number: String? = null,
        val name: String? = null,
        val label: String? = null, // e.g. "Reported as Fraud"
        val callState: String? = null,
        val updatedAtMillis: Long = System.currentTimeMillis(),
    )

    /** How long a captured number stays "current" for the purpose of matching a
     * later-arriving name from the accessibility banner. A new incoming call
     * always resets this anyway, so this just guards against stale hand-off. */
    private const val CORRELATION_WINDOW_MS = 30_000L

    private val lock = Any()
    private var current: CallerInfo = CallerInfo()
    private var listener: ((CallerInfo) -> Unit)? = null

    fun onNumberScreened(number: String) {
        synchronized(lock) {
            current = CallerInfo(number = number, updatedAtMillis = System.currentTimeMillis())
        }
        notifyListener()
    }

    /** Called by BannerReaderService when it reads a name/label off the overlay. */
    fun onBannerCaptured(name: String?, label: String?, callState: String?, bannerNumber: String?) {
        synchronized(lock) {
            val withinWindow = System.currentTimeMillis() - current.updatedAtMillis <= CORRELATION_WINDOW_MS
            val number = current.number.takeIf { withinWindow } ?: bannerNumber
            current = current.copy(
                number = number ?: current.number,
                name = name ?: current.name,
                label = label ?: current.label,
                callState = callState ?: current.callState,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
        notifyListener()
    }

    fun snapshot(): CallerInfo = synchronized(lock) { current }

    fun clear() {
        synchronized(lock) { current = CallerInfo() }
        notifyListener()
    }

    /** Only one observer at a time — the overlay, while it's showing. */
    fun setListener(l: ((CallerInfo) -> Unit)?) {
        synchronized(lock) { listener = l }
    }

    private fun notifyListener() {
        val l: ((CallerInfo) -> Unit)?
        val snap: CallerInfo
        synchronized(lock) {
            l = listener
            snap = current
        }
        l?.invoke(snap)
    }
}
