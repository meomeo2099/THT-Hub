package vn.tht.hub.gps

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Giao thức nhị phân UBX của u-blox (dùng để "mồi" khởi động: vị trí, giờ, quỹ đạo vệ tinh).
 * Khung: B5 62 | class | id | length (LE 2 byte) | payload | CK_A CK_B (Fletcher-8 trên class..payload).
 * Tham chiếu: u-blox 7 Receiver Description (protocol 14), các lệnh AID-*. u-blox 8 (FW ≤ 3) vẫn nhận AID-*.
 */
object Ubx {
    const val CLS_AID = 0x0B
    const val ID_AID_INI = 0x01
    const val ID_AID_HUI = 0x02
    const val ID_AID_ALM = 0x30
    const val ID_AID_EPH = 0x31

    /** Đóng khung UBX kèm checksum. */
    fun frame(cls: Int, id: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArray(8 + payload.size)
        out[0] = 0xB5.toByte(); out[1] = 0x62
        out[2] = cls.toByte(); out[3] = id.toByte()
        out[4] = (payload.size and 0xFF).toByte(); out[5] = (payload.size shr 8 and 0xFF).toByte()
        System.arraycopy(payload, 0, out, 6, payload.size)
        var a = 0; var b = 0
        for (i in 2 until 6 + payload.size) { a = (a + (out[i].toInt() and 0xFF)) and 0xFF; b = (b + a) and 0xFF }
        out[6 + payload.size] = a.toByte(); out[7 + payload.size] = b.toByte()
        return out
    }

    /** Lệnh hỏi (payload rỗng): bộ thu trả lời bằng chính loại tin đó. */
    fun poll(cls: Int, id: Int) = frame(cls, id)

    private const val GPS_EPOCH_MS = 315_964_800_000L   // 1980-01-06 00:00:00 UTC
    private const val WEEK_MS = 604_800_000L
    /** GPS đi trước UTC 18 giây (từ 01/2017, chưa có giây nhuận mới). */
    const val LEAP_SECONDS = 18

    /** UTC (ms) → (tuần GPS, mili giây trong tuần). */
    fun gpsWeekTow(utcMs: Long): Pair<Int, Long> {
        val gps = utcMs - GPS_EPOCH_MS + LEAP_SECONDS * 1000L
        return (gps / WEEK_MS).toInt() to gps % WEEK_MS
    }

    /**
     * AID-INI (48 byte): vị trí gần đúng (độ ×1e7, độ cao cm) + giờ GPS.
     * [posAccM] sai số vị trí, [timeAccMs] sai số giờ. [utcMs] null = không gửi giờ.
     */
    fun aidIni(lat: Double, lon: Double, altM: Double, posAccM: Int, utcMs: Long?, timeAccMs: Int): ByteArray {
        val p = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
        p.putInt(Math.round(lat * 1e7).toInt())
        p.putInt(Math.round(lon * 1e7).toInt())
        p.putInt(Math.round(altM * 100).toInt())
        p.putInt(posAccM.coerceIn(1, 1_000_000) * 100)
        p.putShort(0)                                  // tmCfg
        var flags = FLAG_POS or FLAG_LLA
        if (utcMs != null) {
            val (wn, tow) = gpsWeekTow(utcMs)
            p.putShort(wn.toShort())
            p.putInt(tow.toInt())
            p.putInt(0)                                // towNs
            p.putInt(timeAccMs)                        // tAccMs
            p.putInt(0)                                // tAccNs
            flags = flags or FLAG_TIME
        } else {
            p.putShort(0); p.putInt(0); p.putInt(0); p.putInt(0); p.putInt(0)
        }
        p.putInt(0)                                    // clkDOrFreq
        p.putInt(0)                                    // clkDAcc
        p.putInt(flags)
        return frame(CLS_AID, ID_AID_INI, p.array())
    }

    const val CLS_CFG = 0x06
    const val ID_CFG_RATE = 0x08
    const val ID_CFG_NAV5 = 0x24

