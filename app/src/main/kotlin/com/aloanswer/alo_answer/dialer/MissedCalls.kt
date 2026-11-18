package com.aloanswer.alo_answer.dialer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import com.aloanswer.alo_answer.R
import com.aloanswer.alo_answer.Settings

/**
 * The missed-call notification. As the default phone app, Telecom asks us to
 * show it (ACTION_SHOW_MISSED_CALLS_NOTIFICATION, with the count and the last
 * number) instead of showing its own; count 0 means "remove it".
 */
object MissedCalls {
    private const val CHANNEL = "missed_calls"
    private const val ID = 2003

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    /** Blocking (contact lookup): call off the main thread. */
    fun show(ctx: Context, count: Int, number: String?) {
        if (count <= 0) {
            cancel(ctx)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = nm(ctx) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, Settings.str(ctx, R.string.notif_missed_channel), NotificationManager.IMPORTANCE_DEFAULT)
        )
        val n = number?.takeIf { it.isNotBlank() }
        val who = n?.let { ContactsRepo.nameFor(ctx, it) ?: PhoneSettings.format(ctx, it) }
            ?: Settings.str(ctx, R.string.incall_unknown)
        val title = if (count == 1) Settings.str(ctx, R.string.notif_missed_title)
        else Settings.localized(ctx).resources.getQuantityString(R.plurals.notif_missed_title_n, count, count)
        val text = if (count == 1) who else Settings.str(ctx, R.string.notif_missed_last, who)

        val open = PendingIntent.getActivity(
            ctx, 30,
            Intent(ctx, DialerActivity::class.java)
                .putExtra(DialerActivity.EXTRA_SHOW_MISSED, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_call_missed)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setNumber(count)
        if (Build.VERSION.SDK_INT >= 33) b.setCategory(Notification.CATEGORY_MISSED_CALL)
        if (n != null) {
            val callBack = if (ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                PendingIntent.getBroadcast(
                    ctx, 31,
                    Intent(ctx, CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION_CALL_BACK)
                        .putExtra(CallActionReceiver.EXTRA_NUMBER, n),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            } else {
                // No call permission yet: open the dial pad with the number.
                PendingIntent.getActivity(
                    ctx, 31,
                    Intent(ctx, DialerActivity::class.java).setData(Uri.fromParts("tel", n, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            }
            val sms = PendingIntent.getActivity(
                ctx, 32,
                Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", n, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            b.addAction(Notification.Action.Builder(
                Icon.createWithResource(ctx, R.drawable.ic_call), Settings.str(ctx, R.string.notif_call_back), callBack
            ).build())
            b.addAction(Notification.Action.Builder(
                Icon.createWithResource(ctx, R.drawable.ic_message), Settings.str(ctx, R.string.dialer_sms), sms
            ).build())
        }
        nm.notify(ID, b.build())
    }

    fun cancel(ctx: Context) {
        nm(ctx)?.cancel(ID)
    }

    /**
     * The missed calls were seen: remove the notification, clear the calls' "new" flag (Telecom
     * leaves that to us, since we show the notification) and reset Telecom's count. Blocking.
     */
    fun markReadBlocking(ctx: Context) {
        cancel(ctx)
        CallLogRepo.markMissedRead(ctx)
        try {
            ctx.getSystemService(TelecomManager::class.java)?.cancelMissedCallsNotification()
        } catch (_: Exception) {
        }
    }

    /** [markReadBlocking] on a background thread. */
    fun markRead(ctx: Context) {
        val app = ctx.applicationContext
        cancel(app)
        Thread { markReadBlocking(app) }.start()
    }
}

/** Telecom's "show the missed-call notification" broadcast to the default phone app. */
class MissedCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelecomManager.ACTION_SHOW_MISSED_CALLS_NOTIFICATION) return
        val count = intent.getIntExtra(TelecomManager.EXTRA_NOTIFICATION_COUNT, 0)
        val number = intent.getStringExtra(TelecomManager.EXTRA_NOTIFICATION_PHONE_NUMBER)
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                MissedCalls.show(app, count, number)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
