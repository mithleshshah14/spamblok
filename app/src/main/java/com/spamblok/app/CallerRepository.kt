package com.spamblok.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Phase 3 — SpamBlok's own on-device database: number -> name/label, tagged with
 * where we learned it from. Nothing leaves the device; this is a plain SQLite
 * table in the app's private database directory.
 *
 * Every name/label we ever read off a banner (Truecaller / in-call UI, via
 * [BannerReaderService]) is recorded here ("store every number->name we observe").
 * On a later incoming call, [SpamBlokCallScreeningService] looks this DB up
 * *first* — only when it has nothing do we fall back to waiting for the banner
 * to render (Phase 1/2's approach). If a later-observed name disagrees with what
 * we already had for a number, that's a mismatch: we keep the original ("verified"
 * stays with whichever was consistent longer) but flag it via [mismatchCount] and
 * hand the caller a human-readable description to log.
 */
object CallerRepository {

    data class CallerRecord(
        val number: String,
        val name: String?,
        val label: String?,
        val source: String?,
        val verified: Boolean,
        val firstSeenMillis: Long,
        val lastSeenMillis: Long,
        val mismatchCount: Int,
    )

    /** Strip everything but digits — consistent key for both screening-service and
     * banner-reported numbers (both arrive in E.164-ish "+91XXXXXXXXXX" form). */
    fun normalize(raw: String): String = raw.filter { it.isDigit() }

    /** True if a freshly observed name genuinely disagrees with what we already
     * had for this number (as opposed to just filling in a blank, or a trivial
     * case/whitespace difference). Pure — no DB access — so it's unit-testable. */
    fun isMismatch(existingName: String?, newName: String?): Boolean =
        !existingName.isNullOrBlank() &&
            !newName.isNullOrBlank() &&
            !newName.trim().equals(existingName.trim(), ignoreCase = true)

    fun lookup(context: Context, rawNumber: String): CallerRecord? {
        val key = normalize(rawNumber)
        if (key.isEmpty()) return null
        DbHelper.get(context).readableDatabase.query(
            TABLE, COLUMNS, "$COL_NUMBER = ?", arrayOf(key), null, null, null,
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.toRecord() else null
        }
    }

