package vn.tht.hub.overlay

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import vn.tht.hub.HubService
import vn.tht.hub.Prefs
import vn.tht.hub.R
import vn.tht.hub.SettingsActivity
import vn.tht.hub.vehicle.EcarxController
import vn.tht.hub.vehicle.EcarxFunctions as F

/**
 * Bảng điều khiển nhanh kéo từ mép dưới (kiểu "шторка" của GUHub), chỉ gồm chức năng Coolray 2025 có thật:
 * điều hoà (nguồn, AUTO, A/C, nhiệt độ, quạt 1–7, lấy gió, hướng gió, sấy kính trước/sau),
 * chế độ lái, kính, cửa sổ trời, Wi-Fi, Bluetooth. Không có sưởi ghế / sưởi vô lăng.
 */
class QuickPanel(private val hub: HubService) {

    private val wm = hub.getSystemService(WindowManager::class.java)
    private val prefs = Prefs(hub)
    private val main = Handler(Looper.getMainLooper())
    private val ctl get() = hub.control

    private var handle: View? = null
    private var panel: View? = null
    val isOpen get() = panel != null

    private val refresh: () -> Unit = { render() }

    // ---- Tay kéo ở mép dưới ----

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (handle != null) return
        val bar = View(hub).apply { background = Ui.round(0xCCFFFFFF.toInt(), 6f) }
        val box = LinearLayout(hub).apply {
            gravity = Gravity.CENTER
            setPadding(0, 14, 0, 6)
            addView(bar, LinearLayout.LayoutParams(160, 8))
        }
        val lp = Ui.overlayParams(220, 30, Gravity.BOTTOM or Gravity.START).apply { x = prefs.handleX }
        var downY = 0f; var downX = 0f; var startX = 0; var dragging = false
        box.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; downX = e.rawX; startX = lp.x; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    if (downY - e.rawY > 25) { open(); return@setOnTouchListener true }
                    if (kotlin.math.abs(e.rawX - downX) > 30 && e.eventTime - e.downTime > 400) {
                        dragging = true
                        lp.x = (startX + (e.rawX - downX)).toInt().coerceIn(0, 1700)
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

    private lateinit var status: TextView
    private val buttons = mutableMapOf<String, Ui.Button>()
    private lateinit var tempText: TextView
    private lateinit var fanText: TextView
    private val modeButtons = mutableListOf<Pair<Int, Ui.Button>>()

    @SuppressLint("ClickableViewAccessibility")
    fun open() {
        if (panel != null) return
        buttons.clear(); modeButtons.clear()
        val root = LinearLayout(hub).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.round(Ui.BG, 28f)
            setPadding(24, 16, 24, 20)
        }
        status = Ui.text(hub, "", 17f, Ui.DIM).apply { gravity = Gravity.START }
        val header = LinearLayout(hub).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(Ui.text(hub, "Điều khiển nhanh", 22f), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        header.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 24 })
        header.addView(small(R.drawable.ic_settings) {
            close()
            hub.startActivity(Intent(hub, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        header.addView(small(R.drawable.ic_close) { close() })
        root.addView(header)

        root.addView(row(climateRow()), rowLp())
        root.addView(row(carRow()), rowLp())

        val lp = Ui.overlayParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            windowAnimations = android.R.style.Animation_InputMethod
        }
        // Chạm ra ngoài bảng thì đóng
        root.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_OUTSIDE) close(); false }
        runCatching { wm.addView(root, lp) }.onSuccess {
            panel = root
            hub.addListener(refresh)
            hub.refreshInfo()
            render()
        }
        main.removeCallbacks(autoClose); main.postDelayed(autoClose, AUTO_CLOSE_MS)
    }

    fun close() {
        main.removeCallbacks(autoClose)
        hub.removeListener(refresh)
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
    }

    private val autoClose = Runnable { close() }
    private fun touch() { main.removeCallbacks(autoClose); main.postDelayed(autoClose, AUTO_CLOSE_MS) }

