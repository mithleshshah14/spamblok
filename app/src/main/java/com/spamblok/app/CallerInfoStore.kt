package com.spamblok.app

/**
 * In-process, in-memory hand-off between the Phase 2/3 data sources:
 *  - CallScreeningService: gets the incoming NUMBER first (system API, instant).
 *  - CallerRepository (Phase 3): our own on-device DB — if we've seen this number
 *    before, its name/label is available immediately, no waiting for a banner.
 *  - BannerReaderService (accessibility): gets the NAME/label a moment later,
 *    once the Truecaller/in-call overlay actually renders — this is still what
 *    populates the DB in the first place, and overrides a DB-sourced guess if
 *    the two ever disagree (see [CallerRepository.observe]'s mismatch handling).
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
        val source: String? = null, // "db" or "banner" — where the current name/label came from
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

    /** Called by BannerReaderService when it reads a name/label off the overlay.
     * Always wins over a "db" sourced guess, since it's the fresher, ground-truth read. */
    fun onBannerCaptured(name: String?, label: String?, callState: String?, bannerNumber: String?) {
        synchronized(lock) {
            val withinWindow = System.currentTimeMillis() - current.updatedAtMillis <= CORRELATION_WINDOW_MS
            val number = current.number.takeIf { withinWindow } ?: bannerNumber
            current = current.copy(
                number = number ?: current.number,
                name = name ?: current.name,
                label = label ?: current.label,
                callState = callState ?: current.callState,
                source = if (name != null || label != null) "banner" else current.source,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
        notifyListener()
    }

    /** Called by SpamBlokCallScreeningService right after [onNumberScreened] when
     * our own DB already has a name/label for this number — shows instantly
     * instead of the overlay's "Looking up name…" placeholder. A later banner
     * read (if one arrives) still overrides this via [onBannerCaptured]. */
    fun onDbLookup(name: String?, label: String?) {
        synchronized(lock) {
            current = current.copy(name = name, label = label, source = "db", updatedAtMillis = System.currentTimeMillis())
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
