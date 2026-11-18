package com.aloanswer.alo_answer.dialer

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.widget.Toast
import com.aloanswer.alo_answer.R
import java.util.Locale

/**
 * What a phone app hands over to the system or other apps: the carrier's call
 * settings (forwarding, call waiting, caller ID), SIM choice, the blocked-numbers
 * list, SMS, contacts, the clipboard; plus number formatting and SIM names.
 */
object PhoneSettings {

    /** Call forwarding, call waiting, caller ID… (the phone's own screen for them). */
    fun openCallSettings(a: Activity) = start(a, Intent(TelecomManager.ACTION_SHOW_CALL_SETTINGS))

    /** Which SIM calls go out on. */
    fun openAccounts(a: Activity) = start(a, Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS))

    /** The system list of blocked numbers (the default phone app may open it). */
    fun openBlocked(a: Activity) {
        val i = try {
            a.getSystemService(TelecomManager::class.java)?.createManageBlockedNumbersIntent()
        } catch (_: Exception) {
            null
        }
        if (i == null) cantOpen(a) else start(a, i)
    }

    fun sms(a: Activity, number: String) =
        start(a, Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)))

    /** "Add to contacts": the contacts app asks whether to create a new contact or add to one. */
    fun addToContacts(a: Activity, number: String) = start(
        a,
        Intent(Intent.ACTION_INSERT_OR_EDIT)
            .setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
            .putExtra(ContactsContract.Intents.Insert.PHONE, number)
    )

    fun newContact(a: Activity) =
        start(a, Intent(ContactsContract.Intents.Insert.ACTION, ContactsContract.Contacts.CONTENT_URI))

    fun viewContact(a: Activity, uri: Uri) = start(a, Intent(Intent.ACTION_VIEW, uri))

    fun copy(ctx: Context, text: String) {
        ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("number", text))
        // Android 13+ shows its own "Copied" confirmation.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, R.string.dialer_copied, Toast.LENGTH_SHORT).show()
    }

    /** A dialable number from the clipboard (spaces, dashes and brackets dropped), or null. */
    fun pasteNumber(ctx: Context): String? {
        val clip = ctx.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val raw = clip.getItemAt(0).coerceToText(ctx)?.toString() ?: return null
        val n = PhoneNumberUtils.convertKeypadLettersToDigits(raw).filter { it.isDigit() || it in "+*#" }
        return n.takeIf { it.isNotEmpty() && it.length <= 40 }
    }

    /** "90 123 45 67" style for display; unformatted when Android can't format it. */
    fun format(ctx: Context, number: String): String {
        if (number.length < 5 || number.any { it == '*' || it == '#' }) return number
        return try {
            PhoneNumberUtils.formatNumber(number, countryIso(ctx)) ?: number
        } catch (_: Exception) {
            number
        }
    }

    private var iso: String? = null

    private fun countryIso(ctx: Context): String {
        iso?.let { return it }
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        val c = listOf(tm?.networkCountryIso, tm?.simCountryIso, Locale.getDefault().country)
            .firstOrNull { !it.isNullOrBlank() }.orEmpty().uppercase(Locale.ROOT)
        iso = c
        return c
    }

    private fun accounts(ctx: Context): List<PhoneAccountHandle> = try {
        ctx.getSystemService(TelecomManager::class.java)?.callCapablePhoneAccounts ?: emptyList()
    } catch (_: SecurityException) {
        emptyList()
    }

    /** Number of SIMs (calling accounts) that can make calls. */
    fun simCount(ctx: Context): Int = accounts(ctx).size

    /**
     * Name of the SIM a call log entry was on ("SIM 1", or the carrier), only on
     * phones with more than one; null otherwise.
     */
    fun simLabels(ctx: Context): Map<String, String> {
        val list = accounts(ctx)
        if (list.size < 2) return emptyMap()
        val telecom = ctx.getSystemService(TelecomManager::class.java) ?: return emptyMap()
        return list.associate { h ->
            val label = try {
                telecom.getPhoneAccount(h)?.label?.toString()
            } catch (_: Exception) {
                null
            }
            h.id to (label?.takeIf { it.isNotBlank() } ?: "SIM")
        }
    }

    private fun start(a: Activity, i: Intent) {
        try {
            a.startActivity(i)
        } catch (_: Exception) {
            cantOpen(a)
        }
    }

    private fun cantOpen(ctx: Context) = Toast.makeText(ctx, R.string.dialer_cant_open, Toast.LENGTH_LONG).show()
}
