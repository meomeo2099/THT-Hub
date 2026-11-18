package vn.tht.hub.vehicle

/**
 * Function ID của ECARX AdaptAPI 1.1.18.9 (`ICarFunction`) mà launcher dùng.
 *
 * Nguồn: Coolray2025-Voice-Spec.md, phụ lục A, sinh tự động từ `AdapterAPI.jar` trên chính đầu xe
 * Coolray 2025. Có ID trong catalog chưa có nghĩa là xe có function đó: luôn kiểm tra bằng
 * `isFunctionSupported(id)` (bản 1 tham số – AutoEasy gọi đúng bản này; [EcarxCarSource] vẫn thử
 * bản 2 tham số nếu firmware chỉ có bản đó).
 *
 * Quy ước giá trị: bật/tắt = [ON]/[OFF] (tuyệt đối); giá trị enum = `ID + n` (xem [level]).
 */
object EcarxFunctions {

    data class Fn(val id: Int, val name: String, val zone: Int = ZONE_ALL)

    const val OFF = 0
    const val ON = 1

    /** Giá trị enum `id + n`; n <= 0 nghĩa là tắt (0 tuyệt đối). */
    fun level(id: Int, n: Int): Int = if (n <= 0) OFF else id + n

    /** Ngược của [level]: 0 -> 0, `id + n` -> n; một số firmware trả thẳng n nhỏ. */
    fun levelOf(id: Int, value: Int?): Int? = when {
        value == null -> null
        value == OFF -> 0
        value in (id + 1)..(id + 0xFF) -> value - id
        value in 1..15 -> value
        else -> null
    }

    // ----- Vùng (com.ecarx.xui.adaptapi.vehicle) -----
    const val ZONE_ALL = Int.MIN_VALUE
    const val ZONE_ROW_1_LEFT = 1
    const val ZONE_ROW_1_RIGHT = 4

    // ----- Điều hoà: car.hvac.IHvac -----
    const val HVAC_POWER = 268501248
    const val HVAC_AUTO = 268501504
    const val HVAC_AC = 268501760
    const val HVAC_AC_MAX = 268502016
    const val HVAC_FAN_SPEED = 268566784         // +1..+9 là mức, +10 = AUTO (FAN_AUTO)
    const val HVAC_CIRCULATION = 268632320       // +1 gió trong, +2 gió ngoài, +3 tự động
    const val HVAC_DEFROST_FRONT = 268697856
    const val HVAC_DEFROST_REAR = 268698368
    const val HVAC_TEMP = 268828928              // float (customize), 17–32 °C (dải thật ở HVAC_TEMP_MIN/MAX)
    const val HVAC_TEMP_STEP = 268829696
    const val HVAC_TEMP_DUAL = 268829952
    const val HVAC_BLOWING_MODE = 268894464      // +1..+8 (xem BLOWING_MODES)
    const val HVAC_DEFROST_FRONT_MAX = 268698112
    const val HVAC_SEAT_VENTILATION = 268763392  // zone trái/phải, +1..+3
    const val HVAC_SEAT_HEATING = 268763648      // zone trái/phải, +1..+3
    const val HVAC_STEERING_WHEEL_HEAT = 269025536 // +1 thấp, +2 vừa, +3 cao, +15 = AUTO
    /** Mức AUTO của sưởi vô lăng (`0x1009010F`). */
    const val WHEEL_HEAT_AUTO_LEVEL = 15
    const val HVAC_ECO = 268960000
    const val HVAC_IONS = 268961024
    const val HVAC_G_CLEAN = 269485056
    const val HVAC_AUTO_DRY = 269485312
    const val HVAC_PM25 = 269811968
    const val HVAC_FRAGRANCE = 269156608
    const val HVAC_FRAGRANCE_LEVEL = 269157120   // +1..+3

    val CIRCULATION_MODES = listOf(1 to "Gió trong", 2 to "Gió ngoài", 3 to "Tự động")

    /** 8 hướng gió của AutoEasy `w3.java:78-101`: 1..7 là tổ hợp, 8 là AUTO. */
    val BLOWING_MODES = listOf(
        1 to "Mặt", 2 to "Chân", 3 to "Mặt + chân", 4 to "Kính",
        5 to "Mặt + kính", 6 to "Chân + kính", 7 to "Tất cả", 8 to "Tự động",
    )

