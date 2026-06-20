package com.spamblok.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tiny append-only log persisted in the app's PRIVATE internal storage
 * (context.filesDir). Not world-readable, never uploaded, removed on uninstall.
 * Lets us review captured caller info on the phone itself, without adb/logcat.
 */
object CallLogStore {

    private const val FILE_NAME = "caller_log.txt"
    private const val MAX_BYTES = 256 * 1024 // keep the file small; trim oldest beyond this

    private val tsFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    private val lock = Any()

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun append(context: Context, block: String) {
        synchronized(lock) {
            val f = file(context)
            f.appendText(block)
            if (f.length() > MAX_BYTES) {
                // Keep only the most recent half so the file never grows unbounded.
                val keep = f.readText().takeLast(MAX_BYTES / 2)
                f.writeText("…(older entries trimmed)…\n$keep")
            }
        }
    }

    fun read(context: Context): String {
        synchronized(lock) {
            val f = file(context)
            return if (f.exists()) f.readText() else ""
        }
    }

    fun clear(context: Context) {
        synchronized(lock) { file(context).writeText("") }
    }

    fun timestamp(): String = tsFormat.format(Date())
}
