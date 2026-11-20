package vn.tht.hub.gps

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar

/**
 * "Mồi" khởi động cho u-blox (VK-162/VK-172 không có pin nuôi nên mỗi lần bật là khởi động nguội):
 *  - lưu vị trí cuối + dữ liệu vệ tinh lấy từ chính bộ thu (AID-HUI, AID-ALM niên lịch, AID-EPH quỹ đạo);
 *  - khi mở bộ thu: gửi lại vị trí cuối, giờ máy, rồi HUI/ALM/EPH còn hạn.
 * Toàn bộ chạy trên xe, không cần mạng. Bộ thu không hiểu AID-* (u-blox 9/10) thì chỉ bỏ qua.
 */
class GpsAiding(context: Context) {
    private val sp = context.getSharedPreferences("tht_gps", Context.MODE_PRIVATE)
    private val file = File(context.filesDir, "gps_aid.bin")

    /** Khoá "id:svid" → khung UBX đầy đủ. */
    private val frames = LinkedHashMap<String, ByteArray>()
    private var framesSavedAt = 0L
    private var dirty = false
    private var lastPosSave = 0L
    private var lastPoll = 0L
    private var firstFixAt = 0L

    @Volatile var lastSummary: String = ""; private set

    init { load() }

    // ---- Khi mở bộ thu ----

    /** Tạo chuỗi lệnh mồi; [nowMs] giờ máy UTC. Trả về danh sách khung UBX theo thứ tự gửi. */
    @Synchronized fun startupFrames(nowMs: Long): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val lat = sp.getFloat("last_lat", Float.NaN)
        val lon = sp.getFloat("last_lon", Float.NaN)
        val posAt = sp.getLong("last_pos_at", 0L)
        val clockOk = yearOf(nowMs) >= 2025
        val parts = ArrayList<String>()
        if (!lat.isNaN() && !lon.isNaN()) {
            val ageH = (nowMs - posAt) / 3_600_000.0
            // Xe chỉ di chuyển khi nổ máy: vị trí lúc tắt máy thường vẫn đúng; để rộng nếu đã lâu
            val accM = if (ageH in 0.0..24.0) 300 else 50_000
            out += Ubx.aidIni(lat.toDouble(), lon.toDouble(), sp.getFloat("last_alt", 0f).toDouble(), accM,
                if (clockOk) nowMs else null, 10_000)
            parts += "vị trí cuối" + if (clockOk) " + giờ" else ""
        }
        val age = nowMs - framesSavedAt
        var hui = 0; var alm = 0; var eph = 0
        for ((k, f) in frames) {
            when {
                k.startsWith("${Ubx.ID_AID_HUI}:") && age < ALM_MAX_AGE_MS -> { out += f; hui++ }
                k.startsWith("${Ubx.ID_AID_ALM}:") && age < ALM_MAX_AGE_MS -> { out += f; alm++ }
            }
        }
        if (age in 0 until EPH_MAX_AGE_MS) for ((k, f) in frames) if (k.startsWith("${Ubx.ID_AID_EPH}:")) { out += f; eph++ }
        if (alm > 0) parts += "niên lịch $alm vệ tinh"
        if (eph > 0) parts += "quỹ đạo $eph vệ tinh"
        lastSummary = if (parts.isEmpty()) "Chưa có dữ liệu mồi (lần đầu)" else "Đã mồi: " + parts.joinToString(", ")
        Log.i(TAG, lastSummary)
        return out
    }

    // ---- Trong lúc chạy ----

    /** Tin UBX từ bộ thu: giữ lại HUI/ALM/EPH có dữ liệu. */
    @Synchronized fun onUbx(cls: Int, id: Int, payload: ByteArray, raw: ByteArray) {
        if (!Ubx.hasData(cls, id, payload.size)) return
        val sv = if (id == Ubx.ID_AID_HUI) 0 else ByteBuffer.wrap(payload, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        frames["$id:$sv"] = raw
        dirty = true
    }

    /**
     * Có vị trí: lưu vị trí cuối (mỗi 30 giây), và định kỳ hỏi bộ thu dữ liệu vệ tinh để lưu.
     * Trả về lệnh cần gửi xuống bộ thu (có thể rỗng).
     */
    @Synchronized fun onFix(fix: GnssFix, elapsedMs: Long, nowMs: Long): List<ByteArray> {
        if (firstFixAt == 0L) firstFixAt = elapsedMs
        if (elapsedMs - lastPosSave > 30_000) {
            lastPosSave = elapsedMs
            sp.edit().putFloat("last_lat", fix.lat.toFloat()).putFloat("last_lon", fix.lon.toFloat())
                .putFloat("last_alt", (fix.altitudeM ?: 0.0).toFloat()).putLong("last_pos_at", nowMs).apply()
        }
        if (dirty && elapsedMs - lastPoll > 20_000) save(nowMs)
        // Hỏi lần đầu sau 90 giây có vị trí (đủ thời gian tải quỹ đạo), sau đó 15 phút một lần
        val due = if (lastPoll == 0L) elapsedMs - firstFixAt > 90_000 else elapsedMs - lastPoll > 15 * 60_000
        if (!due) return emptyList()
        lastPoll = elapsedMs
        return listOf(Ubx.poll(Ubx.CLS_AID, Ubx.ID_AID_HUI), Ubx.poll(Ubx.CLS_AID, Ubx.ID_AID_ALM), Ubx.poll(Ubx.CLS_AID, Ubx.ID_AID_EPH))
    }

    // ---- Lưu file ----

    private fun save(nowMs: Long) {
        try {
            val bo = ByteArrayOutputStream()
            bo.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(nowMs).array())
            frames.values.forEach { bo.write(it) }
            val tmp = File(file.path + ".tmp")
            tmp.writeBytes(bo.toByteArray())
            tmp.renameTo(file)
            framesSavedAt = nowMs
            dirty = false
            Log.i(TAG, "Lưu dữ liệu mồi: ${frames.size} khung")
        } catch (t: Throwable) {
            Log.w(TAG, "Không lưu được dữ liệu mồi", t)
        }
    }

    private fun load() {
        runCatching {
            if (!file.exists()) return
            val b = file.readBytes()
            if (b.size < 8) return
            framesSavedAt = ByteBuffer.wrap(b, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
            UbxParser { cls, id, payload, raw -> onUbx(cls, id, payload, raw) }.feed(b.copyOfRange(8, b.size), b.size - 8)
            dirty = false
        }.onFailure { Log.w(TAG, "Không đọc được dữ liệu mồi", it) }
    }

    private fun yearOf(ms: Long) = Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.YEAR)

    companion object {
        private const val TAG = "THTGps"
        /** Quỹ đạo (ephemeris) GPS dùng tốt khoảng 4 giờ; để 3 giờ cho chắc. */
        const val EPH_MAX_AGE_MS = 3 * 3_600_000L
        /** Niên lịch dùng được vài tuần. */
        const val ALM_MAX_AGE_MS = 30 * 24 * 3_600_000L
    }
}