    /** Quạt: 1..9 là mức, [FAN_AUTO] (`0x1002010A`) là AUTO (`w3.java:30-39`). */
    const val FAN_MIN = 1
    const val FAN_MAX = 9
    const val FAN_AUTO = 10

    /** Dải nhiệt độ mặc định; dải thật đọc từ [HVAC_TEMP_MIN]/[HVAC_TEMP_MAX]. */
    const val TEMP_MIN = 17f
    const val TEMP_MAX = 32f
    const val TEMP_STEP_DEFAULT = 0.5f
    const val HVAC_TEMP_MIN = 268829184          // 0x10060200
    const val HVAC_TEMP_MAX = 268829440          // 0x10060300

    /** Làm tròn 0,5 °C như AutoEasy `w3.h()`. */
    fun roundTemp(v: Float): Float = Math.round(v / TEMP_STEP_DEFAULT) * TEMP_STEP_DEFAULT

    // ----- Thân xe: car.vehicle.IBcm / IVehicle -----
    /** `0x21030100` – lệnh cửa sổ theo zone; KHÔNG dùng cho cửa sổ trời/rèm (xem [ROOF_POSITION]). */
    const val BCM_WINDOW = 553844992
    const val BCM_SUNROOF_TILT = 553845760       // 0x21030400 – hé cửa sổ trời
    const val BCM_DOOR = 553779456               // 0x21020100 – cốp, zone = [ZONE_ALL]
    const val BCM_FOLD_MIRROR = 554041600

    /**
     * Cửa sổ trời / rèm: customize float 0..100 %, vùng [ZONE_ROOF] (trời) hoặc [ZONE_CURTAIN] (rèm).
     * AutoEasy `q5.G()` / `q5.E()` ghi `0x21030300`, đọc lại bằng [ROOF_POSITION_READ].
     */
    const val ROOF_POSITION = 553845504          // 0x21030300
    const val ROOF_POSITION_READ = 553846272     // 0x21030600
    /** Dự phòng khi xe không nhận customize (rèm đóng/mở dạng int), AutoEasy `q5.E()`. */
    const val CURTAIN_SWITCH = 553844736         // 0x21030000
    const val ZONE_ROOF = 4                      // WINDOW_ROOF_TOP
    const val ZONE_CURTAIN = 8                   // SUN_CURTAIN

    /** Cốp sau: zone phải là [ZONE_ALL] (`Integer.MIN_VALUE`), không phải function ID. */
    const val ZONE_DOOR_REAR = Int.MIN_VALUE

    /** Tất cả kính: function ID `0x20030600`, giá trị [WINDOW_ALL_OPEN] / [WINDOW_ALL_CLOSE]. */
    const val WINDOW_ALL = 537069056             // 0x20030600
    const val WINDOW_ALL_OPEN = 537069058        // 0x20030602
    const val WINDOW_ALL_CLOSE = 537069059       // 0x20030603

    // ----- Đèn ngoài / trong -----
    const val READING_LIGHT = 553980672
    /** Đèn ban ngày: `0x20040E00`, mode `+1..+3` (AutoEasy `ext_light`, `Guard.resolve(3..6)`). */
    const val DRL = 537136640
    const val WELCOME_LIGHT = 721617152
    const val AUTO_HIGH_BEAM = 721486080

    // ----- Màn hình -----
    /** Độ sáng: customize **float** 0..15 (`q5.f()` đọc `0x29030500`). */
    const val BACKLIGHT = 688063744              // 0x29030500
    const val BACKLIGHT_MAX = 538248704          // 0x20150600
    const val BACKLIGHT_MIN = 538248448          // 0x20150500
    const val BACKLIGHT_RANGE_MAX = 15f
    const val DAYMODE = 538247424                // +1 ngày, +2 đêm, +3 tự động
    val DAYMODES = listOf(1 to "Ngày", 2 to "Đêm", 3 to "Tự động")

