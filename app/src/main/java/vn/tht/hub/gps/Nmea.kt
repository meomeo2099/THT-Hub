package vn.tht.hub.gps

import java.util.Calendar
import java.util.TimeZone

/** Một điểm GPS đã gộp RMC (vị trí, tốc độ, hướng, giờ) với GGA gần nhất (vệ tinh, HDOP, độ cao). */
data class GnssFix(
    val timeMs: Long?,
    val lat: Double,
    val lon: Double,
    val speedMps: Float?,
    val courseDeg: Float?,
    val altitudeM: Double?,
    val satellites: Int?,
    val hdop: Float?,
)

/**
 * Đọc câu NMEA 0183 từ bộ thu (u-blox 7 trên Coolray gửi GPRMC, GPVTG, GPGGA, GPGSA, GPGSV, GPGLL).
 * Chỉ nhận câu có checksum đúng; dữ liệu nhị phân UBX lẫn vào bị bỏ qua.
 */
object Nmea {

    data class Rmc(val timeMs: Long?, val hhmmss: String, val lat: Double, val lon: Double, val speedMps: Float?, val courseDeg: Float?)
    data class Gga(val hhmmss: String, val quality: Int, val satellites: Int?, val hdop: Float?, val altitudeM: Double?)

    private const val KNOT_MPS = 0.514444f

    /** Kiểm tra dạng `$....*HH` và checksum XOR. */
    fun checksumOk(line: String): Boolean {
        if (line.length < 9 || line[0] != '$') return false
        val star = line.lastIndexOf('*')
        if (star < 0 || star + 3 != line.length) return false
        var sum = 0
        for (i in 1 until star) sum = sum xor line[i].code
        val want = line.substring(star + 1).toIntOrNull(16) ?: return false
        return sum == want
    }

    /** Tách trường sau khi đã kiểm checksum; trả về null nếu câu hỏng. */
    fun fields(line: String): List<String>? {
        if (!checksumOk(line)) return null
        return line.substring(1, line.lastIndexOf('*')).split(',')
    }

    /** Loại câu không kể talker: "GPRMC"/"GNRMC" → "RMC". */
    fun type(f: List<String>): String = f[0].let { if (it.length >= 5) it.substring(it.length - 3) else it }

    fun parseRmc(f: List<String>): Rmc? {
        if (f.size < 10 || type(f) != "RMC") return null
        if (f[2] != "A") return null                    // V = chưa có vị trí
        if (f.size > 12 && f[12] == "N") return null     // NMEA 2.3: chế độ N = không hợp lệ
        val lat = coord(f[3], f[4], 2) ?: return null
        val lon = coord(f[5], f[6], 3) ?: return null
        val speed = f[7].toFloatOrNull()?.let { it * KNOT_MPS }
        val course = f[8].toFloatOrNull()?.let { ((it % 360f) + 360f) % 360f }
        return Rmc(utcMillis(f[9], f[1]), f[1].take(6), lat, lon, speed, course)
    }

    fun parseGga(f: List<String>): Gga? {
        if (f.size < 10 || type(f) != "GGA") return null
        val q = f[6].toIntOrNull() ?: 0
        return Gga(f[1].take(6), q, f[7].toIntOrNull(), f[8].toFloatOrNull(), f[9].toDoubleOrNull())
    }

    /** "ddmm.mmmm" (vĩ độ, [degDigits]=2) hoặc "dddmm.mmmm" (kinh độ, 3) + bán cầu → độ thập phân. */
    fun coord(v: String, hemi: String, degDigits: Int): Double? {
        if (v.length < degDigits + 2) return null
        val deg = v.substring(0, degDigits).toIntOrNull() ?: return null
        val min = v.substring(degDigits).toDoubleOrNull() ?: return null
        if (min >= 60.0) return null
        var d = deg + min / 60.0
        when (hemi) {
            "N", "E" -> {}
            "S", "W" -> d = -d
            else -> return null
        }
        val limit = if (degDigits == 2) 90.0 else 180.0
        return if (d > limit || d < -limit) null else d
    }

    /** Ngày "ddmmyy" + giờ "hhmmss.ss" (UTC) → epoch ms; null nếu thiếu. */
    fun utcMillis(date: String, time: String): Long? {
        if (date.length != 6 || time.length < 6) return null
        val dd = date.substring(0, 2).toIntOrNull() ?: return null
        val mo = date.substring(2, 4).toIntOrNull() ?: return null
        val yy = date.substring(4, 6).toIntOrNull() ?: return null
        val hh = time.substring(0, 2).toIntOrNull() ?: return null
        val mi = time.substring(2, 4).toIntOrNull() ?: return null
        val ss = time.substring(4, 6).toIntOrNull() ?: return null
        val frac = if (time.length > 7 && time[6] == '.') ("0" + time.substring(6)).toDoubleOrNull() ?: 0.0 else 0.0
        if (mo !in 1..12 || dd !in 1..31 || hh > 23 || mi > 59 || ss > 60) return null
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.clear()
        c.set(2000 + yy, mo - 1, dd, hh, mi, ss)
        return c.timeInMillis + (frac * 1000).toLong()
    }
}

/** Một vệ tinh trong câu GSV. [elevation]/[azimuth] độ; [snr] dB-Hz (null = không nghe thấy). */
data class Satellite(val system: Char, val prn: Int, val elevation: Int?, val azimuth: Int?, val snr: Int?, val used: Boolean)

/**
 * Bầu trời từ GSV (vị trí, cường độ) + GSA (vệ tinh đang dùng). Mỗi hệ (GP/GL/GA/GB) gửi trọn một chuỗi GSV
 * mỗi giây; chỉ công bố khi nhận đủ câu cuối của chuỗi.
 */
