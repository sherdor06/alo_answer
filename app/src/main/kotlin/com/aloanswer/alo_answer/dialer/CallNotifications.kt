package com.aloanswer.alo_answer.dialer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.telecom.Call
import com.aloanswer.alo_answer.R
import com.aloanswer.alo_answer.Settings

/**
 * Incoming-call notification (heads-up when the phone is in use, full-screen
 * call screen when it isn't) and the ongoing-call notification, as the
 * InCallService docs recommend for the default phone app.
 */
object CallNotifications {
    private const val CH_INCOMING = "incoming_calls"
    /** Same incoming notification without a heads-up, used while our call screen is already in front. */
    private const val CH_INCOMING_QUIET = "incoming_calls_quiet"
    private const val CH_ONGOING = "ongoing_calls"
    private const val ID_INCOMING = 2001
    private const val ID_ONGOING = 2002

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    private fun canPost(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensureChannels(ctx: Context) {
        val nm = nm(ctx)
        nm.createNotificationChannel(
            NotificationChannel(
                CH_INCOMING, Settings.str(ctx, R.string.notif_incoming_channel), NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(null, null) // Telecom plays the ringtone
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CH_INCOMING_QUIET, Settings.str(ctx, R.string.notif_incoming_channel), NotificationManager.IMPORTANCE_LOW
            ).apply { lockscreenVisibility = Notification.VISIBILITY_PUBLIC }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CH_ONGOING, Settings.str(ctx, R.string.notif_ongoing_channel), NotificationManager.IMPORTANCE_LOW
            ).apply { lockscreenVisibility = Notification.VISIBILITY_PUBLIC }
        )
    }

    private fun screenIntent(ctx: Context) = PendingIntent.getActivity(
        ctx, 10, Intent(ctx, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun action(ctx: Context, action: String, req: Int) = PendingIntent.getBroadcast(
        ctx, req, Intent(ctx, CallActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** Called on every call change. */
    fun refresh(ctx: Context) {
        if (!canPost(ctx)) return
        ensureChannels(ctx)
        val ringing = CallManager.ringingCall()
        val current = CallManager.primaryCall()
        when {
            ringing != null -> {
                nm(ctx).cancel(ID_ONGOING)
                nm(ctx).notify(ID_INCOMING, incoming(ctx, ringing))
            }
            current != null && CallManager.stateOf(current) != Call.STATE_DISCONNECTED -> {
                nm(ctx).cancel(ID_INCOMING)
                nm(ctx).notify(ID_ONGOING, ongoing(ctx, current))
            }
            else -> cancelAll(ctx)
        }
    }

    fun cancelAll(ctx: Context) {
        nm(ctx).cancel(ID_INCOMING)
        nm(ctx).cancel(ID_ONGOING)
    }

    private fun caller(ctx: Context, call: Call) =
        CallManager.displayName(call) ?: Settings.str(ctx, R.string.incall_unknown)

    private fun incoming(ctx: Context, call: Call): Notification {
        val name = caller(ctx, call)
        val answer = action(ctx, CallActionReceiver.ACTION_ANSWER, 11)
        val decline = action(ctx, CallActionReceiver.ACTION_DECLINE, 12)
        val channel = if (InCallActivity.visible) CH_INCOMING_QUIET else CH_INCOMING
        val b = Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(name)
            .setContentText(Settings.str(ctx, R.string.incall_incoming))
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(screenIntent(ctx))
            .setFullScreenIntent(screenIntent(ctx), true)
        if (Build.VERSION.SDK_INT >= 31) {
            val person = Person.Builder().setName(name).setImportant(true).build()
            b.setStyle(Notification.CallStyle.forIncomingCall(person, decline, answer))
        } else {
            b.addAction(Notification.Action.Builder(
                Icon.createWithResource(ctx, R.drawable.ic_call_end), Settings.str(ctx, R.string.incall_decline), decline
            ).build())
            b.addAction(Notification.Action.Builder(
                Icon.createWithResource(ctx, R.drawable.ic_call), Settings.str(ctx, R.string.incall_answer), answer
            ).build())
        }
        return b.build()
    }

    private fun ongoing(ctx: Context, call: Call): Notification {
        val connectedAt = call.details.connectTimeMillis
        val b = Notification.Builder(ctx, CH_ONGOING)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(caller(ctx, call))
            .setContentText(Settings.str(ctx, R.string.notif_ongoing_title))
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(screenIntent(ctx))
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(ctx, R.drawable.ic_call_end), Settings.str(ctx, R.string.incall_end),
                action(ctx, CallActionReceiver.ACTION_HANGUP, 13)
            ).build())
        if (connectedAt > 0) b.setWhen(connectedAt).setUsesChronometer(true).setShowWhen(true)
        return b.build()
    }
}
