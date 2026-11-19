package vn.tht.hub.overlay

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import vn.tht.hub.HubService
import vn.tht.hub.MainActivity
import vn.tht.hub.Prefs
import vn.tht.hub.R

/**
 * Bảng điều khiển nhanh kéo từ mép dưới (thiết kế 2-dieu-khien-nhanh.png): nền kính mờ bo 30 px ở trên,
 * tay nắm, tiêu đề + dòng trạng thái, nút Cài đặt/Đóng, và [Controls]. Lớp mờ phía sau, chạm ra ngoài để đóng.
 */
class QuickPanel(private val hub: HubService) {

    private val wm = hub.getSystemService(WindowManager::class.java)
    private val prefs = Prefs(hub)
    private val main = Handler(Looper.getMainLooper())

    private var handle: View? = null
    private var panel: View? = null
    private var controls: Controls? = null
    val isOpen get() = panel != null

    private val refresh: () -> Unit = { controls?.render() }

    // ---- Tay kéo ở mép dưới ----

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (handle != null) return
        val bar = View(hub).apply { background = Ui.round(0xBF78879B.toInt(), 4f) }
        val box = LinearLayout(hub).apply {
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 6)
            addView(bar, LinearLayout.LayoutParams(160, 8))
        }
        val lp = Ui.overlayParams(220, 30, Gravity.BOTTOM or Gravity.START).apply { x = prefs.handleX }
        var downY = 0f; var downX = 0f; var startX = 0; var dragging = false
        box.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; downX = e.rawX; startX = lp.x; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && downY - e.rawY > 25) { open(); return@setOnTouchListener true }
                    if (dragging || (kotlin.math.abs(e.rawX - downX) > 30 && e.eventTime - e.downTime > 400)) {
                        dragging = true
                        lp.x = (startX + (e.rawX - downX)).toInt().coerceIn(160, 1700)
                        runCatching { wm.updateViewLayout(box, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> if (dragging) prefs.handleX = lp.x else if (kotlin.math.abs(e.rawY - downY) < 15) open()
            }
            true
        }
        runCatching { wm.addView(box, lp) }.onSuccess { handle = box }
    }

    fun hide() { close(); handle?.let { runCatching { wm.removeView(it) } }; handle = null }

    fun toggle() = if (isOpen) close() else open()

    // ---- Bảng ----

    @SuppressLint("ClickableViewAccessibility")
    fun open() {
        if (panel != null) return
        val p = Palette.of(hub)
        val status = statusLine(hub, p)
        val ctl = Controls(hub, hub, p, { m, ok -> status.showStatus(p, m, ok) }, ::touch)
        controls = ctl

        val sheet = LinearLayout(hub).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.topRound(p.sheet, 30f)
            elevation = 24f
            setPadding(34, 14, 34, 18)
            isClickable = true   // không để chạm trong bảng rơi xuống lớp mờ
        }
        sheet.addView(View(hub).apply { background = Ui.round(0xFFAEB8C6.toInt(), 4f) },
            LinearLayout.LayoutParams(120, 7).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = 12 })
        val header = LinearLayout(hub).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(Ui.text(hub, "Điều khiển nhanh", 24f, p.text, bold = true))
        header.addView(status, Ui.lp(0, Ui.WRAP, 1f).apply { leftMargin = 24 })
        header.addView(roundBtn(p, R.drawable.ic_settings) {
            close()
            hub.startActivity(Intent(hub, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        header.addView(roundBtn(p, R.drawable.ic_close) { close() }, Ui.lp(52, 52).apply { leftMargin = 10 })
        sheet.addView(header, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = 14 })
        sheet.addView(ctl.build())

        // Lớp mờ phủ toàn màn; bảng nằm dưới, chừa 160 px thanh hệ thống bên trái
        val root = FrameLayout(hub).apply {
            setBackgroundColor(0x470F1928)
            setOnClickListener { close() }
            addView(sheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM).apply { leftMargin = 160 })
        }
        val lp = Ui.overlayParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.BOTTOM).apply {
            windowAnimations = android.R.style.Animation_InputMethod
        }
        runCatching { wm.addView(root, lp) }.onSuccess {
            panel = root
            hub.addListener(refresh)
            hub.refreshInfo()
            ctl.render()
            if (!hub.ecarx.connected) status.showStatus(p, "Chưa nối được dịch vụ xe (${hub.ecarx.status})", false)
        }
        touch()
    }

    fun close() {
        main.removeCallbacks(autoClose)
        hub.removeListener(refresh)
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null; controls = null
    }

    private val autoClose = Runnable { close() }
    private fun touch() { main.removeCallbacks(autoClose); main.postDelayed(autoClose, AUTO_CLOSE_MS) }

    private fun roundBtn(p: Palette, icon: Int, action: () -> Unit) = Ui.icon(hub, icon, p.text, 52).apply {
        background = Ui.round(p.card2, 14f)
        setPadding(13, 13, 13, 13)
        setOnClickListener { action() }
    }

    companion object {
        private const val AUTO_CLOSE_MS = 15_000L
    }
}