    fun getRecent(context: Context, limit: Int = 20): List<CallerRecord> {
        val out = mutableListOf<CallerRecord>()
        DbHelper.get(context).readableDatabase.query(
            TABLE, COLUMNS, null, null, null, null, "$COL_LAST_SEEN DESC", limit.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) out.add(cursor.toRecord())
        }
        return out
    }

    /**
     * Records an observed number/name/label pair. Returns a short human-readable
     * description of anything worth logging (new caller learned, or a mismatch
     * against what we already had) — null if this was just a routine re-confirm.
     */
    fun observe(context: Context, rawNumber: String, name: String?, label: String?, source: String): String? {
        val key = normalize(rawNumber)
        if (key.isEmpty() || (name.isNullOrBlank() && label.isNullOrBlank())) return null

        val db = DbHelper.get(context).writableDatabase
        val now = System.currentTimeMillis()
        val existing = lookup(context, key)

        if (existing == null) {
            db.insert(
                TABLE, null,
                ContentValues().apply {
                    put(COL_NUMBER, key)
                    put(COL_NAME, name)
                    put(COL_LABEL, label)
                    put(COL_SOURCE, source)
                    put(COL_VERIFIED, 0)
                    put(COL_FIRST_SEEN, now)
                    put(COL_LAST_SEEN, now)
                    put(COL_MISMATCH_COUNT, 0)
                },
            )
            return "learned $key -> ${name ?: label} (source: $source)"
        }

        val nameChanged = isMismatch(existing.name, name)

        val values = ContentValues().apply {
            put(COL_LAST_SEEN, now)
            if (!name.isNullOrBlank()) put(COL_NAME, if (nameChanged) existing.name else name)
            if (!label.isNullOrBlank()) put(COL_LABEL, label)
            if (nameChanged) put(COL_MISMATCH_COUNT, existing.mismatchCount + 1)
        }
        db.update(TABLE, values, "$COL_NUMBER = ?", arrayOf(key))

        return if (nameChanged) {
            "MISMATCH for $key: kept '${existing.name}', also saw '$name' (source: $source)"
        } else {
            null
        }
    }

    /** One-time cleanup for rows written by the pre-fix BannerReaderService, which
     * mistook non-name text in incallui's "id/name" field for a real caller name and
     * stored it as one — either a carrier spam-warning label (e.g. "Suspected Spam",
     * "SPAM Alert from Jio") or the bare phone number itself, echoed back as a
     * fallback when incallui had no real identification. Clears the name (keeps the
     * number/row, and resets the mismatch count this bug corrupted) wherever it
     * looks like either; safe to call repeatedly — a no-op once cleaned. Skips
     * "verified" rows (source = "user"), since those are explicit corrections the
     * user typed in themselves and can't be this bug. */
    fun purgeSpamLikeNames(context: Context): Int {
        val db = DbHelper.get(context).writableDatabase
        var cleared = 0
        db.query(TABLE, arrayOf(COL_NUMBER, COL_NAME), "$COL_VERIFIED = 0 AND $COL_NAME IS NOT NULL", null, null, null, null).use { cursor ->
            val numberIdx = cursor.getColumnIndexOrThrow(COL_NUMBER)
            val nameIdx = cursor.getColumnIndexOrThrow(COL_NAME)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx) ?: continue
                if (SpamLabelHeuristics.looksLikeSpamWarning(name) || SpamLabelHeuristics.looksLikeBareNumber(name)) {
                    db.update(
                        TABLE,
                        ContentValues().apply { putNull(COL_NAME); put(COL_MISMATCH_COUNT, 0) },
                        "$COL_NUMBER = ?",
                        arrayOf(cursor.getString(numberIdx)),
                    )
                    cleared++
                }
            }
        }
        return cleared
    }

    /** User-entered name for a number we don't otherwise have one for (e.g. from
     * the Calls tab). Unlike [observe], this always wins — it's an explicit,
     * verified correction from the person using the app, not an inferred guess
     * subject to mismatch handling. */
    fun setManualName(context: Context, rawNumber: String, name: String) {
        val key = normalize(rawNumber)
        if (key.isEmpty() || name.isBlank()) return

        val db = DbHelper.get(context).writableDatabase
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(COL_NUMBER, key)
            put(COL_NAME, name.trim())
            put(COL_SOURCE, "user")
            put(COL_VERIFIED, 1)
            put(COL_LAST_SEEN, now)
        }
        if (lookup(context, key) == null) {
            values.put(COL_FIRST_SEEN, now)
            values.put(COL_MISMATCH_COUNT, 0)
            db.insert(TABLE, null, values)
        } else {
            db.update(TABLE, values, "$COL_NUMBER = ?", arrayOf(key))
        }
    }

    private fun android.database.Cursor.toRecord(): CallerRecord = CallerRecord(
        number = getString(getColumnIndexOrThrow(COL_NUMBER)),
        name = getString(getColumnIndexOrThrow(COL_NAME)),
        label = getString(getColumnIndexOrThrow(COL_LABEL)),
        source = getString(getColumnIndexOrThrow(COL_SOURCE)),
        verified = getInt(getColumnIndexOrThrow(COL_VERIFIED)) != 0,
        firstSeenMillis = getLong(getColumnIndexOrThrow(COL_FIRST_SEEN)),
        lastSeenMillis = getLong(getColumnIndexOrThrow(COL_LAST_SEEN)),
        mismatchCount = getInt(getColumnIndexOrThrow(COL_MISMATCH_COUNT)),
    )

    private const val TABLE = "callers"
    private const val COL_NUMBER = "number"
    private const val COL_NAME = "name"
    private const val COL_LABEL = "label"
    private const val COL_SOURCE = "source"
    private const val COL_VERIFIED = "verified"
    private const val COL_FIRST_SEEN = "first_seen"
    private const val COL_LAST_SEEN = "last_seen"
    private const val COL_MISMATCH_COUNT = "mismatch_count"
    private val COLUMNS = arrayOf(
        COL_NUMBER, COL_NAME, COL_LABEL, COL_SOURCE, COL_VERIFIED,
        COL_FIRST_SEEN, COL_LAST_SEEN, COL_MISMATCH_COUNT,
    )

    private class DbHelper(context: Context) : SQLiteOpenHelper(context.applicationContext, "spamblok_callers.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                    $COL_NUMBER TEXT PRIMARY KEY,
                    $COL_NAME TEXT,
                    $COL_LABEL TEXT,
                    $COL_SOURCE TEXT,
                    $COL_VERIFIED INTEGER NOT NULL DEFAULT 0,
                    $COL_FIRST_SEEN INTEGER NOT NULL,
                    $COL_LAST_SEEN INTEGER NOT NULL,
                    $COL_MISMATCH_COUNT INTEGER NOT NULL DEFAULT 0
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
