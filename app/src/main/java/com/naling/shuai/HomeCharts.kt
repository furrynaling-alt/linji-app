package com.naling.shuai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

object HomeCharts {

    val SRC_NAMES = arrayOf("睡眠时长(小时)", "打卡完成率(%)", "番茄个数", "记账支出(元)")
    val TYPE_NAMES = arrayOf("折线图", "柱状图", "饼图", "表格")

    fun values(src: Int): List<Float> {
        val days = Store.lastDays(7)
        return when (src) {
            1 -> days.map { Store.habitRateOn(it) * 100f }
            2 -> days.map { Store.pomoOn(it).toFloat() }
            3 -> days.map { daySpend(it) }
            else -> days.map { (Store.sleeps().firstOrNull { s -> s.day == it }?.minutes ?: 0) / 60f }
        }
    }

    private fun daySpend(day: String): Float = try {
        Book.recs().filter { it.date == day && !it.income }.sumOf { it.amount }.toFloat()
    } catch (_: Exception) {
        0f
    }

    fun labels(): List<String> = Store.lastDays(7).map { it.substring(5) }

    fun unit(src: Int): String = when (src) {
        1 -> "%"
        2 -> "个"
        3 -> "元"
        else -> "小时"
    }

    class PieView(c: Context, private val values: List<Float>,
                  private val labels: List<String>, private val unit: String) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Ui.SUB; textSize = Ui.dp(c, 10f).toFloat()
        }

        private val colors = intArrayOf(
            0xFFD81E2C.toInt(), 0xFF3B6EA5.toInt(), 0xFFE8A33D.toInt(),
            0xFF1E9E5A.toInt(), 0xFF8E6BD1.toInt(), 0xFF4FB3C7.toInt(), 0xFFB0B4BC.toInt()
        )

        override fun onDraw(canvas: Canvas) {
            val total = values.sum()
            val cx = width / 2f
            val cy = height / 2f - Ui.dp(context, 6f)
            val r = (minOf(width / 2f, height / 2f) - Ui.dp(context, 24f)).coerceAtLeast(10f)
            val box = RectF(cx - r, cy - r, cx + r, cy + r)
            if (total <= 0f) {
                p.color = 0xFFE5E5EA.toInt()
                canvas.drawCircle(cx, cy, r, p)
                canvas.drawText("暂无数据", cx - Ui.dp(context, 20f), cy, t)
                return
            }
            var start = -90f
            for (i in values.indices) {
                val sweep = values[i] / total * 360f
                p.color = colors[i % colors.size]
                canvas.drawArc(box, start, sweep, true, p)
                start += sweep
            }
            p.color = Color.WHITE
            canvas.drawCircle(cx, cy, r * 0.45f, p)
            var ly = Ui.dp(context, 12f).toFloat()
            for (i in values.indices) {
                p.color = colors[i % colors.size]
                canvas.drawRect(Ui.dp(context, 8f).toFloat(), ly,
                    Ui.dp(context, 18f).toFloat(), ly + Ui.dp(context, 8f), p)
                canvas.drawText("${labels.getOrElse(i) { "" }}  ${values[i].toInt()}$unit",
                    Ui.dp(context, 22f).toFloat(), ly + Ui.dp(context, 8f), t)
                ly += Ui.dp(context, 14f)
            }
        }
    }

    fun table(c: Context, values: List<Float>, labels: List<String>, unit: String): View {
        val col = LinearLayout(c)
        col.orientation = LinearLayout.VERTICAL
        for (i in labels.indices) {
            val row = Ui.row(c)
            row.setPadding(0, Ui.dp(c, 6f), 0, Ui.dp(c, 6f))
            val a = Ui.tv(c, labels[i], 13f, Ui.SUB)
            a.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            val b = Ui.tv(c, "${values.getOrElse(i) { 0f }} $unit", 13f, Ui.TXT, true)
            row.addView(a)
            row.addView(b)
            col.addView(row)
            if (i != labels.indices.last) col.addView(Ui.divider(c))
        }
        return col
    }
}