    private fun climateRow(): List<View> {
        tempText = Ui.text(hub, "--", 30f)
        fanText = Ui.text(hub, "--", 30f)
        return listOf(
            btn("power", R.drawable.ic_power, "Điều hoà") { ctl.toggleHvacPower(::done) },
            btn("auto", R.drawable.ic_auto, "AUTO") { ctl.toggleAuto(::done) },
            btn("ac", R.drawable.ic_ac, "A/C") { ctl.toggleAc(::done) },
            Ui.gap(hub, 16),
            btn("t-", R.drawable.ic_minus, "Nhiệt") { ctl.tempStep(-1, ::done) },
            valueBox(tempText, "°C"),
            btn("t+", R.drawable.ic_plus, "Nhiệt") { ctl.tempStep(+1, ::done) },
            Ui.gap(hub, 16),
            btn("f-", R.drawable.ic_minus, "Quạt") { ctl.fanStep(-1, ::done) },
            valueBox(fanText, "Quạt"),
            btn("f+", R.drawable.ic_plus, "Quạt") { ctl.fanStep(+1, ::done) },
            Ui.gap(hub, 16),
            btn("recirc", R.drawable.ic_recirc, "Gió trong") {
                val cur = hub.info?.recirc
                val next = if (cur == 1) 2 else 1
                ctl.setValue(F.HVAC_CIRCULATION, F.ZONE_ALL, F.level(F.HVAC_CIRCULATION, next),
                    if (next == 1) "Lấy gió trong" else "Lấy gió ngoài", done = ::done)
            },
            btn("blow", R.drawable.ic_air_both, "Hướng gió") {
                ctl.cycleLevels(F.HVAC_BLOWING_MODE, F.ZONE_ALL, intArrayOf(1, 2, 3, 4, 5, 6, 7), "Hướng gió",
                    { n -> F.BLOWING_MODES.firstOrNull { it.first == n }?.second ?: "$n" }, ::done)
            },
            btn("defF", R.drawable.ic_front_defrost, "Kính trước") { ctl.toggleFn(F.HVAC_DEFROST_FRONT, F.ZONE_ALL, "Sấy kính trước", done = ::done) },
            btn("defR", R.drawable.ic_rear_defrost, "Kính sau") { ctl.toggleFn(F.HVAC_DEFROST_REAR, F.ZONE_ALL, "Sấy kính sau", done = ::done) },
        )
    }

    private fun carRow(): List<View> {
        val views = mutableListOf<View>()
        val modeIcons = mapOf(1 to R.drawable.ic_eco, 2 to R.drawable.ic_comfort, 3 to R.drawable.ic_sport)
        for ((n, name) in F.DRIVE_MODES) {
            val b = Ui.Button(hub, modeIcons[n] ?: R.drawable.ic_car, name) { touch(); ctl.setDriveMode(n, ::done) }
            modeButtons += n to b
            views += b
        }
        views += Ui.gap(hub, 16)
        views += btn("winC", R.drawable.ic_windows, "Đóng kính") { ctl.setWindows(false, ::done) }
        views += btn("winO", R.drawable.ic_window_vent, "Hé kính") { ctl.setWindows(true, ::done) }
        views += btn("roofT", R.drawable.ic_roof_tilt, "Hé mui") {
            ctl.toggleFn(F.BCM_SUNROOF_TILT, F.ZONE_ALL, "Hé cửa sổ trời", EcarxController.Safety.BELOW_80, ::done)
        }
        views += btn("roofC", R.drawable.ic_sunroof_open, "Đóng mui") { ctl.setRoof(0, ::done) }
        views += Ui.gap(hub, 16)
        views += btn("wifi", R.drawable.ic_wifi, "Wi-Fi") { toggleWifi() }
        views += btn("bt", R.drawable.ic_bluetooth, "Bluetooth") { toggleBt() }
        return views
    }

