package vn.tht.hub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import vn.tht.hub.media.MediaWatcher
import vn.tht.hub.media.NowPlaying
import vn.tht.hub.overlay.MusicBar
import vn.tht.hub.overlay.QuickPanel
import vn.tht.hub.system.CallWatcher
import vn.tht.hub.vehicle.CarPropertySource
import vn.tht.hub.vehicle.EcarxCarSource
import vn.tht.hub.vehicle.EcarxController
import vn.tht.hub.vehicle.EcarxInfo
import vn.tht.hub.vehicle.VehicleData

/**
 * Dịch vụ nền trung tâm (foreground): sở hữu kết nối xe, nhạc, cuộc gọi và các lớp nổi.
 * Không phụ thuộc Activity nào còn sống — giống cách GUHub chạy bên cạnh launcher gốc.
 */
class HubService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs

    lateinit var ecarx: EcarxCarSource; private set
    lateinit var control: EcarxController; private set
    private lateinit var car: CarPropertySource
    lateinit var media: MediaWatcher; private set
    private lateinit var calls: CallWatcher
    lateinit var automation: Automation; private set
    lateinit var audio: vn.tht.hub.audio.AudioControl; private set
    lateinit var doors: vn.tht.hub.vehicle.DoorWindow; private set
    lateinit var gps: vn.tht.hub.gps.GpsRelay; private set

    var vehicle: VehicleData? = null; private set
    var info: EcarxInfo? = null; private set
    var nowPlaying: NowPlaying? = null; private set

    private var quickPanel: QuickPanel? = null
    private var musicBar: MusicBar? = null
    private var unlock: vn.tht.hub.overlay.UnlockCorner? = null
    private var testUnlock: vn.tht.hub.overlay.UnlockCorner? = null

    /** Ai đó (lớp nổi, màn Cài đặt) muốn vẽ lại khi dữ liệu đổi. */
    private val listeners = LinkedHashSet<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun notifyChanged() = listeners.toList().forEach { runCatching(it) }

    private val pollInfo = object : Runnable {
        override fun run() {
            refreshInfo()
            main.postDelayed(this, if (quickPanel?.isOpen == true) 1000L else 3000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        vn.tht.hub.system.ServiceMode.checkExpired(this)
        startForeground(NOTIF_ID, buildNotification())

        val profile = (application as HubApp).profile
        car = CarPropertySource(this, profile) { d0 ->
            // Bản debug bật chế độ mẫu (khoá demo_mode) thì dùng số mẫu khi không có xe
            val d = d0 ?: if (vn.tht.hub.vehicle.DemoCarFunction.enabled(this)) vn.tht.hub.vehicle.DemoCarFunction.vehicle() else null
            vehicle = d
            automation.onVehicle(d)
            notifyChanged()
        }
        ecarx = EcarxCarSource(this) { ready -> Log.i(TAG, "ECARX sẵn sàng: $ready"); if (ready) refreshInfo() }
        ecarx.onChanged = { refreshInfo() }
        control = EcarxController(ecarx, { vehicle?.speedKmh }, { vehicle?.gearText })
        media = MediaWatcher(this) { np -> nowPlaying = np; notifyChanged() }
        calls = CallWatcher(this)
        automation = Automation(this, prefs)
        audio = vn.tht.hub.audio.AudioControl(this).also { it.start() }
        doors = vn.tht.hub.vehicle.DoorWindow(this, ecarx, control) { vehicle?.speedKmh }.also { it.start() }

        gps = vn.tht.hub.gps.GpsRelay(this)
        applyGps()

        car.start(); ecarx.start(); media.start(); calls.start()
        main.post(pollInfo)
        applyOverlays()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_APPLY) { applyOverlays(); applyGps() }
        if (intent?.action == ACTION_TOGGLE_PANEL) quickPanel?.toggle()
        if (intent?.action == ACTION_TEST_UNLOCK && Permissions.canOverlay(this)) {
            testUnlock?.hide(); testUnlock = vn.tht.hub.overlay.UnlockCorner(this, test = true).also { it.show() }
        }
        if (intent?.action == ACTION_RUN_START) automation.runStartScenario(force = true)
        if (intent?.action == ACTION_RESTYLE) { quickPanel?.hide(); quickPanel = null; musicBar?.hide(); musicBar = null; applyOverlays() }
        return START_STICKY
    }

    /** Đọc trạng thái điều hoà/chế độ lái ngoài luồng giao diện. */
    fun refreshInfo() {
        if (!ecarx.connected) return
        control.background({ ecarx.readInfo() }) { i -> info = i; notifyChanged() }
    }

    /** Bật/tắt lớp nổi theo Cài đặt (gọi lại sau khi người dùng đổi). */
    fun applyOverlays() {
        Permissions.grantSelf(this)
        if (!Permissions.canOverlay(this)) { Log.w(TAG, "Chưa có quyền lớp nổi"); return }
        // Chế độ bảo dưỡng: tắt mọi lớp nổi, chỉ còn vùng mở khoá ở góc trên phải
        if (vn.tht.hub.system.ServiceMode.active(this)) {
            quickPanel?.hide(); quickPanel = null; musicBar?.hide(); musicBar = null
            if (unlock == null) unlock = vn.tht.hub.overlay.UnlockCorner(this).also { it.show() }
            return
        }
        unlock?.hide(); unlock = null
        if (prefs.quickPanel) { if (quickPanel == null) quickPanel = QuickPanel(this).also { it.show() } }
        else { quickPanel?.hide(); quickPanel = null }
        if (prefs.musicBar) { if (musicBar == null) musicBar = MusicBar(this).also { it.show() } }
        else { musicBar?.hide(); musicBar = null }
    }

    /** Bật/tắt chuyển GPS USB theo Cài đặt. */
    fun applyGps() {
        if (prefs.gpsRelay) gps.start() else gps.stop()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        runCatching { gps.stop() }
        quickPanel?.hide(); musicBar?.hide()
        runCatching { car.stop() }; runCatching { ecarx.stop() }
        runCatching { media.stop() }; runCatching { calls.stop() }; runCatching { audio.stop() }; runCatching { doors.stop() }
        control.shutdown()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "THT Hub", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle("THT Hub đang chạy")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "THTHub"
        private const val CHANNEL = "hub"
        private const val NOTIF_ID = 7
        const val ACTION_APPLY = "vn.tht.hub.APPLY"
        const val ACTION_TOGGLE_PANEL = "vn.tht.hub.TOGGLE_PANEL"
        const val ACTION_RUN_START = "vn.tht.hub.RUN_START"
        const val ACTION_RESTYLE = "vn.tht.hub.RESTYLE"
        const val ACTION_TEST_UNLOCK = "vn.tht.hub.TEST_UNLOCK"

        @Volatile var instance: HubService? = null; private set

        fun start(c: Context, action: String? = null) {
            val i = Intent(c, HubService::class.java).setAction(action)
            runCatching { c.startForegroundService(i) }.onFailure { Log.w(TAG, "Không khởi động được: ${it.message}") }
        }
    }
}