    // ----- Đèn viền: car.vehicle.IBcm / IAmbienceLight -----
    const val AMBIENT_SWITCH = 553979904         // BCM_FUNC_LIGHT_ATMOSPHERE_LAMPS
    /** Màu theo mã RGB 24-bit trên Coolray (`0x2A050000`), rồi áp dụng bằng [AMBIENT_APPLY]. */
    const val AMBIENT_RGB = 704905472            // 0x2A050000
    const val AMBIENT_APPLY = 537526784          // 0x200A0000, ghi [AMBIENT_APPLY] + 3
    /** Đường màu theo chỉ số (Monjaro và một số firmware): giá trị = RGB 24-bit. */
    const val AMBIENT_THEME_COLOR = 704709376    // 0x2A010300
    /** Đường màu theo chỉ số cũ (`0x2A010200` + n) – chỉ dùng làm dự phòng. */
    const val AMBIENT_INDEX_COLOR = 704709120    // 0x2A010200
    /** Cường độ đèn viền: **int** 0..[AMBIENT_INTENSITY_MAX] (`A(0x2A010100, v)`). */
    const val AMBIENT_INTENSITY = 704708864      // 0x2A010100
    const val AMBIENT_INTENSITY_MAX = 15
    const val AMBIENT_MUSIC = 704972800          // +1 sôi động, +2 thường, +3 nhẹ
    val AMBIENT_MUSIC_MODES = listOf(1 to "Sôi động", 2 to "Thường", 3 to "Nhẹ")
    const val VOICE_LIGHT_MODE = 705103104       // hiệu ứng đèn theo giọng nói

    /** Màu đèn viền: chỉ số -> tên + mã RGB 24-bit (dùng để ghi bằng [AMBIENT_RGB]). */
    val AMBIENT_COLORS: List<Triple<Int, String, Int>> = listOf(
        Triple(1, "Đỏ", 0xFFE53935.toInt()),
        Triple(2, "Cam", 0xFFFB8C00.toInt()),
        Triple(3, "Vàng", 0xFFFDD835.toInt()),
        Triple(4, "Xanh lá", 0xFF43A047.toInt()),
        Triple(5, "Chàm", 0xFF3949AB.toInt()),
        Triple(6, "Xanh dương", 0xFF1E88E5.toInt()),
        Triple(7, "Tím", 0xFF8E24AA.toInt()),
        Triple(8, "Trắng", 0xFFFFFFFF.toInt()),
    )

    /** Mã RGB 24-bit của màu thứ [n] (1..8). */
    fun ambientRgb(n: Int): Int = AMBIENT_COLORS.firstOrNull { it.first == n }?.third ?: 0xFFFFFF

    // ----- Chế độ lái: car.vehicle.IDriveMode -----
    const val DRIVE_MODE = 570491136             // +1 eco, +2 comfort, +3 sport (dynamic)

    val DRIVE_MODES: List<Pair<Int, String>> = listOf(1 to "Eco", 2 to "Comfort", 3 to "Sport")
    /** Thêm các chế độ có thể có trên một số bản xe; chỉ hiện khi getSupportedFunctionValue có. */
    val DRIVE_MODES_EXTRA: List<Pair<Int, String>> = listOf(
        8 to "Power", 9 to "Tuyết", 13 to "Cát", 19 to "Off-road", 21 to "Sport+", 22 to "Thích ứng",
    )

