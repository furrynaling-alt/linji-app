package com.naling.shuai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View

/** 近 7 天趋势：柱状 + 数值（用户明确不喜欢饼图，一律用柱/折线） */
class TrendView(
    c: Context,
    private val values: List<Float>,
    private val labels: List<String>,
    private val unit: String = "",
    private val lineMode: Boolean = false
) : View(c) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.TXT
        textSize = Ui.dp(c, 10f).toFloat()
        textAlign = Paint.Align.CENTER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.SUB
        textSize = Ui.dp(c, 9f).toFloat()
        textAlign = Paint.Align.CENTER
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x22000000
        strokeWidth = Ui.dp(c, 1f).toFloat()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = Ui.dp(context, 150f)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty()) return
        val padTop = Ui.dp(context, 18f).toFloat()
        val padBottom = Ui.dp(context, 18f).toFloat()
        val left = Ui.dp(context, 4f).toFloat()
        val right = width - Ui.dp(context, 4f).toFloat()
        val chartTop = padTop
        val chartBottom = height - padBottom
        val maxV = (values.maxOrNull() ?: 1f).coerceAtLeast(1f)

        // 基线
        canvas.drawLine(left, chartBottom, right, chartBottom, linePaint)

        val n = values.size
        val slot = (right - left) / n
        val barW = slot * 0.5f
        val radius = Ui.dp(context, 6f).toFloat()

        val dense = n > 40
        if (lineMode || dense) {
            val path = android.graphics.Path()
            for (i in 0 until n) {
                val ratio = (values[i] / maxV).coerceIn(0f, 1f)
                val cx = left + slot * i + slot / 2
                val cy = chartBottom - (chartBottom - chartTop) * ratio
                if (i == 0) path.moveTo(cx, cy) else path.lineTo(cx, cy)
            }
            linePaint.color = Ui.RED
            linePaint.strokeWidth = Ui.dp(context, 2.5f).toFloat()
            canvas.drawPath(path, linePaint)
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.RED }
            val rDot = Ui.dp(context, if (dense) 1.6f else 3.5f).toFloat()
            for (i in 0 until n) {
                val v = values[i]
                val ratio = (v / maxV).coerceIn(0f, 1f)
                val cx = left + slot * i + slot / 2
                val cy = chartBottom - (chartBottom - chartTop) * ratio
                canvas.drawCircle(cx, cy, rDot, dot)
                if (!dense) {
                    val txt = if (v <= 0f) "-" else if (v == v.toInt().toFloat()) v.toInt().toString() else "%.1f".format(v)
                    canvas.drawText(txt, cx, (cy - Ui.dp(context, 6f)).coerceAtLeast(textPaint.textSize), textPaint)
                }
                if (i < labels.size && labels[i].isNotEmpty()) canvas.drawText(labels[i], cx, height - Ui.dp(context, 4f).toFloat(), labelPaint)
            }
            if (dense) {
                val avg = values.average().toFloat()
                val ay = chartBottom - (chartBottom - chartTop) * (avg / maxV).coerceIn(0f, 1f)
                val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0x66D81E2C
                    strokeWidth = Ui.dp(context, 1f).toFloat()
                    pathEffect = android.graphics.DashPathEffect(
                        floatArrayOf(Ui.dp(context, 5f).toFloat(), Ui.dp(context, 4f).toFloat()), 0f
                    )
                }
                canvas.drawLine(left, ay, right, ay, dash)
            }
            if (unit.isNotEmpty()) {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Ui.SUB
                    textSize = Ui.dp(context, 9f).toFloat()
                    textAlign = Paint.Align.RIGHT
                }
                canvas.drawText("单位：$unit", right, Ui.dp(context, 10f).toFloat(), p)
            }
            return
        }
        for (i in 0 until n) {
            val v = values[i]
            val ratio = (v / maxV).coerceIn(0f, 1f)
            val barH = (chartBottom - chartTop) * ratio
            val cx = left + slot * i + slot / 2
            val rect = RectF(cx - barW / 2, chartBottom - barH, cx + barW / 2, chartBottom)
            barPaint.shader = if (barH > 0) LinearGradient(
                rect.left, rect.top, rect.left, rect.bottom,
                Ui.RED, Ui.RED_DEEP, Shader.TileMode.CLAMP
            ) else null
            barPaint.color = if (barH > 0) Ui.RED else 0xFFE5E5EA.toInt()
            if (barH > 0) {
                canvas.drawRoundRect(rect, radius, radius, barPaint)
            } else {
                val r2 = RectF(cx - barW / 2, chartBottom - Ui.dp(context, 3f), cx + barW / 2, chartBottom)
                canvas.drawRoundRect(r2, radius, radius, barPaint)
            }
            // 数值
            val txt = if (v <= 0f) "-" else {
                if (v == v.toInt().toFloat()) v.toInt().toString() else "%.1f".format(v)
            }
            canvas.drawText(
                txt, cx, (chartBottom - barH - Ui.dp(context, 4f)).coerceAtLeast(textPaint.textSize),
                textPaint
            )
            // 标签
            if (i < labels.size) {
                canvas.drawText(labels[i], cx, height - Ui.dp(context, 4f).toFloat(), labelPaint)
            }
        }
        // 单位
        if (unit.isNotEmpty()) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Ui.SUB
                textSize = Ui.dp(context, 9f).toFloat()
                textAlign = Paint.Align.RIGHT
            }
            canvas.drawText("单位：$unit", right, Ui.dp(context, 10f).toFloat(), p)
        }
    }
}
