package com.naling.shuai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SparkView(c: Context) : View(c) {
    var data = FloatArray(0)
        set(v) {
            field = v
            invalidate()
        }
    var up = true
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (data.size < 2) return
        val lo = data.min()
        val hi = data.max()
        val span = if (hi - lo < 1e-6f) 1f else hi - lo
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = h * 0.12f
        path.reset()
        for (i in data.indices) {
            val x = w * i / (data.size - 1).toFloat()
            val y = h - pad - (data[i] - lo) / span * (h - pad * 2)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = h * 0.06f
        p.color = if (up) Color.parseColor("#d81e2c") else Color.parseColor("#1e9e5a")
        canvas.drawPath(path, p)
    }
}

object Stock {

    private val h = Handler(Looper.getMainLooper())

    class Src(val url: String, val tk: String, val label: String)

    fun sources(c: Context): MutableList<Src> {
        val out = mutableListOf<Src>()
        val all = try {
            Store.pBridges()
        } catch (_: Exception) {
            mutableListOf<Store.PBridge>()
        }
        for (pb in all) if (pb.on && pb.url.contains("stock")) out.add(Src(pb.url, pb.token, pb.name))
        for (pb in all) if (pb.on && !pb.url.contains("stock")) out.add(Src(pb.url, pb.token, pb.name))
        out.add(Src(Store.serverBase() + "/linji/stock.json", Bridge.token(c), "主桥令牌"))
        return out
    }

    fun hasPlugin(c: Context): Boolean = try {
        Store.pBridges().any { it.on && it.url.contains("stock") }
    } catch (_: Exception) {
        false
    }

    private fun host(u: String): String = try {
        val s = u.substringAfter("//").substringBefore("/")
        if (s.isBlank()) u.substring(0, u.length.coerceAtMost(40)) else s
    } catch (_: Exception) {
        u
    }

    private fun get(url: String, tk: String): Pair<Int, String> {
        return try {
            val u = URL(if (url.contains("?")) "$url&token=$tk" else "$url?token=$tk")
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 12000
            c.requestMethod = "GET"
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            c.disconnect()
            Pair(code, body)
        } catch (e: Exception) {
            Pair(-1, (e.javaClass.simpleName + " " + (e.message ?: "")).trim())
        }
    }

    private fun pick(c: Context): Triple<Src?, Int, String> {
        var code = -2
        var why = "没有可用数据源"
        for (s in sources(c)) {
            val r = get(s.url, s.tk)
            if (r.first in 200..299) {
                if (r.second.contains("bad token")) {
                    code = 403
                    why = "令牌不对（${s.label}）"
                    continue
                }
                return Triple(s, r.first, r.second)
            }
            code = r.first
            why = if (r.first < 0) r.second else "HTTP " + r.first + "（${s.label}）"
        }
        return Triple(null, code, why)
    }

