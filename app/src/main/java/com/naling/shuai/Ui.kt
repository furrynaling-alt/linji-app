package com.naling.shuai

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** iOS 风格（浅色分组卡片）UI 工具，纯代码布局、零依赖 */
object Ui {
    const val RED = 0xFFFF3B30.toInt()        // iOS systemRed
    const val RED_DEEP = 0xFFD81E2C.toInt()
    const val BLUE = 0xFF0A84FF.toInt()
    const val BG = 0xFFF2F2F7.toInt()         // iOS 分组背景
    const val CARD = 0xFFFFFFFF.toInt()
    const val TXT = 0xFF1C1C1E.toInt()
    const val SUB = 0xFF8E8E93.toInt()
    const val LINE = 0xFFE5E5EA.toInt()

    fun dp(c: Context, v: Float): Int = (v * c.resources.displayMetrics.density).toInt()

    fun round(color: Int, radiusDp: Int, c: Context): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(c, radiusDp.toFloat()).toFloat()
        return g
    }

    fun roundStroke(color: Int, strokeColor: Int, radiusDp: Int, c: Context): GradientDrawable {
        val g = round(color, radiusDp, c)
        g.setStroke(dp(c, 1f), strokeColor)
        return g
    }

    fun grad(a: Int, b: Int, radiusDp: Int, c: Context): GradientDrawable {
        val g = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(a, b))
        g.cornerRadius = dp(c, radiusDp.toFloat()).toFloat()
        return g
    }

    // 图片只解码一次（解码大图最耗时间，是界面卡顿的主因）
    private val bmpCache = HashMap<Int, android.graphics.Bitmap>()

    private fun bmp(c: Context, resId: Int): android.graphics.Bitmap? {
        bmpCache[resId]?.let { return it }
        val b = try { BitmapFactory.decodeResource(c.resources, resId) } catch (_: Exception) { null }
        if (b != null) bmpCache[resId] = b
        return b
    }

    /** 大图背景（锁屏 / hero 用） */
    fun imageBg(c: Context, resId: Int): Drawable {
        val b = bmp(c, resId) ?: return ColorDrawable(0xFF101218.toInt())
        val d = BitmapDrawable(c.resources, b)
        d.gravity = Gravity.FILL
        return d
    }

    fun image(c: Context, resId: Int): BitmapDrawable {
        val b = bmp(c, resId)
        val d = BitmapDrawable(c.resources, b)
        d.gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
        return d
    }

    /** 矢量（SVG）图标 */
    fun icon(c: Context, resId: Int, sizeDp: Int = 24, tint: Int = TXT): ImageView {
        val iv = ImageView(c)
        iv.setImageResource(resId)
        iv.setColorFilter(tint)
        val sz = dp(c, sizeDp.toFloat())
        iv.layoutParams = LinearLayout.LayoutParams(sz, sz)
        return iv
    }

    /** TG 手感：按下去有水波纹 */
    fun ripple(c: Context, radiusDp: Int, base: Drawable? = null, rippleColor: Int = 0x1F000000): Drawable {
        val mask = round(0xFFFFFFFF.toInt(), radiusDp, c)
        return RippleDrawable(ColorStateList.valueOf(rippleColor), base, mask)
    }

    fun rippleFor(c: Context, radiusDp: Int, filled: Boolean): Drawable {
        val mask = round(0xFFFFFFFF.toInt(), radiusDp, c)
        return RippleDrawable(
            ColorStateList.valueOf(if (filled) 0x40FFFFFF else 0x1F000000),
            if (filled) grad(RED_DEEP, RED, radiusDp, c) else roundStroke(0xFFEFEFF4.toInt(), LINE, radiusDp, c),
            mask
        )
    }

    fun tv(c: Context, s: String, size: Float, color: Int = TXT, bold: Boolean = false): TextView {
        val t = TextView(c)
        t.text = s
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        t.setTextColor(color)
        if (bold) t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
        return t
    }

    /** iOS 大标题 */
    fun bigTitle(c: Context, s: String): TextView {
        val t = tv(c, s, 30f, TXT, true)
        t.setPadding(dp(c, 4f), dp(c, 6f), 0, dp(c, 10f))
        return t
    }

    fun sectionTitle(c: Context, s: String): TextView {
        val t = tv(c, s.uppercase(), 12f, SUB, true)
        t.setPadding(dp(c, 6f), dp(c, 4f), 0, dp(c, 8f))
        return t
    }

    fun card(c: Context, padDp: Int = 16): LinearLayout {
        val l = LinearLayout(c)
        l.orientation = LinearLayout.VERTICAL
        l.setBackgroundDrawable(round(CARD, 18, c))
        l.elevation = dp(c, 1.5f).toFloat()
        val p = dp(c, padDp.toFloat())
        l.setPadding(p, p, p, p)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(c, 14f)
        l.layoutParams = lp
        autoGap(l, c, 12)          // 卡片内相邻子项之间自动留白，防粘连
        return l
    }

    fun row(c: Context): LinearLayout {
        val l = LinearLayout(c)
        l.orientation = LinearLayout.HORIZONTAL
        l.gravity = Gravity.CENTER_VERTICAL
        autoGap(l, c, 8)           // 行内相邻控件之间自动留白，防粘连
        return l
    }

    fun divider(c: Context): View {
        val v = View(c)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 1f))
        lp.topMargin = dp(c, 10f)
        v.layoutParams = lp
        v.setBackgroundColor(LINE)
        return v
    }

    fun btn(c: Context, s: String, filled: Boolean = true, small: Boolean = false): Button {
        val b = Button(c)
        b.text = s
        b.isAllCaps = false
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (small) 13f else 16f)
        b.background = rippleFor(c, 14, filled)
        b.setTextColor(if (filled) Color.WHITE else TXT)
        val hh = dp(c, if (small) 34f else 46f)
        b.minHeight = hh
        b.minimumHeight = hh
        b.setPadding(dp(c, 16f), 0, dp(c, 16f), 0)
        return b
    }

    /** 给按钮加左侧矢量图标（TG 风格：图标+文字） */
    fun decorate(b: Button, c: Context, resId: Int, tint: Int = Color.WHITE) {
        try {
            val d = c.resources.getDrawable(resId, null)
            d.setTint(tint)
            val sz = dp(c, 18f)
            d.setBounds(0, 0, sz, sz)
            b.setCompoundDrawables(d, null, null, null)
            b.compoundDrawablePadding = dp(c, 8f)
        } catch (_: Exception) {
        }
    }

    fun space(c: Context, heightDp: Int): View {
        val v = View(c)
        v.layoutParams = LinearLayout.LayoutParams(1, dp(c, heightDp.toFloat()))
        return v
    }

    /** 透明间隔 Drawable：给 LinearLayout 当 divider 用（宽度/高度都能给） */
    class GapDrawable(private val h: Int, private val w: Int) : Drawable() {
        override fun draw(canvas: android.graphics.Canvas) {}
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSPARENT
        override fun getIntrinsicHeight(): Int = h
        override fun getIntrinsicWidth(): Int = w
    }

    /**
     * 给容器里"相邻子项之间"自动加透明间隔 —— 解决"元素粘连在一起"。
     * 垂直容器按高度留白，水平容器按宽度留白（LinearLayout divider 机制，不插空 View）。
     */
    fun autoGap(l: LinearLayout, c: Context, gapDp: Int) {
        val g = dp(c, gapDp.toFloat())
        // 只设 dividerDrawable 就够：LinearLayout 会取它的 intrinsic 宽/高当间隔
        l.dividerDrawable = GapDrawable(g, g)
        l.showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
    }

    /** 液态玻璃：半透明白 + 细边框 + 圆角（叠在图片上就是毛玻璃观感） */
    fun glass(c: Context, radiusDp: Int, alpha: Int = 0x1F, border: Int = 0x33FFFFFF): GradientDrawable {
        val g = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf((alpha shl 24) or 0xFFFFFF, ((alpha * 2 / 3) shl 24) or 0xFFFFFF)
        )
        g.cornerRadius = dp(c, radiusDp.toFloat()).toFloat()
        g.setStroke(dp(c, 1f), border)
        return g
    }

    /** 系统级「背景模糊」（Android 12+，OvEM 可能不支持 —— 静默失败，不影响观感） */
    fun blurBehind(lp: android.view.WindowManager.LayoutParams, radiusDp: Int, c: Context) {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        try {
            lp.flags = lp.flags or 0x00000004 /* FLAG_BLUR_BEHIND */
            lp.blurBehindRadius = dp(c, radiusDp.toFloat())
        } catch (_: Throwable) {
        }
    }
}
