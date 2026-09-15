package com.spamblok.app

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks a user-pasted URL against Google's Safe Browsing v4 API — the same
 * database Chrome/Android use for their "Dangerous site" warnings. This is the
 * one place in SpamBlok that talks to the network: the URL you type here is sent
 * to Google (with your own API key, see [SafeBrowsingKeyStore]); nothing else
 * about your device or messages is sent anywhere.
 */
object LinkSafetyChecker {

    enum class Status { SAFE, UNSAFE, ERROR }

    data class Verdict(val status: Status, val threatTypes: List<String>, val message: String)

    private const val ENDPOINT = "https://safebrowsing.googleapis.com/v4/threatMatches:find"
    private val THREAT_TYPES = listOf("MALWARE", "SOCIAL_ENGINEERING", "UNWANTED_SOFTWARE", "POTENTIALLY_HARMFUL_APPLICATION")

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Normalizes bare input like "example.com" into a checkable URL. Returns null
     * if there's nothing url-shaped to check at all. */
    fun normalize(rawInput: String): String? {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return null
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    }

    /** [onResult] is always called on the main thread. */
    fun check(apiKey: String, rawUrl: String, onResult: (Verdict) -> Unit) {
        val url = normalize(rawUrl)
        if (url == null) {
            onResult(Verdict(Status.ERROR, emptyList(), "Enter a URL to check."))
            return
        }
        Thread {
            val verdict = try {
                queryOnce(apiKey, url)
            } catch (e: Exception) {
                Verdict(Status.ERROR, emptyList(), "Couldn't reach Safe Browsing: ${e.message ?: "network error"}")
            }
            mainHandler.post { onResult(verdict) }
        }.start()
    }

    private fun queryOnce(apiKey: String, url: String): Verdict {
        val requestBody = JSONObject().apply {
            put(
                "client",
                JSONObject().apply {
                    put("clientId", "spamblok-app")
                    put("clientVersion", "1.0.0")
                },
            )
            put(
                "threatInfo",
                JSONObject().apply {
                    put("threatTypes", JSONArray(THREAT_TYPES))
                    put("platformTypes", JSONArray(listOf("ANY_PLATFORM")))
                    put("threatEntryTypes", JSONArray(listOf("URL")))
                    put("threatEntries", JSONArray().put(JSONObject().apply { put("url", url) }))
                },
            )
        }

        val connection = URL("$ENDPOINT?key=$apiKey").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        // Required when the API key is restricted to "Android apps" in Google Cloud
        // Console: that restriction is enforced via these two headers on a plain REST
        // call, not automatically — without them Google rejects the request outright
        // (400/403) even with an otherwise-valid key.
        connection.setRequestProperty("X-Android-Package", "com.spamblok.app")
        connection.setRequestProperty("X-Android-Cert", "E7C0AB4579D2409F99EAFBA620CC6E84982CF269")
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        try {
            OutputStreamWriter(connection.outputStream).use { it.write(requestBody.toString()) }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (responseCode !in 200..299) {
                val detail = try {
                    JSONObject(responseText.ifBlank { "{}" }).optJSONObject("error")?.optString("message")
                } catch (e: Exception) {
                    null
                }
                val prefix = if (responseCode == 400 || responseCode == 403) "API key rejected" else "Safe Browsing error (HTTP $responseCode)"
                return Verdict(Status.ERROR, emptyList(), if (detail.isNullOrBlank()) "$prefix." else "$prefix: $detail")
            }

            val matches = JSONObject(responseText.ifBlank { "{}" }).optJSONArray("matches")
            if (matches == null || matches.length() == 0) {
                return Verdict(Status.SAFE, emptyList(), "No known threats found for this link.")
            }

            val threatTypes = (0 until matches.length()).map { matches.getJSONObject(it).optString("threatType", "UNKNOWN") }.distinct()
            return Verdict(Status.UNSAFE, threatTypes, "Google Safe Browsing flags this link as dangerous.")
        } finally {
            connection.disconnect()
        }
    }
}