    private fun btn(key: String, icon: Int, label: String, action: () -> Unit) =
        Ui.Button(hub, icon, label) { touch(); status.text = "Đang gửi…"; action() }.also { buttons[key] = it }

    private fun small(icon: Int, action: () -> Unit) = android.widget.ImageView(hub).apply {
        setImageResource(icon); setColorFilter(Ui.TEXT); setPadding(14, 14, 14, 14)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(64, 64)
    }

    private fun valueBox(t: TextView, caption: String) = LinearLayout(hub).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        addView(t); addView(Ui.text(hub, caption, 14f, Ui.DIM))
        layoutParams = LinearLayout.LayoutParams(110, 104)
    }

    private fun row(views: List<View>): View {
        val line = LinearLayout(hub).apply { gravity = Gravity.CENTER_VERTICAL }
        for (v in views) {
            val lp = (v.layoutParams as? LinearLayout.LayoutParams)
                ?: LinearLayout.LayoutParams(112, 104)
            lp.rightMargin = 10
            line.addView(v, lp)
        }
        return HorizontalScrollView(hub).apply { isHorizontalScrollBarEnabled = false; addView(line) }
    }

    private fun rowLp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 14 }

    private fun done(r: EcarxController.Result) {
        if (panel == null) return
        status.text = r.message
        status.setTextColor(if (r.ok) Ui.DIM else 0xFFFF8A80.toInt())
        hub.refreshInfo()
    }

    /** Vẽ trạng thái hiện tại; null = xe chưa báo / không hỗ trợ (nút mờ). */
    private fun render() {
        if (panel == null) return
        val i = hub.info
        if (!hub.ecarx.connected) status.text = "Chưa nối được dịch vụ xe (${hub.ecarx.status})"
        buttons["power"]?.setOn(i?.hvacOn)
        buttons["auto"]?.setOn(i?.autoOn)
        buttons["ac"]?.setOn(i?.acOn)
        buttons["recirc"]?.apply { setOn(i?.recirc?.let { it == 1 }); setLabel(if (i?.recirc == 2) "Gió ngoài" else "Gió trong") }
        buttons["blow"]?.setLabel(F.BLOWING_MODES.firstOrNull { it.first == i?.blowing }?.second ?: "Hướng gió")
        buttons["defF"]?.setOn(i?.defrostFront)
        buttons["defR"]?.setOn(i?.defrostRear)
        tempText.text = i?.tempC?.let { String.format(Locale.US, "%.1f", it) } ?: "--"
        fanText.text = i?.fanLevel?.takeIf { it > 0 }?.toString() ?: if (i?.autoOn == true) "A" else "--"
        for ((n, b) in modeButtons) b.setOn(i?.driveMode?.let { it == n })
        buttons["wifi"]?.setOn(wifi()?.isWifiEnabled)
        buttons["bt"]?.setOn(BluetoothAdapter.getDefaultAdapter()?.isEnabled)
    }

    private fun wifi() = hub.applicationContext.getSystemService(WifiManager::class.java)

    @Suppress("DEPRECATION")
    private fun toggleWifi() {
        val w = wifi() ?: return
        val ok = runCatching { w.setWifiEnabled(!w.isWifiEnabled) }.getOrDefault(false)
        status.text = if (ok) "Wi-Fi: " + (if (w.isWifiEnabled) "đang tắt" else "đang bật") else "Không đổi được Wi-Fi"
        main.postDelayed({ render() }, 1500)
    }

    @SuppressLint("MissingPermission")
    private fun toggleBt() {
        val a = BluetoothAdapter.getDefaultAdapter() ?: return
        val ok = runCatching { if (a.isEnabled) a.disable() else a.enable() }.getOrDefault(false)
        status.text = if (ok) "Bluetooth: đang đổi" else "Không đổi được Bluetooth"
        main.postDelayed({ render() }, 1500)
    }

    companion object {
        private const val AUTO_CLOSE_MS = 15_000L
    }
}
