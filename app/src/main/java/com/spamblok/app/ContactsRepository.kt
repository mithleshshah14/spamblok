package com.spamblok.app

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

/** Thin, live read-only lookup into system Contacts (requires READ_CONTACTS,
 * granted at runtime from the Calls tab). Deliberately not cached: the call
 * log's own CACHED_NAME column is a snapshot from when the call happened, so
 * it doesn't reflect a contact saved afterwards — this always queries fresh. */
object ContactsRepository {

    fun lookupName(context: Context, number: String): String? {
        if (number.isBlank()) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME)
                return cursor.getString(idx)?.takeIf { it.isNotBlank() }
            }
        }
        return null
    }
}
