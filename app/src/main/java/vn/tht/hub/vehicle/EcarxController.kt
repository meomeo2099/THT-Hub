package vn.tht.hub.vehicle

import android.os.Handler
import android.os.Looper
import java.util.Locale
import java.util.concurrent.Executors
import vn.tht.hub.vehicle.EcarxFunctions as F

/**
 * Lệnh điều khiển xe (điều hoà, chế độ lái, đèn viền) qua [EcarxCarSource].
 *
 * Mỗi lệnh: kiểm tra xe hỗ trợ -> kiểm tra an toàn -> ghi -> chờ -> ĐỌC LẠI, và chỉ báo thành công
 * khi giá trị đọc lại khớp. Chạy trên luồng nền (IPC), kết quả trả về main thread.
 *
 * Cơ cấu chấp hành (cửa sổ trời, rèm, kính, cốp) không đọc lại được ngay vì cần vài giây để chạy:
 * các lệnh đó chỉ báo thành công khi SDK nhận lệnh, giống AutoEasy.
 */
class EcarxController(
    private val ecarx: EcarxCarSource,
    /** Tốc độ hiện tại (km/h) từ CarPropertySource; null = không đọc được. */
    private val speedKmh: () -> Float?,
    /** Số hiện tại ("P", "R", "N", "D"…); null = không đọc được. */
    private val gear: () -> String? = { null },
) {
    data class Result(val ok: Boolean, val message: String)

    /** Mức an toàn của lệnh (theo spec mục 4.4 / 8). */
    enum class Safety { SAFE, BELOW_80, PARKED, PARKED_IN_P }

    /**
     * Hai luồng riêng: đọc (poll mỗi 1,5–2 s, ~25 IPC) không được chặn lệnh ghi
     * (mỗi lệnh ghi còn chờ đọc lại tới 1,2 s).
     */
    private val ioRead = Executors.newSingleThreadExecutor()
    private val ioWrite = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Danh sách chế độ lái xe hỗ trợ (chỉ dò một lần, xem [driveModes]). */
    @Volatile private var driveModesCache: List<Pair<Int, String>>? = null

    /** Đã nối được xe thì cho điều khiển; chữ ký platform chỉ còn là thông tin chẩn đoán. */
    val canWrite: Boolean get() = ecarx.connected

    fun supported(id: Int, zone: Int = F.ZONE_ALL): Boolean = ecarx.isSupported(id, zone) == true

    private fun run(done: (Result) -> Unit, block: () -> Result) {
        ioWrite.execute {
            val r = try {
                if (!ecarx.connected) Result(false, "Chưa nối được ECARX") else block()
            } catch (t: Throwable) {
                Result(false, "Lỗi: ${t.message ?: t.javaClass.simpleName}")
            }
            main.post { done(r) }
        }
    }

    private fun unsupported() = Result(false, "Xe không hỗ trợ tính năng này")

    private fun safetyBlock(safety: Safety): Result? {
        if (safety == Safety.SAFE) return null
        val speed = speedKmh()
        if (safety == Safety.BELOW_80) {
            return if (speed == null || speed > 80f) Result(false, "Không mở kính/cửa sổ trời khi xe chạy trên 80 km/h") else null
        }
        if (speed == null || speed >= 1f) return Result(false, "Chỉ làm được khi xe đứng yên")
        if (safety == Safety.PARKED_IN_P && gear() != "P") return Result(false, "Chỉ làm được khi xe ở số P")
        return null
    }

    /** Chạy [block] trên luồng đọc (poll), trả kết quả về main thread. */
    fun <T> background(block: () -> T, done: (T) -> Unit) {
        ioRead.execute {
            val v = runCatching(block).getOrNull()
            @Suppress("UNCHECKED_CAST")
            main.post { done(v as T) }
        }
    }

    // ---- Thao tác tổng quát (dùng cho Trung tâm điều khiển) ----

    fun setValue(id: Int, zone: Int, value: Int, okMsg: String, safety: Safety = Safety.SAFE, done: (Result) -> Unit) =
        run(done) { safetyBlock(safety) ?: setIntVerified(id, zone, value, okMsg) }

    fun toggleFn(id: Int, zone: Int, name: String, safety: Safety = Safety.SAFE, done: (Result) -> Unit) = run(done) {
        safetyBlock(safety)?.let { return@run it }
        if (!supported(id, zone)) return@run unsupported()
        // Đọc không được thì báo lỗi, KHÔNG đoán là "đang tắt" (trước đây null -> luôn gửi ON)
        val cur = ecarx.getInt(id, zone) ?: return@run Result(false, "Không đọc được trạng thái $name")
        val target = if (cur == F.ON) F.OFF else F.ON
        setIntVerified(id, zone, target, "$name: " + if (target == F.ON) "bật" else "tắt")
    }

    /** Chạm để sang mức kế tiếp: tắt -> 1 -> … -> [max] -> tắt. */
    fun cycleLevel(id: Int, zone: Int, max: Int, name: String, done: (Result) -> Unit) =
        cycleLevels(id, zone, IntArray(max) { it + 1 }, name, { n -> "mức $n" }, done)

    /** Như [cycleLevel] nhưng danh sách mức là giá trị thô (ví dụ sưởi vô lăng: 1, 2, 3, 15 = AUTO). */
    fun cycleLevels(
        id: Int, zone: Int, levels: IntArray, name: String,
        describe: (Int) -> String, done: (Result) -> Unit,
    ) = run(done) {
        if (!supported(id, zone)) return@run unsupported()
        val cur = F.levelOf(id, ecarx.getInt(id, zone)) ?: 0
        val i = levels.indexOfFirst { it == cur }
        val next = levels[(i + 1).mod(levels.size)]
        val value = if (next <= 0) F.OFF else F.level(id, next)
        setIntVerified(id, zone, value, if (next <= 0) "$name: tắt" else "$name: ${describe(next)}")
    }

    // ---- Điều hoà ----

    /** Dải nhiệt độ thật của xe: đọc `0x10060200` / `0x10060300`, hỏng thì dùng 17–32. */
    private fun tempRange(): ClosedFloatingPointRange<Float> {
        val lo = ecarx.getFloat(F.HVAC_TEMP_MIN) ?: ecarx.getInt(F.HVAC_TEMP_MIN)?.toFloat()
        val hi = ecarx.getFloat(F.HVAC_TEMP_MAX) ?: ecarx.getInt(F.HVAC_TEMP_MAX)?.toFloat()
        return if (lo != null && hi != null && hi > lo && lo >= 10f && hi <= 40f) lo..hi else F.TEMP_MIN..F.TEMP_MAX
    }

    /** Nhiệt độ mục tiêu sau một bước, đã làm tròn 0,5 °C như AutoEasy `w3.h()`. */
    private fun nextTemp(cur: Float, direction: Int, step: Float): Float {
        val r = tempRange()
        return F.roundTemp(cur + direction * step).coerceIn(r.start, r.endInclusive)
    }

    /** Nhiệt độ theo vùng (trái/phải/tất cả). */
    fun tempStepZone(zone: Int, direction: Int, done: (Result) -> Unit) = run(done) {
        if (!supported(F.HVAC_TEMP, zone)) return@run unsupported()
        val cur = ecarx.getFloat(F.HVAC_TEMP, zone) ?: return@run Result(false, "Không đọc được nhiệt độ")
        val step = ecarx.getFloat(F.HVAC_TEMP_STEP, F.ZONE_ALL)?.takeIf { it in 0.1f..2f } ?: F.TEMP_STEP_DEFAULT
        val target = nextTemp(cur, direction, step)
        if (target == cur) return@run Result(true, "Nhiệt độ đã ở mức giới hạn")
        setFloatVerified(F.HVAC_TEMP, zone, target, String.format(Locale.US, "Nhiệt độ %.1f°C", target))
    }

    /** Số mức quạt thật của xe (1..9), đọc `getSupportedFunctionValue`; bỏ mức AUTO (10). */
    private fun fanMax(): Int {
        val vals = ecarx.supportedValues(F.HVAC_FAN_SPEED) ?: return F.FAN_MAX
        return vals.map { v -> v - F.HVAC_FAN_SPEED }
            .filter { it in F.FAN_MIN..F.FAN_MAX }
            .maxOrNull() ?: F.FAN_MAX
    }

    /** Tăng/giảm quạt một mức trong dải thật của xe (1..9). */
    fun fanStep(direction: Int, done: (Result) -> Unit) = run(done) {
        if (!supported(F.HVAC_FAN_SPEED)) return@run unsupported()
        val cur = F.levelOf(F.HVAC_FAN_SPEED, ecarx.getInt(F.HVAC_FAN_SPEED, F.ZONE_ALL)) ?: 0
        val max = fanMax()
        val target = (cur + direction).coerceIn(F.FAN_MIN, max)
        if (target == cur) {
            // Đang ở mức cao nhất mà vẫn bấm +: AutoEasy đưa thanh quạt sang mức 10 = AUTO
            if (direction > 0 && ecarx.isSupported(F.HVAC_AUTO) == true) {
                return@run setIntVerified(F.HVAC_AUTO, F.ZONE_ALL, F.ON, "Quạt: AUTO")
            }
            return@run Result(true, "Quạt đã ở mức giới hạn")
        }
        setIntVerified(F.HVAC_FAN_SPEED, F.ZONE_ALL, F.level(F.HVAC_FAN_SPEED, target), "Quạt mức $target")
    }

    /**
     * Quạt AUTO. AutoEasy để thanh quạt ở mức 10 (`HVAC_FAN_SPEED + 10` = `268566794`) nhưng
     * `r3.a()` biến đúng giá trị đó thành `a(0x10010100, 1)` – tức bật AUTO của điều hoà,
     * vì ECU Coolray không nhận mức quạt 10 trực tiếp.
     */
    fun fanAuto(done: (Result) -> Unit) = run(done) {
        if (!supported(F.HVAC_AUTO)) return@run unsupported()
        setIntVerified(F.HVAC_AUTO, F.ZONE_ALL, F.ON, "Quạt: AUTO")
    }

    /** Độ sáng màn hình: customize float 0..15 (`0x29030500`), dải đọc từ `0x20150500/0x20150600`. */
    fun backlightStep(direction: Int, done: (Result) -> Unit) = run(done) {
        if (!supported(F.BACKLIGHT)) return@run unsupported()
        val cur = ecarx.getFloat(F.BACKLIGHT, F.ZONE_ALL) ?: return@run Result(false, "Không đọc được độ sáng")
        // Xe không báo dải thì dùng 0..15 như AutoEasy `q5.t()`; tránh trường hợp max = 0 làm lệnh thành no-op
        val max = (ecarx.getFloat(F.BACKLIGHT_MAX) ?: ecarx.getInt(F.BACKLIGHT_MAX)?.toFloat())
            ?.takeIf { it > 0f && it <= 1000f } ?: F.BACKLIGHT_RANGE_MAX
        val min = (ecarx.getFloat(F.BACKLIGHT_MIN) ?: ecarx.getInt(F.BACKLIGHT_MIN)?.toFloat())
            ?.takeIf { it >= 0f && it < max } ?: 0f
        val step = ((max - min) / F.BACKLIGHT_RANGE_MAX).coerceAtLeast(0.5f)
        val target = (cur + direction * step).coerceIn(min, max)
        if (kotlin.math.abs(target - cur) < 0.01f) return@run Result(true, "Độ sáng đã ở mức giới hạn")
        setFloatVerified(F.BACKLIGHT, F.ZONE_ALL, target, "Độ sáng ${Math.round(target)}/${Math.round(max)}")
    }

    /** Tăng/giảm nhiệt độ một bước (đọc HVAC_TEMP_STEP, mặc định 0,5 °C). */
    fun tempStep(direction: Int, done: (Result) -> Unit) = run(done) {
        val zone = if (supported(F.HVAC_TEMP, F.ZONE_ROW_1_LEFT)) F.ZONE_ROW_1_LEFT else F.ZONE_ALL
        if (!supported(F.HVAC_TEMP, zone)) return@run unsupported()
        val cur = ecarx.getFloat(F.HVAC_TEMP, zone) ?: return@run Result(false, "Không đọc được nhiệt độ")
        val step = ecarx.getFloat(F.HVAC_TEMP_STEP, F.ZONE_ALL)?.takeIf { it in 0.1f..2f } ?: F.TEMP_STEP_DEFAULT
        val target = nextTemp(cur, direction, step)
        if (target == cur) return@run Result(true, "Nhiệt độ đã ở mức giới hạn")
        setFloatVerified(F.HVAC_TEMP, zone, target, String.format(Locale.US, "Nhiệt độ %.1f°C", target))
    }

    /** Đặt nhiệt độ (cùng vùng với [tempStep]). */
    fun setTemp(target: Float, done: (Result) -> Unit) = run(done) {
        val zone = if (supported(F.HVAC_TEMP, F.ZONE_ROW_1_LEFT)) F.ZONE_ROW_1_LEFT else F.ZONE_ALL
        if (!supported(F.HVAC_TEMP, zone)) return@run unsupported()
        val r = tempRange()
        val t = F.roundTemp(target).coerceIn(r.start, r.endInclusive)
        setFloatVerified(F.HVAC_TEMP, zone, t, String.format(Locale.US, "Nhiệt độ %.1f°C", t))
    }

    // ---- Thân xe ----

    /** Cửa sổ trời: customize float 0..100 % (`0x21030300`, vùng 4). */
    fun setRoof(percent: Int, done: (Result) -> Unit) = run(done) {
        val p = percent.coerceIn(0, 100)
        // Chỉ chặn khi MỞ; đóng cửa sổ trời phải luôn được (kể cả lúc chạy nhanh, ví dụ tự động đóng).
        if (p > 0) safetyBlock(Safety.BELOW_80)?.let { return@run it }
        if (!ecarx.setFloat(F.ROOF_POSITION, F.ZONE_ROOF, p.toFloat())) {
            return@run Result(false, "Xe không nhận lệnh cửa sổ trời")
        }
        Result(true, if (p == 0) "Đóng cửa sổ trời" else if (p >= 100) "Mở cửa sổ trời" else "Cửa sổ trời mở $p%")
    }

    /** Rèm che nắng: cùng `0x21030300` vùng 8 (100 = đóng), dự phòng `0x21030000` int 0/1. */
    fun setCurtain(open: Boolean, done: (Result) -> Unit) = run(done) {
        val percent = if (open) 0f else 100f
        val msg = if (open) "Mở rèm" else "Đóng rèm"
        if (ecarx.setFloat(F.ROOF_POSITION, F.ZONE_CURTAIN, percent)) return@run Result(true, msg)
        if (ecarx.setInt(F.CURTAIN_SWITCH, F.ZONE_CURTAIN, if (open) 0 else 1)) return@run Result(true, msg)
        Result(false, "Xe không nhận lệnh rèm")
    }

    /**
     * Kính theo phần trăm (Coolray 2025, theo THT Voice): MCU `0x287000A2`, 8 byte —
     * 0 lái, 1 phụ, 2 sau trái, 3 sau phải; 0–100 % mở, 0xFF giữ nguyên. [windows] = các chỉ số kính cần chạy.
     * Đóng luôn được; mở bị chặn khi chạy trên 80 km/h. Thành công = MCU nhận lệnh; vị trí đọc lại bằng [windowPositions].
     */
    fun setWindowPercent(windows: List<Int>, percent: Int, done: (Result) -> Unit) = run(done) {
        val p = percent.coerceIn(0, 100)
        if (p > 0) safetyBlock(Safety.BELOW_80)?.let { return@run it }
        val bytes = ByteArray(8) { -1 }
        windows.filter { it in 0..3 }.forEach { bytes[it] = p.toByte() }
        val what = if (windows.size >= 4) "tất cả kính" else windows.joinToString(", ") { WINDOW_NAMES.getOrElse(it) { "$it" } }
        if (ecarx.sendBytesToMcu(WINDOW_GROUP, bytes)) {
            Result(true, when (p) { 0 -> "Đóng $what"; 100 -> "Mở hết $what"; else -> "Hé $what $p%" })
        } else Result(false, "Xe không nhận lệnh kính (MCU)")
    }

    /** Vị trí 4 kính (0–100 %), null = không đọc được. */
    fun windowPositions(): List<Int?> = WINDOW_STATE.map { prop ->
        ecarx.getIntByProp(prop, WINDOW_POS_AREA)?.takeIf { it in 0..100 }
    }

    /** Tất cả kính: `0x20030600` + `0x20030602` (hé/mở) hoặc `0x20030603` (đóng). */
    fun setWindows(open: Boolean, done: (Result) -> Unit) = run(done) {
        // Đóng kính luôn được phép; chỉ chặn mở khi chạy nhanh
        if (open) safetyBlock(Safety.BELOW_80)?.let { return@run it }
        val v = if (open) F.WINDOW_ALL_OPEN else F.WINDOW_ALL_CLOSE
        if (ecarx.setInt(F.WINDOW_ALL, F.ZONE_ALL, v)) {
            Result(true, if (open) "Hé/mở tất cả kính" else "Đóng tất cả kính")
        } else {
            Result(false, "Xe không nhận lệnh kính")
        }
    }

    /** Cốp sau: `0x21020100` với zone [F.ZONE_DOOR_REAR] (= `Int.MIN_VALUE`, không phải function ID). */
    fun setTrunk(done: (Result) -> Unit) = run(done) {
        safetyBlock(Safety.PARKED_IN_P)?.let { return@run it }
        if (ecarx.setInt(F.BCM_DOOR, F.ZONE_DOOR_REAR, F.ON)) Result(true, "Mở cốp")
        else Result(false, "Xe không nhận lệnh mở cốp")
    }

    // ---- Chế độ lái (chỉ khi xe đứng yên) ----

    /**
     * Chế độ lái xe thật sự hỗ trợ, đọc `getSupportedFunctionValue(0x22000000)` như AutoEasy;
     * xe không trả gì thì dùng bộ mặc định Eco/Comfort/Sport. Cache lại vì UI hỏi mỗi lần mở tab.
     */
    fun driveModes(): List<Pair<Int, String>> {
        driveModesCache?.let { return it }
        val all = (F.DRIVE_MODES + F.DRIVE_MODES_EXTRA).distinctBy { it.first }
        val list = runCatching {
            val vals = ecarx.supportedValues(F.DRIVE_MODE)
            if (vals == null || vals.isEmpty()) all.take(F.DRIVE_MODES.size)
            else all.filter { (n, _) -> vals.any { it == n || it == F.level(F.DRIVE_MODE, n) } }
                .ifEmpty { all.take(F.DRIVE_MODES.size) }
        }.getOrDefault(F.DRIVE_MODES)
        driveModesCache = list
        return list
    }

    fun setDriveMode(n: Int, done: (Result) -> Unit) = run(done) {
        val speed = speedKmh()
        if (speed == null || speed >= 1f) {
            return@run Result(false, "Chỉ đổi chế độ lái khi xe đứng yên")
        }
        val name = (F.DRIVE_MODES + F.DRIVE_MODES_EXTRA).firstOrNull { it.first == n }?.second ?: "$n"
        setIntVerified(F.DRIVE_MODE, F.ZONE_ALL, F.level(F.DRIVE_MODE, n), "Chế độ lái: $name")
    }

    // ---- Đèn viền ----

    fun toggleAmbient(done: (Result) -> Unit) = toggle(F.AMBIENT_SWITCH, "Đèn viền", done)

    /**
     * Cường độ đèn viền 0..15. AutoEasy `i2.java:53-54` ghi thẳng `A(0x2A010100, v)`
     * (không vùng, không qua bảng mức), nên ở đây cũng ghi giá trị thô.
     */
    fun ambientIntensityStep(direction: Int, done: (Result) -> Unit) = run(done) {
        if (!supported(F.AMBIENT_INTENSITY)) return@run unsupported()
        val cur = ecarx.getInt(F.AMBIENT_INTENSITY, F.ZONE_ALL)
            ?: return@run Result(false, "Không đọc được độ sáng đèn viền")
        val target = (cur + direction).coerceIn(0, F.AMBIENT_INTENSITY_MAX)
        if (target == cur) return@run Result(true, "Độ sáng đèn viền đã ở mức giới hạn")
        if (!ecarx.setIntNoZone(F.AMBIENT_INTENSITY, target)) return@run Result(false, "Xe không nhận lệnh")
        repeat(4) {
            Thread.sleep(VERIFY_DELAY_MS)
            val w = watchedInt(F.AMBIENT_INTENSITY, F.ZONE_ALL)
            if (w == target || ecarx.getInt(F.AMBIENT_INTENSITY, F.ZONE_ALL) == target) {
                return@run Result(true, "Độ sáng đèn viền: $target/${F.AMBIENT_INTENSITY_MAX}")
            }
        }
        Result(false, "Đã gửi lệnh nhưng đọc lại chưa thấy đổi")
    }

    /**
     * Màu đèn viền. AutoEasy `gl.java` case 2: Coolray ghi RGB 24-bit vào `0x2A050000` rồi áp dụng
     * `0x200A0000 + 3`; Monjaro ghi cùng RGB vào `0x2A010300`. Dự phòng cuối là bảng màu theo chỉ số.
     */
    fun setAmbientColor(n: Int, done: (Result) -> Unit) = run(done) {
        val name = F.AMBIENT_COLORS.firstOrNull { it.first == n }?.second ?: "$n"
        val rgb = F.ambientRgb(n) and 0xFFFFFF
        // Coolray: `A(0x2A050000, rgb)` rồi `A(0x200A0000, 0x200A0003)` – bản KHÔNG kèm vùng
        val coolray = ecarx.setIntNoZone(F.AMBIENT_RGB, rgb) &&
            ecarx.setIntNoZone(F.AMBIENT_APPLY, F.AMBIENT_APPLY + 3)
        if (coolray) return@run Result(true, "Đèn viền màu $name")
        // Monjaro: thang vùng MIN_VALUE → 0 → 1 trên 0x2A010300
        if (ecarx.setIntLadder(F.AMBIENT_THEME_COLOR, rgb)) return@run Result(true, "Đèn viền màu $name")
        if (ecarx.setInt(F.AMBIENT_INDEX_COLOR, F.ZONE_ALL, F.level(F.AMBIENT_INDEX_COLOR, n))) {
            return@run Result(true, "Đèn viền màu $name")
        }
        Result(false, "Xe không nhận lệnh đổi màu đèn viền")
    }

    private fun toggle(id: Int, name: String, done: (Result) -> Unit) = run(done) {
        if (!supported(id)) return@run unsupported()
        val cur = ecarx.getInt(id, F.ZONE_ALL) ?: return@run Result(false, "Không đọc được trạng thái $name")
        val target = if (cur == F.ON) F.OFF else F.ON
        setIntVerified(id, F.ZONE_ALL, target, "$name: " + if (target == F.ON) "bật" else "tắt")
    }

    // ---- Điều hoà (bật/tắt nhanh) ----

    fun toggleHvacPower(done: (Result) -> Unit) = toggle(F.HVAC_POWER, "Điều hoà", done)
    fun toggleAc(done: (Result) -> Unit) = toggle(F.HVAC_AC, "A/C", done)
    fun toggleAuto(done: (Result) -> Unit) = toggle(F.HVAC_AUTO, "AUTO", done)

    /**
     * Ghi int rồi đọc lại tới khi khớp (tối đa ~1,2 s).
     *
     * Coolray: AutoEasy `r3.a()` ghi mọi công tắc HVAC bằng `A(id, value)` (**không vùng**),
     * nên các ID `0x10xxxxxx` đi đường [EcarxCarSource.setIntHvac]. Các ID khác giữ đường có vùng.
     */
    private fun setIntVerified(id: Int, zone: Int, value: Int, okMsg: String): Result {
        if (!supported(id, zone)) return unsupported()
        val sent = if (zone == F.ZONE_ALL && id in HVAC_IDS) ecarx.setIntHvac(id, value)
        else ecarx.setInt(id, zone, value)
        if (!sent) return Result(false, "Xe không nhận lệnh")
        repeat(4) {
            Thread.sleep(VERIFY_DELAY_MS)
            if (watchedInt(id, zone) == value || ecarx.getInt(id, zone) == value) return Result(true, okMsg)
        }
        return Result(false, "Đã gửi lệnh nhưng đọc lại chưa thấy đổi")
    }

    private fun setFloatVerified(id: Int, zone: Int, value: Float, okMsg: String): Result {
        if (!supported(id, zone)) return unsupported()
        if (!ecarx.setFloat(id, zone, value)) return Result(false, "Xe không nhận lệnh")
        repeat(4) {
            Thread.sleep(VERIFY_DELAY_MS)
            val w = watchedFloat(id, zone)
            if (w != null && kotlin.math.abs(w - value) < 0.26f) return Result(true, okMsg)
            val v = ecarx.getFloat(id, zone)
            if (v != null && kotlin.math.abs(v - value) < 0.26f) return Result(true, okMsg)
        }
        return Result(false, "Đã gửi lệnh nhưng đọc lại chưa thấy đổi")
    }

    /**
     * Giá trị watcher vừa báo cho [id]. Xe có thể báo kèm vùng khác (`0` / `Int.MIN_VALUE`)
     * dù lệnh ghi không vùng, nên xét cả ba khoá.
     */
    private fun watchedInt(id: Int, zone: Int): Int? =
        ecarx.lastWatchedInt(id, zone)
            ?: ecarx.lastWatchedInt(id, F.ZONE_ALL)
            ?: ecarx.lastWatchedInt(id, Int.MIN_VALUE)

    private fun watchedFloat(id: Int, zone: Int): Float? =
        ecarx.lastWatchedFloat(id, zone)
            ?: ecarx.lastWatchedFloat(id, F.ZONE_ALL)
            ?: ecarx.lastWatchedFloat(id, Int.MIN_VALUE)

    fun shutdown() {
        ioRead.shutdownNow()
        ioWrite.shutdownNow()
    }

    companion object {
        /** MCU nhóm kính `INFO_ID_WINDOW_GROUP` (THT Voice Coolray2025Functions). */
        const val WINDOW_GROUP = 678428834            // 0x287000A2
        /** Trạng thái từng kính: lái, phụ, sau trái, sau phải (`0x2870009D..A0`). */
        val WINDOW_STATE = intArrayOf(678428829, 678428830, 678428831, 678428832)
        /** Vùng đọc vị trí: BCM_FUNC_WINDOW_POS. */
        const val WINDOW_POS_AREA = 553845504
        val WINDOW_NAMES = listOf("kính lái", "kính phụ", "kính sau trái", "kính sau phải")
        private const val VERIFY_DELAY_MS = 300L
        /** Dải ID điều hoà (`0x10000000..0x10FFFFFF`) – Coolray ghi các ID này bằng bản không vùng. */
        private val HVAC_IDS = 0x10000000..0x10FFFFFF
    }
}