    /** Toàn bộ ID để dò. Giữ đồng bộ với các hằng số ở trên. */
    val ALL: List<Fn> = listOf(
        Fn(HVAC_POWER, "HVAC_POWER"),
        Fn(HVAC_AUTO, "HVAC_AUTO"),
        Fn(HVAC_AC, "HVAC_AC"),
        Fn(HVAC_AC_MAX, "HVAC_AC_MAX"),
        Fn(HVAC_FAN_SPEED, "HVAC_FAN_SPEED"),
        Fn(HVAC_CIRCULATION, "HVAC_CIRCULATION"),
        Fn(HVAC_DEFROST_FRONT, "HVAC_DEFROST_FRONT"),
        Fn(HVAC_DEFROST_REAR, "HVAC_DEFROST_REAR"),
        Fn(HVAC_TEMP, "HVAC_TEMP (ALL)"),
        Fn(HVAC_TEMP, "HVAC_TEMP (LEFT)", ZONE_ROW_1_LEFT),
        Fn(HVAC_TEMP, "HVAC_TEMP (RIGHT)", ZONE_ROW_1_RIGHT),
        Fn(HVAC_TEMP_MIN, "HVAC_TEMP_MIN"),
        Fn(HVAC_TEMP_MAX, "HVAC_TEMP_MAX"),
        Fn(HVAC_TEMP_STEP, "HVAC_TEMP_STEP"),
        Fn(HVAC_TEMP_DUAL, "HVAC_TEMP_DUAL"),
        Fn(HVAC_BLOWING_MODE, "HVAC_BLOWING_MODE"),
        Fn(AMBIENT_SWITCH, "AMBIENT_SWITCH"),
        Fn(AMBIENT_RGB, "AMBIENT_RGB"),
        Fn(AMBIENT_APPLY, "AMBIENT_APPLY"),
        Fn(AMBIENT_THEME_COLOR, "AMBIENT_THEME_COLOR"),
        Fn(AMBIENT_INDEX_COLOR, "AMBIENT_INDEX_COLOR"),
        Fn(AMBIENT_INTENSITY, "AMBIENT_INTENSITY"),
        Fn(DRIVE_MODE, "DRIVE_MODE"),
        Fn(HVAC_DEFROST_FRONT_MAX, "HVAC_DEFROST_FRONT_MAX"),
        Fn(HVAC_SEAT_HEATING, "SEAT_HEAT (LEFT)", ZONE_ROW_1_LEFT),
        Fn(HVAC_SEAT_HEATING, "SEAT_HEAT (RIGHT)", ZONE_ROW_1_RIGHT),
        Fn(HVAC_SEAT_VENTILATION, "SEAT_VENT (LEFT)", ZONE_ROW_1_LEFT),
        Fn(HVAC_SEAT_VENTILATION, "SEAT_VENT (RIGHT)", ZONE_ROW_1_RIGHT),
        Fn(HVAC_STEERING_WHEEL_HEAT, "WHEEL_HEAT"),
        Fn(HVAC_ECO, "HVAC_ECO"),
        Fn(HVAC_IONS, "HVAC_IONS"),
        Fn(HVAC_G_CLEAN, "HVAC_G_CLEAN"),
        Fn(HVAC_AUTO_DRY, "HVAC_AUTO_DRY"),
        Fn(HVAC_PM25, "HVAC_PM25"),
        Fn(HVAC_FRAGRANCE, "HVAC_FRAGRANCE"),
        Fn(ROOF_POSITION, "SUNROOF", ZONE_ROOF),
        Fn(ROOF_POSITION, "SUN_CURTAIN", ZONE_CURTAIN),
        Fn(ROOF_POSITION_READ, "SUNROOF (đọc)", ZONE_ROOF),
        Fn(ROOF_POSITION_READ, "SUN_CURTAIN (đọc)", ZONE_CURTAIN),
        Fn(WINDOW_ALL, "WINDOWS (ALL)"),
        Fn(BCM_SUNROOF_TILT, "SUNROOF_TILT", ZONE_ROOF),
        Fn(BCM_WINDOW, "WINDOWS (zone)"),
        Fn(BCM_DOOR, "TRUNK", ZONE_DOOR_REAR),
        Fn(BCM_FOLD_MIRROR, "FOLD_MIRROR"),
        Fn(READING_LIGHT, "READING_LIGHT"),
        Fn(DRL, "DRL"),
        Fn(WELCOME_LIGHT, "WELCOME_LIGHT"),
        Fn(AUTO_HIGH_BEAM, "AUTO_HIGH_BEAM"),
        Fn(BACKLIGHT, "BACKLIGHT"),
        Fn(BACKLIGHT_MIN, "BACKLIGHT_MIN"),
        Fn(BACKLIGHT_MAX, "BACKLIGHT_MAX"),
        Fn(DAYMODE, "DAYMODE"),
        Fn(AMBIENT_MUSIC, "AMBIENT_MUSIC"),
        Fn(VOICE_LIGHT_MODE, "VOICE_LIGHT_MODE"),
    )

    fun hex(id: Int): String = "0x%08X".format(id)
}
