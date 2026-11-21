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

    // ---- Chế độ đi bảo dưỡng ----
    var serviceModePin: String get() = sp.getString("svc_pin", "") ?: ""; set(v) = put("svc_pin", v)
    /** Hiện dấu ••• ở vùng mở khoá khi đang bảo dưỡng (mặc định tắt: vô hình để người khác không thấy). */
    var serviceModeShowMark: Boolean get() = b("svc_mark", false); set(v) = put("svc_mark", v)
    var serviceModeHours: Int get() = sp.getInt("svc_hours", 4); set(v) = put("svc_hours", v)

    // ---- Cửa và kính ----
    var doorDropEnabled: Boolean get() = b("door_drop", false); set(v) = put("door_drop", v)
    var doorDropPercent: Int get() = sp.getInt("door_drop_pct", 15); set(v) = put("door_drop_pct", v)
    /** Chỉ hai cửa trước. */
    var doorDropFrontOnly: Boolean get() = b("door_drop_front", false); set(v) = put("door_drop_front", v)

    // ---- Âm thanh ----
    var duckOnNav: Boolean get() = b("duck_nav", true); set(v) = put("duck_nav", v)
    /** Mức nhạc (% tối đa) trong lúc bản đồ đọc chỉ đường. */
    var duckPercent: Int get() = sp.getInt("duck_pct", 30); set(v) = put("duck_pct", v)
    /** Âm lượng nhạc khi đầu xe khởi động; -1 = giữ nguyên. */
    var startupMusicVolume: Int get() = sp.getInt("startup_vol", -1); set(v) = put("startup_vol", v)

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

    /** Chỉ chạy kịch bản trong khung giờ [autoFromHour, autoToHour); -1 = cả ngày. Qua nửa đêm được (22 → 6). */
    var autoFromHour: Int get() = sp.getInt("auto_from_h", -1); set(v) = put("auto_from_h", v)
    var autoToHour: Int get() = sp.getInt("auto_to_h", -1); set(v) = put("auto_to_h", v)

    // Theo nhiệt độ ngoài trời (ghi đè nhiệt độ chung khi khớp)
    var hotOn: Boolean get() = b("hot_on", false); set(v) = put("hot_on", v)
    var hotAbove: Int get() = sp.getInt("hot_above", 30); set(v) = put("hot_above", v)
    var hotTemp: Float get() = sp.getFloat("hot_temp", 22f); set(v) = put("hot_temp", v)
    var hotRecirc: Boolean get() = b("hot_recirc", true); set(v) = put("hot_recirc", v)
    var coldOn: Boolean get() = b("cold_on", false); set(v) = put("cold_on", v)
    var coldBelow: Int get() = sp.getInt("cold_below", 18); set(v) = put("cold_below", v)
    var coldTemp: Float get() = sp.getFloat("cold_temp", 26f); set(v) = put("cold_temp", v)
    var coldDefrost: Boolean get() = b("cold_defrost", true); set(v) = put("cold_defrost", v)

    /** Đọc GPS USB (u-blox) và đưa vào vị trí "gps" của hệ thống không cờ giả lập (cho Vietmap). */
    var gpsRelay: Boolean get() = b("gps_relay", true); set(v) = put("gps_relay", v)

    /** Dung tích bình (lít) người dùng nhập; 0 = lấy từ xe. Dùng trong CarPropertySource. */
    var tankLiters: Int get() = sp.getInt("tank_l", 0); set(v) = put("tank_l", v)
}
