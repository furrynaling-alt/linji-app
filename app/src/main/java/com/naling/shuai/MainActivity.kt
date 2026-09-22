package com.naling.shuai

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var content: FrameLayout
    private lateinit var nav: LinearLayout
    private lateinit var countdownTv: TextView
    private lateinit var heroCountTv: TextView
    private val tabNames = listOf("首页", "番茄", "便签", "用药", "设置", "工资", "记账", "统计")
    private val tabRes = listOf(
        R.drawable.ic_today, R.drawable.ic_timer, R.drawable.ic_note,
        R.drawable.ic_pill, R.drawable.ic_settings, R.drawable.ic_wage,
        R.drawable.ic_book, R.drawable.ic_chart
    )
    private class NavItem(val id: Int, val box: LinearLayout, val iv: ImageView, val tv: TextView)
    private val navItems = mutableListOf<NavItem>()
    private var tabs = listOf(0, 1, 2, 3, 5, 6, 7, 4)

    /** 记住每个页面的滚动位置（不然操作一下就被弹回顶部） */
    private val tabScroll = mutableMapOf<Int, Int>()

    private fun curScrollY(): Int {
        if (content.childCount == 0) return 0
        return (content.getChildAt(0) as? ScrollView)?.scrollY ?: 0
    }

    private fun enabledTabs(): List<Int> {
        // 2026-09-21 纳棂定（棂记 v2.16）：底部固定 4 个入口 —— 首页 / 便签 / 工资 / 设置
        // 其余功能（番茄钟·用药提醒·记账·统计·今日概览）从「设置 → 更多功能」进，不再占导航
        return listOf(0, 2, 5, 4)
    }
    private var current = 0
    private val h = Handler(Looper.getMainLooper())
    private var pomoTimeTv: TextView? = null
    private var pomoBar: ProgressBar? = null
    private var pomoPhaseTv: TextView? = null

    private val uiTick = object : Runnable {
        override fun run() {
            updateCountdown()
            pomoTimeTv?.text = PomodoroService.mmss(PomodoroService.remainSec)
            pomoPhaseTv?.text = PomodoroService.phaseName(PomodoroService.phase) +
                    (if (PomodoroService.paused) " · 已暂停" else "") +
                    " · 今日 ${Store.pomoDoneToday()} 个"
            val t = PomodoroService.totalSec
            pomoBar?.progress = if (t > 0) (((t - PomodoroService.remainSec) * 100) / t) else 0
            h.postDelayed(this, 1000)
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        Store.init(this)
        Store.syncHabitTimeNames()
        Notify.channels(this)
        askNotif()
        buildRoot()
        show(intent?.getIntExtra("page", 0) ?: 0)
        // ★ v2.15 半夜进程被系统杀了 → 进 App 先把这一觉补记掉（且不再假锁一回、不记 0 分钟）
        val rec = Store.recoverStaleSleep()
        if (rec >= 0) {
            LockService.stop(this)
            toast("这一觉补记好了 ☀️ 睡了 %d 小时 %d 分".format(rec / 60, rec % 60))
        }
        Alarms.scheduleAll(this)
        if (rec < 0 && LockService.shouldLockNow()) LockService.start(this)
    }

    override fun onResume() {
        super.onResume()
        h.removeCallbacks(uiTick)
        h.post(uiTick)
        show(current)
    }

    override fun onPause() {
        super.onPause()
        h.removeCallbacks(uiTick)
    }

    private fun askNotif() {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
            } catch (_: Exception) {
            }
        }
    }

    private fun updateCountdown() {
        val mins = Store.minutesUntil(Store.lockH(), Store.lockM())
        val s = if (LockService.shouldLockNow()) "🌙 现在是睡眠时间"
        else "距 %s 锁机还有 %d 小时 %d 分".format(Store.lockHhmm(), mins / 60, mins % 60)
        if (::countdownTv.isInitialized) countdownTv.text = s
        if (::heroCountTv.isInitialized) heroCountTv.text = s
    }

    // ---------------- 外壳：iOS 风格 + 药丸导航 ----------------
    private fun buildRoot() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Ui.BG)

        content = FrameLayout(this)
        val clp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        )
        clp.bottomMargin = Ui.dp(this, 92f)
        content.layoutParams = clp
        root.addView(content)

        nav = LinearLayout(this)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.gravity = Gravity.CENTER_VERTICAL
        nav.background = Ui.roundStroke(0xF7FFFFFF.toInt(), Ui.LINE, 28, this)
        nav.elevation = Ui.dp(this, 8f).toFloat()
        nav.setPadding(Ui.dp(this, 8f), Ui.dp(this, 7f), Ui.dp(this, 8f), Ui.dp(this, 7f))
        val nlp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        )
        nlp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        nlp.bottomMargin = Ui.dp(this, 20f)
        nav.layoutParams = nlp

        rootView = root
        root.addView(nav)
        setContentView(root)

        // v2.21：整页背景（默认雪山，可在首页「调整」或长按卡片里换）+ 状态栏透明
        applyHomeBg()
        try {
            val lp = content.layoutParams as FrameLayout.LayoutParams
            lp.topMargin = statusBarHeight()
            content.layoutParams = lp
            window.statusBarColor = Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        } catch (_: Exception) {
        }

        rebuildNav()
        // v2.17：App 桥（服务器 AI 控制/监督）—— 默认关，开发者模式里开
        if (Bridge.enabled(this)) {
            Bridge.start(this)
            Bridge.syncNow(this)          // 打开 App 就先同步一次，不用手动点
        }
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    Bridge.syncNow(this@MainActivity)
                }
            })
        } catch (_: Exception) {
        }
    }

    private fun rebuildNav() {
        tabs = enabledTabs()
        if (!tabs.contains(current)) current = 0
        nav.removeAllViews()
        navItems.clear()
        for (id in tabs) {
            val tiny = tabs.size >= 8
            val small = tabs.size >= 6
            val padL = if (tiny) 5f else if (small) 8f else 12f
            val padR = if (tiny) 5f else if (small) 10f else 14f
            val box = LinearLayout(this)
            box.orientation = LinearLayout.HORIZONTAL
            box.gravity = Gravity.CENTER
            box.setPadding(Ui.dp(this, padL), Ui.dp(this, 9f), Ui.dp(this, padR), Ui.dp(this, 9f))
            (box.layoutParams as? LinearLayout.LayoutParams) ?: run {
                box.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            (box.layoutParams as LinearLayout.LayoutParams).marginStart = Ui.dp(this, 4f)
            val iv = Ui.icon(this, tabRes[id], if (tiny) 17 else if (small) 20 else 22, Ui.SUB)
            val tv = Ui.tv(this, tabNames[id], if (tiny) 11f else if (small) 13f else 14f, Color.WHITE, true)
            tv.setPadding(Ui.dp(this, 6f), 0, 0, 0)
            box.addView(iv)
            box.addView(tv)
            box.isClickable = true
            box.setOnClickListener {
                current = id
                show(id)
            }
            nav.addView(box)
            navItems.add(NavItem(id, box, iv, tv))
        }
        paintNav()
    }

    private fun paintNav() {
        val tiny = navItems.size >= 8
        for (item in navItems) {
            val active = item.id == current
            item.tv.visibility = if (active) View.VISIBLE else View.GONE
            item.iv.setColorFilter(if (active) Color.WHITE else Ui.SUB)
            // 内边距保持不变（切换选中不再改 padding，避免相邻 tab 被挤压/粘连）
            item.box.setPadding(
                Ui.dp(this, if (tiny) 8f else 10f), Ui.dp(this, 9f),
                Ui.dp(this, if (tiny) 8f else 10f), Ui.dp(this, 9f)
            )
            item.box.background = if (active)
                Ui.ripple(this, 22, Ui.grad(Ui.RED_DEEP, Ui.RED, 22, this), 0x40FFFFFF)
            else
                Ui.ripple(this, 22, null, 0x1A000000)
        }
    }

    private fun show(i: Int) {
        if (content.childCount > 0) tabScroll[current] = curScrollY()
        if (!tabs.contains(i)) rebuildNav()
        current = i
        paintNav()
        pomoTimeTv = null
        pomoBar = null
        pomoPhaseTv = null
        content.removeAllViews()
        var v = try {
            when (i) {
                0 -> Cards.build(this)
                1 -> screenPomo()
                2 -> screenNotes()
                3 -> screenMeds()
                5 -> screenWage()
                6 -> screenBook()
                7 -> screenStat()
                8 -> screenToday()
                9 -> screenPlugins()
                else -> screenSettings()
            }
        } catch (e: Throwable) {
            crashView(i, e)
        }
        if (!enabledTabs().contains(i)) v = wrapWithBack(i, v)
        content.addView(v)
        val keep = tabScroll[i] ?: 0
        if (keep > 0) v.post { (v as? ScrollView)?.scrollTo(0, keep) }
        v.alpha = 0f
        v.translationY = Ui.dp(this, 10f).toFloat()
        v.animate().alpha(1f).translationY(0f).setDuration(190).setInterpolator(
            android.view.animation.DecelerateInterpolator()
        ).start()
        updateCountdown()
    }

    private fun page(title: String): Pair<ScrollView, LinearLayout> {
        foldMode = false         // 只有设置页折叠卡片（见 screenSettings）
        val s = ScrollView(this)
        s.isFillViewport = true
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(Ui.dp(this, 16f), Ui.dp(this, 26f), Ui.dp(this, 16f), Ui.dp(this, 24f))
        col.addView(Ui.bigTitle(this, title))
        s.addView(col)
        return Pair(s, col)
    }

    private fun edit(hint: String, input: Int = InputType.TYPE_CLASS_TEXT): EditText {
        val e = EditText(this)
        e.hint = hint
        e.inputType = input
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        e.setTextColor(Ui.TXT)
        e.setHintTextColor(Ui.SUB)
        e.background = Ui.round(0xFFF2F2F7.toInt(), 12, this)
        e.setPadding(Ui.dp(this, 14f), Ui.dp(this, 13f), Ui.dp(this, 14f), Ui.dp(this, 13f))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = Ui.dp(this, 10f)
        e.layoutParams = lp
        return e
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---- 熄屏录像 ----
    private var pendingRec = false

    private fun startRecording() {
        if (Build.VERSION.SDK_INT >= 23) {
            val need = mutableListOf<String>()
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.CAMERA)
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.RECORD_AUDIO)
            if (need.isNotEmpty()) {
                pendingRec = true
                requestPermissions(need.toTypedArray(), 7)
                return
            }
        }
        RecorderService.start(this, Store.recFront(), Store.recMinutes())
        toast("开始录像了，现在可以关屏")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 8) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                RecorderService.start(this, Store.recFront(), Store.recMinutes(), true)
                toast("开始录音了，可以关屏")
            } else toast("需要麦克风权限")
            return
        }
        if (requestCode == 7 && pendingRec) {
            pendingRec = false
            val ok = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (ok) {
                RecorderService.start(this, Store.recFront(), Store.recMinutes())
                toast("开始录像了，现在可以关屏")
            } else {
                toast("需要「相机」和「麦克风」权限才能录")
            }
        }
    }

    /** v2.17：导出改为加密 .linji（AES-256-GCM + 口令） */
    private fun exportBackup() {
        askPassword("设置导出密码（记牢，忘了就打不开）") { pwd ->
            if (pwd.isNullOrEmpty()) {
                toast("必须设一个密码才能加密导出")
                return@askPassword
            }
            try {
                val json = Store.exportJson()
                val bytes = Linji.seal(json, pwd)
                val name = "棂记-备份-" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.CHINA).format(Date()) + ".linji"
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                        put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                        put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                            android.os.Environment.DIRECTORY_DOWNLOADS + "/棂记")
                    }
                    val uri = contentResolver.insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        toast("已加密导出「下载 / 棂记」（.linji，没密码打不开）")
                        return@askPassword
                    }
                }
                val dir = java.io.File(getExternalFilesDir(null), "backup")
                if (!dir.exists()) dir.mkdirs()
                java.io.File(dir, name).writeBytes(bytes)
                toast("已加密导出到 App 目录（这台系统较老）")
            } catch (e: Exception) {
                toast("导出失败：" + (e.message ?: ""))
            }
        }
    }

    /** 统一的「输入口令」弹窗（导出/导入共用） */
    private fun askPassword(title: String, cb: (String?) -> Unit) {
        val et = EditText(this)
        et.hint = "口令"
        et.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(et)
            .setPositiveButton("确定") { _, _ -> cb(et.text.toString()) }
            .setNegativeButton("取消") { _, _ -> cb(null) }
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_IMG && resultCode == RESULT_OK) {
            // v2.16：卡片背景图选好了（Cards.pickImage 注册的回调）
            data?.data?.let { u -> pickCb?.invoke(u) }
            return
        }
        if (requestCode == 21 && resultCode == RESULT_OK && data?.data != null) {
            try {
                val uri = data.data!!
                val fname = uri.lastPathSegment ?: ""
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes == null) {
                    toast("读不出文件")
                    return
                }
                val isLinji = Linji.isLinji(fname) ||
                        (bytes.size > 6 && String(bytes, 0, 6, Charsets.US_ASCII) == "LINJI1")
                if (isLinji) {
                    askPassword("输入导出时设置的口令（.linji 加密备份）") { pwd ->
                        if (pwd.isNullOrEmpty()) return@askPassword
                        try {
                            val txt = Linji.open(bytes, pwd)
                            if (Store.importJson(txt)) {
                                toast("解密导入成功 ✅")
                                Alarms.scheduleAll(this)
                                show(4)
                            } else {
                                toast("解密了，但内容不是本 App 的备份")
                            }
                        } catch (e: Exception) {
                            toast("口令不对或文件损坏（" + (e.message ?: "") + "）")
                        }
                    }
                } else {
                    val txt = String(bytes, Charsets.UTF_8)
                    if (Store.importJson(txt)) {
                        toast("导入成功（旧版 json）")
                        Alarms.scheduleAll(this)
                        show(4)
                    } else toast("文件读不出来（不是本 App 导出的？）")
                }
            } catch (e: Exception) {
                toast("导入失败：" + (e.message ?: ""))
            }
        }
    }

    /** 0=未运行/未装 1=已装未授权 2=已授权 */
    private fun shizukuState(): Int {
        return try {
            if (ShizukuShell.running()) {
                if (ShizukuShell.granted()) 2 else 1
            } else {
                try {
                    packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
                    1
                } catch (_: Exception) {
                    0
                }
            }
        } catch (_: Throwable) {
            0
        }
    }

    private fun requestShizuku() {
        try {
            Shizuku.addRequestPermissionResultListener { _, grantResult ->
                toast(if (grantResult == PackageManager.PERMISSION_GRANTED) "Shizuku 已授权 ✅" else "授权被拒绝")
                show(4)
            }
            Shizuku.requestPermission(99)
        } catch (e: Throwable) {
            toast("Shizuku 没在运行：先在 Shizuku App 里启动服务（无线调试方式）")
        }
    }

    private fun openShizuku() {
        try {
            val i = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (i != null) startActivity(i) else toast("先装 Shizuku（我发你那个 APK）")
        } catch (_: Exception) {
            toast("先装 Shizuku（我发你那个 APK）")
        }
    }

    private fun testScreenOff() {
        try {
            if (ShizukuShell.granted()) {
                ShizukuShell.screenOff()
                toast("已执行：按电源键（屏幕应该马上灭）")
            } else {
                toast("Shizuku 还没授权（先点「请求授权」）")
            }
        } catch (e: Throwable) {
            toast("执行失败：" + e.javaClass.simpleName)
        }
    }

    private fun copyText(s: String) {
        try {
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("log", s))
            toast("已复制")
        } catch (_: Exception) {
            toast("复制失败")
        }
    }

    /** 熄屏自检：把"为什么没熄屏"变成可看的结论 */
    private fun lockSelfCheck() {
        val sb = StringBuilder()
        val canWrite = try {
            android.provider.Settings.System.canWrite(this)
        } catch (_: Exception) {
            false
        }
        sb.append("修改系统设置权限：").append(if (canWrite) "已开 ✅" else "未开 ❌（点上面按钮开）").append("\n")
        val cur = try {
            android.provider.Settings.System.getInt(
                contentResolver, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, -1
            )
        } catch (_: Exception) {
            -1
        }
        sb.append("当前屏幕超时：").append(cur).append(" ms\n")
        if (canWrite) {
            try {
                android.provider.Settings.System.putInt(
                    contentResolver, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, 4000
                )
                val back = android.provider.Settings.System.getInt(
                    contentResolver, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, -1
                )
                sb.append("试写 4000 → 读回 ").append(back)
                    .append(if (back == 4000) " ✅ 系统允许改写（熄屏能生效）" else " ❌ 被系统改回去了")
                    .append("\n")
                if (cur > 0) android.provider.Settings.System.putInt(
                    contentResolver, android.provider.Settings.System.SCREEN_OFF_TIMEOUT, cur
                )
            } catch (e: Exception) {
                sb.append("试写失败：").append(e.javaClass.simpleName).append(" ").append(e.message ?: "").append("\n")
            }
        }
        try {
            val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            sb.append("屏幕现在：").append(if (pm.isInteractive) "亮着" else "已灭").append("\n")
        } catch (_: Exception) {
        }
        sb.append("机型：").append(android.os.Build.MANUFACTURER).append(" ").append(android.os.Build.MODEL)
            .append(" / Android ").append(android.os.Build.VERSION.SDK_INT).append("\n")
        sb.append("\n读回 4000 ✅ 但屏幕还亮 → 去关掉：\n")
        sb.append("· 开发者选项 → 充电时保持唤醒 / 不锁定屏幕\n")
        sb.append("· 显示 → 智能保持亮屏 / 注视不熄屏\n")
        sb.append("· 快捷与辅助 → 双击熄屏（电源键坏了就靠它）")
        Store.setLockLog(sb.toString())
        AlertDialog.Builder(this).setTitle("熄屏自检").setMessage(sb.toString())
            .setPositiveButton("知道了") { _, _ -> show(4) }
            .setNeutralButton("复制") { _, _ -> copyText(sb.toString()) }
            .show()
    }

    private fun isAdmin(): Boolean = try {
        val dpm = getSystemService(android.content.Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        dpm.isAdminActive(android.content.ComponentName(this, AdminReceiver::class.java))
    } catch (_: Exception) {
        false
    }

    /** 手动「早上好」：结束当前这一觉并记录时长 */
    private fun manualMorning() {
        val start = Store.sleepStart()
        if (start <= 0L) {
            toast("现在没有在计时的睡眠（先在锁机界面点「我要睡觉了」）")
            return
        }
        if (!Store.canWakeNow()) {
            val m = Store.minutesToWake()
            toast("最早 ${Store.earlyHhmm()} 才能起（还有 %d 小时 %d 分）".format(m / 60, m % 60))
            return
        }
        val end = System.currentTimeMillis()
        Store.addSleep(start, end, false)
        Store.setSleepStart(0L)
        // 这一觉结束了：今晚锁机时刻之前不再自动锁回来（修 bug：按完「早上好」又被锁上）
        Store.setMorningMs(end)
        LockService.stop(this)
        val mins = ((end - start) / 60000L).toInt()
        toast("早上好 ☀️ 这一觉睡了 %d 小时 %d 分".format(mins / 60, mins % 60))
        show(0)
    }

    private fun playVideo(uri: android.net.Uri, audio: Boolean = false) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, if (audio) "audio/mp4" else "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (_: Exception) {
            toast("本机没有能播放的播放器，去相册看吧")
        }
    }

    /** 每行右上角可见的删除按钮（不用长按也删得掉） */
    private fun xBtn(onClick: () -> Unit): View {
        val box = FrameLayout(this)
        box.setPadding(Ui.dp(this, 8f), Ui.dp(this, 8f), Ui.dp(this, 8f), Ui.dp(this, 8f))
        val iv = Ui.icon(this, R.drawable.ic_close, 17, 0xFFB0B0B6.toInt())
        box.addView(iv)
        box.isClickable = true
        box.background = Ui.ripple(this, 20, null, 0x1A000000)
        box.setOnClickListener { onClick() }
        return box
    }

    private fun editRow(title: String, hints: List<String>, onOk: (List<String>) -> Unit) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(Ui.dp(this, 18f), 0, Ui.dp(this, 18f), 0)
        val fields = mutableListOf<EditText>()
        for (h in hints) {
            val e = if (h.contains("天数") || h.contains("分钟") || h.contains("秒"))
                edit(h, InputType.TYPE_CLASS_NUMBER) else edit(h)
            fields.add(e)
            box.addView(e)
        }
        AlertDialog.Builder(this).setTitle(title).setView(box)
            .setPositiveButton("确定") { _, _ -> onOk(fields.map { it.text.toString().trim() }) }
            .setNegativeButton("取消", null).show()
    }

    private fun habitMenu(hb: Store.Habit) {
        val items = arrayOf("重命名", "删除这条习惯", "取消")
        AlertDialog.Builder(this).setTitle(hb.name).setItems(items) { _, which ->
            when (which) {
                0 -> {
                    val box = LinearLayout(this)
                    box.orientation = LinearLayout.VERTICAL
                    box.setPadding(Ui.dp(this, 18f), 0, Ui.dp(this, 18f), 0)
                    val e = edit("习惯名")
                    e.setText(hb.name)
                    box.addView(e)
                    AlertDialog.Builder(this).setTitle("重命名").setView(box)
                        .setPositiveButton("保存") { _, _ ->
                            if (e.text.toString().trim().isNotEmpty()) {
                                Store.renameHabit(hb.id, e.text.toString().trim()); show(0)
                            }
                        }
                        .setNegativeButton("取消", null).show()
                }
                1 -> Store.removeHabit(hb.id).also { show(0) }
            }
        }.show()
    }

    /** 从卡片进的页面顶部加「← 返回首页」 */
    private fun wrapWithBack(pageId: Int, v: View): View {
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        val bar = Ui.row(this)
        bar.setPadding(Ui.dp(this, 14f), Ui.dp(this, 10f), Ui.dp(this, 14f), Ui.dp(this, 2f))
        val bt = Ui.btn(this, "← 返回首页", filled = false, small = true)
        bt.setOnClickListener { openPage(0) }
        bar.addView(bt)
        wrap.addView(bar)
        wrap.addView(v, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return wrap
    }

    private var rootView: View? = null

    /** 整页背景：@home 默认雪山 / @fox1 @fox2 @foxhead / 本地图片路径（做预模糊 → 磨砂底） */
    private fun applyHomeBg() {
        val rv = rootView ?: return
        val tag = Store.prefs().getString("homeBg", "@home") ?: "@home"
        try {
            val src = if (tag.startsWith("@")) {
                val resId = when (tag) {
                    "@fox1" -> R.drawable.bg_fox
                    "@fox2" -> R.drawable.bg_foxsnow
                    "@foxhead" -> R.drawable.bg_fox_head
                    else -> R.drawable.bg_home
                }
                android.graphics.BitmapFactory.decodeResource(resources, resId)
            } else {
                android.graphics.BitmapFactory.decodeFile(tag)
            }
            val sw = (src.width / 12).coerceAtLeast(20)
            val sh = (src.height / 12).coerceAtLeast(20)
            val soft = android.graphics.Bitmap.createScaledBitmap(src, sw, sh, true)
            val blur = android.graphics.Bitmap.createScaledBitmap(soft, src.width, src.height, true)
            val d = android.graphics.drawable.BitmapDrawable(resources, blur)
            d.gravity = Gravity.FILL
            rv.background = d
        } catch (_: Exception) {
        }
    }

    fun reloadRootBg() = applyHomeBg()

    /** 状态栏高度（透明状态栏用） */
    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else Ui.dp(this, 26f)
    }

    /** 页面出错时不闪退，直接把错误显示出来（截屏给我就能定位） */
    private fun crashView(pageId: Int, e: Throwable): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(Ui.dp(this, 16f), Ui.dp(this, 24f), Ui.dp(this, 16f), Ui.dp(this, 16f))
        col.addView(Ui.tv(this, "这个页面出错了（截屏发给棂星）", 16f, 0xFFD81E2C.toInt(), true))
        col.addView(Ui.space(this, 10))
        val sw = ScrollView(this)
        val t = Ui.tv(this, "页面 id = $pageId\n\n${e.javaClass.name}\n" +
                (e.message ?: "") + "\n\n" +
                e.stackTrace.take(14).joinToString("\n") { "  at $it" }, 11f, Ui.SUB)
        sw.addView(t)
        col.addView(sw, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return col
    }

    // ---------------- v2.16 新增：首页卡片刷新 / 选图 / 更多功能入口 ----------------

    /** 卡片编辑后刷新首页（Cards 调用） */
    fun homeRefresh() {
        show(0)
    }

    private var pickCb: ((android.net.Uri) -> Unit)? = null
    private val REQ_PICK_IMG = 0x5101

    /** 打开系统相册选图（Cards 用来设置卡片背景）
     * ⚠️ MainActivity 是原生 Activity（不是 AppCompatActivity）→ 不能用 registerForActivityResult，
     *    只能用经典 startActivityForResult；结果在下面已有的 onActivityResult 里分发（别再加一个！） */
    fun pickImage(cb: (android.net.Uri) -> Unit) {
        pickCb = cb
        try {
            val it = Intent(Intent.ACTION_GET_CONTENT)
            it.type = "image/*"
            it.addCategory(Intent.CATEGORY_OPENABLE)
            startActivityForResult(Intent.createChooser(it, "选择背景图"), REQ_PICK_IMG)
        } catch (e: Exception) {
            pickCb = null
            Toast.makeText(this, "打不开相册：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 从「设置 → 更多功能」打开不占导航的页面（临时把它加进 tabs，点回主入口即恢复） */
    fun openPage(id: Int) {
        if (!tabs.contains(id)) {
            tabs = enabledTabs() + id
            rebuildNav()
        }
        current = id
        show(id)
    }

    private fun openExtra(id: Int) = openPage(id)

    // ---------- v2.17：开发者模式 / 检查更新 ----------
    private var verTaps = 0

    private fun sshHostRaw(c: Context) =
        Store.prefs().getString("sshHost", "23.94.214.35:16598") ?: ""

    private fun sshHost(c: Context) = sshHostRaw(c).substringBefore(":").trim()
    private fun sshPort(c: Context) =
        sshHostRaw(c).substringAfter(":", "").trim().toIntOrNull() ?: 22

    private fun sshUser(c: Context) = Store.prefs().getString("sshUser", "root") ?: "root"

    private fun aiPrompt(): String {
        val pub = SshKey.publicKey(this)
        val h = sshHost(this)
        val pt = sshPort(this)
        val usr = sshUser(this)
        return "你是我的服务器运维助手。请帮我把「手机 App 棂记 ↔ 这台服务器」配好，并把部署文件装上。" +
                "每步请给我【命令 + 预期输出】，我照贴照跑；出错就按你给的排错走。\n" +
                "\n===== 环境 =====\n" +
                "服务器：$h   端口 $pt   用户 $usr\n" +
                "手机 App 已生成密钥，公钥（整行，别换行别加引号）：\n" + pub + "\n" +
                "\n===== 仓库 =====\n" +
                "① 部署文件仓（教程 README + bridge.php + server.js + nginx-linji.conf + version.json 示例）：\n" +
                "   https://github.com/furrynaling-alt/linji\n" +
                "② App 源码 / APK 仓：\n" +
                "   https://github.com/furrynaling-alt/linji-app\n" +
                "   国内慢就用直链：https://furry.gov.naling.net/linji/linji-2.36.apk\n" +
                "\n===== 第一部分：配对（必做）=====\n" +
                "1) 确认 sshd 在跑且监听 $pt：ss -tlnp | grep $pt       预期 LISTEN 0.0.0.0:$pt\n" +
                "2) 把公钥写进 $usr 的 authorized_keys（幂等，别重复写）：\n" +
                "   mkdir -p ~/.ssh && chmod 700 ~/.ssh && (grep -q 'linji@phone' ~/.ssh/authorized_keys 2>/dev/null || echo '" + pub + "' >> ~/.ssh/authorized_keys) && chmod 600 ~/.ssh/authorized_keys\n" +
                "   预期 无报错；grep -c linji@phone ~/.ssh/authorized_keys 输出 >=1\n" +
                "3) 确认允许公钥登录：sshd -T | grep -E \"^pubkeyauthentication|^permitrootlogin\"    预期 pubkeyauthentication yes\n" +
                "4) 验证：从另一台机器 ssh -o StrictHostKeyChecking=no -p $pt $usr@$h 'echo OK'      预期 直接输出 OK，不提示密码\n" +
                "\n===== 第二部分：装部署文件（可选；想要「检查更新 / 手机↔服务器通道」才要）=====\n" +
                "5) 拉文件（二选一）：\n" +
                "   git clone https://github.com/furrynaling-alt/linji.git /opt/linji\n" +
                "   或下载 release：https://github.com/furrynaling-alt/linji/releases/latest （linji-deploy.zip）\n" +
                "6) 放进站点目录：mkdir -p /www/wwwroot/<你的站点>/linji\n" +
                "   cp /opt/linji/bridge.php /www/wwwroot/<你的站点>/linji/\n" +
                "   把 linji-x.y.apk 也放进去；再写 version.json（versionCode/versionName/note/url/md5，md5 用 md5sum 算）\n" +
                "7) nginx 站点配置里加（改完 nginx -t && systemctl reload nginx）：\n" +
                "   location ^~ /linji/ { root /www/wwwroot/<你的站点>; }\n" +
                "8) 想用桥再加 PHP 版（bridge.php 直接就能用，不用起服务）或 Node 版：\n" +
                "   cd /opt/linji && printf '%s\\n' '你的口令' > token.txt && chmod 600 token.txt && npm i -g pm2 && pm2 start server.js --name linji-bridge && pm2 save\n" +
                "   验证：curl -s https://<你的域名或IP>/linji/version.json        预期 返回 JSON\n" +
                "         curl -s -X POST https://<域名>/linji/bridge.php -H 'X-Linji-Token: 你的口令' -d '{}'    预期 {\"cmds\":[]}\n" +
                "\n===== 第三部分：让 SSH 走 443（手机在国内 + 服务器在海外时必做）=====\n" +
                "为什么：裸 IP 的高端口在国内常被丢包（表现：连上后卡住 / channel is not opened），443 端口最正常。\n" +
                "9) 装 nginx 的 stream 模块：apt-get install -y libnginx-mod-stream\n" +
                "10) 把网站从 443 挪到 8443：站点配置里 `listen 443 ssl` 全改成 `listen 8443 ssl`；并在 nginx.conf 的 http{} 里加 `port_in_redirect off;`\n" +
                "11) 在 nginx.conf 顶层（http{} 外面）加：\n" +
                "   stream {\n" +
                "       map \$ssl_preread_protocol \$linji_backend { \"\" 127.0.0.1:$pt; default 127.0.0.1:8443; }\n" +
                "       server { listen 443; listen [::]:443; proxy_pass \$linji_backend; ssl_preread on; proxy_timeout 300s; }\n" +
                "   }\n" +
                "   （含义：443 上先看首包——TLS 走网站，SSH 走 sshd；一个端口同时干两件事）\n" +
                "12) nginx -t && systemctl reload nginx\n" +
                "13) 验证：curl -I https://<你的域名或IP> 仍然 200；ssh -p 443 $usr@$h   能走到要密码/公钥那一步就说明通了\n" +
                "手机端对应：第一步的 IP 填 **$h:443**\n" +
                "\n===== 排错 =====\n" +

                "- Permission denied (publickey)：~/.ssh 权限 700、authorized_keys 600，用户必须是 $usr\n" +
                "- 连不上/超时：云安全组 + 本机防火墙放行 $pt（nftables: nft add rule inet filter input tcp dport $pt accept）\n" +
                "- 手机端对应位置：设置 → SSH 私钥连接服务器 → ①填 $h:$pt ②生成私钥 ③装公钥 ④点「配对连接」\n" +
                "做完把每步实际输出贴给我。"
    }

    private fun oneKeyCmd(): String {
        val pub = SshKey.publicKey(this)
        return "mkdir -p ~/.ssh && chmod 700 ~/.ssh && " +
                "(grep -q 'linji@phone' ~/.ssh/authorized_keys 2>/dev/null || echo '" + pub +
                "' >> ~/.ssh/authorized_keys) && chmod 600 ~/.ssh/authorized_keys && echo 装好了"
    }

    private fun runSsh(cmd: String, out: TextView) {
        val h = sshHost(this)
        if (h.isBlank()) {
            toast("先在设置里填服务器 IP")
            return
        }
        out.text = "连接中…"
        SshShell.run(this, h, sshPort(this), sshUser(this), cmd) { r ->
            runOnUiThread { out.text = r }
        }
    }

    private fun diagText(): String = "版本 ${Update.curVersionName(this)}（build ${Update.curVersionCode(this)}）\n" +
            "服务器 ${Store.serverBase()}\n" +
            "桥 ${if (Bridge.enabled(this)) "开" else "关"} · 上次同步 ${Bridge.lastSync(this)}\n" +
            "睡门禁 ${Store.earlyHhmm()} · 锁机 ${Store.lockHhmm()} → 起床 ${Store.wakeHhmm()}\n" +
            "SSH ${sshUser(this)}@${sshHost(this)}:${sshPort(this)}"

    private fun bridgeLog(): String = try {
        val f = Bridge.logFile(this)
        if (f.exists()) f.readText() else "（还没有桥日志）"
    } catch (e: Exception) {
        "读取失败：" + (e.message ?: "")
    }

    private fun devOn() = Store.prefs().getBoolean("devMode", false)

    private fun tapVersion() {
        verTaps++
        if (verTaps >= 7) {
            verTaps = 0
            val v = !devOn()
            Store.prefs().edit().putBoolean("devMode", v).apply()
            toast(if (v) "开发者模式已开启 🔧" else "开发者模式已关闭")
            show(4)
        } else if (verTaps >= 4) {
            toast("再点 ${7 - verTaps} 次进入开发者模式")
        }
    }

    private fun doCheckUpdate(manual: Boolean) {
        toast("正在检查更新…")
        Thread {
            val info = Update.check(this)
            runOnUiThread {
                if (info == null) {
                    if (manual) toast("检查失败（没网，或更新清单还没放到服务器）")
                    return@runOnUiThread
                }
                val cur = Update.curVersionCode(this)
                if (info.versionCode <= cur) {
                    if (manual) toast("已是最新版本 ${info.versionName} ✅")
                    return@runOnUiThread
                }
                AlertDialog.Builder(this)
                    .setTitle("发现新版本 ${info.versionName}")
                    .setMessage(info.note.ifBlank { "有新版本可用" })
                    .setPositiveButton("下载并安装") { _, _ -> downloadAndInstall(info) }
                    .setNegativeButton("以后再说", null)
                    .show()
            }
        }.start()
    }

    private fun downloadAndInstall(info: Update.Info) {
        toast("开始下载…")
        Thread {
            val f = Update.download(this, info) { pct ->
                runOnUiThread { if (pct % 25 == 0) toast("下载 $pct%") }
            }
            runOnUiThread {
                if (f == null) {
                    toast("下载失败")
                    return@runOnUiThread
                }
                if (!Update.md5Ok(f, info.md5)) {
                    toast("校验失败（md5 不符），已放弃安装")
                    return@runOnUiThread
                }
                Update.install(this, f)
            }
        }.start()
    }

    /** 设置页专用：卡片自动折成二级菜单（标题可点开/收起） */
    private var foldMode = false

    /** 可折叠卡片：addView 进来的内容自动进"折叠体" */
    private inner class FoldCard(ctx: Context, title: String) : LinearLayout(ctx) {
        val body: LinearLayout = LinearLayout(ctx).apply {
            orientation = VERTICAL
            visibility = VISIBLE
        }

        init {
            orientation = VERTICAL
            setBackgroundDrawable(Ui.round(Ui.CARD, 18, ctx))
            elevation = Ui.dp(ctx, 1.5f).toFloat()
            val p = Ui.dp(ctx, 16f)
            setPadding(p, Ui.dp(ctx, 10f), p, Ui.dp(ctx, 10f))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = Ui.dp(ctx, 14f) }
            val head = Ui.row(ctx)
            head.setPadding(0, Ui.dp(ctx, 8f), 0, Ui.dp(ctx, 8f))
            val tv = Ui.tv(ctx, title, 14.5f, Ui.TXT, true)
            tv.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            val arrow = Ui.tv(ctx, "▾", 13f, Ui.SUB, true)
            head.addView(tv)
            head.addView(arrow)
            head.isClickable = true
            head.setOnClickListener {
                val open = body.visibility != View.VISIBLE
                body.visibility = if (open) View.VISIBLE else View.GONE
                arrow.text = if (open) "▾" else "▸"
            }
            super.addView(head)
            super.addView(body)
        }

        override fun addView(child: View?) {
            body.addView(child)
        }

        override fun addView(child: View?, index: Int) {
            body.addView(child, index)
        }

        override fun addView(child: View?, params: ViewGroup.LayoutParams?) {
            body.addView(child, params)
        }

        override fun addView(child: View?, index: Int, params: ViewGroup.LayoutParams?) {
            body.addView(child, index, params)
        }
    }

    private fun card(title: String? = null): LinearLayout {
        val c = Ui.card(this)
        if (title != null) {
            c.addView(Ui.sectionTitle(this, title))
        }
        return c
    }

    // ---------------- 今日 ----------------
    private fun screenToday(): View {
        val (sv, col) = page("今日")

        // hero：狐狸插画 + 倒计时
        val hero = FrameLayout(this)
        val hlp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 190f)
        )
        hlp.bottomMargin = Ui.dp(this, 16f)
        hero.layoutParams = hlp
        hero.background = Ui.round(0xFF101218.toInt(), 20, this)
        hero.clipToOutline = true
        val iv = ImageView(this)
        iv.setImageDrawable(Ui.image(this, R.drawable.bg_fox_head))
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        iv.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        )
        val scrim = View(this)
        scrim.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x22000000, 0xCC000000.toInt())
        )
        scrim.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        )
        val hcol = LinearLayout(this)
        hcol.orientation = LinearLayout.VERTICAL
        hcol.gravity = Gravity.BOTTOM
        hcol.setPadding(Ui.dp(this, 18f), Ui.dp(this, 18f), Ui.dp(this, 18f), Ui.dp(this, 16f))
        heroCountTv = Ui.tv(this, "", 16f, Color.WHITE, true)
        val hsub = Ui.tv(
            this,
            "打卡 %d/%d · 番茄 %d 个 · 连续锁机 %d 天".format(
                Store.doneTodayCount(), Store.habitCount(),
                Store.pomoDoneToday(), Store.lockStreak()
            ), 12f, 0xFFD8D8DE.toInt()
        )
        hcol.addView(heroCountTv)
        hcol.addView(Ui.space(this, 3))
        hcol.addView(hsub)
        hero.addView(iv)
        hero.addView(scrim)
        hero.addView(hcol)
        col.addView(hero)

        // 锁 10 分钟
        val c0 = card()
        val lockRow = Ui.row(this)
        lockRow.addView(Ui.icon(this, R.drawable.ic_moon, 20, Ui.RED))
        val lockTitle = Ui.tv(this, "  锁机 ${Store.quickLockMin()} 分钟", 17f, Ui.TXT, true)
        lockRow.addView(lockTitle)
        c0.addView(lockRow)
        c0.addView(Ui.tv(this, "点一下立刻锁机，到点自动解锁；中途想用就长按 ${Store.holdSec()} 秒（记破戒）", 13f, Ui.SUB))
        val bLock = Ui.btn(this, "立即锁机 ${Store.quickLockMin()} 分钟")
        bLock.setOnClickListener {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                toast("先给「悬浮窗」权限（设置页里有按钮）")
            }
            LockService.start(this, Store.quickLockMin(), LockService.MODE_PLAIN)
        }
        c0.addView(bLock)
        col.addView(c0)

        // 睡眠记录
        if (Store.flag("sleepLog")) {
        val cS = card("睡眠记录")
        val lastS = Store.lastSleep()
        cS.addView(
            Ui.tv(
                this,
                if (lastS != null) "%s 睡了 %d 小时 %d 分".format(lastS.day, lastS.minutes / 60, lastS.minutes % 60)
                else "还没有记录（点「我要睡觉了」开始）",
                18f, Ui.TXT, true
            )
        )
        val avg = Store.avgSleep7()
        if (avg > 0) cS.addView(Ui.tv(this, "近 7 次平均 %d 小时 %d 分".format(avg / 60, avg % 60), 12f, Ui.SUB))
        val srow = Ui.row(this)
        srow.setPadding(0, Ui.dp(this, 12f), 0, 0)
        val bGoSleep = Ui.btn(this, "我要睡觉了")
        bGoSleep.setOnClickListener {
            if (!android.provider.Settings.canDrawOverlays(this)) toast("先给「悬浮窗」权限")
            LockService.start(this, 0, LockService.MODE_SLEEP)
            toast("熄屏睡觉，明早点「早上好」")
        }
        val bMorning = Ui.btn(this, "早上好", filled = false)
        bMorning.setOnClickListener { manualMorning() }
        for (b in listOf(bGoSleep, bMorning)) {
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.rightMargin = Ui.dp(this, 8f)
            b.layoutParams = lp
            srow.addView(b)
        }
        cS.addView(srow)
        val sessions = Store.sleeps().take(7)
        if (sessions.isNotEmpty()) {
            cS.addView(Ui.divider(this))
            cS.addView(Ui.sectionTitle(this, "最近几次（✕ 可删）"))
            for ((i3, ss) in sessions.withIndex()) {
                val row = Ui.row(this)
                row.setPadding(0, Ui.dp(this, 4f), 0, Ui.dp(this, 4f))
                val fmtS = SimpleDateFormat("MM-dd", Locale.CHINA)
                val tv2 = Ui.tv(
                    this,
                    fmtS.format(Date(ss.start)) + "   " + (ss.minutes / 60) + " 小时 " + (ss.minutes % 60) + " 分" +
                            (if (ss.broke) "   （提前解锁）" else if (ss.auto) "   （自动补记）" else ""),
                    15f, Ui.TXT
                )
                tv2.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                tv2.setPadding(0, Ui.dp(this, 8f), 0, Ui.dp(this, 8f))
                row.addView(tv2)
                row.addView(xBtn { Store.delSleep(ss.start, ss.end); show(0) })
                cS.addView(row)
                if (i3 != sessions.size - 1) cS.addView(Ui.divider(this))
            }
        }
        col.addView(cS)
        }

        // 趋势（近 7 天）
        if (Store.flag("trends")) {
        val cT = card("趋势（近 7 天）")
        val days = Store.lastDays(7)
        val labels = days.map { it.substring(5) }
        cT.addView(Ui.tv(this, "睡眠时长", 13f, Ui.SUB))
        val t1 = TrendView(this, days.map { d -> (Store.sleeps().firstOrNull { it.day == d }?.minutes ?: 0) / 60f }, labels, "小时")
        t1.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 150f))
        cT.addView(t1)
        cT.addView(Ui.tv(this, "打卡完成率", 13f, Ui.SUB))
        val t2 = TrendView(this, days.map { Store.habitRateOn(it) * 100f }, labels, "%")
        t2.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 150f))
        cT.addView(t2)
        cT.addView(Ui.tv(this, "番茄个数", 13f, Ui.SUB))
        val t3 = TrendView(this, days.map { Store.pomoOn(it).toFloat() }, labels, "个")
        t3.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 150f))
        cT.addView(t3)
        col.addView(cT)
        }

        // 打卡列表
        val c2 = card("打卡")
        val list = Store.habits()
        for ((idx, hb) in list.withIndex()) {
            val row = Ui.row(this)
            row.setPadding(0, Ui.dp(this, 12f), 0, Ui.dp(this, 12f))
            val done = hb.days.contains(Store.day())
            val mark = Ui.tv(this, if (done) "✓" else "○", 18f, if (done) Ui.RED else 0xFFC7C7CC.toInt(), true)
            mark.width = Ui.dp(this, 30f)
            mark.gravity = Gravity.CENTER
            val name = Ui.tv(this, hb.name, 16f, if (done) Ui.SUB else Ui.TXT)
            name.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            val stk = Ui.tv(this, "连续 %d 天".format(Store.streak(hb)), 12f, Ui.SUB)
            val del = xBtn { Store.removeHabit(hb.id); show(0) }
            row.addView(mark)
            row.addView(name)
            row.addView(stk)
            row.addView(del)
            row.isClickable = true
            row.background = Ui.ripple(this, 14, null, 0x14000000)
            row.setOnClickListener {
                Store.toggleHabit(hb)
                show(0)
            }
            row.setOnLongClickListener {
                habitMenu(hb)
                true
            }
            c2.addView(row)
            if (idx != list.size - 1) c2.addView(Ui.divider(this))
        }
        col.addView(c2)

        if (Store.flag("skinSet")) {
            val bSkin = Ui.btn(this, "＋ 一键加皮肤护理套餐", filled = false)
            bSkin.setOnClickListener {
                Store.addSkincareSet(); show(0); toast("早/晚护理 + 灰指甲 + 不摸脸 已加")
            }
            c2.addView(bSkin)
        }
        if (Store.flag("touchCounter")) {
            val bTouch = Ui.btn(this, "我摸脸了（今天 " + Store.touchCountToday() + " 次）", filled = false)
            bTouch.setOnClickListener {
                Store.addTouch(); show(0)
            }
            c2.addView(bTouch)
        }

        val bAdd = Ui.btn(this, "＋ 添加习惯", filled = false)
        bAdd.setOnClickListener {
            editRow("新习惯（点右上✕可删）", listOf("例如：跑步 20 分钟")) { v ->
                if (v[0].isNotEmpty()) {
                    Store.addHabit(v[0]); show(0)
                }
            }
        }
        col.addView(bAdd)

        val bRestore = Ui.btn(this, "恢复默认习惯（只有 起床 / 睡觉）", filled = false)
        bRestore.setOnClickListener {
            Store.restoreDefaultHabits(); Store.syncHabitTimeNames(); show(0); toast("已恢复默认两条")
        }
        col.addView(bRestore)

        val bSlim = Ui.btn(this, "只留 起床 / 睡觉（其他全删掉）", filled = false)
        bSlim.setOnClickListener {
            val n = Store.slimHabits()
            show(0)
            toast(if (n > 0) "已删掉 $n 条习惯" else "本来就只有起床 / 睡觉两条")
        }
        col.addView(bSlim)
        return sv
    }

    // ---------------- 番茄钟 ----------------
    private fun screenPomo(): View {
        val (sv, col) = page("番茄钟")
        val c = Ui.card(this)
        c.gravity = Gravity.CENTER_HORIZONTAL

        val time = Ui.tv(this, PomodoroService.mmss(PomodoroService.remainSec), 56f, Ui.TXT, true)
        time.gravity = Gravity.CENTER
        val phase = Ui.tv(
            this,
            PomodoroService.phaseName(PomodoroService.phase) + " · 今日 " + Store.pomoDoneToday() + " 个",
            13f, Ui.SUB
        )
        phase.gravity = Gravity.CENTER
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 100
        bar.progressDrawable = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Ui.RED_DEEP, Ui.RED)
        ).apply { cornerRadius = Ui.dp(this@MainActivity, 6f).toFloat() }
        val blp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 8f)
        )
        blp.topMargin = Ui.dp(this, 16f)
        bar.layoutParams = blp
        c.addView(time)
        c.addView(Ui.space(this, 4))
        c.addView(phase)
        c.addView(bar)
        pomoTimeTv = time
        pomoPhaseTv = phase
        pomoBar = bar

        val row = Ui.row(this)
        row.setPadding(0, Ui.dp(this, 16f), 0, 0)
        val bStart = Ui.btn(this, "开始专注")
        bStart.setOnClickListener {
            PomodoroService.start(this, PomodoroService.PHASE_WORK)
            h.post(uiTick)
            toast("开始专注，手机放远点")
        }
        val bPause = Ui.btn(this, "暂停", filled = false)
        bPause.setOnClickListener {
            PomodoroService.togglePause()
            h.post(uiTick)
        }
        val bStop = Ui.btn(this, "停止", filled = false)
        bStop.setOnClickListener {
            PomodoroService.stopAll(this); show(1)
        }
        for (b in listOf(bStart, bPause, bStop)) {
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.rightMargin = Ui.dp(this, 8f)
            b.layoutParams = lp
            row.addView(b)
        }
        c.addView(row)
        col.addView(c)

        val c2 = card("时长设置（分钟）")
        val eW = edit("专注（现在 " + Store.pomoWork() + "）", InputType.TYPE_CLASS_NUMBER)
        val eB = edit("休息（现在 " + Store.pomoBreak() + "）", InputType.TYPE_CLASS_NUMBER)
        val eL = edit("长休息（现在 " + Store.pomoLong() + "）", InputType.TYPE_CLASS_NUMBER)
        val eC = edit("几轮后长休息（现在 " + Store.pomoCycles() + "）", InputType.TYPE_CLASS_NUMBER)
        c2.addView(eW); c2.addView(eB); c2.addView(eL); c2.addView(eC)
        val bSave = Ui.btn(this, "保存")
        bSave.setOnClickListener {
            Store.setPomo(
                (eW.text.toString().toIntOrNull() ?: Store.pomoWork()).coerceIn(1, 180),
                (eB.text.toString().toIntOrNull() ?: Store.pomoBreak()).coerceIn(1, 60),
                (eL.text.toString().toIntOrNull() ?: Store.pomoLong()).coerceIn(1, 120),
                (eC.text.toString().toIntOrNull() ?: Store.pomoCycles()).coerceIn(1, 12)
            )
            toast("已保存")
            show(1)
        }
        c2.addView(bSave)
        col.addView(c2)

        val cP = card("番茄预设（点「开始」即用，✕ 可删）")
        val presets = Store.presets()
        for ((idx, pr) in presets.withIndex()) {
            val row = Ui.row(this)
            row.setPadding(0, Ui.dp(this, 6f), 0, Ui.dp(this, 6f))
            val nm = Ui.tv(this, pr.name + "  ·  " + pr.minutes + " 分钟", 15f, Ui.TXT)
            nm.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            nm.setPadding(0, Ui.dp(this, 8f), 0, Ui.dp(this, 8f))
            val go = Ui.btn(this, "开始", small = true)
            go.setOnClickListener {
                PomodoroService.start(this, PomodoroService.PHASE_WORK, pr.minutes)
                h.post(uiTick)
                toast("开始 " + pr.name)
            }
            row.addView(nm)
            row.addView(go)
            row.addView(xBtn { Store.removePreset(pr.name, pr.minutes); show(1) })
            cP.addView(row)
            if (idx != presets.size - 1) cP.addView(Ui.divider(this))
        }
        val bAddP = Ui.btn(this, "＋ 新增预设", filled = false)
        bAddP.setOnClickListener { dialogAddPreset() }
        cP.addView(bAddP)
        col.addView(cP)

        val c3 = card("专注时一起锁机")
        c3.addView(Ui.tv(this, "专注开始后锁住手机 ${Store.quickLockMin()} 分钟，专心做事", 13f, Ui.SUB))
        val bBoth = Ui.btn(this, "开始专注 + 锁机", filled = false)
        bBoth.setOnClickListener {
            PomodoroService.start(this, PomodoroService.PHASE_WORK)
            PomodoroService.autoNext = true
            LockService.start(this, Store.pomoWork(), LockService.MODE_PLAIN)
            h.post(uiTick)
        }
        c3.addView(bBoth)
        col.addView(c3)
        return sv
    }

    // ---------------- 记录 ----------------
    private fun screenNotes(): View {
        val (sv, col) = page("记录")
        val cT = card("待办（点一下 = 完成）")
        val tl = Store.todos()
        if (tl.isEmpty()) cT.addView(Ui.tv(this, "暂时没有待办", 13f, Ui.SUB))
        for (t in tl) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, Ui.dp(this, 9f), 0, Ui.dp(this, 9f))
            val mk = Ui.tv(this, if (t.done) "✓" else "○", 18f,
                if (t.done) 0xFF1E9E5A.toInt() else Ui.SUB, true)
            mk.setPadding(Ui.dp(this, 4f), 0, Ui.dp(this, 10f), 0)
            row.addView(mk)
            val tx = Ui.tv(this, (if (t.from == "棂星") "🌟 " else "") + t.text, 15f,
                if (t.done) Ui.SUB else Ui.TXT)
            tx.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (t.done) tx.paintFlags = tx.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            tx.setOnClickListener { Store.setTodoDone(t.id, !t.done); show(2) }
            row.addView(tx)
            val x = Ui.tv(this, "✕", 18f, 0xFFC7C7CC.toInt())
            x.setPadding(Ui.dp(this, 12f), Ui.dp(this, 4f), Ui.dp(this, 6f), Ui.dp(this, 4f))
            x.isClickable = true
            x.setOnClickListener { Store.delTodo(t.id); show(2) }
            row.addView(x)
            cT.addView(row)
            cT.addView(Ui.divider(this))
        }
        val bTodo = Ui.btn(this, "＋ 我自己加一条", filled = false)
        bTodo.setOnClickListener {
            editRow("新增待办", listOf("要做什么")) { v ->
                if (v[0].isNotBlank()) {
                    Store.addTodo(v[0], "我"); show(2)
                }
            }
        }
        cT.addView(bTodo)
        col.addView(cT)

        val c = card("随手记（皮肤状态 / 备忘）")
        val e = edit("例如：脸颊新生一颗痘，昨晚熬夜了")
        e.minLines = 2
        c.addView(e)
        val b = Ui.btn(this, "保存")
        b.setOnClickListener {
            val t = e.text.toString().trim()
            if (t.isEmpty()) toast("先写点东西") else {
                Store.addNote(t); show(2)
            }
        }
        c.addView(b)
        col.addView(c)

        val c2 = card("历史（长按删除）")
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        val list = Store.notes()
        if (list.isEmpty()) c2.addView(Ui.tv(this, "还没有记录", 14f, Ui.SUB))
        for ((idx, n) in list.withIndex()) {
            val row = Ui.row(this)
            row.setPadding(0, Ui.dp(this, 4f), 0, Ui.dp(this, 4f))
            val v = Ui.tv(this, fmt.format(Date(n.ts)) + "\n" + n.text, 15f, Ui.TXT)
            v.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            v.setPadding(0, Ui.dp(this, 8f), 0, Ui.dp(this, 8f))
            row.addView(v)
            row.addView(xBtn { Store.delNote(n.ts); show(2) })
            c2.addView(row)
            if (idx != list.size - 1) c2.addView(Ui.divider(this))
        }
        col.addView(c2)
        return sv
    }

    // ---------------- 用药 ----------------
    private fun screenMeds(): View {
        val (sv, col) = page("用药")
        val c = card("正在用的药（到点提醒）")
        val meds = Store.meds()
        if (meds.isEmpty()) c.addView(Ui.tv(this, "还没加药", 14f, Ui.SUB))
        for ((idx, m) in meds.withIndex()) {
            val row = Ui.row(this)
            row.setPadding(0, Ui.dp(this, 12f), 0, Ui.dp(this, 12f))
            val txt = Ui.tv(
                this,
                m.name + "\n剩 " + m.left + " 天 · " + m.time + (if (m.note.isNotEmpty()) " · " + m.note else ""),
                15f, Ui.TXT
            )
            txt.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            val b1 = Ui.btn(this, "－1", small = true)
            b1.setOnClickListener {
                Store.bumpMed(m.id, -1); show(3)
            }
            val del = xBtn { Store.removeMed(m.id); show(3) }
            row.addView(txt)
            row.addView(b1)
            row.addView(del)
            c.addView(row)
            if (idx != meds.size - 1) c.addView(Ui.divider(this))
        }
        val bAdd = Ui.btn(this, "＋ 添加用药（带提醒时间）")
        bAdd.setOnClickListener { dialogAddMed() }
        c.addView(bAdd)
        col.addView(c)

        val c2 = card("买药清单")
        val e = edit("药名 / 要买的东西")
        c2.addView(e)
        val bAdd2 = Ui.btn(this, "加入清单")
        bAdd2.setOnClickListener {
            val t = e.text.toString().trim()
            if (t.isNotEmpty()) {
                Store.addBuy(t); show(3)
            }
        }
        c2.addView(bAdd2)
        val buys = Store.buys()
        for ((idx, b) in buys.withIndex()) {
            val row = Ui.row(this)
            row.setPadding(0, Ui.dp(this, 4f), 0, Ui.dp(this, 4f))
            val v = Ui.tv(this, (if (b.done) "✓ " else "○ ") + b.name, 16f, if (b.done) Ui.SUB else Ui.TXT)
            v.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            v.setPadding(0, Ui.dp(this, 10f), 0, Ui.dp(this, 10f))
            row.isClickable = true
            v.setOnClickListener {
                Store.saveBuys(Store.buys().map { if (it.name == b.name) Store.Buy(it.name, !it.done) else it })
                show(3)
            }
            row.addView(v)
            row.addView(xBtn { Store.saveBuys(Store.buys().filter { it.name != b.name }); show(3) })
            c2.addView(row)
            if (idx != buys.size - 1) c2.addView(Ui.divider(this))
        }
        col.addView(c2)

        val c3 = card("药名参考（都先看皮肤科确诊）")
        c3.addView(
            Ui.tv(
                this,
                "灰指甲：阿莫罗芬 / 环吡酮甲涂剂（每周 1-2 次，坚持 6-12 个月）\n" +
                        "痘痘：阿达帕林（晚薄涂）+ 过氧化苯甲酰（白天点涂）\n" +
                        "腿根两侧：先分清股癣还是湿疹 —— 真菌用特比萘芬，湿疹用弱效激素（≤2 周）\n" +
                        "口服抗真菌药必须医生开 + 查肝功能，别自己买",
                13f, Ui.SUB
            )
        )
        col.addView(c3)
        return sv
    }

    private fun dialogAddMed() {
        editRow(
            "添加用药",
            listOf(
                "药名（例如：阿达帕林凝胶）",
                "提醒时间，可多个用逗号隔开，例如 08:00, 13:30, 21:00",
                "备注（例如：只涂下巴）", "剩余天数，例如 30"
            )
        ) { v ->
            if (v[0].isEmpty()) toast("药名别空着") else {
                val tstr = v[1].ifEmpty { "21:10" }
                Store.addMed(v[0], v[3].toIntOrNull() ?: 30, tstr, v[2])
                Alarms.scheduleMeds(this)
                toast("已加：每天提醒 " + Store.medTimes(Store.Med("", v[0], 0, tstr, "")).size + " 次")
                show(3)
            }
        }
    }

    private fun dialogAddPreset() {
        editRow("新增番茄预设", listOf("名字（例如：写代码 45 分钟）", "几分钟，例如 45")) { v ->
            if (v[0].isNotEmpty()) {
                Store.addPreset(v[0], v[1].toIntOrNull() ?: 25)
                show(1)
            }
        }
    }

    // ---------------- 设置 ----------------
    // ================= 插件页（v2.43 纳棂：设置里加插件页，用户跟自己的 AI 商量后加卡片） =================
    private fun screenPlugins(): View {
        val sc = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(Ui.dp(this, 16f), Ui.dp(this, 8f), Ui.dp(this, 16f), Ui.dp(this, 24f))
        col.addView(Ui.bigTitle(this, "插件"))
        col.addView(
            Ui.tv(
                this,
                "插件 = 一张你**自己加**的首页小卡片（比如股票、服务器状态、粉丝数）。\n" +
                        "做法：① 点下面「复制插件规格」→ ② 粘给你自己的 AI（豆包/马维斯都行），跟它说要什么 → " +
                        "③ AI 给你一段 JSON → ④ 粘回这里「粘贴 AI 的 JSON」就装好了。\n" +
                        "插件是声明式的（不加载外部代码），只请求你指定的 https 地址。",
                12f, Ui.SUB
            )
        )

        val bSpec = Ui.btn(this, "① 复制插件规格（给 AI 看）")
        bSpec.setOnClickListener {
            copyText(Plugins.aiSpec())
            toast("复制好了 → 粘给 AI，说要什么插件")
        }
        col.addView(bSpec)

        val bStock = Ui.btn(this, "② 加一个股票卡（只填代码）", filled = false)
        bStock.setOnClickListener {
            val et = EditText(this)
            et.hint = "股票代码，例 600519 / sh000001"
            AlertDialog.Builder(this)
                .setTitle("加股票卡")
                .setView(et)
                .setPositiveButton("加") { _, _ ->
                    val code = et.text.toString().trim()
                    if (code.isBlank()) toast("代码不能空") else {
                        val p = Plugins.stock(code)
                        Plugins.add(this, p)
                        addPluginCard(p)
                        toast("已加：${p.name}")
                        show(9)
                    }
                }
                .setNegativeButton("取消", null).show()
        }
        col.addView(bStock)

        val bPaste = Ui.btn(this, "③ 粘贴 AI 给的插件 JSON", filled = false)
        bPaste.setOnClickListener {
            val et = EditText(this)
            et.hint = "{\"name\":\"上证指数\",\"icon\":\"📈\",\"url\":\"...\",\"pick\":\"split:~:3\"}"
            et.minLines = 4
            AlertDialog.Builder(this)
                .setTitle("粘贴插件 JSON")
                .setView(et)
                .setPositiveButton("装上") { _, _ ->
                    try {
                        val o = org.json.JSONObject(et.text.toString().trim())
                        val p = Plugins.P.from(o)
                        if (p.url.isBlank() || p.pick.isBlank()) {
                            toast("缺 url 或 pick，让 AI 补上")
                        } else {
                            Plugins.add(this, p)
                            addPluginCard(p)
                            toast("已装：${p.name}")
                            show(9)
                        }
                    } catch (e: Exception) {
                        toast("JSON 解析失败：" + (e.message ?: ""))
                    }
                }
                .setNegativeButton("取消", null).show()
        }
        col.addView(bPaste)

        col.addView(Ui.sectionTitle(this, "已装插件（点「试」立刻拉一次 / 「上首页」加卡片）"))
        val list = Plugins.load(this)
        if (list.isEmpty()) {
            col.addView(Ui.tv(this, "还没有插件。先加一个股票卡试试 👆", 12f, Ui.SUB))
        }
        for (p in list) {
            val c = card(p.icon + " " + p.name)
            c.addView(Ui.tv(this, p.url, 11f, Ui.SUB))
            val row = Ui.row(this)
            val bTest = Ui.btn(this, "试", filled = false, small = true)
            val tvOut = Ui.tv(this, "—", 12f, Ui.TXT)
            bTest.setOnClickListener {
                tvOut.text = "拉取中…"
                Plugins.fetch(p) { a, b, chg -> runOnUiThread { tvOut.text = "$a  $b" } }
            }
            val bHome = Ui.btn(this, "上首页", filled = false, small = true)
            bHome.setOnClickListener {
                addPluginCard(p)
                toast("已加到首页")
                show(9)
            }
            val bDel = Ui.btn(this, "删", filled = false, small = true)
            bDel.setOnClickListener {
                Plugins.remove(this, p.id)
                toast("已删插件（首页那张卡会显示「插件已删除」）")
                show(9)
            }
            row.addView(bTest); row.addView(bHome); row.addView(bDel)
            c.addView(row)
            c.addView(tvOut)
            col.addView(c)
        }
        sc.addView(col)
        return wrapWithBack(9, sc)
    }

    /** 把一个插件加成首页卡片 */
    private fun addPluginCard(p: Plugins.P) {
        val list = Cards.load()
        if (list.any { it.plug == p.id }) return
        list.add(
            Cards.C(
                System.currentTimeMillis(), p.name, dateStr(java.util.Date()), "down", null, 1, -1, 0, -1,
                Cards.STRONG_PUB[list.size % Cards.STRONG_PUB.size], false, 1, p.id
            )
        )
        Cards.save(list)
    }

    private fun dateStr(d: java.util.Date): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(d)

    private fun screenSettings(): View {
        val (sv, col) = page("设置")
        foldMode = false         // v2.22：关闭折叠（自定义折叠控件会导致布局递归栈溢出）

        val cF = card("功能开关（不想要的全关掉）")
        for ((fkey, flabel) in Store.FLAG_LIST) {
            val row = Ui.row(this)
            row.setPadding(0, Ui.dp(this, 6f), 0, Ui.dp(this, 6f))
            val tv = Ui.tv(this, flabel, 15f, Ui.TXT)
            tv.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            val sw2 = Switch(this)
            sw2.isChecked = if (fkey == "water") Store.waterOn() else Store.flag(fkey)
            sw2.setOnCheckedChangeListener { _, v ->
                if (fkey == "water") {
                    Store.setWaterOn(v); Alarms.scheduleWater(this)
                } else {
                    Store.setFlag(fkey, v)
                    rebuildNav()
                    show(current)
                    if (fkey == "sleepLock") {
                        Store.setLockEnabled(v); Alarms.scheduleLock(this)
                        if (!v) LockService.stop(this)
                    }
                    if (fkey == "wakeNoti") Alarms.scheduleWake(this)
                }
            }
            row.addView(tv)
            row.addView(sw2)
            cF.addView(row)
            cF.addView(Ui.divider(this))
        }
        col.addView(cF)

        val c1 = card("作息 / 锁机")
        val sw = Switch(this)
        sw.text = "到点自动锁机（${Store.lockHhmm()}）"
        sw.setTextColor(Ui.TXT)
        sw.isChecked = Store.lockEnabled()
        sw.setOnCheckedChangeListener { _, v ->
            Store.setLockEnabled(v)
            Alarms.scheduleLock(this)
            if (!v) LockService.stop(this)
        }
        c1.addView(sw)
        val eLock = edit("锁机时间，例如 22:50（现在 " + Store.lockHhmm() + "）")
        val eHold = edit("长按解锁秒数（现在 " + Store.holdSec() + "）", InputType.TYPE_CLASS_NUMBER)
        val eWake = edit("起床时间，例如 07:00（现在 " + Store.wakeHhmm() + "）")
        val eQuick = edit("「立即锁机」分钟数（现在 " + Store.quickLockMin() + "）", InputType.TYPE_CLASS_NUMBER)
        c1.addView(eLock); c1.addView(eHold); c1.addView(eWake); c1.addView(eQuick)
        val bSave = Ui.btn(this, "保存并重新定时")
        bSave.setOnClickListener {
            parseHhmm(eLock.text.toString())?.let { Store.setLockTime(it.first, it.second) }
            parseHhmm(eWake.text.toString())?.let { Store.setWakeTime(it.first, it.second) }
            eHold.text.toString().toIntOrNull()?.let { Store.setHoldSec(it) }
            eQuick.text.toString().toIntOrNull()?.let { Store.setQuickLockMin(it) }
            Store.syncHabitTimeNames()
            Alarms.scheduleAll(this)
            toast("已保存（习惯里的起床/睡觉时间也同步了）")
            show(4)
        }
        c1.addView(bSave)
        col.addView(c1)

        val cS = card("睡眠设置")
        val eEarly = edit("最早可起时间，例如 04:00（现在 " + Store.earlyHhmm() + "）")
        val eSnooze = edit("「晚 N 分钟」的 N（现在 " + Store.snoozeMin() + "）", InputType.TYPE_CLASS_NUMBER)
        val eUntil = edit("「早上好」窗口上限小时（现在 " + Store.wakeUntilH() + "，即 4:00-" + Store.wakeUntilH() + ":00 可点）", InputType.TYPE_CLASS_NUMBER)
        cS.addView(eEarly)
        cS.addView(eSnooze)
        cS.addView(eUntil)
        val bSaveS = Ui.btn(this, "保存睡眠设置")
        bSaveS.setOnClickListener {
            parseHhmm(eEarly.text.toString())?.let { Store.setEarly(it.first, it.second) }
            eSnooze.text.toString().toIntOrNull()?.let { Store.setSnoozeMin(it) }
            eUntil.text.toString().toIntOrNull()?.let { Store.setWakeUntilH(it) }
            toast("已保存")
            show(4)
        }
        cS.addView(bSaveS)
        val canWrite = try {
            android.provider.Settings.System.canWrite(this)
        } catch (_: Exception) {
            false
        }
        cS.addView(
            Ui.tv(
                this,
                if (canWrite) "秒熄屏：已开启 ✅（睡觉时把系统超时临时调到 4 秒，只熄屏、不锁屏）"
                else "秒熄屏：未开启 —— 点下面按钮给「修改系统设置」权限，睡觉时 4 秒自动黑屏",
                12f, Ui.SUB
            )
        )
        val bWrite = Ui.btn(
            this,
            if (canWrite) "已开启「修改系统设置」" else "开启「修改系统设置」（秒熄屏用）",
            filled = !canWrite
        )
        bWrite.setOnClickListener {
            if (canWrite) {
                toast("已开启，不用重复操作")
            } else {
                try {
                    startActivity(
                        Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS)
                            .setData(Uri.parse("package:$packageName"))
                    )
                } catch (_: Exception) {
                    toast("去系统设置 → 应用 → 棂打卡 → 允许修改系统设置")
                }
            }
        }
        cS.addView(bWrite)

        val bCheck = Ui.btn(this, "熄屏自检（为什么没熄屏，一测就知道）", filled = false)
        bCheck.setOnClickListener { lockSelfCheck() }
        cS.addView(bCheck)

        // ---- 熄屏：不用 Shizuku，靠控制中心的「一键锁屏」----
        cS.addView(Ui.divider(this))
        cS.addView(Ui.sectionTitle(this, "熄屏（用控制中心的「一键锁屏」）"))
        cS.addView(
            Ui.tv(
                this,
                "点「我要睡觉了」后会收起悬浮窗几秒 → 你下拉控制中心点「一键锁屏」即可。\n" +
                        "填 0 = 不收起悬浮窗、不提示（你自己熄灭）。",
                12f, Ui.SUB
            )
        )
        val eManual = edit("熄屏提示秒数（现在 " + Store.manualLockSec() + "，0=不提示）", InputType.TYPE_CLASS_NUMBER)
        cS.addView(eManual)
        val bSaveM = Ui.btn(this, "保存熄屏提示秒数", filled = false)
        bSaveM.setOnClickListener {
            eManual.text.toString().toIntOrNull()?.let { Store.setManualLockSec(it) }
            toast("已保存")
            show(4)
        }
        cS.addView(bSaveM)

        cS.addView(
            Ui.tv(
                this,
                "电源键坏了 + 不用 Shizuku 的稳妥做法：\n" +
                        "① 系统设置里搜「双击熄屏 / 双击状态栏熄屏」打开（vivo 智能体感里）\n" +
                        "② 开发者选项 → 「充电时保持唤醒 / 不锁定屏幕」必须关（开着的话充电时永远不熄屏）\n" +
                        "③ 显示 → 「智能保持亮屏 / 注视不熄屏」关掉\n" +
                        "④ 安全 → 自动锁定 → 选最长（或「永不」）= 醒来不用输密码",
                12f, Ui.SUB
            )
        )

        val lk = Store.lockLog()
        if (lk.isNotEmpty()) {
            cS.addView(Ui.divider(this))
            cS.addView(Ui.sectionTitle(this, "熄屏日志（点「复制」发我）"))
            val lgv = Ui.tv(this, lk, 11f, Ui.SUB)
            lgv.setTextIsSelectable(true)
            cS.addView(lgv)
            val bCopyL = Ui.btn(this, "复制熄屏日志", filled = false, small = true)
            bCopyL.setOnClickListener { copyText(lk) }
            cS.addView(bCopyL)
        }
        col.addView(cS)

        val cW = card("喝水 / 久坐提醒")
        val swW = Switch(this)
        swW.text = "每小时提醒我喝水、站起来动一动"
        swW.setTextColor(Ui.TXT)
        swW.isChecked = Store.waterOn()
        swW.setOnCheckedChangeListener { _, v ->
            Store.setWaterOn(v)
            Alarms.scheduleWater(this)
        }
        cW.addView(swW)
        val eW2 = edit("间隔分钟（现在 " + Store.waterMin() + "）", InputType.TYPE_CLASS_NUMBER)
        val eW3 = edit("提醒时段，例如 7-22（现在 " + Store.waterFrom() + "-" + Store.waterTo() + "）")
        cW.addView(eW2); cW.addView(eW3)
        val bSaveW = Ui.btn(this, "保存提醒设置")
        bSaveW.setOnClickListener {
            eW2.text.toString().toIntOrNull()?.let { Store.setWaterMin(it) }
            val win = eW3.text.toString().split("-", "~", "－")
            if (win.size >= 2) {
                val a = win[0].trim().toIntOrNull()
                val b = win[1].trim().toIntOrNull()
                if (a != null && b != null) Store.setWaterWindow(a, b)
            }
            Alarms.scheduleWater(this)
            toast("已保存")
            show(4)
        }
        cW.addView(bSaveW)
        col.addView(cW)

        val cB = card("数据备份（换手机不丢）")
        cB.addView(Ui.tv(this, "导出成 .linji 加密备份（AES-256-GCM，要设口令）→ 存到「下载 / 棂记」；" +
                "换机用「导入」+ 同一口令读回来。旧版 .json 也兼容。", 12f, Ui.SUB))
        val br = Ui.row(this)
        br.setPadding(0, Ui.dp(this, 12f), 0, 0)
        val bExp = Ui.btn(this, "导出备份")
        bExp.setOnClickListener { exportBackup() }
        val bImp = Ui.btn(this, "导入备份", filled = false)
        bImp.setOnClickListener {
            try {
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 21
                )
            } catch (_: Exception) {
                toast("这台机器没有文件选择器")
            }
        }
        for (b in listOf(bExp, bImp)) {
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.rightMargin = Ui.dp(this, 8f)
            b.layoutParams = lp
            br.addView(b)
        }
        cB.addView(br)
        col.addView(cB)

        // ---------- v2.32：更新（服务器地址只给检查更新用）----------
        val cUp = card("检查更新")
        val vRow = Ui.row(this)
        vRow.setPadding(0, Ui.dp(this, 4f), 0, Ui.dp(this, 4f))
        val vTv = Ui.tv(this, "棂记 v${Update.curVersionName(this)}（build ${Update.curVersionCode(this)}）" +
                if (devOn()) "  🔧" else "", 15f, Ui.TXT)
        vTv.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        vTv.isClickable = true
        vTv.setOnClickListener { tapVersion() }
        vRow.addView(vTv)
        cUp.addView(vRow)
        val bChk = Ui.btn(this, "检查更新")
        bChk.setOnClickListener { doCheckUpdate(true) }
        cUp.addView(bChk)
        cUp.addView(
            Ui.tv(
                this, "服务器地址（只有检查更新用它）。建议留空 = 默认棂冕域名（走 Cloudflare，国内能通）；" +
                        "填裸 IP 会走 http 的 80 端口，国内常被拦（实测过）。", 12f, Ui.SUB
            )
        )
        val eSrv = edit("服务器 IP（例 23.94.214.35）或域名")
        eSrv.setText(Store.serverBaseRaw())
        cUp.addView(eSrv)
        val bSrv = Ui.btn(this, "保存服务器", filled = false)
        bSrv.setOnClickListener {
            Store.setServerBase(eSrv.text.toString())
            toast("服务器：${Store.serverBase()}")
            show(4)
        }
        cUp.addView(bSrv)
        cUp.addView(Ui.tv(this, "当前生效：${Store.serverBase()}", 11f, Ui.SUB))
        col.addView(cUp)



        // ---------- v2.38：App 桥（手机 ↔ 服务器 自动同步，像 TG 那样上线就收） ----------
        val cBr = card("App 桥（自动同步）")
        val rowB = Ui.row(this)
        rowB.setPadding(0, Ui.dp(this, 6f), 0, Ui.dp(this, 6f))
        val lbB = Ui.tv(this, "开着就自动收服务器发来的东西", 15f, Ui.TXT)
        lbB.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val swB = Switch(this)
        swB.isChecked = Bridge.enabled(this)
        swB.setOnCheckedChangeListener { _, v ->
            Bridge.setEnabled(this, v)
            if (v) {
                Bridge.start(this)
                toast("桥已开：每 20 秒同步一次，联网/开机自动续上")
            } else {
                Bridge.stop(this)
                toast("桥已关")
            }
        }
        rowB.addView(lbB)
        rowB.addView(swB)
        cBr.addView(rowB)
        cBr.addView(
            Ui.tv(
                this, (if (Bridge.enabled(this)) "状态：✅ 自动同步中（每 20 秒）\n"
                else "状态：⚠️ 桥没开 → 只有你手动点「立即同步」才会连\n") +
                        "地址：${Bridge.url(this)}\n令牌：" +
                        (if (Bridge.token(this).isBlank()) "未设置（服务器会拒收）" else "已设置") +
                        "\n上次同步：${Bridge.lastSync(this)}", 12f, Ui.SUB
            )
        )
        val bSetB = Ui.btn(this, "设置令牌", filled = false)
        bSetB.setOnClickListener {
            val colD = LinearLayout(this)
            colD.orientation = LinearLayout.VERTICAL
            colD.setPadding(Ui.dp(this, 16f), Ui.dp(this, 8f), Ui.dp(this, 16f), 0)
            val etU = EditText(this); etU.hint = "桥地址"; etU.setText(Bridge.url(this))
            val etT = EditText(this); etT.hint = "令牌（服务器 bridge.php / token.txt 里那个）"; etT.setText(Bridge.token(this))
            colD.addView(etU); colD.addView(etT)
            AlertDialog.Builder(this).setTitle("App 桥设置").setView(colD)
                .setPositiveButton("保存") { _, _ ->
                    Bridge.setUrl(this, etU.text.toString().trim())
                    Bridge.setToken(this, etT.text.toString().trim())
                    toast("已保存")
                    show(4)
                }
                .setNegativeButton("取消", null).show()
        }
        cBr.addView(bSetB)
        val bSync = Ui.btn(this, "立即同步一次", filled = false)
        bSync.setOnClickListener {
            toast("同步中…")
            Bridge.manualSync(this) { msg -> runOnUiThread { toast(msg) } }
        }
        cBr.addView(bSync)
        cBr.addView(
            Ui.tv(
                this, "手机离线时服务器加的东西（待办/通知）会排队留着，你一联网就自动收到；" +
                        "开机也会自动续上，不用手动点。流量和耗电都很小。", 11f, Ui.SUB
            )
        )
        col.addView(cBr)

        // ---------- v2.34：SSH 私钥连接服务器（三步配对） ----------
        val cSh = card("SSH 私钥连接服务器")
        cSh.addView(
            Ui.tv(
                this, "三步配对：① 填服务器 IP ② App 生成 .ssh 私钥 ③ 把公钥装到服务器 → 配对成功。" +
                        "之后手机 ↔ 服务器直连，不经任何中间服务器。", 12f, Ui.SUB
            )
        )

        cSh.addView(Ui.sectionTitle(this, "① 服务器 IP"))
        val eHost = edit("IP 或 IP:端口（例 23.94.214.35:16598）")
        eHost.setText(sshHostRaw(this))
        cSh.addView(eHost)
        val bSaveSsh = Ui.btn(this, "保存 IP")
        bSaveSsh.setOnClickListener {
            Store.prefs().edit().putString("sshHost", eHost.text.toString().trim()).apply()
            toast("已保存：${sshHostRaw(this)}")
            show(4)
        }
        cSh.addView(bSaveSsh)

        cSh.addView(Ui.sectionTitle(this, "② 生成 .ssh 私钥"))
        if (SshKey.exists(this)) {
            cSh.addView(Ui.tv(this, "指纹：${SshKey.fingerprint(this)}\n私钥只在本机 App 私有目录，不上传。", 12f, Ui.SUB))
        } else {
            val bGen = Ui.btn(this, "生成 .ssh 私钥")
            bGen.setOnClickListener {
                try {
                    SshKey.generate(this)
                    toast("已生成 ✅ 接着把公钥装到服务器")
                    show(4)
                } catch (e: Exception) {
                    toast("生成失败：" + (e.message ?: ""))
                }
            }
            cSh.addView(bGen)
        }

        cSh.addView(Ui.sectionTitle(this, "③ 把公钥装到服务器"))
        val bOne = Ui.btn(this, "复制一键安装命令")
        bOne.setOnClickListener {
            if (!SshKey.exists(this)) {
                toast("先点上面生成私钥")
            } else {
                copyText(oneKeyCmd())
                toast("复制好了 → 贴到服务器上跑一次（云控制台/宝塔终端都行）")
            }
        }
        cSh.addView(bOne)
        val bCpKey = Ui.btn(this, "只复制公钥", filled = false)
        bCpKey.setOnClickListener {
            if (SshKey.exists(this)) copyText(SshKey.publicKey(this)) else toast("先生成私钥")
        }
        cSh.addView(bCpKey)
        cSh.addView(
            Ui.tv(
                this, "那条命令做的事：把公钥写进服务器的 ~/.ssh/authorized_keys。" +
                        "服务器上要放的其它文件（更新清单 version.json / bridge.php）见部署教学。", 11f, Ui.SUB
            )
        )
        val bDoc = Ui.btn(this, "打开部署教学（GitHub）", filled = false)
        bDoc.setOnClickListener {
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/furrynaling-alt/linji"))
                )
            } catch (_: Exception) {
                toast("浏览器打不开，手输 github.com/furrynaling-alt/linji")
            }
        }
        cSh.addView(bDoc)

        cSh.addView(Ui.sectionTitle(this, "④ 配对连接"))
        val outTv = Ui.tv(this, "（配对结果显示在这里）", 12f, Ui.TXT)
        outTv.gravity = Gravity.START
        outTv.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        outTv.setTextIsSelectable(true)
        val bConn = Ui.btn(this, "配对连接")
        bConn.setOnClickListener {
            val h = sshHost(this)
            if (h.isBlank()) {
                toast("先填服务器 IP")
            } else {
                outTv.text = "配对中…"
                SshShell.run(
                    this, h, sshPort(this), sshUser(this),
                    "echo LINJI_OK; uptime; free -m | head -2"
                ) { r ->
                    runOnUiThread {
                        outTv.text = if (r.contains("LINJI_OK")) {
                            "配对成功 ✅\n\n" + r.replace("LINJI_OK", "").trim()
                        } else {
                            r
                        }
                    }
                }
            }
        }
        cSh.addView(bConn)
        cSh.addView(outTv)
        val bCmd = Ui.btn(this, "执行自定义命令…", filled = false)
        bCmd.setOnClickListener {
            editRow("执行命令", listOf("命令，例 systemctl status nginx")) { v ->
                if (v[0].isNotBlank()) {
                    outTv.text = "执行中…"
                    SshShell.run(this, sshHost(this), sshPort(this), sshUser(this), v[0]) { r ->
                        runOnUiThread { outTv.text = r }
                    }
                }
            }
        }
        cSh.addView(bCmd)
        val bCpCmd = Ui.btn(this, "复制 AI 提示词（让 AI 帮你配服务器）", filled = false)
        bCpCmd.setOnClickListener {
            copyText(aiPrompt())
            toast("复制好了 → 粘给你的 AI（马维斯/豆包/元宝都行）")
        }
        cSh.addView(bCpCmd)
        col.addView(cSh)

        // ---------- v2.31：日志与诊断 ----------
        val cDiag = card("日志与诊断")
        cDiag.addView(Ui.tv(this, diagText(), 12f, Ui.SUB))
        val bCpDiag = Ui.btn(this, "复制诊断信息", filled = false)
        bCpDiag.setOnClickListener { copyText(diagText()) }
        cDiag.addView(bCpDiag)
        val bLog = Ui.btn(this, "复制桥日志", filled = false)
        bLog.setOnClickListener { copyText(bridgeLog()) }
        cDiag.addView(bLog)
        col.addView(cDiag)

        // ---------- v2.43：插件 ----------
        val cPlug = card("插件（自己加首页小卡片）")
        cPlug.addView(
            Ui.tv(this, "股票 / 服务器 / 任意接口：跟你自己的 AI 商量好，把它给的 JSON 粘进来就装好了。", 12f, Ui.SUB)
        )
        val bPlug = Ui.btn(this, "打开插件页")
        bPlug.setOnClickListener { openPage(9) }
        cPlug.addView(bPlug)
        col.addView(cPlug)

        val c2 = card("权限（缺一个锁机就可能失效）")
        c2.addView(
            Ui.tv(
                this,
                "① 悬浮窗：盖住屏幕（最关键）\n② 通知：提醒用\n③ 电池优化白名单：免得半夜被杀（必点）\n④ 精确闹钟：到点才准\n\n" +
                        "vivo 防杀四步（不做夜里会被系统杀掉 → 整夜没记录）：\n" +
                        "· 设置 → 电池 → 后台耗电管理 → 棂打卡 → 允许后台高耗电\n" +
                        "· i 管家 → 自启动管理 → 棂打卡 → 允许\n" +
                        "· 最近任务里把棂打卡卡片往下拉 → 加锁（锁住后台）\n" +
                        "· 设置 → 应用与权限 → 棂打卡 → 耗电 → 允许后台运行",
                13f, Ui.SUB
            )
        )
        val r1 = Ui.row(this)
        r1.setPadding(0, Ui.dp(this, 12f), 0, 0)
        val bPerm1 = Ui.btn(this, "悬浮窗权限", small = true)
        bPerm1.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 23) startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        val bPerm2 = Ui.btn(this, "电池白名单", filled = false, small = true)
        bPerm2.setOnClickListener {
            try {
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
                startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        r1.addView(bPerm1); r1.addView(bPerm2)
        c2.addView(r1)
        val r2 = Ui.row(this)
        r2.setPadding(0, Ui.dp(this, 8f), 0, 0)
        val bPerm3 = Ui.btn(this, "精确闹钟", small = true)
        bPerm3.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    startActivity(
                        Intent(
                            android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (_: Exception) {
                }
            } else toast("这个安卓版本不需要")
        }
        val bPerm4 = Ui.btn(this, "通知设置", filled = false, small = true)
        bPerm4.setOnClickListener {
            try {
                startActivity(
                    Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra("android.provider.extra.APP_PACKAGE", packageName)
                )
            } catch (_: Exception) {
            }
        }
        r2.addView(bPerm3); r2.addView(bPerm4)
        c2.addView(r2)
        val r3 = Ui.row(this)
        r3.setPadding(0, Ui.dp(this, 8f), 0, 0)
        val bPerm5 = Ui.btn(this, "应用详情 / 耗电设置", small = true)
        bPerm5.setOnClickListener {
            try {
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
            }
        }
        val bPerm6 = Ui.btn(this, "省电模式设置", filled = false, small = true)
        bPerm6.setOnClickListener {
            try {
                startActivity(Intent("android.settings.BATTERY_SAVER_SETTINGS"))
            } catch (_: Exception) {
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS))
                } catch (_: Exception) {
                    toast("这个机型没有这个设置页")
                }
            }
        }
        r3.addView(bPerm5); r3.addView(bPerm6)
        c2.addView(r3)
        col.addView(c2)

        if (Store.flag("recorder")) {
        val cR = card("熄屏录像（关屏也继续录）")
        cR.addView(
            Ui.tv(
                this,
                "点「开始录像」后就可以关屏 / 锁屏，录像不会停。视频存到相册的「棂打卡」文件夹。",
                13f, Ui.SUB
            )
        )
        val swFront = Switch(this)
        swFront.text = if (Store.recFront()) "用前置摄像头" else "用后置摄像头"
        swFront.setTextColor(Ui.TXT)
        swFront.isChecked = Store.recFront()
        swFront.setOnCheckedChangeListener { _, v ->
            Store.setRecFront(v)
            swFront.text = if (v) "用前置摄像头" else "用后置摄像头"
        }
        cR.addView(swFront)
        val eRecMin = edit("最长录多少分钟（0 = 一直录到手动停，现在 " + Store.recMinutes() + "）", InputType.TYPE_CLASS_NUMBER)
        cR.addView(eRecMin)
        val rr = Ui.row(this)
        rr.setPadding(0, Ui.dp(this, 12f), 0, 0)
        val bRecStart = Ui.btn(this, "开始录像")
        bRecStart.setOnClickListener {
            eRecMin.text.toString().toIntOrNull()?.let { Store.setRecMinutes(it) }
            startRecording()
        }
        val bRecStop = Ui.btn(this, "停止录像", filled = false)
        bRecStop.setOnClickListener {
            RecorderService.stop(this); toast("已停止并保存")
            show(4)
        }
        for (b in listOf(bRecStart, bRecStop)) {
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.rightMargin = Ui.dp(this, 8f)
            b.layoutParams = lp
            rr.addView(b)
        }
        cR.addView(rr)
        val bRecAudio = Ui.btn(this, "只录音（最稳，熄屏一定能用）", filled = false)
        bRecAudio.setOnClickListener {
            eRecMin.text.toString().toIntOrNull()?.let { Store.setRecMinutes(it) }
            if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 8)
            } else {
                RecorderService.start(this, Store.recFront(), Store.recMinutes(), true)
                toast("开始录音了，可以关屏")
            }
        }
        cR.addView(bRecAudio)
        cR.addView(Ui.space(this, 6))
        cR.addView(Ui.tv(this, "提示：国产系统要允许「后台运行 / 自启动」，否则关屏后可能被杀。只录自己的场景，别录别人。", 12f, Ui.SUB))

        val recLogTxt = Store.recLog()
        if (recLogTxt.isNotEmpty()) {
            cR.addView(Ui.divider(this))
            cR.addView(Ui.sectionTitle(this, "上次录像日志（失败原因看这里）"))
            val lg = Ui.tv(this, recLogTxt, 11f, Ui.SUB)
            lg.setTextIsSelectable(true)
            cR.addView(lg)
            val bCopy = Ui.btn(this, "复制日志", filled = false, small = true)
            bCopy.setOnClickListener {
                try {
                    val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("recLog", recLogTxt))
                    toast("日志已复制，粘贴到 QQ 发我")
                } catch (_: Exception) {
                    toast("复制失败，截图也行")
                }
            }
            cR.addView(bCopy)
        }

        val recs = RecStore.list(this)
        if (recs.isNotEmpty()) {
            cR.addView(Ui.divider(this))
            cR.addView(Ui.sectionTitle(this, "已录好的（点一下播放，✕ 删）"))
            for ((i2, it2) in recs.take(12).withIndex()) {
                val row = Ui.row(this)
                row.setPadding(0, Ui.dp(this, 4f), 0, Ui.dp(this, 4f))
                val fmt2 = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                val tvv = Ui.tv(this, it2.name + "\n" + fmt2.format(Date(it2.date)) + " · " + RecStore.humanSize(it2.size), 14f, Ui.TXT)
                tvv.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                tvv.setPadding(0, Ui.dp(this, 8f), 0, Ui.dp(this, 8f))
                row.isClickable = true
                row.background = Ui.ripple(this, 14, null, 0x14000000)
                tvv.setOnClickListener { playVideo(it2.uri, it2.audio) }
                row.addView(tvv)
                row.addView(xBtn { RecStore.delete(this, it2); show(4) })
                cR.addView(row)
                if (i2 != recs.take(12).size - 1) cR.addView(Ui.divider(this))
            }
        }
        col.addView(cR)
        }

        val c3 = card("测试 / 重置")
        val bTest = Ui.btn(this, "测试锁机 1 分钟")
        bTest.setOnClickListener { LockService.start(this, 1, LockService.MODE_PLAIN) }
        c3.addView(bTest)
        val bStop = Ui.btn(this, "停止锁机", filled = false)
        bStop.setOnClickListener {
            LockService.stop(this); toast("已停止")
        }
        c3.addView(bStop)
        val bReset = Ui.btn(this, "清空全部数据", filled = false)
        bReset.setOnClickListener {
            AlertDialog.Builder(this).setTitle("确定清空？").setMessage("打卡、记录、用药都会被删掉")
                .setPositiveButton("清空") { _, _ ->
                    Store.resetAll(); show(0)
                }
                .setNegativeButton("取消", null).show()
        }
        c3.addView(bReset)
        col.addView(c3)

        val c4 = card("关于")
        c4.addView(
            Ui.tv(
                this,
                "棂打卡 v2.6 · 自用自建，无广告、不联网\n" +
                        "打卡 / 番茄 / 记录 / 用药 / 锁机 / 熄屏录像 / 睡眠时长记录\n22:50 到点问「我要睡觉了」→ 熄屏 → 早上好（4:00 后）\n所有内容都能自己加、自己删（每行右上角 ✕）",
                13f, Ui.SUB
            )
        )
        col.addView(c4)
        return sv
    }

    // ==================== 工时 / 工资（小时工） ====================
    private var wageTab = 0            // 0 记一笔 / 1 明细 / 2 日历 / 3 班次统计 / 4 记月工资
    private var wageOff = 0            // 考勤周期偏移（0 = 本周期）
    private var wageMonth = 0          // 日历月份偏移
    private var wageSel = ""           // 日历选中日期

    private fun chip(text: String, active: Boolean, onClick: () -> Unit): TextView {
        val t = Ui.tv(this, text, 13f, if (active) Color.WHITE else Ui.TXT, active)
        t.gravity = Gravity.CENTER
        t.setPadding(Ui.dp(this, 12f), Ui.dp(this, 7f), Ui.dp(this, 12f), Ui.dp(this, 7f))
        t.background = if (active) Ui.ripple(this, 14, Ui.grad(Ui.RED_DEEP, Ui.RED, 14, this), 0x40FFFFFF)
        else Ui.ripple(this, 14, Ui.roundStroke(0xFFFFFFFF.toInt(), Ui.LINE, 14, this), 0x1A000000)
        t.isClickable = true
        t.setOnClickListener { onClick() }
        return t
    }

    private fun chipStyle(t: TextView, active: Boolean) {
        (t.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.marginEnd = Ui.dp(this, 8f)
        } ?: run {
            t.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = Ui.dp(this@MainActivity, 8f) }
        }
        t.background = if (active) Ui.ripple(this, 14, Ui.grad(Ui.RED_DEEP, Ui.RED, 14, this), 0x40FFFFFF)
        else Ui.ripple(this, 14, Ui.roundStroke(0xFFFFFFFF.toInt(), Ui.LINE, 14, this), 0x1A000000)
        t.setTextColor(if (active) Color.WHITE else Ui.TXT)
        t.setTypeface(t.typeface, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    private fun numEdit(hint: String, decimal: Boolean = false): EditText {
        val it2 = InputType.TYPE_CLASS_NUMBER or
                (if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)
        return edit(hint, it2)
    }

    /** 金额输入：12 或 12/天（按天算） */
    private fun parseAmt(s: String): Pair<Double, Boolean> {
        val t = s.trim().replace("元", "")
        val per = t.contains("/天")
        val num = t.replace("/天", "").trim().toDoubleOrNull() ?: 0.0
        return Pair(num, per)
    }

    private fun screenWage(): View {
        val (sv, col) = page("工时工资")
        col.gravity = Gravity.CENTER_HORIZONTAL
        val cyc = Wage.cycleStartDate(wageOff)
        val list = Wage.recsIn(wageOff)
        val days = Wage.workDays(list)
        val nightDays = list.filter { it.shift == "夜班" }.map { it.date }.distinct().size
        val hours = Wage.sumHours(list)
        val pay = Wage.sumPay(list)
        val subT = Wage.itemTotal(Wage.K_SUB, cyc, days)
        val cutT = Wage.itemTotal(Wage.K_CUT, cyc, days) + Wage.itemTotal(Wage.K_OTH, cyc, days)
        val income = Wage.cycleIncome(wageOff)
        val mLabel = Wage.md(Wage.cycleStartDate(wageOff)).substringBefore(".").trimStart('0') + "月"

        // ---- 顶部：本月收入大卡 ----
        val head = LinearLayout(this)
        head.orientation = LinearLayout.VERTICAL
        head.background = Ui.grad(Ui.RED_DEEP, Ui.RED, 18, this)
        head.setPadding(Ui.dp(this, 16f), Ui.dp(this, 14f), Ui.dp(this, 16f), Ui.dp(this, 16f))
        val hlp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        hlp.bottomMargin = Ui.dp(this, 14f)
        head.layoutParams = hlp

        val prow = Ui.row(this)
        val bPrev = Ui.tv(this, "‹ 上一周期", 13f, 0xDDFFFFFF.toInt())
        val bNext = Ui.tv(this, "下一周期 ›", 13f, 0xDDFFFFFF.toInt())
        bPrev.setPadding(0, Ui.dp(this, 2f), 0, Ui.dp(this, 2f))
        bNext.setPadding(0, Ui.dp(this, 2f), 0, Ui.dp(this, 2f))
        bPrev.isClickable = true
        bNext.isClickable = true
        bPrev.setOnClickListener { wageOff -= 1; show(5) }
        bNext.setOnClickListener { wageOff += 1; show(5) }
        val cycTv = Ui.tv(this, Wage.cycleLabel(wageOff), 13f, Color.WHITE, true)
        cycTv.gravity = Gravity.CENTER
        cycTv.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        prow.addView(bPrev)
        prow.addView(cycTv)
        prow.addView(bNext)
        head.addView(prow)

        head.addView(Ui.tv(this, mLabel + "收入", 13f, 0xCCFFFFFF.toInt()))
        val big = Ui.tv(this, Wage.money(income), 34f, Color.WHITE, true)
        big.gravity = Gravity.CENTER
        big.setPadding(0, Ui.dp(this, 2f), 0, Ui.dp(this, 8f))
        head.addView(big)

        val srow = Ui.row(this)
        val c1 = Ui.tv(this, "考勤工时 " + Wage.hh1(hours), 13f, 0xE6FFFFFF.toInt())
        c1.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val c2 = Ui.tv(this, "工时收入 " + Wage.money(pay), 13f, 0xE6FFFFFF.toInt())
        srow.addView(c1)
        srow.addView(c2)
        head.addView(srow)
        col.addView(head)

        // ---- 三入口 + 再记一笔 ----
        val q = card(null)
        val qr = Ui.row(this)
        val entries = listOf(
            Triple("日历", R.drawable.ic_calendar, 2),
            Triple("统计", R.drawable.ic_chart, 3),
            Triple("明细", R.drawable.ic_note, 1)
        )
        for ((name, icon, tab) in entries) {
            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            box.gravity = Gravity.CENTER
            box.setPadding(0, Ui.dp(this, 6f), 0, Ui.dp(this, 6f))
            box.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            val iv = Ui.icon(this, icon, 26, Ui.TXT)
            box.addView(iv)
            val tv = Ui.tv(this, name, 13f, Ui.TXT)
            tv.setPadding(0, Ui.dp(this, 6f), 0, 0)
            box.addView(tv)
            box.isClickable = true
            box.background = Ui.ripple(this, 14, null, 0x1A000000)
            box.setOnClickListener { wageTab = tab; show(5) }
            qr.addView(box)
        }
        q.addView(qr)
        val bAdd = Ui.btn(this, "再记一笔")
        Ui.decorate(bAdd, this, R.drawable.ic_plus, Color.WHITE)
        bAdd.setOnClickListener { wageTab = 0; show(5) }
        q.addView(bAdd)
        col.addView(q)

        // ---- 今日记录 ----
        val tRecs = Wage.dayRecs(Wage.today())
        val ct = card(Wage.md(Wage.today()) + " · " + Wage.weekday(Wage.today()) + "　已记 " + tRecs.size + " 笔")
        if (tRecs.isEmpty()) {
            ct.addView(Ui.tv(this, "今天还没记，点上面「再记一笔」", 14f, Ui.SUB))
        } else {
            for ((i2, r) in tRecs.withIndex()) {
                val row = Ui.row(this)
                row.setPadding(0, Ui.dp(this, 6f), 0, Ui.dp(this, 6f))
                val left = LinearLayout(this)
                left.orientation = LinearLayout.VERTICAL
                left.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                left.addView(Ui.tv(this, r.shift + " " + (if (r.start.isNotEmpty()) Wage.span(r) else "记工时"), 15f, Ui.TXT))
                left.addView(Ui.tv(this, Wage.money(r.rate) + "元/小时 × " + Wage.hh1(Wage.hoursOf(r)) + "小时" + (if (r.note.isNotEmpty()) " · " + r.note else ""), 12f, Ui.SUB))
                row.addView(left)
                row.addView(Ui.tv(this, Wage.money(Wage.payOf(r)) + "元", 17f, Ui.RED, true))
                ct.addView(row)
                val ops = Ui.row(this)
                ops.gravity = Gravity.END
                val bDel = Ui.btn(this, "删除", false, true)
                bDel.setOnClickListener { Wage.delRec(r.id); show(5) }
                val bEd = Ui.btn(this, "修改", false, true)
                bEd.setOnClickListener { wageDayDialog(r.date) }
                ops.addView(bDel)
                ops.addView(Ui.space(this, 8))
                ops.addView(bEd)
                ct.addView(ops)
                if (i2 != tRecs.size - 1) ct.addView(Ui.divider(this))
            }
        }
        col.addView(ct)

        // ---- 分段：记一笔 / 明细 / 日历 / 统计 / 工资 / 考勤 ----
        val names = listOf("记一笔", "明细", "日历", "统计", "工资", "考勤")
        val segScroll = HorizontalScrollView(this)
        segScroll.isHorizontalScrollBarEnabled = false
        val seg = Ui.row(this)
        segScroll.addView(seg)
        val slp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        slp.bottomMargin = Ui.dp(this, 12f)
        segScroll.layoutParams = slp
        for (i2 in names.indices) {
            val t = chip(names[i2], wageTab == i2) { wageTab = i2; show(5) }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.rightMargin = Ui.dp(this, 6f)
            t.layoutParams = lp
            seg.addView(t)
        }
        col.addView(segScroll)

        when (wageTab) {
            1 -> wageDetail(col)
            2 -> wageCalendar(col)
            3 -> wageStat(col, list)
            4 -> wagePay(col, cyc, hours, pay, subT, cutT, income, days, nightDays)
            5 -> wageAttend(col)
            else -> wageRecord(col)
        }

        // ---- 工时设置 ----
        val cs = card("工时设置")
        val eRate = numEdit("小时工资，例如 16（现在 " + Wage.money(Wage.rate()) + "）", true)
        val eL = numEdit("午休分钟（现在 " + Wage.lunchDef() + "）")
        val eD2 = numEdit("晚休分钟（现在 " + Wage.dinnerDef() + "）")
        val eCy = numEdit("考勤周期起始日（1 = 自然月；21 = 21日到次月20日，现在 " + Wage.cycleStart() + "）")
        cs.addView(eRate)
        cs.addView(eL)
        cs.addView(eD2)
        cs.addView(eCy)
        val bS = Ui.btn(this, "保存工时设置")
        bS.setOnClickListener {
            eRate.text.toString().toDoubleOrNull()?.let { Wage.setRate(it) }
            val l = eL.text.toString().toIntOrNull()
            val d2 = eD2.text.toString().toIntOrNull()
            if (l != null || d2 != null) {
                Wage.setBreaks(l ?: Wage.lunchDef(), d2 ?: Wage.dinnerDef())
            }
            eCy.text.toString().toIntOrNull()?.let { Wage.setCycleStart(it) }
            toast("已保存")
            show(5)
        }
        cs.addView(bS)
        cs.addView(
            Ui.tv(
                this,
                "工时 = 下班 - 上班 - 午休 - 晚休；工资 = 工时 × 小时工资。休息班次不计钱。\n" +
                        "数据在「设置 → 导出备份」里一起备份，换手机不丢。",
                12f, Ui.SUB
            )
        )
        col.addView(cs)
        return sv
    }

    /** ① 记一笔（小时工 · 智能计算工时） */
    private fun wageRecord(col: LinearLayout) {
        val c = card("记一笔 · 智能算工时")
        val cY = chip("今天 " + Wage.md(Wage.today()), true) { }
        val eDate = edit("日期，例如 " + Wage.today() + "（默认今天）")
        val rowD = Ui.row(this)
        rowD.addView(cY)
        rowD.addView(Ui.space(this, 8))
        val cY2 = chip("昨天 " + Wage.md(Wage.d(1)), false) { eDate.setText(Wage.d(1)) }
        rowD.addView(cY2)
        c.addView(rowD)

        val cS = chip("前一天 " + Wage.md(Wage.d(1)), false) { eDate.setText(Wage.d(1)) }
        // 日期输入
        c.addView(eDate)

        val eStart = edit("上班时间，例如 08:00")
        val eEnd = edit("下班时间，例如 21:00")
        val eRate = numEdit("小时工资，例如 16（现在 " + Wage.money(Wage.rate()) + "）", true)
        val eL = numEdit("午休分钟（现在 " + Wage.lunchDef() + "）")
        val eD2 = numEdit("晚休分钟（现在 " + Wage.dinnerDef() + "）")
        c.addView(eStart)
        c.addView(eEnd)
        c.addView(eRate)
        c.addView(eL)
        c.addView(eD2)

        var shift = "白班"
        c.addView(Ui.sectionTitle(this, "班次"))
        val chips = mutableListOf<Pair<String, TextView>>()
        var srow: LinearLayout? = null
        for ((i2, s) in Wage.SHIFTS.withIndex()) {
            if (i2 % 3 == 0) {
                srow = Ui.row(this)
                srow.setPadding(0, Ui.dp(this, 3f), 0, Ui.dp(this, 3f))
                c.addView(srow)
            }
            val t = chip(s, s == shift) {
                shift = s
                for ((n, v) in chips) chipStyle(v, n == shift)
            }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.rightMargin = Ui.dp(this, 6f)
            t.layoutParams = lp
            chips.add(Pair(s, t))
            srow!!.addView(t)
        }

        val preview = Ui.tv(this, "", 16f, Ui.TXT, true)
        preview.setPadding(0, Ui.dp(this, 12f), 0, Ui.dp(this, 6f))
        c.addView(preview)

        fun calcNow(): Double {
            val s = Wage.hhmm(if (eStart.text.isEmpty()) "08:00" else eStart.text.toString()) ?: 0
            val e = Wage.hhmm(if (eEnd.text.isEmpty()) "21:00" else eEnd.text.toString()) ?: 0
            var mins = e - s
            if (mins <= 0) mins += 1440
            mins -= ((eL.text.toString().toIntOrNull() ?: Wage.lunchDef()) + (eD2.text.toString().toIntOrNull() ?: Wage.dinnerDef()))
            if (mins < 0) mins = 0
            return mins / 60.0
        }
        fun refresh() {
            val h = calcNow()
            val r = eRate.text.toString().toDoubleOrNull() ?: Wage.rate()
            preview.text = "上班 " + Wage.hh1(h) + " 小时　＝　" + Wage.money(h * r) + " 元"
        }
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c2: Int) { refresh() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        }
        eStart.addTextChangedListener(watcher)
        eEnd.addTextChangedListener(watcher)
        eRate.addTextChangedListener(watcher)
        eL.addTextChangedListener(watcher)
        eD2.addTextChangedListener(watcher)
        refresh()

        val bSave = Ui.btn(this, "保存工作时长")
        bSave.setOnClickListener {
            val date = if (eDate.text.isNullOrEmpty()) Wage.today() else eDate.text.toString().trim()
            if (Wage.parse(date) == null) {
                toast("日期写成 2026-09-19 这样")
            } else {
                val s = if (eStart.text.isNullOrEmpty()) "08:00" else eStart.text.toString()
                val e = if (eEnd.text.isNullOrEmpty()) "21:00" else eEnd.text.toString()
                if (Wage.hhmm(s) == null || Wage.hhmm(e) == null) {
                    toast("时间写成 08:00 这样")
                } else {
                    val r = eRate.text.toString().toDoubleOrNull() ?: Wage.rate()
                    Wage.setRate(r)
                    val l = eL.text.toString().toIntOrNull() ?: Wage.lunchDef()
                    val d2 = eD2.text.toString().toIntOrNull() ?: Wage.dinnerDef()
                    Wage.setBreaks(l, d2)
                    val rec = Wage.Rec(
                        "", date, s, e, r, l, d2, shift, ""
                    )
                    Wage.addRec(rec)
                    toast("已保存：上班 " + Wage.hh1(Wage.hoursOf(rec)) + " 小时")
                    wageTab = 1
                    show(5)
                }
            }
        }
        c.addView(bSave)

        val rowB = Ui.row(this)
        val bTpl = Ui.btn(this, "设为模板", false, true)
        bTpl.setOnClickListener {
            val s = if (eStart.text.isNullOrEmpty()) "08:00" else eStart.text.toString()
            val e = if (eEnd.text.isNullOrEmpty()) "21:00" else eEnd.text.toString()
            val r = eRate.text.toString().toDoubleOrNull() ?: Wage.rate()
            Wage.addTpl(Wage.Tpl(s, e, eL.text.toString().toIntOrNull() ?: Wage.lunchDef(),
                eD2.text.toString().toIntOrNull() ?: Wage.dinnerDef(), shift, r))
            toast("已存为快捷记录")
            show(5)
        }
        rowB.addView(bTpl)
        c.addView(rowB)

        val tpls = Wage.tpls()
        if (tpls.isNotEmpty()) {
            c.addView(Ui.divider(this))
            c.addView(Ui.sectionTitle(this, "新增快捷记录（点一下就套用）"))
            for ((i2, t) in tpls.withIndex()) {
                val row = Ui.row(this)
                row.setPadding(0, Ui.dp(this, 5f), 0, Ui.dp(this, 5f))
                val tv = Ui.tv(
                    this,
                    t.shift + " " + t.start + "-" + t.end + " · 休 " + (t.lunch + t.dinner) / 60 + "小时 · " + Wage.money(t.rate) + "元/时",
                    14f, Ui.TXT
                )
                tv.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                tv.isClickable = true
                tv.setOnClickListener {
                    eStart.setText(t.start); eEnd.setText(t.end)
                    eL.setText(t.lunch.toString()); eD2.setText(t.dinner.toString())
                    eRate.setText(t.rate.toString())
                    shift = t.shift
                    for ((n, v) in chips) chipStyle(v, n == shift)
                    refresh()
                    toast("已套用快捷记录")
                }
                row.addView(tv)
                row.addView(xBtn { Wage.delTpl(i2); show(5) })
                c.addView(row)
            }
        }
        col.addView(c)
    }

    /** ② 工时明细 */
    private fun wageDetail(col: LinearLayout) {
        val list = Wage.recsIn(wageOff)
        val head = card(null)
        val r1 = Ui.row(this)
        val a = Ui.tv(this, "本周期工时", 15f, Ui.TXT)
        a.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        r1.addView(a)
        r1.addView(Ui.tv(this, Wage.hh1(Wage.sumHours(list)) + "时　" + Wage.money(Wage.sumPay(list)) + "元", 17f, Ui.RED, true))
        head.addView(r1)
        head.addView(Ui.tv(this, "共 " + Wage.workDays(list) + " 天出勤 · " + Wage.cycleLabel(wageOff), 12f, Ui.SUB))
        col.addView(head)

        val c = card("每天明细（点一行修改 / 右侧 ✕ 删除）")
        if (list.isEmpty()) c.addView(Ui.tv(this, "本周期还没有记录", 14f, Ui.SUB))
        val byDate = list.map { it.date }.distinct().sortedDescending()
        for ((i2, date) in byDate.withIndex()) {
            val row = Ui.row(this)
            row.setPadding(0, Ui.dp(this, 8f), 0, Ui.dp(this, 8f))
            val left = LinearLayout(this)
            left.orientation = LinearLayout.VERTICAL
            left.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            val s = Wage.dayShift(date)
            left.addView(Ui.tv(this, Wage.md(date) + " " + Wage.weekday(date) + "　[" + s + "]", 15f, Ui.TXT))
            val dayRate = Wage.dayRecs(date).firstOrNull()?.rate ?: 0.0
            left.addView(Ui.tv(this, Wage.money(dayRate) + "元/小时", 12f, Ui.SUB))
            row.addView(left)
            val right = LinearLayout(this)
            right.orientation = LinearLayout.VERTICAL
            right.gravity = Gravity.END
            val hh = Ui.tv(this, Wage.hh1(Wage.dayHours(date)) + "时　" + Wage.money(Wage.dayPay(date)) + "元", 16f, Ui.TXT, true)
            hh.gravity = Gravity.END
            right.addView(hh)
            row.addView(right)
            row.addView(xBtn { Wage.delDay(date); show(5) })
            row.isClickable = true
            row.setOnClickListener { wageDayDialog(date) }
            c.addView(row)
            if (i2 != byDate.size - 1) c.addView(Ui.divider(this))
        }
        col.addView(c)
    }

    /** ③ 日历 */
    private fun wageCalendar(col: LinearLayout) {
        val c = card(null)
        val cal = Calendar.getInstance()
        cal.add(Calendar.MONTH, wageMonth)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val mLabel = "%d年%d月".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)

        val nav = Ui.row(this)
        val bPrev = chip("‹ 上月", false) { wageMonth -= 1; show(5) }
        val bNext = chip("下月 ›", false) { wageMonth += 1; show(5) }
        val mid = Ui.tv(this, mLabel, 16f, Ui.TXT, true)
        mid.gravity = Gravity.CENTER
        mid.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        nav.addView(bPrev)
        nav.addView(mid)
        nav.addView(bNext)
        c.addView(nav)

        val wk = Ui.row(this)
        for (w in listOf("一", "二", "三", "四", "五", "六", "日")) {
            val t = Ui.tv(this, w, 12f, Ui.SUB, true)
            t.gravity = Gravity.CENTER
            t.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            wk.addView(t)
        }
        wk.setPadding(0, Ui.dp(this, 8f), 0, Ui.dp(this, 4f))
        c.addView(wk)

        val first = Calendar.getInstance()
        first.time = cal.time
        val maxDay = first.getActualMaximum(Calendar.DAY_OF_MONTH)
        var dow = first.get(Calendar.DAY_OF_WEEK)          // 1 = 周日
        var mondayIdx = (dow + 5) % 7                       // 0 = 周一
        var day = 1
        val todayStr = Wage.today()
        while (day <= maxDay) {
            val row = Ui.row(this)
            for (colInRow in 0 until 7) {
                val cell = LinearLayout(this)
                cell.orientation = LinearLayout.VERTICAL
                cell.gravity = Gravity.CENTER
                cell.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                if ((day == 1 && colInRow < mondayIdx) || day > maxDay) {
                    row.addView(cell)
                    continue
                }
                val date = "%04d-%02d-%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, day)
                val hh = Wage.dayHours(date)
                val has = Wage.dayRecs(date).isNotEmpty()
                val isToday = date == todayStr
                val numTv = Ui.tv(this, day.toString(), 14f, if (isToday) Ui.RED else Ui.TXT, isToday)
                numTv.gravity = Gravity.CENTER
                cell.addView(numTv)
                if (has) {
                    val sh = Wage.dayShift(date)
                    val sub = Ui.tv(this, sh.take(1) + " " + Wage.hh1(hh) + "h", 9f, Ui.RED)
                    sub.gravity = Gravity.CENTER
                    cell.addView(sub)
                } else {
                    cell.addView(Ui.tv(this, " ", 9f, Ui.SUB))
                }
                cell.setPadding(0, Ui.dp(this, 5f), 0, Ui.dp(this, 5f))
                cell.background = if (wageSel == date)
                    Ui.roundStroke(0xFFFDECEE.toInt(), Ui.RED, 10, this)
                else Ui.ripple(this, 10, null, 0x1A000000)
                cell.isClickable = true
                cell.setOnClickListener { wageSel = date; wageDayDialog(date) }
                row.addView(cell)
                day++
            }
            c.addView(row)
        }

        c.addView(Ui.divider(this))
        val sr = Ui.row(this)
        val s1 = Ui.tv(this, "本月工时\n" + Wage.hh1(Wage.sumHours(Wage.recsIn(wageOff))), 13f, Ui.SUB)
        val s2 = Ui.tv(this, "本月收入\n" + Wage.money(Wage.sumPay(Wage.recsIn(wageOff))), 13f, Ui.SUB)
        s1.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        s2.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        sr.addView(s1)
        sr.addView(s2)
        c.addView(sr)
        val bSee = Ui.btn(this, "查看明细", false, true)
        bSee.setOnClickListener { wageTab = 1; show(5) }
        c.addView(bSee)

        // 漏记提示：本月已过去的工作日里没有记录的
        var miss = 0
        val now = Calendar.getInstance()
        for (d2 in 1..now.get(Calendar.DAY_OF_MONTH)) {
            val dd = "%04d-%02d-%02d".format(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, d2)
            if (d2 == now.get(Calendar.DAY_OF_MONTH)) continue
            if (Wage.dayRecs(dd).isEmpty()) miss++
        }
        if (miss > 0) c.addView(Ui.tv(this, "漏记 " + miss + " 天", 12f, Ui.RED, true))
        col.addView(c)
    }

    /** ④ 班次统计 */
    private fun wageStat(col: LinearLayout, list: List<Wage.Rec>) {
        val c = card("班次统计 · " + Wage.cycleLabel(wageOff))
        val st = Wage.stat(list)
        var srow: LinearLayout? = null
        for (i2 in Wage.SHIFTS.indices) {
            if (i2 % 3 == 0) {
                srow = Ui.row(this)
                srow.setPadding(0, Ui.dp(this, 6f), 0, Ui.dp(this, 6f))
                c.addView(srow)
            }
            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            box.gravity = Gravity.CENTER
            box.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            val n = Ui.tv(this, st[i2].toString() + "天", 18f, Ui.TXT, true)
            n.gravity = Gravity.CENTER
            box.addView(n)
            val nm = Ui.tv(this, Wage.SHIFTS[i2], 12f, Ui.SUB)
            nm.gravity = Gravity.CENTER
            box.addView(nm)
            srow!!.addView(box)
        }
        c.addView(Ui.divider(this))

        val hours = Wage.sumHours(list)
        val pay = Wage.sumPay(list)
        val days = Wage.workDays(list)
        val rows = listOf(
            "出勤天数" to (days.toString() + " 天"),
            "总工时" to (Wage.hh1(hours) + " 小时"),
            "工时收入" to (Wage.money(pay) + " 元"),
            "平均每天" to (Wage.hh1(if (days > 0) hours / days else 0.0) + " 小时"),
            "平均时薪" to (Wage.money(if (hours > 0) pay / hours else 0.0) + " 元/小时")
        )
        for ((k, v) in rows) {
            val r = Ui.row(this)
            r.setPadding(0, Ui.dp(this, 6f), 0, Ui.dp(this, 6f))
            val t1 = Ui.tv(this, k, 14f, Ui.SUB)
            t1.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            r.addView(t1)
            r.addView(Ui.tv(this, v, 15f, Ui.TXT, true))
            c.addView(r)
        }
        col.addView(c)
    }

    /** ⑤ 记月工资 */
    private fun wagePay(
        col: LinearLayout, cyc: String, hours: Double, pay: Double,
        subT: Double, cutT: Double, income: Double, days: Int, nightDays: Int
    ) {
        val big = card(null)
        big.background = Ui.grad(0xFFFDE7E9.toInt(), 0xFFFFFFFF.toInt(), 18, this)
        big.addView(Ui.tv(this, "本周期收入", 14f, Ui.TXT))
        val r0 = Ui.row(this)
        val v0 = Ui.tv(this, Wage.money(income), 30f, Ui.RED, true)
        v0.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        r0.addView(v0)
        r0.addView(Ui.tv(this, "收入 " + Wage.money(pay + subT) + "　扣除 -" + Wage.money(cutT), 13f, Ui.SUB))
        big.addView(r0)
        col.addView(big)

        val c1 = card(null)
        val h1 = Ui.row(this)
        val t1 = Ui.tv(this, "工时收入", 15f, Ui.TXT, true)
        t1.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        h1.addView(t1)
        h1.addView(Ui.tv(this, Wage.money(pay), 17f, Ui.TXT, true))
        c1.addView(h1)
        c1.addView(Ui.tv(this, "小时工资　" + Wage.money(Wage.rate()) + "元 × " + Wage.hh1(hours) + " = " + Wage.money(pay) + "元", 13f, Ui.SUB))
        col.addView(c1)

        col.addView(wageItemCard("补贴项目", Wage.K_SUB, cyc, subT, days, nightDays, Ui.RED))
        col.addView(wageItemCard("扣款项目", Wage.K_CUT, cyc, cutT, days, nightDays, 0xFFFF9500.toInt()))
        col.addView(wageItemCard("其他项目", Wage.K_OTH, cyc, 0.0, days, nightDays, 0xFF34C759.toInt()))

        val cE = card(null)
        val rE = Ui.row(this)
        val a = Ui.tv(this, "应发合计", 15f, Ui.TXT, true)
        a.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        rE.addView(a)
        rE.addView(Ui.tv(this, Wage.money(income) + " 元", 18f, Ui.RED, true))
        cE.addView(rE)
        cE.addView(Ui.tv(this, "= 工时收入 + 补贴 − 扣款 − 其他", 12f, Ui.SUB))
        col.addView(cE)
    }

    private fun wageItemCard(
        title: String, kind: String, cyc: String, total: Double, days: Int, nightDays: Int, dot: Int
    ): LinearLayout {
        val c = card(null)
        val head = Ui.row(this)
        val d = View(this)
        d.background = Ui.round(dot, 5, this)
        d.layoutParams = LinearLayout.LayoutParams(Ui.dp(this, 10f), Ui.dp(this, 10f))
        head.addView(d)
        head.addView(Ui.space(this, 8))
        val t = Ui.tv(this, title, 15f, Ui.TXT, true)
        t.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        head.addView(t)
        head.addView(Ui.tv(this, Wage.money(total), 16f, Ui.TXT, true))
        c.addView(head)

        val items = Wage.items(kind, cyc)
        for ((i2, it2) in items.withIndex()) {
            val r = Ui.row(this)
            r.setPadding(0, Ui.dp(this, 7f), 0, Ui.dp(this, 7f))
            val nm = Ui.tv(
                this,
                it2.name + (if (it2.perDay) " ｜ " + Wage.money(it2.amount) + "元/天" else ""),
                14f, Ui.TXT
            )
            nm.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            val useDays = if (it2.name.contains("夜班")) nightDays else days
            val v = if (it2.perDay) it2.amount * useDays else it2.amount
            val vTv = Ui.tv(this, Wage.money(v) + (if (it2.perDay) "（" + useDays + "天）" else ""), 14f, Ui.SUB)
            vTv.isClickable = true
            vTv.setOnClickListener {
                editRow(
                    it2.name,
                    listOf("金额（元；按天算就写 12/天）"),
                    { vals ->
                        val p = parseAmt(vals[0])
                        val l = Wage.items(kind, cyc)
                        l[i2] = Wage.Item(it2.name, p.first, p.second)
                        Wage.saveItems(kind, cyc, l)
                        show(5)
                    })
            }
            r.addView(nm)
            r.addView(vTv)
            r.addView(xBtn {
                val l = Wage.items(kind, cyc)
                if (i2 in l.indices) {
                    l.removeAt(i2)
                    Wage.saveItems(kind, cyc, l)
                }
                show(5)
            })
            c.addView(r)
            if (i2 != items.size - 1) c.addView(Ui.divider(this))
        }
        val bAdd = Ui.btn(this, "＋ 添加" + title.replace("项目", ""), false, true)
        bAdd.setOnClickListener {
            editRow(
                "添加" + title,
                listOf("名称，例如 夜班补贴", "金额（元；按天算就写 12/天）"),
                { vals ->
                    if (vals[0].trim().isNotEmpty()) {
                        val p = parseAmt(if (vals.size > 1) vals[1] else "0")
                        val l = Wage.items(kind, cyc)
                        l.add(Wage.Item(vals[0].trim(), p.first, p.second))
                        Wage.saveItems(kind, cyc, l)
                        show(5)
                    }
                })
        }
        c.addView(bAdd)
        return c
    }

    /** 某天的记录面板：班次 + 工作时长 + 小时工资 + 备注（对标记加班的下拉面板） */
    private fun wageDayDialog(date: String) {
        var shift = Wage.dayRecs(date).firstOrNull()?.shift ?: "白班"
        val cyc = Wage.cycleStartDate(wageOff)
        var dlg: AlertDialog? = null

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(Ui.dp(this, 18f), Ui.dp(this, 12f), Ui.dp(this, 18f), Ui.dp(this, 6f))
        col.addView(Ui.tv(this, Wage.md(date) + " " + Wage.weekday(date), 18f, Ui.TXT, true))

        val exist = Wage.dayRecs(date)
        if (exist.isNotEmpty()) {
            col.addView(Ui.sectionTitle(this, "已记（✕ 删除）"))
            for (r in exist) {
                val row = Ui.row(this)
                row.setPadding(0, Ui.dp(this, 4f), 0, Ui.dp(this, 4f))
                val tv = Ui.tv(
                    this,
                    r.shift + " · " + Wage.hh1(Wage.hoursOf(r)) + "时 · " + Wage.money(Wage.payOf(r)) + "元",
                    14f, Ui.TXT
                )
                tv.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                row.addView(tv)
                row.addView(xBtn {
                    Wage.delRec(r.id)
                    dlg?.dismiss()
                    wageDayDialog(date)
                })
                col.addView(row)
            }
        }

        col.addView(Ui.sectionTitle(this, "选择班次"))
        val chips = mutableListOf<Pair<String, TextView>>()
        var srow: LinearLayout? = null
        for ((i2, s) in Wage.SHIFTS.withIndex()) {
            if (i2 % 3 == 0) {
                srow = Ui.row(this)
                srow.setPadding(0, Ui.dp(this, 3f), 0, Ui.dp(this, 3f))
                col.addView(srow)
            }
            val t = chip(s, s == shift) {
                shift = s
                for ((n, v) in chips) chipStyle(v, n == shift)
            }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.rightMargin = Ui.dp(this, 6f)
            t.layoutParams = lp
            chips.add(Pair(s, t))
            srow!!.addView(t)
        }

        col.addView(Ui.sectionTitle(this, "工作时长"))
        val eH = numEdit("例如 11 或 11.5", true)
        col.addView(eH)
        val vals = (0..35).map { it * 0.5 }
        var idx = 0
        while (idx < vals.size) {
            val row = Ui.row(this)
            for (cnum in 0 until 6) {
                if (idx >= vals.size) {
                    val sp2 = View(this)
                    sp2.layoutParams = LinearLayout.LayoutParams(0, Ui.dp(this, 34f), 1f)
                    row.addView(sp2)
                    continue
                }
                val v = vals[idx]
                idx++
                val t = chip(if (v % 1.0 == 0.0) v.toInt().toString() else v.toString(), false) {
                    eH.setText(v.toString())
                }
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                lp.rightMargin = Ui.dp(this, 4f)
                lp.topMargin = Ui.dp(this, 4f)
                t.layoutParams = lp
                row.addView(t)
            }
            col.addView(row)
        }

        val eRate = numEdit("小时工资，例如 16（现在 " + Wage.money(Wage.rate()) + "）", true)
        val eNote = edit("备注（可空）")
        col.addView(eRate)
        col.addView(eNote)

        val sv = ScrollView(this)
        sv.addView(col)
        val d = AndroidDialogWrap(sv)
        val builder = AlertDialog.Builder(this)
        builder.setView(d)
        builder.setNegativeButton("取消", null)
        builder.setPositiveButton("保存") { _, _ ->
            val h = eH.text.toString().toDoubleOrNull() ?: -1.0
            if (h < 0) {
                toast("先填工作时长")
            } else {
                val r = eRate.text.toString().toDoubleOrNull() ?: Wage.rate()
                Wage.setRate(r)
                Wage.addRec(Wage.Rec("", date, "", "", r, 0, 0, shift, eNote.text.toString().trim(), h))
                toast("已保存 " + Wage.hh1(h) + " 小时")
                show(5)
            }
        }
        dlg = builder.create()
        dlg.show()
    }

    private fun AndroidDialogWrap(v: View): View {
        val sc = ScrollView(this)
        sc.addView(v)
        return sc
    }

    /** ⑥ 考勤：迟到 / 早退 / 缺勤 / 请假 / 加班，全靠工时记录自动算 */
    private fun wageAttend(col: LinearLayout) {
        val startD = Wage.cycleStartDate(wageOff)
        val endD = Wage.cycleEndDate(wageOff)
        val todayS = Wage.today()
        var lateN = 0
        var earlyN = 0
        var absentN = 0
        var leaveN = 0
        var restN = 0
        var otHours = 0.0
        val rows = mutableListOf<Triple<String, String, String>>()
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        val cal = Calendar.getInstance()
        fmt.parse(startD)?.let { cal.time = it }
        val endCal = Calendar.getInstance()
        fmt.parse(endD)?.let { endCal.time = it }
        while (!cal.after(endCal)) {
            val date = fmt.format(cal.time)
            val workday = Wage.isWorkday(date)
            val rs = Wage.dayRecs(date)
            var st: String
            var detail = ""
            if (rs.isNotEmpty()) {
                detail = rs.joinToString("　") {
                    if (it.start.isNotEmpty()) it.start + "-" + it.end else Wage.hh1(it.hours) + "小时"
                }
            }
            if (rs.isEmpty()) {
                st = if (workday && date < todayS) "缺勤" else "—"
                if (st == "缺勤") absentN++
            } else if (rs.any { it.shift == "休息" }) {
                st = "休息"; restN++
            } else if (rs.any { it.shift == "请假" }) {
                st = "请假"; leaveN++
            } else {
                val ws = Wage.hhmm(Wage.workStart())
                val we = Wage.hhmm(Wage.workEnd())
                var late = 0
                var early = 0
                var ot = 0
                for (r in rs) {
                    if (r.start.isNotEmpty()) {
                        val s = Wage.hhmm(r.start)
                        if (ws != null && s != null && s > ws) late = maxOf(late, s - ws)
                    }
                    if (r.end.isNotEmpty()) {
                        val e = Wage.hhmm(r.end)
                        if (we != null && e != null && e < we) early = maxOf(early, we - e)
                    }
                    val std = if (ws != null && we != null) we - ws else 0
                    val ex = (Wage.hoursOf(r) * 60).toInt() - std
                    if (ex > 0) ot += ex
                }
                if (late > 0) lateN++
                if (early > 0) earlyN++
                otHours += ot / 60.0
                st = when {
                    late > 0 && early > 0 -> "迟到" + late + "分·早退" + early + "分"
                    late > 0 -> "迟到 " + late + " 分"
                    early > 0 -> "早退 " + early + " 分"
                    ot >= 30 -> "加班 " + Wage.hh1(ot / 60.0) + " 小时"
                    else -> "正常"
                }
            }
            rows.add(Triple(date, st, detail))
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }

        val cs = card("考勤设置（用来判断迟到 / 早退）")
        val eA = edit("规定上班时间，例如 08:00（现在 " + Wage.workStart() + "）")
        val eB = edit("规定下班时间，例如 17:00（现在 " + Wage.workEnd() + "）")
        cs.addView(eA)
        cs.addView(eB)
        val bS = Ui.btn(this, "保存考勤时间")
        bS.setOnClickListener {
            var a = Wage.workStart()
            var b = Wage.workEnd()
            if (Wage.hhmm(eA.text.toString()) != null) a = eA.text.toString().trim()
            if (Wage.hhmm(eB.text.toString()) != null) b = eB.text.toString().trim()
            Wage.setWorkTime(a, b)
            toast("已保存")
            show(5)
        }
        cs.addView(bS)
        col.addView(cs)

        val c = card("考勤汇总 · " + Wage.cycleLabel(wageOff))
        val attDays = Wage.workDays(Wage.recsIn(wageOff))
        val pairs = listOf(
            "出勤" to attDays, "迟到" to lateN, "早退" to earlyN,
            "缺勤" to absentN, "请假" to leaveN, "休息" to restN
        )
        var srow: LinearLayout? = null
        for ((i2, p) in pairs.withIndex()) {
            if (i2 % 3 == 0) {
                srow = Ui.row(this)
                srow.setPadding(0, Ui.dp(this, 6f), 0, Ui.dp(this, 6f))
                c.addView(srow)
            }
            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            box.gravity = Gravity.CENTER
            box.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            val n = Ui.tv(this, p.second.toString() + "天", 18f, if (p.second > 0 && (p.first == "迟到" || p.first == "早退" || p.first == "缺勤")) Ui.RED else Ui.TXT, true)
            n.gravity = Gravity.CENTER
            box.addView(n)
            val nm = Ui.tv(this, p.first, 12f, Ui.SUB)
            nm.gravity = Gravity.CENTER
            box.addView(nm)
            srow!!.addView(box)
        }
        c.addView(Ui.divider(this))
        c.addView(Ui.tv(this, "加班合计 " + Wage.hh1(otHours) + " 小时", 14f, Ui.TXT))
        val perfect = lateN == 0 && earlyN == 0 && absentN == 0
        c.addView(Ui.tv(this, if (perfect) "全勤 ✓（没有迟到/早退/缺勤）" else "不是全勤：迟到 " + lateN + " 次、早退 " + earlyN + " 次、缺勤 " + absentN + " 天", 13f, if (perfect) 0xFF34C759.toInt() else Ui.RED, true))
        col.addView(c)

        val cl = card("每天考勤（点一行改记录）")
        for ((i2, t) in rows.withIndex()) {
            val row = Ui.row(this)
            row.setPadding(0, Ui.dp(this, 7f), 0, Ui.dp(this, 7f))
            val left = LinearLayout(this)
            left.orientation = LinearLayout.VERTICAL
            left.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            left.addView(Ui.tv(this, Wage.md(t.first) + " " + Wage.weekday(t.first), 14f, Ui.TXT))
            if (t.third.isNotEmpty()) left.addView(Ui.tv(this, t.third, 12f, Ui.SUB))
            row.addView(left)
            row.addView(Ui.tv(this, t.second, 13f, if (t.second == "正常") 0xFF34C759.toInt() else if (t.second == "—" || t.second == "休息") Ui.SUB else Ui.RED, true))
            row.isClickable = true
            row.setOnClickListener { wageDayDialog(t.first) }
            cl.addView(row)
            if (i2 != rows.size - 1) cl.addView(Ui.divider(this))
        }
        col.addView(cl)
    }

    // ==================== 多功能记账 ====================
    private var bookTab = 0            // 0 记一笔 / 1 明细 / 2 统计 / 3 预算
    private var bookOff = 0            // 月份偏移

    private fun screenBook(): View {
        val (sv, col) = page("记账")
        val ym = Book.month(bookOff)
        val mlist = Book.inMonth(Book.recs(), ym)
        val out = Book.sumOut(mlist)
        val inn = Book.sumIn(mlist)
        val left = inn - out


        val head = LinearLayout(this)
        head.orientation = LinearLayout.VERTICAL
        head.background = Ui.grad(0xFF2C2C2E.toInt(), 0xFF48484A.toInt(), 18, this)
        head.setPadding(Ui.dp(this, 16f), Ui.dp(this, 14f), Ui.dp(this, 16f), Ui.dp(this, 16f))
        val hlp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        hlp.bottomMargin = Ui.dp(this, 14f)
        head.layoutParams = hlp

        val prow = Ui.row(this)
        val bPrev = Ui.tv(this, "‹ 上月", 13f, 0xDDFFFFFF.toInt())
        val bNext = Ui.tv(this, "下月 ›", 13f, 0xDDFFFFFF.toInt())
        bPrev.isClickable = true
        bNext.isClickable = true
        bPrev.setOnClickListener { bookOff -= 1; show(6) }
        bNext.setOnClickListener { bookOff += 1; show(6) }
        val mid = Ui.tv(this, ym.replace("-", " 年 ") + " 月", 13f, Color.WHITE, true)
        mid.gravity = Gravity.CENTER
        mid.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        prow.addView(bPrev)
        prow.addView(mid)
        prow.addView(bNext)
        head.addView(prow)

        head.addView(Ui.tv(this, "本月支出", 13f, 0xCCFFFFFF.toInt()))
        val big = Ui.tv(this, Book.money(out), 34f, Color.WHITE, true)
        big.setPadding(0, Ui.dp(this, 2f), 0, Ui.dp(this, 8f))
        head.addView(big)
        val r2 = Ui.row(this)
        val t1 = Ui.tv(this, "收入 " + Book.money(inn), 13f, 0xE6FFFFFF.toInt())
        t1.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        r2.addView(t1)
        r2.addView(Ui.tv(this, "结余 " + Book.money(left), 13f, if (left < 0) 0xFFFFB4AB.toInt() else 0xFF9BE7A8.toInt()))
        head.addView(r2)
        col.addView(head)

        val names = listOf("记一笔", "明细", "统计", "预算")
        val seg = Ui.row(this)
        val slp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        slp.bottomMargin = Ui.dp(this, 12f)
        seg.layoutParams = slp
        for (i2 in names.indices) {
            val t = chip(names[i2], bookTab == i2) { bookTab = i2; show(6) }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.leftMargin = if (i2 == 0) 0 else Ui.dp(this, 4f)
            t.layoutParams = lp
            seg.addView(t)
        }
        col.addView(seg)

        when (bookTab) {
            1 -> bookDetail(col, mlist)
            2 -> bookStat(col, ym)
            3 -> bookBudget(col, ym, out)
            else -> bookRecord(col)
        }
        return sv
    }

    /** 记一笔 */
    private fun bookRecord(col: LinearLayout) {
        val c = card("记一笔")
        var income = false
        var cat = Book.cats(false).firstOrNull() ?: "餐饮"

        val typeRow = Ui.row(this)
        val tOut = chip("支出", true) { }
        val tIn = chip("收入", false) { }
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp.rightMargin = Ui.dp(this, 6f)
        tOut.layoutParams = lp
        tIn.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        typeRow.addView(tOut)
        typeRow.addView(tIn)
        c.addView(typeRow)

        val eAmt = numEdit("金额，例如 25.5", true)
        val eDate = edit("日期，例如 " + Book.today() + "（默认今天）")
        val eNote = edit("备注（可空）")
        c.addView(eAmt)

        c.addView(Ui.sectionTitle(this, "分类"))
        val catBox = LinearLayout(this)
        catBox.orientation = LinearLayout.VERTICAL
        c.addView(catBox)
        val chips = mutableListOf<Pair<String, TextView>>()
        fun renderCats() {
            catBox.removeAllViews()
            chips.clear()
            var row: LinearLayout? = null
            val list = Book.cats(income)
            for ((i2, name) in list.withIndex()) {
                if (i2 % 3 == 0) {
                    row = Ui.row(this)
                    row.setPadding(0, Ui.dp(this, 3f), 0, Ui.dp(this, 3f))
                    catBox.addView(row)
                }
                val t = chip(name, name == cat) {
                    cat = name
                    for ((n, v) in chips) chipStyle(v, n == cat)
                }
                val l2 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                l2.rightMargin = Ui.dp(this, 6f)
                t.layoutParams = l2
                chips.add(Pair(name, t))
                row!!.addView(t)
            }
        }
        renderCats()
        tOut.setOnClickListener {
            income = false
            cat = Book.cats(false).firstOrNull() ?: "其他"
            chipStyle(tOut, true)
            chipStyle(tIn, false)
            renderCats()
        }
        tIn.setOnClickListener {
            income = true
            cat = Book.cats(true).firstOrNull() ?: "工资"
            chipStyle(tOut, false)
            chipStyle(tIn, true)
            renderCats()
        }

        c.addView(eDate)
        val rowD = Ui.row(this)
        val cT = chip("今天", true) { eDate.setText(Book.today()) }
        val cY = chip("昨天", false) { eDate.setText(Book.d(1)) }
        val cQ = chip("前天", false) { eDate.setText(Book.d(2)) }
        val l3 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        l3.rightMargin = Ui.dp(this, 6f)
        cT.layoutParams = l3
        cY.layoutParams = l3
        cQ.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        rowD.addView(cT)
        rowD.addView(cY)
        rowD.addView(cQ)
        rowD.setPadding(0, Ui.dp(this, 6f), 0, 0)
        c.addView(rowD)

        c.addView(eNote)
        val b = Ui.btn(this, "保存这一笔")
        b.setOnClickListener {
            val amt = eAmt.text.toString().toDoubleOrNull() ?: 0.0
            if (amt <= 0) {
                toast("先填金额")
            } else {
                val date = if (eDate.text.isNullOrEmpty()) Book.today() else eDate.text.toString().trim()
                if (Wage.parse(date) == null) {
                    toast("日期写成 2026-09-19 这样")
                } else {
                    Book.add(Book.Rec("", date, income, cat, amt, eNote.text.toString().trim()))
                    toast((if (income) "收入 " else "支出 ") + Book.money(amt) + " 已记")
                    bookTab = 1
                    show(6)
                }
            }
        }
        c.addView(b)
        col.addView(c)
    }

    /** 明细 */
    private fun bookDetail(col: LinearLayout, mlist: List<Book.Rec>) {
        val c = card("本月明细（点一行改 / ✕ 删）")
        if (mlist.isEmpty()) c.addView(Ui.tv(this, "这个月还没记账", 14f, Ui.SUB))
        val dates = mlist.map { it.date }.distinct().sortedDescending()
        for ((i2, date) in dates.withIndex()) {
            if (i2 > 0) c.addView(Ui.divider(this))
            val dr = Ui.row(this)
            dr.setPadding(0, Ui.dp(this, 8f), 0, Ui.dp(this, 4f))
            val dTv = Ui.tv(this, Book.md(date) + " " + Book.weekday(date), 14f, Ui.TXT, true)
            dTv.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            dr.addView(dTv)
            val o = Book.dayOut(date)
            val i3 = Book.dayIn(date)
            dr.addView(Ui.tv(this, (if (o > 0) "支出 " + Book.money(o) + "　" else "") + (if (i3 > 0) "收入 " + Book.money(i3) else ""), 12f, Ui.SUB))
            c.addView(dr)
            for (r in Book.dayOf(date)) {
                val row = Ui.row(this)
                row.setPadding(0, Ui.dp(this, 5f), 0, Ui.dp(this, 5f))
                val left = Ui.tv(this, r.cat + (if (r.note.isNotEmpty()) " · " + r.note else ""), 14f, Ui.TXT)
                left.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                row.addView(left)
                row.addView(Ui.tv(this, (if (r.income) "+" else "-") + Book.money(r.amount), 15f, if (r.income) 0xFF34C759.toInt() else Ui.TXT, true))
                row.addView(xBtn { Book.del(r.id); show(6) })
                row.isClickable = true
                row.setOnClickListener {
                    editRow(
                        r.cat + " " + Book.md(r.date),
                        listOf("金额（现在 " + Book.money(r.amount) + "）", "备注（现在 " + (if (r.note.isEmpty()) "空" else r.note) + "）"),
                        { vals ->
                            vals[0].toDoubleOrNull()?.let { Book.upd(Book.Rec(r.id, r.date, r.income, r.cat, it, if (vals.size > 1) vals[1] else r.note)) }
                            show(6)
                        })
                }
                c.addView(row)
            }
        }
        col.addView(c)
    }

    /** 统计 */
    private fun bookStat(col: LinearLayout, ym: String) {
        val mlist = Book.inMonth(Book.recs(), ym)
        val c0 = card("本月合计")
        val r1 = Ui.row(this)
        val a = Ui.tv(this, "支出", 15f, Ui.TXT)
        a.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        r1.addView(a)
        r1.addView(Ui.tv(this, Book.money(Book.sumOut(mlist)) + " 元", 18f, Ui.RED, true))
        c0.addView(r1)
        val r2 = Ui.row(this)
        val b = Ui.tv(this, "收入", 15f, Ui.TXT)
        b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        r2.addView(b)
        r2.addView(Ui.tv(this, Book.money(Book.sumIn(mlist)) + " 元", 18f, 0xFF34C759.toInt(), true))
        c0.addView(r2)
        val r3 = Ui.row(this)
        val d = Ui.tv(this, "结余", 15f, Ui.TXT)
        d.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        r3.addView(d)
        val bal = Book.sumIn(mlist) - Book.sumOut(mlist)
        r3.addView(Ui.tv(this, Book.money(bal) + " 元", 18f, if (bal < 0) Ui.RED else Ui.TXT, true))
        c0.addView(r3)
        col.addView(c0)

        val cats = Book.byCat(mlist, false)
        if (cats.isNotEmpty()) {
            val c1 = card("支出分类排行")
            val mx = cats.first().second
            for ((i2, p) in cats.withIndex()) {
                val row = Ui.row(this)
                row.setPadding(0, Ui.dp(this, 6f), 0, Ui.dp(this, 6f))
                val nm = Ui.tv(this, p.first, 14f, Ui.TXT)
                nm.layoutParams = LinearLayout.LayoutParams(Ui.dp(this, 56f), ViewGroup.LayoutParams.WRAP_CONTENT)
                row.addView(nm)
                val track = LinearLayout(this)
                track.layoutParams = LinearLayout.LayoutParams(0, Ui.dp(this, 8f), 1f)
                val ratio = if (mx > 0) (p.second / mx).toFloat().coerceAtLeast(0.04f) else 0.04f
                val bar = View(this)
                bar.background = Ui.round(Ui.RED, 4, this)
                bar.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, ratio)
                val rest = View(this)
                rest.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - ratio)
                track.addView(bar)
                track.addView(rest)
                row.addView(track)
                row.addView(Ui.space(this, 8))
                val pct = if (Book.sumOut(mlist) > 0) (p.second * 100 / Book.sumOut(mlist)).toInt() else 0
                row.addView(Ui.tv(this, Book.money(p.second) + "（" + pct + "%）", 12f, Ui.SUB))
                c1.addView(row)
                if (i2 != cats.size - 1) c1.addView(Ui.divider(this))
            }
            col.addView(c1)
        }

        val inc = Book.byCat(mlist, true)
        if (inc.isNotEmpty()) {
            val c2 = card("收入来源")
            for ((i2, p) in inc.withIndex()) {
                val row = Ui.row(this)
                row.setPadding(0, Ui.dp(this, 6f), 0, Ui.dp(this, 6f))
                val nm = Ui.tv(this, p.first, 14f, Ui.TXT)
                nm.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                row.addView(nm)
                row.addView(Ui.tv(this, Book.money(p.second) + " 元", 14f, 0xFF34C759.toInt(), true))
                c2.addView(row)
                if (i2 != inc.size - 1) c2.addView(Ui.divider(this))
            }
            col.addView(c2)
        }

        // 近 14 天柱状（禁饼图）
        val data = Book.lastDaysOut(14)
        val c3 = card("近 14 天支出")
        val mx2 = data.maxOfOrNull { it.second } ?: 0.0
        val chart = Ui.row(this)
        chart.gravity = Gravity.BOTTOM
        for (p in data) {
            val colv = LinearLayout(this)
            colv.orientation = LinearLayout.VERTICAL
            colv.gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            colv.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            val hgt = if (mx2 > 0) (Ui.dp(this, 88f) * (p.second / mx2)).toInt().coerceAtLeast(2) else 2
            val bar = View(this)
            bar.background = Ui.round(if (p.second > 0) Ui.RED else Ui.LINE, 3, this)
            bar.layoutParams = LinearLayout.LayoutParams(Ui.dp(this, 10f), hgt)
            colv.addView(bar)
            val t = Ui.tv(this, p.first.substring(8), 9f, Ui.SUB)
            t.gravity = Gravity.CENTER
            colv.addView(t)
            chart.addView(colv)
        }
        c3.addView(chart)
        c3.addView(Ui.tv(this, "最高 " + Book.money(mx2) + " 元/天", 12f, Ui.SUB))
        col.addView(c3)
    }

    /** 预算 + 分类管理 */
    private fun bookBudget(col: LinearLayout, ym: String, spent: Double) {
        val bud = Book.budget()
        val c = card("本月预算")
        c.addView(Ui.tv(this, "已花 " + Book.money(spent) + " 元" + (if (bud > 0) " / 预算 " + Book.money(bud) + " 元" else "（还没设预算）"), 15f, Ui.TXT))
        if (bud > 0) {
            val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
            pb.max = 100
            pb.progress = ((spent / bud) * 100).toInt().coerceIn(0, 100)
            pb.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 10f))
            c.addView(pb)
            val leftB = bud - spent
            c.addView(
                Ui.tv(
                    this,
                    if (leftB >= 0) "还能花 " + Book.money(leftB) + " 元" else "超支 " + Book.money(-leftB) + " 元！",
                    13f, if (leftB >= 0) 0xFF34C759.toInt() else Ui.RED, true
                )
            )
        }
        val eB = numEdit("预算金额，例如 3000（0 = 不设预算）", true)
        c.addView(eB)
        val bS = Ui.btn(this, "保存预算")
        bS.setOnClickListener {
            eB.text.toString().toDoubleOrNull()?.let { Book.setBudget(it) }
            toast("已保存")
            show(6)
        }
        c.addView(bS)
        col.addView(c)

        for (income in listOf(false, true)) {
            val c2 = card(if (income) "收入分类（可自由增删）" else "支出分类（可自由增删）")
            var row: LinearLayout? = null
            val list = Book.cats(income)
            for ((i2, name) in list.withIndex()) {
                if (i2 % 3 == 0) {
                    row = Ui.row(this)
                    row.setPadding(0, Ui.dp(this, 3f), 0, Ui.dp(this, 3f))
                    c2.addView(row)
                }
                val box = Ui.row(this)
                box.background = Ui.roundStroke(0xFFFFFFFF.toInt(), Ui.LINE, 14, this)
                box.setPadding(Ui.dp(this, 10f), Ui.dp(this, 6f), Ui.dp(this, 4f), Ui.dp(this, 6f))
                val l4 = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                l4.rightMargin = Ui.dp(this, 6f)
                box.layoutParams = l4
                val tv = Ui.tv(this, name, 13f, Ui.TXT)
                tv.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                box.addView(tv)
                val x = Ui.tv(this, "✕", 13f, 0xFFC7C7CC.toInt())
                x.setPadding(Ui.dp(this, 6f), 0, Ui.dp(this, 4f), 0)
                x.isClickable = true
                x.setOnClickListener { Book.delCat(income, name); show(6) }
                box.addView(x)
                row!!.addView(box)
            }
            val bAdd = Ui.btn(this, "＋ 添加" + (if (income) "收入" else "支出") + "分类", false, true)
            bAdd.setOnClickListener {
                editRow("添加分类", listOf("分类名称，例如 通勤"), { vals ->
                    if (vals[0].trim().isNotEmpty()) {
                        Book.addCat(income, vals[0])
                        show(6)
                    }
                })
            }
            c2.addView(bAdd)
            col.addView(c2)
        }

        val c3 = card(null)
        c3.addView(Ui.tv(this, "记账数据跟着「设置 → 导出备份」一起存，换手机不丢。\n统计只做柱状/排行（没有饼图），和你要的一致。", 12f, Ui.SUB))
        col.addView(c3)
    }

    // ==================== 统计 / 月度 / 历史 ====================
    private var statOff = 0        // 月份偏移
    private var statMetric = 0     // 0 打卡率 1 睡眠 2 番茄 3 工时 4 支出

    private fun monthDays(off: Int): List<String> {
        val c = Calendar.getInstance()
        c.add(Calendar.MONTH, off)
        c.set(Calendar.DAY_OF_MONTH, 1)
        val y = c.get(Calendar.YEAR)
        val m = c.get(Calendar.MONTH) + 1
        val n = c.getActualMaximum(Calendar.DAY_OF_MONTH)
        return (1..n).map { "%04d-%02d-%02d".format(y, m, it) }
    }

    private fun screenStat(): View {
        val (sv, col) = page("统计")
        val days = monthDays(statOff)
        val ym = days[0].substring(0, 7)
        val today = Store.day()
        val isCur = ym == today.substring(0, 7)

        val hs = Store.habits()
        val allS = Store.sleeps()
        val allW = Wage.recs()
        val allB = Book.recs()

        // 预统计（按天）
        val habitByDay = mutableMapOf<String, Int>()
        for (h in hs) for (d in h.days) habitByDay[d] = (habitByDay[d] ?: 0) + 1
        val sleepByDay = mutableMapOf<String, Int>()
        for (s in allS) sleepByDay[s.day] = (sleepByDay[s.day] ?: 0) + s.minutes
        val wHourByDay = mutableMapOf<String, Double>()
        val wPayByDay = mutableMapOf<String, Double>()
        for (r in allW) {
            wHourByDay[r.date] = (wHourByDay[r.date] ?: 0.0) + Wage.hoursOf(r)
            wPayByDay[r.date] = (wPayByDay[r.date] ?: 0.0) + Wage.payOf(r)
        }
        val outByDay = mutableMapOf<String, Double>()
        val inByDay = mutableMapOf<String, Double>()
        for (r in allB) {
            if (r.income) inByDay[r.date] = (inByDay[r.date] ?: 0.0) + r.amount
            else outByDay[r.date] = (outByDay[r.date] ?: 0.0) + r.amount
        }

        fun sumOver(dl: List<String>, f: (String) -> Double): Double {
            var t = 0.0
            for (d in dl) t += f(d)
            return t
        }

        val passed = if (isCur) days.count { it <= today } else days.size
        val should = (hs.size * passed).coerceAtLeast(1)
        val doneN = sumOver(days) { (habitByDay[it] ?: 0).toDouble() }.toInt()
        val rate = doneN * 100 / should
        val sleepH = sumOver(days) { (sleepByDay[it] ?: 0) / 60.0 }
        val pomoN = sumOver(days) { Store.pomoOn(it).toDouble() }.toInt()
        val wHours = sumOver(days) { wHourByDay[it] ?: 0.0 }
        val wPay = sumOver(days) { wPayByDay[it] ?: 0.0 }
        val bOut = sumOver(days) { outByDay[it] ?: 0.0 }
        val bIn = sumOver(days) { inByDay[it] ?: 0.0 }

        // ---- 头部 ----
        val head = LinearLayout(this)
        head.orientation = LinearLayout.VERTICAL
        head.background = Ui.grad(0xFF1C1C1E.toInt(), 0xFF3A3A3C.toInt(), 18, this)
        head.setPadding(Ui.dp(this, 16f), Ui.dp(this, 14f), Ui.dp(this, 16f), Ui.dp(this, 16f))
        val hlp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        hlp.bottomMargin = Ui.dp(this, 14f)
        head.layoutParams = hlp

        val prow = Ui.row(this)
        val bPrev = Ui.tv(this, "‹ 上月", 13f, 0xDDFFFFFF.toInt())
        val bNext = Ui.tv(this, "下月 ›", 13f, 0xDDFFFFFF.toInt())
        bPrev.isClickable = true
        bNext.isClickable = true
        bPrev.setOnClickListener { statOff -= 1; show(7) }
        bNext.setOnClickListener { statOff += 1; show(7) }
        val mid = Ui.tv(this, "%s 年 %s 月".format(ym.substring(0, 4), ym.substring(5, 7)), 14f, Color.WHITE, true)
        mid.gravity = Gravity.CENTER
        mid.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        prow.addView(bPrev)
        prow.addView(mid)
        prow.addView(bNext)
        head.addView(prow)

        fun bigRow(items: List<Pair<String, String>>) {
            val r = Ui.row(this)
            for ((k, v) in items) {
                val box = LinearLayout(this)
                box.orientation = LinearLayout.VERTICAL
                box.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                box.setPadding(0, Ui.dp(this, 8f), 0, 0)
                val t1 = Ui.tv(this, v, 19f, Color.WHITE, true)
                t1.gravity = Gravity.CENTER
                box.addView(t1)
                val t2 = Ui.tv(this, k, 11f, 0xAAFFFFFF.toInt())
                t2.gravity = Gravity.CENTER
                box.addView(t2)
                r.addView(box)
            }
            head.addView(r)
        }
        bigRow(
            listOf(
                Pair("打卡完成率", rate.toString() + "%"),
                Pair("睡眠", Wage.hh1(sleepH) + "时"),
                Pair("番茄", pomoN.toString() + "个")
            )
        )
        bigRow(
            listOf(
                Pair("工时", Wage.hh1(wHours) + "时"),
                Pair("工时收入", Wage.money(wPay)),
                Pair("加班/补贴", Wage.money(wPay))
            )
        )
        bigRow(
            listOf(
                Pair("支出", Book.money(bOut)),
                Pair("收入", Book.money(bIn)),
                Pair("结余", Book.money(bIn - bOut))
            )
        )
        col.addView(head)

        // ---- 指标趋势（整月） ----
        val names = listOf("打卡率", "睡眠", "番茄", "工时", "支出")
        val seg = HorizontalScrollView(this)
        seg.isHorizontalScrollBarEnabled = false
        val segRow = Ui.row(this)
        seg.addView(segRow)
        val slp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        slp.bottomMargin = Ui.dp(this, 10f)
        seg.layoutParams = slp
        for (i2 in names.indices) {
            val t = chip(names[i2], statMetric == i2) { statMetric = i2; show(7) }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.rightMargin = Ui.dp(this, 6f)
            t.layoutParams = lp
            segRow.addView(t)
        }
        col.addView(seg)

        val vals: List<Float> = when (statMetric) {
            0 -> days.map { (habitByDay[it] ?: 0) * 100f / hs.size.coerceAtLeast(1) }
            1 -> days.map { (sleepByDay[it] ?: 0) / 60f }
            2 -> days.map { Store.pomoOn(it).toFloat() }
            3 -> days.map { (wHourByDay[it] ?: 0.0).toFloat() }
            else -> days.map { (outByDay[it] ?: 0.0).toFloat() }
        }
        val unit = when (statMetric) {
            0 -> "%"
            1 -> "小时"
            2 -> "个"
            3 -> "小时"
            else -> "元"
        }
        val labels = days.mapIndexed { i2, _ -> if (i2 == 0 || (i2 + 1) % 5 == 0 || i2 == days.size - 1) (i2 + 1).toString() else "" }
        val cT = card("本月每天 · " + names[statMetric])
        val tv = TrendView(this, vals, labels, unit)
        tv.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 160f))
        cT.addView(tv)
        val sumV = vals.sum().toDouble()
        cT.addView(
            Ui.tv(
                this,
                "合计 " + (if (statMetric == 0) rate.toString() + "%（月完成率）" else Wage.hh1(sumV) + " " + unit) +
                        "　最高 " + Wage.hh1((vals.maxOrNull() ?: 0f).toDouble()) + " " + unit,
                12f, Ui.SUB
            )
        )
        col.addView(cT)

        // ---- 习惯打卡（本月） ----
        val cH = card("习惯打卡（本月）")
        if (hs.isEmpty()) cH.addView(Ui.tv(this, "还没有习惯", 14f, Ui.SUB))
        for ((i2, h) in hs.withIndex()) {
            val n = h.days.count { it.startsWith(ym) }
            val row = Ui.row(this)
            row.setPadding(0, Ui.dp(this, 6f), 0, Ui.dp(this, 6f))
            val nm = Ui.tv(this, h.name, 14f, Ui.TXT)
            nm.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(nm)
            row.addView(Ui.tv(this, n.toString() + " 天", 14f, Ui.RED, true))
            cH.addView(row)
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
            bar.max = days.size
            bar.progress = n
            bar.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 6f))
            cH.addView(bar)
            if (i2 != hs.size - 1) cH.addView(Ui.divider(this))
        }
        col.addView(cH)

        // ---- 月度对比 ----
        val pdays = monthDays(statOff - 1)
        val pOut = sumOver(pdays) { outByDay[it] ?: 0.0 }
        val pIn = sumOver(pdays) { inByDay[it] ?: 0.0 }
        val pW = sumOver(pdays) { wHourByDay[it] ?: 0.0 }
        val pSleep = sumOver(pdays) { (sleepByDay[it] ?: 0) / 60.0 }
        val cC = card("对比上月")
        val cmp = listOf(
            "支出" to Pair(bOut, pOut),
            "收入" to Pair(bIn, pIn),
            "工时" to Pair(wHours, pW),
            "睡眠" to Pair(sleepH, pSleep)
        )
        for ((i2, item) in cmp.withIndex()) {
            val row = Ui.row(this)
            row.setPadding(0, Ui.dp(this, 7f), 0, Ui.dp(this, 7f))
            val nm = Ui.tv(this, item.first, 14f, Ui.SUB)
            nm.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            row.addView(nm)
            val nowV = item.second.first
            val lastV = item.second.second
            val diff = nowV - lastV
            val arrow = if (diff > 0.01) "↑" else if (diff < -0.01) "↓" else "—"
            val col2 = if (diff > 0.01) Ui.RED else if (diff < -0.01) 0xFF34C759.toInt() else Ui.SUB
            row.addView(Ui.tv(this, Wage.hh1(nowV) + "　上月 " + Wage.hh1(lastV) + "　" + arrow, 13f, col2))
            cC.addView(row)
            if (i2 != cmp.size - 1) cC.addView(Ui.divider(this))
        }
        col.addView(cC)

        // ---- 历史（每天一行） ----
        val cL = card("历史（本月每天）")
        for ((i2, d) in days.reversed().withIndex()) {
            val hDone = habitByDay[d] ?: 0
            val sl = (sleepByDay[d] ?: 0) / 60.0
            val pm = Store.pomoOn(d)
            val wh = wHourByDay[d] ?: 0.0
            val out = outByDay[d] ?: 0.0
            val inn = inByDay[d] ?: 0.0
            val hasAny = hDone > 0 || sl > 0 || pm > 0 || wh > 0 || out > 0 || inn > 0
            val row = Ui.row(this)
            row.setPadding(0, Ui.dp(this, 8f), 0, Ui.dp(this, 8f))
            val left = LinearLayout(this)
            left.orientation = LinearLayout.VERTICAL
            left.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            left.addView(Ui.tv(this, Wage.md(d) + " " + Wage.weekday(d), 14f, if (d == today) Ui.RED else Ui.TXT, d == today))
            val sb = StringBuilder()
            if (hDone > 0) sb.append("打卡 ").append(hDone).append("/").append(hs.size).append("　")
            if (sl > 0) sb.append("睡 ").append(Wage.hh1(sl)).append("时　")
            if (pm > 0) sb.append("番茄 ").append(pm).append("　")
            if (wh > 0) sb.append("工时 ").append(Wage.hh1(wh)).append("时　")
            if (out > 0) sb.append("支出 ").append(Book.money(out)).append("　")
            if (inn > 0) sb.append("收入 ").append(Book.money(inn))
            left.addView(Ui.tv(this, if (hasAny) sb.toString().trim() else "没有记录", 12f, Ui.SUB))
            row.addView(left)
            row.addView(Ui.tv(this, if (hasAny) "●" else "○", 12f, if (hasAny) Ui.RED else 0xFFC7C7CC.toInt()))
            cL.addView(row)
            if (i2 != days.size - 1) cL.addView(Ui.divider(this))
        }
        col.addView(cL)

        val cE = card(null)
        val bCsv = Ui.btn(this, "导出记账明细 CSV（Excel 可打开）", false)
        bCsv.setOnClickListener { exportBookCsv() }
        cE.addView(bCsv)
        cE.addView(Ui.tv(this, "统计一律柱状/折线，没有饼图。数据全部本地，跟着「导出备份」一起备份。", 12f, Ui.SUB))
        col.addView(cE)
        return sv
    }

    private fun exportBookCsv() {
        val recs = Book.recs()
        if (recs.isEmpty()) {
            toast("还没有记账")
            return
        }
        val sb = StringBuilder("日期,类型,分类,金额,备注\n")
        for (r in recs) {
            val note = if (r.note.contains(',')) "\"" + r.note + "\"" else r.note
            sb.append(r.date).append(',').append(if (r.income) "收入" else "支出").append(',')
                .append(r.cat).append(',').append(Book.money(r.amount)).append(',').append(note).append('\n')
        }
        val name = "记账明细-" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.CHINA).format(Date()) + ".csv"
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/csv")
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/棂打卡")
                }
                val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { it.write(("\uFEFF" + sb.toString()).toByteArray()) }
                    toast("已导出「下载/棂打卡/" + name + "」")
                    return
                }
            }
            val dir = java.io.File(getExternalFilesDir(null), "backup")
            if (!dir.exists()) dir.mkdirs()
            java.io.File(dir, name).writeText(sb.toString())
            toast("已导出到 App 目录")
        } catch (e: Exception) {
            toast("导出失败：" + (e.message ?: ""))
        }
    }

    private fun parseHhmm(s: String): Pair<Int, Int>? {
        val parts = s.trim().replace("：", ":").split(":")
        if (parts.size < 2) return null
        val hh = parts[0].toIntOrNull() ?: return null
        val mm = parts[1].toIntOrNull() ?: return null
        if (hh < 0 || hh > 23 || mm < 0 || mm > 59) return null
        return Pair(hh, mm)
    }
}
