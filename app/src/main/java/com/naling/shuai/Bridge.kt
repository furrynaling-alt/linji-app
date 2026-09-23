package com.naling.shuai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 棂记 v2.17 · App 桥（MCP 风格的「服务器 AI ↔ App」通道，2026-09-21 纳棂要求）
 *
 * 为什么这么设计（不是标准 MCP server 反向连手机）：
 *   手机在运营商 NAT 后面，服务器**无法**主动连它；开反向端口/隧道既不安全也不稳。
 *   所以走**出站轮询**：App 每隔 N 秒 POST 一次（带上自己的状态），服务器返回要执行的指令。
 *   → 不开放任何端口、不做隧道、耗电可控；断网就下一轮重试。
 *
 * 协议（POST JSON，token 在设置里配）：
 *   上行 {"v":1,"device":"...","status":{...},"acks":[...]}
 *   下行 {"cmds":[{"id":"..","cmd":"notify","title":"..","text":".."}, ...]}
 * 支持的指令：notify / toast / get(cards|status|wage|sleep) / flag(key,value) / ping
 *   wage_add(date,start,end[,hours,rate,shift,note,lunch,dinner]) —— 服务器直接往手机补一条工时（补卡）
 *   wage_del(wid|date)
 */
object Bridge {

    fun urlDefault(c: Context): String = Store.serverBase() + "/linji/bridge.php"

    private var th: Thread? = null
    @Volatile private var stop = false

    private fun sp(c: Context) = c.getSharedPreferences("shuai", Context.MODE_PRIVATE)

    fun enabled(c: Context) = sp(c).getBoolean("bridgeOn", false)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("bridgeOn", v).apply()

    /** 主桥或任意插件桥开着 = 有活干（插件桥单独也能用） */
    fun anyOn(c: Context): Boolean =
        enabled(c) || Store.pBridges().any { it.on && it.url.isNotBlank() }

    fun url(c: Context) = sp(c).getString("bridgeUrl", "")?.takeIf { it.isNotBlank() } ?: urlDefault(c)
    fun setUrl(c: Context, v: String) = sp(c).edit().putString("bridgeUrl", v).apply()

    fun token(c: Context) = sp(c).getString("bridgeToken", "") ?: ""
    fun setToken(c: Context, v: String) = sp(c).edit().putString("bridgeToken", v).apply()

    fun lastSync(c: Context) = sp(c).getString("bridgeLast", "-") ?: "-"

    private fun acks(c: Context): JSONArray = try {
        JSONArray(sp(c).getString("bridgeAcks", "[]"))
    } catch (_: Exception) {
        JSONArray()
    }

    private fun addAck(c: Context, id: String, ok: Boolean, msg: String) {
        val a = acks(c)
        a.put(JSONObject().apply { put("id", id); put("ok", ok); put("msg", msg) })
        val keep = JSONArray()
        for (i in maxOf(0, a.length() - 20) until a.length()) keep.put(a.get(i))
        sp(c).edit().putString("bridgeAcks", keep.toString()).apply()
    }

    /** 外部调用：起前台服务（保活）+ 轮询线程 */
    fun start(c: Context) {
        val app = c.applicationContext
        try {
            val i = Intent(app, BridgeService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i)
        } catch (_: Exception) {
        }
        startThread(app)
    }

    /** 只起轮询线程（服务内部用，避免递归起服务） */
    fun startThread(c: Context) {
        if (th != null) return
        stop = false
        val app = c.applicationContext
        th = Thread {
            while (!stop) {
                try {
                    if (anyOn(app)) sync(app)
                } catch (_: Exception) {
                }
                try {
                    Thread.sleep(if (anyOn(app)) 20_000L else 15_000L)
                } catch (_: InterruptedException) {
                    break
                }
            }
            th = null
        }.also { it.isDaemon = true; it.name = "linji-bridge"; it.start() }
    }

    fun syncNow(c: Context) {
        if (!anyOn(c)) return
        val app = c.applicationContext
        Thread {
            try {
                sync(app)
            } catch (_: Exception) {
            }
        }.start()
    }

    fun ensureStarted(c: Context) {
        if (anyOn(c)) start(c)
    }

