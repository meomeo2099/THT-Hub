package vn.tht.hub

import android.content.Context
import android.content.SharedPreferences

/** Cài đặt của THT Hub. */
class Prefs(context: Context) {
    val sp: SharedPreferences = context.getSharedPreferences("tht_hub", Context.MODE_PRIVATE)

    private fun b(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun put(k: String, v: Any) = sp.edit().apply {
        when (v) {
            is Boolean -> putBoolean(k, v); is Int -> putInt(k, v)
            is Float -> putFloat(k, v); is String -> putString(k, v)
        }
    }.apply()

    /** Giao diện: 0 theo xe (ngày/đêm hệ thống), 1 sáng, 2 tối. */
    var theme: Int get() = sp.getInt("theme", 0); set(v) = put("theme", v)

    // ---- Lớp nổi ----
    var quickPanel: Boolean get() = b("quick_panel", true); set(v) = put("quick_panel", v)
    var musicBar: Boolean get() = b("music_bar", true); set(v) = put("music_bar", v)
    /** Ẩn thanh nhạc khi không có gì phát. */
    var musicBarAutoHide: Boolean get() = b("music_autohide", true); set(v) = put("music_autohide", v)
    var musicX: Int get() = sp.getInt("music_x", 1310); set(v) = put("music_x", v)
    var musicY: Int get() = sp.getInt("music_y", 590); set(v) = put("music_y", v)
    /** Vị trí ngang (px) của tay kéo bảng nhanh ở mép dưới. */
    var handleX: Int get() = sp.getInt("handle_x", 860); set(v) = put("handle_x", v)

    // ---- Nhạc / cuộc gọi ----
    var callPauseMusic: Boolean get() = b("call_pause_music", true); set(v) = put("call_pause_music", v)

    // ---- Tự động hoá khi nổ máy ----
    var autoEnabled: Boolean get() = b("auto_enabled", false); set(v) = put("auto_enabled", v)
    var autoClimateAuto: Boolean get() = b("auto_climate_auto", false); set(v) = put("auto_climate_auto", v)
    /** 0 = không đổi; còn lại là nhiệt độ °C (16..32). */
    var autoTemp: Float get() = sp.getFloat("auto_temp", 0f); set(v) = put("auto_temp", v)
    /** 0 = không đổi; 1 Eco, 2 Comfort, 3 Sport. */
    var autoDriveMode: Int get() = sp.getInt("auto_drive_mode", 0); set(v) = put("auto_drive_mode", v)
    var autoPlayMusic: Boolean get() = b("auto_play_music", false); set(v) = put("auto_play_music", v)
    /** Package app mở khi nổ máy (vd. bản đồ); rỗng = không mở. */
    var autoLaunchPkg: String get() = sp.getString("auto_launch_pkg", "") ?: ""; set(v) = put("auto_launch_pkg", v)
    /** Chờ (giây) sau khi nổ máy rồi mới chạy, để ECU điều hoà sẵn sàng. */
    var autoDelaySec: Int get() = sp.getInt("auto_delay", 5); set(v) = put("auto_delay", v)
    /** Tự đóng hết kính khi xe chạy quá tốc độ này (0 = tắt). */
    var autoCloseWindowsKmh: Int get() = sp.getInt("auto_close_win", 0); set(v) = put("auto_close_win", v)

    /** Dung tích bình (lít) người dùng nhập; 0 = lấy từ xe. Dùng trong CarPropertySource. */
    var tankLiters: Int get() = sp.getInt("tank_l", 0); set(v) = put("tank_l", v)
}
