package com.spamblok.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Phase 4 — an imported, offline spam-number list (e.g. a locally downloaded
 * snapshot of an open list like Yet Another Call Blocker's crowdsourced DB —
 * see DATA_SOURCES.md). SpamBlok doesn't bundle or redistribute anyone else's
 * database itself (their license/ToS is between the user and that project); the
 * user downloads a plain-text list themselves and imports it here, entirely
 * on-device — same privacy guarantee as everything else in this app.
 *
 * A match here doesn't block the call (only the user's own [BlockedPrefixStore]
 * does that) — it just upgrades the heuristic verdict shown on the overlay, the
 * same way a `140` prefix does.
 */
object SpamNumberListStore {

    /** Parses a plain-text list (one number per line) into normalized digit
     * strings, skipping blank lines and "#"-prefixed comments. Pure — no I/O —
     * so it's unit-testable independent of the file-picker/DB plumbing. */
    fun parseLines(lines: List<String>): List<String> = lines
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() }
        .map { it.filter(Char::isDigit) }
        .filter { it.length >= 6 } // discard junk that isn't plausibly a phone number

    fun importNumbers(context: Context, numbers: List<String>): Int {
        if (numbers.isEmpty()) return 0
        val db = DbHelper.get(context).writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            numbers.forEach { number ->
                db.insertWithOnConflict(
                    TABLE, null,
                    ContentValues().apply {
                        put(COL_NUMBER, number)
                        put(COL_IMPORTED_AT, now)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return numbers.size
    }

    fun contains(context: Context, rawNumber: String): Boolean {
        val key = CallerRepository.normalize(rawNumber)
        if (key.isEmpty()) return false
        DbHelper.get(context).readableDatabase.query(
            TABLE, arrayOf(COL_NUMBER), "$COL_NUMBER = ?", arrayOf(key), null, null, null,
        ).use { cursor -> return cursor.moveToFirst() }
    }

    fun count(context: Context): Int {
        DbHelper.get(context).readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { cursor ->
            cursor.moveToFirst()
            return cursor.getInt(0)
        }
    }

    fun clear(context: Context) {
        DbHelper.get(context).writableDatabase.delete(TABLE, null, null)
    }

    private const val TABLE = "spam_numbers"
    private const val COL_NUMBER = "number"
    private const val COL_IMPORTED_AT = "imported_at"

    private class DbHelper(context: Context) : SQLiteOpenHelper(context.applicationContext, "spamblok_spam_list.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                    $COL_NUMBER TEXT PRIMARY KEY,
                    $COL_IMPORTED_AT INTEGER NOT NULL
                )
                """.trimIndent(),
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }

        companion object {
            @Volatile private var instance: DbHelper? = null
            fun get(context: Context): DbHelper =
                instance ?: synchronized(this) {
                    instance ?: DbHelper(context).also { instance = it }
                }
        }
    }
}
