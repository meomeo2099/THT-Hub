package vn.tht.hub

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.util.Locale
import vn.tht.hub.overlay.Ui
import vn.tht.hub.vehicle.EcarxFunctions as F
import vn.tht.hub.vehicle.PlatformSignature

/** Cài đặt THT Hub (dựng bằng code, không layout XML). */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var col: LinearLayout
    private lateinit var diag: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        HubService.start(this)
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 32, 48, 48) }
        window.decorView.setBackgroundColor(0xFF10151C.toInt())
        setContentView(ScrollView(this).apply { addView(col) })
        build()
    }

    override fun onResume() { super.onResume(); updateDiag() }

    private fun build() {
        col.addView(Ui.text(this, "THT Hub", 30f).apply { gravity = Gravity.START })
        col.addView(Ui.text(this, "Chạy nền cạnh màn hình chính gốc. Thanh trạng thái, chia màn và giọng nói nằm ở THT-StatusBar, THT Split, THT Voice.", 16f, Ui.DIM).apply {
            gravity = Gravity.START; maxLines = 3
        })

        section("Lớp nổi")
        switch("Bảng điều khiển nhanh (kéo từ mép dưới)", prefs.quickPanel) { prefs.quickPanel = it; apply() }
        switch("Thanh nhạc nổi", prefs.musicBar) { prefs.musicBar = it; apply() }
        switch("Ẩn thanh nhạc khi không phát", prefs.musicBarAutoHide) { prefs.musicBarAutoHide = it }
        button("Mở bảng điều khiển nhanh") { HubService.start(this, HubService.ACTION_TOGGLE_PANEL) }

        section("Nhạc và cuộc gọi")
        switch("Tạm dừng nhạc khi có cuộc gọi, phát lại sau", prefs.callPauseMusic) { prefs.callPauseMusic = it }

        section("Khi nổ máy")
        switch("Bật kịch bản khi nổ máy", prefs.autoEnabled) { prefs.autoEnabled = it }
        switch("Điều hoà AUTO", prefs.autoClimateAuto) { prefs.autoClimateAuto = it }
        choice("Nhiệt độ", listOf(0f to "Không đổi") + (36..52).map { (it / 2f) to String.format(Locale.US, "%.1f °C", it / 2f) },
            prefs.autoTemp) { prefs.autoTemp = it }
        choice("Chế độ lái", listOf(0 to "Không đổi") + F.DRIVE_MODES, prefs.autoDriveMode) { prefs.autoDriveMode = it }
        switch("Phát nhạc", prefs.autoPlayMusic) { prefs.autoPlayMusic = it }
        appChoice()
        choice("Chờ sau khi nổ máy", listOf(0, 3, 5, 10, 20).map { it to "$it giây" }, prefs.autoDelaySec) { prefs.autoDelaySec = it }
        choice("Tự đóng kính khi chạy trên", listOf(0 to "Tắt", 60 to "60 km/h", 80 to "80 km/h", 100 to "100 km/h"),
            prefs.autoCloseWindowsKmh) { prefs.autoCloseWindowsKmh = it }

        section("Chẩn đoán")
        diag = Ui.text(this, "", 15f, Ui.DIM).apply { gravity = Gravity.START; maxLines = 20 }
        col.addView(diag)
        button("Cấp quyền lớp nổi thủ công") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
        }
        button("Cấp quyền đọc thông báo (nhạc) thủ công") {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }
    }

    private fun updateDiag() {
        val hub = HubService.instance
        val v = hub?.vehicle
        diag.text = buildString {
            appendLine("Chữ ký platform: " + if (PlatformSignature.verify(this@SettingsActivity)) "KHỚP" else "không khớp")
            appendLine("Quyền lớp nổi: " + if (Permissions.canOverlay(this@SettingsActivity)) "có" else "CHƯA")
            appendLine("Đọc thông báo (nhạc): " + if (Permissions.notificationListenerEnabled(this@SettingsActivity)) "có" else "CHƯA")
            appendLine("Dịch vụ nền: " + if (hub != null) "đang chạy" else "chưa chạy")
            appendLine("ECARX: " + (hub?.ecarx?.status ?: "-"))
            if (v != null) {
                appendLine(String.format(Locale.US, "Tốc độ %s · Số %s · Xăng %s · Còn %s · Ắc quy %s · Máy %s",
                    v.speedKmh?.let { "%.0f km/h".format(Locale.US, it) } ?: "-", v.gearText,
                    v.fuelPercent?.let { "%.0f%%".format(Locale.US, it) } ?: "-",
                    v.rangeKm?.let { "%.0f km".format(Locale.US, it) } ?: "-",
                    v.batteryVoltage?.let { "%.1f V".format(Locale.US, it) } ?: "-",
                    when (v.engineOn) { true -> "nổ"; false -> "tắt"; null -> "-" }))
            }
            hub?.info?.let { i -> append("Điều hoà: ").append(i.lines().joinToString(" · ")) }
        }
        diag.postDelayed({ if (!isFinishing) updateDiag() }, 2000)
    }

    private fun apply() = HubService.start(this, HubService.ACTION_APPLY)

    // ---- Dựng giao diện ----

    private fun section(t: String) = col.addView(Ui.text(this, t, 22f, Ui.ON).apply { gravity = Gravity.START },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 36; bottomMargin = 8 })

    private fun switch(label: String, value: Boolean, set: (Boolean) -> Unit) = col.addView(Switch(this).apply {
        text = label; textSize = 19f; setTextColor(Ui.TEXT); isChecked = value; setPadding(0, 14, 0, 14)
        setOnCheckedChangeListener { _, b -> set(b) }
    })

    private fun button(label: String, action: () -> Unit) = col.addView(Button(this).apply {
        text = label; textSize = 17f; isAllCaps = false; setOnClickListener { action() }
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 8 })

    private fun <T> choice(label: String, options: List<Pair<T, String>>, current: T, set: (T) -> Unit) {
        val b = Button(this).apply { textSize = 17f; isAllCaps = false }
        fun show(v: T) { b.text = "$label: " + (options.firstOrNull { it.first == v }?.second ?: "$v") }
        show(current)
        b.setOnClickListener {
            AlertDialog.Builder(this).setTitle(label)
                .setItems(options.map { it.second }.toTypedArray()) { _, i -> set(options[i].first); show(options[i].first) }
                .show()
        }
        col.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 8 })
    }

    private fun appChoice() {
        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }.sortedBy { it.second.lowercase() }
        choice("Mở app", listOf("" to "Không mở") + apps, prefs.autoLaunchPkg) { prefs.autoLaunchPkg = it }
    }
}
