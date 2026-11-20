package vn.tht.hub.system

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import vn.tht.hub.HubService
import vn.tht.hub.Prefs

/**
 * Chế độ đi bảo dưỡng (kiểu "К дилеру" của GUHub): ẩn các app cài ngoài để thợ không thấy, dữ liệu vẫn giữ.
 *
 * - Ẩn bằng `PackageManager.setApplicationHiddenSettingAsUser` (quyền MANAGE_USERS có nhờ chữ ký platform):
 *   app biến khỏi danh sách và không chạy được, nhưng không bị gỡ. Không ẩn được thì tắt app
 *   (`COMPONENT_ENABLED_STATE_DISABLED_USER`, quyền CHANGE_COMPONENT_ENABLED_STATE).
 * - THT Hub không tự ẩn (phải còn chạy để mở lại) nhưng giấu biểu tượng và tắt lớp nổi.
 * - Mở lại: hết giờ hẹn (AlarmManager + kiểm tra lúc khởi động), bấm 5 lần góc trên phải rồi nhập PIN,
 *   hoặc mã `*#*#4848#*#*` trên bàn phím gọi (nếu app Điện thoại của xe chuyển mã bí mật).
 */
object ServiceMode {
    private const val TAG = "THTHubService"
    private const val KEY_ACTIVE = "svc_active"
    private const val KEY_HIDDEN = "svc_hidden"      // gói đã ẩn, cách nhau bởi dấu phẩy
    private const val KEY_DISABLED = "svc_disabled"  // gói phải tắt vì không ẩn được
    private const val KEY_UNTIL = "svc_until"        // System.currentTimeMillis(); 0 = không tự mở
    const val ACTION_EXPIRE = "vn.tht.hub.SERVICE_MODE_EXPIRE"

    private fun sp(c: Context) = Prefs(c).sp

    fun active(c: Context) = sp(c).getBoolean(KEY_ACTIVE, false)
    fun until(c: Context) = sp(c).getLong(KEY_UNTIL, 0L)

    /** App cài ngoài (không phải app hệ thống), trừ chính THT Hub. Gồm cả app THT khác. */
    fun candidates(c: Context): List<Pair<String, String>> {
        val pm = c.packageManager
        return pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0 }
            .filter { it.packageName != c.packageName }
            .map { it.packageName to pm.getApplicationLabel(it).toString() }
            .sortedBy { it.second.lowercase() }
    }

    /** Bật chế độ: ẩn [packages], hẹn tự mở sau [hours] giờ (0 = không tự mở). Trả về số app đã ẩn. */
    fun enter(c: Context, packages: List<String>, hours: Int): Int {
        val hidden = mutableListOf<String>()
        val disabled = mutableListOf<String>()
        for (pkg in packages) {
            when {
                setHidden(c, pkg, true) -> hidden += pkg
                setEnabled(c, pkg, false) -> disabled += pkg
                else -> Log.w(TAG, "Không ẩn được $pkg")
            }
        }
        // Không ẩn được app nào (thiếu quyền hệ thống): không bật chế độ, tránh chỉ giấu mất THT Hub
        if (packages.isNotEmpty() && hidden.isEmpty() && disabled.isEmpty()) {
            Log.w(TAG, "Không ẩn được app nào – không bật chế độ bảo dưỡng")
            return -1
        }
        val until = if (hours > 0) System.currentTimeMillis() + hours * 3_600_000L else 0L
        sp(c).edit().putBoolean(KEY_ACTIVE, true)
            .putString(KEY_HIDDEN, hidden.joinToString(","))
            .putString(KEY_DISABLED, disabled.joinToString(","))
            .putLong(KEY_UNTIL, until).apply()
        setLauncherIcon(c, false)
        if (until > 0) schedule(c, until)
        HubService.start(c, HubService.ACTION_RESTYLE)   // tắt lớp nổi, bật vùng mở khoá
        Log.i(TAG, "Bật chế độ bảo dưỡng: ẩn ${hidden.size}, tắt ${disabled.size}, tự mở lúc $until")
        return hidden.size + disabled.size
    }

    /** Tắt chế độ: hiện lại đúng các app đã ẩn/tắt. */
    fun exit(c: Context) {
        val s = sp(c)
        s.getString(KEY_HIDDEN, "").orEmpty().split(',').filter { it.isNotBlank() }.forEach { setHidden(c, it, false) }
        s.getString(KEY_DISABLED, "").orEmpty().split(',').filter { it.isNotBlank() }.forEach { setEnabled(c, it, true) }
        s.edit().putBoolean(KEY_ACTIVE, false).remove(KEY_HIDDEN).remove(KEY_DISABLED).putLong(KEY_UNTIL, 0L).apply()
        setLauncherIcon(c, true)
        cancel(c)
        HubService.start(c, HubService.ACTION_RESTYLE)
        Log.i(TAG, "Tắt chế độ bảo dưỡng, đã hiện lại app")
    }

    /** Gọi lúc khởi động / mỗi lần dịch vụ chạy: quá giờ hẹn thì mở lại. */
    fun checkExpired(c: Context) {
        val u = until(c)
        if (active(c) && u > 0 && System.currentTimeMillis() >= u) exit(c) else if (active(c) && u > 0) schedule(c, u)
    }

    private fun setHidden(c: Context, pkg: String, hide: Boolean): Boolean = runCatching {
        val pm = c.packageManager
        val user = Process.myUserHandle()
        val m = pm.javaClass.getMethod("setApplicationHiddenSettingAsUser",
            String::class.java, Boolean::class.javaPrimitiveType, android.os.UserHandle::class.java)
        val ok = m.invoke(pm, pkg, hide, user) as? Boolean ?: false
        // Hiện lại: true nghĩa là đã đổi; nếu app vốn không ẩn thì cũng coi như xong
        ok || !hide
    }.onFailure { Log.w(TAG, "setApplicationHidden $pkg lỗi: ${it.cause ?: it}") }.getOrDefault(false)

    private fun setEnabled(c: Context, pkg: String, on: Boolean): Boolean = runCatching {
        c.packageManager.setApplicationEnabledSetting(pkg,
            if (on) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0)
        true
    }.onFailure { Log.w(TAG, "setApplicationEnabled $pkg lỗi: ${it.cause ?: it}") }.getOrDefault(false)

    /** Giấu/hiện biểu tượng THT Hub trong danh sách app (app vẫn chạy nền). */
    private fun setLauncherIcon(c: Context, show: Boolean) = runCatching {
        c.packageManager.setComponentEnabledSetting(ComponentName(c, "vn.tht.hub.MainActivity"),
            if (show) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP)
    }

    private fun pending(c: Context) = PendingIntent.getBroadcast(c, 77,
        Intent(c, ExpireReceiver::class.java).setAction(ACTION_EXPIRE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun schedule(c: Context, at: Long) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val delay = (at - System.currentTimeMillis()).coerceAtLeast(1000L)
        am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delay, pending(c))
    }

    private fun cancel(c: Context) { c.getSystemService(AlarmManager::class.java)?.cancel(pending(c)) }

    /** Hết giờ hẹn → mở lại. */
    class ExpireReceiver : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (active(c)) exit(c) }
    }

    /** Mã bí mật trên bàn phím gọi: *#*#4848#*#* → mở lại (nếu app Điện thoại của xe hỗ trợ). */
    class SecretCodeReceiver : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            Log.i(TAG, "Nhận mã bí mật ${i.data}")
            if (active(c)) exit(c)
            else c.startActivity(Intent().setComponent(ComponentName(c, "vn.tht.hub.MainActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
