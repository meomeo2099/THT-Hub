package vn.tht.hub.keys

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import vn.tht.hub.overlay.Palette

/**
 * Hình vô lăng Coolray 2025 vẽ bằng code (ba chấu, đáy phẳng, hai cụm 2×2 nút ở chấu trái/phải).
 * Chạm một nút để chọn; nút đã học viền xanh, đã gán nền xanh; bấm nút thật thì nút trên hình nháy.
 * Hệ toạ độ thiết kế 820×560, tự co theo kích thước view.
 */
class WheelView(c: Context, private val p: Palette, private val onSelect: (String) -> Unit) : View(c) {

    var slots: List<KeySlot> = emptyList(); set(v) { field = v; invalidate() }
    var selected: String? = null; set(v) { field = v; invalidate() }
    private var flashId: String? = null

    private val rects = LinkedHashMap<String, RectF>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    init {
        // 4 nút trái (L1–L4) và 4 nút phải (R1–R4), lưới 2×2 trên mỗi chấu ngang
        val bw = 62f; val bh = 52f; val g = 10f
        fun grid(prefix: String, x0: Float) {
            var n = 1
            for (r in 0..1) for (col in 0..1) {
                val x = x0 + col * (bw + g); val y = 268f + r * (bh + g)
                rects["$prefix${n++}"] = RectF(x, y, x + bw, y + bh)
            }
        }
        // Chấu trái x 170–340, chấu phải x 480–650 (xem onDraw): căn lưới 134 px vào giữa
        grid("L", 188f)
        grid("R", 498f)
    }

    /** Nháy nút có mã [code] khi người dùng bấm nút thật. */
    fun flash(code: Int) {
        val id = slots.firstOrNull { it.code == code }?.id ?: return
        flashId = id; invalidate()
        postDelayed({ if (flashId == id) { flashId = null; invalidate() } }, 400)
    }

    override fun onDraw(cv: Canvas) {
        val s = minOf(width / 820f, height / 560f)
        cv.save()
        cv.translate((width - 820 * s) / 2, (height - 560 * s) / 2)
        cv.scale(s, s)

        val cx = 410f; val cy = 280f
        val leather = if (p.dark) 0xFF2E3846.toInt() else 0xFF2A323D.toInt()
        val trim = if (p.dark) 0xFF5A6778.toInt() else 0xFF8C97A6.toInt()
        val spoke = if (p.dark) 0xFF3A4555.toInt() else 0xFF3A4350.toInt()

        // Vành: tròn, đáy phẳng
        paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 50f; paint.color = leather
        val rim = Path().apply {
            arcTo(RectF(cx - 250, cy - 250, cx + 250, cy + 250), 118f, 304f, true)
            lineTo(cx - 250 * 0.47f, cy + 250 * 0.88f)
        }
        cv.drawPath(rim, paint)
        paint.strokeWidth = 4f; paint.color = trim
        cv.drawPath(Path().apply { arcTo(RectF(cx - 226, cy - 226, cx + 226, cy + 226), 200f, 140f, true) }, paint)

        // Chấu ngang trái/phải và chấu dưới
        paint.style = Paint.Style.FILL; paint.color = spoke
        cv.drawRoundRect(RectF(cx - 240, cy - 40, cx - 70, cy + 130), 40f, 40f, paint)
        cv.drawRoundRect(RectF(cx + 70, cy - 40, cx + 240, cy + 130), 40f, 40f, paint)
        cv.drawRoundRect(RectF(cx - 46, cy + 100, cx + 46, cy + 240), 30f, 30f, paint)
        // Đệm giữa và logo trung tính (không dùng logo hãng)
        paint.color = leather
        cv.drawRoundRect(RectF(cx - 110, cy - 80, cx + 110, cy + 120), 70f, 70f, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 5f; paint.color = trim
        cv.drawOval(RectF(cx - 44, cy - 6, cx + 44, cy + 46), paint)
        paint.strokeWidth = 3f
        cv.drawLine(cx - 30, cy + 20, cx + 30, cy + 20, paint)

        // Nút
        for ((id, r) in rects) {
            val slot = slots.firstOrNull { it.id == id }
            val on = slot?.active == true
            val flash = id == flashId
            paint.style = Paint.Style.FILL
            paint.color = when { flash -> 0xFFFFFFFF.toInt(); on -> p.blue; else -> if (p.dark) 0xFF2A3442.toInt() else 0xFF56606E.toInt() }
            cv.drawRoundRect(r, 14f, 14f, paint)
            if (slot?.learned == true && !on) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f; paint.color = p.blue
                cv.drawRoundRect(r, 14f, 14f, paint)
            }
            if (id == selected) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f; paint.color = 0xFFFFC94D.toInt()
                cv.drawRoundRect(RectF(r.left - 7, r.top - 7, r.right + 7, r.bottom + 7), 18f, 18f, paint)
            }
            text.color = if (flash) p.blue else 0xFFFFFFFF.toInt()
            text.textSize = 22f
            cv.drawText(id, r.centerX(), r.centerY() + 8, text)
        }

        // Chú thích trái / phải
        text.color = p.sub; text.textSize = 18f
        cv.drawText("Cụm nút trái", cx - 155, cy + 165, text)
        cv.drawText("Cụm nút phải", cx + 155, cy + 165, text)
        cv.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_UP) return true
        val s = minOf(width / 820f, height / 560f)
        val x = (e.x - (width - 820 * s) / 2) / s
        val y = (e.y - (height - 560 * s) / 2) / s
        rects.entries.firstOrNull { (_, r) -> RectF(r.left - 8, r.top - 8, r.right + 8, r.bottom + 8).contains(x, y) }
            ?.let { selected = it.key; onSelect(it.key) }
        return true
    }
}
