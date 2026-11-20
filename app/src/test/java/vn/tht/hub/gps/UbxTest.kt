package vn.tht.hub.gps

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UbxTest {

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }

    /** Lệnh hỏi MON-VER chuẩn trong tài liệu u-blox: B5 62 0A 04 00 00 0E 34. */
    @Test fun checksumMonVer() {
        assertEquals("B5 62 0A 04 00 00 0E 34", hex(Ubx.poll(0x0A, 0x04)))
    }

    @Test fun pollAid() {
        assertEquals("B5 62 0B 31 00 00 3C BF", hex(Ubx.poll(Ubx.CLS_AID, Ubx.ID_AID_EPH)))
    }

    @Test fun gioGps() {
        // 20/11/2026 03:45:12 UTC → tuần 2445, TOW 445530 s (đã cộng 18 giây nhuận)
        val (wn, tow) = Ubx.gpsWeekTow(1795146312000L)
        assertEquals(2445, wn)
        assertEquals(445_530_000L, tow)
    }

    @Test fun aidIniViTriNhaVaGio() {
        val f = Ubx.aidIni(14.366094, 108.963095, 21.3, 300, 1795146312000L, 10_000)
        assertEquals(8 + 48, f.size)
        assertEquals(0x0B, f[2].toInt()); assertEquals(0x01, f[3].toInt())
        assertEquals(48, f[4].toInt())
        val p = ByteBuffer.wrap(f, 6, 48).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(143660940, p.getInt(6))
        assertEquals(1089630950, p.getInt(10))
        assertEquals(2130, p.getInt(14))               // cm
        assertEquals(30000, p.getInt(18))              // 300 m → cm
        assertEquals(2445, p.getShort(24).toInt())
        assertEquals(445_530_000, p.getInt(26))
        assertEquals(10_000, p.getInt(34))
        assertEquals(Ubx.FLAG_POS or Ubx.FLAG_TIME or Ubx.FLAG_LLA, p.getInt(50))
        // Checksum đúng: parser nhận lại được đúng khung
        var got: ByteArray? = null
        UbxParser { _, _, _, raw -> got = raw }.feed(f, f.size)
        assertArrayEquals(f, got)
    }

    @Test fun aidIniKhongGio() {
        val f = Ubx.aidIni(14.0, 108.0, 0.0, 50_000, null, 0)
        // wrap(f, 6, 48) vẫn đánh chỉ số tuyệt đối từ đầu mảng: cờ ở 6 + 44
        val flags = ByteBuffer.wrap(f, 6, 48).order(ByteOrder.LITTLE_ENDIAN).getInt(50)
        assertEquals(Ubx.FLAG_POS or Ubx.FLAG_LLA, flags)
    }

    @Test fun tachUbxLanNmea() {
        val eph = ByteArray(104).also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 16); it[50] = 7 }
        val ephFrame = Ubx.frame(Ubx.CLS_AID, Ubx.ID_AID_EPH, eph)
        val empty = Ubx.frame(Ubx.CLS_AID, Ubx.ID_AID_EPH, ByteArray(8).also { it[0] = 5 })
        val bad = Ubx.frame(Ubx.CLS_AID, Ubx.ID_AID_EPH, eph).also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val stream = "\$GPRMC,1*00\r\n".toByteArray() + ephFrame + bad + "\$GPGGA\r\n".toByteArray() + empty
        val got = mutableListOf<Pair<Int, Int>>()
        val p = UbxParser { cls, id, payload, _ -> got += id to payload.size }
        // Cắt gói 64 byte như USB
        var i = 0
        while (i < stream.size) { val n = minOf(64, stream.size - i); p.feed(stream.copyOfRange(i, i + n), n); i += n }
        assertEquals(listOf(Ubx.ID_AID_EPH to 104, Ubx.ID_AID_EPH to 8), got)
        assertTrue(Ubx.hasData(Ubx.CLS_AID, Ubx.ID_AID_EPH, 104))
        assertFalse(Ubx.hasData(Ubx.CLS_AID, Ubx.ID_AID_EPH, 8))
    }
}
