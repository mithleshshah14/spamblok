package com.spamblok.app

/**
 * Pure-Kotlin, offline classification of an SMS into a category for the Messages
 * tab — same philosophy as [NumberHeuristics]: no network, no ML, just patterns.
 * Mirrors the Truecaller-style inbox split the user asked for (Personal / Bank /
 * Other organization / Spam).
 *
 * Indian SMS senders come in two shapes: a plain phone number (a person), or a
 * short alphanumeric DLT-registered sender ID like "AD-HDFCBK" or "TXSODEXO" (a
 * business/transactional header — never a real phone number). That distinction
 * is the first, most reliable signal; keyword matching narrows it further.
 */
object MessageClassifier {

    enum class Category { PERSONAL, OTP, BANK, ORGANIZATION, SPAM }

    private val OTP_KEYWORDS = listOf(
        "otp", "one time password", "one-time password", "verification code",
        "security code", "verification pin",
    )

    private val BANK_KEYWORDS = listOf(
        "hdfc", "icici", "sbi", "axis", "kotak", "pnb", "bob", "canara", "yesbank",
        "yes bank", "idbi", "indusind", "rbl", "federal bank", "iob", "uco",
        "boi", "centralbank", "idfc", "au bank", "bandhan", "dbs", "hsbc",
        "citibank", "standard chartered", "paytm bank", "airtel bank", "bank",
    )

    private val SPAM_KEYWORDS = listOf(
        "congratulations you", "you have won", "claim your prize", "lottery",
        "loan approved", "pre-approved loan", "instant loan", "credit card offer",
        "click here", "bit.ly", "tinyurl", "limited time offer", "cashback offer",
        "act now", "download now", "install now", "kbc lottery", "lucky winner",
        "free gift", "get rich", "earn from home", "work from home",
    )

    /** Roughly "this address is a real mobile number, not a business sender ID" —
     * mostly digits (allowing +, spaces, hyphens for formatting) AND in the digit
     * range an actual mobile number falls in (10 local, 12/13 with country code).
     * A short numeric code (5-9 digits — common for Indian OTP/verification/alert
     * senders, e.g. "5727312") is NOT a phone number a person dials from; treating
     * it as one would misclassify automated senders as Personal just because
     * they're all-digit. */
    private fun looksLikePhoneNumber(address: String): Boolean {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return false
        val digitCount = trimmed.count { it.isDigit() }
        val otherCount = trimmed.count { !it.isDigit() && it !in "+ -()" }
        return digitCount in 10..13 && otherCount == 0
    }

    fun classify(address: String, body: String, isKnownSpamNumber: Boolean): Category {
        if (isKnownSpamNumber) return Category.SPAM

        val haystack = "$address $body".lowercase()
        // Spam keywords are checked first, ahead of OTP/bank: a message forging OTP
        // or bank language to look trustworthy ("your OTP prize is ready, click
        // here...") is exactly the kind of thing that needs to keep landing in Spam,
        // not get waved through because it also mentions "OTP".
        if (SPAM_KEYWORDS.any { haystack.contains(it) }) return Category.SPAM
        // OTP ahead of Bank: a bank's own OTP text ("SECRET OTP for txn...ICICI
        // Bank...") mentions both, but the whole point of a dedicated OTP tab is
        // finding any OTP fast regardless of who sent it — it shouldn't be split
        // across Bank vs OTP depending on the sender.
        if (OTP_KEYWORDS.any { haystack.contains(it) }) return Category.OTP
        if (BANK_KEYWORDS.any { haystack.contains(it) }) return Category.BANK

        return if (looksLikePhoneNumber(address)) Category.PERSONAL else Category.ORGANIZATION
    }
}
