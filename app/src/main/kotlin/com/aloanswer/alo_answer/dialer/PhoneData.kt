package com.aloanswer.alo_answer.dialer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone

/** Call history (READ_CALL_LOG) for the Recents tab. */
object CallLogRepo {

    data class Entry(
        val id: Long,
        val number: String,
        val name: String?,
        val type: Int,
        val date: Long,
        val durationSec: Long,
    )

    fun granted(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    /** Newest first. Blocking: call off the main thread. */
    fun load(ctx: Context, limit: Int = 300): List<Entry> {
        if (!granted(ctx)) return emptyList()
        val uri = CallLog.Calls.CONTENT_URI.buildUpon()
            .appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, limit.toString())
            .build()
        val projection = arrayOf(
            CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION,
        )
        val out = mutableListOf<Entry>()
        val looked = HashMap<String, String?>() // name lookups for numbers without a cached name
        ctx.contentResolver.query(uri, projection, null, null, "${CallLog.Calls.DATE} DESC")?.use { c ->
            while (c.moveToNext()) {
                val number = c.getString(1).orEmpty()
                var name = c.getString(2)?.takeIf { it.isNotBlank() }
                if (name == null && number.isNotEmpty()) {
                    name = looked.getOrPut(number) { ContactsRepo.nameFor(ctx, number) }
                }
                out += Entry(c.getLong(0), number, name, c.getInt(3), c.getLong(4), c.getLong(5))
            }
        }
        return out
    }
}

/** Contacts (READ_CONTACTS) for the Contacts tab and caller names. */
object ContactsRepo {

    data class Number(val number: String, val label: String)

    data class Contact(val id: Long, val name: String, val numbers: List<Number>, val starred: Boolean)

    fun granted(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Contacts that have at least one phone number, A→Z, favourites first. Blocking. */
    fun load(ctx: Context): List<Contact> {
        if (!granted(ctx)) return emptyList()
        val projection = arrayOf(
            Phone.CONTACT_ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER, Phone.TYPE, Phone.LABEL,
            Phone.STARRED, Phone.IS_SUPER_PRIMARY,
        )
        val byId = LinkedHashMap<Long, MutableList<Pair<Number, Boolean>>>()
        val names = HashMap<Long, String>()
        val starred = HashMap<Long, Boolean>()
        ctx.contentResolver.query(
            Phone.CONTENT_URI, projection, null, null, "${Phone.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val number = c.getString(2)?.takeIf { it.isNotBlank() } ?: continue
                val label = Phone.getTypeLabel(ctx.resources, c.getInt(3), c.getString(4)).toString()
                names.putIfAbsent(id, c.getString(1) ?: number)
                starred[id] = c.getInt(5) == 1
                byId.getOrPut(id) { mutableListOf() } += Number(number, label) to (c.getInt(6) == 1)
            }
        }
        val list = byId.map { (id, nums) ->
            // primary number first, duplicates (same digits) removed
            val ordered = nums.sortedByDescending { it.second }.map { it.first }
                .distinctBy { it.number.filter(Char::isDigit) }
            Contact(id, names[id].orEmpty(), ordered, starred[id] == true)
        }
        return list.sortedByDescending { it.starred } // stable: keeps A→Z within each group
    }

    /** Contact name for a phone number, or null (no permission / not a contact). Blocking but fast. */
    fun nameFor(ctx: Context, number: String): String? {
        if (!granted(ctx) || number.isBlank()) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (_: Exception) {
            null
        }
    }
}
