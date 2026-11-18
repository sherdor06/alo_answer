package com.aloanswer.alo_answer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * Tells the user that voice answering could NOT start for this call and why
 * (blocked background launch, bad AccessKey, missing .ppn, ...). Tapping it
 * only opens the app; it never answers the call.
 */
object ProblemNotification {
    private const val CHANNEL_ID = "problems"
    private const val NOTIF_ID = 1003

    fun show(ctx: Context, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val nm = ctx.getSystemService(NotificationManager::class.java)
        // Re-creating an existing channel only updates its name/description (current language).
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                Settings.str(ctx, R.string.notif_problem_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = Settings.str(ctx, R.string.notif_problem_channel_desc)
                setSound(null, null) // don't add noise on top of the ringtone
            }
        )
        val open = PendingIntent.getActivity(
            ctx, 3,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(Settings.str(ctx, R.string.notif_problem_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID, n)
    }
}
