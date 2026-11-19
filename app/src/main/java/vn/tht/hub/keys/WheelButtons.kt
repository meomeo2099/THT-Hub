package vn.tht.hub.keys

import vn.tht.hub.R

/**
 * Nút vô lăng Coolray 2025 theo ảnh vô lăng thật của chủ xe (19/11/2026).
 * - Cụm trái: hỗ trợ lái, LIM, ≡−, RES+/SET−/bật ga tự động, ≡ (ga tự động – xe xử lý trực tiếp, KHÔNG qua đầu màn hình),
 *   hàng dưới: giọng nói, nghe máy, cúp máy.
 * - Cụm phải: Home, Tắt tiếng, ◀, con lăn ▲▼, ▶, MODE, đổi màn hình.
 * Mã đã thấy trên xe (thao_tac/06b_PHIM_VO_LANG): 300 = Tắt tiếng, 304 = ◀. 298 = giọng nói (THT Voice).
 */
enum class WheelButton(
    val label: String,
    val left: Boolean,
    /** Biểu tượng; 0 = vẽ chữ [text]. */
    val icon: Int = 0,
    val text: String = "",
    /** Màu biểu tượng (0 = trắng). */
    val tint: Int = 0,
    val knownCode: Int = -1,
    /** Dành cho THT Voice: hiện nhưng không gán. */
    val reserved: Boolean = false,
    /** Nút ga tự động / hỗ trợ lái: xe xử lý trực tiếp, app không bắt được. */
    val vehicleOnly: Boolean = false,
) {
    // ---- Cụm trái: ga tự động (xe xử lý) ----
    ASSIST("Hỗ trợ lái / giữ làn", true, icon = R.drawable.ic_cruise, vehicleOnly = true),
    LIM("LIM · giới hạn tốc độ", true, text = "LIM", vehicleOnly = true),
    GAP_DOWN("Giảm khoảng cách (≡−)", true, text = "≡−", vehicleOnly = true),
    RES("RES / + (tăng tốc độ ga)", true, text = "RES+", vehicleOnly = true),
    SET("SET / − (giảm tốc độ ga)", true, text = "SET−", vehicleOnly = true),
    GAP_UP("Tăng khoảng cách (≡)", true, text = "≡", vehicleOnly = true),
    // ---- Cụm trái hàng dưới: đầu màn hình ----
    VOICE("Giọng nói (THT Voice)", true, icon = R.drawable.ic_mic, knownCode = 298, reserved = true),
    CALL("Nghe máy", true, icon = R.drawable.ic_phone, tint = 0xFF35C46A.toInt()),
    HANG_UP("Cúp máy", true, icon = R.drawable.ic_phone, tint = 0xFFFF5A5F.toInt()),
    // ---- Cụm phải ----
    HOME("Home", false, icon = R.drawable.ic_home),
    MUTE("Tắt tiếng", false, icon = R.drawable.ic_muted, knownCode = 300),
    PREV("◀ Trái (bài trước)", false, icon = R.drawable.ic_chevron_left, knownCode = 304),
    ROLL_UP("Con lăn lên (âm lượng +)", false, icon = R.drawable.ic_chevron_up),
    ROLL_DOWN("Con lăn xuống (âm lượng −)", false, icon = R.drawable.ic_chevron_down),
    NEXT("▶ Phải (bài tiếp)", false, icon = R.drawable.ic_chevron_right),
    MODE("MODE (đổi nguồn)", false, text = "MODE"),
    VIEW("Đổi màn hình", false, icon = R.drawable.ic_view_switch),
}
