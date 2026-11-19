package vn.tht.hub.overlay

import android.annotation.SuppressLint
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import vn.tht.hub.HubService
import vn.tht.hub.Prefs
import vn.tht.hub.R

/**
 * Thanh nhạc nổi (thiết kế 1-tong-quan.png): viên thuốc 560×96 kính mờ, ảnh bìa tròn, tên bài + nguồn,
 * lùi / phát (tròn xanh) / tiếp và vạch tiến trình. Chạm để mở app đang phát; giữ 0,5 s rồi kéo để di chuyển.
 */
class MusicBar(private val hub: HubService) {

    private val wm = hub.getSystemService(WindowManager::class.java)
    private val prefs = Prefs(hub)
    private val main = Handler(Looper.getMainLooper())
    private var root: View? = null
    private val lp = Ui.overlayParams(W, H, Gravity.TOP or Gravity.START).apply {
        x = prefs.musicX; y = prefs.musicY
    }

    private lateinit var p: Palette
    private lateinit var art: ImageView
    private lateinit var title: android.widget.TextView
    private lateinit var sub: android.widget.TextView
    private lateinit var play: ImageView
    private lateinit var progress: View
    private lateinit var track: FrameLayout

    private val refresh: () -> Unit = { render() }
    private val tick = object : Runnable {
        override fun run() { renderProgress(); main.postDelayed(this, 1000) }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (root != null) return
        p = Palette.of(hub)
        art = ImageView(hub).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) = o.setOval(0, 0, v.width, v.height)
            }
            clipToOutline = true
        }
        title = Ui.text(hub, "", 20f, p.text, bold = true)
        sub = Ui.text(hub, "", 15f, p.sub)
        play = Ui.icon(hub, R.drawable.ic_play, 0xFFFFFFFF.toInt(), 56).apply {
            background = Ui.round(p.blue, 28f); setPadding(14, 14, 14, 14)
            setOnClickListener { hub.media.playPause() }
        }
        progress = View(hub).apply { background = Ui.round(p.blue, 2f) }
        track = FrameLayout(hub).apply {
            background = Ui.round(p.line, 2f)
            addView(progress, FrameLayout.LayoutParams(0, 3))
        }
        val texts = LinearLayout(hub).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            addView(title); addView(sub, Ui.lp(Ui.WRAP, Ui.WRAP).apply { topMargin = 4 })
            addView(track, Ui.lp(Ui.MATCH, 3).apply { topMargin = 10 })
        }
        val r = LinearLayout(hub).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.round(if (p.dark) 0xF0212A36.toInt() else 0xEBFAFCFF.toInt(), 48f)
            elevation = 14f
            setPadding(14, 0, 18, 0)
            addView(art, Ui.lp(70, 70))
            addView(texts, Ui.lp(0, Ui.WRAP, 1f).apply { leftMargin = 16; rightMargin = 10 })
            addView(btn(R.drawable.ic_previous) { hub.media.previous() })
            addView(play, Ui.lp(56, 56).apply { leftMargin = 6; rightMargin = 6 })
            addView(btn(R.drawable.ic_next) { hub.media.next() })
        }
        // Giữ 0,5 s rồi kéo để di chuyển; chạm nhanh để mở app đang phát
        var dx = 0f; var dy = 0f; var sx = 0; var sy = 0; var moving = false
        r.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; sx = lp.x; sy = lp.y; moving = false }
                MotionEvent.ACTION_MOVE -> if (moving || e.eventTime - e.downTime > 500) {
                    moving = true
                    lp.x = (sx + e.rawX - dx).toInt().coerceIn(160, 1920 - W)
                    lp.y = (sy + e.rawY - dy).toInt().coerceIn(0, 720 - H)
                    runCatching { wm.updateViewLayout(r, lp) }
                }
                MotionEvent.ACTION_UP -> if (moving) { prefs.musicX = lp.x; prefs.musicY = lp.y } else openPlayer()
            }
            true
        }
        runCatching { wm.addView(r, lp) }.onSuccess {
            root = r
            hub.addListener(refresh)
            render()
            main.post(tick)
        }
    }

    fun hide() {
        main.removeCallbacks(tick)
        hub.removeListener(refresh)
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }

    private fun openPlayer() {
        val pkg = hub.nowPlaying?.pkg ?: return
        hub.packageManager.getLaunchIntentForPackage(pkg)
            ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.let { runCatching { hub.startActivity(it) } }
    }

    private fun render() {
        val r = root ?: return
        val np = hub.nowPlaying
        val empty = np == null || np.title.isNullOrBlank()
        r.visibility = if (empty && prefs.musicBarAutoHide) View.GONE else View.VISIBLE
        title.text = np?.title ?: "Không có nhạc"
        sub.text = listOfNotNull(np?.artist, np?.source).filter { it.isNotBlank() }.joinToString(" · ")
        if (np?.art != null) {
            art.background = null; art.setPadding(0, 0, 0, 0); art.clearColorFilter(); art.setImageBitmap(np.art)
        } else {
            // Đĩa chuyển sắc xanh – tím như thiết kế khi app không có ảnh bìa
            art.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF1F6BF2.toInt(), 0xFF7FB0FF.toInt(), 0xFF9C7BFF.toInt()))
            art.setImageResource(R.drawable.ic_music); art.setColorFilter(0xFFFFFFFF.toInt()); art.setPadding(20, 20, 20, 20)
        }
        play.setImageResource(if (np?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play)
        renderProgress()
    }

    private fun renderProgress() {
        val np = hub.nowPlaying
        val w = track.width
        val frac = if (np != null && np.durationMs > 0) np.currentPosition().toFloat() / np.durationMs else 0f
        (progress.layoutParams as FrameLayout.LayoutParams).width = (w * frac.coerceIn(0f, 1f)).toInt()
        progress.requestLayout()
        track.visibility = if (np != null && np.durationMs > 0) View.VISIBLE else View.INVISIBLE
    }

    private fun btn(res: Int, action: () -> Unit) = Ui.icon(hub, res, p.text, 56).apply {
        setPadding(14, 14, 14, 14)
        setOnClickListener { action() }
    }

    companion object {
        private const val W = 560
        private const val H = 96
    }
}
