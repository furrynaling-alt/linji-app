package com.naling.shuai

import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.pm.PackageManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.PowerManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.text.SimpleDateFormat

/**
 * 锁机 / 睡眠
 *  - 22:50 到点 → 询问界面：「我要睡觉了」/「晚十分钟再睡觉」
 *  - 点「我要睡觉了」→ 记下入睡时间 + 熄屏，进入睡眠模式（早上好按钮 4:00 后才可用）
 *  - 点「早上好」→ 结束这一觉，记录睡了多久
 *  - 任何时候长按 N 秒可提前解锁（记一次破戒）
 *  - ★ 点过「早上好」/「我起床了」/提前解锁 = 这一觉结束了 → 到今晚锁机时刻之前
 *      不再自动锁回来（开 App / 闹钟迟到 / 开机恢复 / 贪睡到点 都不会再锁）
 *  - ★ 询问屏（还没开始睡）过了起床时间会自己收起；睡眠屏不自动收（留着看睡了多久）
 */
class LockService : Service() {

    companion object {
        const val NOTI_ID = 101
        const val EXTRA_MIN = "minutes"
        const val EXTRA_MODE = "mode"

        const val MODE_ASK = "ask"       // 22:50 的询问界面
        const val MODE_SLEEP = "sleep"   // 睡眠模式（已睡计时）
        const val MODE_PLAIN = "plain"   // 普通/定时锁机

        @Volatile
        var running = false
            private set

        /** 本次锁机时段的开始时刻（ms）——「早上好」之后不再锁回来的判断基准 */
        private fun windowStartMs(): Long {
            val t = Store.lockH() * 60 + Store.lockM()
            val n = Store.nowMinutes()
            // n < 起床时间 = 凌晨那一段，窗口是「昨天」开的
            val startMin = if (n >= t) t else t - 24 * 60
            return Store.todayStartMs() + startMin * 60_000L
        }

        fun shouldLockNow(): Boolean {
            if (!Store.lockEnabled()) return false
            val t = Store.lockH() * 60 + Store.lockM()
            val w = Store.wakeH() * 60 + Store.wakeM()
            val n = Store.nowMinutes()
            val inWin = if (t > w) (n >= t || n < w) else (n >= t && n < w)
            if (!inWin) return false
            // ★ 这一觉已经结束过了（点「早上好」/「我起床了」/长按提前解锁）
            //   → 到今晚锁机时刻之前，任何入口都不许再把它锁回来（修的 bug：按完了还锁）
            return Store.morningMs() < windowStartMs()
        }

        fun start(c: Context, minutes: Int = 0, mode: String = MODE_ASK) {
            try {
                val i = Intent(c, LockService::class.java)
                    .putExtra(EXTRA_MIN, minutes).putExtra(EXTRA_MODE, mode)
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
            } catch (e: Exception) {
                Notify.show(
                    c, 98,
                    Notify.build(
                        c, Notify.CH_LOCK, "点这里开始锁机", "系统限制了后台启动，点一下就行",
                        pi = Notify.pi(c, MainActivity::class.java, 14, "lock")
                    )
                )
            }
        }

        fun stop(c: Context) {
            try {
                c.stopService(Intent(c, LockService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    private var wm: WindowManager? = null
    private var overlay: View? = null
    private var progress: ProgressBar? = null
    private var holdText: TextView? = null
    private var infoText: TextView? = null
    private var clockText: TextView? = null
    private val h = Handler(Looper.getMainLooper())
    private var holdStart = 0L
    private var endAt = 0L
    private var timed = false
    private var lockMinutes = 0
    private var mode = MODE_ASK
    private var finished = false
    private var overlayLp: WindowManager.LayoutParams? = null
    private var originalTimeout = 0
    private var blackMode = false
    private var overlayRoot: FrameLayout? = null
    private var aliveAt = 0L
    private var overlayImg: ImageView? = null
    private var contentCol: LinearLayout? = null
    private var morningBtn: android.widget.Button? = null
    private var morningWait: TextView? = null
    private var morningHint: TextView? = null

    private fun applyMorningState(ok: Boolean) {
        morningBtn?.visibility = if (ok) View.VISIBLE else View.GONE
        morningWait?.visibility = if (ok) View.GONE else View.VISIBLE
        morningHint?.visibility = if (ok) View.GONE else View.VISIBLE
    }

    private val tick = object : Runnable {
        override fun run() {
            if (finished) return
            // 「到点了」询问屏只在锁机时段里挂着：过了起床时间就自己收起。
            // 注意：睡眠界面（MODE_SLEEP，已睡计时）不自动收 —— 那是你要看「睡了多久」的界面，
            // 得留着等你点「早上好」；这里收的是「压根没开始睡」的询问屏。
            if (!timed && mode == MODE_ASK && !shouldLockNow()) {
                dismissAsk()
                return
            }
            clockText?.text = nowHhmmSs()
            when (mode) {
                MODE_SLEEP -> {
                    val start = Store.sleepStart()
                    // ★ v2.15 心跳：每 15 秒落一次「还活着」。进程被系统杀掉后，
                    //   就靠它判断这一觉断过没有（见 Store.recoverStaleSleep）
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - aliveAt > 15_000L) {
                        aliveAt = nowMs
                        Store.setLastAlive(nowMs)
                    }
                    val slept = if (start > 0) (System.currentTimeMillis() - start) / 60000L else 0L
                    infoText?.text = "已睡 %d 小时 %d 分\n%s".format(slept / 60, slept % 60, Store.morningHint())
                    // ★ 到点（默认 4:00）自动放出「早上好」，之前不显示
                    val nowOk = canWake()
                    applyMorningState(nowOk)
                }
                MODE_PLAIN -> {
                    val left = endAt - System.currentTimeMillis()
                    if (left <= 0) {
                        autoUnlock(); return
                    }
                    val mm = (left / 60000).toInt()
                    val ss = ((left / 1000) % 60).toInt()
                    infoText?.text = if (timed) "还有 %02d:%02d 自动解锁".format(mm, ss)
                    else "还有 %d 小时 %d 分到起床时间".format(mm / 60, mm % 60)
                }
                else -> {
                    // MODE_ASK：显示距离现在多少分钟
                    infoText?.text = "现在 ${nowHhmm()} · 目标睡觉 ${Store.lockHhmm()}"
                }
            }
            h.postDelayed(this, 1000)
        }
    }

    private val holdTick = object : Runnable {
        override fun run() {
            val need = Store.holdSec() * 1000L
            val el = System.currentTimeMillis() - holdStart
            val p = ((el * 100f) / need).toInt().coerceIn(0, 100)
            progress?.progress = p
            holdText?.text = if (p >= 100) "解锁中…" else "长按中… 还剩 %.1f 秒".format((need - el) / 1000f)
            if (el >= need) {
                breakOut()
                return
            }
            h.postDelayed(this, 60)
        }
    }

    private fun nowHhmm(): String {
        val c = Calendar.getInstance()
        return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    /** 顶部大时钟：时:分:秒 */
    private fun nowHhmmSs(): String {
        val c = Calendar.getInstance()
        return "%02d:%02d:%02d".format(
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND)
        )
    }

    /**
     * 「早上好」的门槛 = 入睡那天之后的【次日 earlyHhmm（默认 04:00）】。
     * （旧 bug：只看"现在几点"，结果晚上 23 点睡下 6 分钟就显示"可以起床了"）
     */
    private fun canWake(): Boolean = Store.canMorning()

    /** 按钮/提示文案 */
    private fun wakeBtnText(): String {
        if (!Store.flag("earlyGate")) return "早上好 ☀️"
        return if (Store.canMorning()) "早上好 ☀️" else "早上好（${Store.earlyHhmm()} 后才能起）"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Store.init(this)
        val minutes = intent?.getIntExtra(EXTRA_MIN, 0) ?: 0
        val wantMode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_ASK
        running = true
        finished = false
        lockMinutes = if (minutes > 0) minutes else 0
        timed = minutes > 0
        mode = when {
            wantMode == MODE_PLAIN -> MODE_PLAIN
            wantMode == MODE_SLEEP -> MODE_SLEEP
            // 已经在睡（有未结束的入睡时间）→ 回到睡眠界面，别再问一遍
            Store.sleepStart() > 0L -> MODE_SLEEP
            // 关掉「先问」→ 到点直接进睡眠模式（自动开始记录入睡）
            !Store.flag("askMode") -> MODE_SLEEP
            else -> MODE_ASK
        }
        // 自动弹的「到点了」只在锁机时段内出现：起床时间之后的迟到闹钟 / 重启不再把手机锁上
        if (mode == MODE_ASK && !timed && !shouldLockNow()) {
            running = false
            try {
                Notify.channels(this)
                startForeground(NOTI_ID, buildNoti())
                stopForeground(true)
            } catch (_: Exception) {
            }
            stopSelf()
            return START_NOT_STICKY
        }
        // ★ v2.15 半夜进程被系统杀掉 → 这一觉自动补记，别假锁一回、也别记出「0 小时 0 分」
        if (mode == MODE_SLEEP && Store.sleepStart() > 0L) {
            val rec = Store.recoverStaleSleep()
            if (rec >= 0) {
                logLock("半夜进程被杀 → 这一觉已自动补记 %d 分钟".format(rec))
                running = false
                try {
                    Notify.channels(this)
                    startForeground(NOTI_ID, buildNoti())
                    stopForeground(true)
                } catch (_: Exception) {
                }
                Notify.show(
                    this, 95,
                    Notify.build(
                        this, Notify.CH_LOCK, "这一觉补记好了 ☀️",
                        "睡了 %d 小时 %d 分（半夜被系统杀了，时间已自动补上）".format(rec / 60, rec % 60),
                        ongoing = false
                    )
                )
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (mode == MODE_SLEEP && Store.sleepStart() == 0L) Store.setSleepStart(System.currentTimeMillis())
        aliveAt = System.currentTimeMillis()
        Store.setLastAlive(aliveAt)
        if (mode == MODE_SLEEP) Alarms.scheduleNightWatch(this)
        // ★ v2.13：不再自动熄屏 / 不再黑屏兜底 —— 睡眠界面就留在这儿，由你自己锁屏
        endAt = if (timed) System.currentTimeMillis() + minutes * 60_000L else wakeMillis()

        if (mode == MODE_SLEEP && Store.sleepStart() == 0L) {
            Store.setSleepStart(System.currentTimeMillis())
        }

        startForeground(NOTI_ID, buildNoti())
        if (Settings.canDrawOverlays(this)) {
            rebuildOverlay()
            val d = Store.day()
            if (mode != MODE_SLEEP && Store.lastLockDay() != d) {
                Store.setLastLockDay(d)
                Store.bumpLockStreak()
            }
        } else {
            Notify.show(
                this, 97,
                Notify.build(
                    this, Notify.CH_LOCK, "还差一步：打开「悬浮窗」权限",
                    "没有它就盖不住屏幕（点这里去设置）",
                    pi = Notify.pi(this, MainActivity::class.java, 16, "perm")
                )
            )
        }
        registerScreenReceiver()
        h.removeCallbacks(tick)
        h.post(tick)
        return START_STICKY
    }

    private fun buildNoti(): android.app.Notification {
        val (t, s) = when (mode) {
            MODE_SLEEP -> Pair("睡眠模式 🌙", "已睡 ${Store.sleepStart().let { if (it > 0) (System.currentTimeMillis() - it) / 60000 else 0 }} 分钟 · 点「早上好」记录")
            MODE_PLAIN -> Pair(
                if (timed) "锁机中 · $lockMinutes 分钟" else "睡眠模式运行中 🌙",
                "长按可提前解锁（记一次破戒）"
            )
            else -> Pair("该睡觉了 🌙", "点「我要睡觉了」熄屏入睡，或「晚 ${Store.snoozeMin()} 分钟」")
        }
        return Notify.build(this, Notify.CH_LOCK, t, s, ongoing = true, pi = Notify.pi(this, MainActivity::class.java, 15))
    }

    /** 顶部日期：9月20日 星期日 */
    private fun todayCn(): String =
        SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(Date())

    private fun wakeMillis(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, Store.wakeH())
        c.set(Calendar.MINUTE, Store.wakeM())
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis
    }

    private fun canWriteSettings(): Boolean = try {
        android.provider.Settings.System.canWrite(this)
    } catch (_: Exception) {
        false
    }

    /** 去开「修改系统设置」权限（用来把屏幕超时临时调短 = 秒熄屏，且不触发密码锁） */
    private fun openWriteSettings() {
        try {
            val i = Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS)
            i.data = android.net.Uri.parse("package:$packageName")
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (_: Exception) {
            try {
                startActivity(
                    Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun shizukuReady(): Boolean = ShizukuShell.granted()

    /**
     * 熄屏（不锁屏）：不调用 lockNow（那会要求输密码）
     * 做法 = 放开常亮 + 把系统"屏幕超时"临时调到 1 秒 → 屏幕自然熄灭
     * 如果 8 秒后屏幕还亮着（厂商不理会该设置 / 开了"充电时保持唤醒" / 智能保持亮屏），
     * 自动启用「黑屏兜底」：窗口亮度降到 0，看上去就是熄屏状态，点一下恢复。
     */
    private fun turnScreenOffSoon() {
        // 去掉 KEEP_SCREEN_ON，否则永远不会超时
        try {
            val base = (WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            overlayLp?.let { lp ->
                lp.flags = base
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                overlay?.let { v -> wm?.updateViewLayout(v, lp) }
            }
        } catch (_: Exception) {
        }

        // 首选：收起悬浮窗，给 N 秒自己在控制中心点「一键锁屏」
        if (Store.manualLockSec() > 0) {
            countdownManualLock()
            return
        }

        val canWrite = try {
            android.provider.Settings.System.canWrite(this)
        } catch (_: Exception) {
            false
        }

        if (!canWrite) {
            infoText?.text = "熄屏没生效：没给「修改系统设置」权限，也没装 Shizuku\n（装 Shizuku = 真·按电源键熄屏，最稳）"
            enableBlackMode()
            return
        }

        try {
            if (originalTimeout <= 0) {
                originalTimeout = android.provider.Settings.System.getInt(
                    contentResolver, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, 30000
                )
            }
            android.provider.Settings.System.putInt(
                contentResolver, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, 1000
            )
            val back = android.provider.Settings.System.getInt(
                contentResolver, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, -1
            )
            logLock("写入屏幕超时=1000，读回=$back（原值 $originalTimeout）")
            infoText?.text = "1 秒后自动熄屏（不锁屏，醒来不用密码）\n写入校验：读回 $back"
            h.removeCallbacks(restoreTimeoutTask)
            h.postDelayed(restoreTimeoutTask, 60000L)
            // 8 秒后看屏幕是否真的灭了
            h.postDelayed({
                val on = try {
                    val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    pm.isInteractive
                } catch (_: Exception) {
                    true
                }
                logLock("8 秒后检测：屏幕还在亮 = $on")
                if (on) {
                    infoText?.text = "系统没理会熄屏设置（见下面提示）→ 已用黑屏兜底"
                    enableBlackMode()
                }
            }, 8000L)
        } catch (e: Exception) {
            logLock("写超时失败：" + e.javaClass.simpleName + " " + (e.message ?: ""))
            infoText?.text = "熄屏设置写入失败（" + e.javaClass.simpleName + "）→ 已用黑屏兜底"
            enableBlackMode()
        }
    }

    private var screenOnReceiver: BroadcastReceiver? = null

    /** 注册「屏幕亮起」监听：你手动熄屏后，再亮屏时自动把睡眠界面铺回来 */
    private fun registerScreenReceiver() {
        if (screenOnReceiver != null) return
        try {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    if (finished) return
                    if (i?.action == Intent.ACTION_SCREEN_ON && overlay == null) {
                        h.postDelayed({
                            if (!finished && overlay == null && Settings.canDrawOverlays(this@LockService)) {
                                logLock("屏幕亮起 → 铺回睡眠界面")
                                rebuildOverlay()
                            }
                        }, 400L)
                    }
                }
            }
            registerReceiver(r, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON) })
            screenOnReceiver = r
        } catch (_: Exception) {
        }
    }

    /**
     * 没有 Shizuku：收起悬浮窗，给 N 秒去「双击桌面空白处熄屏」；
     * 到点屏幕还亮着就自动盖回来（并再试一次超时熄屏 + 黑屏兜底）。
     */
    private fun countdownManualLock() {
        val total = Store.manualLockSec()
        removeOverlay()
        var left = total
        val cd = object : Runnable {
            override fun run() {
                if (finished) return
                if (left <= 0) {
                    val on = try {
                        (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
                    } catch (_: Exception) {
                        true
                    }
                    if (on) {
                        logLock("${total} 秒内没熄屏 → 重新盖回来")
                        rebuildOverlay()
                        fallbackTimeoutAndBlack()
                    } else {
                        logLock("已手动双击熄屏 ✓（再亮屏自动回睡眠界面）")
                        notifySaved()
                    }
                    return
                }
                Notify.show(
                    this@LockService, 88,
                    Notify.build(
                        this@LockService, Notify.CH_LOCK,
                        "下拉控制中心点「一键锁屏」（还有 $left 秒）",
                        "熄屏后睡眠继续计时；到点没熄会自动盖回来",
                        ongoing = true
                    )
                )
                left--
                h.postDelayed(this, 1000L)
            }
        }
        h.post(cd)
    }

    private fun notifySaved() {
        Notify.show(
            this, 87,
            Notify.build(
                this, Notify.CH_LOCK, "熄屏了 ✓",
                "睡眠计时继续走；亮屏会自动回到睡眠界面（点「早上好」记录）"
            )
        )
    }

    /** 最后的兜底：改系统超时 + 黑屏 */
    private fun fallbackTimeoutAndBlack() {
        val canWrite = try {
            android.provider.Settings.System.canWrite(this)
        } catch (_: Exception) {
            false
        }
        if (!canWrite) {
            infoText?.text = "熄屏没生效：装 Shizuku 最靠谱（见设置页）"
            enableBlackMode()
            return
        }
        try {
            if (originalTimeout <= 0) {
                originalTimeout = android.provider.Settings.System.getInt(
                    contentResolver, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, 30000
                )
            }
            android.provider.Settings.System.putInt(
                contentResolver, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, 1000
            )
            val back = android.provider.Settings.System.getInt(
                contentResolver, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, -1
            )
            logLock("兜底：写入超时=1000，读回=$back")
            infoText?.text = "1 秒后自动熄屏（读回 $back）"
            h.removeCallbacks(restoreTimeoutTask)
            h.postDelayed(restoreTimeoutTask, 60000L)
            h.postDelayed({
                val on = try {
                    (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
                } catch (_: Exception) {
                    true
                }
                if (on) {
                    logLock("兜底也无效 → 黑屏")
                    enableBlackMode()
                }
            }, 8000L)
        } catch (e: Exception) {
            logLock("兜底写超时失败：" + e.javaClass.simpleName)
            enableBlackMode()
        }
    }

    /** 黑屏兜底：窗口亮度 0 + 纯黑背景（看上去就是熄屏），点一下恢复界面 */
    private fun enableBlackMode() {
        if (blackMode) return
        blackMode = true
        try {
            overlayImg?.visibility = View.GONE
            overlayRoot?.setBackgroundColor(Color.BLACK)
            overlayLp?.let { lp ->
                lp.screenBrightness = 0f
                // 注意：这里绝不能加 KEEP_SCREEN_ON，否则屏幕永远不睡（表面全黑但背光还亮）
                lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
                overlay?.let { v -> wm?.updateViewLayout(v, lp) }
            }
            contentCol?.visibility = View.GONE
        } catch (_: Exception) {
        }
        logLock("已进入黑屏兜底模式（点屏幕一下可恢复界面）")
    }

    private fun disableBlackMode() {
        if (!blackMode) return
        blackMode = false
        try {
            overlayImg?.visibility = View.VISIBLE
            overlayRoot?.setBackgroundColor(Color.TRANSPARENT)
            overlayLp?.let { lp ->
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                if (mode != MODE_ASK) lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
                overlay?.let { v -> wm?.updateViewLayout(v, lp) }
            }
            contentCol?.visibility = View.VISIBLE
        } catch (_: Exception) {
        }
    }

    private fun logLock(msg: String) {
        val old = Store.lockLog()
        val line = "[" + nowHhmm() + "] " + msg
        Store.setLockLog((line + "\n" + old).take(1200))
    }

    private val restoreTimeoutTask = Runnable {
        try {
            if (originalTimeout > 0 && android.provider.Settings.System.canWrite(this)) {
                android.provider.Settings.System.putInt(
                    contentResolver, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, originalTimeout
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun restoreTimeoutNow() {
        try {
            h.removeCallbacks(restoreTimeoutTask)
            if (originalTimeout > 0) {
                h.post(restoreTimeoutTask)
            }
        } catch (_: Exception) {
        }
    }

    private fun lockScreenNow() = turnScreenOffSoon()

    /** 重新铺一层界面（模式切换时用） */
    private fun rebuildOverlay() {
        removeOverlay()
        val w = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = w
        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        var flags = (WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        // 只有"询问要不要睡觉"时保持常亮；进入睡眠模式就放开，好让屏幕自然熄灭
        if (mode == MODE_ASK) flags = flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type, flags, PixelFormat.TRANSLUCENT
        )
        Ui.blurBehind(lp, 22, this)   // 液态玻璃：系统级背景模糊（不支持则忽略）

        val root = FrameLayout(this)
        val iv = ImageView(this)
        iv.setImageDrawable(Ui.imageBg(this, R.drawable.bg_lock))
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        )
        val scrim = View(this)
        scrim.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x33000000, 0xE6000000.toInt())
        )
        scrim.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        )
        // 顶部当前时间：液态玻璃胶囊 + 细体大时钟 + 日期（每秒跳）
        val clockWrap = LinearLayout(this)
        clockWrap.orientation = LinearLayout.VERTICAL
        clockWrap.gravity = Gravity.CENTER
        clockWrap.background = Ui.glass(this, 26, 0x2A)
        Ui.autoGap(clockWrap, this, 2)
        clockWrap.setPadding(
            Ui.dp(this, 24f), Ui.dp(this, 14f), Ui.dp(this, 24f), Ui.dp(this, 14f)
        )
        val clock = Ui.tv(this, nowHhmmSs(), 46f, Color.WHITE, false)
        clock.gravity = Gravity.CENTER
        clock.letterSpacing = 0.06f
        try {
            clock.setShadowLayer(Ui.dp(this, 8f).toFloat(), 0f, Ui.dp(this, 2f).toFloat(), 0x8A000000.toInt())
        } catch (_: Exception) {
        }
        val dateTv = Ui.tv(this, todayCn(), 12f, 0xE6FFFFFF.toInt(), false)
        dateTv.gravity = Gravity.CENTER
        clockWrap.addView(clock)
        clockWrap.addView(dateTv)
        val clp2 = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        )
        clp2.gravity = Gravity.TOP
        clp2.topMargin = Ui.dp(this, 54f)
        clp2.leftMargin = Ui.dp(this, 30f)
        clp2.rightMargin = Ui.dp(this, 30f)
        clockWrap.layoutParams = clp2
        clockText = clock

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        val pad = Ui.dp(this, 24f)
        col.setPadding(pad, pad, pad, Ui.dp(this, 48f))
        col.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        )

        val title = Ui.tv(
            this, when (mode) {
                MODE_SLEEP -> "晚安 🌙"
                MODE_PLAIN -> if (timed) "手机已锁" else "该睡觉了 🌙"
                else -> "到点了 🌙"
            }, 30f, Color.WHITE, true
        )
        title.gravity = Gravity.CENTER
        val sub = Ui.tv(
            this, when (mode) {
                MODE_SLEEP -> "好好睡，明早 ${Store.wakeHhmm()} 起"
                MODE_PLAIN -> if (timed) "已锁 $lockMinutes 分钟" else "现在睡 · 明早 ${Store.wakeHhmm()} 起"
                else -> "把手机放下，去睡吧"
            }, 15f, 0xFFE7E7EC.toInt()
        )
        sub.gravity = Gravity.CENTER
        val info = Ui.tv(this, "", 14f, 0xFFBFBFC6.toInt())
        info.gravity = Gravity.CENTER
        infoText = info

        col.addView(title)
        col.addView(Ui.space(this, 6))
        col.addView(sub)
        col.addView(Ui.space(this, 6))
        col.addView(info)
        col.addView(Ui.space(this, 22))

        // ------- 按钮区 -------
        if (mode == MODE_ASK) {
            val bSleep = Ui.btn(this, "我要睡觉了")
            Ui.decorate(bSleep, this, R.drawable.ic_moon, Color.WHITE)
            bSleep.setOnClickListener { goSleep() }
            col.addView(bSleep)
            val bSnooze = Ui.btn(this, "晚 ${Store.snoozeMin()} 分钟再睡觉", filled = false)
            Ui.decorate(bSnooze, this, R.drawable.ic_timer, Ui.TXT)
            bSnooze.setOnClickListener { snooze() }
            val slp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            slp.topMargin = Ui.dp(this, 10f)
            bSnooze.layoutParams = slp
            col.addView(bSnooze)
        } else if (mode == MODE_SLEEP) {
            val bMorning = Ui.btn(this, "早上好 ☀️", filled = true)
            bMorning.setOnClickListener { morning() }
            morningBtn = bMorning
            col.addView(bMorning)

            val hint = Ui.tv(
                this,
                "🌙 睡吧 —— ${Store.earlyHhmm()} 之后「早上好」才会出现",
                14f, 0xFFE7E7EC.toInt()
            )
            hint.gravity = Gravity.CENTER
            morningHint = hint
            col.addView(hint)

            val mWait = Ui.tv(this, "早上好 ${Store.earlyHhmm()} 之后才会出现（现在先睡）", 13f, 0xFFBFBFC6.toInt())
            mWait.gravity = Gravity.CENTER
            morningWait = mWait
            col.addView(mWait)

            applyMorningState(canWake())

            val rowS = LinearLayout(this)
            rowS.orientation = LinearLayout.HORIZONTAL
            rowS.gravity = Gravity.CENTER_VERTICAL
            val bKeep = Ui.btn(this, "继续睡觉", filled = false, small = true)
            Ui.decorate(bKeep, this, R.drawable.ic_moon, Ui.TXT)
            bKeep.setOnClickListener { continueSleep() }
            val bPlay = Ui.btn(this, "再玩 ${Store.snoozeMin()} 分钟", filled = false, small = true)
            Ui.decorate(bPlay, this, R.drawable.ic_timer, Ui.TXT)
            bPlay.setOnClickListener { playMore() }
            for (b in listOf(bKeep, bPlay)) {
                val lpB = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                lpB.topMargin = Ui.dp(this, 10f)
                lpB.rightMargin = Ui.dp(this, 8f)
                b.layoutParams = lpB
                rowS.addView(b)
            }
            col.addView(rowS)
        } else {
            val bStop = Ui.btn(this, "我要睡觉了", filled = false)
            bStop.setOnClickListener { goSleep() }
            col.addView(bStop)
        }

        // ------- 长按解锁 -------
        val tip = Ui.tv(this, "长按这里 ${Store.holdSec()} 秒 = 提前解锁（记一次破戒）", 14f, Color.WHITE, true)
        tip.gravity = Gravity.CENTER
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 100
        bar.progress = 0
        bar.progressDrawable = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Ui.RED_DEEP, Ui.RED)
        ).apply { cornerRadius = Ui.dp(this@LockService, 6f).toFloat() }
        val blp = LinearLayout.LayoutParams(Ui.dp(this, 250f), Ui.dp(this, 8f))
        blp.topMargin = Ui.dp(this, 14f)
        bar.layoutParams = blp
        progress = bar
        val ht = Ui.tv(this, "", 12f, 0xFFBFBFC6.toInt())
        ht.gravity = Gravity.CENTER
        holdText = ht
        val stat = Ui.tv(
            this, "连续锁机 ${Store.lockStreak()} 天 · 破戒 ${Store.breaks()} 次", 12f, 0xFF8E8E93.toInt()
        )
        stat.gravity = Gravity.CENTER

        val holdArea = LinearLayout(this)
        holdArea.orientation = LinearLayout.VERTICAL
        holdArea.background = Ui.glass(this, 20, 0x1C)
        holdArea.setPadding(
            Ui.dp(this, 16f), Ui.dp(this, 14f), Ui.dp(this, 16f), Ui.dp(this, 14f)
        )
        Ui.autoGap(holdArea, this, 8)
        holdArea.addView(tip)
        holdArea.addView(bar)
        holdArea.addView(ht)
        val tlp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        tlp.topMargin = Ui.dp(this, 22f)
        holdArea.layoutParams = tlp
        col.addView(holdArea)
        col.addView(Ui.space(this, 6))
        col.addView(stat)

        // v2.13 起不再自动熄屏 → 提示你自己锁（下拉控制中心「一键锁屏」）
        if (mode != MODE_ASK) {
            val h2 = Ui.tv(
                this, "自己锁屏：下拉控制中心 →「一键锁屏」", 12f, 0xFFBFBFC6.toInt()
            )
            h2.gravity = Gravity.CENTER
            h2.setPadding(0, Ui.dp(this, 12f), 0, 0)
            col.addView(h2)
        }

        if (Store.flag("sos")) {
            val sos = Ui.tv(this, "紧急：打电话", 13f, 0xFF9EAABF.toInt())
            sos.gravity = Gravity.CENTER
            sos.setPadding(0, Ui.dp(this, 12f), 0, 0)
            sos.setOnClickListener { emergency() }
            col.addView(sos)
        }

        root.addView(iv)
        root.addView(scrim)
        root.addView(clockWrap)
        root.addView(col)
        overlayRoot = root
        overlayImg = iv
        contentCol = col

        // ★ v2.13：长按只在「长按区」生效 —— 背景随便点不会误触发破戒
        holdArea.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    holdStart = System.currentTimeMillis()
                    h.removeCallbacks(holdTick)
                    h.post(holdTick)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    h.removeCallbacks(holdTick)
                    bar.progress = 0
                    ht.text = ""
                    // 黑屏兜底时，轻点一下把界面点亮回来
                    if (blackMode && System.currentTimeMillis() - holdStart < 700) disableBlackMode()
                    true
                }
                else -> true
            }
        }

        try {
            w.addView(root, lp)
            overlay = root
            overlayLp = lp
        } catch (_: Exception) {
            overlay = null
        }
    }

    /** 紧急：临时收起锁屏去拨号，1 分钟后自动恢复 */
    private fun emergency() {
        removeOverlay()
        try {
            startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
        h.postDelayed({
            if (!finished && Settings.canDrawOverlays(this)) rebuildOverlay()
        }, 60_000L)
    }

    /** 睡眠模式里点「继续睡觉」：只把界面留着，不动计时、不记破戒、也不自动熄屏 */
    private fun continueSleep() {
        rebuildOverlay()
        infoText?.text = "继续睡吧 🌙 睡眠计时在走\n自己锁屏：下拉控制中心点「一键锁屏」"
    }

    /** 睡眠模式里点「再玩 N 分钟」：收起锁屏 N 分钟（睡眠计时保留），到点自动回到睡眠界面 */
    private fun playMore() {
        Alarms.scheduleSnooze(this, Store.snoozeMin())
        Notify.show(
            this, 91,
            Notify.build(
                this, Notify.CH_LOCK, "好，给你 ${Store.snoozeMin()} 分钟",
                "到点自动回来锁屏（这次不影响今晚的睡眠记录）"
            )
        )
        removeOverlay()
        finished = true
        h.removeCallbacks(tick)
        stopSelf()
    }

    /** 「我要睡觉了」→ 记录入睡时间 + 熄屏 + 进入睡眠模式 */
    private fun goSleep() {
        Store.setSleepStart(System.currentTimeMillis())
        Store.setLastAlive(System.currentTimeMillis())
        aliveAt = System.currentTimeMillis()
        mode = MODE_SLEEP
        Alarms.cancelSnooze(this)
        Alarms.scheduleWake(this)
        Alarms.scheduleNightWatch(this)
        rebuildOverlay()
        Notify.show(this, NOTI_ID, buildNoti())
        infoText?.text = "晚安 🌙 睡眠计时已经开始\n自己锁屏：下拉控制中心点「一键锁屏」"
        h.post(tick)
    }

    /** 「晚 N 分钟再睡觉」 */
    private fun snooze() {
        Alarms.scheduleSnooze(this, Store.snoozeMin())
        Notify.show(
            this, 91,
            Notify.build(
                this, Notify.CH_LOCK, "好，晚 ${Store.snoozeMin()} 分钟再来",
                "到点还会提醒你睡觉（别刷太久）"
            )
        )
        removeOverlay()
        finished = true
        h.removeCallbacks(tick)
        stopSelf()
    }

    /** 「早上好」→ 结束这一觉并记录时长 */
    private fun morning() {
        val start = Store.sleepStart()
        val end = System.currentTimeMillis()
        if (start > 0) {
            Store.addSleep(start, end, false)
        }
        Store.setSleepStart(0L)
        // 这一觉结束了：今晚锁机时刻之前不再自动锁回来
        Store.setMorningMs(end)
        Alarms.cancelNightWatch(this)
        restoreTimeoutNow()
        disableBlackMode()
        val mins = ((end - start) / 60000L).toInt()
        Notify.show(
            this, 90,
            Notify.build(
                this, Notify.CH_LOCK, "早上好 ☀️",
                if (mins > 0) "这一觉睡了 %d 小时 %d 分".format(mins / 60, mins % 60) else "起床啦",
                ongoing = false
            )
        )
        finished = true
        removeOverlay()
        h.removeCallbacks(tick)
        stopSelf()
    }

    private fun vibe(ms: Long) {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 26)
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            else
                @Suppress("DEPRECATION") v.vibrate(ms)
        } catch (_: Exception) {
        }
    }

