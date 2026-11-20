package vn.tht.hub.gps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NmeaTest {

    /** Thêm checksum đúng cho thân câu (không có $ và *). */
    private fun s(body: String): String {
        var x = 0
        body.forEach { x = x xor it.code }
        return "$" + body + "*" + "%02X".format(x)
    }

    @Test fun checksumMauChuan() {
        assertTrue(Nmea.checksumOk("\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A"))
        assertTrue(Nmea.checksumOk("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"))
        assertFalse(Nmea.checksumOk("\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6B"))
        assertFalse(Nmea.checksumOk("GPRMC,123519*6A"))
        assertFalse(Nmea.checksumOk("\$GPRMC,123519,A"))
    }

    @Test fun rmcMauChuan() {
        val r = Nmea.parseRmc(Nmea.fields("\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A")!!)!!
        assertEquals(48.1173, r.lat, 1e-4)
        assertEquals(11.516667, r.lon, 1e-5)
        assertEquals(22.4f * 0.514444f, r.speedMps!!, 1e-3f)
        assertEquals(84.4f, r.courseDeg!!, 1e-3f)
    }

    /** Vị trí nhà chủ xe (14.366094, 108.963095), hướng 225° như chuyến chạy 20/11/2026. */
    @Test fun rmcCoolrayVietNam() {
        val line = s("GPRMC,034512.00,A,1421.96564,N,10857.78570,E,10.80,225.30,201126,,,A")
        val r = Nmea.parseRmc(Nmea.fields(line)!!)!!
        assertEquals(14.366094, r.lat, 1e-5)
        assertEquals(108.963095, r.lon, 1e-5)
        assertEquals(10.8f * 0.514444f, r.speedMps!!, 1e-3f)
        assertEquals(225.3f, r.courseDeg!!, 1e-3f)
        // 20/11/2026 03:45:12 UTC
        assertEquals(1795146312000L, r.timeMs)
    }

    @Test fun rmcChuaCoViTri() {
        assertNull(Nmea.parseRmc(Nmea.fields(s("GPRMC,034512.00,V,,,,,,,201126,,,N"))!!))
        // Trạng thái A nhưng chế độ N vẫn bỏ
        assertNull(Nmea.parseRmc(Nmea.fields(s("GPRMC,034512.00,A,1421.96564,N,10857.78570,E,0.0,,201126,,,N"))!!))
    }

    @Test fun rmcDungYenKhongCoHuong() {
        val r = Nmea.parseRmc(Nmea.fields(s("GPRMC,034512.00,A,1421.96564,N,10857.78570,E,0.012,,201126,,,A"))!!)!!
        assertNull(r.courseDeg)
        assertEquals(0.012f * 0.514444f, r.speedMps!!, 1e-4f)
    }

    @Test fun banCauNamTay() {
        assertEquals(-33.5, Nmea.coord("3330.000", "S", 2)!!, 1e-9)
        assertEquals(-70.25, Nmea.coord("07015.000", "W", 3)!!, 1e-9)
        assertNull(Nmea.coord("3360.000", "N", 2))
        assertNull(Nmea.coord("1421.9", "X", 2))
        assertNull(Nmea.coord("", "N", 2))
    }

    @Test fun ghepRmcVoiGgaTruoc() {
        val a = NmeaAssembler()
        assertNull(a.feed(s("GPGGA,034511.00,1421.96564,N,10857.78570,E,1,09,0.84,23.1,M,,M,,")))
        val fix = a.feed(s("GPRMC,034512.00,A,1421.96564,N,10857.78570,E,10.80,225.30,201126,,,A"))
        assertNotNull(fix)
        assertEquals(9, fix!!.satellites)
        assertEquals(0.84f, fix.hdop!!, 1e-4f)
        assertEquals(23.1, fix.altitudeM!!, 1e-6)
    }

    @Test fun ggaKhongCoFixThiKhongDung() {
        val a = NmeaAssembler()
        a.feed(s("GPGGA,034511.00,,,,,0,00,99.99,,,,,,"))
        val fix = a.feed(s("GPRMC,034512.00,A,1421.96564,N,10857.78570,E,10.80,225.30,201126,,,A"))!!
        assertNull(fix.satellites)
        assertNull(fix.hdop)
    }

    @Test fun boQuaCauKhacVaCauHong() {
        val a = NmeaAssembler()
        assertNull(a.feed(s("GPVTG,225.30,T,,M,10.80,N,20.00,K,A")))
        assertNull(a.feed(s("GPGSV,3,1,12,01,40,083,46,02,17,308,41,12,07,344,39,14,22,228,45")))
        assertNull(a.feed("\$GPRMC,034512.00,A,1421.96564,N,10857.78570,E,10.80,225.30,201126,,,A*00"))
        assertNull(a.feed("rác"))
    }

    @Test fun tachDongLanNhiPhanUbx() {
        val lines = mutableListOf<String>()
        val sp = LineSplitter { lines += it }
        val rmc = s("GPRMC,034512.00,A,1421.96564,N,10857.78570,E,10.80,225.30,201126,,,A")
        val gga = s("GPGGA,034512.00,1421.96564,N,10857.78570,E,1,09,0.84,23.1,M,,M,,")
        val ubx = byteArrayOf(0xB5.toByte(), 0x62, 0x01, 0x07, 0x5C, 0x00, 0x24, 0x0A, 0x00, 0xFF.toByte())
        val all = ubx + (rmc + "\r\n").toByteArray() + ubx + (gga + "\r\n").toByteArray()
        // Cắt thành gói 64 byte như USB full-speed
        var i = 0
        while (i < all.size) {
            val n = minOf(64, all.size - i)
            sp.feed(all.copyOfRange(i, i + n), n)
            i += n
        }
        assertEquals(listOf(rmc, gga), lines)
    }

    @Test fun dongBiCatGiuaChungKhongDinhDong() {
        val lines = mutableListOf<String>()
        val sp = LineSplitter { lines += it }
        val half = "\$GPRMC,0345".toByteArray()
        sp.feed(half, half.size)
        val rmc = s("GPRMC,034512.00,A,1421.96564,N,10857.78570,E,10.80,225.30,201126,,,A")
        val b = (rmc + "\n").toByteArray()
        sp.feed(b, b.size)
        assertEquals(listOf(rmc), lines)
    }

    @Test fun utc() {
        assertEquals(1795146312250L, Nmea.utcMillis("201126", "034512.25"))
        assertNull(Nmea.utcMillis("", "034512"))
        assertNull(Nmea.utcMillis("201326", "034512"))
    }

    @Test fun gsvVaGsaChoRadar() {
        val a = NmeaAssembler()
        // 2 câu GSV GPS (5 vệ tinh), GSA dùng 3 vệ tinh
        a.feed(s("GPGSV,2,1,05,02,45,083,46,05,17,308,41,12,07,344,,14,22,228,35"), 1000)
        assertEquals(0, a.sky.snapshot(1000).size) // chưa đủ chuỗi
        a.feed(s("GPGSV,2,2,05,25,80,010,30"), 1000)
        a.feed(s("GPGSA,A,3,02,05,25,,,,,,,,,,1.6,0.9,1.3"), 1000)
        val sky = a.sky.snapshot(1100)
        assertEquals(listOf(2, 5, 12, 14, 25), sky.map { it.prn })
        assertEquals(setOf(2, 5, 25), sky.filter { it.used }.map { it.prn }.toSet())
        val s12 = sky.first { it.prn == 12 }
        assertEquals(7, s12.elevation); assertEquals(344, s12.azimuth); assertNull(s12.snr)
        // Quá 5 giây không cập nhật thì bỏ
        assertEquals(0, a.sky.snapshot(7000).size)
    }

    @Test fun gsvNmea41CoSignalIdKhongTaoVeTinhAo() {
        val a = NmeaAssembler()
        a.feed(s("GPGSV,1,1,02,02,45,083,46,05,17,308,41,1"), 0)
        assertEquals(listOf(2, 5), a.sky.snapshot(0).map { it.prn })
    }

    @Test fun demCauHopLeDeDoBaud() {
        val a = NmeaAssembler()
        a.feed("rác", 0)
        a.feed(s("GPVTG,225.30,T,,M,10.80,N,20.00,K,A"), 0)
        a.feed(s("GPTXT,01,01,02,u-blox ag - www.u-blox.com"), 0)
        assertEquals(2L, a.validSentences)
    }
}
