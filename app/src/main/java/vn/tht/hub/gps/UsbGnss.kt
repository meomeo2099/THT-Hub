package vn.tht.hub.gps

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException

/**
 * Đọc bộ thu GPS USB qua thư viện usb-serial-for-android (MIT, cùng thư viện Car GNSS Bridge dùng):
 * CDC-ACM (u-blox 7/8/9/10 — VK-162, VK-172, BN-xxx…), Prolific PL2303 (BU-353), SiLabs CP210x, FTDI, CH340.
 *
 * - u-blox/CDC: tốc độ baud không ảnh hưởng (USB thật), dùng 115200.
 * - Chip USB-serial: dò lần lượt 9600, 4800, 38400, 115200, 57600, 19200 cho tới khi nhận câu NMEA đúng checksum;
 *   nhớ tốc độ theo VID:PID cho lần sau.
 * - Thiết bị USB-serial không phải GPS (cáp OBD, Arduino…) không ra NMEA ở tốc độ nào → bỏ qua tới khi rút ra.
 * Tự cấp quyền USB bằng chữ ký platform (MANAGE_USB) nên không hiện hộp thoại.
 */
class UsbGnss(
    private val context: Context,
    /** Đếm số câu NMEA hợp lệ đã nhận (để biết đúng là GPS và đúng tốc độ baud). */
    private val validCount: () -> Long,
    private val onBytes: (ByteArray, Int) -> Unit,
    /** Gọi trên luồng đọc khi đã xác định xong cổng (u-blox = true). */
    private val onReady: (isUblox: Boolean) -> Unit = {},
) {
    @Volatile var status: String = "Chưa bắt đầu"; private set
    @Volatile var deviceName: String? = null; private set
    @Volatile var baud: Int? = null; private set
    @Volatile var lastDataAt = 0L; private set

    private val usb = context.getSystemService(UsbManager::class.java)
    private val sp = context.getSharedPreferences("tht_gps", Context.MODE_PRIVATE)
    private val worker = HandlerThread("tht-gps-usb").apply { start() }
    private val handler = Handler(worker.looper)

    @Volatile private var running = false
    @Volatile private var openKey: String? = null
    private var port: UsbSerialPort? = null
    private var reader: Thread? = null
    /** Thiết bị đã thử mà không ra NMEA (theo deviceName); xoá khi rút ra. */
    private val rejected = HashSet<String>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> handler.postDelayed({ ensureOpen() }, 700)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val name = deviceOf(i)?.deviceName
                    handler.post {
                        if (name != null) rejected.remove(name)
                        if (name != null && name == openKey) close("Đã rút bộ thu GPS")
                    }
                }
                ACTION_PERMISSION -> handler.post { ensureOpen() }
            }
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            if (openKey != null && baud != null && SystemClock.elapsedRealtime() - lastDataAt > SILENT_REOPEN_MS) {
                close("Bộ thu im quá ${SILENT_REOPEN_MS / 1000} giây, mở lại")
            }
            ensureOpen()
            handler.postDelayed(this, 3000)
        }
    }

    fun start() {
        if (running) return
        running = true
        val f = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_PERMISSION)
        }
        context.registerReceiver(receiver, f, null, handler)
        handler.post(watchdog)
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { context.unregisterReceiver(receiver) }
        handler.removeCallbacksAndMessages(null)
        handler.post { close("Đã tắt") }
        worker.quitSafely()
    }

    // ---- Chạy trên luồng worker ----

    private fun ensureOpen() {
        if (!running || openKey != null) return
        val drivers = runCatching { UsbSerialProber.getDefaultProber().findAllDrivers(usb) }.getOrDefault(emptyList())
            .filter { it.device.deviceName !in rejected }
            .sortedBy { priority(it.device) }
        val drv = drivers.firstOrNull()
        if (drv == null) {
            status = if (rejected.isEmpty()) "Chưa thấy bộ thu GPS USB" else "Thiết bị USB đang cắm không phải GPS"
            return
        }
        val dev = drv.device
        if (!usb.hasPermission(dev)) {
            grantSelf(dev)
            if (!usb.hasPermission(dev)) {
                status = "Đang xin quyền USB cho ${label(dev)}"
                val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_PERMISSION).setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
                runCatching { usb.requestPermission(dev, pi) }
                return
            }
        }
        open(drv)
    }

    private fun open(drv: UsbSerialDriver) {
        val dev = drv.device
        val conn = usb.openDevice(dev) ?: run { status = "Không mở được ${label(dev)}"; return }
        val p = drv.ports.firstOrNull() ?: run { conn.close(); status = "${label(dev)} không có cổng"; return }
        try {
            p.open(conn)
        } catch (e: IOException) {
            runCatching { conn.close() }
            status = "Không mở được cổng ${label(dev)} (app khác đang dùng?)"
            Log.w(TAG, status, e)
            return
        }
        port = p
        openKey = dev.deviceName
        deviceName = label(dev)
        baud = null
        lastDataAt = SystemClock.elapsedRealtime()
        val cdc = drv is CdcAcmSerialDriver
        val t = Thread({ run(p, dev, cdc) }, "tht-gps-read")
        reader = t
        t.start()
    }

    /** Luồng đọc: dò baud (nếu cần) rồi đọc liên tục. */
    private fun run(p: UsbSerialPort, dev: UsbDevice, cdc: Boolean) {
        val key = "baud_%04x_%04x".format(dev.vendorId, dev.productId)
        val saved = sp.getInt(key, 0)
        val candidates = (if (cdc) listOf(115200) else listOf(saved) + BAUDS).filter { it > 0 }.distinct()
        val buf = ByteArray(4096)
        try {
            var found: Int? = null
            for (b in candidates) {
                if (!alive(dev)) return
                runCatching { p.setParameters(b, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE) }
                runCatching { p.dtr = true; p.rts = true }
                status = "Đang dò ${label(dev)} ở $b baud"
                val before = validCount()
                val until = SystemClock.elapsedRealtime() + PROBE_MS
                while (alive(dev) && SystemClock.elapsedRealtime() < until && validCount() - before < 2) {
                    val n = p.read(buf, 500)
                    if (n > 0) { lastDataAt = SystemClock.elapsedRealtime(); onBytes(buf, n) }
                }
                if (validCount() - before >= 2) { found = b; break }
            }
            if (found == null) {
                if (cdc && dev.vendorId == VID_UBLOX) {
                    // u-blox nhưng chưa thấy NMEA (có thể đang chỉ xuất UBX): vẫn giữ và đọc tiếp
                    found = 115200
                } else {
                    handler.post { if (openKey == dev.deviceName) { rejected += dev.deviceName; close("${label(dev)} không gửi NMEA — không phải GPS, bỏ qua") } }
                    return
                }
            }
            baud = found
            if (!cdc) sp.edit().putInt(key, found).apply()
            status = "Đang đọc ${label(dev)}" + if (cdc) "" else " · $found baud"
            Log.i(TAG, status)
            try { onReady(dev.vendorId == VID_UBLOX) } catch (t: Throwable) { Log.w(TAG, "onReady lỗi", t) }
            while (alive(dev)) {
                val n = p.read(buf, 1000)
                if (n > 0) {
                    lastDataAt = SystemClock.elapsedRealtime()
                    try { onBytes(buf, n) } catch (t: Throwable) { Log.w(TAG, "Lỗi xử lý dữ liệu GPS", t) }
                }
            }
        } catch (e: IOException) {
            // Thư viện ném IOException khi mất kết nối (rút cáp, lỗi USB)
            handler.post { if (openKey == dev.deviceName) close("Mất kết nối ${label(dev)}: ${e.message}") }
        } catch (t: Throwable) {
            Log.w(TAG, "Luồng đọc GPS lỗi", t)
            handler.post { if (openKey == dev.deviceName) close("Lỗi đọc ${label(dev)}: ${t.message}") }
        }
    }

    private fun alive(dev: UsbDevice) = running && openKey == dev.deviceName

    /** Ghi xuống bộ thu (lệnh UBX). An toàn từ luồng bất kỳ; lỗi thì trả false. */
    fun write(bytes: ByteArray): Boolean {
        val p = port ?: return false
        return try { p.write(bytes, 1000); true } catch (t: Throwable) { Log.w(TAG, "Ghi xuống bộ thu lỗi: ${t.message}"); false }
    }

    private fun close(why: String) {
        val p = port
        val t = reader
        openKey = null
        port = null
        reader = null
        if (t != null && t !== Thread.currentThread()) runCatching { t.join(1500) }
        runCatching { p?.close() }
        deviceName = null
        baud = null
        status = why
        Log.i(TAG, why)
    }

    /** u-blox trước, rồi thiết bị tên có GPS/GNSS, rồi chip USB-serial khác. */
    private fun priority(d: UsbDevice): Int {
        if (d.vendorId == VID_UBLOX) return 0
        val name = "${d.productName ?: ""} ${d.manufacturerName ?: ""}"
        if (Regex("(?i)gps|gnss|u-blox|ublox|sirf|mediatek|quectel").containsMatchIn(name)) return 1
        return 2
    }

    /** UsbManager.grantPermission(UsbDevice) — API ẩn, cần MANAGE_USB (chữ ký platform). */
    private fun grantSelf(dev: UsbDevice) {
        runCatching {
            UsbManager::class.java.getMethod("grantPermission", UsbDevice::class.java).invoke(usb, dev)
        }.onFailure { Log.w(TAG, "Không tự cấp được quyền USB: ${it.cause?.message ?: it.message}") }
    }

    @Suppress("DEPRECATION")
    private fun deviceOf(i: Intent): UsbDevice? = i.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    private fun label(d: UsbDevice) = (d.productName?.trim()?.takeIf { it.isNotEmpty() }
        ?: "USB %04x:%04x".format(d.vendorId, d.productId))

    companion object {
        private const val TAG = "THTGps"
        private const val ACTION_PERMISSION = "vn.tht.hub.USB_GPS_PERMISSION"
        private const val VID_UBLOX = 0x1546
        private const val SILENT_REOPEN_MS = 10_000L
        /** GPS thường xuất 1 câu/giây trở lên; 2,5 giây đủ thấy ≥2 câu đúng checksum. */
        private const val PROBE_MS = 2500L
        private val BAUDS = listOf(9600, 4800, 38400, 115200, 57600, 19200)
    }
}
