package vn.tht.hub.vehicle

import android.content.Context
import android.content.pm.ApplicationInfo
import vn.tht.hub.Prefs

/**
 * Xe giả cho bản debug trên máy không có ECARX (máy ảo, điện thoại): có cùng tên hàm với
 * `ICarFunction` nên [EcarxCarSource] gọi được bằng reflection. Dùng để xem/kiểm thử giao diện.
 *
 * Chỉ bật khi: APK debuggable + Cài đặt → "Chế độ demo" + không nạp được ECARX thật.
 */
@Suppress("unused", "UNUSED_PARAMETER") // gọi qua reflection
class DemoCarFunction {
    private val ints = HashMap<Long, Int>()
    private val floats = HashMap<Long, Float>()

    private fun key(id: Int, zone: Int) = (id.toLong() shl 32) or (zone.toLong() and 0xFFFFFFFFL)

    init {
        val f = EcarxFunctions
        ints[key(f.HVAC_POWER, f.ZONE_ALL)] = f.ON
        ints[key(f.HVAC_AC, f.ZONE_ALL)] = f.ON
        ints[key(f.HVAC_FAN_SPEED, f.ZONE_ALL)] = f.level(f.HVAC_FAN_SPEED, 3)
        ints[key(f.DRIVE_MODE, f.ZONE_ALL)] = f.level(f.DRIVE_MODE, 2)
        ints[key(f.HVAC_CIRCULATION, f.ZONE_ALL)] = f.level(f.HVAC_CIRCULATION, 3)
        ints[key(f.HVAC_BLOWING_MODE, f.ZONE_ALL)] = f.level(f.HVAC_BLOWING_MODE, 1)
        floats[key(f.BACKLIGHT, f.ZONE_ALL)] = 7f
        floats[key(f.BACKLIGHT_MIN, f.ZONE_ALL)] = 0f
        floats[key(f.BACKLIGHT_MAX, f.ZONE_ALL)] = 15f
        floats[key(f.ROOF_POSITION, f.ZONE_ROOF)] = 0f
        floats[key(f.ROOF_POSITION, f.ZONE_CURTAIN)] = 0f
        floats[key(f.ROOF_POSITION_READ, f.ZONE_ROOF)] = 0f
        floats[key(f.ROOF_POSITION_READ, f.ZONE_CURTAIN)] = 0f
        floats[key(f.HVAC_TEMP, f.ZONE_ROW_1_LEFT)] = 24f
        floats[key(f.HVAC_TEMP, f.ZONE_ROW_1_RIGHT)] = 23.5f
        floats[key(f.HVAC_TEMP, f.ZONE_ALL)] = 24f
        floats[key(f.HVAC_TEMP_STEP, f.ZONE_ALL)] = 0.5f
    }

    fun isFunctionSupported(id: Int): String = isFunctionSupported(id, EcarxFunctions.ZONE_ALL)

    fun isFunctionSupported(id: Int, zone: Int): String =
        if (EcarxFunctions.ALL.any { it.id == id } || id == EcarxFunctions.BACKLIGHT_MIN || id == EcarxFunctions.BACKLIGHT_MAX) "active" else "notavailable"

    fun getFunctionValue(id: Int): Int = ints[key(id, EcarxFunctions.ZONE_ALL)] ?: 0
    fun getFunctionValue(id: Int, zone: Int): Int = ints[key(id, zone)] ?: 0
    fun setFunctionValue(id: Int, value: Int): Boolean { ints[key(id, EcarxFunctions.ZONE_ALL)] = value; return true }
    fun setFunctionValue(id: Int, zone: Int, value: Int): Boolean { ints[key(id, zone)] = value; return true }
    fun getCustomizeFunctionValue(id: Int, zone: Int): Float = floats[key(id, zone)] ?: 0f
    fun setCustomizeFunctionValue(id: Int, zone: Int, value: Float): Boolean { floats[key(id, zone)] = value; return true }

    /** Giống firmware thật: quạt trả đủ mức 1..9 + AUTO để [EcarxController.fanMax] đọc được. */
    fun getSupportedFunctionValue(id: Int): IntArray = when (id) {
        EcarxFunctions.HVAC_FAN_SPEED ->
            IntArray(EcarxFunctions.FAN_MAX) { EcarxFunctions.HVAC_FAN_SPEED + it + 1 } +
                (EcarxFunctions.HVAC_FAN_SPEED + EcarxFunctions.FAN_AUTO)
        else -> intArrayOf()
    }

    fun getSupportedFunctionValue(id: Int, zone: Int): IntArray = getSupportedFunctionValue(id)

    companion object {
        fun enabled(c: Context): Boolean =
            (c.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
                Prefs(c).sp.getBoolean("demo_mode", false)

        /** Dữ liệu VHAL giả cho widget. */
        fun vehicle() = VehicleData(
            speedKmh = 0f, gear = VehicleData.GEAR_PARK, fuelPercent = 62f, fuelLow = false,
            odometerKm = 12480f, parkingBrake = true, nightMode = null, outsideTempC = 31f, rangeKm = 430f, ignition = 4, rpm = 750f, coolantTempC = 88f, fuelLiters = 31f, batteryVoltage = 14.1f,
        )
    }
}
