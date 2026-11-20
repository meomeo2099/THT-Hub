package vn.tht.hub.gps

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import vn.tht.hub.overlay.Palette
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Vòng radar bầu trời (phong cách Flyme của THT Hub): Bắc ở trên, vòng ngoài = chân trời, tâm = thiên đỉnh.
 * Chấm đặc xanh = vệ tinh đang dùng để tính vị trí, chấm rỗng = thấy nhưng không dùng. Quạt mờ = hướng xe.
 */
class SkyRadarView(c: Context, private val p: Palette) : View(c) {
    var satellites: List<Satellite> = emptyList(); set(v) { field = v; invalidate() }
    var bearing: Float? = null; set(v) { field = v; invalidate() }

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = p.line }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 20f; color = p.sub }
    private val prn = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 15f; typeface = Typeface.DEFAULT_BOLD }
    private val path = Path()

    override fun onDraw(cv: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 30f
        if (r <= 0) return

        fill.color = p.card2
        cv.drawCircle(cx, cy, r, fill)
        // Vòng độ cao 0°, 30°, 60° và hai trục
        for (k in 1..3) cv.drawCircle(cx, cy, r * k / 3f, ring)
        cv.drawLine(cx - r, cy, cx + r, cy, ring)
        cv.drawLine(cx, cy - r, cx, cy + r, ring)

        // Quạt hướng xe
        bearing?.let { b ->
            fill.color = p.blue and 0x00FFFFFF or 0x40000000
            cv.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), b - 90f - 18f, 36f, true, fill)
        }
        // Mũi tên xe ở tâm
        val a = Math.toRadians((bearing ?: 0f).toDouble())
        fun pt(ang: Double, d: Float) = (cx + d * sin(ang)).toFloat() to (cy - d * cos(ang)).toFloat()
        val (tx, ty) = pt(a, 26f)
        val (lx, ly) = pt(a + 2.5, 20f)
        val (rx, ry) = pt(a - 2.5, 20f)
        path.reset(); path.moveTo(tx, ty); path.lineTo(lx, ly); path.lineTo(cx, cy); path.lineTo(rx, ry); path.close()
        fill.color = if (bearing != null) p.blue else p.sub
        cv.drawPath(path, fill)

        // Hướng
        val fm = label.fontMetrics
        val mid = -(fm.ascent + fm.descent) / 2
        cv.drawText("B", cx, cy - r - 10f, label)
        cv.drawText("N", cx, cy + r + 10f + 2 * mid, label)
        cv.drawText("Đ", cx + r + 16f, cy + mid, label)
        cv.drawText("T", cx - r - 16f, cy + mid, label)

        // Vệ tinh
        for (s in satellites) {
            val el = s.elevation ?: continue
            val az = s.azimuth ?: continue
            if (el < 0 || el > 90) continue
            val d = r * (90 - el) / 90f
            val ang = Math.toRadians(az.toDouble())
            val x = (cx + d * sin(ang)).toFloat()
            val y = (cy - d * cos(ang)).toFloat()
            val color = colorOf(s, p)
            if (s.used) {
                fill.color = color; cv.drawCircle(x, y, 14f, fill)
                prn.color = 0xFFFFFFFF.toInt()
            } else {
                fill.color = p.card2; cv.drawCircle(x, y, 14f, fill)
                stroke.color = color; cv.drawCircle(x, y, 13f, stroke)
                prn.color = color
            }
            val pm = prn.fontMetrics
            cv.drawText(s.prn.toString(), x, y - (pm.ascent + pm.descent) / 2, prn)
        }
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
    }
}

/** Cột cường độ tín hiệu (dB-Hz) từng vệ tinh; cột đặc = đang dùng. */
class SignalBarsView(c: Context, private val p: Palette) : View(c) {
    var satellites: List<Satellite> = emptyList(); set(v) { field = v; invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 15f; color = p.sub }
    private val base = Paint().apply { color = p.line; strokeWidth = 2f }

    override fun onDraw(cv: Canvas) {
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
            val x = slot * i + slot / 2
            val snr = (s.snr ?: 0).coerceIn(0, 50)
            val h = (bottom - top) * snr / 50f
            val color = SkyRadarView.colorOf(s, p)
            val rect = RectF(x - bw / 2, bottom - h.coerceAtLeast(3f), x + bw / 2, bottom)
            if (s.used) { fill.color = color; cv.drawRoundRect(rect, 4f, 4f, fill) }
            else { stroke.color = color; cv.drawRoundRect(rect, 4f, 4f, stroke) }
            if (s.snr != null) cv.drawText(s.snr.toString(), x, bottom - h - 6f, txt)
            cv.drawText(s.prn.toString(), x, height - 4f, txt)
        }
    }
}