    fun screen(act: MainActivity): View {
        val sc = ScrollView(act)
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(Ui.dp(act, 16f), Ui.dp(act, 26f), Ui.dp(act, 16f), Ui.dp(act, 24f))
        col.addView(Ui.bigTitle(act, "股票监控"))
        col.addView(Ui.tv(act, "只读：行情 / 自选 / 持仓，不下单。", 12f, Ui.SUB))

        val head = Ui.card(act)
        val moodTv = Ui.tv(act, "加载中…", 13f, Ui.TXT)
        val moneyTv = Ui.tv(act, "", 13f, Ui.TXT)
        val srcTv = Ui.tv(act, "", 11f, Ui.SUB)
        head.addView(moodTv)
        head.addView(moneyTv)
        head.addView(srcTv)
        col.addView(head)

        val box = LinearLayout(act)
        box.orientation = LinearLayout.VERTICAL
        col.addView(box)

        val btn = TextView(act)
        btn.text = "  刷新  "
        btn.setPadding(Ui.dp(act, 14f), Ui.dp(act, 9f), Ui.dp(act, 14f), Ui.dp(act, 9f))
        btn.setBackgroundColor(Color.parseColor("#d81e2c"))
        btn.setTextColor(Color.WHITE)
        btn.isClickable = true
        val wrap = LinearLayout(act)
        wrap.gravity = Gravity.CENTER
        wrap.setPadding(0, Ui.dp(act, 12f), 0, 0)
        wrap.addView(btn)
        col.addView(wrap)

        val fmt = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

        fun load() {
            moodTv.text = "刷新中…"
            Thread {
                val r = pick(act)
                val s = r.first
                val body = r.third
                h.post {
                    if (s == null) {
                        moodTv.text = "拉取失败：" + body
                        moneyTv.text = ""
                        val line = StringBuilder()
                        line.append("试过的数据源：\n")
                        for (x in sources(act)) line.append("· ").append(x.label).append("  ").append(host(x.url)).append("\n")
                        line.append("\n去 设置 → APP桥 里检查地址/令牌；或把主桥（").append(host(Store.serverBase())).append("）的令牌填好。")
                        srcTv.text = line.toString()
                        return@post
                    }
                    val d = try {
                        JSONObject(body)
                    } catch (_: Exception) {
                        null
                    }
                    if (d == null) {
                        moodTv.text = "返回的不是 JSON"
                        srcTv.text = body.take(200)
                        return@post
                    }
                    moodTv.text = d.optString("market", "（市场情绪暂无）")
                    moneyTv.text = "现金 " + d.optDouble("cash", 0.0) + " 元 · 总资产 " + d.optDouble("total", 0.0) + " 元"
                    srcTv.text = "数据源 " + s.label + " · " + host(s.url) + " · " + fmt.format(Date())
                    box.removeAllViews()
                    val arr = d.optJSONArray("items") ?: return@post
                    if (arr.length() == 0) {
                        box.addView(Ui.tv(act, "自选 / 持仓是空的（服务器 watchlist.json + 模拟盘持仓都没东西）", 12f, Ui.SUB))
                        return@post
                    }
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val chg = o.optDouble("chg", 0.0)
                        val card = Ui.card(act)
                        val r1 = Ui.row(act)
                        val t1 = Ui.tv(act, o.optString("name") + "  " + o.optString("code"), 15f, Ui.TXT)
                        t1.setTypeface(t1.typeface, android.graphics.Typeface.BOLD)
                        val t2 = Ui.tv(
                            act, (if (chg >= 0) "+" else "") + String.format("%.2f", chg) + "%", 15f,
                            if (chg >= 0) Color.parseColor("#d81e2c") else Color.parseColor("#1e9e5a")
                        )
                        t2.setTypeface(t2.typeface, android.graphics.Typeface.BOLD)
                        r1.addView(t1, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        r1.addView(t2)
                        card.addView(r1)
                        val r2 = Ui.row(act)
                        var line = "现价 " + o.optDouble("price", 0.0)
                        if (o.optBoolean("holding")) {
                            val pnl = o.optDouble("pnl", 0.0)
                            line += "  ·  成本 " + o.optDouble("buy", 0.0) + "  ·  盈亏 " +
                                    (if (pnl >= 0) "+" else "") + String.format("%.0f", pnl) + " 元"
                        }
                        r2.addView(Ui.tv(act, line, 12.5f, Ui.SUB))
                        card.addView(r2)
                        val k = o.optJSONArray("k")
                        if (k != null && k.length() > 1) {
                            val f = FloatArray(k.length())
                            for (j in 0 until k.length()) f[j] = k.optDouble(j, 0.0).toFloat()
                            val sv = SparkView(act)
                            sv.data = f
                            sv.up = f[f.size - 1] >= f[0]
                            card.addView(
                                sv, LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 56f)
                                )
                            )
                        }
                        box.addView(card)
                    }
                }
            }.start()
        }
        btn.setOnClickListener { load() }
        val tick = object : Runnable {
            override fun run() {
                if (sc.isAttachedToWindow) {
                    load()
                    h.postDelayed(this, 15000)
                }
            }
        }
        sc.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                h.postDelayed(tick, 200)
            }

            override fun onViewDetachedFromWindow(v: View) {
                h.removeCallbacks(tick)
            }
        })
        return sc
    }
}
