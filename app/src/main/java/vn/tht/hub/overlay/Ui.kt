package vn.tht.hub.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import vn.tht.hub.Prefs

/**
 * Thiết kế THT Hub (design/mockup.html): phong cách Flyme / EX2ToolsIV — nền xám xanh sáng, thẻ trắng bo góc,
 * xanh Flyme làm màu nhấn; có bản tối. Màn 1920×720 @160 dpi nên 1 dp = 1 px.
 */
class Palette(
    val dark: Boolean,
    val bg: Int, val card: Int, val card2: Int, val line: Int,
    val text: Int, val sub: Int, val blue: Int, val blueSoft: Int,
    val navSel: Int, val sheet: Int, val green: Int = 0xFF22A55B.toInt(), val red: Int = 0xFFE5484D.toInt(),
) {
    companion object {
        val LIGHT = Palette(
            false, bg = 0xFFDCE3EC.toInt(), card = 0xFFF7F9FC.toInt(), card2 = 0xFFFFFFFF.toInt(), line = 0xFFD3DBE6.toInt(),
            text = 0xFF18202B.toInt(), sub = 0xFF6B7686.toInt(), blue = 0xFF1F6BF2.toInt(), blueSoft = 0xFFDCE7FB.toInt(),
            navSel = 0xFFE6ECF5.toInt(), sheet = 0xF2F0F4F9.toInt(),
        )
        val DARK = Palette(
            true, bg = 0xFF141A22.toInt(), card = 0xFF212A36.toInt(), card2 = 0xFF273141.toInt(), line = 0xFF2E3949.toInt(),
            text = 0xFFEEF2F8.toInt(), sub = 0xFF93A0B2.toInt(), blue = 0xFF4C8DFF.toInt(), blueSoft = 0xFF24375A.toInt(),
            navSel = 0xFF1E2938.toInt(), sheet = 0xF21C2430.toInt(),
        )

        /** Prefs.theme: 0 theo xe (chế độ ngày/đêm của hệ thống), 1 sáng, 2 tối. */
        fun of(c: Context): Palette = when (Prefs(c).theme) {
            1 -> LIGHT
            2 -> DARK
            else -> if ((c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES) DARK else LIGHT
        }
    }
}

object Ui {
    val BOLD: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun round(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }

    fun topRound(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
    }

    fun overlayParams(w: Int, h: Int, gravity: Int, focusable: Boolean = false) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        PixelFormat.TRANSLUCENT,
    ).also { it.gravity = gravity }

    fun text(c: Context, s: String = "", size: Float = 20f, color: Int, bold: Boolean = false) = TextView(c).apply {
        text = s; textSize = size; setTextColor(color); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        if (bold) typeface = BOLD
    }

    fun icon(c: Context, res: Int, color: Int, size: Int) = ImageView(c).apply {
        setImageResource(res); setColorFilter(color)
        layoutParams = LinearLayout.LayoutParams(size, size)
    }

    fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
    const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT

    fun gap(c: Context, w: Int, h: Int = 1) = View(c).apply { layoutParams = LinearLayout.LayoutParams(w, h) }

    /**
     * Ô vuông 104×96: icon + nhãn. Bật = nền xanh, chữ trắng; null = xe không báo trạng thái (mờ).
     */
    class Tile(c: Context, private val p: Palette, icon: Int, label: String, onClick: () -> Unit) : LinearLayout(c) {
        private val img = ImageView(c).apply { setImageResource(icon) }
        private val lbl = text(c, label, 16f, p.sub).apply { gravity = Gravity.CENTER }
        init {
            orientation = VERTICAL; gravity = Gravity.CENTER
            addView(img, LayoutParams(32, 32))
            addView(lbl, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 8 })
            layoutParams = LayoutParams(104, 96)
            isClickable = true
            setOnClickListener { onClick() }
            setOn(false)
        }
        fun setOn(on: Boolean?) {
            background = round(if (on == true) p.blue else p.card, 16f)
            img.setColorFilter(if (on == true) 0xFFFFFFFF.toInt() else p.text)
            lbl.setTextColor(if (on == true) 0xFFFFFFFF.toInt() else p.sub)
            alpha = if (on == null) 0.6f else 1f
        }
        fun setLabel(s: String) { lbl.text = s }
    }

    /** Nút − / + cao 96. */
    fun step(c: Context, p: Palette, sign: String, onClick: () -> Unit) = TextView(c).apply {
        text = sign; textSize = 32f; setTextColor(p.text); gravity = Gravity.CENTER
        background = round(p.card, 16f)
        layoutParams = LinearLayout.LayoutParams(58, 96)
        setOnClickListener { onClick() }
    }

    /** Khối trắng có tiêu đề nhỏ (Điều hoà, Chế độ lái…). */
    fun block(c: Context, p: Palette, title: String, content: View) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = round(p.card2, 20f)
        setPadding(18, 14, 18, 16)
        addView(text(c, title, 16f, p.sub), LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = 10 })
        addView(content, LinearLayout.LayoutParams(WRAP, WRAP))
    }

    fun row(c: Context, vararg views: View, spacing: Int = 10) = LinearLayout(c).apply {
        gravity = Gravity.CENTER_VERTICAL
        views.forEachIndexed { i, v ->
            val l = (v.layoutParams as? LinearLayout.LayoutParams) ?: LinearLayout.LayoutParams(WRAP, WRAP)
            if (i > 0) l.leftMargin = spacing
            addView(v, l)
        }
    }

    /** Công tắc vuông bo nhẹ kiểu Flyme (46×28). */
    class Toggle(c: Context, private val p: Palette, checked: Boolean, private val onChange: (Boolean) -> Unit) : FrameLayout(c) {
        var checked = checked; private set
        private val knob = View(c).apply { background = round(0xFFFFFFFF.toInt(), 5f) }
        init {
            addView(knob, LayoutParams(20, 20))
            layoutParams = LinearLayout.LayoutParams(46, 28)
            render()
            setOnClickListener { this.checked = !this.checked; render(); onChange(this.checked) }
        }
        private fun render() {
            background = round(if (checked) p.blue else if (p.dark) 0xFF3A4657.toInt() else 0xFFC9D1DC.toInt(), 7f)
            (knob.layoutParams as LayoutParams).apply { leftMargin = if (checked) 22 else 4; topMargin = 4 }
            knob.requestLayout()
        }
    }

    /** Nhóm nút chọn (Eco | Comfort | Sport): mục chọn nền xanh. */
    class Segmented<T>(
        c: Context, private val p: Palette, private val options: List<Pair<T, String>>, current: T,
        private val width: Int = 150, private val onPick: (T) -> Unit,
    ) : LinearLayout(c) {
        private var selected: T = current
        private val views: List<TextView> = options.map { (v, label) ->
            text(c, label, 19f, p.text, bold = true).apply {
                gravity = Gravity.CENTER
                setOnClickListener { selected = v; render(); onPick(v) }
            }
        }
        init {
            views.forEachIndexed { i, v -> addView(v, LayoutParams(width, 62).apply { if (i > 0) leftMargin = 8 }) }
            render()
        }
        private fun render(): Unit = options.forEachIndexed { i, (v, _) ->
            val on = v == selected
            views[i].background = round(if (on) p.blue else p.card2, 10f)
            views[i].setTextColor(if (on) 0xFFFFFFFF.toInt() else p.text)
        }
    }
}