    /** CFG-RATE: chu kỳ đo [measRateMs] (200 = 5 lần/giây), theo giờ GPS. Không lưu vào bộ thu (mất khi rút điện). */
    fun cfgRate(measRateMs: Int): ByteArray {
        val p = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
        p.putShort(measRateMs.toShort()); p.putShort(1); p.putShort(1)
        return frame(CLS_CFG, ID_CFG_RATE, p.array())
    }

    /** CFG-NAV5: chỉ đổi mô hình động lực (mask bit 0) sang ô tô (4) để vị trí/hướng ổn định khi lái. */
    fun cfgNav5Automotive(): ByteArray {
        val p = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN)
        p.putShort(0x0001)      // mask: chỉ dynModel
        p.put(4)                // dynModel = automotive
        return frame(CLS_CFG, ID_CFG_NAV5, p.array())
    }

    const val ID_CFG_MSG = 0x01

    /** CFG-MSG (3 byte, cổng hiện tại): câu NMEA chuẩn class F0, [nmeaId] (00 GGA, 01 GLL, 02 GSA, 03 GSV, 04 RMC, 05 VTG) mỗi [everyN] lần đo; 0 = tắt. */
    fun cfgNmeaRate(nmeaId: Int, everyN: Int): ByteArray =
        frame(CLS_CFG, ID_CFG_MSG, byteArrayOf(0xF0.toByte(), nmeaId.toByte(), everyN.toByte()))

    /**
     * Bộ câu NMEA dùng ở 1 lần/giây: RMC, GGA, GSA, GSV mỗi lần đo; tắt GLL/VTG không dùng cho nhẹ.
     * (Đã thử 5 lần/giây trên xe 20/11/2026: u-blox 7 qua USB tràn bộ đệm, bỏ gần hết GSV, báo TXT liên tục.)
     */
    fun nmeaFor1Hz(): List<ByteArray> = listOf(
        cfgNmeaRate(0x01, 0), cfgNmeaRate(0x05, 0),
        cfgNmeaRate(0x00, 1), cfgNmeaRate(0x04, 1),
        cfgNmeaRate(0x02, 1), cfgNmeaRate(0x03, 1),
    )

    const val FLAG_POS = 0x01
    const val FLAG_TIME = 0x02
    const val FLAG_LLA = 0x20

    /** Tin AID-EPH/ALM có dữ liệu thật (8 byte = chỉ có số hiệu vệ tinh, không có quỹ đạo). */
    fun hasData(cls: Int, id: Int, payloadLen: Int): Boolean = cls == CLS_AID && when (id) {
        ID_AID_EPH -> payloadLen >= 104
        ID_AID_ALM -> payloadLen >= 40
        ID_AID_HUI -> payloadLen >= 72
        else -> false
    }
}

/** Tách khung UBX từ luồng byte (lẫn với NMEA); bỏ khung sai checksum. */
class UbxParser(private val onFrame: (cls: Int, id: Int, payload: ByteArray, raw: ByteArray) -> Unit) {
    private var state = 0
    private var cls = 0; private var id = 0; private var len = 0
    private var payload = ByteArray(0); private var pos = 0
    private var ckA = 0; private var ckB = 0

    private fun ck(b: Int) { ckA = (ckA + b) and 0xFF; ckB = (ckB + ckA) and 0xFF }

    fun feed(buf: ByteArray, n: Int) {
        for (i in 0 until n) step(buf[i].toInt() and 0xFF)
    }

    private fun step(b: Int) {
        when (state) {
            0 -> if (b == 0xB5) state = 1
            1 -> state = if (b == 0x62) 2 else if (b == 0xB5) 1 else 0
            2 -> { ckA = 0; ckB = 0; cls = b; ck(b); state = 3 }
            3 -> { id = b; ck(b); state = 4 }
            4 -> { len = b; ck(b); state = 5 }
            5 -> {
                len = len or (b shl 8); ck(b)
                if (len > 1024) { state = 0; return }
                payload = ByteArray(len); pos = 0
                state = if (len == 0) 7 else 6
            }
            6 -> { payload[pos++] = b.toByte(); ck(b); if (pos == len) state = 7 }
            7 -> state = if (b == ckA) 8 else 0
            8 -> {
                state = 0
                if (b == ckB) onFrame(cls, id, payload, Ubx.frame(cls, id, payload))
            }
        }
    }
}
