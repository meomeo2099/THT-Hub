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

    fun runStartScenario() {
        Log.i(TAG, "Chạy kịch bản nổ máy")
        val c = hub.control
        if (prefs.autoClimateAuto) c.setValue(F.HVAC_AUTO, F.ZONE_ALL, F.ON, "Điều hoà AUTO") { log(it.message) }
        if (prefs.autoTemp in 16f..32f) c.setTemp(prefs.autoTemp) { log(it.message) }
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
        hub.control.setWindows(false) { log("Tự đóng kính (${speed.toInt()} km/h): ${it.message}") }
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
