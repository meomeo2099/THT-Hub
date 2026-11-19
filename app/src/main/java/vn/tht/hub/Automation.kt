package vn.tht.hub

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.media.AudioManager
import vn.tht.hub.vehicle.EcarxFunctions as F
import vn.tht.hub.vehicle.VehicleData

/**
 * Kịch bản khi nổ máy (chỉ chạy một lần cho mỗi lần máy chuyển tắt → nổ) và đóng kính theo tốc độ.
 * Chỉ dùng chức năng Coolray 2025 có thật: điều hoà AUTO, nhiệt độ, chế độ lái, kính.
 * Không có sưởi ghế/vô lăng trên xe này nên không có mục đó.
 */
class Automation(private val hub: HubService, private val prefs: Prefs) {

    private val main = Handler(Looper.getMainLooper())
    private var lastEngineOn: Boolean? = null
    private var windowsClosedAt = 0L

    fun onVehicle(d: VehicleData?) {
        val on = d?.engineOn ?: return
        val was = lastEngineOn
        lastEngineOn = on
        if (on && was == false || on && was == null && SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) {
            if (prefs.autoEnabled) main.postDelayed({ runStartScenario() }, prefs.autoDelaySec * 1000L)
        }
        closeWindowsBySpeed(d)
    }

    /** Giờ hiện tại có nằm trong khung giờ cài đặt không (-1 = cả ngày; 22 → 6 là qua nửa đêm). */
    private fun inWindow(): Boolean {
        val from = prefs.autoFromHour; val to = prefs.autoToHour
        if (from < 0 || to < 0 || from == to) return true
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return if (from < to) h in from until to else h >= from || h < to
    }

    /** Kết quả chọn kịch bản điều hoà theo nhiệt độ ngoài trời, để hiện trong app. */
    fun climateRule(outside: Float?): String = when {
        outside == null -> "Chưa đọc được nhiệt độ ngoài trời: dùng cài đặt chung"
        prefs.hotOn && outside >= prefs.hotAbove -> "Trời nóng (${outside.toInt()} °C): A/C, ${prefs.hotTemp} °C" + if (prefs.hotRecirc) ", gió trong" else ""
        prefs.coldOn && outside <= prefs.coldBelow -> "Trời lạnh (${outside.toInt()} °C): ${prefs.coldTemp} °C" + if (prefs.coldDefrost) ", sấy kính trước" else ""
        else -> "Ngoài trời ${outside.toInt()} °C: dùng cài đặt chung"
    }

    /** [force] = bấm "Chạy thử ngay": bỏ qua khung giờ. */
    fun runStartScenario(force: Boolean = false) {
        if (!force && !inWindow()) { log("Ngoài khung giờ, bỏ qua kịch bản nổ máy"); return }
        Log.i(TAG, "Chạy kịch bản nổ máy")
        val c = hub.control
        val outside = hub.vehicle?.outsideTempC
        log(climateRule(outside))
        if (prefs.autoClimateAuto) c.setValue(F.HVAC_AUTO, F.ZONE_ALL, F.ON, "Điều hoà AUTO") { log(it.message) }
        when {
            outside != null && prefs.hotOn && outside >= prefs.hotAbove -> {
                c.setValue(F.HVAC_AC, F.ZONE_ALL, F.ON, "A/C bật") { log(it.message) }
                c.setTemp(prefs.hotTemp) { log(it.message) }
                if (prefs.hotRecirc) c.setValue(F.HVAC_CIRCULATION, F.ZONE_ALL, F.level(F.HVAC_CIRCULATION, 1), "Gió trong") { log(it.message) }
            }
            outside != null && prefs.coldOn && outside <= prefs.coldBelow -> {
                c.setTemp(prefs.coldTemp) { log(it.message) }
                if (prefs.coldDefrost) c.setValue(F.HVAC_DEFROST_FRONT, F.ZONE_ALL, F.ON, "Sấy kính trước") { log(it.message) }
            }
            prefs.autoTemp in 16f..32f -> c.setTemp(prefs.autoTemp) { log(it.message) }
        }
        if (prefs.autoDriveMode in 1..3) c.setDriveMode(prefs.autoDriveMode) { log(it.message) }
        if (prefs.autoLaunchPkg.isNotBlank()) {
            hub.packageManager.getLaunchIntentForPackage(prefs.autoLaunchPkg)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?.let { runCatching { hub.startActivity(it) } }
        }
        if (prefs.autoPlayMusic) main.postDelayed({ mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY) }, 3000)
    }

    private fun closeWindowsBySpeed(d: VehicleData) {
        val limit = prefs.autoCloseWindowsKmh
        val speed = d.speedKmh ?: return
        if (limit <= 0 || speed < limit) return
        val now = SystemClock.elapsedRealtime()
        if (now - windowsClosedAt < 60_000) return   // tối đa 1 lần/phút
        windowsClosedAt = now
        hub.control.setWindowPercent(listOf(0, 1, 2, 3), 0) { log("Tự đóng kính (${speed.toInt()} km/h): ${it.message}") }
    }

    private fun mediaKey(code: Int) {
        val am = hub.getSystemService(AudioManager::class.java) ?: return
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun log(m: String) = Log.i(TAG, m)

    companion object {
        private const val TAG = "THTHub"
        /** Máy đã nổ sẵn khi đầu xe vừa khởi động (thường gặp): coi như vừa nổ máy nếu trong 3 phút đầu. */
        private const val BOOT_WINDOW_MS = 180_000L
    }
}
