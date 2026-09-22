package com.naling.shuai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * 棂记 · 桥的常驻前台服务（2026-09-22 纳棂：每次打开都没同步，还要手动点）
 * 为什么需要：桥原来只是个守护线程，App 退到后台就被系统（尤其 vivo）冻结 →
 * 20 秒轮询停了，只有你手动点「立即同步」才动一下。
 * 前台服务带一条常驻通知，系统不会冻它，同步才是连续的。
 */
class BridgeService : Service() {

    private val CH = "linji_bridge"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Bridge.startThread(this)
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (nm.getNotificationChannel(CH) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(CH, "棂记同步", NotificationManager.IMPORTANCE_MIN).apply {
                            setShowBadge(false)
                        }
                    )
                }
                val n: Notification =
                    if (Build.VERSION.SDK_INT >= 26)
                        Notification.Builder(this, CH)
                            .setContentTitle("棂记 · 自动同步中")
                            .setContentText("每 20 秒一次，手机↔服务器")
                            .setSmallIcon(android.R.drawable.stat_notify_sync)
                            .setOngoing(true)
                            .build()
                    else Notification.Builder(this).setContentTitle("棂记 · 自动同步中").build()
                if (Build.VERSION.SDK_INT >= 34) {
                    startForeground(9911, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } else {
                    startForeground(9911, n)
                }
            }
        } catch (_: Exception) {
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Bridge.stopThread()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
