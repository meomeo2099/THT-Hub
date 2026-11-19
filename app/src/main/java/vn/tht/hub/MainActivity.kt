package vn.tht.hub

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import vn.tht.hub.overlay.Controls
import vn.tht.hub.overlay.Palette
import vn.tht.hub.overlay.Ui
import vn.tht.hub.overlay.showStatus
import vn.tht.hub.overlay.statusLine
import vn.tht.hub.vehicle.EcarxFunctions as F
import vn.tht.hub.vehicle.PlatformSignature

/**
 * Màn chính THT Hub theo thiết kế (design/mockup.html): menu trái có icon, nội dung thẻ trắng,
 * ảnh Coolray mờ dần bên phải. Trang: Tổng quan, Điều khiển nhanh, Khi nổ máy, Lớp nổi, Nhạc và cuộc gọi, Chẩn đoán.
 */
class MainActivity : Activity() {

    private enum class Page(val title: String, val icon: Int) {
        OVERVIEW("Tổng quan", R.drawable.ic_nav_overview),
        PANEL("Điều khiển nhanh", R.drawable.ic_nav_panel),
        START("Khi nổ máy", R.drawable.ic_nav_start),
        OVERLAYS("Lớp nổi", R.drawable.ic_nav_layers),
        MUSIC("Nhạc và cuộc gọi", R.drawable.ic_nav_music),
        DIAG("Chẩn đoán", R.drawable.ic_nav_info),
    }

    private lateinit var prefs: Prefs
    private lateinit var p: Palette
    private val main = Handler(Looper.getMainLooper())
    private var page = Page.OVERVIEW
    private lateinit var nav: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var hero: ImageView
    private var pageRefresh: (() -> Unit)? = null
    private val hubListener: () -> Unit = { pageRefresh?.invoke() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        HubService.start(this)
        page = savedInstanceState?.getString("page")?.let { runCatching { Page.valueOf(it) }.getOrNull() } ?: Page.OVERVIEW
        build()
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putString("page", page.name) }

    override fun onResume() {
        super.onResume()
        attachHub()
    }

    override fun onPause() {
        HubService.instance?.removeListener(hubListener)
        main.removeCallbacksAndMessages(null)
        super.onPause()
    }

    /** Dịch vụ nền có thể chưa kịp chạy khi mở app: chờ rồi gắn listener. */
    private fun attachHub() {
        val hub = HubService.instance
        if (hub == null) { main.postDelayed({ attachHub() }, 500); return }
        hub.addListener(hubListener)
        pageRefresh?.invoke()
    }

    // ---- Khung ----

    private fun build() {
        p = Palette.of(this)
        window.statusBarColor = p.bg
        val root = FrameLayout(this).apply { setBackgroundColor(p.bg) }
        hero = ImageView(this).apply {
            setImageResource(if (p.dark) R.drawable.hero_night else R.drawable.hero_day)
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = if (p.dark) 0.55f else 0.95f
        }
        // Ảnh xe chiếm nửa phải, mờ dần sang trái bằng lớp chuyển sắc phủ lên
        root.addView(hero, FrameLayout.LayoutParams(1120, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(p.bg, p.bg and 0x00FFFFFF))
        }, FrameLayout.LayoutParams(440, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END).apply { rightMargin = 1120 - 440 })

