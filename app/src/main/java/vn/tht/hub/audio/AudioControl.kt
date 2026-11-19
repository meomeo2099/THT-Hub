package vn.tht.hub.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.util.Log
import vn.tht.hub.Prefs

/**
 * Âm lượng theo kênh và tự giảm nhạc khi bản đồ đọc chỉ đường.
 *
 * Đầu xe Coolray 2025 không có dịch vụ `car_audio` (thong_tin/dumpsys_services.txt) nên dùng luồng AudioManager chuẩn.
 * Chỉ đường (USAGE_ASSISTANCE_NAVIGATION_GUIDANCE) thường phát chung luồng nhạc trên máy này, nên "giảm nhạc khi
 * chỉ đường" hạ luồng nhạc xuống mức đã chọn trong lúc bản đồ đang đọc rồi trả lại mức cũ.
 * Bluetooth điện thoại đi qua module GOC riêng: âm lượng cuộc gọi ở đây có thể không tác động lên cuộc gọi Bluetooth.
 */
class AudioControl(private val c: Context) {

    enum class Channel(val label: String, val stream: Int) {
        MUSIC("Nhạc / bản đồ", AudioManager.STREAM_MUSIC),
        CALL("Cuộc gọi", AudioManager.STREAM_VOICE_CALL),
        RING("Chuông", AudioManager.STREAM_RING),
        NOTIFY("Thông báo", AudioManager.STREAM_NOTIFICATION),
        SYSTEM("Âm hệ thống", AudioManager.STREAM_SYSTEM),
        ALARM("Cảnh báo / báo thức", AudioManager.STREAM_ALARM),
    }

    private val am = c.getSystemService(AudioManager::class.java)
    private val prefs = Prefs(c)
    private val main = Handler(Looper.getMainLooper())

    fun max(ch: Channel) = runCatching { am.getStreamMaxVolume(ch.stream) }.getOrDefault(0)
    fun get(ch: Channel) = runCatching { am.getStreamVolume(ch.stream) }.getOrDefault(0)
    fun set(ch: Channel, v: Int, showUi: Boolean = false) = runCatching {
        am.setStreamVolume(ch.stream, v.coerceIn(0, max(ch)), if (showUi) AudioManager.FLAG_SHOW_UI else 0)
    }.onFailure { Log.w(TAG, "Không đặt được âm lượng ${ch.label}: ${it.message}") }

    // ---- Tự giảm nhạc khi chỉ đường ----

    /** Mức nhạc trước khi giảm; null = đang không giảm. */
    private var savedMusic: Int? = null
    private var navActive = false

    private val callback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) = onConfigs(configs)
    }

    fun start() {
        runCatching { am.registerAudioPlaybackCallback(callback, main) }
        applyStartupVolume()
    }

    fun stop() {
        runCatching { am.unregisterAudioPlaybackCallback(callback) }
        restoreMusic()
    }

    /** Có app nào đang phát âm thanh chỉ đường (Vietmap, Google Maps…). */
    fun navigationPlaying(): Boolean = runCatching {
        am.activePlaybackConfigurations.any { it.audioAttributes.usage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE }
    }.getOrDefault(false)

    private fun onConfigs(configs: List<AudioPlaybackConfiguration>) {
        val nav = configs.any { it.audioAttributes.usage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE }
        if (nav == navActive) return
        navActive = nav
        if (!prefs.duckOnNav) return
        if (nav) {
            main.removeCallbacks(restoreRunnable)
            if (savedMusic == null) {
                val cur = get(Channel.MUSIC)
                val target = (max(Channel.MUSIC) * prefs.duckPercent / 100f).toInt()
                if (cur > target) { savedMusic = cur; set(Channel.MUSIC, target); Log.i(TAG, "Giảm nhạc $cur → $target khi chỉ đường") }
            }
        } else {
            // Chờ một chút: bản đồ thường đọc nhiều câu liên tiếp
            main.postDelayed(restoreRunnable, RESTORE_DELAY_MS)
        }
    }

    private val restoreRunnable = Runnable { restoreMusic() }

    private fun restoreMusic() {
        val v = savedMusic ?: return
        savedMusic = null
        set(Channel.MUSIC, v)
        Log.i(TAG, "Trả nhạc về $v")
    }

    /** Đặt âm lượng nhạc cố định khi đầu xe khởi động (tránh bật lên quá to). -1 = giữ nguyên. */
    private fun applyStartupVolume() {
        val v = prefs.startupMusicVolume
        if (v >= 0) set(Channel.MUSIC, v)
    }

    companion object {
        private const val TAG = "THTHubAudio"
        private const val RESTORE_DELAY_MS = 1500L
    }
}
