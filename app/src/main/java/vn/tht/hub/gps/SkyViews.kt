package vn.tht.hub.gps

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import vn.tht.hub.overlay.Palette
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Vòng radar bầu trời (phong cách Flyme của THT Hub): Bắc ở trên, vòng ngoài = chân trời, tâm = thiên đỉnh.
 * Hướng xe và vệ tinh TRƯỢT mượt tới vị trí mới (nội suy theo khung hình), không nhảy từng giây.
 * Chấm đặc = vệ tinh đang dùng, chấm rỗng = thấy nhưng không dùng; màu theo cường độ tín hiệu.
 */
class SkyRadarView(c: Context, private val p: Palette) : View(c) {

    /** Vệ tinh đang vẽ: vị trí/độ mờ hiện tại trượt dần về đích. */
    private class Dot(var x: Float, var y: Float, var tx: Float, var ty: Float, var alpha: Float, var sat: Satellite, var alive: Boolean)

    private val dots = LinkedHashMap<String, Dot>()
    private var targetBearing: Float? = null
    private var shownBearing: Float? = null
    private var lastFrame = 0L

    var satellites: List<Satellite> = emptyList()
        set(v) { field = v; syncDots(); kick() }

    var bearing: Float? = null
        set(v) {
            field = v
            targetBearing = v
            if (shownBearing == null) shownBearing = v
            kick()
        }

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f }
    private val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sweep = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val cardinal = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 22f; typeface = Typeface.DEFAULT_BOLD }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT; textSize = 14f }
    private val prn = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 15f; typeface = Typeface.DEFAULT_BOLD }
    private val arrow = Path()
    private val m = Matrix()
    private var cx = 0f; private var cy = 0f; private var r = 0f

    init { setLayerType(LAYER_TYPE_HARDWARE, null) }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        cx = w / 2f; cy = h / 2f; r = min(w, h) / 2f - 34f
        if (r <= 0) return
        bg.shader = RadialGradient(cx, cy, r, intArrayOf(blend(p.blueSoft, p.card2, 0.55f), p.card2), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        // Quạt hướng: đậm ở mép trước, mờ dần về sau như tia quét radar
        val a = Color.alpha(0x99000000.toInt())
        sweep.shader = SweepGradient(cx, cy,
            intArrayOf(p.blue and 0x00FFFFFF, p.blue and 0x00FFFFFF, (p.blue and 0x00FFFFFF) or (a shl 24), p.blue and 0x00FFFFFF),
            floatArrayOf(0f, 0.86f, 0.999f, 1f))
        dots.values.forEach { d -> place(d.sat)?.let { (x, y) -> d.x = x; d.y = y; d.tx = x; d.ty = y } }
    }

    private fun place(s: Satellite): Pair<Float, Float>? {
        val el = s.elevation ?: return null
        val az = s.azimuth ?: return null
        if (el < 0 || el > 90 || r <= 0) return null
        val d = r * (90 - el) / 90f
        val ang = Math.toRadians(az.toDouble())
        return (cx + d * sin(ang)).toFloat() to (cy - d * cos(ang)).toFloat()
    }

    private fun syncDots() {
        val seen = HashSet<String>()
        for (s in satellites) {
            val pos = place(s) ?: continue
            val key = "${s.system}${s.prn}"
            seen += key
            val d = dots[key]
            if (d == null) dots[key] = Dot(pos.first, pos.second, pos.first, pos.second, 0f, s, true)
            else { d.tx = pos.first; d.ty = pos.second; d.sat = s; d.alive = true }
        }
        dots.forEach { (k, d) -> if (k !in seen) d.alive = false }
    }

    private fun kick() { lastFrame = 0L; postInvalidateOnAnimation() }

    /** Tiến hoạt ảnh theo thời gian thực; trả về true nếu còn cần vẽ tiếp. */
    private fun step(): Boolean {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 16f else (now - lastFrame).coerceIn(1, 100).toFloat()
        lastFrame = now
        val k = 1f - exp(-dt / 260f)            // ~0,26 s: bộ thu báo 5 lần/giây nên chuyển động liền mạch
        var moving = false

        val t = targetBearing
        val s = shownBearing
        if (t != null && s != null) {
            var delta = ((t - s + 540f) % 360f) - 180f   // đi đường ngắn nhất qua 0°/360°
            if (abs(delta) < 0.05f) delta = 0f else moving = true
            shownBearing = (s + delta * k + 360f) % 360f
        }
        val it = dots.entries.iterator()
        while (it.hasNext()) {
            val d = it.next().value
            d.x += (d.tx - d.x) * k; d.y += (d.ty - d.y) * k
            val ta = if (d.alive) 1f else 0f
            d.alpha += (ta - d.alpha) * k
            if (abs(d.tx - d.x) > 0.2f || abs(d.ty - d.y) > 0.2f || abs(ta - d.alpha) > 0.01f) moving = true
            if (!d.alive && d.alpha < 0.02f) it.remove()
        }
        return moving
    }

    override fun onDraw(cv: Canvas) {
        if (r <= 0) return
        val more = step()

        // Nền và vòng
        cv.drawCircle(cx, cy, r, bg)
        ring.color = withAlpha(p.sub, 0.35f)
        for (k in 1..2) cv.drawCircle(cx, cy, r * k / 3f, ring)
        cv.drawLine(cx - r, cy, cx + r, cy, ring)
        cv.drawLine(cx, cy - r, cx, cy + r, ring)
        outer.color = withAlpha(p.blue, 0.55f)
        cv.drawCircle(cx, cy, r, outer)
        // Vạch mỗi 10°, vạch dài mỗi 30°
        for (deg in 0 until 360 step 10) {
            val a = Math.toRadians(deg.toDouble())
            val len = if (deg % 30 == 0) 12f else 6f
            tick.color = withAlpha(p.sub, if (deg % 30 == 0) 0.8f else 0.4f)
            cv.drawLine((cx + r * sin(a)).toFloat(), (cy - r * cos(a)).toFloat(),
                (cx + (r - len) * sin(a)).toFloat(), (cy - (r - len) * cos(a)).toFloat(), tick)
        }
        // Nhãn độ cao
        small.color = withAlpha(p.sub, 0.7f)
        cv.drawText("30°", cx + 4f, cy - r * 2 / 3f - 4f, small)
        cv.drawText("60°", cx + 4f, cy - r / 3f - 4f, small)

        // Quạt hướng xe (xoay mượt)
        val b = shownBearing
        if (b != null) {
            m.setRotate(b - 90f, cx, cy)
            sweep.shader.setLocalMatrix(m)
            cv.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), b - 90f - 50f, 50f, true, sweep)
        }

        // Hướng chính
        val fm = cardinal.fontMetrics
        val mid = -(fm.ascent + fm.descent) / 2
        cardinal.color = p.red; cv.drawText("B", cx, cy - r - 12f, cardinal)
        cardinal.color = p.sub
        cv.drawText("N", cx, cy + r + 12f + 2 * mid, cardinal)
        cv.drawText("Đ", cx + r + 18f, cy + mid, cardinal)
        cv.drawText("T", cx - r - 18f, cy + mid, cardinal)

        // Vệ tinh
        for (d in dots.values) {
            val s = d.sat
            val color = colorOf(s, p)
            val a = d.alpha.coerceIn(0f, 1f)
            if (s.used) {
                fill.color = withAlpha(color, 0.22f * a); cv.drawCircle(d.x, d.y, 21f, fill)   // quầng sáng
                fill.color = withAlpha(color, a); cv.drawCircle(d.x, d.y, 14f, fill)
                prn.color = withAlpha(0xFFFFFFFF.toInt(), a)
            } else {
                fill.color = withAlpha(p.card2, a); cv.drawCircle(d.x, d.y, 14f, fill)
                stroke.color = withAlpha(color, a); cv.drawCircle(d.x, d.y, 13f, stroke)
                prn.color = withAlpha(color, a)
            }
            val pm = prn.fontMetrics
            cv.drawText(s.prn.toString(), d.x, d.y - (pm.ascent + pm.descent) / 2, prn)
        }

        // Mũi tên xe ở tâm, kiểu mũi tên dẫn đường
        cv.save()
        cv.rotate(b ?: 0f, cx, cy)
        arrow.reset()
        arrow.moveTo(cx, cy - 30f); arrow.lineTo(cx + 20f, cy + 22f); arrow.lineTo(cx, cy + 12f); arrow.lineTo(cx - 20f, cy + 22f); arrow.close()
        fill.color = withAlpha(0xFF000000.toInt(), if (p.dark) 0.35f else 0.15f)
        cv.translate(0f, 3f); cv.drawPath(arrow, fill); cv.translate(0f, -3f)  // bóng đổ
        fill.color = if (b != null) p.blue else p.sub
        cv.drawPath(arrow, fill)
        stroke.color = 0xFFFFFFFF.toInt(); stroke.strokeWidth = 2.5f
        cv.drawPath(arrow, stroke)
        stroke.strokeWidth = 3f
        cv.restore()

        if (more) postInvalidateOnAnimation() else lastFrame = 0L
    }

    companion object {
        /** Màu theo cường độ: mạnh xanh dương, vừa xanh lá, yếu cam, không nghe thấy xám. */
        fun colorOf(s: Satellite, p: Palette): Int {
            val snr = s.snr ?: return p.sub
            return when {
                snr >= 35 -> p.blue
                snr >= 25 -> p.green
                snr > 0 -> 0xFFF0A020.toInt()
                else -> p.sub
            }
        }

        fun withAlpha(c: Int, a: Float): Int = (c and 0x00FFFFFF) or ((Color.alpha(c) * a).toInt().coerceIn(0, 255) shl 24)

        fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
            (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(),
            (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(),
            (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt(),
        )
    }
}

/** Cột cường độ tín hiệu (dB-Hz) từng vệ tinh; cột đặc = đang dùng. Chiều cao cột trượt mượt. */
class SignalBarsView(c: Context, private val p: Palette) : View(c) {
    private val shown = HashMap<String, Float>()
    private var lastFrame = 0L

    var satellites: List<Satellite> = emptyList()
        set(v) { field = v; lastFrame = 0L; postInvalidateOnAnimation() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 15f; color = p.sub }
    private val base = Paint().apply { color = p.line; strokeWidth = 2f }

    override fun onDraw(cv: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 16f else (now - lastFrame).coerceIn(1, 100).toFloat()
        lastFrame = now
        val k = 1f - exp(-dt / 220f)
        var moving = false

        val list = satellites.filter { it.snr != null || it.elevation != null }.take(20)
        val bottom = height - 26f
        val top = 22f
        cv.drawLine(0f, bottom, width.toFloat(), bottom, base)
        if (list.isEmpty()) {
            txt.textAlign = Paint.Align.LEFT
            cv.drawText("Chưa thấy vệ tinh", 0f, bottom - 10f, txt)
            txt.textAlign = Paint.Align.CENTER
            return
        }
        val slot = width / 20f
        val bw = slot * 0.62f
        list.forEachIndexed { i, s ->
            val key = "${s.system}${s.prn}"
            val target = (s.snr ?: 0).coerceIn(0, 50).toFloat()
            val cur = shown[key] ?: 0f
            val next = cur + (target - cur) * k
            if (abs(target - next) > 0.1f) moving = true
            shown[key] = next
            val x = slot * i + slot / 2
            val h = (bottom - top) * next / 50f
            val color = SkyRadarView.colorOf(s, p)
            val rect = RectF(x - bw / 2, bottom - h.coerceAtLeast(3f), x + bw / 2, bottom)
            if (s.used) { fill.color = color; cv.drawRoundRect(rect, 5f, 5f, fill) }
            else { stroke.color = color; cv.drawRoundRect(rect, 5f, 5f, stroke) }
            if (s.snr != null) cv.drawText(s.snr.toString(), x, bottom - h - 6f, txt)
            cv.drawText(s.prn.toString(), x, height - 4f, txt)
        }
        if (moving) postInvalidateOnAnimation() else lastFrame = 0L
    }
}
