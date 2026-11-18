package vn.tht.hub

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.os.Process
import android.provider.Settings
import android.util.Log
import vn.tht.hub.media.MediaListenerService

/**
 * Tự cấp quyền cho chính app nhờ chữ ký platform (WRITE_SECURE_SETTINGS, MANAGE_APP_OPS_MODES):
 * vẽ lớp nổi và đọc thông báo (để lấy MediaSession). Không có chữ ký platform thì lặng lẽ bỏ qua,
 * người dùng bật tay trong Cài đặt.
 */
object Permissions {
    private const val TAG = "THTHub"

    fun grantSelf(c: Context) {
        grantOverlay(c)
        enableNotificationListener(c)
    }

    fun canOverlay(c: Context) = Settings.canDrawOverlays(c)

    private fun grantOverlay(c: Context) {
        if (Settings.canDrawOverlays(c)) return
        runCatching {
            val ops = c.getSystemService(AppOpsManager::class.java)
            val op = AppOpsManager::class.java.getField("OP_SYSTEM_ALERT_WINDOW").getInt(null)
            AppOpsManager::class.java
                .getMethod("setMode", Int::class.java, Int::class.java, String::class.java, Int::class.java)
                .invoke(ops, op, Process.myUid(), c.packageName, AppOpsManager.MODE_ALLOWED)
        }.onFailure { Log.w(TAG, "Không tự cấp được quyền lớp nổi: ${it.message}") }
    }

    fun notificationListenerEnabled(c: Context): Boolean {
        val flat = Settings.Secure.getString(c.contentResolver, KEY_LISTENERS) ?: return false
        val me = ComponentName(c, MediaListenerService::class.java).flattenToString()
        return flat.split(':').any { it == me }
    }

    private fun enableNotificationListener(c: Context) {
        if (notificationListenerEnabled(c)) return
        runCatching {
            val me = ComponentName(c, MediaListenerService::class.java).flattenToString()
            val cur = Settings.Secure.getString(c.contentResolver, KEY_LISTENERS).orEmpty()
            val next = if (cur.isBlank()) me else "$cur:$me"
            Settings.Secure.putString(c.contentResolver, KEY_LISTENERS, next)
        }.onFailure { Log.w(TAG, "Không tự bật được đọc thông báo: ${it.message}") }
    }

    private const val KEY_LISTENERS = "enabled_notification_listeners"
}
