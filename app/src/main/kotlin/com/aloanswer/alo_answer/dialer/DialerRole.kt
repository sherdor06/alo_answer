package com.aloanswer.alo_answer.dialer

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telecom.TelecomManager

/** Is Alo Answer the default phone app, and how to ask the user to make it one. */
object DialerRole {

    fun isDefault(ctx: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= 29) {
            ctx.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_DIALER)
        } else {
            ctx.getSystemService(TelecomManager::class.java).defaultDialerPackage == ctx.packageName
        }
    } catch (_: Exception) {
        false
    }

    fun isAvailable(ctx: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= 29) {
            ctx.getSystemService(RoleManager::class.java).isRoleAvailable(RoleManager.ROLE_DIALER)
        } else {
            true
        }
    } catch (_: Exception) {
        false
    }

    /** System dialog "Set Alo Answer as your default phone app?". Start it for a result. */
    fun requestIntent(ctx: Context): Intent =
        if (Build.VERSION.SDK_INT >= 29) {
            ctx.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_DIALER)
        } else {
            @Suppress("DEPRECATION")
            Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, ctx.packageName)
        }
}
