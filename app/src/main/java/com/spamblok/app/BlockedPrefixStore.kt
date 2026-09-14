package com.spamblok.app

import android.content.Context

/**
 * User-managed list of number PREFIXES to hard-block (e.g. "+9180" or "80" to
 * block every call whose number starts with the Bangalore STD code). Persisted in
 * SharedPreferences (private to the app) as digits-only strings. The actual
 * matching logic lives in [PrefixMatcher] (pure, unit-testable).
 */
object BlockedPrefixStore {

    private const val PREFS_NAME = "spamblok_blocklist"
    private const val KEY_PREFIXES = "prefixes"
    private const val MIN_PREFIX_DIGITS = 2

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun add(context: Context, rawPrefix: String): Boolean {
        val normalized = PrefixMatcher.normalizePrefix(rawPrefix)
        if (normalized.length < MIN_PREFIX_DIGITS) return false
        val p = prefs(context)
        val current = LinkedHashSet(p.getStringSet(KEY_PREFIXES, emptySet()).orEmpty())
        current.add(normalized)
        p.edit().putStringSet(KEY_PREFIXES, current).apply()
        return true
    }

    fun remove(context: Context, prefix: String) {
        val p = prefs(context)
        val current = LinkedHashSet(p.getStringSet(KEY_PREFIXES, emptySet()).orEmpty())
        current.remove(prefix)
        p.edit().putStringSet(KEY_PREFIXES, current).apply()
    }

    /** Sorted for stable UI display. */
    fun getAll(context: Context): List<String> =
        prefs(context).getStringSet(KEY_PREFIXES, emptySet()).orEmpty().sorted()

    /** The stored prefix (if any) that [number] matches. */
    fun matches(context: Context, number: String): String? =
        PrefixMatcher.match(prefs(context).getStringSet(KEY_PREFIXES, emptySet()).orEmpty(), number)
}
