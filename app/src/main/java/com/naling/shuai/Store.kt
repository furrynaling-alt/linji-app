package com.naling.shuai

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 极简本地存储：SharedPreferences + JSON，无第三方依赖 */
object Store {

    private lateinit var sp: SharedPreferences

    // 默认习惯
    private val DEFAULT_HABITS = listOf(
        "7:00 起床",
        "22:50 睡觉"
    )

    /** 给工时/工资模块用的存储入口 */
    fun prefs(): SharedPreferences = sp

    fun init(c: Context) {
        if (!::sp.isInitialized) {
            sp = c.applicationContext.getSharedPreferences("shuai", Context.MODE_PRIVATE)
        }
    }

    // ---------------- 日期 ----------------
    private fun fmt(): SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

    fun day(d: Date = Date()): String = fmt().format(d)

    fun nowMinutes(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    fun minutesUntil(h: Int, m: Int): Int {
        val t = h * 60 + m
        val n = nowMinutes()
        return if (t >= n) t - n else t + 24 * 60 - n
    }

    // ---------------- 打卡 ----------------
    class Habit(val id: String, val name: String, val days: MutableSet<String>)

    private fun seedHabits(): MutableList<Habit> {
        val list = mutableListOf<Habit>()
        list.add(Habit("h0", wakeHhmm() + " 起床", mutableSetOf()))
        list.add(Habit("h1", lockHhmm() + " 睡觉", mutableSetOf()))
        saveHabits(list)
        return list
    }

    /** 让「起床 / 睡觉」这两条的名字跟着设置走（改了睡觉时间，这里自动变） */
    fun syncHabitTimeNames() {
        val wake = wakeHhmm() + " 起床"
        val sleep = lockHhmm() + " 睡觉"
        val cur = habits()
        var changed = false
        val out = cur.map { h ->
            val nn = when (h.id) {
                "h0" -> wake
                "h1" -> sleep
                else -> h.name
            }
            if (nn != h.name) changed = true
            Habit(h.id, nn, h.days)
        }
        if (changed) saveHabits(out)
    }

    /** 只留「起床 / 睡觉」两条，其余删掉（别人装上也能从零开始） */
    fun slimHabits(): Int {
        val cur = habits()
        val keep = cur.filter {
            it.id == "h0" || it.id == "h1" || it.name.contains("起床") || it.name.contains("睡觉")
        }
        val removed = cur.size - keep.size
        if (removed > 0) saveHabits(keep)
        return removed
    }

    // ---------------- 「早上好」门禁：统一走 wakeThreshold（入睡之后次日 4:00） ----------------
    fun morningGate(): Long = wakeThreshold(sleepStart())

    fun minutesUntilMorning(): Int = minutesToWake()

    fun canMorning(): Boolean = canWakeNow()

    /** 睡眠界面 / 首页统一用的提示语 */
    fun morningHint(): String {
        if (!flag("earlyGate")) return "随时可以点「早上好」"
        val m = minutesToWake()
        if (m > 0) return "最早 ${earlyHhmm()} 才能起（还有 %d 小时 %d 分）".format(m / 60, m % 60)
        return "可以点「早上好」起床了 ✓"
    }

    fun habits(): MutableList<Habit> {
        val raw = sp.getString("habits", null) ?: return seedHabits()
        val list = mutableListOf<Habit>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val set = mutableSetOf<String>()
                val da = o.optJSONArray("days") ?: JSONArray()
                for (j in 0 until da.length()) set.add(da.getString(j))
                list.add(Habit(o.getString("id"), o.getString("name"), set))
            }
        } catch (e: Exception) {
            return seedHabits()
        }
        if (list.isEmpty()) return seedHabits()
        return list
    }

    fun saveHabits(list: List<Habit>) {
        val arr = JSONArray()
        for (h in list) {
            val o = JSONObject()
            o.put("id", h.id)
            o.put("name", h.name)
            val da = JSONArray()
            h.days.sorted().forEach { da.put(it) }
            o.put("days", da)
            arr.put(o)
        }
        sp.edit().putString("habits", arr.toString()).apply()
    }

    fun toggleHabit(h: Habit) {
        val d = day()
        if (h.days.contains(d)) h.days.remove(d) else h.days.add(d)
        saveHabits(habits().map { if (it.id == h.id) h else it })
    }

    /** 连续天数（含今天；今天没打勾就看昨天往前） */
    fun streak(h: Habit): Int {
        var n = 0
        val cal = Calendar.getInstance()
        if (!h.days.contains(day(cal.time))) cal.add(Calendar.DAY_OF_MONTH, -1)
        while (h.days.contains(day(cal.time))) {
            n++
            cal.add(Calendar.DAY_OF_MONTH, -1)
        }
        return n
    }

    fun addHabit(name: String) {
        val list = habits()
        list.add(Habit("h" + System.currentTimeMillis(), name, mutableSetOf()))
        saveHabits(list)
    }

    fun removeHabit(id: String) {
        saveHabits(habits().filter { it.id != id })
    }

    fun renameHabit(id: String, name: String) {
        saveHabits(habits().map { if (it.id == id) Habit(it.id, name, it.days) else it })
    }

    /** 恢复出厂那两条习惯（起床 / 睡觉，时间跟设置走） */
    fun restoreDefaultHabits() {
        val cur = habits()
        if (cur.none { it.id == "h0" }) cur.add(Habit("h0", wakeHhmm() + " 起床", mutableSetOf()))
        if (cur.none { it.id == "h1" }) cur.add(Habit("h1", lockHhmm() + " 睡觉", mutableSetOf()))
        saveHabits(cur)
    }

    fun doneTodayCount(): Int = habits().count { it.days.contains(day()) }
    fun habitCount(): Int = habits().size

    // ---------------- 记录 ----------------
    class Todo(val id: Long, val text: String, val done: Boolean, val ts: Long, val from: String)

    fun todos(): MutableList<Todo> {
        val raw = sp.getString("todos", "[]") ?: "[]"
        val list = mutableListOf<Todo>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Todo(
                        o.optLong("id"), o.optString("text", ""),
                        o.optBoolean("done", false), o.optLong("ts"), o.optString("from", "我")
                    )
                )
            }
        } catch (_: Exception) {
        }
        return list
    }

    private fun saveTodos(list: List<Todo>) {
        val arr = JSONArray()
        for (t in list) arr.put(
            JSONObject().put("id", t.id).put("text", t.text)
                .put("done", t.done).put("ts", t.ts).put("from", t.from)
        )
        sp.edit().putString("todos", arr.toString()).commit()
    }

    fun addTodo(text: String, from: String): Long {
        val id = System.currentTimeMillis()
        val list = todos()
        list.add(0, Todo(id, text, false, id, from))
        saveTodos(list)
        return id
    }

    fun setTodoDone(id: Long, done: Boolean) {
        saveTodos(todos().map { if (it.id == id) Todo(it.id, it.text, done, it.ts, it.from) else it })
    }

    fun delTodo(id: Long) {
        saveTodos(todos().filterNot { it.id == id })
    }

    fun todoMatch(id: Long, text: String): Long {
        val list = todos()
        if (id > 0 && list.any { it.id == id }) return id
        return list.firstOrNull { text.isNotBlank() && it.text.contains(text) }?.id ?: -1L
    }

    fun serverBase(): String {
        val raw = (sp.getString("serverBase", "") ?: "").trim().trimEnd('/')
        if (raw.isBlank()) return "https://furry.gov.naling.net"
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        val ipOnly = raw.matches(Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}(:[0-9]+)?$"))
        return (if (ipOnly) "http://" else "https://") + raw
    }

    fun serverBaseRaw(): String = (sp.getString("serverBase", "") ?: "")

    fun setServerBase(v: String) {
        sp.edit().putString("serverBase", v.trim().trimEnd('/')).apply()
    }

    class Note(val ts: Long, val text: String, val pin: Boolean = false)

    fun notes(): MutableList<Note> {
        val raw = sp.getString("notes", "[]") ?: "[]"
        val list = mutableListOf<Note>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(Note(o.optLong("ts"), o.optString("text", ""), o.optBoolean("pin", false)))
            }
        } catch (_: Exception) {
        }
        list.sortWith(compareByDescending<Note> { it.pin }.thenByDescending { it.ts })
        return list
    }

    fun saveNotes(list: List<Note>) {
        val arr = JSONArray()
        for (n in list) {
            arr.put(JSONObject().put("ts", n.ts).put("text", n.text).put("pin", n.pin))
        }
        sp.edit().putString("notes", arr.toString()).apply()
    }

    fun addNote(text: String) {
        val list = notes()
        list.add(0, Note(System.currentTimeMillis(), text, false))
        saveNotes(list)
    }

    fun updNote(ts: Long, text: String) {
        saveNotes(notes().map { if (it.ts == ts) Note(it.ts, text, it.pin) else it })
    }

    fun pinNote(ts: Long, on: Boolean) {
        saveNotes(notes().map { if (it.ts == ts) Note(it.ts, it.text, on) else it })
    }

    fun delNote(ts: Long) {
        saveNotes(notes().filter { it.ts != ts })
    }

    // ---------------- 用药 ----------------
    class Med(val id: String, val name: String, val left: Int, val time: String, val note: String)

    fun meds(): MutableList<Med> {
        val raw = sp.getString("meds", "[]") ?: "[]"
        val list = mutableListOf<Med>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Med(
                        o.getString("id"), o.getString("name"),
                        o.optInt("left", 0), o.optString("time", "21:10"), o.optString("note", "")
                    )
                )
            }
        } catch (_: Exception) {
        }
        return list
    }

    fun saveMeds(list: List<Med>) {
        val arr = JSONArray()
        for (m in list) {
            arr.put(
                JSONObject().put("id", m.id).put("name", m.name)
                    .put("left", m.left).put("time", m.time).put("note", m.note)
            )
        }
        sp.edit().putString("meds", arr.toString()).apply()
    }

    fun addMed(name: String, left: Int, time: String, note: String) {
        val list = meds()
        list.add(Med("m" + System.currentTimeMillis(), name, left, time, note))
        saveMeds(list)
    }

    fun removeMed(id: String) {
        saveMeds(meds().filter { it.id != id })
    }

    fun bumpMed(id: String, delta: Int) {
        saveMeds(meds().map { if (it.id == id) Med(it.id, it.name, (it.left + delta).coerceAtLeast(0), it.time, it.note) else it })
    }

    // ---------------- 买药清单 ----------------
    class Buy(val name: String, val done: Boolean)

    fun buys(): MutableList<Buy> {
        val raw = sp.getString("buys", "[]") ?: "[]"
        val list = mutableListOf<Buy>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(Buy(o.getString("name"), o.optBoolean("done", false)))
            }
        } catch (_: Exception) {
        }
        return list
    }

    fun saveBuys(list: List<Buy>) {
        val arr = JSONArray()
        for (b in list) arr.put(JSONObject().put("name", b.name).put("done", b.done))
        sp.edit().putString("buys", arr.toString()).apply()
    }

    fun addBuy(name: String) {
        val l = buys()
        l.add(Buy(name, false))
        saveBuys(l)
    }

    // ---------------- 番茄钟 ----------------
    fun pomoWork(): Int = sp.getInt("pomoWork", 25)
    fun pomoBreak(): Int = sp.getInt("pomoBreak", 5)
    fun pomoLong(): Int = sp.getInt("pomoLong", 15)
    fun pomoCycles(): Int = sp.getInt("pomoCycles", 4)

    fun setPomo(work: Int, brk: Int, lng: Int, cyc: Int) {
        sp.edit().putInt("pomoWork", work).putInt("pomoBreak", brk)
            .putInt("pomoLong", lng).putInt("pomoCycles", cyc).apply()
    }

    fun pomoDoneToday(): Int {
        val raw = sp.getString("pomoDone", "{}") ?: "{}"
        return try {
            JSONObject(raw).optInt(day(), 0)
        } catch (_: Exception) {
            0
        }
    }

    fun incPomoToday() {
        val raw = sp.getString("pomoDone", "{}") ?: "{}"
        val o = try {
            JSONObject(raw)
        } catch (_: Exception) {
            JSONObject()
        }
        o.put(day(), o.optInt(day(), 0) + 1)
        sp.edit().putString("pomoDone", o.toString()).apply()
    }

    // ---------------- 番茄预设（可自由添加/删除） ----------------
    class Preset(val name: String, val minutes: Int)

    private val DEFAULT_PRESETS = listOf(
        Preset("标准专注", 25), Preset("深度 50 分钟", 50),
        Preset("短冲 15 分钟", 15), Preset("长休息", 15)
    )

    fun presets(): MutableList<Preset> {
        val raw = sp.getString("presets", null) ?: run {
            savePresets(DEFAULT_PRESETS)
            return DEFAULT_PRESETS.toMutableList()
        }
        val list = mutableListOf<Preset>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(Preset(o.getString("name"), o.optInt("minutes", 25)))
            }
        } catch (_: Exception) {
        }
        if (list.isEmpty()) {
            savePresets(DEFAULT_PRESETS)
            return DEFAULT_PRESETS.toMutableList()
        }
        return list
    }

    fun savePresets(list: List<Preset>) {
        val arr = JSONArray()
        for (p in list) arr.put(JSONObject().put("name", p.name).put("minutes", p.minutes))
        sp.edit().putString("presets", arr.toString()).apply()
    }

    fun addPreset(name: String, minutes: Int) {
        val l = presets()
        l.add(Preset(name, minutes.coerceIn(1, 180)))
        savePresets(l)
    }

    fun removePreset(name: String, minutes: Int) {
        savePresets(presets().filterNot { it.name == name && it.minutes == minutes })
    }

    // ---------------- 锁机 / 作息设置 ----------------
    fun lockEnabled(): Boolean = sp.getBoolean("lockEnabled", true)
    fun setLockEnabled(v: Boolean) = sp.edit().putBoolean("lockEnabled", v).apply()

    fun lockH(): Int = sp.getInt("lockH", 22)
    fun lockM(): Int = sp.getInt("lockM", 50)
    fun setLockTime(h: Int, m: Int) = sp.edit().putInt("lockH", h).putInt("lockM", m).apply()

    fun holdSec(): Int = sp.getInt("holdSec", 10)
    fun setHoldSec(v: Int) = sp.edit().putInt("holdSec", v.coerceIn(3, 60)).apply()

    fun wakeH(): Int = sp.getInt("wakeH", 7)
    fun wakeM(): Int = sp.getInt("wakeM", 0)
    fun setWakeTime(h: Int, m: Int) = sp.edit().putInt("wakeH", h).putInt("wakeM", m).apply()

    fun lockHhmm(): String = "%02d:%02d".format(lockH(), lockM())
    fun wakeHhmm(): String = "%02d:%02d".format(wakeH(), wakeM())

    /** 指定日期完成了几个番茄 */
    fun pomoOn(date: String): Int {
        val raw = sp.getString("pomoDone", "{}") ?: "{}"
        return try {
            JSONObject(raw).optInt(date, 0)
        } catch (_: Exception) {
            0
        }
    }

    /** 指定日期的打卡完成率（0~1） */
    fun habitRateOn(date: String): Float {
        val hs = habits()
        if (hs.isEmpty()) return 0f
        return hs.count { it.days.contains(date) }.toFloat() / hs.size
    }

    /** 近 N 天日期（旧→新） */
    fun lastDays(n: Int): List<String> {
        val out = mutableListOf<String>()
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_MONTH, -(n - 1))
        for (i in 0 until n) {
            out.add(day(cal.time))
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        return out
    }

    // ---------------- 功能开关（设置里自由开关） ----------------
    fun flag(key: String, def: Boolean = true): Boolean = sp.getBoolean("flag_" + key, def)
    fun setFlag(key: String, v: Boolean) = sp.edit().putBoolean("flag_" + key, v).apply()

    /** 出厂默认都开，除了摸脸计数（默认开） */
    val FLAG_LIST = listOf(
        "sleepLock" to "到点自动锁机（22:50）",
        "askMode" to "到点先问：我要睡觉了 / 晚 N 分钟",
        "earlyGate" to "早上好 4:00 后才可点",
        "sos" to "锁屏显示「紧急：打电话」",
        "sleepLog" to "今日页显示「睡眠记录」",
        "trends" to "今日页显示「近 7 天趋势图」",
        "touchCounter" to "显示「我摸脸了」计数器",
        "skinSet" to "显示「一键加皮肤护理套餐」",
        "pomo" to "番茄钟",
        "notes" to "记录",
        "meds" to "用药 / 买药",
        "water" to "喝水 / 久坐提醒",
        "recorder" to "熄屏录像",
        "wage" to "工时 / 考勤 / 工资（小时工）",
        "book" to "记账（收支账本）",
        "stats" to "统计 / 月度（含历史）",
        "wakeNoti" to "7:00 起床提醒"
    )

    // ---------------- 摸脸 / 挤痘计数 ----------------
    fun touchCountToday(): Int {
        val raw = sp.getString("touches", "{}") ?: "{}"
        return try {
            JSONObject(raw).optInt(day(), 0)
        } catch (_: Exception) {
            0
        }
    }

    fun addTouch() {
        val raw = sp.getString("touches", "{}") ?: "{}"
        val o = try {
            JSONObject(raw)
        } catch (_: Exception) {
            JSONObject()
        }
        o.put(day(), o.optInt(day(), 0) + 1)
        sp.edit().putString("touches", o.toString()).apply()
    }

    // ---------------- 喝水 / 久坐提醒 ----------------
    fun waterOn(): Boolean = sp.getBoolean("waterOn", false)
    fun setWaterOn(v: Boolean) = sp.edit().putBoolean("waterOn", v).apply()
    fun waterMin(): Int = sp.getInt("waterMin", 60)          // 间隔分钟
    fun setWaterMin(v: Int) = sp.edit().putInt("waterMin", v.coerceIn(15, 240)).apply()
    fun waterFrom(): Int = sp.getInt("waterFrom", 7)          // 提醒起始小时
    fun waterTo(): Int = sp.getInt("waterTo", 22)             // 提醒结束小时
    fun setWaterWindow(a: Int, b: Int) = sp.edit().putInt("waterFrom", a).putInt("waterTo", b).apply()

    // ---------------- 备份导出 / 导入 ----------------
    fun exportJson(): String {
        val o = JSONObject()
        for (k in sp.all.keys) {
            o.put(k, sp.all[k])
        }
        return o.toString(2)
    }

    fun importJson(text: String): Boolean {
        return try {
            val o = JSONObject(text)
            val e = sp.edit()
            for (k in o.keys()) {
                val v = o.get(k)
                when (v) {
                    is Boolean -> e.putBoolean(k, v)
                    is Int -> e.putInt(k, v)
                    is Long -> e.putLong(k, v)
                    is Float -> e.putFloat(k, v)
                    else -> e.putString(k, v.toString())
                }
            }
            e.apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 一天的多个提醒时间（支持 "08:00, 13:30, 21:00"） */
    fun medTimes(m: Med): List<String> {
        val out = mutableListOf<String>()
        for (p in m.time.split(',', '，', '、', '/', ';', '；', ' ')) {
            val t = p.trim()
            if (t.matches(Regex("\\d{1,2}:\\d{1,2}"))) out.add(t)
        }
        if (out.isEmpty()) out.add("21:10")
        return out
    }

    /** 一键加一套皮肤护理习惯 */
    fun addSkincareSet() {
        val list = habits()
        val have = list.map { it.name }.toSet()
        val set = listOf(
            "早：洗脸 → 保湿 → 防晒",
            "晚：洗澡 → 涂药 → 保湿",
            "灰指甲涂剂（周三 / 周日）",
            "不摸脸、不挤痘",
            "袜子内裤每天换"
        )
        for (n in set) {
            if (!have.contains(n)) list.add(Habit("s" + System.currentTimeMillis() + n.hashCode(), n, mutableSetOf()))
        }
        saveHabits(list)
    }

    // ---------------- 睡眠记录 ----------------
    class Sleep(
        val start: Long,
        val end: Long,
        val broke: Boolean,
        /** 半夜进程被系统杀掉后自动补记的一觉（列表里标「自动补记」） */
        val auto: Boolean = false
    ) {
        val minutes: Int get() = ((end - start) / 60000L).toInt()
        val day: String
            get() {
                // 归属"哪一晚"：以睡觉那天为准
                return SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(start))
            }
    }

    fun sleeps(): MutableList<Sleep> {
        val raw = sp.getString("sleeps", "[]") ?: "[]"
        val list = mutableListOf<Sleep>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Sleep(
                        o.getLong("start"), o.getLong("end"),
                        o.optBoolean("broke", false), o.optBoolean("auto", false)
                    )
                )
            }
        } catch (_: Exception) {
        }
        list.sortByDescending { it.start }
        // 过滤历史垃圾记录（<1 分钟的"0 小时 0 分"）
        return list.filter { it.end - it.start >= 60_000L }.toMutableList()
    }

    private fun saveSleeps(list: List<Sleep>) {
        val arr = JSONArray()
        for (s2 in list) {
            arr.put(
                JSONObject().put("start", s2.start).put("end", s2.end)
                    .put("broke", s2.broke).put("auto", s2.auto)
            )
        }
        // ★ 同步落盘：进程被系统杀掉时 apply() 的异步写可能还没写完（真机踩过）
        sp.edit().putString("sleeps", arr.toString()).commit()
    }

    fun addSleep(start: Long, end: Long, broke: Boolean, auto: Boolean = false) {
        if (end <= start) return
        // ★ 不到 1 分钟不算一觉：真机踩过「半夜进程被杀 → 早上假锁一回 → 记出 0 小时 0 分的垃圾记录」
        if (end - start < 60_000L) return
        val l = sleeps()
        l.add(0, Sleep(start, end, broke, auto))
        saveSleeps(l.take(200))
    }

    fun delSleep(start: Long, end: Long) {
        saveSleeps(sleeps().filterNot { it.start == start && it.end == end })
    }

    /** 当前这一觉的开始时间（0 = 没在睡） */
    fun sleepStart(): Long = sp.getLong("sleepStart", 0L)

    /** ★ 同步落盘：这个值丢了 = 整夜记录白睡（vivo 会杀后台，2026-09-21 真机事故） */
    fun setSleepStart(v: Long) = sp.edit().putLong("sleepStart", v).commit()

    /**
     * 每次 tick 落一次「App 还活着」的心跳（15 秒一次，用 apply 就够）。
     * 用途：进程被杀之后再进来时，判断这一觉是不是断过（见 recoverStaleSleep）。
     */
    fun lastAlive(): Long = sp.getLong("lastAlive", 0L)
    fun setLastAlive(v: Long) = sp.edit().putLong("lastAlive", v).apply()

    /**
     * 上一次「App 被系统杀掉」的时间（开机/进程重启时写）。
     * 只用于日志，不参与判定。
     */
    fun lastKilled(): Long = sp.getLong("lastKilled", 0L)
    fun setLastKilled(v: Long) = sp.edit().putLong("lastKilled", v).commit()

    /**
     * 半夜 App 被系统杀了 / 被省电策略冻结很久之后的「补记」。
     *
     * 真机事故（2026-09-21）：vivo 夜里把进程杀掉 → 悬浮窗和计时一起没了。
     * 早上再进 App，sleepStart 归零 → 任何入口又建了一个「刚入睡」的新会话，
     * 于是：整夜没记录 + 列表里多出「0 小时 0 分（提前解锁）」的垃圾。
     *
     * 规则：正在睡 + 心跳断了 10 分钟以上 + 已经过了「早上好」门禁（默认 4:00）
     *   → 把这一觉按「入睡 → 现在」补记下来并结束它（标「自动补记」）。
     * 还没到门禁（还在夜里）→ 不补记，交给锁机把睡眠界面铺回来继续计时。
     * 返回补记的分钟数；没补记返回 -1。
     */
    fun recoverStaleSleep(): Int {
        val start = sleepStart()
        if (start <= 0L) return -1
        val now = System.currentTimeMillis()
        val alive = if (lastAlive() > start) lastAlive() else start
        if (now - alive < 10 * 60_000L) return -1   // 10 分钟内还活着 = 正常在睡，别乱补
        if (!canWakeNow()) return -1                // 还在夜里 → 恢复锁机，不动记录
        addSleep(start, now, false, auto = true)
        setSleepStart(0L)
        setMorningMs(now)
        return ((now - start) / 60000L).toInt()
    }

    /**
     * 上一觉「结束」的时间（点了「早上好」/「我起床了」/长按提前解锁）。
     * 用途：这一觉已经结束了，本次锁机时段内就不要再把手机锁回来。
     */
    fun morningMs(): Long = sp.getLong("morningMs", 0L)
    fun setMorningMs(v: Long) = sp.edit().putLong("morningMs", v).commit()

    /** 今天 0 点（毫秒） */
    fun todayStartMs(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun lastSleep(): Sleep? = sleeps().firstOrNull()

    fun avgSleep7(): Int {
        val l = sleeps().take(7)
        if (l.isEmpty()) return 0
        return l.sumOf { it.minutes } / l.size
    }

    /** 最早可起时间（默认 4:00） */
    fun earlyH(): Int = sp.getInt("earlyH", 4)
    fun earlyM(): Int = sp.getInt("earlyM", 0)
    fun setEarly(h: Int, m: Int) = sp.edit().putInt("earlyH", h).putInt("earlyM", m).apply()

    /** 「早上好」窗口的上限小时（默认 12 点；超过这个点就不是"刚睡醒"了） */
    fun wakeUntilH(): Int = sp.getInt("wakeUntilH", 12)
    fun setWakeUntilH(v: Int) = sp.edit().putInt("wakeUntilH", v.coerceIn(6, 23)).apply()
    fun earlyHhmm(): String = "%02d:%02d".format(earlyH(), earlyM())

    /**
     * 允许起床的时间点：从「入睡时间」所在那天算 4:00；
     * 若入睡时间已过 4:00（例如 22:50 睡），门槛顺延到**次日** 4:00。
     */
    fun wakeThreshold(startTs: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = if (startTs > 0) startTs else System.currentTimeMillis()
        cal.set(Calendar.HOUR_OF_DAY, earlyH())
        cal.set(Calendar.MINUTE, earlyM())
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (startTs > 0 && startTs >= cal.timeInMillis) cal.add(Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
    }

    /** 现在能不能起床（关掉门禁则始终可以） */
    fun canWakeNow(): Boolean {
        if (!flag("earlyGate")) return true
        val start = sleepStart()
        return System.currentTimeMillis() >= wakeThreshold(start)
    }

    /** 距离能起床还有多少分钟（已过则为 0） */
    fun minutesToWake(): Int {
        val left = wakeThreshold(sleepStart()) - System.currentTimeMillis()
        return if (left <= 0) 0 else (left / 60000L).toInt()
    }

    /** 没有 Shizuku 时，给用户几秒双击桌面熄屏（默认 5 秒；0=不提示） */
    fun manualLockSec(): Int = sp.getInt("manualLockSec", 5)
    fun setManualLockSec(v: Int) = sp.edit().putInt("manualLockSec", v.coerceIn(0, 30)).apply()

    /** 「晚十分钟再睡觉」的分钟数 */
    fun snoozeMin(): Int = sp.getInt("snoozeMin", 10)
    fun setSnoozeMin(v: Int) = sp.edit().putInt("snoozeMin", v.coerceIn(1, 60)).apply()

    // ---------------- 熄屏日志（自检用） ----------------
    fun lockLog(): String = sp.getString("lockLog", "") ?: ""
    fun setLockLog(v: String) = sp.edit().putString("lockLog", v).apply()

    // ---------------- 熄屏录像：失败日志（给用户截图看） ----------------
    fun recLog(): String = sp.getString("recLog", "") ?: ""
    fun setRecLog(v: String) = sp.edit().putString("recLog", v).apply()

    // ---------------- 熄屏录像设置 ----------------
    fun recFront(): Boolean = sp.getBoolean("recFront", true)
    fun setRecFront(v: Boolean) = sp.edit().putBoolean("recFront", v).apply()

    /** 最长录制分钟数，0 = 手动停 */
    fun recMinutes(): Int = sp.getInt("recMinutes", 30)
    fun setRecMinutes(v: Int) = sp.edit().putInt("recMinutes", v.coerceIn(0, 600)).apply()

    /** 「锁 10 分钟」的默认分钟数 */
    fun quickLockMin(): Int = sp.getInt("quickLockMin", 10)
    fun setQuickLockMin(v: Int) = sp.edit().putInt("quickLockMin", v.coerceIn(1, 180)).apply()

    /** 连续"按时锁机"天数 */
    fun lockStreak(): Int = sp.getInt("lockStreak", 0)
    fun bumpLockStreak() = sp.edit().putInt("lockStreak", lockStreak() + 1).apply()

    /** 破戒（长按解锁）次数 */
    fun breaks(): Int = sp.getInt("breaks", 0)
    fun bumpBreaks() = sp.edit().putInt("breaks", breaks() + 1).apply()

    fun lastLockDay(): String = sp.getString("lastLockDay", "") ?: ""
    fun setLastLockDay(d: String) = sp.edit().putString("lastLockDay", d).apply()

    fun resetAll() = sp.edit().clear().apply()
}
