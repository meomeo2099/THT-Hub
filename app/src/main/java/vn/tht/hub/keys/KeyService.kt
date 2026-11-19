package vn.tht.hub.keys

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import vn.tht.hub.HubService
import vn.tht.hub.vehicle.EcarxFunctions as F

/**
 * Bắt nút vô lăng (thiết bị `ecarx-keypad` → KeyEvent Android) bằng dịch vụ trợ năng có lọc phím.
 *
 * - Chế độ học: báo mã phím cho màn "Nút vô lăng", KHÔNG chặn phím.
 * - Nút đã gán: nhấn nhanh → hành động "nhấn"; giữ ≥ [HOLD_MS] → hành động "giữ".
 *   Nếu một trong hai là "Giữ chức năng gốc", phím gốc được bơm lại (INJECT_EVENTS, có nhờ chữ ký platform).
 * - Nút chưa gán: để nguyên cho hệ thống.
 * Không đọc nội dung cửa sổ.
 */
class KeyService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private var slots: Map<Int, KeySlot> = emptyMap()
    private val downAt = HashMap<Int, Long>()
    private val holdFired = HashSet<Int>()
    private val holdTimers = HashMap<Int, Runnable>()
    /** Mã phím đang được bơm lại: bỏ qua để không lặp vô hạn. */
    private val injecting = HashSet<Int>()

    override fun onServiceConnected() {
        instance = this
        reload()
        Log.i(TAG, "Dịch vụ phím vô lăng đã chạy")
    }

    fun reload() {
        slots = KeyMap.load(this).filter { it.learned }.associateBy { it.code }
    }

    override fun onKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        if (code in injecting) {
            if (e.action == KeyEvent.ACTION_UP) injecting -= code
            return false
        }
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) main.post { listener?.invoke(code) }
        if (learning) return false

        val s = slots[code] ?: return false
        if (!s.active) return false

        when (e.action) {
            KeyEvent.ACTION_DOWN -> if (e.repeatCount == 0) {
                downAt[code] = SystemClock.uptimeMillis()
                holdFired -= code
                if (s.hold != KeyAction.PASS) {
                    val r = Runnable { if (downAt.containsKey(code)) { holdFired += code; perform(s.hold, s.holdArg) } }
                    holdTimers[code] = r
                    main.postDelayed(r, HOLD_MS)
                }
            }
            KeyEvent.ACTION_UP -> {
                val start = downAt.remove(code) ?: return true
                holdTimers.remove(code)?.let { main.removeCallbacks(it) }
                if (code in holdFired) return true
                val held = SystemClock.uptimeMillis() - start >= HOLD_MS
                val action = if (held && s.hold != KeyAction.PASS) s.hold else s.press
                val arg = if (action == s.hold) s.holdArg else s.pressArg
                if (action == KeyAction.PASS) reinject(code) else perform(action, arg)
            }
        }
        return true
    }

    /** Trả phím gốc lại cho hệ thống (ví dụ nút chỉ gán "giữ" nhưng người dùng nhấn nhanh). */
    private fun reinject(code: Int) {
        injecting += code
        val ok = runCatching {
            val im = Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)
            val inject = im.javaClass.getMethod("injectInputEvent", android.view.InputEvent::class.java, Int::class.javaPrimitiveType)
            val t = SystemClock.uptimeMillis()
            inject.invoke(im, KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0), 0)
            inject.invoke(im, KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0), 0)
        }.isSuccess
        if (!ok) { injecting -= code; Log.w(TAG, "Không bơm lại được phím $code") }
    }

    private fun perform(a: KeyAction, arg: String) {
        Log.i(TAG, "Nút vô lăng → ${a.name}")
        val hub = HubService.instance
        val ctl = hub?.control
        val done: (vn.tht.hub.vehicle.EcarxController.Result) -> Unit = { Log.i(TAG, it.message) }
        when (a) {
            KeyAction.PASS -> Unit
            KeyAction.PANEL -> HubService.start(this, HubService.ACTION_TOGGLE_PANEL)
            KeyAction.PLAY_PAUSE -> hub?.media?.playPause()
            KeyAction.NEXT -> hub?.media?.next()
            KeyAction.PREV -> hub?.media?.previous()
            KeyAction.MUTE -> getSystemService(AudioManager::class.java)
                ?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
            KeyAction.OPEN_APP -> packageManager.getLaunchIntentForPackage(arg)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { startActivity(it) } }
            KeyAction.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            // Camera 360 của Coolray: app Neusoft `com.neusoft.nsapa`. Xe có thể tự tắt hình khi chạy nhanh (do ECU, không phải app).
            KeyAction.CAMERA_360 -> (packageManager.getLaunchIntentForPackage(CAMERA_PKG)
                ?: Intent().setPackage(CAMERA_PKG).setAction(Intent.ACTION_MAIN))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).let { i ->
                    runCatching { startActivity(i) }.onFailure { Log.w(TAG, "Không mở được camera 360: ${it.message}") }
                }
            KeyAction.DRIVE_MODE -> {
                val cur = hub?.info?.driveMode ?: 0
                val next = F.DRIVE_MODES.map { it.first }.let { m -> m.getOrElse((m.indexOf(cur) + 1) % m.size) { m.first() } }
                ctl?.setDriveMode(next, done)
            }
            KeyAction.HVAC_POWER -> ctl?.toggleHvacPower(done)
            KeyAction.HVAC_AUTO -> ctl?.setValue(F.HVAC_AUTO, F.ZONE_ALL, F.ON, "Điều hoà AUTO", done = done)
            KeyAction.AC -> ctl?.toggleAc(done)
            KeyAction.TEMP_UP -> ctl?.tempStep(+1, done)
            KeyAction.TEMP_DOWN -> ctl?.tempStep(-1, done)
            KeyAction.FAN_UP -> ctl?.fanStep(+1, done)
            KeyAction.FAN_DOWN -> ctl?.fanStep(-1, done)
            KeyAction.RECIRC -> {
                val next = if (hub?.info?.recirc == 1) 2 else 1
                ctl?.setValue(F.HVAC_CIRCULATION, F.ZONE_ALL, F.level(F.HVAC_CIRCULATION, next), "Lấy gió", done = done)
            }
            KeyAction.DEFROST_FRONT -> ctl?.toggleFn(F.HVAC_DEFROST_FRONT, F.ZONE_ALL, "Sấy kính trước", done = done)
            KeyAction.WINDOWS_CLOSE -> ctl?.setWindowPercent(listOf(0, 1, 2, 3), 0, done)
            KeyAction.WINDOWS_CRACK -> ctl?.setWindowPercent(listOf(0, 1, 2, 3), 20, done)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "THTHubKeys"
        const val HOLD_MS = 600L
        const val CAMERA_PKG = "com.neusoft.nsapa"

        @Volatile var instance: KeyService? = null; private set
        /** Màn "Nút vô lăng" đang học mã phím: không chặn phím nào. */
        @Volatile var learning = false
        /** Nhận mã phím vừa bấm (main thread) – để học và để nháy nút trên hình. */
        @Volatile var listener: ((Int) -> Unit)? = null

        fun enabled(c: Context): Boolean {
            val me = ComponentName(c, KeyService::class.java).flattenToString()
            return Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.split(':')?.any { it.equals(me, true) } == true
        }

        /** Tự bật dịch vụ nhờ WRITE_SECURE_SETTINGS (chữ ký platform). */
        fun enable(c: Context): Boolean = runCatching {
            if (enabled(c)) return@runCatching true
            val me = ComponentName(c, KeyService::class.java).flattenToString()
            val cur = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            Settings.Secure.putString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                if (cur.isBlank()) me else "$cur:$me")
            Settings.Secure.putInt(c.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        }.getOrDefault(false)
    }
}
