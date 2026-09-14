package com.spamblok.app

import android.content.Context
import android.provider.Telephony

/** Thin read-only wrapper over the system SMS provider (requires READ_SMS,
 * granted at runtime from the Messages tab). SpamBlok only reads messages
 * already on the device — nothing is sent, nothing leaves the device. */
object SmsRepository {

    enum class MessageType { INBOX, SENT, OTHER }

    data class Entry(
        val address: String,
        val body: String,
        val type: MessageType,
        val timestampMillis: Long,
    )

    fun getRecent(context: Context, limit: Int = 100): List<Entry> {
        val out = mutableListOf<Entry>()
        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.TYPE,
            Telephony.Sms.DATE,
        )
        // Not "$COLUMN DESC LIMIT $limit" — some OEM providers reject a LIMIT
        // clause injected into sortOrder ("Invalid token LIMIT") since it's not
        // parameterized. Sort only in SQL, cap the result size in Kotlin.
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            null,
            null,
            "${Telephony.Sms.DATE} DESC",
        )?.use { cursor ->
            val addressIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val typeIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (cursor.moveToNext() && out.size < limit) {
                out.add(
                    Entry(
                        address = cursor.getString(addressIdx) ?: "",
                        body = cursor.getString(bodyIdx) ?: "",
                        type = mapType(cursor.getInt(typeIdx)),
                        timestampMillis = cursor.getLong(dateIdx),
                    ),
                )
            }
        }
        return out
    }

    private fun mapType(raw: Int): MessageType = when (raw) {
        Telephony.Sms.MESSAGE_TYPE_INBOX -> MessageType.INBOX
        Telephony.Sms.MESSAGE_TYPE_SENT -> MessageType.SENT
        else -> MessageType.OTHER
    }
}
