package vn.tht.hub.gps

import android.app.AppOpsManager
import android.content.Context
import android.location.Location
import android.os.IBinder
import android.os.Process
import android.util.Log
import java.lang.reflect.Method

/**
 * Đưa vị trí vào nhà cung cấp "gps" của Android 9 như một bộ thu thật, KHÔNG gắn cờ giả lập.
 *
 * Dùng ILocationManager.reportLocation (API ẩn, cần INSTALL_LOCATION_PROVIDER — chữ ký platform).
 * Vietmap Live bỏ qua vị trí có cờ mock (đã thử A/B trên xe 20/11/2026): mũi tên chỉ bám đường
 * khi vị trí đến theo đường này.
 *
 * Lưu ý Android 9: nếu "gps" đang là test provider (app giả lập như Car GNSS Bridge đăng ký) thì
 * hệ thống vẫn gắn cờ mock cho mọi vị trí "gps" → phải gỡ test provider đó trước ([removeGpsTestProvider]).
 */
object SystemLocation {
    private const val TAG = "THTGps"

    @Volatile private var lm: Any? = null
    @Volatile private var report: Method? = null

    private fun service(): Any {
        lm?.let { return it }
        val b = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, "location") as IBinder
        val s = Class.forName("android.location.ILocationManager\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, b)!!
        lm = s
        return s
    }

    private fun method(name: String, argc: Int): Method =
        service().javaClass.methods.first { it.name == name && it.parameterTypes.size == argc }

    /** Gửi một vị trí; trả về lỗi (null = thành công). */
    fun report(loc: Location): String? = try {
        val m = report ?: method("reportLocation", 2).also { report = it }
        m.invoke(service(), loc, false)
        null
    } catch (t: Throwable) {
        val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
        lm = null; report = null
        "${cause.javaClass.simpleName}: ${cause.message}"
    }

    /**
     * Gỡ test provider "gps" nếu có (để vị trí của ta không bị gắn cờ mock).
     * Cần quyền giả lập vị trí cho chính app trong lúc gỡ; cấp tạm qua AppOps rồi trả lại.
     * Trả về true nếu đã gỡ được, false nếu không có gì để gỡ hoặc lỗi.
     */
    fun removeGpsTestProvider(c: Context): Boolean {
        val ops = c.getSystemService(AppOpsManager::class.java)
        val op = runCatching { AppOpsManager::class.java.getField("OP_MOCK_LOCATION").getInt(null) }.getOrNull()
        val setMode = runCatching {
            AppOpsManager::class.java.getMethod("setMode", Int::class.java, Int::class.java, String::class.java, Int::class.java)
        }.getOrNull()
        if (op != null && setMode != null) runCatching { setMode.invoke(ops, op, Process.myUid(), c.packageName, AppOpsManager.MODE_ALLOWED) }
        return try {
            method("removeTestProvider", 2).invoke(service(), "gps", c.packageName)
            Log.i(TAG, "Đã gỡ test provider gps của app giả lập khác")
            true
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            // IllegalArgumentException "Provider gps unknown" = không có test provider: bình thường
            if (cause !is IllegalArgumentException) Log.w(TAG, "Không gỡ được test provider gps: $cause")
            false
        } finally {
            if (op != null && setMode != null) runCatching { setMode.invoke(ops, op, Process.myUid(), c.packageName, AppOpsManager.MODE_DEFAULT) }
        }
    }
}