    /**
     * 「到点了」询问屏过时了 → 自己收起。
     * 顺手记下「这一觉结束」，免得刚收起来又被别的入口锁回来。
     */
    private fun dismissAsk() {
        Store.setMorningMs(System.currentTimeMillis())
        autoUnlock()
    }

    private fun autoUnlock() {
        if (finished) return
        finished = true
        h.removeCallbacks(tick)
        h.removeCallbacks(holdTick)
        removeOverlay()
        // 还在睡（另有睡眠计时在跑）就不撤看门狗
        if (Store.sleepStart() <= 0L) Alarms.cancelNightWatch(this)
        Notify.show(
            this, 96,
            Notify.build(
                this, Notify.CH_LOCK, "已自动解锁 ✅",
                when {
                    timed -> "锁机 $lockMinutes 分钟完成"
                    mode == MODE_ASK -> "到起床时间了，没开始睡就不锁了"
                    else -> "到起床时间了：洗脸 → 保湿 → 防晒"
                }, ongoing = false
            )
        )
        stopSelf()
    }

    /** 长按提前解锁 */
    private fun breakOut() {
        if (finished) return
        finished = true
        h.removeCallbacks(tick)
        h.removeCallbacks(holdTick)
        vibe(120)
        removeOverlay()
        Store.bumpBreaks()
        restoreTimeoutNow()
        disableBlackMode()
        // 如果是在睡眠中破戒，把这一觉也记下来（标记：提前解锁）
        val start = Store.sleepStart()
        if (mode == MODE_SLEEP && start > 0) {
            Store.addSleep(start, System.currentTimeMillis(), true)
            Store.setSleepStart(0L)
        }
        // 已经手动解锁了 = 今晚结束了 → 到今晚锁机时刻之前不许再锁回来（否则长按就白按了）
        Store.setMorningMs(System.currentTimeMillis())
        Alarms.cancelNightWatch(this)
        Notify.show(
            this, 96,
            Notify.build(
                this, Notify.CH_LOCK, "提前解锁了（记一次破戒）",
                "破戒共 ${Store.breaks()} 次 —— 下次把手机放远一点", ongoing = false
            )
        )
        stopSelf()
    }

    private fun removeOverlay() {
        val o = overlay ?: return
        try {
            wm?.removeView(o)
        } catch (_: Exception) {
        }
        overlay = null
    }

    override fun onDestroy() {
        running = false
        h.removeCallbacks(tick)
        h.removeCallbacks(holdTick)
        blackMode = false
        // 服务没了但这一觉还在睡 → 看门狗留着（15 分钟后把睡眠界面铺回来）
        if (Store.sleepStart() <= 0L) Alarms.cancelNightWatch(this)
        try {
            screenOnReceiver?.let { unregisterReceiver(it) }
        } catch (_: Exception) {
        }
        screenOnReceiver = null
        restoreTimeoutNow()
        removeOverlay()
        super.onDestroy()
    }
}
