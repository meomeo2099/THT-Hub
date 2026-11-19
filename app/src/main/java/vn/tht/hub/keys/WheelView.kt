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
 * Vô lăng Coolray 2025 vẽ theo ảnh xe thật: vành tròn, chấu dưới hình chữ V viền bạc, đệm giữa lớn,
 * hai cụm nút bạc hai bên (xem [WheelButton]). Nút ga tự động bên trái vẽ mờ (xe xử lý, app không bắt được).
 * Viền xanh = đã biết mã, nền xanh = đã gán chức năng, nháy trắng khi bấm nút thật. Hệ toạ độ 820×560.
 */
class WheelView(c: Context, private val p: Palette, private val onSelect: (String) -> Unit) : View(c) {

    var slots: List<KeySlot> = emptyList(); set(v) { field = v; invalidate() }
    var selected: String? = null; set(v) { field = v; invalidate() }
    private var flashId: String? = null

    private val rects = LinkedHashMap<WheelButton, RectF>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val icons = WheelButton.values().filter { it.icon != 0 }.associateWith { c.getDrawable(it.icon)!!.mutate() }

    private val cx = 410f
    private val cy = 250f

    init {
        fun put(b: WheelButton, x: Float, y: Float, w: Float = 60f, h: Float = 44f) {
            rects[b] = RectF(x - w / 2, y - h / 2, x + w / 2, y + h / 2)
        }
        // Cụm trái (tâm x ≈ cx-178)
        val lx = cx - 178f
        put(WheelButton.ASSIST, lx - 36, cy - 6)
        put(WheelButton.LIM, lx + 34, cy - 22)
        put(WheelButton.GAP_DOWN, lx - 70, cy + 46, 46f)
        put(WheelButton.RES, lx, cy + 30, 64f, 34f)
        put(WheelButton.SET, lx, cy + 66, 64f, 34f)
        put(WheelButton.GAP_UP, lx + 70, cy + 46, 46f)
        put(WheelButton.VOICE, lx - 58, cy + 108, 58f)
        put(WheelButton.CALL, lx + 6, cy + 112, 58f)
        put(WheelButton.HANG_UP, lx + 68, cy + 108, 52f)
        // Cụm phải (tâm x ≈ cx+178)
        val rx = cx + 178f
        put(WheelButton.HOME, rx - 34, cy - 22)
        put(WheelButton.MUTE, rx + 36, cy - 6)
        put(WheelButton.PREV, rx - 72, cy + 46, 46f)
        put(WheelButton.ROLL_UP, rx, cy + 30, 64f, 34f)
        put(WheelButton.ROLL_DOWN, rx, cy + 66, 64f, 34f)
        put(WheelButton.NEXT, rx + 72, cy + 46, 46f)
        put(WheelButton.MODE, rx - 34, cy + 112, 76f)
        put(WheelButton.VIEW, rx + 48, cy + 108, 62f)
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

        val leather = if (p.dark) 0xFF2B3340.toInt() else 0xFF262C35.toInt()
        val silver = if (p.dark) 0xFF8E99A8.toInt() else 0xFFB9C2CD.toInt()
        val pod = if (p.dark) 0xFF1E252F.toInt() else 0xFF1F252D.toInt()
        val btn = if (p.dark) 0xFF323C4A.toInt() else 0xFF3A434F.toInt()

        // Vành
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 54f; paint.color = leather
        cv.drawCircle(cx, cy + 20, 250f, paint)
        // Chấu dưới hình chữ V, viền bạc
        paint.style = Paint.Style.FILL; paint.color = leather
        val v = Path().apply {
            moveTo(cx - 120, cy + 150); lineTo(cx + 120, cy + 150)
            lineTo(cx + 92, cy + 262); lineTo(cx - 92, cy + 262); close()
        }
        cv.drawPath(v, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 6f; paint.color = silver
        cv.drawPath(Path().apply { moveTo(cx - 116, cy + 152); lineTo(cx - 88, cy + 262); lineTo(cx + 88, cy + 262); lineTo(cx + 116, cy + 152) }, paint)

        // Hai cụm nút: nền tối, viền bạc
        fun podPath(sign: Float) = Path().apply {
            val x0 = cx + sign * 100; val x1 = cx + sign * 262
            moveTo(x0, cy - 60); lineTo(x1 - sign * 20, cy - 40)
            quadTo(x1 + sign * 4, cy + 50, x1 - sign * 30, cy + 142)
            lineTo(x0, cy + 142); close()
        }
        for (sign in listOf(-1f, 1f)) {
            val pp = podPath(sign)
            paint.style = Paint.Style.FILL; paint.color = pod; cv.drawPath(pp, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 7f; paint.color = silver; cv.drawPath(pp, paint)
        }
        // Đệm túi khí + logo trung tính
        paint.style = Paint.Style.FILL; paint.color = leather
        cv.drawRoundRect(RectF(cx - 132, cy - 90, cx + 132, cy + 200), 110f, 110f, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 5f; paint.color = silver
        cv.drawOval(RectF(cx - 56, cy + 10, cx + 56, cy + 70), paint)
        paint.strokeWidth = 3f
        cv.drawLine(cx, cy + 12, cx, cy + 68, paint); cv.drawLine(cx - 54, cy + 40, cx + 54, cy + 40, paint)

        for ((b, r) in rects) {
            val slot = slots.firstOrNull { it.id == b.name }
            val on = slot?.active == true
            val flash = b.name == flashId
            paint.style = Paint.Style.FILL
            paint.color = when { flash -> 0xFFFFFFFF.toInt(); on -> p.blue; b.reserved -> 0xFF6B4FBF.toInt(); else -> btn }
            cv.drawRoundRect(r, 10f, 10f, paint)
            if (slot?.learned == true && !on && !b.reserved) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f; paint.color = p.blue
                cv.drawRoundRect(r, 10f, 10f, paint)
            }
            if (b.name == selected) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f; paint.color = 0xFFFFC94D.toInt()
                cv.drawRoundRect(RectF(r.left - 6, r.top - 6, r.right + 6, r.bottom + 6), 14f, 14f, paint)
            }
            val fg = when { flash -> p.blue; b.vehicleOnly -> 0x80FFFFFF.toInt(); b.tint != 0 && !on -> b.tint; else -> 0xFFFFFFFF.toInt() }
            val d = icons[b]
            if (d != null) {
                val sz = 26
                d.setBounds((r.centerX() - sz / 2).toInt(), (r.centerY() - sz / 2).toInt(),
                    (r.centerX() + sz / 2).toInt(), (r.centerY() + sz / 2).toInt())
                d.colorFilter = PorterDuffColorFilter(fg, PorterDuff.Mode.SRC_IN)
                if (b == WheelButton.HANG_UP) { cv.save(); cv.rotate(135f, r.centerX(), r.centerY()); d.draw(cv); cv.restore() } else d.draw(cv)
            } else {
                text.color = fg; text.textSize = if (b.text.length > 3) 17f else 20f
                cv.drawText(b.text, r.centerX(), r.centerY() + 7, text)
            }
        }

        text.color = p.sub; text.textSize = 17f; text.typeface = Typeface.DEFAULT
        cv.drawText("Cụm trái · nút mờ do xe xử lý", cx - 178, cy - 84, text)
        cv.drawText("Cụm phải", cx + 178, cy - 84, text)
        text.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        cv.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_UP) return true
        val s = minOf(width / 820f, height / 560f)
        val x = (e.x - (width - 820 * s) / 2) / s
        val y = (e.y - (height - 560 * s) / 2) / s
        rects.entries.firstOrNull { (_, r) -> RectF(r.left - 4, r.top - 4, r.right + 4, r.bottom + 4).contains(x, y) }
            ?.let { selected = it.key.name; onSelect(it.key.name) }
        return true
    }
}
