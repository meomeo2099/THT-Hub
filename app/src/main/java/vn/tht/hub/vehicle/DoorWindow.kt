package vn.tht.hub.vehicle

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import vn.tht.hub.Prefs

/**
 * Mở cửa → kính cửa đó tự hạ [Prefs.doorDropPercent] %; đóng cửa → kính kéo lên lại (chỉ khi chính app đã hạ).
 *
 * Coolray 2025 chưa có mã trạng thái cửa đã xác nhận, nên dùng bước HỌC: đọc một tập ứng viên
 * (`BCM_FUNC_DOOR_POS` / `BCM_FUNC_DOOR_STATUS` / `BCM_FUNC_DOOR` × các vùng cửa ECARX/VHAL), người dùng đóng hết cửa,
 * rồi mở từng cửa; ứng viên đổi giá trị đúng lúc đó được ghi lại cho cửa ấy.
 * Chỉ chạy khi xe đứng yên (< 5 km/h). Cửa i ↔ kính i: 0 lái, 1 phụ, 2 sau trái, 3 sau phải.
 */
class DoorWindow(private val c: Context, private val ecarx: EcarxCarSource, private val ctl: EcarxController,
                 private val speed: () -> Float?) {

    data class Probe(val id: Int, val zone: Int) {
        val key get() = "$id:$zone"
        val label get() = if (zone == MCU_ZONE) "MCU ${EcarxFunctions.hex(id)}" else "${NAMES[id] ?: EcarxFunctions.hex(id)} vùng $zone"
    }

    /** Cửa đã học: ứng viên + giá trị khi ĐÓNG (khác giá trị này = đang mở). */
    data class Learned(val probe: Probe, val closedValue: Int)

    private val prefs = Prefs(c)
    private val main = Handler(Looper.getMainLooper())
    private var running = false
    private val lastOpen = arrayOfNulls<Boolean>(4)
    /** Kính do app hạ, chờ đóng cửa để kéo lên. */
    private val dropped = BooleanArray(4)

    var learned: Array<Learned?> = load(); private set

    /** Đọc toàn bộ ứng viên (luồng nền). Khoá = Probe.key. */
    fun snapshot(): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        for (id in IDS) for (z in ZONES) {
            val v = runCatching { ecarx.getInt(id, z) }.getOrNull() ?: continue
            out[Probe(id, z).key] = v
        }
        // Thuộc tính MCU thân xe 0x28700000..0x287002FF (nơi có kính, ắc quy, xăng…): cửa thường cũng nằm ở đây.
        ecarx.awaitCarReady()
        for (prop in MCU_FROM..MCU_TO) {
            val v = runCatching { ecarx.getIntByProp(prop, 0) }.getOrNull() ?: continue
            out[Probe(prop, MCU_ZONE).key] = v
        }
        return out
    }

    /** Đọc một ứng viên đã học (hàm ECARX theo vùng, hoặc thuộc tính MCU). */
    fun read(p: Probe): Int? = if (p.zone == MCU_ZONE) ecarx.getIntByProp(p.id, 0) else ecarx.getInt(p.id, p.zone)

    /** So [closed] (đóng hết) với [now] (vừa mở cửa [door]); chọn ứng viên đổi giá trị, ưu tiên ứng viên chưa thuộc cửa khác. */
    fun learn(door: Int, closed: Map<String, Int>, now: Map<String, Int>): Learned? {
        val taken = learned.filterNotNull().map { it.probe.key }.toSet()
        val changed = now.filter { (k, v) -> closed[k] != null && closed[k] != v }.keys
        val pick = changed.firstOrNull { it !in taken } ?: return null
        val (id, z) = pick.split(':').map { it.toInt() }
        return Learned(Probe(id, z), closed.getValue(pick)).also {
            learned[door] = it; save()
            Log.i(TAG, "Học cửa $door → ${it.probe.label}, đóng=${it.closedValue}")
        }
    }

    fun forget(door: Int) { learned[door] = null; save() }

    fun start() { if (!running) { running = true; main.post(tick) } }
    fun stop() { running = false; main.removeCallbacks(tick) }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (prefs.doorDropEnabled && learned.any { it != null } && ecarx.connected) {
                ctl.background({ learned.map { l -> l?.let { read(it.probe) } } }) { values ->
                    values?.forEachIndexed { i, v -> onDoor(i, v?.let { it != learned[i]?.closedValue }) }
                }
            }
            main.postDelayed(this, POLL_MS)
        }
    }

    private fun onDoor(i: Int, open: Boolean?) {
        if (open == null) return
        val was = lastOpen[i]
        lastOpen[i] = open
        if (was == null || was == open) return
        val sp = speed()
        if (open) {
            if (sp != null && sp >= 5f) return
            if (!prefs.doorDropFrontOnly || i < 2) {
                dropped[i] = true
                ctl.setWindowPercent(listOf(i), prefs.doorDropPercent) { Log.i(TAG, "Mở cửa $i: ${it.message}") }
            }
        } else if (dropped[i]) {
            dropped[i] = false
            // Chờ cửa đóng hẳn rồi mới kéo kính lên
            main.postDelayed({ ctl.setWindowPercent(listOf(i), 0) { Log.i(TAG, "Đóng cửa $i: ${it.message}") } }, CLOSE_DELAY_MS)
        }
    }

    private fun load(): Array<Learned?> {
        val arr = runCatching { JSONArray(prefs.sp.getString(KEY, "[]")) }.getOrDefault(JSONArray())
        return Array(4) { i ->
            arr.optJSONObject(i)?.takeIf { it.has("id") }?.let {
                Learned(Probe(it.getInt("id"), it.getInt("zone")), it.getInt("closed"))
            }
        }
    }

    private fun save() {
        val arr = JSONArray()
        learned.forEach { l ->
            arr.put(if (l == null) JSONObject() else JSONObject().put("id", l.probe.id).put("zone", l.probe.zone).put("closed", l.closedValue))
        }
        prefs.sp.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val TAG = "THTHubDoor"
        private const val KEY = "door_learned"
        private const val POLL_MS = 500L
        private const val CLOSE_DELAY_MS = 800L
        const val BCM_FUNC_DOOR = 553779456
        const val BCM_FUNC_DOOR_POS = 553779968
        const val BCM_FUNC_DOOR_STATUS = 553785856
        val IDS = intArrayOf(BCM_FUNC_DOOR_POS, BCM_FUNC_DOOR_STATUS, BCM_FUNC_DOOR)
        private val NAMES = mapOf(BCM_FUNC_DOOR_POS to "DOOR_POS", BCM_FUNC_DOOR_STATUS to "DOOR_STATUS", BCM_FUNC_DOOR to "DOOR")
        /** Vùng cửa thường gặp: VHAL (1,4,16,64), ECARX cửa/kính (1,2,4,8,16,32,256,512) và toàn xe. */
        val ZONES = intArrayOf(1, 2, 4, 8, 16, 32, 64, 128, 256, 512, EcarxFunctions.ZONE_ALL)
        const val MCU_FROM = 0x28700000
        const val MCU_TO = 0x287002FF
        /** Vùng giả đánh dấu ứng viên là thuộc tính MCU. */
        const val MCU_ZONE = -2
        val DOOR_NAMES = listOf("Cửa lái", "Cửa phụ", "Cửa sau trái", "Cửa sau phải")
    }
}
