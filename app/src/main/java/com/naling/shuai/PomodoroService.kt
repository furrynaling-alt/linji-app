package com.naling.shuai

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator

/** 番茄钟后台计时（前台服务 + 通知倒计时） */
class PomodoroService : Service() {

    companion object {
        const val NOTI_ID = 102
        const val PHASE_WORK = 0
        const val PHASE_BREAK = 1
        const val PHASE_LONG = 2

        @Volatile
        var running = false
            private set
        @Volatile
        var paused = false
            private set
        @Volatile
        var phase = PHASE_WORK
            private set
        @Volatile
        var remainSec = 0
            private set
        @Volatile
        var totalSec = 0
            private set
        @Volatile
        var autoNext = true

        var onTick: (() -> Unit)? = null

        private var doneInCycle = 0

        fun start(c: Context, phaseIn: Int = PHASE_WORK, minutes: Int = -1) {
            phase = phaseIn
            val mins = if (minutes > 0) minutes else when (phaseIn) {
                PHASE_WORK -> Store.pomoWork()
                PHASE_BREAK -> Store.pomoBreak()
                else -> Store.pomoLong()
            }
            totalSec = mins * 60
            remainSec = totalSec
            paused = false
            try {
                val i = Intent(c, PomodoroService::class.java)
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
            } catch (_: Exception) {
            }
        }

        fun stopAll(c: Context) {
            try {
                c.stopService(Intent(c, PomodoroService::class.java))
            } catch (_: Exception) {
            }
        }

        fun togglePause() {
            paused = !paused
        }

        fun phaseName(p: Int): String = when (p) {
            PHASE_WORK -> "专注"
            PHASE_BREAK -> "休息"
            else -> "长休息"
        }

        fun mmss(sec: Int): String = "%02d:%02d".format(sec / 60, sec % 60)
    }

    private val h = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            if (!paused && remainSec > 0) {
                remainSec--
                updateNoti()
                try {
                    onTick?.invoke()
                } catch (_: Exception) {
                }
            }
            if (remainSec <= 0) {
                finishPhase()
                return
            }
            h.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Store.init(this)
        running = true
        startForeground(NOTI_ID, buildNoti())
        h.removeCallbacks(tick)
        h.postDelayed(tick, 1000)
        return START_NOT_STICKY
    }

    private fun buildNoti(): android.app.Notification = Notify.build(
        this, Notify.CH_POMO,
        "${phaseName(phase)} · ${mmss(remainSec)}" + (if (paused) "（已暂停）" else ""),
        "今日已完成 ${Store.pomoDoneToday()} 个番茄 · 点开查看",
        ongoing = true, pi = Notify.pi(this, MainActivity::class.java, 17)
    )

    private fun updateNoti() {
        Notify.show(this, NOTI_ID, buildNoti())
    }

    private fun finishPhase() {
        h.removeCallbacks(tick)
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 26)
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 120, 200), -1))
            else
                @Suppress("DEPRECATION") v.vibrate(longArrayOf(0, 200, 120, 200), -1)
        } catch (_: Exception) {
        }

        val finished = phase
        if (finished == PHASE_WORK) {
            Store.incPomoToday()
            doneInCycle++
        }
        val next = when (finished) {
            PHASE_WORK -> if (doneInCycle >= Store.pomoCycles()) PHASE_LONG else PHASE_BREAK
            else -> PHASE_WORK
        }
        if (next == PHASE_WORK || next == PHASE_LONG) doneInCycle = if (next == PHASE_LONG) 0 else doneInCycle

        val msg = if (finished == PHASE_WORK) "🍅 一个番茄完成！该 ${phaseName(next)}了" else "休息结束，继续专注"
        Notify.show(this, 95, Notify.build(this, Notify.CH_POMO, msg, "今日已完成 ${Store.pomoDoneToday()} 个番茄"))

        if (!autoNext) {
            running = false
            stopSelf()
            return
        }
        phase = next
        val mins = when (next) {
            PHASE_WORK -> Store.pomoWork()
            PHASE_BREAK -> Store.pomoBreak()
            else -> Store.pomoLong()
        }
        totalSec = mins * 60
        remainSec = totalSec
        updateNoti()
        h.postDelayed(tick, 1000)
    }

    override fun onDestroy() {
        running = false
        h.removeCallbacks(tick)
        super.onDestroy()
    }
}