    fun stop(c: Context) {
        try {
            c.applicationContext.stopService(Intent(c.applicationContext, BridgeService::class.java))
        } catch (_: Exception) {
        }
        stopThread()
    }

    fun stopThread() {
        stop = true
        th?.interrupt()
        th = null
    }

    /** 手动同步一次（开发者模式里的按钮；回调在主线程之外拿结果字符串） */
    fun manualSync(c: Context, cb: (String) -> Unit) {
        val app = c.applicationContext
        Thread {
            try {
                sync(app)
                cb("同步完成：" + lastSync(app))
            } catch (e: Exception) {
                cb("同步失败：" + (e.message ?: e.javaClass.simpleName))
            }
        }.start()
    }

    /** 一轮同步：主桥 + 所有开着的插件桥，各自上报 / 各自收指令 */
    private fun sync(c: Context) {
        try {
            syncOne(c, url(c), token(c), 0L)
        } catch (_: Exception) {
        }
        for (b in Store.pBridges()) {
            if (!b.on || b.url.isBlank()) continue
            try {
                syncOne(c, b.url, b.token, b.id)
            } catch (_: Exception) {
            }
        }
        sp(c).edit().putString("bridgeAcks", "[]").apply()
    }

    private fun keyOf(id: Long): String = if (id == 0L) "bridgeLast" else "bLast_$id"

    fun lastOf(c: Context, id: Long): String = sp(c).getString(keyOf(id), "-") ?: "-"

