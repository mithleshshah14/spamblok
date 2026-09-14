package com.spamblok.app

import android.content.Context
import android.provider.CallLog

/** Thin read-only wrapper over the system call log (requires READ_CALL_LOG,
 * granted at runtime from the Calls tab). We never write to it — SpamBlok
 * only reads what the OS already recorded. */
object CallLogRepository {

    enum class CallType { INCOMING, OUTGOING, MISSED, REJECTED, BLOCKED, OTHER }

    data class Entry(
        val number: String,
        val displayName: String?,
        val type: CallType,
        val timestampMillis: Long,
        val durationSeconds: Long,
    )

    fun getRecent(context: Context, limit: Int = 100): List<Entry> {
        val out = mutableListOf<Entry>()
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
        )
        // Not "$COLUMN DESC LIMIT $limit" — some OEM call-log providers reject a
        // LIMIT clause injected into sortOrder ("Invalid token LIMIT") since it's
        // not parameterized. Sort only in SQL, cap the result size in Kotlin.
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            null,
            null,
            "${CallLog.Calls.DATE} DESC",
        )?.use { cursor ->
            val numberIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val nameIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
            val typeIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val dateIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val durationIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
            while (cursor.moveToNext() && out.size < limit) {
                out.add(
                    Entry(
                        number = cursor.getString(numberIdx) ?: "",
                        displayName = cursor.getString(nameIdx),
                        type = mapType(cursor.getInt(typeIdx)),
                        timestampMillis = cursor.getLong(dateIdx),
                        durationSeconds = cursor.getLong(durationIdx),
                    ),
                )
            }
        }
        return out
    }

    private fun mapType(raw: Int): CallType = when (raw) {
        CallLog.Calls.INCOMING_TYPE -> CallType.INCOMING
        CallLog.Calls.OUTGOING_TYPE -> CallType.OUTGOING
        CallLog.Calls.MISSED_TYPE -> CallType.MISSED
        CallLog.Calls.REJECTED_TYPE -> CallType.REJECTED
        CallLog.Calls.BLOCKED_TYPE -> CallType.BLOCKED
        else -> CallType.OTHER
    }
}
