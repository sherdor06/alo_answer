package com.aloanswer.alo_answer

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings

/**
 * Deep links for MIUI / HyperOS setup.
 *
 * The com.miui.* component names below are NOT public API. They are what
 * MIUI/HyperOS builds have shipped for years, but Xiaomi can change them in
 * any update. Every call falls back to a documented AOSP screen, and returns
 * which one actually opened so the UI can tell the user where they landed.
 */
object XiaomiIntents {

    val isXiaomi: Boolean
        get() = Build.MANUFACTURER.equals("Xiaomi", true) ||
            Build.BRAND.equals("Redmi", true) || Build.BRAND.equals("POCO", true)

    /** Returns "xiaomi", "fallback" or "failed". */
    fun open(activity: Activity, target: String): String {
        val pkg = activity.packageName
        val candidates: List<Pair<String, Intent>> = when (target) {
            "autostart" -> listOf(
                "xiaomi" to Intent().setComponent(
                    ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    )
                ),
                "fallback" to appDetails(pkg),
            )
            "battery" -> listOf(
                "xiaomi" to Intent().setComponent(
                    ComponentName(
                        "com.miui.powerkeeper",
                        "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                    )
                ).putExtra("package_name", pkg)
                    .putExtra("package_label", activity.applicationInfo.loadLabel(activity.packageManager)),
                "fallback" to Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                "fallback" to appDetails(pkg),
            )
            // "Display pop-up windows while running in the background" + "Show on Lock screen"
            "otherPermissions" -> listOf(
                "xiaomi" to Intent("miui.intent.action.APP_PERM_EDITOR")
                    .setClassName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.permissions.PermissionsEditorActivity"
                    )
                    .putExtra("extra_pkgname", pkg),
                "fallback" to appDetails(pkg),
            )
            "overlay" -> listOf(
                "fallback" to Intent(
                    AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.fromParts("package", pkg, null)
                ),
                "fallback" to appDetails(pkg),
            )
            "notifications" -> listOf(
                "fallback" to Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, pkg),
                "fallback" to appDetails(pkg),
            )
            "accessibility" -> listOf(
                "fallback" to Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS),
                "fallback" to appDetails(pkg),
            )
            "app" -> listOf("fallback" to appDetails(pkg))
            else -> emptyList()
        }
        for ((kind, intent) in candidates) {
            try {
                activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return kind
            } catch (_: Exception) {
                // ActivityNotFoundException / SecurityException: try next
            }
        }
        return "failed"
    }

    fun isIgnoringBatteryOptimizations(activity: Activity): Boolean =
        activity.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(activity.packageName)

    private fun appDetails(pkg: String) =
        Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", pkg, null))
}
