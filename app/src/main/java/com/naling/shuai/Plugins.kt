package com.naling.shuai

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/**
 * 棂记 · 插件（2026-09-22 纳棂：设置里加一个插件页，让用户跟自己的 AI 商量后加小卡片）
 * 设计原则：插件是**声明式 JSON**，不是代码 ——
 *   ① 安全（不加载外部代码）② 小白也能用（让 AI 写 JSON，粘回来就行）
 *   ③ 内置模板覆盖高频需求（股票/服务器/任意 URL）
 *
 * 一个插件长这样：
 * {
 *   "id": "stock600519", "name": "贵州茅台", "icon": "📈",
 *   "url": "https://qt.gtimg.cn/q=sh600519", "enc": "gbk",
 *   "pick": "split:~:3",                // 取值方式：split:<分隔符>:<第几个> | json:<a.b.0.c> | regex:<正则>:<组号>
 *   "templ": "¥{v}", "sub": "涨跌 {chg}%",
 *   "chg_pick": "split:~:32",           // 可选：哪个字段决定红涨绿跌
 *   "refresh": 60                       // 秒
 * }
 */
object Plugins {

    private const val KEY = "plugins_v1"

    class P(
        var id: String = "",
        var name: String = "插件",
        var icon: String = "🧩",
        var url: String = "",
        var enc: String = "utf-8",
        var pick: String = "",
        var templ: String = "{v}",
        var sub: String = "",
        var chgPick: String = "",
        var refresh: Int = 60
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id); put("name", name); put("icon", icon); put("url", url); put("enc", enc)
            put("pick", pick); put("templ", templ); put("sub", sub); put("chg_pick", chgPick)
            put("refresh", refresh)
        }

        companion object {
            fun from(o: JSONObject): P = P(
                o.optString("id", System.currentTimeMillis().toString()),
                o.optString("name", "插件"), o.optString("icon", "🧩"),
                o.optString("url", ""), o.optString("enc", "utf-8"),
                o.optString("pick", ""), o.optString("templ", "{v}"),
                o.optString("sub", ""), o.optString("chg_pick", ""),
                o.optInt("refresh", 60)
            )
        }
    }

    fun load(c: Context): MutableList<P> {
        val raw = Store.prefs().getString(KEY, null) ?: return mutableListOf()
        val out = mutableListOf<P>()
        try {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) out.add(P.from(a.getJSONObject(i)))
        } catch (_: Exception) {
        }
        return out
    }

    fun save(c: Context, list: List<P>) {
        val a = JSONArray()
        for (p in list) a.put(p.toJson())
        Store.prefs().edit().putString(KEY, a.toString()).apply()
    }

    fun add(c: Context, p: P) {
        val list = load(c)
        list.removeAll { it.id == p.id }
        list.add(p)
        save(c, list)
    }

    fun remove(c: Context, id: String) {
        val list = load(c)
        list.removeAll { it.id == id }
        save(c, list)
    }

    /** 内置股票插件（腾讯行情，零配置：只填代码） */
    fun stock(code: String): P {
        val c = code.trim().lowercase()
        val full = if (c.startsWith("sh") || c.startsWith("sz")) c
        else if (c.startsWith("6") || c.startsWith("5")) "sh$c" else "sz$c"
        return P(id = "stock_$full", name = full.uppercase(), icon = "📈",
            url = "https://qt.gtimg.cn/q=$full", enc = "gbk",
            pick = "split:~:3", templ = "{v}", sub = "{chg}", chgPick = "split:~:32", refresh = 60)
    }

    // ---------- 取值 ----------
    private fun pickOne(text: String, rule: String): String {
        return try {
            val parts = rule.split(":", limit = 3)
            when (parts.getOrNull(0)) {
                "split" -> {
                    val sep = if (parts.size > 1) parts[1] else "~"
                    val idx = parts.getOrNull(2)?.toIntOrNull() ?: 0
                    val arr = text.split(sep)
                    arr.getOrElse(idx) { "" }.trim()
                }
                "json" -> {
                    val path = parts.getOrNull(1) ?: return ""
                    var node: Any = JSONObject(text)
                    for (k in path.split(".")) {
                        node = when (node) {
                            is JSONObject -> node.opt(k) ?: return ""
                            is JSONArray -> node.opt(k.toIntOrNull() ?: 0) ?: return ""
                            else -> return ""
                        }
                    }
                    node.toString()
                }
                "regex" -> {
                    val pat = parts.getOrNull(1) ?: return ""
                    val g = parts.getOrNull(2)?.toIntOrNull() ?: 1
                    val m = Regex(pat).find(text) ?: return ""
                    m.groupValues.getOrElse(g) { "" }
                }
                else -> text
            }
        } catch (_: Exception) {
            ""
        }
    }

    /** 拉一次：回调 (大字, 副文本, 涨跌值或 null) */
    fun fetch(p: P, cb: (String, String, Double?) -> Unit) {
        Thread {
            var text = ""
            try {
                val conn = (URL(p.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000; readTimeout = 8000
                    setRequestProperty("User-Agent", "Linji/2.43")
                }
                val bytes = conn.inputStream.readBytes()
                text = try {
                    String(bytes, Charset.forName(if (p.enc.equals("gbk", true)) "GBK" else "UTF-8"))
                } catch (_: Exception) {
                    String(bytes, Charsets.UTF_8)
                }
            } catch (e: Exception) {
                android.os.Handler(Looper.getMainLooper()).post { cb("—", "取数失败：${e.message ?: ""}", null) }
                return@Thread
            }
            val v = pickOne(text, p.pick)
            val chg = if (p.chgPick.isBlank()) null else pickOne(text, p.chgPick).toDoubleOrNull()
            val big = p.templ.replace("{v}", v)
            val subTxt = p.sub.replace("{v}", v).replace("{chg}", if (chg == null) "" else String.format("%+.2f%%", chg))
            Handler(Looper.getMainLooper()).post { cb(big, subTxt, chg) }
        }.start()
    }

    /** 给 AI 的插件规格说明（用户复制走，跟自己的 AI 商量后把 JSON 粘回来） */
    fun aiSpec(): String = """
你是我的插件生成助手。我要给手机 App「棂记」写一个插件（就是一个 JSON，粘回 App 即可用）。

【输出必须是**一个 JSON 对象**，不要解释文字，字段如下】
{
  "id": "唯一英文 id",
  "name": "卡片名，例：贵州茅台",
  "icon": "一个 emoji，例：📈",
  "url": "要请求的地址，必须 https，返回 JSON 或纯文本",
  "enc": "utf-8 或 gbk",
  "pick": "取值方式",
  "templ": "大字模板，用 {v} 代入",
  "sub": "小字模板，可用 {v} 和 {chg}",
  "chg_pick": "可选：哪个字段是涨跌幅（数字），用来红涨绿跌",
  "refresh": 60
}

【pick 三选一】
- "split:分隔符:第几个"  例：腾讯行情是 "split:~:3"（按 ~ 切开取第 4 段）
- "json:路径"            例：接口返回 {"data":{"price":12.3}} → "json:data.price"；数组用下标，如 "json:list.0.name"
- "regex:正则:第几组"    例："regex:现价[:：]\s*([\d.]+):1"

【要求】
1. 只用公开可访问、无需登录的接口；不要带我的密钥
2. 值要短（卡片只能显示十几个字）
3. 只输出 JSON，别的什么都不要说

【我要的插件】：（在这里写你要看什么，例：我要看上证指数今天的点位和涨跌幅）
""".trimIndent()
}
