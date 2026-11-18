package vn.tht.hub.overlay

import android.annotation.SuppressLint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import vn.tht.hub.HubService
import vn.tht.hub.Prefs
import vn.tht.hub.R

/**
 * Thanh nhạc nổi (kiểu "музыкальная плашка" của GUHub): ảnh bìa, tên bài, nút lùi/phát/tiếp.
 * Đọc MediaSession (Spotify, Zing, YouTube Music, Bluetooth…) và trình phát gốc ECARX.
 * Giữ rồi kéo để đổi vị trí; vị trí được lưu.
 */
class MusicBar(private val hub: HubService) {

    private val wm = hub.getSystemService(WindowManager::class.java)
    private val prefs = Prefs(hub)
    private var root: LinearLayout? = null
    private val lp = Ui.overlayParams(460, 72, Gravity.TOP or Gravity.START).apply {
        x = prefs.musicX; y = prefs.musicY
    }

    private val art = ImageView(hub).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val title = Ui.text(hub, "", 17f).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val sub = Ui.text(hub, "", 13f, Ui.DIM).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
    private val play = icon(R.drawable.ic_play) { hub.media.playPause() }

    private val refresh: () -> Unit = { render() }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (root != null) return
        val texts = LinearLayout(hub).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            addView(title); addView(sub)
        }
        val r = LinearLayout(hub).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.round(Ui.BG, 36f)
            setPadding(10, 8, 10, 8)
            addView(art, LinearLayout.LayoutParams(56, 56))
            addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = 12 })
            addView(icon(R.drawable.ic_previous) { hub.media.previous() })
            addView(play)
            addView(icon(R.drawable.ic_next) { hub.media.next() })
        }
        // Giữ 0,5 s rồi kéo để di chuyển
        var dx = 0f; var dy = 0f; var sx = 0; var sy = 0; var moving = false
        r.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; sx = lp.x; sy = lp.y; moving = false }
                MotionEvent.ACTION_MOVE -> if (moving || e.eventTime - e.downTime > 500) {
                    moving = true
                    lp.x = (sx + e.rawX - dx).toInt().coerceIn(0, 1920 - 460)
                    lp.y = (sy + e.rawY - dy).toInt().coerceIn(0, 720 - 72)
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
        }
    }

    fun hide() {
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
        if (np?.art != null) { art.setImageBitmap(np.art); art.clearColorFilter() }
        else { art.setImageResource(R.drawable.ic_music); art.setColorFilter(Ui.DIM) }
        play.setImageResource(if (np?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun icon(res: Int, action: () -> Unit) = ImageView(hub).apply {
        setImageResource(res); setColorFilter(Ui.TEXT); setPadding(12, 12, 12, 12)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(56, 56)
    }
}
