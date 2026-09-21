package com.naling.shuai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object Notify {
    const val CH_LOCK = "lock"
    const val CH_REMIND = "remind"
    const val CH_POMO = "pomo"

    fun channels(c: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH_LOCK, "睡眠锁机", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_REMIND, "用药 / 起床提醒", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_POMO, "番茄钟", NotificationManager.IMPORTANCE_LOW))
    }

    fun pi(c: Context, cls: Class<*>, req: Int, extra: String? = null): PendingIntent {
        val i = Intent(c, cls)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (extra != null) i.putExtra("action", extra)
        val flags = if (Build.VERSION.SDK_INT >= 23)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getActivity(c, req, i, flags)
    }

    fun build(
        c: Context, ch: String, title: String, text: String,
        ongoing: Boolean = false, pi: PendingIntent? = null
    ): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, ch) else Notification.Builder(c)
        b.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setShowWhen(false)
        if (pi != null) b.setContentIntent(pi)
        return b.build()
    }

    fun show(c: Context, id: Int, n: Notification) {
        try {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(id, n)
        } catch (_: Exception) {
        }
    }

    fun cancel(c: Context, id: Int) {
        try {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(id)
        } catch (_: Exception) {
        }
    }
}
