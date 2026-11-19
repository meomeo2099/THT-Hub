package vn.tht.hub.keys

import android.content.Context
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * Hành động gán cho nút vô lăng. [PASS] = giữ chức năng gốc của xe (không chặn phím).
 * Chỉ gồm chức năng Coolray 2025 có thật; không có sưởi ghế/vô lăng. Nút voice để cho THT Voice.
 */
enum class KeyAction(val label: String) {
    PASS("Giữ chức năng gốc"),
    PANEL("Mở/đóng bảng điều khiển nhanh"),
    PLAY_PAUSE("Phát / dừng nhạc"),
    NEXT("Bài tiếp"),
    PREV("Bài trước"),
    MUTE("Tắt / bật tiếng"),
    OPEN_APP("Mở app…"),
    HOME("Về màn hình chính"),
    CAMERA_360("Mở camera 360"),
    DRIVE_MODE("Đổi chế độ lái (Eco → Comfort → Sport)"),
    HVAC_POWER("Điều hoà bật / tắt"),
    HVAC_AUTO("Điều hoà AUTO"),
    AC("A/C bật / tắt"),
    TEMP_UP("Nhiệt độ +0,5°"),
    TEMP_DOWN("Nhiệt độ −0,5°"),
    FAN_UP("Quạt +1"),
    FAN_DOWN("Quạt −1"),
    RECIRC("Gió trong / ngoài"),
    DEFROST_FRONT("Sấy kính trước"),
    WINDOWS_CLOSE("Đóng hết kính"),
}

/** Một nút trên hình vô lăng: mã phím đã học và hai hành động (nhấn / giữ). */
data class KeySlot(
    val id: String,
    var code: Int = -1,
    var press: KeyAction = KeyAction.PASS,
    var pressArg: String = "",
    var hold: KeyAction = KeyAction.PASS,
    var holdArg: String = "",
) {
    val learned get() = code > 0
    val active get() = learned && (press != KeyAction.PASS || hold != KeyAction.PASS)
    val keyName: String get() = if (!learned) "Chưa học" else keyLabel(code)
}

/** Tên dễ đọc cho mã phím. */
fun keyLabel(code: Int): String = when (code) {
    KeyEvent.KEYCODE_MEDIA_NEXT -> "Bài tiếp"
    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "Bài trước"
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> "Phát/dừng"
    KeyEvent.KEYCODE_VOLUME_UP -> "Âm lượng +"
    KeyEvent.KEYCODE_VOLUME_DOWN -> "Âm lượng −"
    KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_MUTE -> "Tắt tiếng"
    KeyEvent.KEYCODE_CALL -> "Nghe gọi"
    KeyEvent.KEYCODE_ENDCALL -> "Kết thúc gọi"
    KeyEvent.KEYCODE_VOICE_ASSIST, 298 -> "Giọng nói"
    300 -> "Tắt tiếng (ECARX)"
    301, 302, 303 -> "Phím ECARX"
    304 -> "◀ Trái (ECARX)"
    KeyEvent.KEYCODE_HOME -> "Home"
    KeyEvent.KEYCODE_BACK -> "Quay lại"
    else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").let { if (it.all(Char::isDigit)) "Mã $code" else it }
} + " ($code)"

object KeyMap {
    /** Các nút thật trên vô lăng Coolray 2025, xem [WheelButton]. */
    val IDS = WheelButton.values().map { it.name }

    private const val KEY = "wheel_keys"
    private fun sp(c: Context) = c.getSharedPreferences("tht_hub", Context.MODE_PRIVATE)

    fun load(c: Context): List<KeySlot> {
        val saved = runCatching { JSONArray(sp(c).getString(KEY, "[]")) }.getOrDefault(JSONArray())
        val byId = (0 until saved.length()).mapNotNull { saved.optJSONObject(it) }.associateBy { it.optString("id") }
        return IDS.map { id ->
            val known = WheelButton.valueOf(id).knownCode
            val o = byId[id] ?: return@map KeySlot(id, code = known)
            KeySlot(
                id, o.optInt("code", -1),
                runCatching { KeyAction.valueOf(o.optString("press")) }.getOrDefault(KeyAction.PASS), o.optString("pressArg"),
                runCatching { KeyAction.valueOf(o.optString("hold")) }.getOrDefault(KeyAction.PASS), o.optString("holdArg"),
            )
        }
    }

    fun save(c: Context, slots: List<KeySlot>) {
        val arr = JSONArray()
        slots.forEach { s ->
            arr.put(JSONObject().put("id", s.id).put("code", s.code)
                .put("press", s.press.name).put("pressArg", s.pressArg)
                .put("hold", s.hold.name).put("holdArg", s.holdArg))
        }
        sp(c).edit().putString(KEY, arr.toString()).apply()
        KeyService.instance?.reload()
    }
}
