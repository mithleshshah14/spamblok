package com.spamblok.app

import android.content.Context

/** Stores the user's own Google Safe Browsing API key (see [LinkSafetyChecker]).
 * Plain SharedPreferences — the key never leaves the device except as part of the
 * user-initiated request to Google's API itself. */
object SafeBrowsingKeyStore {
    private const val PREFS = "safe_browsing_prefs"
    private const val KEY_API_KEY = "api_key"

    fun get(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }

    fun set(context: Context, apiKey: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_API_KEY, apiKey.trim()).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_API_KEY).apply()
    }
}
