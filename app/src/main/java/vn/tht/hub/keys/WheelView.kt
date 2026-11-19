package vn.tht.hub.keys

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import vn.tht.hub.overlay.Palette

/**
 * Hình vô lăng Coolray 2025 vẽ bằng code: ba chấu, đáy phẳng.
 * Chấu trái = cụm 4 phím mũi tên (Lên/Xuống/Trái/Phải); chấu phải = âm lượng +/−, tắt tiếng, điện thoại,
 * nguồn âm thanh, giọng nói. Mỗi nút có biểu tượng thật; chạm để chọn.
 * Viền xanh = đã biết mã, nền xanh = đã gán chức năng, nút nháy trắng khi bấm nút thật trên xe.
 * Hệ toạ độ thiết kế 820×560, tự co theo view.
 */
class WheelView(c: Context, private val p: Palette, private val onSelect: (String) -> Unit) : View(c) {

    var slots: List<KeySlot> = emptyList(); set(v) { field = v; invalidate() }
    var selected: String? = null; set(v) { field = v; invalidate() }
    private var flashId: String? = null

    private val rects = LinkedHashMap<WheelButton, RectF>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val icons = WheelButton.values().associateWith { c.getDrawable(it.icon)!!.mutate() }

    private val cx = 410f
    private val cy = 270f

    init {
        // Cụm trái: chữ thập 4 mũi tên quanh tâm (cx-165, cy+40)
        val dx = cx - 165f; val dy = cy + 40f; val w = 56f; val h = 50f; val o = 56f
        fun at(b: WheelButton, x: Float, y: Float) { rects[b] = RectF(x - w / 2, y - h / 2, x + w / 2, y + h / 2) }
        at(WheelButton.UP, dx, dy - o)
        at(WheelButton.DOWN, dx, dy + o)
        at(WheelButton.LEFT, dx - o - 4, dy)
        at(WheelButton.RIGHT, dx + o + 4, dy)
        // Cụm phải: lưới 2 cột × 3 hàng
        val grid = listOf(
            WheelButton.VOL_UP, WheelButton.VOICE,
            WheelButton.VOL_DOWN, WheelButton.PHONE,
            WheelButton.MUTE, WheelButton.SOURCE,
        )
        val gx = cx + 96f; val gy = cy - 44f; val bw = 70f; val bh = 50f; val g = 8f
        grid.forEachIndexed { i, b ->
            val x = gx + (i % 2) * (bw + g); val y = gy + (i / 2) * (bh + g)
            rects[b] = RectF(x, y, x + bw, y + bh)
        }
    }

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

        val leather = if (p.dark) 0xFF2E3846.toInt() else 0xFF2A323D.toInt()
        val trim = if (p.dark) 0xFF5A6778.toInt() else 0xFF8C97A6.toInt()
        val spoke = if (p.dark) 0xFF3A4555.toInt() else 0xFF3A4350.toInt()
        val btn = if (p.dark) 0xFF4A5668.toInt() else 0xFF56606E.toInt()

        // Vành tròn, đáy phẳng
        paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 50f; paint.color = leather
        cv.drawPath(Path().apply {
            arcTo(RectF(cx - 250, cy - 250, cx + 250, cy + 250), 118f, 304f, true)
            lineTo(cx - 250 * 0.47f, cy + 250 * 0.88f)
        }, paint)
        paint.strokeWidth = 4f; paint.color = trim
        cv.drawPath(Path().apply { arcTo(RectF(cx - 226, cy - 226, cx + 226, cy + 226), 200f, 140f, true) }, paint)

        // Chấu
        paint.style = Paint.Style.FILL; paint.color = spoke
        cv.drawRoundRect(RectF(cx - 262, cy - 46, cx - 70, cy + 128), 44f, 44f, paint)
        cv.drawRoundRect(RectF(cx + 70, cy - 60, cx + 262, cy + 130), 44f, 44f, paint)
        cv.drawRoundRect(RectF(cx - 46, cy + 110, cx + 46, cy + 240), 30f, 30f, paint)
        // Đệm giữa + logo trung tính
        paint.color = leather
        cv.drawRoundRect(RectF(cx - 104, cy - 86, cx + 104, cy + 128), 70f, 70f, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 5f; paint.color = trim
        cv.drawOval(RectF(cx - 44, cy - 2, cx + 44, cy + 50), paint)
        paint.strokeWidth = 3f
        cv.drawLine(cx - 30, cy + 24, cx + 30, cy + 24, paint)
        // Nền tròn của cụm mũi tên
        paint.style = Paint.Style.FILL; paint.color = leather
        cv.drawCircle(cx - 165, cy + 40, 92f, paint)
        paint.color = spoke
        cv.drawCircle(cx - 165, cy + 40, 22f, paint)

        for ((b, r) in rects) {
            val slot = slots.firstOrNull { it.id == b.name }
            val on = slot?.active == true
            val flash = b.name == flashId
            paint.style = Paint.Style.FILL
            paint.color = when { flash -> 0xFFFFFFFF.toInt(); on -> p.blue; b.reserved -> 0xFF6B4FBF.toInt(); else -> btn }
            cv.drawRoundRect(r, 14f, 14f, paint)
            if (slot?.learned == true && !on && !b.reserved) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f; paint.color = p.blue
                cv.drawRoundRect(r, 14f, 14f, paint)
            }
            if (b.name == selected) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f; paint.color = 0xFFFFC94D.toInt()
                cv.drawRoundRect(RectF(r.left - 7, r.top - 7, r.right + 7, r.bottom + 7), 18f, 18f, paint)
            }
            val d = icons.getValue(b)
            val sz = 30
            d.setBounds((r.centerX() - sz / 2).toInt(), (r.centerY() - sz / 2).toInt(),
                (r.centerX() + sz / 2).toInt(), (r.centerY() + sz / 2).toInt())
            d.colorFilter = PorterDuffColorFilter(if (flash) p.blue else 0xFFFFFFFF.toInt(), PorterDuff.Mode.SRC_IN)
            d.draw(cv)
        }

        text.color = p.sub; text.textSize = 18f
        cv.drawText("Cụm trái", cx - 165, cy + 168, text)
        cv.drawText("Cụm phải", cx + 166, cy + 168, text)
        cv.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_UP) return true
        val s = minOf(width / 820f, height / 560f)
        val x = (e.x - (width - 820 * s) / 2) / s
        val y = (e.y - (height - 560 * s) / 2) / s
        rects.entries.firstOrNull { (_, r) -> RectF(r.left - 6, r.top - 6, r.right + 6, r.bottom + 6).contains(x, y) }
            ?.let { selected = it.key.name; onSelect(it.key.name) }
        return true
    }
}
