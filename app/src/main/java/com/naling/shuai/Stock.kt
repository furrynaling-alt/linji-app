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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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

    fun bridge(act: Context): Store.PBridge? {
        val all = Store.pBridges()
        return all.firstOrNull { it.on && it.url.contains("stock") } ?: all.firstOrNull { it.on }
    }

    fun fetch(url: String, tk: String): JSONObject? {
        return try {
            val u = URL(if (url.contains("?")) "$url&token=$tk" else "$url?token=$tk")
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 12000
            c.requestMethod = "GET"
            val txt = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            JSONObject(txt)
        } catch (e: Exception) {
            null
        }
    }

    fun screen(act: MainActivity): View {
        val sc = ScrollView(act)
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(Ui.dp(act, 16f), Ui.dp(act, 8f), Ui.dp(act, 16f), Ui.dp(act, 24f))
        col.addView(Ui.bigTitle(act, "股票监控"))
        val sub = Ui.tv(act, "只读：行情/自选/持仓，不下单。数据走你自己加的插件桥。", 12f, Ui.SUB)
        col.addView(sub)
        val head = Ui.card(act)
        val moodTv = Ui.tv(act, "加载中…", 13f, Ui.TXT)
        val moneyTv = Ui.tv(act, "", 13f, Ui.TXT)
        head.addView(moodTv)
        head.addView(moneyTv)
        col.addView(head)
        val box = LinearLayout(act)
        box.orientation = LinearLayout.VERTICAL
        col.addView(box)
        val btn = TextView(act)
        btn.text = "  刷新  "
        btn.setPadding(Ui.dp(act, 12f), Ui.dp(act, 8f), Ui.dp(act, 12f), Ui.dp(act, 8f))
        btn.setBackgroundColor(Color.parseColor("#d81e2c"))
        btn.setTextColor(Color.WHITE)
        val wrap = LinearLayout(act)
        wrap.gravity = Gravity.CENTER
        wrap.setPadding(0, Ui.dp(act, 10f), 0, 0)
        wrap.addView(btn)
        col.addView(wrap)

        fun load() {
            val b = bridge(act)
            if (b == null) {
                moodTv.text = "还没配插件桥"
                moneyTv.text = "去 设置 → APP桥 → 添加插件桥：\n地址 https://furry.gov.naling.net/linji/stock.json\n令牌填我给的那串"
                return
            }
            moodTv.text = "刷新中…"
            Thread {
                val d = fetch(b.url, b.token)
                h.post {
                    if (d == null) {
                        moodTv.text = "拉取失败（检查网络 / 地址 / 令牌）"
                        return@post
                    }
                    moodTv.text = d.optString("market", "")
                    moneyTv.text = "现金 " + d.optDouble("cash", 0.0) + " 元 · 总资产 " + d.optDouble("total", 0.0) + " 元"
                    box.removeAllViews()
                    val arr = d.optJSONArray("items") ?: return@post
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val chg = o.optDouble("chg", 0.0)
                        val card = Ui.card(act)
                        val r1 = Ui.row(act)
                        val t1 = Ui.tv(act, o.optString("name") + "  " + o.optString("code"), 15f, Ui.TXT)
                        t1.setTypeface(t1.typeface, android.graphics.Typeface.BOLD)
                        val t2 = Ui.tv(act, (if (chg >= 0) "+" else "") + String.format("%.2f", chg) + "%", 15f,
                                if (chg >= 0) Color.parseColor("#d81e2c") else Color.parseColor("#1e9e5a"))
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
                            card.addView(sv, LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 56f)))
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
