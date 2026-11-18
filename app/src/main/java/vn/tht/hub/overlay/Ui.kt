package vn.tht.hub.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Màu và hàm dựng view dùng chung cho lớp nổi (màn 1920×720 @160 dpi: 1 dp = 1 px). */
object Ui {
    const val BG = 0xE6141A22.toInt()
    const val CARD = 0xFF222B36.toInt()
    const val ON = 0xFF19B3A6.toInt()
    const val TEXT = Color.WHITE
    const val DIM = 0xFF9AA6B2.toInt()

    fun round(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }

    fun overlayParams(w: Int, h: Int, gravity: Int, focusable: Boolean = false) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        PixelFormat.TRANSLUCENT,
    ).also { it.gravity = gravity }

    fun text(c: Context, s: String = "", size: Float = 20f, color: Int = TEXT) = TextView(c).apply {
        text = s; textSize = size; setTextColor(color); gravity = Gravity.CENTER; maxLines = 1
    }

    /** Nút vuông có icon + nhãn; [setOn] đổi màu theo trạng thái. */
    class Button(c: Context, icon: Int, label: String, val onClick: () -> Unit) : LinearLayout(c) {
        private val img = ImageView(c).apply { setImageResource(icon); setColorFilter(TEXT) }
        private val lbl = text(c, label, 15f, DIM)
        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            background = round(CARD, 18f)
            setPadding(6, 10, 6, 8)
            addView(img, LayoutParams(44, 44))
            addView(lbl, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 6 })
            setOnClickListener { onClick() }
        }
        fun setOn(on: Boolean?) {
            background = round(if (on == true) ON else CARD, 18f)
            lbl.setTextColor(if (on == true) TEXT else DIM)
            alpha = if (on == null) 0.55f else 1f
        }
        fun setLabel(s: String) { lbl.text = s }
    }

    fun gap(c: Context, w: Int) = View(c).apply { layoutParams = LinearLayout.LayoutParams(w, 1) }
}