class SkyTracker {
    private val pending = HashMap<Char, MutableList<Satellite>>()
    private val complete = HashMap<Char, List<Satellite>>()
    private val complAt = HashMap<Char, Long>()
    private val used = HashSet<String>()
    private var usedAt = 0L

    /** Hệ vệ tinh theo talker: P GPS, L GLONASS, A Galileo, B BeiDou, Q QZSS; N (nhiều hệ) đoán theo số PRN. */
    private fun system(talker: String, prn: Int): Char = when (talker) {
        "GP" -> if (prn in 193..202) 'Q' else 'P'
        "GL" -> 'L'
        "GA" -> 'A'
        "GB", "BD" -> 'B'
        "GQ" -> 'Q'
        else -> when (prn) { in 1..32 -> 'P'; in 65..96 -> 'L'; in 193..202 -> 'Q'; in 201..237 -> 'B'; in 301..336 -> 'A'; else -> 'P' }
    }

    @Synchronized fun feed(f: List<String>, nowMs: Long): Boolean {
        val talker = f[0].take(2)
        return when (Nmea.type(f)) {
            "GSV" -> gsv(f, talker, nowMs)
            "GSA" -> { gsa(f, talker, nowMs); false }
            else -> false
        }
    }

    private fun gsv(f: List<String>, talker: String, nowMs: Long): Boolean {
        val total = f.getOrNull(1)?.toIntOrNull() ?: return false
        val num = f.getOrNull(2)?.toIntOrNull() ?: return false
        val sys0 = system(talker, 1)
        val list = if (num == 1) mutableListOf<Satellite>().also { pending[sys0] = it } else pending[sys0] ?: return false
        // Chỉ nhóm đủ 4 trường; NMEA 4.1 thêm 1 trường signal ID ở cuối, bỏ qua
        val groups = (f.size - 4) / 4
        for (g in 0 until groups) {
            val i = 4 + g * 4
            val prn = f[i].toIntOrNull()
            if (prn != null && prn > 0) {
                val sys = system(talker, prn)
                list += Satellite(sys, prn, f.getOrNull(i + 1)?.toIntOrNull(), f.getOrNull(i + 2)?.toIntOrNull(),
                    f.getOrNull(i + 3)?.toIntOrNull(), false)
            }
        }
        if (num == total) {
            complete[sys0] = list.toList()
            complAt[sys0] = nowMs
            pending.remove(sys0)
            return true
        }
        return false
    }

    private fun gsa(f: List<String>, talker: String, nowMs: Long) {
        // Mỗi epoch u-blox gửi một GSA cho mỗi hệ; gom lại trong 500 ms
        if (nowMs - usedAt > 500) used.clear()
        usedAt = nowMs
        for (i in 3..14) {
            val prn = f.getOrNull(i)?.toIntOrNull() ?: continue
            used += "${system(talker, prn)}$prn"
        }
    }

    /** Danh sách hiện tại; bỏ hệ không cập nhật quá 5 giây. */
    @Synchronized fun snapshot(nowMs: Long): List<Satellite> {
        val freshUsed = nowMs - usedAt < 3000
        return complete.filterKeys { nowMs - (complAt[it] ?: 0) < 5000 }.values.flatten()
            .map { s -> if (freshUsed && "${s.system}${s.prn}" in used) s.copy(used = true) else s }
            .sortedWith(compareBy({ it.system }, { it.prn }))
    }
}

/** Gộp câu theo thứ tự u-blox gửi: phát ra một [GnssFix] mỗi khi có RMC hợp lệ. */
class NmeaAssembler {
    private var lastGga: Nmea.Gga? = null
    val sky = SkyTracker()
    /** Số câu NMEA hợp lệ đã nhận (dùng để nhận biết đúng là bộ thu GPS và dò tốc độ baud). */
    @Volatile var validSentences = 0L; private set

    fun feed(line: String, nowMs: Long = 0L): GnssFix? {
        val f = Nmea.fields(line) ?: return null
        validSentences++
        when (Nmea.type(f)) {
            "GSV", "GSA" -> { sky.feed(f, nowMs); return null }
            "GGA" -> { lastGga = Nmea.parseGga(f); return null }
            "RMC" -> {
                val r = Nmea.parseRmc(f) ?: return null
                // GGA đến sau RMC trong cùng giây, nên dùng GGA của giây trước (chỉ lấy số vệ tinh/HDOP/độ cao)
                val g = lastGga?.takeIf { it.quality > 0 }
                return GnssFix(r.timeMs, r.lat, r.lon, r.speedMps, r.courseDeg, g?.altitudeM, g?.satellites, g?.hdop)
            }
        }
        return null
    }
}

/** Ghép byte từ cổng USB thành dòng NMEA; bỏ qua byte nhị phân và dòng quá dài. */
class LineSplitter(private val onLine: (String) -> Unit) {
    private val sb = StringBuilder(128)
    private var inLine = false

    fun feed(buf: ByteArray, n: Int) {
        for (i in 0 until n) {
            val c = buf[i].toInt() and 0xFF
            when {
                c == '$'.code -> { sb.setLength(0); sb.append('$'); inLine = true }
                !inLine -> {}
                c == '\r'.code || c == '\n'.code -> { if (sb.length > 6) onLine(sb.toString()); sb.setLength(0); inLine = false }
                c in 0x20..0x7E && sb.length < 120 -> sb.append(c.toChar())
                else -> { sb.setLength(0); inLine = false }
            }
        }
    }
}
