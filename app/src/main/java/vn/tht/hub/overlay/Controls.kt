package vn.tht.hub.overlay

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import vn.tht.hub.HubService
import vn.tht.hub.R
import vn.tht.hub.vehicle.EcarxController
import vn.tht.hub.vehicle.EcarxFunctions as F

/**
 * Bộ điều khiển Coolray 2025 dùng chung cho bảng kéo từ mép dưới ([QuickPanel]) và trang
 * "Điều khiển nhanh" trong app: điều hoà, chế độ lái, kính/cửa sổ trời, Wi-Fi/Bluetooth.
 * Không có sưởi ghế / sưởi vô lăng (xe không trang bị).
 */
class Controls(
    private val c: Context,
    private val hub: HubService,
    private val p: Palette,
    /** Báo kết quả lệnh (dòng trạng thái); ok=false tô đỏ. */
    private val onStatus: (String, Boolean) -> Unit,
    /** Gọi mỗi lần người dùng chạm (để hoãn tự đóng bảng). */
    private val onTouch: () -> Unit = {},
) {
    private val main = Handler(Looper.getMainLooper())
    private val ctl get() = hub.control
    private val tiles = mutableMapOf<String, Ui.Tile>()
    private val modes = mutableListOf<Pair<Int, Ui.Tile>>()
    private val tempText = Ui.text(c, "--", 44f, p.text, bold = true).apply { gravity = Gravity.CENTER }
    private val fanText = Ui.text(c, "--", 44f, p.text, bold = true).apply { gravity = Gravity.CENTER }
    private val fanBars = LinearLayout(c).apply { gravity = Gravity.CENTER }

    /** Hai hàng: điều hoà; chế độ lái + kính + kết nối. */
    fun build(): View = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        addView(scroll(Ui.block(c, p, "Điều hoà", climate())), Ui.lp(Ui.MATCH, Ui.WRAP))
        val second = Ui.row(
            c,
            Ui.block(c, p, "Chế độ lái", driveModes()),
            Ui.block(c, p, "Kính và cửa sổ trời", body()),
            Ui.block(c, p, "Kết nối", connect()),
            spacing = 14,
        )
        addView(scroll(second), Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = 14 })
    }

    private fun scroll(v: View) = HorizontalScrollView(c).apply {
        isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
        addView(v)
    }

    private fun climate(): View {
        for (i in 1..7) fanBars.addView(View(c), Ui.lp(8, 14).apply { if (i > 1) leftMargin = 4 })
        val temp = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            addView(tempText); addView(Ui.text(c, "Nhiệt độ", 15f, p.sub).apply { gravity = Gravity.CENTER })
            layoutParams = Ui.lp(124, 96)
        }
        val fan = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            addView(fanText); addView(fanBars, Ui.lp(Ui.WRAP, Ui.WRAP).apply { topMargin = 6 })
            layoutParams = Ui.lp(124, 96)
        }
        return Ui.row(
            c,
            tile("power", R.drawable.ic_power, "Bật") { ctl.toggleHvacPower(::done) },
            tile("auto", R.drawable.ic_auto, "AUTO") { ctl.toggleAuto(::done) },
            tile("ac", R.drawable.ic_ac, "A/C") { ctl.toggleAc(::done) },
            Ui.gap(c, 4),
            Ui.step(c, p, "−") { act { ctl.tempStep(-1, ::done) } }, temp, Ui.step(c, p, "+") { act { ctl.tempStep(+1, ::done) } },
            Ui.gap(c, 4),
            Ui.step(c, p, "−") { act { ctl.fanStep(-1, ::done) } }, fan, Ui.step(c, p, "+") { act { ctl.fanStep(+1, ::done) } },
            Ui.gap(c, 4),
            tile("recirc", R.drawable.ic_recirc, "Gió trong") {
                val next = if (hub.info?.recirc == 1) 2 else 1
                ctl.setValue(F.HVAC_CIRCULATION, F.ZONE_ALL, F.level(F.HVAC_CIRCULATION, next),
                    if (next == 1) "Lấy gió trong" else "Lấy gió ngoài", done = ::done)
            },
            tile("blow", R.drawable.ic_air_upper, "Hướng gió") {
                ctl.cycleLevels(F.HVAC_BLOWING_MODE, F.ZONE_ALL, intArrayOf(1, 2, 3, 4, 5, 6, 7), "Hướng gió",
                    { n -> F.BLOWING_MODES.firstOrNull { it.first == n }?.second ?: "$n" }, ::done)
            },
            tile("defF", R.drawable.ic_front_defrost, "Kính trước") { ctl.toggleFn(F.HVAC_DEFROST_FRONT, F.ZONE_ALL, "Sấy kính trước", done = ::done) },
            tile("defR", R.drawable.ic_rear_defrost, "Kính sau") { ctl.toggleFn(F.HVAC_DEFROST_REAR, F.ZONE_ALL, "Sấy kính sau", done = ::done) },
        )
    }

    private fun driveModes(): View {
        val icons = mapOf(1 to R.drawable.ic_eco, 2 to R.drawable.ic_comfort, 3 to R.drawable.ic_sport)
        val views = F.DRIVE_MODES.map { (n, name) ->
            Ui.Tile(c, p, icons[n] ?: R.drawable.ic_car, name) { act { ctl.setDriveMode(n, ::done) } }.also { modes += n to it }
        }
        return Ui.row(c, *views.toTypedArray())
    }

    private fun body() = Ui.row(
        c,
        tile("winC", R.drawable.ic_windows, "Đóng kính") { ctl.setWindows(false, ::done) },
        tile("winO", R.drawable.ic_window_vent, "Hé kính") { ctl.setWindows(true, ::done) },
        tile("roofT", R.drawable.ic_roof_tilt, "Hé mui") {
            ctl.toggleFn(F.BCM_SUNROOF_TILT, F.ZONE_ALL, "Hé cửa sổ trời", EcarxController.Safety.BELOW_80, ::done)
        },
        tile("roofC", R.drawable.ic_sunroof_open, "Đóng mui") { ctl.setRoof(0, ::done) },
    )

    private fun connect() = Ui.row(
        c,
        Ui.Tile(c, p, R.drawable.ic_wifi, "Wi-Fi") { toggleWifi() }.also { tiles["wifi"] = it },
        Ui.Tile(c, p, R.drawable.ic_bluetooth, "Bluetooth") { toggleBt() }.also { tiles["bt"] = it },
    )

    private fun tile(key: String, icon: Int, label: String, action: () -> Unit) =
        Ui.Tile(c, p, icon, label) { act(action) }.also { tiles[key] = it }

    private fun act(action: () -> Unit) { onTouch(); onStatus("Đang gửi…", true); action() }

    private fun done(r: EcarxController.Result) { onStatus(r.message, r.ok); hub.refreshInfo() }

    /** Vẽ theo trạng thái xe; null = xe chưa báo / không hỗ trợ. */
    fun render() {
        val i = hub.info
        tiles["power"]?.setOn(i?.hvacOn)
        tiles["auto"]?.setOn(i?.autoOn)
        tiles["ac"]?.setOn(i?.acOn)
        tiles["recirc"]?.apply { setOn(i?.recirc?.let { it == 1 }); setLabel(if (i?.recirc == 2) "Gió ngoài" else "Gió trong") }
        tiles["blow"]?.apply {
            setOn(false)
            setLabel(F.BLOWING_MODES.firstOrNull { it.first == i?.blowing }?.second ?: "Hướng gió")
        }
        tiles["defF"]?.setOn(i?.defrostFront)
        tiles["defR"]?.setOn(i?.defrostRear)
        for (k in listOf("winC", "winO", "roofT", "roofC")) tiles[k]?.setOn(false)
        tempText.text = i?.tempC?.let { String.format(Locale.US, "%.1f°", it) } ?: "--"
        val fan = i?.fanLevel?.takeIf { it > 0 }
        fanText.text = fan?.toString() ?: if (i?.autoOn == true) "A" else "--"
        for (n in 0 until fanBars.childCount) {
            fanBars.getChildAt(n).background = Ui.round(if (fan != null && n < fan) p.blue else p.line, 2f)
        }
        for ((n, t) in modes) t.setOn(i?.driveMode?.let { it == n })
        tiles["wifi"]?.setOn(wifi()?.isWifiEnabled)
        tiles["bt"]?.setOn(BluetoothAdapter.getDefaultAdapter()?.isEnabled)
    }

    private fun wifi() = c.applicationContext.getSystemService(WifiManager::class.java)

    @Suppress("DEPRECATION")
    private fun toggleWifi() {
        onTouch()
        val w = wifi() ?: return
        val target = !w.isWifiEnabled
        val ok = runCatching { w.setWifiEnabled(target) }.getOrDefault(false)
        onStatus(if (ok) "Wi-Fi: " + (if (target) "đang bật" else "đang tắt") else "Không đổi được Wi-Fi", ok)
        main.postDelayed({ render() }, 1500)
    }

    @SuppressLint("MissingPermission")
    private fun toggleBt() {
        onTouch()
        val a = BluetoothAdapter.getDefaultAdapter() ?: return
        val ok = runCatching { if (a.isEnabled) a.disable() else a.enable() }.getOrDefault(false)
        onStatus(if (ok) "Bluetooth: đang đổi" else "Không đổi được Bluetooth", ok)
        main.postDelayed({ render() }, 1500)
    }
}

/** Dòng trạng thái nhỏ dùng chung. */
fun statusLine(c: Context, p: Palette) = Ui.text(c, "", 17f, p.sub)

fun TextView.showStatus(p: Palette, msg: String, ok: Boolean) {
    text = msg; setTextColor(if (ok) p.sub else p.red)
}
