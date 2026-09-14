package com.spamblok.app

/**
 * Pure number-prefix matching logic, split out from [BlockedPrefixStore] so it can
 * be unit-tested without an Android `Context`/SharedPreferences.
 */
object PrefixMatcher {

    /** Strip everything but digits (drops "+", spaces, dashes, parens). */
    fun normalizePrefix(raw: String): String = raw.filter { it.isDigit() }

    /** First stored prefix (if any) that matches [number] under any normalized form. */
    fun match(prefixes: Collection<String>, number: String): String? {
        if (prefixes.isEmpty()) return null
        val candidates = numberVariants(number)
        return prefixes.firstOrNull { prefix -> candidates.any { it.startsWith(prefix) } }
    }

    /** A handful of normalized digit-forms of [number], covering the common
     * with/without-country-code and with/without-trunk-zero variations. */
    private fun numberVariants(number: String): Set<String> {
        val d = number.filter { it.isDigit() }
        if (d.isEmpty()) return emptySet()

        val variants = mutableSetOf(d)
        // "+91XXXXXXXXXX" / "91XXXXXXXXXX" -> national form without country code.
        if (d.startsWith("91") && d.length >= 12) {
            variants.add(d.removePrefix("91"))
        }
        // Bare 10-digit mobile/landline -> also try with the country code prefixed.
        if (d.length == 10) {
            variants.add("91$d")
        }
        // Locally-dialed "0XXXXXXXXXX" -> drop the trunk zero too.
        if (d.startsWith("0") && d.length in 8..11) {
            variants.add(d.trimStart('0'))
        }
        return variants
    }
}
