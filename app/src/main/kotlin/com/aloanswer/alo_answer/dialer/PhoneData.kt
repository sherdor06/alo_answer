package com.aloanswer.alo_answer.dialer

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.BlockedNumberContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import java.util.Calendar

/** Call history (READ_CALL_LOG, WRITE_CALL_LOG to delete) for the Recents tab. */
object CallLogRepo {

    data class Entry(
        val id: Long,
        val number: String,
        val name: String?,
        val type: Int,
        val date: Long,
        val durationSec: Long,
        val accountId: String?,
        val photoUri: String?,
    ) {
        val missed get() = type == CallLog.Calls.MISSED_TYPE
        val declined get() = type == CallLog.Calls.REJECTED_TYPE || type == CallLog.Calls.BLOCKED_TYPE
    }

    /** Calls in a row with the same number on the same day: one line in Recents, newest first. */
    class Group(val entries: List<Entry>) {
        val first: Entry get() = entries[0]
        val number: String get() = first.number
        val name: String? get() = first.name
        val count: Int get() = entries.size
    }

    fun granted(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    fun canWrite(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    /** Newest first. Blocking: call off the main thread. */
    fun load(ctx: Context, limit: Int = 500): List<Entry> {
        if (!granted(ctx)) return emptyList()
        val uri = CallLog.Calls.CONTENT_URI.buildUpon()
            .appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, limit.toString())
            .build()
        val projection = arrayOf(
            CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION,
            CallLog.Calls.PHONE_ACCOUNT_ID, CallLog.Calls.CACHED_PHOTO_URI,
        )
        val out = mutableListOf<Entry>()
        // Contact name and photo per number (the call log's cached name may be missing or stale).
        val looked = HashMap<String, ContactsRepo.Info?>()
        try {
            ctx.contentResolver.query(uri, projection, null, null, "${CallLog.Calls.DATE} DESC")?.use { c ->
                while (c.moveToNext()) {
                    val number = c.getString(1).orEmpty()
                    val info = if (number.isEmpty()) null else looked.getOrPut(number) { ContactsRepo.lookup(ctx, number) }
                    val name = info?.name ?: c.getString(2)?.takeIf { it.isNotBlank() }
                    val photo = info?.photoUri ?: c.getString(7)?.takeIf { it.isNotBlank() }
                    out += Entry(c.getLong(0), number, name, c.getInt(3), c.getLong(4), c.getLong(5), c.getString(6), photo)
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun group(entries: List<Entry>): List<Group> {
        val out = mutableListOf<Group>()
        var cur = mutableListOf<Entry>()
        for (e in entries) {
            val prev = cur.lastOrNull()
            if (prev != null && key(prev.number) == key(e.number) && sameDay(prev.date, e.date)) {
                cur.add(e)
            } else {
                if (cur.isNotEmpty()) out += Group(cur)
                cur = mutableListOf(e)
            }
        }
        if (cur.isNotEmpty()) out += Group(cur)
        return out
    }

    private fun key(n: String) = n.filter { it.isDigit() || it == '+' }

    private fun sameDay(a: Long, b: Long): Boolean {
        val x = Calendar.getInstance().apply { timeInMillis = a }
        val y = Calendar.getInstance().apply { timeInMillis = b }
        return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR)
    }

    /** Missed calls nobody has looked at yet (the badge on the Recents tab). Blocking. */
    fun newMissedCount(ctx: Context): Int {
        if (!granted(ctx)) return 0
        return try {
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls._ID),
                "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.NEW} = 1 AND " +
                    "(${CallLog.Calls.IS_READ} IS NULL OR ${CallLog.Calls.IS_READ} = 0)",
                arrayOf(CallLog.Calls.MISSED_TYPE.toString()), null
            )?.use { it.count } ?: 0
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Missed calls have been seen: clear their "new" flag. Telecom leaves this to the default
     * phone app when the app shows the missed-call notification itself. Needs WRITE_CALL_LOG. Blocking.
     */
    fun markMissedRead(ctx: Context): Int {
        if (!canWrite(ctx)) return 0
        return try {
            val values = ContentValues().apply {
                put(CallLog.Calls.NEW, 0)
                put(CallLog.Calls.IS_READ, 1)
            }
            ctx.contentResolver.update(
                CallLog.Calls.CONTENT_URI, values,
                "${CallLog.Calls.NEW} = 1 AND ${CallLog.Calls.TYPE} = ?", arrayOf(CallLog.Calls.MISSED_TYPE.toString())
            )
        } catch (_: Exception) {
            0
        }
    }

    /** For the call button with an empty number, like other phone apps. Blocking. */
    fun lastOutgoingNumber(ctx: Context): String? {
        if (!granted(ctx)) return null
        return try {
            CallLog.Calls.getLastOutgoingCall(ctx)?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /** Needs WRITE_CALL_LOG. Blocking. */
    fun delete(ctx: Context, ids: Collection<Long>): Int {
        if (!canWrite(ctx) || ids.isEmpty()) return 0
        return try {
            ctx.contentResolver.delete(CallLog.Calls.CONTENT_URI, "${CallLog.Calls._ID} IN (${ids.joinToString(",")})", null)
        } catch (_: Exception) {
            0
        }
    }

    /** Needs WRITE_CALL_LOG. Blocking. */
    fun clearAll(ctx: Context): Int {
        if (!canWrite(ctx)) return 0
        return try {
            ctx.contentResolver.delete(CallLog.Calls.CONTENT_URI, null, null)
        } catch (_: Exception) {
            0
        }
    }
}

/** Contacts (READ_CONTACTS) for the Contacts tab, smart dial and caller names. */
object ContactsRepo {

    data class Number(val number: String, val label: String)

    data class Contact(
        val id: Long,
        val lookupKey: String?,
        val name: String,
        val numbers: List<Number>,
        val starred: Boolean,
        val photoUri: String?,
    ) {
        val uri: Uri get() = ContactsContract.Contacts.getLookupUri(id, lookupKey)

        /** Dial pad digits of each word of the name, for smart dial. */
        val t9Words: List<String> by lazy { T9.words(name) }
        val digitNumbers: List<String> by lazy { numbers.map { n -> n.number.filter(Char::isDigit) } }
    }

    /** A number's contact: name, small photo and the contact to open. */
    data class Info(val name: String, val photoUri: String?, val contactUri: Uri?)

    fun granted(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Contacts that have at least one phone number, A→Z, favourites first. Blocking. */
    fun load(ctx: Context): List<Contact> {
        if (!granted(ctx)) return emptyList()
        val projection = arrayOf(
            Phone.CONTACT_ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER, Phone.TYPE, Phone.LABEL,
            Phone.STARRED, Phone.IS_SUPER_PRIMARY, Phone.LOOKUP_KEY, Phone.PHOTO_THUMBNAIL_URI,
        )
        val byId = LinkedHashMap<Long, MutableList<Pair<Number, Boolean>>>()
        val names = HashMap<Long, String>()
        val starred = HashMap<Long, Boolean>()
        val lookup = HashMap<Long, String?>()
        val photos = HashMap<Long, String?>()
        try {
            ctx.contentResolver.query(
                Phone.CONTENT_URI, projection, null, null, "${Phone.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val number = c.getString(2)?.takeIf { it.isNotBlank() } ?: continue
                    val label = Phone.getTypeLabel(ctx.resources, c.getInt(3), c.getString(4)).toString()
                    names.putIfAbsent(id, c.getString(1) ?: number)
                    starred[id] = c.getInt(5) == 1
                    lookup.putIfAbsent(id, c.getString(7))
                    photos.putIfAbsent(id, c.getString(8))
                    byId.getOrPut(id) { mutableListOf() } += Number(number, label) to (c.getInt(6) == 1)
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }
        val list = byId.map { (id, nums) ->
            // primary number first, duplicates (same digits) removed
            val ordered = nums.sortedByDescending { it.second }.map { it.first }
                .distinctBy { it.number.filter(Char::isDigit) }
            Contact(id, lookup[id], names[id].orEmpty(), ordered, starred[id] == true, photos[id])
        }
        return list.sortedByDescending { it.starred } // stable: keeps A→Z within each group
    }

    /** Contact for a phone number, or null (no permission / not a contact). Blocking but fast. */
    fun lookup(ctx: Context, number: String): Info? {
        if (!granted(ctx) || number.isBlank()) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            val projection = arrayOf(
                ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.LOOKUP_KEY,
                ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI,
            )
            ctx.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return null
                val name = c.getString(2)?.takeIf { it.isNotBlank() } ?: return null
                Info(name, c.getString(3), ContactsContract.Contacts.getLookupUri(c.getLong(0), c.getString(1)))
            }
        } catch (_: Exception) {
            null
        }
    }

    fun nameFor(ctx: Context, number: String): String? = lookup(ctx, number)?.name
}

/** Blocked numbers (the system list; the default phone app may change it). */
object BlockRepo {

    fun canBlock(ctx: Context) = try {
        BlockedNumberContract.canCurrentUserBlockNumbers(ctx)
    } catch (_: Exception) {
        false
    }

    /** Blocking. */
    fun isBlocked(ctx: Context, number: String) = try {
        number.isNotBlank() && BlockedNumberContract.isBlocked(ctx, number)
    } catch (_: Exception) {
        false
    }

    /** Blocking. */
    fun block(ctx: Context, number: String): Boolean = try {
        val values = ContentValues().apply {
            put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number)
        }
        ctx.contentResolver.insert(BlockedNumberContract.BlockedNumbers.CONTENT_URI, values) != null
    } catch (_: Exception) {
        false
    }

    /** Blocking. */
    fun unblock(ctx: Context, number: String): Boolean = try {
        BlockedNumberContract.unblock(ctx, number) > 0
    } catch (_: Exception) {
        false
    }
}

/**
 * Smart dial: letters → dial pad digits, Latin and Cyrillic (Russian and Uzbek
 * keypads), so "Ali" is found by 2-5-4 and "Анвар" by 2-6-2-2-7.
 */
object T9 {
    private val keys: Map<Char, Char> = HashMap<Char, Char>().apply {
        fun key(d: Char, letters: String) = letters.forEach { put(it, d) }
        key('2', "abcабвгғ")
        key('3', "defдеёжз")
        key('4', "ghiийклқ")
        key('5', "jklмноп")
        key('6', "mnoрстуў")
        key('7', "pqrsфхцчҳ")
        key('8', "tuvшщъы")
        key('9', "wxyzьэюя")
    }

    /** Digits of each word of [name]; other characters (ʻ, apostrophes, dots) are skipped. */
    fun words(name: String): List<String> =
        name.lowercase().split(' ', '-', '.', ',', '(', ')', '_').mapNotNull { w ->
            val d = buildString {
                for (ch in w) {
                    if (ch.isDigit()) append(ch) else keys[ch]?.let { append(it) }
                }
            }
            d.ifEmpty { null }
        }

    /** How well a name matches typed [digits]: 0 = not at all, 3 = a word starts with them. */
    fun nameScore(words: List<String>, digits: String): Int {
        if (digits.isEmpty() || words.isEmpty()) return 0
        if (words.any { it.startsWith(digits) }) return 3
        if (digits.length >= 2 && words.joinToString("") { it.take(1) }.startsWith(digits)) return 2
        if (digits.length >= 3 && words.joinToString("").contains(digits)) return 1
        return 0
    }
}
