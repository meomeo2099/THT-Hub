package vn.tht.hub.overlay

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import vn.tht.hub.HubService
import vn.tht.hub.Prefs
import vn.tht.hub.system.ServiceMode

/**
 * Vùng mở khoá chế độ bảo dưỡng: ô trong suốt 90×90 ở góc trên phải (dưới vùng trạng thái 50 px của xe),
 * bấm 5 lần trong 4 giây → bàn phím PIN. Đúng PIN thì hiện lại các app đã ẩn.
 */
class UnlockCorner(private val hub: HubService, private val test: Boolean = false) {
    private val wm = hub.getSystemService(WindowManager::class.java)
    private val prefs = Prefs(hub)
    private val main = Handler(Looper.getMainLooper())
    private var corner: View? = null
    private var pad: View? = null
    private val taps = ArrayDeque<Long>()

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (corner != null) return
        val p = Palette.of(hub)
        // Khung viền mờ để thấy chỗ bấm; chế độ thử thì rõ hơn và có chữ hướng dẫn
        val v = TextView(hub).apply {
            gravity = Gravity.CENTER
            textSize = 13f
            // Thật: vô hình hoàn toàn trừ khi bật "Hiện dấu •••"; thử: khung xanh có chữ
            val mark = test || prefs.serviceModeShowMark
            setTextColor(if (test) 0xFFFFFFFF.toInt() else 0x66FFFFFF)
            text = if (test) "Bấm 5 lần" else if (mark) "•••" else ""
            if (mark) background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 18f
                setColor(if (test) 0x661F6BF2 else 0x14FFFFFF)
                setStroke(if (test) 4 else 2, if (test) p.blue else 0x55FFFFFF)
            }
        }
        v.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                val now = SystemClock.uptimeMillis()
                taps.addLast(now)
                while (taps.isNotEmpty() && now - taps.first() > 4000) taps.removeFirst()
                if (taps.size >= 5) { taps.clear(); openPad() }
            }
            true
        }
        // y = 0: hệ thống đặt lớp nổi ngay dưới vùng trạng thái 50 px → khung nằm sát dưới đồng hồ, sát mép phải
        // Vị trí chọn trong Cài đặt; bên trái chừa thanh OEM 160 px, dưới chừa tay kéo bảng nhanh
        val lp = when (prefs.serviceModeCorner) {
            1 -> Ui.overlayParams(100, 100, Gravity.TOP or Gravity.START).apply { x = 170; y = 0 }
            2 -> Ui.overlayParams(100, 100, Gravity.BOTTOM or Gravity.END).apply { x = 6; y = 50 }
            3 -> Ui.overlayParams(100, 100, Gravity.BOTTOM or Gravity.START).apply { x = 170; y = 50 }
            4 -> Ui.overlayParams(100, 100, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { y = 0 }
            else -> Ui.overlayParams(100, 100, Gravity.TOP or Gravity.END).apply { x = 6; y = 0 }
        }
        runCatching { wm.addView(v, lp) }.onSuccess { corner = v }
        if (test) main.postDelayed({ hide() }, 30_000)
    }

    fun hide() {
        closePad()
        corner?.let { runCatching { wm.removeView(it) } }
        corner = null
    }

    private fun openPad() {
        if (pad != null) return
        val p = Palette.of(hub)
        var entered = ""
        val display = Ui.text(hub, "Nhập mã PIN", 26f, p.text, bold = true).apply { gravity = Gravity.CENTER }
        val msg = Ui.text(hub, "", 16f, p.sub).apply { gravity = Gravity.CENTER }
        fun render() { display.text = if (entered.isEmpty()) "Nhập mã PIN" else "•".repeat(entered.length) }
        val grid = GridLayout(hub).apply { columnCount = 3 }
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "Xoá", "0", "OK")
        for (k in keys) {
            grid.addView(TextView(hub).apply {
                text = k; textSize = 26f; gravity = Gravity.CENTER
                setTextColor(if (k == "OK") 0xFFFFFFFF.toInt() else p.text)
                background = Ui.round(if (k == "OK") p.blue else p.card2, 16f)
                setOnClickListener {
                    when (k) {
                        "Xoá" -> entered = entered.dropLast(1)
                        "OK" -> if (entered == prefs.serviceModePin) {
                            closePad()
                            if (test) android.widget.Toast.makeText(hub, "Đúng mã PIN – vùng mở khoá hoạt động", android.widget.Toast.LENGTH_LONG).show()
                            else ServiceMode.exit(hub)
                            return@setOnClickListener
                        } else { entered = ""; msg.text = "Sai mã"; msg.setTextColor(p.red) }
                        else -> if (entered.length < 8) entered += k
                    }
                    render()
                }
            }, GridLayout.LayoutParams().apply { width = 120; height = 84; setMargins(8, 8, 8, 8) })
        }
        val box = LinearLayout(hub).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = Ui.round(p.sheet, 28f); setPadding(32, 28, 32, 24); elevation = 24f
            isClickable = true
            addView(display, Ui.lp(Ui.MATCH, Ui.WRAP))
            addView(msg, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = 6; bottomMargin = 10 })
            addView(grid)
            addView(Ui.text(hub, "Đóng", 18f, p.sub).apply {
                gravity = Gravity.CENTER; setPadding(0, 16, 0, 4); setOnClickListener { closePad() }
            }, Ui.lp(Ui.MATCH, Ui.WRAP))
        }
        val root = FrameLayout(hub).apply {
            setBackgroundColor(0x660A0F18)
            setOnClickListener { closePad() }
            addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        val lp = Ui.overlayParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER)
        runCatching { wm.addView(root, lp) }.onSuccess { pad = root }
        main.postDelayed({ closePad() }, 60_000)
    }

    private fun closePad() {
        pad?.let { runCatching { wm.removeView(it) } }
        pad = null
    }
}