    private fun syncOne(c: Context, u: String, tk: String, id: Long) {
        if (u.isBlank()) {
            sp(c).edit().putString(keyOf(id), "未填地址").apply()
            return
        }
        val up = JSONObject().apply {
            put("v", 1)
            put("device", Build.MODEL)
            put("version", Update.curVersionName(c))
            put("status", status(c))
            put("acks", acks(c))
        }
        val conn = (URL(u).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000; readTimeout = 8000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("X-Linji-Token", tk)
        }
        conn.outputStream.use { it.write(up.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val body = if (code in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else ""
        sp(c).edit().putString(keyOf(id), nowStr() + " (HTTP $code)").apply()
        if (body.isBlank()) return

        val cmds = JSONObject(body).optJSONArray("cmds") ?: return
        for (i in 0 until cmds.length()) {
            val o = cmds.optJSONObject(i) ?: continue
            val id = o.optString("id", "?")
            val cmd = o.optString("cmd")
            try {
                when (cmd) {
                    "notify" -> {
                        noti(c, o.optString("title", "棂记"), o.optString("text", ""))
                        addAck(c, id, true, "notified")
                    }
                    "toast" -> addAck(c, id, true, "toast:" + o.optString("text"))
                    "ping" -> addAck(c, id, true, "pong")
                    "get" -> addAck(c, id, true, getWhat(c, o.optString("what")))
                    "set" -> {
                        val k = o.optString("key")
                        val v = o.optString("value")
                        val hm = v.split(":")
                        when (k) {
                            "lock_time" -> if (hm.size == 2) Store.setLockTime(hm[0].trim().toInt(), hm[1].trim().toInt())
                            "wake_time" -> if (hm.size == 2) Store.setWakeTime(hm[0].trim().toInt(), hm[1].trim().toInt())
                            "early_time" -> if (hm.size == 2) Store.setEarly(hm[0].trim().toInt(), hm[1].trim().toInt())
                            "gate" -> Store.setFlag("earlyGate", v == "true" || v == "1")
                            "hold_sec" -> v.toIntOrNull()?.let { Store.setHoldSec(it) }
                            "quick_lock_min" -> v.toIntOrNull()?.let { Store.setQuickLockMin(it) }
                            "snooze_min" -> v.toIntOrNull()?.let { Store.setSnoozeMin(it) }
                            "wake_until_h" -> v.toIntOrNull()?.let { Store.setWakeUntilH(it) }
                            "lock_on" -> {
                                Store.setLockEnabled(v == "true" || v == "1")
                                if (v != "true" && v != "1") LockService.stop(c)
                            }
                            else -> Store.setFlag(k, v == "true" || v == "1")
                        }
                        Store.syncHabitTimeNames()
                        Alarms.scheduleAll(c)
                        addAck(c, id, true, "set " + k + "=" + v)
                    }
                    "todo" -> {
                        val text = o.optString("text")
                        if (text.isBlank()) addAck(c, id, false, "empty") else {
                            Store.addTodo(text, "棂星")
                            noti(c, "棂星给你加了个待办", text)
                            addAck(c, id, true, "todo added")
                        }
                    }
                    "todo_done" -> {
                        val tid = Store.todoMatch(o.optLong("tid", -1L), o.optString("text"))
                        if (tid < 0) addAck(c, id, false, "no such todo") else {
                            Store.setTodoDone(tid, o.optBoolean("done", true))
                            addAck(c, id, true, "todo done")
                        }
                    }
                    "todo_del" -> {
                        val tid = Store.todoMatch(o.optLong("tid", -1L), o.optString("text"))
                        if (tid < 0) addAck(c, id, false, "no such todo") else {
                            Store.delTodo(tid)
                            addAck(c, id, true, "todo deleted")
                        }
                    }
                    "lock" -> {
                        val m = o.optInt("minutes", Store.quickLockMin())
                        LockService.start(c, m, LockService.MODE_PLAIN)
                        addAck(c, id, true, "locked " + m + " min")
                    }
                    "card_add" -> {
                        val list = Cards.load()
                        list.add(
                            0, Cards.C(
                                System.currentTimeMillis(), o.optString("title", "纪念日"),
                                o.optString("date", ""), o.optString("mode", "down"), null, 1
                            )
                        )
                        Cards.save(list)
                        addAck(c, id, true, "card added")
                    }
                    "plugin_add" -> {
                        val p = Plugins.P(
                            o.optString("pid", o.optString("id", System.currentTimeMillis().toString())),
                            o.optString("name", "插件"), o.optString("icon", "📈"),
                            o.optString("url", ""), o.optString("enc", "utf-8"),
                            o.optString("pick", ""), o.optString("templ", "{v}"),
                            o.optString("sub", ""), o.optString("chg_pick", ""),
                            o.optInt("refresh", 60)
                        )
                        Plugins.add(c, p)
                        addAck(c, id, true, "plugin added " + p.id)
                    }
                    "plugin_del" -> {
                        Plugins.remove(c, o.optString("pid", o.optString("id", "")))
                        addAck(c, id, true, "plugin del")
                    }
                    "plugin_clear" -> {
                        Plugins.save(c, emptyList())
                        addAck(c, id, true, "plugins cleared")
                    }
                    "card_del" -> {
                        val title = o.optString("title")
                        val list = Cards.load()
                        val keep = list.filterNot { it.title == title }
                        Cards.save(keep)
                        addAck(c, id, if (keep.size < list.size) true else false, "card del " + title)
                    }
                    "wage_add" -> {
                        val date = o.optString("date")
                        if (date.isBlank()) addAck(c, id, false, "need date") else {
                            val hv = o.optDouble("hours", -1.0)
                            val r = Wage.Rec(
                                date = date,
                                start = o.optString("start").ifBlank { Wage.workStart() },
                                end = o.optString("end").ifBlank { Wage.workEnd() },
                                rate = o.optDouble("rate", Wage.rate()),
                                lunch = o.optInt("lunch", 0),
                                dinner = o.optInt("dinner", 0),
                                shift = o.optString("shift", "白班"),
                                note = o.optString("note", "桥补录"),
                                hours = if (hv >= 0) hv else -1.0
                            )
                            Wage.addRec(r)
                            val msg = "%s  %s-%s  = %s 小时".format(r.date, r.start, r.end, Wage.hh1(Wage.hoursOf(r)))
                            noti(c, "已补一条工时", msg)
                            addAck(c, id, true, "wage $msg")
                        }
                    }
                    "wage_del" -> {
                        val wid = o.optString("wid")
                        val wdate = o.optString("date")
                        when {
                            wid.isNotBlank() -> {
                                Wage.delRec(wid); addAck(c, id, true, "wage del " + wid)
                            }
                            wdate.isNotBlank() -> {
                                Wage.delDay(wdate); addAck(c, id, true, "wage del day " + wdate)
                            }
                            else -> addAck(c, id, false, "need wid|date")
                        }
                    }
                    "flag" -> {
                        Store.setFlag(o.optString("key"), o.optBoolean("value", true))
                        addAck(c, id, true, "flag set")
                    }
                    else -> addAck(c, id, false, "unknown:$cmd")
                }
            } catch (e: Exception) {
                addAck(c, id, false, "err:" + (e.message ?: ""))
            }
        }
    }

    /** 上报给服务器 AI 的状态（这就是"监督"的数据面） */
    fun status(c: Context): JSONObject {
        val bm = c.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val out = JSONObject().apply {
            put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            put("charging", bm.isCharging)
            put("sleep_start", Store.sleepStart())
            put("lock_on", Store.lockEnabled())
            put("screen", nowStr())
        }
        val cards = JSONArray()
        for (x in Cards.load()) {
            cards.put(JSONObject().apply {
                put("title", x.title); put("date", x.date); put("mode", x.mode)
                put("days", Cards.daysOf(x))
            })
        }
        out.put("cards", cards)
        val td = JSONArray()
        for (t in Store.todos()) td.put(JSONObject().apply {
            put("id", t.id); put("text", t.text); put("done", t.done); put("from", t.from)
        })
        out.put("todos", td)
        out.put("cfg", cfg(c))
        out.put("sleeps", sleepArr(14))
        out.put("sleep_avg7", Store.avgSleep7())
        val ls = Store.lastSleep()
        if (ls != null) {
            out.put("last_start", ls.start)
            out.put("last_end", ls.end)
            out.put("last_min", ls.minutes)
            out.put("last_broke", ls.broke)
            out.put("last_auto", ls.auto)
        }
        return out
    }

    private fun sleepArr(n: Int): JSONArray {
        val a = JSONArray()
        for (s in Store.sleeps().take(n)) a.put(JSONObject().apply {
            put("start", s.start); put("end", s.end)
            put("min", s.minutes); put("broke", s.broke); put("auto", s.auto)
        })
        return a
    }

    private fun cfg(c: Context): JSONObject = JSONObject().apply {
        put("lock_time", Store.lockHhmm())
        put("wake_time", Store.wakeHhmm())
        put("early_time", Store.earlyHhmm())
        put("gate", Store.flag("earlyGate"))
        put("lock_on", Store.lockEnabled())
        put("hold_sec", Store.holdSec())
        put("quick_lock_min", Store.quickLockMin())
        put("snooze_min", Store.snoozeMin())
        put("wake_until_h", Store.wakeUntilH())
        put("server", Store.serverBase())
    }

    private fun getWhat(c: Context, what: String): String = when (what) {
        "status" -> status(c).toString()
        "cards" -> {
            val a = JSONArray()
            for (x in Cards.load()) a.put(JSONObject().apply {
                put("title", x.title); put("date", x.date); put("days", Cards.daysOf(x))
            })
            a.toString()
        }
        "sleep" -> sleepArr(14).toString()
        "wage" -> {
            val a = JSONArray()
            for (r in Wage.recs()) a.put(JSONObject().apply {
                put("id", r.id); put("date", r.date)
                put("start", r.start); put("end", r.end)
                put("hours", Wage.hoursOf(r)); put("pay", Wage.payOf(r)); put("shift", r.shift)
            })
            a.toString()
        }
        else -> "ok"
    }

    private fun nowStr(): String =
        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date())

    /** 本地通知（服务器 AI 让手机弹提醒） */
    fun noti(c: Context, title: String, text: String) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("linji_bridge", "AI 消息",
                NotificationManager.IMPORTANCE_HIGH))
        }
        val b = if (Build.VERSION.SDK_INT >= 26)
            android.app.Notification.Builder(c, "linji_bridge") else android.app.Notification.Builder(c)
        b.setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(text)
            .setAutoCancel(true)
        try {
            nm.notify(9000 + (System.currentTimeMillis() % 500).toInt(), b.build())
        } catch (_: Exception) {
        }
    }

    /** 开发者模式里看的桥日志 */
    fun logFile(c: Context) = File(c.filesDir, "bridge.log")
}
