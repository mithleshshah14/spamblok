package com.spamblok.app

/**
 * Pure-Kotlin spam/category heuristics based on the Indian numbering plan.
 * No database, no network — privacy-perfect, works offline, never breaks.
 * This is the cheapest, most-private layer of SpamBlok's data strategy
 * (see DATA_SOURCES.md): it flags a *category* from the number alone.
 *
 * Regulatory basis (TRAI / DoT):
 *  - 140-series (140xxxxxxx): reserved for PROMOTIONAL / TELEMARKETING calls.
 *    A call from such a number is, by definition, telemarketing.
 *  - 160-series (160xxxxxxx, incl. 1600xxxxxx): assigned for TRANSACTIONAL /
 *    SERVICE calls from banks, financial institutions and government — i.e.
 *    "expected legit" service calls, not spam.
 *
 * Known limitation: a plain 10-digit number can be either a mobile (leading
 * 6-9) or a landline with an area code (e.g. Bengaluru 080 -> 80xxxxxxxx). We do
 * NOT try to distinguish those here — both are returned NEUTRAL (no signal),
 * which is harmless because we never block on a neutral verdict.
 */
object NumberHeuristics {

    enum class Verdict { LIKELY_SPAM, LIKELY_SERVICE, SUSPICIOUS, NEUTRAL }

    data class Result(
        val verdict: Verdict,
        val label: String,
        val reason: String,
        val confidence: Int, // 0..100, how sure we are of the label
    )

    private data class Parsed(val national: String, val isForeign: Boolean)

    fun classify(raw: String): Result {
        val parsed = parse(raw)
        val n = parsed.national

        if (parsed.isForeign) {
            return Result(
                Verdict.SUSPICIOUS,
                "International call",
                "Not an Indian (+91) number; international calls are a common scam vector.",
                55,
            )
        }

        if (n.isEmpty()) {
            return Result(Verdict.SUSPICIOUS, "Unknown", "No usable digits in the number.", 40)
        }

        if (n.startsWith("140")) {
            return Result(
                Verdict.LIKELY_SPAM,
                "Telemarketer (140)",
                "The 140 series is reserved by TRAI for promotional/telemarketing calls.",
                90,
            )
        }

        if (n.startsWith("160")) {
            return Result(
                Verdict.LIKELY_SERVICE,
                "Service/Bank (160)",
                "The 160 series is assigned for transactional/service calls (banks, govt).",
                80,
            )
        }

        // Standard 10-digit number (mobile or landline-with-area-code): no signal.
        if (n.length == 10 && n[0] in '2'..'9') {
            return Result(
                Verdict.NEUTRAL,
                "Standard number",
                "Normal 10-digit Indian number; no pattern-based signal.",
                0,
            )
        }

        // Short codes (e.g. 121, 198): operator/service messages, usually legit.
        if (n.length in 3..6) {
            return Result(
                Verdict.NEUTRAL,
                "Short code",
                "Short code (telecom/service); typically operator or service messages.",
                10,
            )
        }

        // Local landline without area code (7-9 digits): no signal.
        if (n.length in 7..9 && n[0] in '2'..'9') {
            return Result(
                Verdict.NEUTRAL,
                "Local number",
                "Looks like a local landline without area code; no pattern-based signal.",
                0,
            )
        }

        return Result(
            Verdict.SUSPICIOUS,
            "Unusual number",
            "Number doesn't match a normal Indian mobile/landline format.",
            50,
        )
    }

    /** Strip formatting, country code (+91/91), and trunk prefix (0) to a national number. */
    private fun parse(raw: String): Parsed {
        val trimmed = raw.trim()
        val explicitIntl = trimmed.startsWith("+") || trimmed.startsWith("00")
        var digits = trimmed.filter { it.isDigit() }

        if (digits.startsWith("00")) digits = digits.drop(2)

        // India: +91 / 91 followed by a full national number.
        if (digits.startsWith("91") && digits.length >= 12) {
            return Parsed(digits.drop(2), isForeign = false)
        }
        // National trunk prefix: 0XXXXXXXXXX
        if (digits.startsWith("0") && digits.length >= 11) {
            return Parsed(digits.drop(1), isForeign = false)
        }
        // Explicitly international and not +91 -> foreign.
        if (explicitIntl && !digits.startsWith("91")) {
            return Parsed(digits, isForeign = true)
        }
        // Bare number: assume Indian local/national.
        return Parsed(digits, isForeign = false)
    }
}
