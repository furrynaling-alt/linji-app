package com.naling.shuai

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 工时 / 工资（小时工）：数据 + 计算，纯本地存储（SharedPreferences + JSON）
 * 全部功能对标「安心记加班」，但没有任何广告 / 签到 / 社交。
 */
object Wage {

    /** 班次（顺序同界面：白班/夜班/休息/早班/中班/晚班/请假） */
    val SHIFTS = listOf("白班", "夜班", "休息", "早班", "中班", "晚班", "请假")

    const val K_SUB = "sub"      // 补贴项目
    const val K_CUT = "cut"      // 扣款项目
    const val K_OTH = "oth"      // 其他项目

    class Rec(
        var id: String = "",
        var date: String = "",          // yyyy-MM-dd
        var start: String = "08:00",
        var end: String = "21:00",
        var rate: Double = 0.0,         // 元/小时
        var lunch: Int = 60,            // 午休（分钟）
        var dinner: Int = 60,           // 晚休（分钟）
        var shift: String = "白班",
        var note: String = "",
        var hours: Double = -1.0        // >=0：直接按工时记（日历里那种）
    )

    class Item(
        var name: String = "",
        var amount: Double = 0.0,
        var perDay: Boolean = false     // true = 元/天 × 出勤天数
    )

    class Tpl(
        var start: String = "08:00",
        var end: String = "21:00",
        var lunch: Int = 60,
        var dinner: Int = 60,
        var shift: String = "白班",
        var rate: Double = 0.0
    )

    // ---------------- 基础 ----------------
    private fun sp() = Store.prefs()

    private fun df(): SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

    fun today(): String = df().format(Date())

