package com.naling.shuai

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/** 定时：锁机 / 起床 / 用药提醒（含开机自启恢复） */
object Alarms {
    const val REQ_LOCK = 1001
    const val REQ_WAKE = 1002
    const val REQ_MED = 2000
    const val REQ_SNOOZE = 1003
    const val REQ_WATER = 1004
    const val REQ_WATCH = 1005

    const val A_LOCK = "LOCK"
    const val A_WAKE = "WAKE"
    const val A_MED = "MED"
    const val A_SNOOZE = "SNOOZE"
    const val A_WATER = "WATER"
    const val A_WATCH = "WATCH"

    private fun pi(c: Context, req: Int, action: String, extraId: String? = null): PendingIntent {
        val i = Intent(c, AlarmReceiver::class.java).setAction(action)
        if (extraId != null) i.putExtra("id", extraId)
        val flags = if (Build.VERSION.SDK_INT >= 23)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getBroadcast(c, req, i, flags)
    }

    private fun at(h: Int, m: Int, addDayIfPast: Boolean = true): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, h)
        c.set(Calendar.MINUTE, m)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (addDayIfPast && c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis
    }

    private fun am(c: Context) = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** 锁机用 setAlarmClock：最不容易被系统省电策略打掉，且允许后台拉起前台服务 */
    fun scheduleLock(c: Context) {
        val a = am(c)
        val pi = pi(c, REQ_LOCK, A_LOCK)
        a.cancel(pi)
        if (!Store.lockEnabled()) return
        val t = at(Store.lockH(), Store.lockM())
        try {
            val info = AlarmManager.AlarmClockInfo(t, Notify.pi(c, MainActivity::class.java, 11))
            a.setAlarmClock(info, pi)
        } catch (e: Exception) {
            safeExact(a, t, pi)
        }
    }

    fun scheduleWake(c: Context) {
        val a = am(c)
        val pi = pi(c, REQ_WAKE, A_WAKE)
        a.cancel(pi)
        if (!Store.flag("wakeNoti")) return
        safeExact(a, at(Store.wakeH(), Store.wakeM()), pi)
    }

    fun scheduleMeds(c: Context) {
        val a = am(c)
        // 先取消旧的（每瓶药最多 10 个时间点）
        for (i in 0 until 400) a.cancel(pi(c, REQ_MED + i, A_MED))
        Store.meds().forEachIndexed { idx, med ->
            Store.medTimes(med).forEachIndexed { ti, t ->
                val parts = t.split(":")
                val h = parts.getOrNull(0)?.toIntOrNull() ?: 21
                val m = parts.getOrNull(1)?.toIntOrNull() ?: 10
                safeExact(a, at(h, m), pi(c, REQ_MED + idx * 10 + ti, A_MED, med.id))
            }
        }
    }

    /** 晚 N 分钟再锁（贪睡） */
    fun scheduleSnooze(c: Context, minutes: Int) {
        val a = am(c)
        val pi = pi(c, REQ_SNOOZE, A_SNOOZE)
        a.cancel(pi)
        safeExact(a, System.currentTimeMillis() + minutes * 60_000L, pi)
    }

    fun cancelSnooze(c: Context) {
        try {
            am(c).cancel(pi(c, REQ_SNOOZE, A_SNOOZE))
        } catch (_: Exception) {
        }
    }

    /** 喝水 / 久坐提醒：按间隔排下一次，到点在窗口内才提醒 */
    fun scheduleWater(c: Context) {
        val a = am(c)
        val pi = pi(c, REQ_WATER, A_WATER)
        a.cancel(pi)
        if (!Store.waterOn()) return
        safeExact(a, System.currentTimeMillis() + Store.waterMin() * 60_000L, pi)
    }

    fun scheduleAll(c: Context) {
        scheduleLock(c)
        scheduleWake(c)
        scheduleMeds(c)
        scheduleWater(c)
        scheduleNightWatch(c)
    }

    /**
     * ★ v2.15 夜间看门狗：每 15 分钟醒一次，发现「还在睡但锁机/悬浮窗没了」就把它铺回来。
     * 为什么需要：vivo 等国产 ROM 会在夜里杀掉后台进程（用户 2026-09-21 报「软件被杀了」），
     * 悬浮窗一没就整夜没记录、早上还会莫名其妙冒出「0 小时 0 分」。
     * 用 setAndAllowWhileIdle（Doze 里也能醒），不用 setAlarmClock（那会在状态栏挂个闹钟图标）。
     */
    fun scheduleNightWatch(c: Context) {
        val a = am(c)
        val pi = pi(c, REQ_WATCH, A_WATCH)
        a.cancel(pi)
        // 不在夜里 / 这一觉已经结束了 → 不再续，自己就停
        if (Store.sleepStart() <= 0L && !LockService.shouldLockNow()) return
        val t = System.currentTimeMillis() + 15 * 60_000L
        try {
            a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
        } catch (_: Exception) {
            try {
                a.set(AlarmManager.RTC_WAKEUP, t, pi)
            } catch (_: Exception) {
            }
        }
    }

    fun cancelNightWatch(c: Context) {
        try {
            am(c).cancel(pi(c, REQ_WATCH, A_WATCH))
        } catch (_: Exception) {
        }
    }

    private fun safeExact(a: AlarmManager, t: Long, pi: PendingIntent) {
        try {
            if (Build.VERSION.SDK_INT >= 31 && !a.canScheduleExactAlarms()) {
                a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
            } else if (Build.VERSION.SDK_INT >= 23) {
                a.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
            } else {
                a.setExact(AlarmManager.RTC_WAKEUP, t, pi)
            }
        } catch (e: Exception) {
            try {
                a.set(AlarmManager.RTC_WAKEUP, t, pi)
            } catch (_: Exception) {
            }
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        Store.init(c)
        when (intent.action) {
            Alarms.A_LOCK -> {
                // 到点：拉起锁机。已经过了起床时间（闹钟迟到 / 手机刚开机才收到）就不锁
                if (LockService.shouldLockNow()) LockService.start(c)
                Alarms.scheduleLock(c)
            }
            Alarms.A_SNOOZE -> {
                // 「晚 N 分钟」到点：过了起床时间就不再锁回来（早上熬夜玩手机不该被锁）
                if (LockService.shouldLockNow()) LockService.start(c, 0) else LockService.stop(c)
            }
            Alarms.A_WATER -> {
                val hh = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                if (Store.waterOn() && hh >= Store.waterFrom() && hh < Store.waterTo()) {
                    Notify.show(
                        c, 41,
                        Notify.build(
                            c, Notify.CH_REMIND, "喝水 / 站起来动一动 💧",
                            "喝水 200ml，顺便看看远处 20 秒（对眼睛和皮肤都好）",
                            pi = Notify.pi(c, MainActivity::class.java, 19)
                        )
                    )
                }
                Alarms.scheduleWater(c)
            }
            Alarms.A_WAKE -> {
                Notify.show(
                    c, 21,
                    Notify.build(
                        c, Notify.CH_REMIND, "该起床了 ☀️", "7:00 起床 · 洗脸 → 保湿 → 防晒",
                        pi = Notify.pi(c, MainActivity::class.java, 12)
                    )
                )
                Alarms.scheduleWake(c)
            }
            Alarms.A_WATCH -> {
                // ★ v2.15 看门狗：夜里进程被杀 → 悬浮窗/计时一起没了这里把它铺回来。
                // 还在睡（有入睡时间）或还在锁机时段内，且服务确实不在跑 → 重新拉起。
                if (Store.sleepStart() > 0L || LockService.shouldLockNow()) {
                    if (!LockService.running) {
                        Store.setLastKilled(System.currentTimeMillis())
                        LockService.start(
                            c, 0,
                            if (Store.sleepStart() > 0L) LockService.MODE_SLEEP else LockService.MODE_ASK
                        )
                    }
                    Alarms.scheduleNightWatch(c)
                }
            }
            Alarms.A_MED -> {
                val id = intent.getStringExtra("id")
                val med = Store.meds().firstOrNull { it.id == id }
                if (med != null) {
                    Notify.show(
                        c, 31 + (med.name.hashCode() and 7),
                        Notify.build(
                            c, Notify.CH_REMIND, "该用药了 💊", med.name + (if (med.note.isNotEmpty()) " · " + med.note else ""),
                            pi = Notify.pi(c, MainActivity::class.java, 13)
                        )
                    )
                }
                Alarms.scheduleMeds(c)
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        Store.init(c)
        Notify.channels(c)
        Alarms.scheduleAll(c)
        // 如果现在正处于锁机时段，重启后也恢复锁机
        if (LockService.shouldLockNow()) LockService.start(c)
    }
}
