package com.naling.shuai

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 棂记 · 首页纪念日卡片（2026-09-21 纳棂定）
 *  - 两列卡片：背景图（可自定义）+ 标题 + 大数字 + 单位
 *  - 每张卡可编辑：标题 / 日期 / 模式(倒计时·已过去) / 背景图 / 高度(矮·中·高)
 *  - 增删自由，数据存 SharedPreferences(JSON)，背景图存 app 私有目录
 *  - 兼容 minSdk 24：不用 java.time，全部用 Calendar + 毫秒差算天数
 */
object Cards {

    private const val KEY = "home_cards"
    private const val DAY = 86_400_000L
    private val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

    class C(
        var id: Long,
        var title: String,
        var date: String,        // yyyy-MM-dd（fn>=0 时忽略）
        var mode: String,        // "down"=倒计时 / "up"=已过去
        var bg: String?,         // 图片绝对路径，null=纯色渐变
        var h: Int,              // 0 矮 / 1 中 / 2 高
        var fn: Int = -1,        // >=0 = 功能卡（页面 id）
        var src: Int = 0,        // 图表卡数据源：0睡眠 1打卡率 2番茄 3记账
        var ct: Int = -1,        // 图表类型：-1非图表 0折线 1柱状 2饼图 3表格
        var color: Int = -1,     // 卡片底色（-1 = 默认浅色渐变）
        var rep: Boolean = false, // 每年重复（生日/节日）
        var size: Int = 2,       // 1=单格 / 2=半宽 / 4=整行（图表卡固定 4）
        var plug: String? = null // 插件卡：插件 id（v2.43 纳棂：让用户自己加小卡片）
    )