    fun d(off: Int): String {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_MONTH, off)
        return df().format(c.time)
    }

    fun parse(date: String): Date? = try {
        df().parse(date)
    } catch (_: Exception) {
        null
    }

    fun weekday(date: String): String {
        val dd = parse(date) ?: return ""
        val c = Calendar.getInstance()
        c.time = dd
        val arr = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
        return arr[c.get(Calendar.DAY_OF_WEEK) - 1]
    }

    fun md(date: String): String = if (date.length >= 10) date.substring(5).replace("-", ".") else date

    fun hhmm(s: String): Int? {
        val t = s.trim()
        val i = t.indexOf(':')
        if (i <= 0) return null
        val h = t.substring(0, i).toIntOrNull() ?: return null
        val m = t.substring(i + 1).trim().toIntOrNull() ?: return null
        if (h !in 0..47 || m !in 0..59) return null
        return h * 60 + m
    }

    fun money(v: Double): String {
        val a = abs(v)
        return if (abs(a - a.toLong()) < 0.05) a.toLong().toString() else String.format(Locale.CHINA, "%.1f", a)
    }

    fun moneyS(v: Double): String = (if (v < 0) "-" else "") + money(v)

    fun hh1(v: Double): String = String.format(Locale.CHINA, "%.1f", v)

    // ---------------- 设置 ----------------
    fun rate(): Double = sp().getString("w_rate", "16")!!.toDoubleOrNull() ?: 16.0
    fun setRate(v: Double) = sp().edit().putString("w_rate", v.toString()).apply()

    fun lunchDef(): Int = sp().getInt("w_lunch", 60)
    fun dinnerDef(): Int = sp().getInt("w_dinner", 60)
    fun setBreaks(l: Int, d2: Int) = sp().edit().putInt("w_lunch", l).putInt("w_dinner", d2).apply()

    fun cycleStart(): Int = sp().getInt("w_cycle", 1)          // 1 = 自然月
    fun setCycleStart(v: Int) = sp().edit().putInt("w_cycle", v.coerceIn(1, 28)).apply()

    // ---------------- 记录 ----------------
    fun recs(): MutableList<Rec> {
        val raw = sp().getString("w_recs", "[]") ?: "[]"
        val list = mutableListOf<Rec>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Rec(
                        o.optString("id", ""), o.optString("date", ""),
                        o.optString("start", "08:00"), o.optString("end", "21:00"),
                        o.optDouble("rate", 0.0), o.optInt("lunch", 0), o.optInt("dinner", 0),
                        o.optString("shift", "白班"), o.optString("note", ""),
                        o.optDouble("hours", -1.0)
                    )
                )
            }
        } catch (_: Exception) {
        }
        list.sortWith(compareByDescending<Rec> { it.date }.thenByDescending { it.start })
        return list
    }

    private fun saveRecs(list: List<Rec>) {
        val arr = JSONArray()
        for (r in list) {
            val o = JSONObject()
            o.put("id", r.id); o.put("date", r.date)
            o.put("start", r.start); o.put("end", r.end)
            o.put("rate", r.rate); o.put("lunch", r.lunch); o.put("dinner", r.dinner)
            o.put("shift", r.shift); o.put("note", r.note); o.put("hours", r.hours)
            arr.put(o)
        }
        sp().edit().putString("w_recs", arr.toString()).apply()
    }

    fun addRec(r: Rec) {
        if (r.id.isEmpty()) r.id = "w" + System.currentTimeMillis() + "_" + (0..999).random()
        val l = recs()
        l.add(r)
        saveRecs(l)
    }

    fun updRec(r: Rec) {
        val l = recs()
        val i = l.indexOfFirst { it.id == r.id }
        if (i >= 0) l[i] = r else l.add(r)
        saveRecs(l)
    }

    fun delRec(id: String) {
        val l = recs().filter { it.id != id }.toMutableList()
        saveRecs(l)
    }

    fun delDay(date: String) {
        saveRecs(recs().filter { it.date != date })
    }

    // ---------------- 计算 ----------------
    fun hoursOf(r: Rec): Double {
        if (r.hours >= 0) return r.hours
        val s = hhmm(r.start) ?: return 0.0
        val e = hhmm(r.end) ?: return 0.0
        var mins = e - s
        if (mins <= 0) mins += 1440
        mins -= (r.lunch + r.dinner)
        if (mins < 0) mins = 0
        return mins / 60.0
    }

    fun payOf(r: Rec): Double = if (r.shift == "休息" || r.shift == "请假") 0.0 else hoursOf(r) * r.rate

    /** 时段显示：08:00-21:00 */
    fun span(r: Rec): String = r.start + "-" + r.end

    // ---------------- 考勤周期 ----------------
    private fun cycleStartCal(off: Int): Calendar {
        val sd = cycleStart().coerceIn(1, 28)
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        if (c.get(Calendar.DAY_OF_MONTH) < sd) c.add(Calendar.MONTH, -1)
        c.set(Calendar.DAY_OF_MONTH, sd)
        c.add(Calendar.MONTH, off)
        return c
    }

    fun cycleStartDate(off: Int): String = df().format(cycleStartCal(off).time)

    fun cycleEndDate(off: Int): String {
        val c = cycleStartCal(off + 1)
        c.add(Calendar.DAY_OF_MONTH, -1)
        return df().format(c.time)
    }

    fun cycleLabel(off: Int): String = md(cycleStartDate(off)) + " - " + md(cycleEndDate(off))

    fun inCycle(date: String, off: Int): Boolean =
        date >= cycleStartDate(off) && date <= cycleEndDate(off)

    fun recsIn(off: Int): List<Rec> = recs().filter { inCycle(it.date, off) }

    fun sumHours(list: List<Rec>): Double = list.sumOf { hoursOf(it) }

    fun sumPay(list: List<Rec>): Double = list.sumOf { payOf(it) }

    fun cycleIncome(off: Int): Double {
        val cyc = cycleStartDate(off)
        val list = recsIn(off)
        val days = workDays(list)
        val pay = sumPay(list)
        val subT = itemTotal(K_SUB, cyc, days)
        val cutT = itemTotal(K_CUT, cyc, days) + itemTotal(K_OTH, cyc, days)
        return pay + subT - cutT
    }

    /** 出勤天数（同一天多笔算 1 天；休息 / 请假不算） */
    fun workDays(list: List<Rec>): Int =
        list.filter { it.shift != "休息" && it.shift != "请假" }.map { it.date }.distinct().size

    // ---------------- 考勤（规定上下班时间） ----------------
    fun workStart(): String = sp().getString("w_workstart", "08:00") ?: "08:00"
    fun workEnd(): String = sp().getString("w_workend", "17:00") ?: "17:00"
    fun setWorkTime(a: String, b: String) =
        sp().edit().putString("w_workstart", a).putString("w_workend", b).apply()

    /** 一天的考勤状态：正常 / 迟到N分 / 早退N分 / 缺勤 / 请假 / 休息 / 加班N小时 */
    fun attendStatus(date: String, isPastWorkday: Boolean): String {
        val rs = dayRecs(date)
        if (rs.isEmpty()) {
            return when {
                !isPastWorkday -> "—"
                else -> "缺勤"
            }
        }
        val sh = rs.firstOrNull()?.shift ?: ""
        if (sh == "休息") return "休息"
        if (sh == "请假" || rs.any { it.shift == "请假" }) return "请假"
        val ws = hhmm(workStart())
        val we = hhmm(workEnd())
        var late = 0
        var early = 0
        var otMin = 0
        for (r in rs) {
            if (r.start.isNotEmpty()) {
                val s = hhmm(r.start)
                if (ws != null && s != null && s > ws) late = maxOf(late, s - ws)
            }
            if (r.end.isNotEmpty()) {
                val e = hhmm(r.end)
                if (we != null && e != null && e < we) early = maxOf(early, we - e)
            }
            val std = if (ws != null && we != null) we - ws else 0
            val extra = (hoursOf(r) * 60).toInt() - std
            if (extra > 0) otMin += extra
        }
        return when {
            late > 0 && early > 0 -> "迟到" + late + "分 · 早退" + early + "分"
            late > 0 -> "迟到 " + late + " 分"
            early > 0 -> "早退 " + early + " 分"
            otMin >= 30 -> "加班 " + hh1(otMin / 60.0) + " 小时"
            else -> "正常"
        }
    }

    /** 是否工作日（周一~周五） */
    fun isWorkday(date: String): Boolean {
        val w = weekday(date)
        return w != "周六" && w != "周日"
    }

    fun stat(list: List<Rec>): IntArray {
        val a = IntArray(SHIFTS.size)
        for (s in SHIFTS.indices) {
            a[s] = list.filter { it.shift == SHIFTS[s] }.map { it.date }.distinct().size
        }
        return a
    }

    /** 某天的合计 */
    fun dayHours(date: String): Double = recs().filter { it.date == date }.sumOf { hoursOf(it) }
    fun dayPay(date: String): Double = recs().filter { it.date == date }.sumOf { payOf(it) }
    fun dayRecs(date: String): List<Rec> = recs().filter { it.date == date }
    fun dayShift(date: String): String = recs().firstOrNull { it.date == date }?.shift ?: ""

    // ---------------- 补贴 / 扣款 / 其他 ----------------
    private fun itemKey(kind: String, cyc: String) = "w_i_" + kind + "_" + cyc

    private fun defaults(kind: String): List<Item> = when (kind) {
        K_SUB -> listOf(Item("夜班补贴", 0.0, true), Item("伙食补贴"), Item("住房补贴"), Item("岗位补贴"))
        K_CUT -> listOf(Item("水费"), Item("电费"), Item("住宿费"))
        else -> listOf(Item("商保"))
    }

    fun items(kind: String, cyc: String): MutableList<Item> {
        val raw = sp().getString(itemKey(kind, cyc), null)
        if (raw == null) {
            val l = defaults(kind).toMutableList()
            saveItems(kind, cyc, l)
            return l
        }
        val list = mutableListOf<Item>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(Item(o.optString("n", ""), o.optDouble("a", 0.0), o.optBoolean("p", false)))
            }
        } catch (_: Exception) {
        }
        return list
    }

    fun saveItems(kind: String, cyc: String, list: List<Item>) {
        val arr = JSONArray()
        for (it2 in list) {
            val o = JSONObject()
            o.put("n", it2.name); o.put("a", it2.amount); o.put("p", it2.perDay)
            arr.put(o)
        }
        sp().edit().putString(itemKey(kind, cyc), arr.toString()).apply()
    }

    fun itemTotal(kind: String, cyc: String, days: Int): Double {
        var t = 0.0
        for (it2 in items(kind, cyc)) t += if (it2.perDay) it2.amount * days else it2.amount
        return t
    }

    // ---------------- 模板（快捷记录） ----------------
    fun tpls(): MutableList<Tpl> {
        val raw = sp().getString("w_tpls", "[]") ?: "[]"
        val list = mutableListOf<Tpl>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Tpl(
                        o.optString("s", "08:00"), o.optString("e", "21:00"),
                        o.optInt("l", 60), o.optInt("d", 60),
                        o.optString("h", "白班"), o.optDouble("r", 0.0)
                    )
                )
            }
        } catch (_: Exception) {
        }
        return list
    }

    fun saveTpls(list: List<Tpl>) {
        val arr = JSONArray()
        for (t in list) {
            val o = JSONObject()
            o.put("s", t.start); o.put("e", t.end); o.put("l", t.lunch)
            o.put("d", t.dinner); o.put("h", t.shift); o.put("r", t.rate)
            arr.put(o)
        }
        sp().edit().putString("w_tpls", arr.toString()).apply()
    }

    fun addTpl(t: Tpl) {
        val l = tpls()
        if (l.any { it.start == t.start && it.end == t.end && it.shift == t.shift }) return
        l.add(t)
        saveTpls(l)
    }

    fun delTpl(index: Int) {
        val l = tpls()
        if (index in l.indices) {
            l.removeAt(index)
            saveTpls(l)
        }
    }
}
