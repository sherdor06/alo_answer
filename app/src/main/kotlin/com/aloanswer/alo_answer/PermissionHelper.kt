package com.aloanswer.alo_answer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings

/**
 * Permission state for the permissions screen and the home screen.
 *
 * Status values: "granted", "denied" (can ask again), "permanentlyDenied"
 * (asked before and the system will no longer show the dialog).
 * "overlay" (SYSTEM_ALERT_WINDOW) is a special permission: it is never a
 * dialog, only a Settings toggle, so it is only ever "granted" or "denied".
 */
object PermissionHelper {

    const val OVERLAY = "overlay"
    const val GRANTED = "granted"
    const val PERMANENTLY_DENIED = "permanentlyDenied"
    val ALL = listOf("phone", "answer", "mic", "notifications", OVERLAY)

    /** Runtime permission for a permission-screen name, or null when nothing needs asking on this API. */
    fun runtimePermission(name: String): String? = when (name) {
        "phone" -> Manifest.permission.READ_PHONE_STATE
        "answer" -> Manifest.permission.ANSWER_PHONE_CALLS
        "mic" -> Manifest.permission.RECORD_AUDIO
        "notifications" -> if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null
        else -> throw IllegalArgumentException("Not a runtime permission: $name")
    }

    fun status(activity: Activity, name: String): String {
        if (name == OVERLAY) {
            return if (AndroidSettings.canDrawOverlays(activity)) "granted" else "denied"
        }
        val perm = runtimePermission(name) ?: return "granted"
        if (activity.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) return "granted"
        val asked = Settings.prefs(activity).getBoolean(Settings.KEY_ASKED_PREFIX + name, false)
        return if (asked && !activity.shouldShowRequestPermissionRationale(perm)) "permanentlyDenied" else "denied"
    }

    fun statusAll(activity: Activity): Map<String, String> = ALL.associateWith { status(activity, it) }

    fun allGranted(activity: Activity) = ALL.all { status(activity, it) == GRANTED }

    fun markAsked(activity: Activity, name: String) {
        Settings.prefs(activity).edit().putBoolean(Settings.KEY_ASKED_PREFIX + name, true).apply()
    }

    fun openAppSettings(activity: Activity) {
        activity.startActivity(
            Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", activity.packageName, null))
        )
    }

    /** The package URI is only honored before Android 11; after that the user picks the app from a list. */
    fun openOverlaySettings(activity: Activity) {
        activity.startActivity(
            Intent(
                AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.fromParts("package", activity.packageName, null)
            )
        )
    }
}
