package com.spamblok.app

/**
 * Shared check for "this text is a carrier/OEM spam warning, not a real caller
 * name" — used both where [BannerReaderService] reads Samsung's incallui (whose
 * "id/name" field doubles as a spam-warning slot) and where [MainActivity]
 * resolves a call-log row's `CallLog.Calls.CACHED_NAME` (the same warning text
 * — e.g. "Suspected Spam", "SPAM Alert from Jio" — gets written into that system
 * column too, independent of our own BannerReaderService).
 */
object SpamLabelHeuristics {
    private val PATTERNS = listOf("spam", "scam", "fraud", "spoofed", "robocall")

    fun looksLikeSpamWarning(text: String?): Boolean =
        text != null && PATTERNS.any { text.contains(it, ignoreCase = true) }

    /** True for text that's just a phone number (no letters at all) rather than a
     * real name — e.g. Samsung's incallui echoes the number itself into its "name"
     * field as a fallback when it has no real identification for the caller. */
    fun looksLikeBareNumber(text: String?): Boolean =
        text != null && text.any { it.isDigit() } && text.none { it.isLetter() }
}
