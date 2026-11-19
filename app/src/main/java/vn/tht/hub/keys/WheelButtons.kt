package vn.tht.hub.keys

import vn.tht.hub.R

/**
 * Nút vô lăng Coolray 2025 (SX11-LA). Nguồn:
 * - thao_tac/06b_PHIM_VO_LANG/logcat.txt (bấm trên xe): mã 300 = 静音 Tắt tiếng (cụm phải, ECarXRMuteAction),
 *   mã 304 = 左 Trái (cụm trái, ECarXRMediaPreviousAction).
 * - Bảng tên nút của ECarXDebugTools: 上 下 左 右 静音 电话 音源切换.
 * - THT Voice: mã 298 = nút giọng nói.
 * [knownCode] > 0 chỉ khi đã thấy trên xe; các nút khác phải học mã.
 */
enum class WheelButton(
    val label: String,
    val icon: Int,
    val left: Boolean,
    val knownCode: Int = -1,
    /** Nút dành cho app khác (THT Voice): hiện trên hình nhưng không gán. */
    val reserved: Boolean = false,
) {
    UP("Lên", R.drawable.ic_chevron_up, true),
    DOWN("Xuống", R.drawable.ic_chevron_down, true),
    LEFT("Trái · bài trước", R.drawable.ic_chevron_left, true, knownCode = 304),
    RIGHT("Phải · bài tiếp", R.drawable.ic_chevron_right, true),
    VOL_UP("Âm lượng +", R.drawable.ic_plus, false),
    VOL_DOWN("Âm lượng −", R.drawable.ic_minus, false),
    MUTE("Tắt tiếng", R.drawable.ic_muted, false, knownCode = 300),
    PHONE("Điện thoại", R.drawable.ic_phone, false),
    SOURCE("Đổi nguồn âm thanh", R.drawable.ic_radio, false),
    VOICE("Giọng nói (THT Voice)", R.drawable.ic_mic, false, knownCode = 298, reserved = true),
}