    // ---------- 存取 ----------
    fun load(): MutableList<C> {
        val sp = Store.prefs()
        val raw = sp.getString(KEY, null)
        val out = mutableListOf<C>()
        if (raw != null) {
            try {
                val a = JSONArray(raw)
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    out.add(C(o.optLong("id"), o.optString("title", "纪念日"),
                        o.optString("date", today()), o.optString("mode", "down"),
                        if (o.isNull("bg")) null else o.optString("bg"), o.optInt("h", 1),
                        o.optInt("fn", -1), o.optInt("src", 0), o.optInt("ct", -1),
                        o.optInt("color", -1), o.optBoolean("rep", false), o.optInt("size", 2),
                        if (o.isNull("plug")) null else o.optString("plug", null)))
                }
            } catch (_: Exception) {
            }
        }
        if (out.isEmpty()) {
            out.addAll(defaults())
            save(out)
        }
        if (out.none { it.fn >= 0 }) {
            var k = 0L
            for ((id, label) in FN_CARDS) {
                out.add(C(System.currentTimeMillis() + k, label, today(), "down", null, 1, id, 0, -1,
                    STRONG[(k.toInt() + 2) % STRONG.size], false))
                k++
            }
            save(out)
        }
        if (out.none { it.ct >= 0 }) {
            out.add(C(System.currentTimeMillis() + 100, "睡眠时长(近7天)", today(), "down", null, 2, -1, 0, 1,
                PALETTE[3], false))
            save(out)
        }
        // 旧卡片自动补色 + 图表卡标题规范化 + 去重 + 首次把睡眠图表挪到最上
        var idx = 0
        var dirty = false
        val seen = HashSet<Int>()
        val fixed = mutableListOf<C>()
        for (c in out) {
            if (c.fn >= 0) {
                if (seen.contains(c.fn)) {
                    dirty = true
                    continue
                }
                seen.add(c.fn)
            }
            if (c.color < 0) {
                c.color = STRONG[idx % STRONG.size]
                dirty = true
            }
            if (c.ct >= 0 && c.title.length > 8) {
                c.title = HomeCharts.SRC_NAMES[c.src]
                dirty = true
            }
            if (c.size != 1 && c.size != 2) {
                c.size = 2
                dirty = true
            }
            idx++
            fixed.add(c)
        }
        out.clear()
        out.addAll(fixed)
        if (!Store.prefs().getBoolean("chartFirst", false)) {
            val ci = out.indexOfFirst { it.ct >= 0 }
            if (ci > 0) {
                out.add(0, out.removeAt(ci))
                dirty = true
            }
            Store.prefs().edit().putBoolean("chartFirst", true).apply()
        }
        if (dirty) save(out)
        return out
    }

    fun save(list: List<C>) {
        val a = JSONArray()
        for (c in list) {
            a.put(JSONObject().apply {
                put("id", c.id); put("title", c.title); put("date", c.date)
                put("mode", c.mode); put("bg", c.bg ?: JSONObject.NULL); put("h", c.h)
            put("plug", c.plug ?: JSONObject.NULL)
                put("fn", c.fn)
                put("src", c.src); put("ct", c.ct)
                put("color", c.color); put("rep", c.rep); put("size", c.size)
            })
        }
        Store.prefs().edit().putString(KEY, a.toString()).apply()
    }

    private fun defaults() = mutableListOf(
        C(1, "生命已经过去", "2000-01-01", "up", null, 1, -1, 0, -1, STRONG[0], false),
        C(2, "成年日", "2027-01-01", "down", null, 1, -1, 0, -1, STRONG[1], false)
    )

    /** 首页默认带全部功能卡（用户长按可删） */
    private val FN_CARDS = listOf(
        3 to "吃药提醒", 1 to "番茄钟", 6 to "记账", 7 to "统计", 5 to "工资"
    )

    // ---------- 天数 ----------
    private fun today(): String = fmt.format(Calendar.getInstance().time)

    private fun startOfDay(cal: Calendar): Long {
        val c = cal.clone() as Calendar
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /** 卡片底色可选（浅色系为主，最后一个是深色） */
    val PALETTE = intArrayOf(
        0xFFFFE0B2.toInt(), 0xFFFFD9E2.toInt(), 0xFFFFF3C4.toInt(),
        0xFFD6EFFF.toInt(), 0xFFDDF5E1.toInt(), 0xFFE8DEFF.toInt(),
        0xFFFFFFFF.toInt(), 0xFF3A3F4B.toInt()
    )

    private fun darker(c: Int): Int {
        val r = ((c shr 16 and 0xFF) * 0.88f).toInt().coerceIn(0, 255)
        val g = ((c shr 8 and 0xFF) * 0.88f).toInt().coerceIn(0, 255)
        val b = ((c and 0xFF) * 0.88f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun isDark(c: Int): Boolean {
        val lum = ((c shr 16 and 0xFF) * 0.299 + (c shr 8 and 0xFF) * 0.587 + (c and 0xFF) * 0.114)
        return lum < 140
    }

    fun daysOf(c: C): Long {
        val now = startOfDay(Calendar.getInstance())
        val cal = try {
            Calendar.getInstance().apply { time = fmt.parse(c.date)!! }
        } catch (_: Exception) {
            Calendar.getInstance()
        }
        if (c.rep) {
            val t = Calendar.getInstance().apply {
                set(Calendar.MONTH, cal.get(Calendar.MONTH))
                set(Calendar.DAY_OF_MONTH, cal.get(Calendar.DAY_OF_MONTH))
            }
            var d = (startOfDay(t) - now) / DAY
            if (d < 0) {
                t.add(Calendar.YEAR, 1)
                d = (startOfDay(t) - now) / DAY
            }
            return d
        }
        val t = startOfDay(cal)
        val d = (t - now) / DAY
        return if (c.mode == "down") d else -d
    }

    private fun unitTip(c: C): String = if (c.mode == "down") "天" else "天"

    /** GNAME 风高饱和配色 */
    private val STRONG = intArrayOf(
        0xFF1E1B33.toInt(), 0xFFFFFFFF.toInt(), 0xFF1E7BF5.toInt(), 0xFFF5891F.toInt(),
        0xFFF5C21E.toInt(), 0xFFF5336E.toInt(), 0xFF22C51E.toInt(), 0xFF6E3CF5.toInt()
    )

    /** 插件卡等在别处也要用这套配色 */
    val STRONG_PUB get() = STRONG

    private fun MAP(): Int = ViewGroup.LayoutParams.MATCH_PARENT
    private fun WRAP(): Int = ViewGroup.LayoutParams.WRAP_CONTENT

    /** 卡片底部胶囊按钮（GNAME 那种） */
    private fun pill(act: MainActivity, text: String, dark: Boolean): TextView {
        val tv = Ui.tv(act, text, 14f, if (dark) Color.WHITE else 0xFF1C1C1E.toInt(), true)
        tv.gravity = Gravity.CENTER
        tv.setPadding(Ui.dp(act, 10f), Ui.dp(act, 10f), Ui.dp(act, 10f), Ui.dp(act, 10f))
        tv.background = Ui.round(if (dark) 0x33FFFFFF else 0x1A000000, 14, act)
        return tv
    }

    /** 顶部白色大卡：搜索样式 + 4 个图标入口 */
    private fun gnameHeader(act: MainActivity, onAdd: () -> Unit): View {
        val cardWrap = LinearLayout(act)
        cardWrap.orientation = LinearLayout.VERTICAL
        cardWrap.layoutParams = LinearLayout.LayoutParams(MAP(), WRAP())
        cardWrap.setBackgroundDrawable(Ui.round(0xFFFFFFFF.toInt(), 22, act))
        cardWrap.setPadding(Ui.dp(act, 14f), Ui.dp(act, 14f), Ui.dp(act, 14f), Ui.dp(act, 12f))
        cardWrap.elevation = Ui.dp(act, 2f).toFloat()

        val sBox = LinearLayout(act)
        sBox.orientation = LinearLayout.HORIZONTAL
        sBox.gravity = Gravity.CENTER_VERTICAL
        sBox.setPadding(Ui.dp(act, 14f), Ui.dp(act, 13f), Ui.dp(act, 14f), Ui.dp(act, 13f))
        sBox.background = Ui.round(0xFFF2F2F7.toInt(), 16, act)
        sBox.addView(Ui.icon(act, R.drawable.ic_plus, 18, Ui.RED))
        val hint = Ui.tv(act, "添加卡片 / 自定义首页", 15f, Ui.SUB)
        hint.gravity = Gravity.CENTER
        hint.setPadding(Ui.dp(act, 10f), 0, 0, 0)
        hint.layoutParams = LinearLayout.LayoutParams(0, WRAP(), 1f)
        sBox.addView(hint)
        val adjust = Ui.tv(act, "调整", 14f, Ui.RED, true)
        adjust.setPadding(Ui.dp(act, 8f), 0, 0, 0)
        adjust.isClickable = true
        adjust.setOnClickListener {
            AlertDialog.Builder(act).setTitle("调整主页")
                .setItems(arrayOf("拖动排序（长按卡片拖到目标位置）", "列表调整（上移 / 下移）", "换整页背景")) { _, i ->
                    when (i) {
                        0 -> toggleEdit(act)
                        1 -> adjustDialog(act)
                        2 -> bgDialog(act)
                    }
                }.show()
        }
        sBox.addView(adjust)
        sBox.isClickable = true
        sBox.setOnClickListener { onAdd() }
        cardWrap.addView(sBox)
        cardWrap.addView(Ui.space(act, 10))

        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        val entries = listOf(
            Triple("吃药", R.drawable.ic_pill, 3),
            Triple("番茄", R.drawable.ic_timer, 1),
            Triple("记账", R.drawable.ic_book, 6),
            Triple("睡眠", R.drawable.ic_moon, 8)
        )
        for ((label, icon, page) in entries) {
            val item = LinearLayout(act)
            item.orientation = LinearLayout.VERTICAL
            item.gravity = Gravity.CENTER
            item.layoutParams = LinearLayout.LayoutParams(0, WRAP(), 1f)
            item.addView(Ui.icon(act, icon, 26, Ui.RED))
            val lb = Ui.tv(act, label, 13f, Ui.TXT)
            lb.gravity = Gravity.CENTER
            lb.layoutParams = LinearLayout.LayoutParams(MAP(), WRAP())
            lb.setPadding(0, Ui.dp(act, 6f), 0, 0)
            item.addView(lb)
            item.isClickable = true
            item.setOnClickListener { act.openPage(page) }
            row.addView(item)
        }
        cardWrap.addView(row)
        return cardWrap
    }

    /** 功能卡上的"数据预览"（不是写死的"进入"） */
    private fun fnPreview(page: Int): String {
        val d = today()
        return try {
            when (page) {
                1 -> Store.pomoOn(d).toString() + " 个"
                3 -> Store.meds().size.toString() + " 种"
                6 -> "¥" + Book.money(Book.recs().filter { it.date == d && !it.income }.sumOf { it.amount })
                7 -> (Store.habitRateOn(d) * 100).toInt().toString() + "%"
                5 -> "¥" + Book.money(Book.recs().filter { it.date.startsWith(d.substring(0, 7)) && it.income }.sumOf { it.amount })
                else -> (Store.habitRateOn(d) * 100).toInt().toString() + "%"
            }
        } catch (_: Exception) {
            "进入"
        }
    }

    /** 卡片顺序调整（前移/后移） */
    private fun move(act: MainActivity, c: C, dir: Int) {
        val list = load()
        val i = list.indexOfFirst { it.id == c.id }
        val j = i + dir
        if (i < 0 || j < 0 || j >= list.size) return
        val t = list[i]
        list[i] = list[j]
        list[j] = t
        save(list)
        act.homeRefresh()
    }

    /** GNAME 风卡片：左上小标签 + 超大数字 + 底部胶囊 + 卡下小字 */
    private fun gnameCard(act: MainActivity, c: C, big: String, tap: () -> Unit): View {
        val wrap = LinearLayout(act)
        wrap.orientation = LinearLayout.VERTICAL
        val sz = c.size.coerceIn(1, 2)
        wrap.layoutParams = LinearLayout.LayoutParams(0, WRAP(), sz.toFloat()).apply {
            setMargins(Ui.dp(act, 5f), Ui.dp(act, 5f), Ui.dp(act, 5f), Ui.dp(act, 5f))
        }
        val base = if (c.color >= 0) c.color else 0xFFFFFFFF.toInt()
        val fr = if (c.color >= 0) ((0xD9 shl 24) or (c.color and 0xFFFFFF)) else 0xCCFFFFFF.toInt()
        val dark = c.color >= 0 && isDark(c.color)
        val frame = FrameLayout(act)
        frame.layoutParams = LinearLayout.LayoutParams(MAP(), Ui.dp(act, if (sz == 1) 106f else 148f))
        frame.setBackgroundDrawable(Ui.roundStroke(fr, 0x66FFFFFF, 22, act))
        frame.clipToOutline = true
        frame.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) {
                o.setRoundRect(0, 0, v.width, v.height, Ui.dp(act, 22f).toFloat())
            }
        }
        val box = LinearLayout(act)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(Ui.dp(act, if (sz == 1) 8f else 14f), Ui.dp(act, if (sz == 1) 10f else 14f),
            Ui.dp(act, if (sz == 1) 8f else 14f), Ui.dp(act, if (sz == 1) 8f else 12f))
        val lab = Ui.tv(act, c.title, if (sz == 1) 10f else 12f,
            if (dark) 0xB3FFFFFF.toInt() else 0x991C1C1E.toInt())
        lab.gravity = Gravity.CENTER
        lab.layoutParams = LinearLayout.LayoutParams(MAP(), WRAP())
        box.addView(lab)
        box.addView(Ui.space(act, if (sz == 1) 2 else 4))
        val numTv = Ui.tv(act, big, if (sz == 1) 26f else 40f,
            if (dark) Color.WHITE else 0xFF1C1C1E.toInt(), true)
        numTv.gravity = Gravity.CENTER
        numTv.layoutParams = LinearLayout.LayoutParams(MAP(), WRAP())
        box.addView(numTv)
        box.addView(View(act), LinearLayout.LayoutParams(MAP(), 0, 1f))
        if (sz != 1) box.addView(pill(act, "查看", dark), LinearLayout.LayoutParams(MAP(), WRAP()))
        frame.addView(box, FrameLayout.LayoutParams(MAP(), MAP()))
        wrap.addView(frame)
        val cap = Ui.tv(act, c.title, 12.5f, 0xE61C1C1E.toInt(), true)
        cap.gravity = Gravity.CENTER
        cap.layoutParams = LinearLayout.LayoutParams(MAP(), WRAP())
        cap.setPadding(0, Ui.dp(act, 6f), 0, 0)
        wrap.addView(cap)
        wrap.isClickable = true
        pressEffect(wrap)
        if (editMode) {
            wrap.setOnClickListener { Toast.makeText(act, "排序中：长按拖动", Toast.LENGTH_SHORT).show() }
            wrap.setOnLongClickListener {
                val data = android.content.ClipData.newPlainText("id", c.id.toString())
                wrap.startDragAndDrop(data, android.view.View.DragShadowBuilder(wrap), c.id, 0)
                true
            }
            wrap.setOnDragListener { _, e ->
                if (e.action == android.view.DragEvent.ACTION_DROP) {
                    val did = e.localState as? Long
                    if (did != null) moveTo(act, did, c.id)
                }
                true
            }
        } else {
            wrap.setOnClickListener { tap() }
            wrap.setOnLongClickListener { longPressMenu(act, c); true }
        }
        return wrap
    }

    // ---------- 首页 ----------
    fun build(act: MainActivity): View {
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(Ui.dp(act, 12f), Ui.dp(act, 8f), Ui.dp(act, 12f), Ui.dp(act, 24f))

        if (editMode) {
            val bar = LinearLayout(act)
            bar.orientation = LinearLayout.HORIZONTAL
            bar.gravity = Gravity.CENTER_VERTICAL
            bar.setPadding(Ui.dp(act, 14f), Ui.dp(act, 12f), Ui.dp(act, 12f), Ui.dp(act, 12f))
            bar.setBackgroundDrawable(Ui.round(0xE61C1C1E.toInt(), 18, act))
            val tip = Ui.tv(act, "长按卡片拖动到目标位置", 13f, Color.WHITE, true)
            tip.layoutParams = LinearLayout.LayoutParams(0, WRAP(), 1f)
            bar.addView(tip)
            val done = Ui.tv(act, "完成", 13f, Color.WHITE, true)
            done.setPadding(Ui.dp(act, 10f), Ui.dp(act, 6f), Ui.dp(act, 10f), Ui.dp(act, 6f))
            done.background = Ui.round(0xFFD81E2C.toInt(), 12, act)
            done.isClickable = true
            done.setOnClickListener { toggleEdit(act) }
            bar.addView(done)
            col.addView(bar)
            col.addView(Ui.space(act, 10))
        }

        val head = gnameHeader(act) { addChooser(act) }
        col.addView(head)
        col.addView(Ui.space(act, 12))

        val cards = load()
        var row: LinearLayout? = null
        var used = 0
        for (c in cards) {
            val unit = if (c.ct >= 0) 4 else c.size.coerceIn(1, 2)
            if (unit >= 4) {
                row = null
                used = 0
                col.addView(chartCard(act, c))
                continue
            }
            if (row == null || used + unit > 4) {
                row = LinearLayout(act)
                row.orientation = LinearLayout.HORIZONTAL
                col.addView(row)
                used = 0
            }
            val v = if (c.plug != null) {
                pluginCard(act, c)
            } else if (c.fn >= 0) {
                gnameCard(act, c, fnPreview(c.fn)) { act.openPage(c.fn) }
            } else {
                gnameCard(act, c, Math.abs(daysOf(c)).toString()) { edit(act, c) }
            }
            row.addView(v)
            used += unit
            if (used >= 4) {
                row = null
                used = 0
            }
        }
        if (used in 1..3 && row != null) {
            val ph = View(act)
            ph.layoutParams = LinearLayout.LayoutParams(0, 1, (4 - used).toFloat())
            row.addView(ph)
        }

        val sv = ScrollView(act)
        sv.isFillViewport = true
        sv.addView(col)
        return sv
    }

    private fun heightDp(h: Int) = when (h) {
        0 -> 108f
        2 -> 196f
        else -> 152f
    }

    private fun cardView(act: MainActivity, c: C, grid: GridLayout): View {
        val pad = Ui.dp(act, 5f)
        val frame = FrameLayout(act)
        frame.layoutParams = GridLayout.LayoutParams().apply {
            width = 0; height = Ui.dp(act, heightDp(c.h))
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setMargins(pad, pad, pad, pad)
        }
        // 背景：图片 或 渐变
        val builtin = when (c.bg) {
            "@fox1" -> R.drawable.bg_fox
            "@fox2", "@foxsnow" -> R.drawable.bg_foxsnow
            "@foxhead" -> R.drawable.bg_fox_head
            else -> 0
        }
        val bgFile = if (builtin == 0) c.bg?.let { File(it) } else null
        val hasImg = builtin != 0 || (bgFile != null && bgFile.exists())
        val base = if (c.color >= 0) c.color else PALETTE[0]
        if (hasImg) {
            val iv = ImageView(act)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            if (builtin != 0) {
                iv.setImageResource(builtin)
            } else {
                iv.setImageBitmap(android.graphics.BitmapFactory.decodeFile(bgFile!!.absolutePath))
            }
            frame.addView(iv, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } else {
            val v = View(act)
            v.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(base, darker(base)))
            frame.addView(v, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        if (hasImg) {
            val scrim = View(act)
            scrim.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x66000000, 0x22000000, 0xB3000000.toInt()))
            frame.addView(scrim, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val tc = if (hasImg || isDark(base)) Color.WHITE else 0xFF3C3C43.toInt()
        val tc2 = if (hasImg) 0xCCFFFFFF.toInt() else (tc and 0x00FFFFFF) or 0x99000000.toInt()

        val box = LinearLayout(act)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        box.setPadding(Ui.dp(act, 8f), Ui.dp(act, 10f), Ui.dp(act, 8f), Ui.dp(act, 10f))
        val title = Ui.tv(act, c.title, 13.5f, tc, true)
        title.gravity = Gravity.CENTER
        title.maxLines = 2
        if (hasImg) title.setShadowLayer(4f, 0f, 1f, 0xAA000000.toInt())
        box.addView(title)
        box.addView(Ui.space(act, 4))

        val num = Ui.tv(act, Math.abs(daysOf(c)).toString(), 36f, tc, true)
        num.gravity = Gravity.CENTER
        if (hasImg) num.setShadowLayer(6f, 0f, 2f, 0xAA000000.toInt())
        box.addView(num)

        val u = Ui.tv(act, unitTip(c), 11f, tc2)
        u.gravity = Gravity.END
        box.addView(u)

        frame.addView(box, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.clipToOutline = true
        frame.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) {
                o.setRoundRect(0, 0, v.width, v.height, Ui.dp(act, 26f).toFloat())
            }
        }
        frame.background = Ui.ripple(act, 24, Ui.round(0x00000000, 26, act))
        frame.isClickable = true
        pressEffect(frame)
        frame.setOnClickListener { edit(act, c) }
        frame.setOnLongClickListener { confirmDelete(act, c); true }
        return frame
    }

    /** 按压动效（TG/iOS 那种按下去缩一下） */
    private fun pressEffect(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(0.955f).scaleY(0.955f).setDuration(90).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            }
            false
        }
    }

    /** 功能卡：点一下进入对应功能页（吃药/番茄/记账/统计/工资/今日） */
    private fun fnCard(act: MainActivity, c: C): View {
        val pad = Ui.dp(act, 5f)
        val frame = FrameLayout(act)
        frame.layoutParams = GridLayout.LayoutParams().apply {
            width = 0; height = Ui.dp(act, heightDp(c.h))
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setMargins(pad, pad, pad, pad)
        }
        val v = View(act)
        v.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(PALETTE[3], darker(PALETTE[3])))
        frame.addView(v, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val box = LinearLayout(act)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        val title = Ui.tv(act, c.title, 16f, 0xFF3C3C43.toInt(), true)
        title.gravity = Gravity.CENTER
        box.addView(title)
        val tip = Ui.tv(act, "点一下进入 →", 11f, 0x993C3C43.toInt())
        tip.gravity = Gravity.CENTER
        box.addView(tip)
        frame.addView(box, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.clipToOutline = true
        frame.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) {
                o.setRoundRect(0, 0, v.width, v.height, Ui.dp(act, 26f).toFloat())
            }
        }
        frame.background = Ui.ripple(act, 24, Ui.round(0x00000000, 26, act))
        frame.isClickable = true
        pressEffect(frame)
        frame.setOnClickListener { act.openPage(c.fn) }
        frame.setOnLongClickListener { confirmDelete(act, c); true }
        return frame
    }

    /** 按 数据源 × 图表类型 生成图表视图（卡片与预览共用） */
    fun chartBody(act: MainActivity, src: Int, ct: Int): View {
        val vals = HomeCharts.values(src)
        val lbs = HomeCharts.labels()
        val u = HomeCharts.unit(src)
        val v: View = when (ct) {
            2 -> HomeCharts.PieView(act, vals, lbs, u).also {
                it.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 190f))
            }
            3 -> HomeCharts.table(act, vals, lbs, u)
            else -> TrendView(act, vals, lbs, u, ct == 0).also {
                it.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 150f))
            }
        }
        return v
    }

    /** 插件卡（v2.43）：显示某个插件拉回来的数据；点一下刷新，自动按 refresh 秒轮询 */
    private fun pluginCard(act: MainActivity, c: C): View {
        val box = LinearLayout(act)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        val pad = Ui.dp(act, 5f)
        val w = act.resources.displayMetrics.widthPixels - Ui.dp(act, 34f)
        val unitW = if (c.size == 1) (w / 4) else (w / 2)
        box.layoutParams = LinearLayout.LayoutParams(unitW, (w / 2).coerceAtLeast(Ui.dp(act, 150f))).apply {
            setMargins(pad, pad, pad, pad)
        }
        box.setBackgroundDrawable(Ui.round(if (c.color >= 0) c.color else Ui.CARD, 24, act))
        box.setPadding(Ui.dp(act, 10f), Ui.dp(act, 12f), Ui.dp(act, 10f), Ui.dp(act, 12f))
        box.elevation = Ui.dp(act, 1.5f).toFloat()

        val list = Plugins.load(act)
        val plug = list.firstOrNull { it.id == c.plug }
        if (plug == null) {
            box.addView(Ui.tv(act, c.title, 13f, Ui.TXT, true).also { it.gravity = Gravity.CENTER })
            box.addView(Ui.tv(act, "插件已删除", 11f, Ui.SUB).also { it.gravity = Gravity.CENTER })
            return box
        }

        val head = Ui.tv(act, plug.icon + " " + plug.name, 12f, Ui.SUB)
        head.gravity = Gravity.CENTER
        head.maxLines = 1
        box.addView(head)
        val big = Ui.tv(act, "…", if (c.size == 1) 20f else 26f, Ui.TXT, true)
        big.gravity = Gravity.CENTER
        big.maxLines = 1
        box.addView(big)
        val sub = Ui.tv(act, "", 11f, Ui.SUB)
        sub.gravity = Gravity.CENTER
        sub.maxLines = 1
        box.addView(sub)

        var stopped = false
        box.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) {}
            override fun onViewDetachedFromWindow(v: android.view.View) {
                stopped = true
            }
        })
        fun once() {
            Plugins.fetch(plug) { a, b, chg ->
                if (stopped) return@fetch
                big.text = a
                sub.text = b
                // A股习惯：红涨绿跌
                if (chg != null) {
                    val col = if (chg > 0) 0xFFD81E2C.toInt() else if (chg < 0) 0xFF1E9E5A.toInt() else Ui.TXT
                    big.setTextColor(col)
                    sub.setTextColor(col)
                }
            }
        }
        once()
        box.setOnClickListener { once() }
        box.setOnLongClickListener { longPressMenu(act, c); true }

        // 自动刷新（按插件的 refresh 秒；只在这个卡片活着时跑）
        val iv = plug.refresh.coerceIn(15, 3600) * 1000L
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                if (stopped) return
                once()
                h.postDelayed(this, iv)
            }
        }
        h.postDelayed(tick, iv)
        return box
    }

    /** 图表卡：折线 / 柱状 / 饼图 / 表格（整行宽 4×2，点一下换数据源与图表类型） */
    private fun chartCard(act: MainActivity, c: C): View {
        val pad = Ui.dp(act, 5f)
        val box = LinearLayout(act)
        box.orientation = LinearLayout.VERTICAL
        val w = act.resources.displayMetrics.widthPixels - Ui.dp(act, 34f)
        box.layoutParams = LinearLayout.LayoutParams(MAP(),
            (w / 2).coerceAtLeast(Ui.dp(act, 170f))).apply {
            setMargins(pad, pad, pad, pad)
        }
        box.setBackgroundDrawable(Ui.round(Ui.CARD, 26, act))
        box.setPadding(Ui.dp(act, 12f), Ui.dp(act, 12f), Ui.dp(act, 12f), Ui.dp(act, 12f))
        box.elevation = Ui.dp(act, 1.5f).toFloat()

        val head = Ui.row(act)
        val t = Ui.tv(act, c.title, 15f, Ui.TXT, true)
        t.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        head.addView(t)
        head.addView(Ui.tv(act, "换图表 ›", 12f, Ui.SUB))
        box.addView(head)
        box.addView(Ui.space(act, 6))
        box.addView(chartBody(act, c.src, c.ct))
        box.isClickable = true
        pressEffect(box)
        if (editMode) {
            box.setOnClickListener { }
            box.setOnLongClickListener {
                box.startDragAndDrop(android.content.ClipData.newPlainText("id", c.id.toString()),
                    android.view.View.DragShadowBuilder(box), c.id, 0)
                true
            }
            box.setOnDragListener { _, e ->
                if (e.action == android.view.DragEvent.ACTION_DROP) {
                    (e.localState as? Long)?.let { moveTo(act, it, c.id) }
                }
                true
            }
        } else {
            box.setOnClickListener { chartChooser(act, c) }
            box.setOnLongClickListener { longPressMenu(act, c); true }
        }
        return box
    }

    /** 选完数据源与图表类型 → 先预览，再决定保存 */
    private fun chartChooser(act: MainActivity, c: C?) {
        AlertDialog.Builder(act).setTitle("选择数据源")
            .setItems(HomeCharts.SRC_NAMES) { _, s ->
                AlertDialog.Builder(act).setTitle("选择图表类型")
                    .setItems(HomeCharts.TYPE_NAMES) { _, t -> previewChart(act, c, s, t) }
                    .setNegativeButton("取消", null).show()
            }
            .setNegativeButton("取消", null).show()
    }

    private fun previewChart(act: MainActivity, c: C?, src: Int, ct: Int) {
        val box = LinearLayout(act)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(Ui.dp(act, 16f), Ui.dp(act, 8f), Ui.dp(act, 16f), 0)
        val pvTv = Ui.tv(act, HomeCharts.SRC_NAMES[src] + " · " + HomeCharts.TYPE_NAMES[ct], 13f, Ui.SUB)
        pvTv.gravity = Gravity.CENTER
        pvTv.layoutParams = LinearLayout.LayoutParams(MAP(), WRAP())
        box.addView(pvTv)
        box.addView(Ui.space(act, 6))
        box.addView(chartBody(act, src, ct))
        AlertDialog.Builder(act).setTitle("预览（不满意可取消）").setView(box)
            .setPositiveButton("就用这个") { _, _ ->
                val list = load()
                if (c == null) {
                    list.add(C(System.currentTimeMillis(), HomeCharts.SRC_NAMES[src], today(), "down",
                        null, 2, -1, src, ct, PALETTE[3], false))
                } else {
                    val i = list.indexOfFirst { it.id == c.id }
                    if (i >= 0) {
                        list[i].src = src
                        list[i].ct = ct
                        list[i].title = HomeCharts.SRC_NAMES[src]
                    }
                }
                save(list)
                act.homeRefresh()
            }
            .setNegativeButton("取消", null).show()
    }

    /** 拖动排序模式（像桌面编辑：长按卡片拖到目标位置） */
    private var editMode = false

    fun toggleEdit(act: MainActivity) {
        editMode = !editMode
        act.homeRefresh()
        Toast.makeText(act, if (editMode) "长按卡片拖动排序，改完点「完成」" else "已退出排序", Toast.LENGTH_SHORT).show()
    }

    /** 把 dragId 这张卡拖到 targetId 的位置 */
    private fun moveTo(act: MainActivity, dragId: Long, targetId: Long) {
        val l = load()
        val from = l.indexOfFirst { it.id == dragId }
        val to = l.indexOfFirst { it.id == targetId }
        if (from < 0 || to < 0 || from == to) return
        val c = l.removeAt(from)
        l.add(to, c)
        save(l)
        act.homeRefresh()
    }

    /** 长按卡片：换背景 / 调位置 / 换整页背景 / 删除 */
    private fun longPressMenu(act: MainActivity, c: C) {
        AlertDialog.Builder(act).setTitle(c.title)
            .setItems(arrayOf("换背景 / 颜色", "调整位置", "换整页背景", "删除卡片")) { _, i ->
                when (i) {
                    0 -> edit(act, c)
                    1 -> adjustDialog(act)
                    2 -> bgDialog(act)
                    3 -> confirmDelete(act, c)
                }
            }.show()
    }

    /** 调整：自己给卡片排序 + 换整页背景 */
    fun adjustDialog(act: MainActivity) {
        val list = load()
        val names = list.map { (if (it.ct >= 0) "▤ " else if (it.fn >= 0) "▣ " else "◆ ") + it.title }.toTypedArray()
        AlertDialog.Builder(act).setTitle("点一张卡片调整它")
            .setItems(names) { _, i -> cardActions(act, list[i]) }
            .setNeutralButton("换整页背景") { _, _ -> bgDialog(act) }
            .setNegativeButton("关闭", null).show()
    }

    private fun cardActions(act: MainActivity, c: C) {
        AlertDialog.Builder(act).setTitle(c.title)
            .setItems(arrayOf("上移", "下移", "换背景 / 颜色", "删除")) { _, i ->
                when (i) {
                    0 -> {
                        move(act, c, -1)
                        adjustDialog(act)
                    }
                    1 -> {
                        move(act, c, 1)
                        adjustDialog(act)
                    }
                    2 -> edit(act, c)
                    3 -> confirmDelete(act, c)
                }
            }.show()
    }

    /** 整页背景：内置四张 + 相册自选 */
    private fun bgDialog(act: MainActivity) {
        val items = arrayOf("默认（雪山）", "狐狸1", "狐狸2", "狐狸头", "从相册选一张")
        AlertDialog.Builder(act).setTitle("整页背景").setItems(items) { _, i ->
            when (i) {
                0 -> setHomeBg(act, "@home")
                1 -> setHomeBg(act, "@fox1")
                2 -> setHomeBg(act, "@fox2")
                3 -> setHomeBg(act, "@foxhead")
                4 -> act.pickImage { uri -> copyHomeBg(act, uri) }
            }
        }.show()
    }

    private fun setHomeBg(act: MainActivity, tag: String) {
        Store.prefs().edit().putString("homeBg", tag).apply()
        act.reloadRootBg()
        Toast.makeText(act, "背景已换", Toast.LENGTH_SHORT).show()
    }

    private fun copyHomeBg(act: MainActivity, uri: android.net.Uri) {
        try {
            val dir = File(act.filesDir, "bg")
            dir.mkdirs()
            val f = File(dir, "home_${System.currentTimeMillis()}.jpg")
            act.contentResolver.openInputStream(uri)?.use { ins ->
                f.outputStream().use { out -> ins.copyTo(out) }
            }
            setHomeBg(act, f.absolutePath)
        } catch (_: Exception) {
            Toast.makeText(act, "图片读取失败", Toast.LENGTH_SHORT).show()
        }
    }

    /** 立刻把某张卡片的背景写盘（不用等"保存"，避免跳相册/来回切换时丢改动） */
    private fun bgNow(act: MainActivity, old: C?, tag: String?) {
        if (old == null) return
        val l = load()
        val i = l.indexOfFirst { it.id == old.id }
        if (i >= 0) {
            l[i].bg = tag
            save(l)
            act.homeRefresh()
        }
    }

    /** 长按卡片 → 删除 */
    private fun confirmDelete(act: MainActivity, c: C) {
        AlertDialog.Builder(act)
            .setTitle("删除卡片")
            .setMessage("把「${c.title}」这张卡片删掉？")
            .setPositiveButton("删除") { _, _ ->
                val list = load()
                list.removeAll { it.id == c.id }
                save(list)
                act.homeRefresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 加号：先选类型（纪念日 / 功能卡） */
    private fun addChooser(act: MainActivity) {
        val items = arrayOf("纪念日卡片（日期倒计时）", "图表卡（折线/柱状/饼图/表格）", "吃药提醒", "番茄钟",
            "记账", "统计", "工资", "今日概览")
        val ids = intArrayOf(-1, -2, 3, 1, 6, 7, 5, 8)
        AlertDialog.Builder(act).setTitle("添加卡片").setItems(items) { _, i ->
            if (ids[i] == -2) {
                chartChooser(act, null)
            } else if (ids[i] < 0) {
                edit(act, null)
            } else {
                val list = load()
                list.add(C(System.currentTimeMillis(), items[i], today(), "down", null, 1, ids[i]))
                save(list)
                act.homeRefresh()
            }
        }.show()
    }

    private fun plusCard(act: MainActivity): View {
        val pad = Ui.dp(act, 5f)
        val frame = FrameLayout(act)
        frame.layoutParams = LinearLayout.LayoutParams(0, Ui.dp(act, 148f), 1f).apply {
            setMargins(pad, pad, pad, pad)
        }
        frame.background = Ui.ripple(act, 24,
            Ui.roundStroke(0xFFFFFFFF.toInt(), 0x33000000, 26, act))
        val tv = Ui.tv(act, "＋ 添加卡片", 14f, Ui.SUB, true)
        tv.gravity = Gravity.CENTER
        frame.addView(tv)
        frame.isClickable = true
        pressEffect(frame)
        frame.setOnClickListener { addChooser(act) }
        return frame
    }

    // ---------- 编辑弹窗 ----------
    private fun edit(act: MainActivity, old: C?) {
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(Ui.dp(act, 16f), Ui.dp(act, 8f), Ui.dp(act, 16f), 0)

        val etTitle = EditText(act)
        etTitle.hint = "标题（如：生日）"
        etTitle.setText(old?.title ?: "")
        col.addView(etTitle)

        val etDate = EditText(act)
        etDate.hint = "日期 yyyy-MM-dd（如 2027-01-01）"
        etDate.setText(old?.date ?: today())
        col.addView(etDate)

        var picked = old?.color ?: -1
        var pendingBg: String? = old?.bg
        col.addView(Ui.tv(act, "预览（改颜色/高度即时可见）", 11f, Ui.SUB))
        val pv = FrameLayout(act)
        pv.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 92f))
        col.addView(pv)
        fun refreshPv() {
            pv.removeAllViews()
            val base = if (picked >= 0) picked else STRONG[2]
            pv.background = Ui.round(base, 22, act)
            pv.clipToOutline = true
            pv.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(vv: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, vv.width, vv.height, Ui.dp(act, 22f).toFloat())
                }
            }
            val tvv = Ui.tv(act, etTitle.text.toString().ifEmpty { "标题" }, 12f,
                if (isDark(base)) 0xB3FFFFFF.toInt() else 0x991C1C1E.toInt())
            tvv.gravity = Gravity.CENTER
            val nv = Ui.tv(act, old?.let { Math.abs(daysOf(it)).toString() } ?: "365", 30f,
                if (isDark(base)) Color.WHITE else 0xFF1C1C1E.toInt(), true)
            nv.gravity = Gravity.CENTER
            val pg = Ui.tv(act, "查看", 12f, if (isDark(base)) Color.WHITE else 0xFF1C1C1E.toInt(), true)
            pg.gravity = Gravity.CENTER
            pg.setPadding(0, Ui.dp(act, 8f), 0, Ui.dp(act, 8f))
            pg.background = Ui.round(if (isDark(base)) 0x33FFFFFF else 0x1A000000, 12, act)
            val pc = LinearLayout(act)
            pc.orientation = LinearLayout.VERTICAL
            pc.setPadding(Ui.dp(act, 12f), Ui.dp(act, 10f), Ui.dp(act, 12f), Ui.dp(act, 10f))
            pc.addView(tvv)
            pc.addView(nv)
            pc.addView(pg, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            pv.addView(pc, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        refreshPv()
        etTitle.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = refreshPv()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        val rgMode = RadioGroup(act)
        rgMode.orientation = LinearLayout.HORIZONTAL
        val r1 = RadioButton(act).apply { text = "倒计时"; id = 1001 }
        val r2 = RadioButton(act).apply { text = "已过去"; id = 1002 }
        rgMode.addView(r1); rgMode.addView(r2)
        rgMode.check(if ((old?.mode ?: "down") == "down") 1001 else 1002)
        col.addView(rgMode)

        val rgH = RadioGroup(act)
        rgH.orientation = LinearLayout.HORIZONTAL
        val h1 = RadioButton(act).apply { text = "矮"; id = 2001 }
        val h2 = RadioButton(act).apply { text = "中"; id = 2002 }
        val h3 = RadioButton(act).apply { text = "高"; id = 2003 }
        rgH.addView(h1); rgH.addView(h2); rgH.addView(h3)
        rgH.check(when (old?.h ?: 1) { 0 -> 2001; 2 -> 2003; else -> 2002 })
        col.addView(rgH)

        col.addView(Ui.tv(act, "卡片宽度（单格 = 一行放四个）", 12f, Ui.SUB))
        val rgS = RadioGroup(act)
        rgS.orientation = LinearLayout.HORIZONTAL
        rgS.addView(RadioButton(act).apply { text = "单格"; id = 3001 })
        rgS.addView(RadioButton(act).apply { text = "半宽"; id = 3002 })
        rgS.check(if ((old?.size ?: 2) == 1) 3001 else 3002)
        col.addView(rgS)

        var pickedWasSet = false
        rgMode.setOnCheckedChangeListener { _, _ -> refreshPv() }
        rgH.setOnCheckedChangeListener { _, _ -> refreshPv() }
        col.addView(Ui.tv(act, "卡片颜色（点一下选，不选＝浅橙）", 12f, Ui.SUB))
        val colorRow = LinearLayout(act)
        colorRow.orientation = LinearLayout.HORIZONTAL
        colorRow.setPadding(0, 0, 0, Ui.dp(act, 8f))
        val chips = mutableListOf<View>()
        for (cc in STRONG) {
            val chip = View(act)
            val lp = LinearLayout.LayoutParams(Ui.dp(act, 30f), Ui.dp(act, 30f))
            lp.rightMargin = Ui.dp(act, 8f)
            chip.layoutParams = lp
            chip.background = Ui.roundStroke(cc,
                if (picked == cc) 0xFFD81E2C.toInt() else 0x22000000, 15, act)
            chip.isClickable = true
            chip.setOnClickListener {
                picked = cc
                for ((k2, vw) in chips.withIndex()) {
                    vw.background = Ui.roundStroke(STRONG[k2],
                        if (STRONG[k2] == cc) 0xFFD81E2C.toInt() else 0x22000000, 15, act)
                }
                if (old != null) {
                    val l2 = load()
                    val i2 = l2.indexOfFirst { it.id == old.id }
                    if (i2 >= 0) {
                        l2[i2].color = cc
                        save(l2)
                    }
                }
                refreshPv()
            }
            chips.add(chip)
            colorRow.addView(chip)
        }
        col.addView(colorRow)

        if (old != null) {
            val mv = LinearLayout(act)
            mv.orientation = LinearLayout.HORIZONTAL
            val bUp = Ui.btn(act, "← 前移", filled = false, small = true)
            val bDn = Ui.btn(act, "后移 →", filled = false, small = true)
            bUp.setOnClickListener {
                move(act, old, -1)
                Toast.makeText(act, "已前移", Toast.LENGTH_SHORT).show()
            }
            bDn.setOnClickListener {
                move(act, old, 1)
                Toast.makeText(act, "已后移", Toast.LENGTH_SHORT).show()
            }
            mv.addView(bUp)
            mv.addView(bDn)
            col.addView(mv)
        }

        val cbRep = CheckBox(act)
        cbRep.text = "每年重复（生日 / 节日）"
        cbRep.isChecked = old?.rep ?: false
        col.addView(cbRep)

        val bgRow = LinearLayout(act)
        bgRow.orientation = LinearLayout.HORIZONTAL
        val bPick = Ui.btn(act, if (old?.bg != null) "换背景图" else "选背景图", filled = false, small = true)
        val bClear = Ui.btn(act, "清除背景", filled = false, small = true)
        bgRow.addView(bPick); bgRow.addView(bClear)
        col.addView(bgRow)

        col.addView(Ui.tv(act, "内置背景", 12f, Ui.SUB))
        val biRow = LinearLayout(act)
        biRow.orientation = LinearLayout.HORIZONTAL
        val bFox = Ui.btn(act, "雪狐", filled = false, small = true)
        bFox.setOnClickListener {
            pendingBg = "@foxsnow"
            bgNow(act, old, "@foxsnow")
            Toast.makeText(act, "已换成雪狐背景", Toast.LENGTH_SHORT).show()
        }
        biRow.addView(bFox)
        col.addView(biRow)

        val bSetImg = Ui.btn(act, "测试背景图路径", filled = false, small = true)
        bSetImg.visibility = View.GONE      // 保留占位，不影响版式
        col.addView(bSetImg)

        val dlg = AlertDialog.Builder(act).setTitle(if (old == null) "添加卡片" else "编辑卡片")
            .setView(col)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .setNeutralButton(if (old == null) null else "删除", null)
            .create()

        bClear.setOnClickListener {
            pendingBg = null
            bgNow(act, old, null)
            Toast.makeText(act, "已清除背景", Toast.LENGTH_SHORT).show()
        }
        bPick.setOnClickListener {
            act.pickImage { uri ->
                val f = File(File(act.filesDir, "cards").apply { mkdirs() },
                    "card_${old?.id ?: System.currentTimeMillis()}.jpg")
                try {
                    act.contentResolver.openInputStream(uri)?.use { ins ->
                        f.outputStream().use { outs -> ins.copyTo(outs) }
                    }
                    pendingBg = f.absolutePath
                    bgNow(act, old, f.absolutePath)
                    Toast.makeText(act, "背景已设置", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(act, "读取图片失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = etTitle.text.toString().trim().ifEmpty { "纪念日" }
                val date = etDate.text.toString().trim()
                if (!date.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                    Toast.makeText(act, "日期格式要像 2027-01-01", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val list = load()
                val mode = if (rgMode.checkedRadioButtonId == 1001) "down" else "up"
                val hh = when (rgH.checkedRadioButtonId) { 2001 -> 0; 2003 -> 2; else -> 1 }
                val szz = if (rgS.checkedRadioButtonId == 3001) 1 else 2
                if (old == null) {
                    list.add(C(System.currentTimeMillis(), title, date, mode, pendingBg, hh, -1, 0, -1,
                        picked, cbRep.isChecked, szz))
                } else {
                    old.title = title; old.date = date; old.mode = mode; old.bg = pendingBg; old.h = hh
                    old.color = picked; old.rep = cbRep.isChecked; old.size = szz
                }
                save(list)
                dlg.dismiss()
                act.homeRefresh()
            }
            if (old != null) {
                dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    val list = load(); list.removeAll { it.id == old.id }; save(list)
                    dlg.dismiss(); act.homeRefresh()
                }
            }
        }
        dlg.show()
    }
}
