package vn.tht.hub.gps

import android.content.Context
import android.location.Location
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import java.util.Locale

/**
 * GPS USB → vị trí "gps" của hệ thống (không cờ giả lập) cho Vietmap, Google Maps và các app khác.
 *
 * Hướng đi lấy từ "course over ground" của câu RMC; khi xe chậm/đứng yên giữ hướng cuối để mũi tên
 * không quay lung tung.
 */
class GpsRelay(private val context: Context) {

    private val assembler = NmeaAssembler()
    private val splitter = LineSplitter { line -> onLine(line) }
    /** Tạo mới mỗi lần bật (luồng USB không khởi động lại được sau khi dừng). */
    @Volatile private var usb: UsbGnss? = null

    @Volatile var running = false; private set
    @Volatile var lastFix: GnssFix? = null; private set
    @Volatile var lastFixAt = 0L; private set
    @Volatile var sent = 0L; private set
    @Volatile var lastError: String? = null; private set
    @Volatile private var lastBearing: Float? = null
    @Volatile private var lastTestProviderCheck = 0L

    fun start() {
        if (running) return
        running = true
        removeTestProviderNow()
        usb = UsbGnss(context, { assembler.validSentences }) { b, n -> splitter.feed(b, n) }.also { it.start() }
        Log.i(TAG, "Bắt đầu chuyển GPS USB")
    }

    fun stop() {
        if (!running) return
        running = false
        usb?.stop()
        usb = null
        Log.i(TAG, "Dừng chuyển GPS USB")
    }

    /** Ảnh chụp trạng thái cho trang GPS. */
    data class Snapshot(
        val on: Boolean,
        val usbStatus: String,
        val device: String?,
        val baud: Int?,
        /** Vị trí còn mới (< 5 giây). */
        val fix: GnssFix?,
        val bearing: Float?,
        val accuracyM: Float?,
        val sky: List<Satellite>,
        val sent: Long,
        val error: String?,
        val bridgeOn: Boolean,
    )

    fun snapshot(): Snapshot {
        val u = usb
        val now = SystemClock.elapsedRealtime()
        val fresh = lastFix?.takeIf { lastFixAt != 0L && now - lastFixAt < 5000 }
        return Snapshot(
            on = running && u != null,
            usbStatus = u?.status ?: "Tắt",
            device = u?.deviceName,
            baud = u?.baud,
            fix = fresh,
            bearing = lastBearing,
            accuracyM = fresh?.let { accuracyFrom(it.hdop) },
            sky = if (u?.deviceName != null) assembler.sky.snapshot(now) else emptyList(),
            sent = sent,
            error = lastError,
            bridgeOn = bridgeEnabled(),
        )
    }

    /** Một dòng ngắn cho trang Chẩn đoán. */
    fun statusText(): String = buildString {
        val u = usb
        if (!running || u == null) { append("Tắt"); return@buildString }
        append(u.status)
        val f = lastFix
        val age = if (lastFixAt == 0L) null else (SystemClock.elapsedRealtime() - lastFixAt) / 1000
        if (f != null && age != null && age < 5) {
            append(String.format(Locale.US, "\nVị trí %.6f, %.6f · %.0f km/h · hướng %s · %s vệ tinh · đã gửi %d",
                f.lat, f.lon, (f.speedMps ?: 0f) * 3.6f,
                lastBearing?.let { "%.0f°".format(Locale.US, it) } ?: "-",
                f.satellites?.toString() ?: "-", sent))
        } else if (u.deviceName != null) {
            append("\nChưa có vị trí (đang bắt vệ tinh)")
        }
        lastError?.let { append("\nLỗi gửi: ").append(it) }
        if (bridgeEnabled()) append("\nCảnh báo: Car GNSS Bridge đang bật, hai app sẽ tranh bộ thu USB")
    }

    private fun bridgeEnabled(): Boolean = runCatching {
        val pm = context.packageManager
        pm.getApplicationInfo(BRIDGE_PKG, 0).enabled &&
            pm.getApplicationEnabledSetting(BRIDGE_PKG) != android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
    }.getOrDefault(false)

    private fun onLine(line: String) {
        if (!running) return
        val now = SystemClock.elapsedRealtime()
        val fix = assembler.feed(line, now) ?: return
        lastFix = fix
        lastFixAt = now

        val speed = fix.speedMps ?: 0f
        val course = fix.courseDeg
        if (course != null && speed >= MIN_SPEED_FOR_COURSE_MPS) lastBearing = course

        val loc = Location("gps").apply {
            latitude = fix.lat
            longitude = fix.lon
            // Hệ thống so thời gian vị trí với đồng hồ máy; dùng giờ máy để Vietmap không coi là vị trí cũ
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            accuracy = accuracyFrom(fix.hdop)
            this.speed = speed
            lastBearing?.let { bearing = it }
            fix.altitudeM?.let { altitude = it }
            extras = Bundle().apply {
                fix.satellites?.let { putInt("satellites", it) }
                putString("source", "tht_usb_gnss")
            }
        }

        val err = SystemLocation.report(loc)
        if (err == null) {
            sent++
            if (lastError != null) { lastError = null; Log.i(TAG, "Gửi vị trí lại được") }
        } else if (err != lastError) {
            lastError = err
            Log.w(TAG, "Không gửi được vị trí: $err")
        }

        // App giả lập khác có thể đăng ký lại test provider "gps" (làm vị trí bị gắn cờ mock): kiểm tra mỗi phút
        if (now - lastTestProviderCheck > 60_000) removeTestProviderNow()
    }

    private fun removeTestProviderNow() {
        lastTestProviderCheck = SystemClock.elapsedRealtime()
        if (SystemLocation.removeGpsTestProvider(context)) {
            lastError = null
        }
    }

    companion object {
        private const val TAG = "THTGps"
        private const val BRIDGE_PKG = "ru.monjaro.cargnssbridge"
        /** Dưới ~3,6 km/h thì hướng từ GPS nhiễu: giữ hướng cũ. */
        private const val MIN_SPEED_FOR_COURSE_MPS = 1.0f

        /** HDOP × 5 m, giới hạn 3–50 m (u-blox 7 ngoài trời HDOP ~0,8–1,5 → 4–8 m). */
        fun accuracyFrom(hdop: Float?): Float = ((hdop ?: 2f) * 5f).coerceIn(3f, 50f)
    }
}
