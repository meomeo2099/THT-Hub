package vn.tht.hub.system

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import android.view.KeyEvent
import vn.tht.hub.Prefs

/**
 * Tạm dừng nhạc khi có cuộc gọi, tự phát lại sau khi kết thúc.
 *
 * Tương đương phần xử lý cuộc gọi của `AutomationService` trong AutoEasy 1.0.3:
 * nghe trạng thái điện thoại, phát PAUSE khi đổ chuông/nhấc máy và PLAY sau 2,5 s kể từ lúc
 * kết thúc — tắt được bằng `Prefs.callPauseMusic` (khoá `call_pause_music`, mặc định bật như AutoEasy).
 *
 * Chỉ tạm dừng khi nhạc đang thật sự phát ([AudioManager.isMusicActive]) để không tự bật nhạc
 * khi trước cuộc gọi không có gì đang chạy.
 */
class CallWatcher(private val c: Context) {

    private val audio = c.getSystemService(AudioManager::class.java)
    private val telephony = c.getSystemService(TelephonyManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = Prefs(c)

    /** Đang trong cuộc gọi. */
    private var inCall = false
    /** Đã phát PAUSE và còn nợ một lần PLAY. */
    private var paused = false

    private val resume = Runnable {
        if (!paused) return@Runnable
        paused = false
        key(KeyEvent.KEYCODE_MEDIA_PLAY)
    }

    private val listener = object : PhoneStateListener() {
        @Deprecated("Deprecated in Java")
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            val active = state == TelephonyManager.CALL_STATE_RINGING ||
                state == TelephonyManager.CALL_STATE_OFFHOOK
            if (active && !inCall) {
                inCall = true
                if (!prefs.callPauseMusic) return
                handler.removeCallbacks(resume)
                if (paused) return
                val playing = runCatching { audio?.isMusicActive == true }.getOrDefault(false)
                if (!playing) return
                paused = true
                key(KeyEvent.KEYCODE_MEDIA_PAUSE)
            } else if (!active && inCall) {
                inCall = false
                if (!paused) return
                handler.removeCallbacks(resume)
                // AutoEasy chờ 2,5 s sau khi kết thúc cuộc gọi rồi mới phát lại.
                handler.postDelayed(resume, RESUME_DELAY_MS)
            }
        }
    }

    fun start() {
        val tm = telephony ?: return
        runCatching { tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE) }
            .onFailure { Log.w(TAG, "Không nghe được trạng thái cuộc gọi: $it") }
    }

    fun stop() {
        runCatching { telephony?.listen(listener, PhoneStateListener.LISTEN_NONE) }
        handler.removeCallbacksAndMessages(null)
        if (paused) {
            paused = false
            key(KeyEvent.KEYCODE_MEDIA_PLAY)
        }
        inCall = false
    }

    private fun key(code: Int) {
        val am = audio ?: return
        val now = android.os.SystemClock.uptimeMillis()
        runCatching {
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
        }
    }

    companion object {
        private const val TAG = "CallWatcher"
        private const val RESUME_DELAY_MS = 2_500L
    }
}