        val body = LinearLayout(this)
        nav = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 20, 0, 0) }
        body.addView(nav, Ui.lp(300, Ui.MATCH))
        content = FrameLayout(this)
        body.addView(content, Ui.lp(0, Ui.MATCH, 1f))
        root.addView(body)
        setContentView(root)
        buildNav()
        show(page)
    }

    private fun buildNav() {
        nav.removeAllViews()
        for (pg in Page.values()) {
            val on = pg == page
            val item = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(36, 0, 0, 0)
                if (on) background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(p.navSel, p.navSel and 0x00FFFFFF))
                val color = if (on) p.blue else p.text
                addView(View(this@MainActivity).apply { setBackgroundColor(if (on) p.blue else 0) }, Ui.lp(5, Ui.MATCH))
                addView(Ui.icon(this@MainActivity, pg.icon, color, 28), Ui.lp(28, 28).apply { leftMargin = 30 })
                addView(Ui.text(this@MainActivity, pg.title, 23f, color), Ui.lp(Ui.WRAP, Ui.WRAP).apply { leftMargin = 20 })
                setOnClickListener { show(pg) }
            }
            item.setPadding(0, 0, 0, 0)
            nav.addView(item, Ui.lp(Ui.MATCH, 70))
        }
    }

    private fun show(pg: Page) {
        page = pg
        buildNav()
        content.removeAllViews()
        pageRefresh = null
        hero.visibility = if (pg == Page.PANEL || pg == Page.DIAG) View.GONE else View.VISIBLE
        val v = when (pg) {
            Page.OVERVIEW -> overview()
            Page.PANEL -> panel()
            Page.START -> start()
            Page.OVERLAYS -> overlays()
            Page.MUSIC -> music()
            Page.DIAG -> diag()
        }
        content.addView(v)
        pageRefresh?.invoke()
    }

    /** Cột nội dung cuộn dọc, rộng [w]. */
    private fun column(w: Int = 760): Pair<ScrollView, LinearLayout> {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 30, 0, 40) }
        val sv = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col) }
        sv.layoutParams = FrameLayout.LayoutParams(w + 40, ViewGroup.LayoutParams.MATCH_PARENT)
        return sv to col
    }

    // ---- Tổng quan ----

    private fun overview(): View {
        val (sv, col) = column(700)
        val fuelV = Ui.text(this, "--", 76f, p.blue, bold = true)
        val fuelSub = Ui.text(this, "", 17f, p.sub)
        val fuelBar = FrameLayout(this).apply { background = Ui.round(p.line, 4f) }
        val fuelFill = View(this).apply { background = Ui.round(p.blue, 4f) }
        fuelBar.addView(fuelFill, FrameLayout.LayoutParams(0, 8))
        val fuelL = Ui.text(this, "", 16f, p.sub)
        val fuelCap = Ui.text(this, "", 16f, p.sub).apply { gravity = Gravity.END }
        val tank = FrameLayout(this).apply {
            background = GradientDrawable().apply { setColor(0); setStroke(4, p.text); cornerRadius = 18f }
            setPadding(4, 4, 4, 4)
        }
        val tankFill = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF5D9BFF.toInt(), 0xFF1F6BF2.toInt()))
                .apply { cornerRadius = 12f }
        }
        tank.addView(tankFill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, Gravity.BOTTOM))

        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.text(this@MainActivity, "Nhiên liệu", 21f, p.text))
            addView(fuelV)
            addView(fuelSub)
            addView(fuelBar, Ui.lp(Ui.MATCH, 8).apply { topMargin = 18; bottomMargin = 10 })
            addView(LinearLayout(this@MainActivity).apply {
                addView(fuelL, Ui.lp(0, Ui.WRAP, 1f)); addView(fuelCap, Ui.lp(0, Ui.WRAP, 1f))
            })
        }
        val big = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.round(p.card, 18f)
            setPadding(28, 22, 28, 22)
            addView(left, Ui.lp(0, Ui.WRAP, 1f))
            addView(tank, Ui.lp(96, 160).apply { leftMargin = 30 })
        }
        big.minimumHeight = 220
        col.addView(big, Ui.lp(676, Ui.WRAP))

        val range = mini(R.drawable.ic_range, "Còn đi được")
        val batt = mini(R.drawable.ic_battery, "Ắc quy 12 V", bar = true)
        val out = mini(R.drawable.ic_thermometer, "Ngoài trời")
        val clim = mini(R.drawable.ic_climate, "Điều hoà")
        col.addView(Ui.row(this, range.view, batt.view, spacing = 16), Ui.lp(Ui.WRAP, Ui.WRAP).apply { topMargin = 16 })
        col.addView(Ui.row(this, out.view, clim.view, spacing = 16), Ui.lp(Ui.WRAP, Ui.WRAP).apply { topMargin = 16 })

        val pill = Ui.text(this, "", 16f, p.text).apply {
            background = Ui.round(p.card2, 14f); setPadding(18, 12, 18, 12)
        }
        val wrap = FrameLayout(this)
        wrap.addView(sv)
        wrap.addView(pill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.END or Gravity.TOP).apply { topMargin = 20; rightMargin = 30 })

        pageRefresh = {
            val hub = HubService.instance
            val v = hub?.vehicle
            val pct = v?.fuelPercent
            fuelV.text = pct?.let { "${it.toInt()}%" } ?: "--"
            fuelSub.text = v?.fuelLiters?.let { String.format(Locale.US, "Còn khoảng %.0f lít", it) } ?: "Chưa đọc được mức xăng"
            fuelBar.post {
                (fuelFill.layoutParams as FrameLayout.LayoutParams).width = ((pct ?: 0f) / 100f * fuelBar.width).toInt()
                fuelFill.requestLayout()
                (tankFill.layoutParams as FrameLayout.LayoutParams).height = ((pct ?: 0f) / 100f * (tank.height - 8)).toInt()
                tankFill.requestLayout()
            }
            val cap = prefs.tankLiters.takeIf { it > 0 } ?: 50
            fuelL.text = v?.fuelLiters?.let { String.format(Locale.US, "%.0f / %d L", it, cap) } ?: ""
            fuelCap.text = "Bình $cap L"
            range.set(v?.rangeKm?.let { "%.0f".format(Locale.US, it) }, "km")
            val volt = v?.batteryVoltage
            batt.set(volt?.let { "%.1f".format(Locale.US, it) }, "V",
                barColor = volt?.let { if (it in 12.2f..15f) p.green else p.red })
            out.set(v?.outsideTempC?.let { "%.0f".format(Locale.US, it) }, "°C")
            val i = hub?.info
            clim.title.text = if (i?.autoOn == true) "Điều hoà · AUTO" else if (i?.hvacOn == false) "Điều hoà · tắt" else "Điều hoà"
            clim.set(i?.tempC?.let { "%.1f".format(Locale.US, it) }, "°C")
            val ok = hub?.ecarx?.connected == true
            pill.text = if (ok) "●  Đã nối xe" else "●  Chưa nối xe"
            pill.setTextColor(if (ok) p.green else p.red)
        }
        return wrap
    }

    private inner class Mini(val view: View, val title: TextView, val value: TextView, val unit: TextView, val bar: View?) {
        fun set(v: String?, u: String, barColor: Int? = null) {
            value.text = v ?: "--"; unit.text = if (v != null) u else ""
            bar?.apply { visibility = if (barColor != null) View.VISIBLE else View.INVISIBLE; barColor?.let { background = Ui.round(it, 3f) } }
        }
    }

    private fun mini(icon: Int, title: String, bar: Boolean = false): Mini {
        val ico = Ui.icon(this, icon, p.blue, 56).apply { background = Ui.round(p.blueSoft, 14f); setPadding(13, 13, 13, 13) }
        val t = Ui.text(this, title, 17f, p.sub)
        val v = Ui.text(this, "--", 38f, p.text, bold = true)
        val u = Ui.text(this, "", 18f, p.sub)
        val b = if (bar) View(this) else null
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(t)
            addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.BOTTOM
                addView(v); addView(u, Ui.lp(Ui.WRAP, Ui.WRAP).apply { leftMargin = 6; bottomMargin = 6 })
            }, Ui.lp(Ui.WRAP, Ui.WRAP).apply { topMargin = 4 })
            b?.let { addView(it, Ui.lp(200, 6).apply { topMargin = 6 }) }
        }
        val card = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.round(p.card, 18f)
            setPadding(22, 0, 22, 0)
            addView(ico, Ui.lp(56, 56))
            addView(texts, Ui.lp(0, Ui.WRAP, 1f).apply { leftMargin = 18 })
            layoutParams = Ui.lp(330, 118)
        }
        return Mini(card, t, v, u, b)
    }

    // ---- Điều khiển nhanh (cùng bộ với bảng kéo) ----

    private fun panel(): View {
        val hub = HubService.instance ?: return waiting()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 30, 30, 30) }
        val status = statusLine(this, p)
        val controls = Controls(this, hub, p, { m, ok -> status.showStatus(p, m, ok) })
        col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.text(this@MainActivity, "Điều khiển nhanh", 24f, p.text, bold = true))
            addView(status, Ui.lp(0, Ui.WRAP, 1f).apply { leftMargin = 24 })
            addView(primary("Mở bảng nổi") { HubService.start(this@MainActivity, HubService.ACTION_TOGGLE_PANEL) }, Ui.lp(220, 56))
        }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = 16 })
        col.addView(controls.build())
        col.addView(Ui.text(this, "Trên mọi màn hình: vuốt lên từ tay kéo nhỏ ở mép dưới.", 16f, p.sub),
            Ui.lp(Ui.WRAP, Ui.WRAP).apply { topMargin = 16 })
        pageRefresh = { controls.render(); if (!hub.ecarx.connected) status.showStatus(p, "Chưa nối được dịch vụ xe (${hub.ecarx.status})", false) }
        return col
    }

    private fun waiting(): View {
        main.postDelayed({ if (HubService.instance != null) show(page) }, 600)
        return Ui.text(this, "Đang khởi động dịch vụ nền…", 20f, p.sub).apply { setPadding(40, 40, 0, 0) }
    }

    // ---- Khi nổ máy ----

    private fun start(): View {
        val (sv, col) = column()
        cap(col, "Kịch bản khi nổ máy", top = 0)
        toggle(col, "Chạy khi nổ máy", "Một lần mỗi khi máy chuyển từ tắt sang nổ, kể cả khi đầu xe vừa khởi động", prefs.autoEnabled) { prefs.autoEnabled = it }
        cap(col, "Chế độ lái")
        col.addView(Ui.Segmented(this, p, listOf(0 to "Không đổi") + F.DRIVE_MODES, prefs.autoDriveMode) { prefs.autoDriveMode = it })
        cap(col, "Điều hoà")
        toggle(col, "Bật AUTO", "Điều hoà tự chọn quạt và hướng gió", prefs.autoClimateAuto) { prefs.autoClimateAuto = it }
        val temps = listOf(0f to "Không đổi") + (32..64).map { (it / 2f) to String.format(Locale.US, "%.1f °C", it / 2f) }
        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }.filter { it.first != packageName }.sortedBy { it.second.lowercase() }
        col.addView(Ui.row(this,
            choice("Nhiệt độ", temps, prefs.autoTemp) { prefs.autoTemp = it },
            choice("Chờ trước khi chạy", listOf(0, 3, 5, 10, 20).map { it to "$it giây" }, prefs.autoDelaySec) { prefs.autoDelaySec = it },
        ), Ui.lp(Ui.WRAP, Ui.WRAP).apply { topMargin = 12 })
        col.addView(Ui.row(this,
            choice("Mở app", listOf("" to "Không mở") + apps, prefs.autoLaunchPkg) { prefs.autoLaunchPkg = it },
            choice("Phát nhạc", listOf(false to "Tắt", true to "Bật"), prefs.autoPlayMusic) { prefs.autoPlayMusic = it },
        ), Ui.lp(Ui.WRAP, Ui.WRAP).apply { topMargin = 10 })
        col.addView(primary("Chạy thử ngay") { HubService.start(this, HubService.ACTION_RUN_START) },
            Ui.lp(720, 60).apply { topMargin = 18 })
        return sv
    }

    // ---- Lớp nổi ----

    private fun overlays(): View {
        val (sv, col) = column()
        cap(col, "Lớp nổi trên mọi màn hình", top = 0)
        toggle(col, "Bảng điều khiển nhanh", "Vuốt lên từ tay kéo ở mép dưới. Giữ rồi kéo ngang để đổi vị trí tay kéo", prefs.quickPanel) { prefs.quickPanel = it; apply() }
        toggle(col, "Thanh nhạc nổi", "Tên bài và nút điều khiển. Giữ 0,5 giây rồi kéo để di chuyển", prefs.musicBar) { prefs.musicBar = it; apply() }
        toggle(col, "Ẩn thanh nhạc khi không phát", "Tự hiện lại khi có nhạc", prefs.musicBarAutoHide) { prefs.musicBarAutoHide = it; apply() }
        cap(col, "Giao diện")
        col.addView(Ui.Segmented(this, p, listOf(0 to "Theo xe", 1 to "Sáng", 2 to "Tối"), prefs.theme) {
            prefs.theme = it
            HubService.start(this, HubService.ACTION_RESTYLE)
            build()
        })
        cap(col, "An toàn")
        col.addView(choice("Tự đóng kính khi chạy trên", listOf(0 to "Tắt", 60 to "60 km/h", 80 to "80 km/h", 100 to "100 km/h"),
            prefs.autoCloseWindowsKmh, width = 720) { prefs.autoCloseWindowsKmh = it })
        col.addView(Ui.text(this, "Thanh trạng thái · chia màn · giọng nói: dùng THT-StatusBar, THT Split, THT Voice", 15f, p.sub),
            Ui.lp(Ui.WRAP, Ui.WRAP).apply { topMargin = 24 })
        return sv
    }

    // ---- Nhạc và cuộc gọi ----

    private fun music(): View {
        val (sv, col) = column()
        cap(col, "Cuộc gọi", top = 0)
        toggle(col, "Tạm dừng nhạc khi có cuộc gọi", "Phát lại 2,5 giây sau khi kết thúc cuộc gọi", prefs.callPauseMusic) { prefs.callPauseMusic = it }
        cap(col, "Đang phát")
        val now = Ui.text(this, "", 20f, p.text).apply { maxLines = 2 }
        col.addView(now)
        col.addView(Ui.row(this,
            secondary("⏮  Bài trước") { HubService.instance?.media?.previous() },
            primary("Phát / Dừng") { HubService.instance?.media?.playPause() },
            secondary("Bài tiếp  ⏭") { HubService.instance?.media?.next() },
        ), Ui.lp(Ui.WRAP, Ui.WRAP).apply { topMargin = 14 })
        cap(col, "Quyền")
        col.addView(secondary("Mở cài đặt đọc thông báo (nếu thanh nhạc không thấy bài)") {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }, Ui.lp(720, 60))
        pageRefresh = {
            val np = HubService.instance?.nowPlaying
            now.text = if (np?.title.isNullOrBlank()) "Không có gì đang phát"
            else listOfNotNull(np?.title, np?.artist, np?.source).filter { it.isNotBlank() }.joinToString(" · ")
        }
        return sv
    }

    // ---- Chẩn đoán ----

    private fun diag(): View {
        val (sv, col) = column(1000)
        cap(col, "Trạng thái", top = 0)
        val text = Ui.text(this, "", 18f, p.text).apply { maxLines = 30; setLineSpacing(8f, 1f) }
        col.addView(text, Ui.lp(Ui.MATCH, Ui.WRAP))
        col.addView(Ui.row(this,
            secondary("Cấp quyền lớp nổi") {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
            },
            secondary("Cấp quyền đọc thông báo") { startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")) },
        ), Ui.lp(Ui.WRAP, Ui.WRAP).apply { topMargin = 20 })
        pageRefresh = {
            val hub = HubService.instance
            val v = hub?.vehicle
            text.text = buildString {
                appendLine("Chữ ký platform: " + if (PlatformSignature.verify(this@MainActivity)) "KHỚP" else "không khớp")
                appendLine("Quyền lớp nổi: " + if (Permissions.canOverlay(this@MainActivity)) "có" else "CHƯA")
                appendLine("Đọc thông báo (nhạc): " + if (Permissions.notificationListenerEnabled(this@MainActivity)) "có" else "CHƯA")
                appendLine("Dịch vụ nền: " + if (hub != null) "đang chạy" else "chưa chạy")
                appendLine("ECARX: " + (hub?.ecarx?.status ?: "-"))
                if (v != null) appendLine(String.format(Locale.US, "Tốc độ %s · Số %s · Máy %s · ODO %s",
                    v.speedKmh?.let { "%.0f km/h".format(Locale.US, it) } ?: "-", v.gearText,
                    when (v.engineOn) { true -> "nổ"; false -> "tắt"; null -> "-" },
                    v.odometerKm?.let { "%.0f km".format(Locale.US, it) } ?: "-"))
                hub?.info?.let { append("Điều hoà: ").append(it.lines().joinToString(" · ")) }
            }
        }
        return sv
    }

    // ---- Thành phần ----

    private fun apply() = HubService.start(this, HubService.ACTION_APPLY)

    private fun cap(col: LinearLayout, t: String, top: Int = 26) =
        col.addView(Ui.text(this, t, 17f, p.sub), Ui.lp(Ui.WRAP, Ui.WRAP).apply { topMargin = top; bottomMargin = 12 })

    private fun toggle(col: LinearLayout, title: String, sub: String, value: Boolean, set: (Boolean) -> Unit) {
        val row = LinearLayout(this).apply { gravity = Gravity.TOP }
        val sw = Ui.Toggle(this, p, value, set)
        row.addView(sw, Ui.lp(46, 28).apply { topMargin = 2 })
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Ui.text(this@MainActivity, title, 20f, p.text))
            addView(Ui.text(this@MainActivity, sub, 15f, p.sub).apply { maxLines = 2 }, Ui.lp(Ui.WRAP, Ui.WRAP).apply { topMargin = 4 })
        }, Ui.lp(0, Ui.WRAP, 1f).apply { leftMargin = 18 })
        row.setOnClickListener { sw.performClick() }
        col.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = 14 })
    }

    private fun <T> choice(label: String, options: List<Pair<T, String>>, current: T, width: Int = 355, set: (T) -> Unit): View {
        val value = Ui.text(this, "", 19f, p.sub)
        fun show(v: T) { value.text = (options.firstOrNull { it.first == v }?.second ?: "$v") + "  ›" }
        show(current)
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.round(p.card2, 10f)
            setPadding(20, 0, 20, 0)
            addView(Ui.text(this@MainActivity, label, 19f, p.text), Ui.lp(0, Ui.WRAP, 1f))
            addView(value)
            layoutParams = Ui.lp(width, 62)
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity, if (p.dark) android.R.style.Theme_DeviceDefault_Dialog_Alert
                    else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                    .setTitle(label)
                    .setItems(options.map { it.second }.toTypedArray()) { _, i -> set(options[i].first); show(options[i].first) }
                    .show()
            }
        }
    }

    private fun primary(label: String, action: () -> Unit) = Ui.text(this, label, 19f, 0xFFFFFFFF.toInt(), bold = true).apply {
        gravity = Gravity.CENTER; background = Ui.round(p.blue, 10f); setPadding(24, 0, 24, 0)
        layoutParams = Ui.lp(Ui.WRAP, 60)
        setOnClickListener { action() }
    }

    private fun secondary(label: String, action: () -> Unit) = Ui.text(this, label, 19f, p.text).apply {
        gravity = Gravity.CENTER; background = Ui.round(p.card2, 10f); setPadding(24, 0, 24, 0)
        layoutParams = Ui.lp(Ui.WRAP, 60)
        setOnClickListener { action() }
    }
}
