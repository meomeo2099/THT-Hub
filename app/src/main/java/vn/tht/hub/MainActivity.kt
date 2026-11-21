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
import vn.tht.hub.gps.SignalBarsView
import vn.tht.hub.gps.SkyRadarView
import vn.tht.hub.keys.KeyAction
import vn.tht.hub.keys.KeyMap
import vn.tht.hub.keys.KeyService
import vn.tht.hub.keys.WheelView
import vn.tht.hub.keys.keyLabel
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
        WHEEL("Nút vô lăng", R.drawable.ic_nav_wheel),
        AUDIO("Âm thanh", R.drawable.ic_speaker),
        DOORS("Cửa và kính", R.drawable.ic_windows),
        START("Khi nổ máy", R.drawable.ic_nav_start),
        OVERLAYS("Lớp nổi", R.drawable.ic_nav_layers),
        MUSIC("Nhạc và cuộc gọi", R.drawable.ic_nav_music),
        GPS("GPS", R.drawable.ic_gps),
        SERVICE("Đi bảo dưỡng", R.drawable.ic_car_settings),
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
        KeyService.learning = false
        KeyService.listener = null
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
        // Chừa giao diện gốc của xe chỉ theo insets hệ thống báo (không tự đặt lề mặc định).
        root.setOnApplyWindowInsetsListener { v, ins ->
            // Trên Coolray cửa sổ app đã được đặt sẵn sau thanh OEM (x ≥ 160) nên insets = 0: KHÔNG cộng thêm lề.
            v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
            ins.consumeSystemWindowInsets()
        }
        root.requestApplyInsets()
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
            nav.addView(item, Ui.lp(Ui.MATCH, 56))
        }
    }

    private fun show(pg: Page) {
        page = pg
        buildNav()
        content.removeAllViews()
        pageRefresh = null
        KeyService.learning = false
        KeyService.listener = null
        hero.visibility = if (pg == Page.PANEL || pg == Page.DIAG || pg == Page.WHEEL || pg == Page.GPS) View.GONE else View.VISIBLE
        val v = when (pg) {
            Page.OVERVIEW -> overview()
            Page.PANEL -> panel()
            Page.WHEEL -> wheel()
            Page.AUDIO -> audioPage()
            Page.DOORS -> doorPage()
            Page.START -> start()
            Page.OVERLAYS -> overlays()
            Page.MUSIC -> music()
            Page.GPS -> gpsPage()
            Page.SERVICE -> servicePage()
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

    // ---- Nút vô lăng ----

    private fun wheel(): View {
        val slots = KeyMap.load(this)
        var sel = slots.firstOrNull { it.active }?.id ?: vn.tht.hub.keys.WheelButton.PREV.name
        val detail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val live = Ui.text(this, "Bấm thử một nút trên vô lăng…", 17f, p.sub)
        val svc = Ui.text(this, "", 16f, p.sub)
        var render: () -> Unit = {}
        val wv = WheelView(this, p) { id -> sel = id; KeyService.learning = false; render() }

        fun save() { KeyMap.save(this, slots); wv.slots = slots.toList() }
        fun liveListener(): (Int) -> Unit = { c -> wv.flash(c); live.text = "Vừa bấm: " + keyLabel(c); live.setTextColor(p.sub) }

        fun actionChoice(label: String, cur: KeyAction, curArg: String, set: (KeyAction, String) -> Unit): View {
            val opts = KeyAction.values().toList()
            val shown = if (cur == KeyAction.OPEN_APP && curArg.isNotBlank())
                "Mở " + (runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(curArg, 0)) }.getOrNull() ?: curArg)
            else cur.label
            return LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = Ui.round(p.card2, 10f); setPadding(20, 0, 20, 0)
                addView(Ui.text(this@MainActivity, label, 19f, p.text))
                addView(Ui.text(this@MainActivity, "$shown  ›", 18f, p.sub).apply { gravity = Gravity.END },
                    Ui.lp(0, Ui.WRAP, 1f).apply { leftMargin = 16 })
                layoutParams = Ui.lp(Ui.MATCH, 62).apply { topMargin = 10 }
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity, dialogTheme()).setTitle(label)
                        .setItems(opts.map { it.label }.toTypedArray()) { _, i ->
                            val a = opts[i]
                            if (a == KeyAction.OPEN_APP) pickApp { pkg -> set(a, pkg) } else set(a, "")
                        }.show()
                }
            }
        }

        render = render@{
            wv.slots = slots.toList(); wv.selected = sel
            val s = slots.first { it.id == sel }
            val wb = vn.tht.hub.keys.WheelButton.valueOf(s.id)
            detail.removeAllViews()
            detail.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                val badgeBg = Ui.round(when { wb.reserved -> 0xFF6B4FBF.toInt(); wb.vehicleOnly -> p.line; else -> p.blue }, 12f)
                addView(if (wb.icon != 0) Ui.icon(this@MainActivity, wb.icon, if (wb.tint != 0) wb.tint else 0xFFFFFFFF.toInt(), 48).apply {
                    background = badgeBg; setPadding(10, 10, 10, 10)
                } else Ui.text(this@MainActivity, wb.text, 16f, 0xFFFFFFFF.toInt(), bold = true).apply {
                    background = badgeBg; gravity = Gravity.CENTER; layoutParams = Ui.lp(64, 48)
                })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(Ui.text(this@MainActivity, wb.label, 26f, p.text, bold = true))
                    addView(Ui.text(this@MainActivity, if (wb.left) "Cụm nút trái" else "Cụm nút phải", 15f, p.sub),
                        Ui.lp(Ui.WRAP, Ui.WRAP).apply { topMargin = 4 })
                }, Ui.lp(0, Ui.WRAP, 1f).apply { leftMargin = 16 })
            }, Ui.lp(Ui.MATCH, Ui.WRAP))
            if (wb.reserved || wb.vehicleOnly) {
                detail.addView(Ui.text(this, if (wb.reserved) "Nút này dành cho THT Voice nên THT Hub không gán."
                    else "Nút ga tự động / hỗ trợ lái: xe xử lý trực tiếp, không đi qua màn hình, nên app không bắt hay đổi được.",
                    18f, p.sub).apply { maxLines = 3 }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = 18 })
                return@render
            }
            detail.addView(Ui.text(this, "Mã phím: ${s.keyName}" + if (s.learned && s.code == wb.knownCode) " · đã thấy trên xe" else "",
                17f, p.sub), Ui.lp(Ui.WRAP, Ui.WRAP).apply { topMargin = 14 })
            detail.addView(primary(if (s.learned) "Học lại mã phím" else "Học mã phím") {
                KeyService.learning = true
                live.text = "Bấm nút «${wb.label}» trên vô lăng ngay bây giờ…"; live.setTextColor(p.blue)
                KeyService.listener = { code ->
                    if (code != android.view.KeyEvent.KEYCODE_BACK && code != android.view.KeyEvent.KEYCODE_HOME) {
                        slots.forEach { o -> if (o.id != s.id && o.code == code) o.code = -1 }   // một mã chỉ thuộc một nút
                        s.code = code
                        KeyService.learning = false
                        KeyService.listener = liveListener()
                        save()
                        render()
                        live.text = "Đã học: ${keyLabel(code)}"; live.setTextColor(p.green)
                    }
                }
            }.apply { layoutParams = Ui.lp(Ui.MATCH, 60).apply { topMargin = 18 } })
            if (s.learned) {
                detail.addView(actionChoice("Khi nhấn", s.press, s.pressArg) { a, arg -> s.press = a; s.pressArg = arg; save(); render() })
                detail.addView(actionChoice("Khi giữ", s.hold, s.holdArg) { a, arg -> s.hold = a; s.holdArg = arg; save(); render() })
                detail.addView(secondary("Xoá gán nút này") {
                    s.code = -1; s.press = KeyAction.PASS; s.hold = KeyAction.PASS; s.pressArg = ""; s.holdArg = ""
                    save(); render()
                }.apply { layoutParams = Ui.lp(Ui.MATCH, 60).apply { topMargin = 10 } })
            }
            detail.addView(Ui.text(this, "Giữ = bấm lâu hơn 0,6 giây. Nút chưa gán vẫn giữ chức năng gốc của xe. " +
                "Nút giọng nói để cho THT Voice.", 15f, p.sub).apply { maxLines = 3 },
                Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = 16 })
        }

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, 30, 30, 0)
            addView(svc)
            addView(live, Ui.lp(Ui.WRAP, Ui.WRAP).apply { topMargin = 6; bottomMargin = 14 })
            addView(detail)
        }
        val row = LinearLayout(this)
        row.addView(wv, Ui.lp(820, 560).apply { topMargin = 30 })
        row.addView(right, Ui.lp(0, Ui.WRAP, 1f).apply { leftMargin = 20 })

        KeyService.listener = liveListener()
        pageRefresh = {
            val on = KeyService.enabled(this) && KeyService.instance != null
            svc.text = if (on) "●  Dịch vụ nút vô lăng đang chạy" else "●  Dịch vụ nút vô lăng chưa chạy – đang tự bật…"
            svc.setTextColor(if (on) p.green else p.red)
            if (!on) KeyService.enable(this)
        }
        render()
        return row
    }

    private fun dialogTheme() = if (p.dark) android.R.style.Theme_DeviceDefault_Dialog_Alert
        else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert

    private fun pickApp(done: (String) -> Unit) {
        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }.filter { it.first != packageName }.sortedBy { it.second.lowercase() }
        AlertDialog.Builder(this, dialogTheme()).setTitle("Chọn app")
            .setItems(apps.map { it.second }.toTypedArray()) { _, i -> done(apps[i].first) }.show()
    }

    // ---- Cửa và kính ----

    private fun doorPage(): View {
        val hub = HubService.instance ?: return waiting()
        val dw = hub.doors
        val D = vn.tht.hub.vehicle.DoorWindow
        val (sv, col) = column(900)
        cap(col, "Mở cửa hạ kính, đóng cửa kéo kính lên", top = 0)
        toggle(col, "Bật", "Mở cửa nào thì kính cửa đó hạ xuống một chút; đóng cửa thì kính kéo lên lại. Chỉ khi xe đứng yên.",
            prefs.doorDropEnabled) { prefs.doorDropEnabled = it }
        col.addView(Ui.Segmented(this, p, listOf(10 to "10%", 15 to "15%", 20 to "20%", 30 to "30%"), prefs.doorDropPercent) {
            prefs.doorDropPercent = it
        })
        toggle(col, "Chỉ hai cửa trước", "Cửa sau không hạ kính", prefs.doorDropFrontOnly) { prefs.doorDropFrontOnly = it }

        cap(col, "Học cửa (làm một lần, xe đang đỗ)")
        val step = Ui.text(this, "Bước 1: đóng hết 4 cửa rồi bấm Bắt đầu.", 18f, p.blue).apply { maxLines = 2 }
        col.addView(step, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = 12 })
        var baseline: Map<String, Int>? = null
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lateinit var renderRows: () -> Unit
        col.addView(primary("Bắt đầu (đã đóng hết cửa)") {
            step.text = "Đang đọc trạng thái cửa…"
            hub.control.background({
                // Đọc hai lần, bỏ tín hiệu tự dao động (điện áp, xăng…) để không nhận nhầm là cửa
                val a = dw.snapshot(); Thread.sleep(1200); val b = dw.snapshot()
                a.filter { (k, v) -> b[k] == v }
            }) { snap ->
                baseline = snap
                step.text = if (snap.isNullOrEmpty()) "Không đọc được tín hiệu cửa nào từ xe (đã nối ECARX chưa?)."
                    else "Bước 2: mở một cửa, bấm Ghi nhận ở đúng dòng cửa đó, đóng lại rồi làm cửa tiếp theo. (${snap.size} tín hiệu)"
            }
        }, Ui.lp(560, 60))
        col.addView(rows, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = 14 })
        val states = arrayOfNulls<TextView>(4)
        renderRows = {
            rows.removeAllViews()
            for (i in 0..3) {
                val l = dw.learned[i]
                val st = Ui.text(this, "", 16f, p.sub).also { states[i] = it }
                rows.addView(LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    background = Ui.round(p.card2, 10f); setPadding(20, 10, 14, 10)
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(Ui.text(this@MainActivity, D.DOOR_NAMES[i], 20f, p.text))
                        addView(Ui.text(this@MainActivity, l?.probe?.label?.let { "Đã học: $it" } ?: "Chưa học", 15f,
                            if (l != null) p.green else p.sub))
                        addView(st)
                    }, Ui.lp(0, Ui.WRAP, 1f))
                    addView(secondary("Ghi nhận") {
                        val base = baseline
                        if (base == null) { step.text = "Bấm Bắt đầu trước (khi đã đóng hết cửa)."; return@secondary }
                        step.text = "Đang so sánh…"
                        hub.control.background({ dw.snapshot() }) { now ->
                            val r = now?.let { dw.learn(i, base, it) }
                            step.text = if (r != null) "Đã học ${D.DOOR_NAMES[i]}: ${r.probe.label}. Đóng cửa rồi làm cửa tiếp theo."
                                else "Không thấy tín hiệu nào đổi. ${D.DOOR_NAMES[i]} đã mở chưa?"
                            renderRows()
                        }
                    }, Ui.lp(Ui.WRAP, 56))
                    addView(secondary("Xoá") { dw.forget(i); renderRows() }, Ui.lp(Ui.WRAP, 56).apply { leftMargin = 8 })
                }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = 10 })
            }
        }
        renderRows()
        pageRefresh = {
            hub.control.background({ dw.learned.map { l -> l?.let { dw.read(it.probe) } } }) { vals ->
                vals?.forEachIndexed { i, v ->
                    val l = dw.learned[i]
                    states[i]?.text = when { l == null -> ""; v == null -> "Không đọc được"; v != l.closedValue -> "●  Đang mở"; else -> "Đang đóng" }
                    states[i]?.setTextColor(if (l != null && v != null && v != l.closedValue) p.blue else p.sub)
                }
            }
        }
        return sv
    }

    // ---- Chế độ đi bảo dưỡng ----

    private fun servicePage(): View {
        val SM = vn.tht.hub.system.ServiceMode
        val (sv, col) = column(900)
        cap(col, "Chế độ đi bảo dưỡng", top = 0)
        col.addView(Ui.text(this, "Ẩn các app cài ngoài trước khi giao xe cho đại lý. App không bị gỡ, dữ liệu giữ nguyên. " +
            "THT Hub giấu biểu tượng và tắt lớp nổi.", 17f, p.sub).apply { maxLines = 3 }, Ui.lp(Ui.MATCH, Ui.WRAP))

        cap(col, "Mở lại các app")
        col.addView(choice("Tự mở lại sau", listOf(0 to "Không tự mở", 1 to "1 giờ", 2 to "2 giờ", 4 to "4 giờ",
            8 to "8 giờ", 24 to "1 ngày", 72 to "3 ngày"), prefs.serviceModeHours, width = 860) { prefs.serviceModeHours = it })
        val pinRow = choice("Mã PIN mở khoá", listOf(prefs.serviceModePin to (prefs.serviceModePin.ifEmpty { "Chưa đặt" })),
            prefs.serviceModePin, width = 860) { }
        pinRow.setOnClickListener { askPin { prefs.serviceModePin = it; show(page) } }
        col.addView(pinRow, Ui.lp(860, 62).apply { topMargin = 10 })
        col.addView(Ui.text(this, "Cách mở khoá: ở MÀN HÌNH CHÍNH, bấm nhanh 5 lần vào khung mờ ••• ở góc trên bên phải (sát dưới đồng hồ) rồi nhập PIN, " +
            "hoặc gõ *#*#4848#*#* trên bàn phím gọi của xe (nếu xe hỗ trợ), hoặc chờ hết giờ hẹn.", 15f, p.sub).apply { maxLines = 3 },
            Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = 10 })

        col.addView(secondary("Thử vùng mở khoá (không ẩn app)") {
            if (prefs.serviceModePin.length < 4) { askPin { prefs.serviceModePin = it; show(page) }; return@secondary }
            HubService.start(this, HubService.ACTION_TEST_UNLOCK)
            android.widget.Toast.makeText(this, "Về màn hình chính, bấm 5 lần vào khung xanh ở góc trên phải", android.widget.Toast.LENGTH_LONG).show()
            moveTaskToBack(true)
        }.apply { layoutParams = Ui.lp(860, 60).apply { topMargin = 12 } })

        cap(col, "App sẽ ẩn")
        val apps = SM.candidates(this)
        // THT Shell mặc định KHÔNG ẩn: còn nó thì quên PIN vẫn chạy được lệnh hiện lại app
        val keep = setOf("com.tht.shell", "com.tht.shell.gateway")
        val chosen = apps.map { it.first }.filter { it !in keep }.toMutableSet()
        for ((pkg, label) in apps) {
            toggle(col, label, pkg + if (pkg in keep) " · giữ lại để cứu khi quên PIN" else "", pkg !in keep) { on ->
                if (on) chosen += pkg else chosen -= pkg
            }
        }
        if (apps.isEmpty()) col.addView(Ui.text(this, "Không có app cài ngoài nào.", 17f, p.sub))

        col.addView(primary("Bật chế độ đi bảo dưỡng") {
            if (prefs.serviceModePin.length < 4) { askPin { prefs.serviceModePin = it; show(page) }; return@primary }
            val hrs = prefs.serviceModeHours
            AlertDialog.Builder(this, dialogTheme()).setTitle("Ẩn ${chosen.size} app?")
                .setMessage("Biểu tượng THT Hub cũng sẽ ẩn. Mở lại bằng PIN ở góc trên phải" +
                    (if (hrs > 0) " hoặc tự mở sau $hrs giờ." else ".") + "\nNhớ mã PIN: ${prefs.serviceModePin}")
                .setPositiveButton("Ẩn") { _, _ ->
                    val n = SM.enter(this, chosen.toList(), hrs)
                    if (n < 0) {
                        android.widget.Toast.makeText(this, "Không ẩn được app (thiếu quyền hệ thống) – chưa bật chế độ", android.widget.Toast.LENGTH_LONG).show()
                    } else {
                        android.widget.Toast.makeText(this, "Đã ẩn $n app", android.widget.Toast.LENGTH_LONG).show()
                        moveTaskToBack(true)
                    }
                }.setNegativeButton("Huỷ", null).show()
        }, Ui.lp(860, 60).apply { topMargin = 24 })
        return sv
    }

    /** Hỏi mã PIN 4–8 số. */
    private fun askPin(done: (String) -> Unit) {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "4–8 chữ số"
        }
        AlertDialog.Builder(this, dialogTheme()).setTitle("Đặt mã PIN mở khoá").setView(input)
            .setPositiveButton("Lưu") { _, _ ->
                val v = input.text.toString()
                if (v.length in 4..8 && v.all(Char::isDigit)) done(v)
                else android.widget.Toast.makeText(this, "PIN phải 4–8 chữ số", android.widget.Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Huỷ", null).show()
    }

    // ---- Âm thanh ----

    private fun audioPage(): View {
        val (sv, col) = column()
        val hub = HubService.instance ?: return waiting()
        val ac = hub.audio
        cap(col, "Âm lượng từng kênh", top = 0)
        val bars = mutableListOf<Pair<vn.tht.hub.audio.AudioControl.Channel, android.widget.SeekBar>>()
        for (ch in vn.tht.hub.audio.AudioControl.Channel.values()) {
            val max = ac.max(ch)
            if (max <= 0) continue
            val value = Ui.text(this, "", 18f, p.sub).apply { gravity = Gravity.END }
            val bar = android.widget.SeekBar(this).apply {
                this.max = max; progress = ac.get(ch)
                progressTintList = android.content.res.ColorStateList.valueOf(p.blue)
                thumbTintList = android.content.res.ColorStateList.valueOf(p.blue)
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: android.widget.SeekBar, v: Int, fromUser: Boolean) {
                        value.text = "$v / $max"; if (fromUser) ac.set(ch, v)
                    }
                    override fun onStartTrackingTouch(s: android.widget.SeekBar) {}
                    override fun onStopTrackingTouch(s: android.widget.SeekBar) {}
                })
            }
            value.text = "${bar.progress} / $max"
            col.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = Ui.round(p.card2, 10f); setPadding(20, 0, 20, 0)
                addView(Ui.text(this@MainActivity, ch.label, 19f, p.text), Ui.lp(230, Ui.WRAP))
                addView(bar, Ui.lp(0, Ui.WRAP, 1f))
                addView(value, Ui.lp(90, Ui.WRAP))
            }, Ui.lp(720, 64).apply { bottomMargin = 10 })
            bars += ch to bar
        }
        col.addView(Ui.text(this, "Cuộc gọi Bluetooth đi qua module GOC riêng của xe, nên kênh Cuộc gọi ở đây có thể không đổi được âm lượng khi gọi.",
            15f, p.sub).apply { maxLines = 2 }, Ui.lp(720, Ui.WRAP))

        cap(col, "Khi bản đồ đọc chỉ đường")
        toggle(col, "Tự giảm nhạc", "Hạ nhạc trong lúc bản đồ đang đọc, đọc xong trả lại mức cũ", prefs.duckOnNav) { prefs.duckOnNav = it }
        col.addView(choice("Mức nhạc khi giảm", listOf(10, 20, 30, 40, 50, 60).map { it to "$it% tối đa" }, prefs.duckPercent, width = 720) { prefs.duckPercent = it })
        val navNow = Ui.text(this, "", 16f, p.sub)
        col.addView(navNow, Ui.lp(Ui.WRAP, Ui.WRAP).apply { topMargin = 10 })

        cap(col, "Khi đầu xe khởi động")
        val musicMax = ac.max(vn.tht.hub.audio.AudioControl.Channel.MUSIC)
        col.addView(choice("Âm lượng nhạc", listOf(-1 to "Giữ như lần trước") + (0..musicMax).map { it to "Mức $it" },
            prefs.startupMusicVolume, width = 720) { prefs.startupMusicVolume = it })

        pageRefresh = {
            for ((ch, b) in bars) if (!b.isPressed) b.progress = ac.get(ch)
            navNow.text = if (ac.navigationPlaying()) "●  Bản đồ đang đọc chỉ đường" else "Bản đồ đang im lặng"
            navNow.setTextColor(if (ac.navigationPlaying()) p.blue else p.sub)
        }
        return sv
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

        cap(col, "Khung giờ")
        val hours = listOf(-1 to "Cả ngày") + (0..23).map { it to "%02d:00".format(it) }
        col.addView(Ui.row(this,
            choice("Từ", hours, prefs.autoFromHour) { prefs.autoFromHour = it },
            choice("Đến", hours, prefs.autoToHour) { prefs.autoToHour = it },
        ))

        val rule = Ui.text(this, "", 17f, p.blue).apply { maxLines = 2 }
        fun refreshRule() { rule.text = HubService.instance?.automation?.climateRule(HubService.instance?.vehicle?.outsideTempC) ?: "" }
        val degrees = (10..40).map { it to "$it °C" }
        cap(col, "Theo nhiệt độ ngoài trời")
        col.addView(rule, Ui.lp(Ui.WRAP, Ui.WRAP).apply { bottomMargin = 12 })
        toggle(col, "Trời nóng", "Bật A/C và đặt nhiệt độ riêng khi ngoài trời nóng", prefs.hotOn) { prefs.hotOn = it; refreshRule() }
        col.addView(Ui.row(this,
            choice("Khi từ", degrees, prefs.hotAbove) { prefs.hotAbove = it; refreshRule() },
            choice("Đặt", temps.drop(1), prefs.hotTemp) { prefs.hotTemp = it; refreshRule() },
        ))
        toggle(col, "Lấy gió trong khi nóng", "Mát nhanh hơn", prefs.hotRecirc) { prefs.hotRecirc = it; refreshRule() }
        toggle(col, "Trời lạnh", "Đặt nhiệt độ ấm hơn khi ngoài trời lạnh", prefs.coldOn) { prefs.coldOn = it; refreshRule() }
        col.addView(Ui.row(this,
            choice("Khi dưới", degrees, prefs.coldBelow) { prefs.coldBelow = it; refreshRule() },
            choice("Đặt", temps.drop(1), prefs.coldTemp) { prefs.coldTemp = it; refreshRule() },
        ))
        toggle(col, "Sấy kính trước khi lạnh", "Chống mờ kính", prefs.coldDefrost) { prefs.coldDefrost = it; refreshRule() }

        col.addView(primary("Chạy thử ngay") { HubService.start(this, HubService.ACTION_RUN_START) },
            Ui.lp(720, 60).apply { topMargin = 18 })
        pageRefresh = { refreshRule() }
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

    // ---- GPS ----

    private fun gpsPage(): View {
        val root = LinearLayout(this).apply { setPadding(40, 30, 40, 30) }

        // Trái: vòng radar
        val radar = SkyRadarView(this, p)
        val legend = Ui.text(this, "", 15f, p.sub).apply { maxLines = 2 }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.round(p.card2, 20f)
            setPadding(20, 16, 20, 16)
            addView(Ui.text(this@MainActivity, "Bầu trời", 16f, p.sub))
            addView(radar, Ui.lp(Ui.MATCH, 0, 1f))
            addView(legend, Ui.lp(Ui.MATCH, Ui.WRAP))
        }, Ui.lp(500, Ui.MATCH))

        // Phải: số liệu, tín hiệu, trạng thái
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun stat(title: String): Pair<View, Pair<TextView, TextView>> {
            val v = Ui.text(this, "—", 40f, p.text, bold = true)
            val u = Ui.text(this, "", 16f, p.sub)
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = Ui.round(p.card2, 20f)
                setPadding(20, 14, 16, 14)
                addView(Ui.text(this@MainActivity, title, 16f, p.sub))
                addView(v, Ui.lp(Ui.WRAP, Ui.WRAP).apply { topMargin = 6 })
                addView(u, Ui.lp(Ui.WRAP, Ui.WRAP).apply { topMargin = 4 })
            }
            return box to (v to u)
        }
        val (b1, speed) = stat("Tốc độ")
        val (b2, head) = stat("Hướng")
        val (b3, sats) = stat("Vệ tinh")
        val (b4, acc) = stat("Độ chính xác")
        right.addView(LinearLayout(this).apply {
            listOf(b1, b2, b3, b4).forEachIndexed { i, b -> addView(b, Ui.lp(0, 128, 1f).apply { if (i > 0) leftMargin = 14 }) }
        }, Ui.lp(Ui.MATCH, Ui.WRAP))

        val bars = SignalBarsView(this, p)
        right.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.round(p.card2, 20f)
            setPadding(20, 14, 20, 10)
            addView(Ui.text(this@MainActivity, "Tín hiệu (dB-Hz) · cột đặc = đang dùng", 16f, p.sub))
            addView(bars, Ui.lp(Ui.MATCH, 150))
        }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = 14 })

        val info = Ui.text(this, "", 17f, p.text).apply { maxLines = 7; setLineSpacing(6f, 1f) }
        val warn = Ui.text(this, "", 16f, p.red).apply { maxLines = 2 }
        val tg = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        toggle(tg, "Đọc GPS USB cho bản đồ", "Vị trí vào hệ thống như GPS thật (không cờ giả lập) để mũi tên Vietmap bám đường. Mọi app bản đồ đều dùng được.",
            prefs.gpsRelay) { prefs.gpsRelay = it; apply() }
        right.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.round(p.card2, 20f)
            setPadding(20, 16, 20, 14)
            addView(tg)
            addView(info)
            addView(warn, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = 6 })
        }, Ui.lp(Ui.MATCH, 0, 1f).apply { topMargin = 14 })
        root.addView(right, Ui.lp(0, Ui.MATCH, 1f).apply { leftMargin = 20 })

        val tick = object : Runnable {
            override fun run() {
                if (page != Page.GPS) return
                val s = HubService.instance?.gps?.snapshot()
                if (s == null) {
                    info.text = "Dịch vụ nền chưa chạy"
                } else {
                    val f = s.fix
                    speed.first.text = f?.let { "%.0f".format(Locale.US, (it.speedMps ?: 0f) * 3.6f) } ?: "—"
                    speed.second.text = "km/h"
                    head.first.text = s.bearing?.let { "%.0f°".format(Locale.US, it) } ?: "—"
                    head.second.text = s.bearing?.let { compass(it) } ?: "chờ xe chạy"
                    val used = s.sky.count { it.used }
                    sats.first.text = if (s.sky.isEmpty()) (f?.satellites?.toString() ?: "—") else "$used/${s.sky.size}"
                    sats.second.text = if (s.sky.isEmpty()) "" else "dùng / thấy"
                    acc.first.text = s.accuracyM?.let { "±%.0f".format(Locale.US, it) } ?: "—"
                    acc.second.text = if (s.accuracyM != null) "m" else ""
                    radar.satellites = s.sky
                    radar.bearing = s.bearing
                    bars.satellites = s.sky
                    val systems = s.sky.map { it.system }.distinct().joinToString(" · ") { sysName(it) }
                    legend.text = if (s.sky.isEmpty()) "Chưa có dữ liệu vệ tinh" else "$systems · đặc = đang dùng · xanh mạnh, lục vừa, cam yếu"
                    info.text = buildString {
                        append(s.usbStatus)
                        if (f != null) append(String.format(Locale.US, "\nVị trí %.6f, %.6f%s",
                            f.lat, f.lon, f.altitudeM?.let { " · cao %.0f m".format(Locale.US, it) } ?: ""))
                        else if (s.device != null) append("\nĐang bắt vệ tinh… (ngoài trời mất 30 giây – vài phút)")
                        if (s.aiding.isNotEmpty()) append("\n").append(s.aiding)
                        s.ttffSec?.let { append("\nCó vị trí sau $it giây kể từ khi mở bộ thu") }
                        append("\nĐã gửi cho bản đồ: ${s.sent} điểm")
                    }
                    warn.text = listOfNotNull(
                        s.error?.let { "Lỗi gửi vị trí: $it" },
                        if (s.bridgeOn) "Car GNSS Bridge đang bật: tắt nó đi để hai app không tranh bộ thu USB" else null,
                    ).joinToString("\n")
                }
                main.postDelayed(this, 200)   // làm mới 5 lần/giây cho chữ số và radar nhận dữ liệu mới sớm
            }
        }
        pageRefresh = { main.removeCallbacks(tick); main.post(tick) }
        return root
    }

    private fun compass(deg: Float): String {
        val names = listOf("Bắc", "Đông Bắc", "Đông", "Đông Nam", "Nam", "Tây Nam", "Tây", "Tây Bắc")
        return names[(((deg + 22.5f) % 360f) / 45f).toInt().coerceIn(0, 7)]
    }

    private fun sysName(c: Char) = when (c) { 'P' -> "GPS"; 'L' -> "GLONASS"; 'A' -> "Galileo"; 'B' -> "BeiDou"; 'Q' -> "QZSS"; else -> "$c" }

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
                appendLine("GPS USB: " + (hub?.gps?.statusText()?.lines()?.joinToString(" · ") ?: "-"))
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
