package vn.tht.hub.vehicle

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.lang.reflect.Method

/**
 * Đọc/ghi dữ liệu xe qua ECARX AdaptAPI 1.1.18.9:
 * `com.ecarx.xui.adaptapi.car.Car.create(context)` → `connect()` → chờ `onConnected` →
 * `getICarFunction()` với các hàm `isFunctionSupported(id, zone)`,
 * `get/setFunctionValue(id, zone[, int])`, `get/setCustomizeFunctionValue(id, zone[, float])`.
 *
 * Trên đầu xe, AdaptAPI KHÔNG nằm trong classpath của app thường (manifest chỉ khai báo
 * android.car), nên phải nạp các jar trong /system/framework bằng PathClassLoader riêng.
 * Car là đối tượng IConnectable: lấy ICarFunction trước khi nối xong sẽ đọc sai / ghi không ăn.
 * Trên máy khác (điện thoại, máy ảo) đơn giản là không nối được và không có dữ liệu.
 *
 * GHI không còn bị chặn cứng: nếu APK không ký bằng khoá platform thì chỉ ghi log cảnh báo và
 * vẫn thử ghi, để tầng SDK tự trả lỗi (AutoEasy 1.0.3 cũng không kiểm tra chữ ký).
 */
class EcarxCarSource(
    private val context: Context,
    private val onReady: (Boolean) -> Unit = {},
) {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var carFunction: Any? = null
    private var loader: ClassLoader? = null
    private var car: Any? = null
    private var watching = false
    /** `isFunctionSupported(int)` – AutoEasy chỉ dùng bản 1 tham số. */
    private var mSupported1: Method? = null
    /** `isFunctionSupported(int, int)` – bản 2 tham số, dự phòng cho firmware khác. */
    private var mSupported2: Method? = null
    private var mGetInt: Method? = null
    private var mSetInt: Method? = null
    private var mGetFloat: Method? = null
    private var mSetFloat: Method? = null
    /** Bản không có vùng: getFunctionValue(id), setFunctionValue(id, value) – dùng cho chức năng toàn xe. */
    private var mGetIntNoZone: Method? = null
    private var mSetIntNoZone: Method? = null
    /** `getSupportedFunctionValue(int[, int])`. */
    private var mSupportedValues1: Method? = null
    private var mSupportedValues2: Method? = null
    private var attempts = 0
    /** Kết quả dò hỗ trợ, có hạn [SUPPORT_TTL_MS] để ECU chưa sẵn sàng không bị nhớ cả phiên. */
    private class Cached(val ok: Boolean, val at: Long)
    private val supportCache = java.util.concurrent.ConcurrentHashMap<Long, Cached>()

    // ---- Function value watcher (AutoEasy `q5` đăng ký trong `y()`) ----

    /** Proxy cho `ICarFunction$IFunctionValueWatcher`, giữ tham chiếu để không bị GC. */
    private var watcher: Any? = null
    private class Watched(val value: Int, val at: Long)
    private class WatchedFloat(val value: Float, val at: Long)
    private val watchInt = java.util.concurrent.ConcurrentHashMap<Long, Watched>()
    private val watchFloat = java.util.concurrent.ConcurrentHashMap<Long, WatchedFloat>()

    /** Gọi trên main thread mỗi khi xe báo giá trị đổi (để vẽ lại ngay, không chờ nhịp poll). */
    @Volatile var onChanged: (() -> Unit)? = null

    private fun key(id: Int, zone: Int) = (id.toLong() shl 32) or (zone.toLong() and 0xFFFFFFFFL)

    /** true khi APK ký bằng khoá platform của firmware; chỉ để hiện trong chẩn đoán, không chặn ghi. */
    var writeAllowed: Boolean = PlatformSignature.verify(context)
        private set

    val connected: Boolean get() = carFunction != null

    /** Trạng thái nối (hiện trong Chẩn đoán). */
    @Volatile var status: String = "chưa nối"
        private set

    fun start() {
        if (connected) return
        try {
            val cl = loader ?: adaptApiLoader().also { loader = it }
            val c = car ?: createCar(cl).also { car = it }
            val connectable = runCatching { cl.loadClass(CONNECTABLE) }.getOrNull()
            if (connectable != null && connectable.isInstance(c)) {
                if (!watching) watching = watchConnection(cl, connectable, c)
                val already = runCatching { connectable.getMethod("isConnected").invoke(c) == true }.getOrDefault(false)
                if (already) {
                    fetchFunction(c)
                } else {
                    status = "đang kết nối dịch vụ xe…"
                    connectable.getMethod("connect").invoke(c)
                    // Nếu bản AdaptAPI không gọi onConnected: thử lấy ICarFunction sau một lúc
                    handler.postDelayed({ if (!connected) fetchFunction(c) }, CONNECT_WAIT_MS)
                }
            } else {
                fetchFunction(c)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ECARX AdaptAPI không khả dụng: $t")
            status = "không có AdaptAPI (${t.javaClass.simpleName}: ${t.message})"
            if (DemoCarFunction.enabled(context)) {
                // Bản debug, không có xe thật: dùng xe giả để xem giao diện (ghi chỉ vào bộ nhớ)
                Log.i(TAG, "Chế độ demo: dùng DemoCarFunction")
                writeAllowed = true
                bind(DemoCarFunction())
                return
            }
            onReady(false)
        }
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        carFunction = null
        supportCache.clear()
    }

    /**
     * ClassLoader có AdaptAPI: của app nếu đã có sẵn, không thì nạp các jar hệ thống của đầu xe
     * (cha là ClassLoader của app để dùng chung lớp Android).
     */
    private fun adaptApiLoader(): ClassLoader {
        val app = context.classLoader
        if (runCatching { app.loadClass(CAR_CLASS) }.isSuccess) return app
        val jars = SYSTEM_JARS.filter { java.io.File(it).exists() }
        if (jars.isEmpty()) throw ClassNotFoundException("$CAR_CLASS (không có jar AdaptAPI trong /system/framework)")
        Log.i(TAG, "Nạp AdaptAPI từ ${jars.joinToString(":")}")
        return dalvik.system.PathClassLoader(jars.joinToString(":"), app)
    }

    /** `Car.create(Context)`; bản không có hàm này thì tạo `CarImpl(Context)`. */
    private fun createCar(cl: ClassLoader): Any {
        val ctx = context.applicationContext
        val create = runCatching { cl.loadClass(CAR_CLASS) }.getOrNull()?.methods?.firstOrNull {
            it.name == "create" && it.parameterTypes.size == 1 && Context::class.java.isAssignableFrom(it.parameterTypes[0])
        }
        if (create != null) create.invoke(null, ctx)?.let { return it }
        return cl.loadClass(CAR_IMPL_CLASS).getConstructor(Context::class.java).newInstance(ctx)
    }

    /** Đăng ký IConnectWatcher: nối xong mới lấy ICarFunction; mất nối thì nối lại. */
    private fun watchConnection(cl: ClassLoader, connectable: Class<*>, c: Any): Boolean = try {
        val watcher = cl.loadClass("$CONNECTABLE\$IConnectWatcher")
        val proxy = java.lang.reflect.Proxy.newProxyInstance(watcher.classLoader, arrayOf(watcher)) { self, m, args ->
            when (m.name) {
                "onConnected" -> { handler.post { fetchFunction(c) }; null }
                "onDisConnected", "onDisconnected" -> { handler.post { onDisconnected() }; null }
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === args?.getOrNull(0)
                "toString" -> "THTCarConnectWatcher"
                else -> null
            }
        }
        connectable.methods.first { it.name == "registerConnectWatcher" && it.parameterTypes.size == 1 }.invoke(c, proxy)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "Không đăng ký được IConnectWatcher: $t")
        false
    }

    private fun onDisconnected() {
        Log.w(TAG, "Mất kết nối dịch vụ xe, nối lại sau ${RETRY_MS} ms")
        carFunction = null
        supportCache.clear()
        status = "mất kết nối, đang nối lại…"
        onReady(false)
        handler.postDelayed({ start() }, RETRY_MS)
    }

    private fun fetchFunction(c: Any) {
        if (connected) return
        val fn = try {
            c.javaClass.methods.firstOrNull { it.name == "getICarFunction" && it.parameterTypes.isEmpty() }?.invoke(c)
        } catch (t: Throwable) {
            Log.w(TAG, "getICarFunction lỗi: ${t.cause ?: t}")
            null
        }
        if (fn == null) retry("getICarFunction trả null") { fetchFunction(c) } else bind(fn)
    }

    private fun retry(reason: String, again: () -> Unit) {
        if (++attempts > MAX_ATTEMPTS) {
            Log.w(TAG, "$reason, bỏ cuộc sau $MAX_ATTEMPTS lần")
            status = "$reason (đã thử $MAX_ATTEMPTS lần)"
            onReady(false)
            return
        }
        Log.i(TAG, "$reason, thử lại ($attempts/$MAX_ATTEMPTS)")
        handler.postDelayed(again, RETRY_MS)
    }

    private fun bind(fn: Any) {
        val methods = fn.javaClass.methods
        fun find(name: String, vararg types: Class<*>) =
            methods.firstOrNull { it.name == name && it.parameterTypes.contentEquals(arrayOf(*types)) }
        // AutoEasy chỉ dùng bản 1 tham số (dexdump: filled-new-array {v2}); bind cả bản 2 tham số để chắc chắn
        mSupported1 = find("isFunctionSupported", INT)
        mSupported2 = find("isFunctionSupported", INT, INT)
        mGetInt = find("getFunctionValue", INT, INT)
        mSetInt = find("setFunctionValue", INT, INT, INT)
        mGetFloat = find("getCustomizeFunctionValue", INT, INT)
        mSetFloat = find("setCustomizeFunctionValue", INT, INT, FLOAT)
        mGetIntNoZone = find("getFunctionValue", INT)
        mSetIntNoZone = find("setFunctionValue", INT, INT)
        mSupportedValues1 = find("getSupportedFunctionValue", INT)
        mSupportedValues2 = find("getSupportedFunctionValue", INT, INT)
        supportCache.clear()
        watchInt.clear()
        watchFloat.clear()
        attempts = 0
        carFunction = fn
        status = "đã nối (${fn.javaClass.simpleName})"
        Log.i(
            TAG, "Đã nối ${fn.javaClass.name}: supported1=${mSupported1 != null} supported2=${mSupported2 != null} " +
                "int=${mGetInt != null}/${mSetInt != null} float=${mGetFloat != null}/${mSetFloat != null} " +
                "values=${mSupportedValues1 != null}/${mSupportedValues2 != null} " +
                "ghi=${if (writeAllowed) "MỞ (chữ ký platform khớp)" else "THỬ (chữ ký không khớp firmware)"}"
        )
        registerWatcher(fn)
        onReady(true)
    }

    // ---- Đọc ----

    /**
     * true = xe có chức năng này (trạng thái "active", hoặc "notactive" = có nhưng đang tạm không dùng,
     * ví dụ nhiệt độ khi điều hoà tắt), false = xe không có, null = không hỏi được.
     * Chỉ nhớ kết quả chắc chắn; trạng thái khác (lỗi, chưa sẵn sàng) hỏi lại lần sau.
     */
    fun isSupported(id: Int, zone: Int = EcarxFunctions.ZONE_ALL): Boolean? {
        val key = key(id, zone)
        supportCache[key]?.let { if (System.currentTimeMillis() - it.at < SUPPORT_TTL_MS) return it.ok }
        val state = rawSupportState(id, zone)?.lowercase() ?: return null
        val result = when (state) {
            "active", "notactive" -> true
            "notavailable", "unsupported", "notsupport" -> false
            else -> return null
        }
        supportCache[key] = Cached(result, System.currentTimeMillis())
        return result
    }

    /** Gọi `isFunctionSupported` bản 1 tham số trước (đúng như AutoEasy), rồi bản 2 tham số. */
    private fun rawSupportState(id: Int, zone: Int): String? {
        val raw = runCatching { mSupported1?.invoke(carFunction, id) }.getOrNull()
            ?: runCatching { mSupported2?.invoke(carFunction, id, zone) }.getOrNull()
            ?: return null
        return if (raw is Enum<*>) raw.name else raw.toString()
    }

    /** Bỏ giá trị không hợp lệ khi đọc: âm, và 253..255 (AutoEasy `q5.java:1089-1108`). */
    private fun validInt(v: Int?): Int? = v?.takeIf { it >= 0 && (it < 253 || it > 255) }

    fun getInt(id: Int, zone: Int = EcarxFunctions.ZONE_ALL): Int? {
        // Chức năng toàn xe: hỏi bản không vùng trước, không được thì hỏi kèm ZONE_ALL
        if (zone == EcarxFunctions.ZONE_ALL) {
            validInt(runCatching { (mGetIntNoZone?.invoke(carFunction, id) as? Number)?.toInt() }.getOrNull())
                ?.let { return it }
        }
        return validInt(
            runCatching { (mGetInt?.invoke(carFunction, id, zone) as? Number)?.toInt() }.getOrNull()
        )
    }

    fun getFloat(id: Int, zone: Int = EcarxFunctions.ZONE_ALL): Float? = try {
        (mGetFloat?.invoke(carFunction, id, zone) as? Number)?.toFloat()?.takeIf { it.isFinite() }
    } catch (_: Throwable) {
        null
    }

    fun supportedValues(id: Int, zone: Int = EcarxFunctions.ZONE_ALL): IntArray? {
        runCatching { mSupportedValues1?.invoke(carFunction, id) }.getOrNull()?.let { if (it is IntArray) return it }
        return runCatching { mSupportedValues2?.invoke(carFunction, id, zone) }.getOrNull() as? IntArray
    }

    // ---- Watcher (AutoEasy `q5.y()`): xe đẩy giá trị mới thay vì chờ poll ----

    /**
     * Đăng ký `registerFunctionValueWatcher` bằng reflection, đúng thứ tự AutoEasy thử:
     * `(int[], watcher)` → `(int, watcher)` từng hàm → `(watcher)`.
     */
    private fun registerWatcher(fn: Any) {
        val ld = loader ?: return
        val cls = runCatching {
            ld.loadClass("com.ecarx.xui.adaptapi.car.base.ICarFunction\$IFunctionValueWatcher")
        }.getOrNull() ?: return
        val proxy = runCatching {
            java.lang.reflect.Proxy.newProxyInstance(cls.classLoader, arrayOf(cls)) { p, m, args ->
                handleWatchEvent(m.name, args, p)
            }
        }.getOrNull() ?: return
        watcher = proxy

        // Cùng danh sách AutoEasy dùng, cộng thêm các ID trong catalog của THT
        val fixed = intArrayOf(
            268828928, 268763648, 268763392, 553846272, 553845760,
            553979904, 704709376, 570491136, 537002240, 269484800,
        )
        val ids = (fixed.toSet() + EcarxFunctions.ALL.map { it.id }).filter { it != 0 }.toIntArray()

        val all = runCatching {
            fn.javaClass.getMethod("registerFunctionValueWatcher", IntArray::class.java, cls)
                .invoke(fn, ids, proxy) as? Boolean
        }.getOrNull() == true
        if (all) {
            Log.i(TAG, "watcher: đăng ký cả bảng (${ids.size} hàm)")
            return
        }
        var ok = 0
        val intType = Int::class.javaPrimitiveType!!
        for (id in ids) {
            val r = runCatching {
                fn.javaClass.getMethod("registerFunctionValueWatcher", intType, cls)
                    .invoke(fn, id, proxy) as? Boolean
            }.getOrNull() == true
            if (r) ok++
        }
        if (ok > 0) {
            Log.i(TAG, "watcher: đăng ký từng hàm $ok/${ids.size}")
            return
        }
        val single = runCatching {
            fn.javaClass.getMethod("registerFunctionValueWatcher", cls).invoke(fn, proxy) as? Boolean
        }.getOrNull() == true
        Log.i(TAG, "watcher: không đăng ký được (all=false, từng hàm=0, một tham số=$single)")
    }

    /** Xử lý callback của watcher; sai kiểu/tham số thì bỏ qua, không được ném ra binder. */
    private fun handleWatchEvent(name: String?, args: Array<out Any?>?, proxy: Any): Any? {
        when (name) {
            "onFunctionValueChanged" -> {
                val id = (args?.getOrNull(0) as? Number)?.toInt()
                val zone = (args?.getOrNull(1) as? Number)?.toInt() ?: EcarxFunctions.ZONE_ALL
                val value = (args?.getOrNull(2) as? Number)?.toInt()
                if (id != null && value != null) {
                    watchInt[key(id, zone)] = Watched(value, SystemClock.elapsedRealtime())
                    notifyChanged()
                }
                return null
            }
            "onCustomizeFunctionValueChanged" -> {
                val id = (args?.getOrNull(0) as? Number)?.toInt()
                val zone = (args?.getOrNull(1) as? Number)?.toInt() ?: EcarxFunctions.ZONE_ALL
                val value = (args?.getOrNull(2) as? Number)?.toFloat()
                if (id != null && value != null) {
                    watchFloat[key(id, zone)] = WatchedFloat(value, SystemClock.elapsedRealtime())
                    notifyChanged()
                }
                return null
            }
            "onFunctionChanged", "onSupportedFunctionStatusChanged" -> {
                // Hỗ trợ đổi → xoá cache dò để lần sau hỏi lại
                supportCache.clear()
                notifyChanged()
                return null
            }
            "toString" -> return "THTValueWatcher"
            "hashCode" -> return System.identityHashCode(proxy)
            "equals" -> return proxy === args?.getOrNull(0)
        }
        return null
    }

    private fun notifyChanged() {
        handler.post { onChanged?.invoke() }
    }

    /** Giá trị watcher vừa báo (dùng để xác nhận lệnh ghi mà không phải chờ poll). */
    fun lastWatchedInt(id: Int, zone: Int = EcarxFunctions.ZONE_ALL): Int? =
        watchInt[key(id, zone)]?.let { if (SystemClock.elapsedRealtime() - it.at < WATCH_TTL_MS) it.value else null }

    fun lastWatchedFloat(id: Int, zone: Int = EcarxFunctions.ZONE_ALL): Float? =
        watchFloat[key(id, zone)]?.let { if (SystemClock.elapsedRealtime() - it.at < WATCH_TTL_MS) it.value else null }

    // ---- Ghi (không chặn theo chữ ký: SDK tự quyết định) ----

    /** true chỉ nghĩa là AdaptAPI nhận lệnh; người gọi phải đọc lại để xác nhận. */
    fun setInt(id: Int, zone: Int, value: Int): Boolean {
        // Đúng thứ tự AutoEasy (ei.java:43, gl.java:53): có vùng trước, rồi bản không vùng
        if (write(mSetInt, id, zone, value)) return true
        if (mSetIntNoZone != null && write(mSetIntNoZone, id, null, value)) return true
        // Cuối cùng là thang zone của AutoEasy q5.C(): MIN_VALUE, 0, 1
        if (zone != EcarxFunctions.ZONE_ALL) {
            for (z in intArrayOf(EcarxFunctions.ZONE_ALL, 0, 1)) {
                if (z != zone && write(mSetInt, id, z, value)) return true
            }
        }
        return false
    }

    fun setFloat(id: Int, zone: Int, value: Float): Boolean = write(mSetFloat, id, zone, value)

    /** `setFunctionValue(id, value)` không kèm vùng — AutoEasy dùng cho đèn viền Coolray (`q5.A`). */
    fun setIntNoZone(id: Int, value: Int): Boolean = write(mSetIntNoZone, id, null, value)

    /**
     * Điều hoà trên Coolray: AutoEasy `r3.a()` gọi `A(id, value)` (**không zone**) cho mọi công tắc
     * HVAC; chỉ Monjaro mới dùng zone (`d(i)`: AUTO → MIN_VALUE, quạt/hướng gió → 8).
     * Xe không nhận bản không zone thì vẫn thử bản có zone.
     */
    fun setIntHvac(id: Int, value: Int): Boolean {
        if (write(mSetIntNoZone, id, null, value)) return true
        return write(mSetInt, id, EcarxFunctions.ZONE_ALL, value)
    }

    /** Thang vùng của AutoEasy `q5.C`: thử MIN_VALUE → 0 → 1, dừng ở lần đầu nhận. */
    fun setIntLadder(id: Int, value: Int): Boolean {
        for (z in intArrayOf(EcarxFunctions.ZONE_ALL, 0, 1)) {
            if (write(mSetInt, id, z, value)) return true
        }
        return false
    }

    /** [zone] null = gọi bản không có tham số vùng. */
    private fun write(method: Method?, id: Int, zone: Int?, value: Any): Boolean {
        if (!writeAllowed) {
            // Không chặn: AutoEasy cũng không kiểm tra chữ ký, để SDK tự quyết định
            Log.w(TAG, "Chữ ký không khớp platform – vẫn thử ghi ${EcarxFunctions.hex(id)}")
        }
        if (method == null || carFunction == null) return false
        return try {
            val r = if (zone == null) method.invoke(carFunction, id, value) else method.invoke(carFunction, id, zone, value)
            Log.i(TAG, "set ${EcarxFunctions.hex(id)} zone=${zone ?: "-"} value=$value -> $r")
            when (r) {
                is Boolean -> r
                is Number -> r.toInt() != 0
                null -> true // hàm void
                else -> false
            }
        } catch (t: Throwable) {
            Log.w(TAG, "set ${EcarxFunctions.hex(id)} lỗi: ${t.cause ?: t}")
            false
        }
    }

    // ---- Hiển thị ----

    /** Trạng thái để hiện trên màn hình chính; chỉ đọc function xe báo hỗ trợ. null nếu chưa nối. */
    fun readInfo(): EcarxInfo? {
        if (!connected) return null
        val f = EcarxFunctions
        fun <T> ifSupported(id: Int, zone: Int = f.ZONE_ALL, read: () -> T?): T? =
            if (isSupported(id, zone) == true) read() else null
        val tempZone = if (isSupported(f.HVAC_TEMP, f.ZONE_ROW_1_LEFT) == true) f.ZONE_ROW_1_LEFT else f.ZONE_ALL
        return EcarxInfo(
            hvacOn = ifSupported(f.HVAC_POWER) { getInt(f.HVAC_POWER) }?.let { it == f.ON },
            acOn = ifSupported(f.HVAC_AC) { getInt(f.HVAC_AC) }?.let { it == f.ON },
            tempC = ifSupported(f.HVAC_TEMP, tempZone) { getFloat(f.HVAC_TEMP, tempZone) }
                ?.takeIf { it in 10f..40f },   // dải thật do xe quyết định; chỉ loại giá trị vô lý
            fanLevel = ifSupported(f.HVAC_FAN_SPEED) { f.levelOf(f.HVAC_FAN_SPEED, getInt(f.HVAC_FAN_SPEED)) },
            driveMode = ifSupported(f.DRIVE_MODE) { f.levelOf(f.DRIVE_MODE, getInt(f.DRIVE_MODE)) },
            ambientOn = ifSupported(f.AMBIENT_SWITCH) { getInt(f.AMBIENT_SWITCH) }?.let { it == f.ON },
            autoOn = ifSupported(f.HVAC_AUTO) { getInt(f.HVAC_AUTO) }?.let { it == f.ON },
            recirc = ifSupported(f.HVAC_CIRCULATION) { f.levelOf(f.HVAC_CIRCULATION, getInt(f.HVAC_CIRCULATION)) },
            blowing = ifSupported(f.HVAC_BLOWING_MODE) { f.levelOf(f.HVAC_BLOWING_MODE, getInt(f.HVAC_BLOWING_MODE)) },
            defrostFront = ifSupported(f.HVAC_DEFROST_FRONT) { getInt(f.HVAC_DEFROST_FRONT) }?.let { it == f.ON },
            defrostRear = ifSupported(f.HVAC_DEFROST_REAR) { getInt(f.HVAC_DEFROST_REAR) }?.let { it == f.ON },
            seatHeatLeft = ifSupported(f.HVAC_SEAT_HEATING, f.ZONE_ROW_1_LEFT) {
                f.levelOf(f.HVAC_SEAT_HEATING, getInt(f.HVAC_SEAT_HEATING, f.ZONE_ROW_1_LEFT))
            },
            seatHeatRight = ifSupported(f.HVAC_SEAT_HEATING, f.ZONE_ROW_1_RIGHT) {
                f.levelOf(f.HVAC_SEAT_HEATING, getInt(f.HVAC_SEAT_HEATING, f.ZONE_ROW_1_RIGHT))
            },
            tempRightC = ifSupported(f.HVAC_TEMP, f.ZONE_ROW_1_RIGHT) { getFloat(f.HVAC_TEMP, f.ZONE_ROW_1_RIGHT) }
                ?.takeIf { it in f.TEMP_MIN..f.TEMP_MAX },
        )
    }

    /**
     * Dò toàn bộ catalog và in ra logcat (tag "EcarxCarSource"). Xem: `adb logcat -s EcarxCarSource`.
     */
    /** Toàn bộ catalog dạng chữ (cho báo cáo chẩn đoán): trạng thái, giá trị, giá trị cho phép. Gọi ngoài luồng giao diện. */
    fun report(): String = buildString {
        if (!connected) { appendLine("(chưa nối ICarFunction)"); return@buildString }
        for (fn in EcarxFunctions.ALL) {
            val state = rawSupportState(fn.id, fn.zone) ?: "null"
            append("${fn.name} ${EcarxFunctions.hex(fn.id)} zone=${fn.zone}: $state")
            if (state.equals("active", true) || state.equals("notactive", true)) {
                append(" int=${getInt(fn.id, fn.zone)} float=${getFloat(fn.id, fn.zone)}")
                supportedValues(fn.id, fn.zone)?.let { v -> append(" values=[${v.joinToString(",") { EcarxFunctions.hex(it) }}]") }
            }
            appendLine()
        }
        // Mã chưa dùng trong app (xem docs/ecarx-reference.md): dò để biết xe còn hỗ trợ gì
        appendLine("-- Dò thêm")
        for (id in PROBE_IDS) {
            val state = rawSupportState(id, EcarxFunctions.ZONE_ALL) ?: "null"
            append("${EcarxFunctions.hex(id)}: $state")
            if (state.equals("active", true) || state.equals("notactive", true)) {
                append(" int=${getInt(id)} float=${getFloat(id)}")
                supportedValues(id)?.let { v -> append(" values=[${v.joinToString(",") { EcarxFunctions.hex(it) }}]") }
            }
            appendLine()
        }
    }

    /**
     * Danh mục hằng số số nguyên trong AdaptAPI của chính xe (tên → mã), để biết mã chức năng chuẩn
     * của firmware. Đọc danh sách lớp từ ClassLoader đã nạp jar hệ thống; gọi ngoài luồng giao diện.
     */
    fun constantsCatalog(): String {
        val cl = runCatching { loader ?: adaptApiLoader().also { loader = it } }.getOrElse {
            return "(không nạp được AdaptAPI: $it)\n"
        }
        return catalog(cl, "com.ecarx.xui.adaptapi", SYSTEM_JARS)
    }

    /** Liệt kê hằng số int/long của mọi lớp có tên bắt đầu bằng [prefix] mà [cl] (hoặc [jars]) chứa. */
    fun catalog(cl: ClassLoader, prefix: String, jars: List<String> = emptyList()): String = buildString {
        val names = sortedSetOf<String>()
        // Lấy tên lớp từ các file dex mà ClassLoader đang giữ (BaseDexClassLoader.pathList.dexElements[].dexFile)
        runCatching {
            var c: ClassLoader? = cl
            while (c != null) {
                if (c is dalvik.system.BaseDexClassLoader) {
                    val pathList = dalvik.system.BaseDexClassLoader::class.java.getDeclaredField("pathList").apply { isAccessible = true }.get(c)
                    val elements = pathList.javaClass.getDeclaredField("dexElements").apply { isAccessible = true }.get(pathList) as Array<*>
                    for (e in elements) {
                        val dex = e?.javaClass?.getDeclaredField("dexFile")?.apply { isAccessible = true }?.get(e) as? dalvik.system.DexFile ?: continue
                        @Suppress("DEPRECATION")
                        for (n in dex.entries()) if (n.startsWith(prefix)) names += n
                    }
                }
                c = c.parent
            }
        }.onFailure { appendLine("(không đọc được danh sách lớp: $it)") }
        // Dự phòng: AdaptAPI nằm trong classpath hệ thống → mở thẳng các jar để lấy tên lớp
        if (names.isEmpty()) for (path in jars.filter { java.io.File(it).exists() }) {
            runCatching {
                @Suppress("DEPRECATION")
                val dex = dalvik.system.DexFile(path)
                @Suppress("DEPRECATION")
                for (n in dex.entries()) if (n.startsWith(prefix)) names += n
            }.onFailure { appendLine("(không mở được $path: $it)") }
        }
        if (names.isEmpty()) { appendLine("(không thấy lớp AdaptAPI nào)"); return@buildString }
        var total = 0
        for (n in names) {
            val cls = runCatching { Class.forName(n, false, cl) }.getOrNull() ?: continue
            val consts = cls.declaredFields.filter {
                java.lang.reflect.Modifier.isStatic(it.modifiers) && java.lang.reflect.Modifier.isFinal(it.modifiers) &&
                    (it.type == Int::class.javaPrimitiveType || it.type == Long::class.javaPrimitiveType)
            }.mapNotNull { f ->
                runCatching { f.isAccessible = true; f.name to (f.get(null) as Number).toLong() }.getOrNull()
            }
            if (consts.isEmpty()) continue
            appendLine("# ${n.removePrefix("$prefix.")}")
            for ((k, v) in consts) appendLine("  $k = 0x${java.lang.Long.toHexString(v)} ($v)")
            total += consts.size
        }
        appendLine("(${names.size} lớp, $total hằng số)")
    }

    fun dumpSupported() {
        if (!connected) {
            Log.w(TAG, "Chưa nối ICarFunction, bỏ qua dump")
            return
        }
        Log.i(TAG, "===== Dò function ECARX (ghi ${if (writeAllowed) "MỞ" else "KHOÁ"}) =====")
        var n = 0
        for (fn in EcarxFunctions.ALL) {
            val state = rawSupportState(fn.id, fn.zone) ?: "null"
            if (state.equals("active", ignoreCase = true) || state.equals("notactive", ignoreCase = true)) {
                n++
                val values = supportedValues(fn.id, fn.zone)?.joinToString(",")
                Log.i(
                    TAG, "✔ ${fn.name} ${EcarxFunctions.hex(fn.id)} ($state) int=${getInt(fn.id, fn.zone)} " +
                        "float=${getFloat(fn.id, fn.zone)}" + (values?.let { "  [giá trị: $it]" } ?: "")
                )
            } else {
                Log.i(TAG, "· ${fn.name} ${EcarxFunctions.hex(fn.id)} ($state)")
            }
        }
        Log.i(TAG, "===== Hỗ trợ $n/${EcarxFunctions.ALL.size} =====")
    }

    private companion object {
        const val TAG = "EcarxCarSource"
        const val CAR_CLASS = "com.ecarx.xui.adaptapi.car.Car"
        const val CAR_IMPL_CLASS = "com.ecarx.xui.adaptapi.car.impl.CarImpl"
        const val CONNECTABLE = "com.ecarx.xui.adaptapi.binder.IConnectable"
        /** Jar AdaptAPI có sẵn trong firmware ECARX (không đóng gói vào APK). */
        val SYSTEM_JARS = listOf(
            "/system/framework/AdapterAPI.jar", "/system/framework/AdapterAPIImpl.jar",
            "/system/framework/ecarx.jar", "/system/framework/ecarxSignalAPI.jar",
        )
        const val CONNECT_WAIT_MS = 5000L
        /** Mã chức năng thấy ở launcher khác nhưng app chưa dùng – chỉ để dò trong báo cáo chẩn đoán. */
        val PROBE_IDS = intArrayOf(
            0x10050300, 0x10060200, 0x10060300, 0x10060600, 0x10100300, 0x10140400, 0x10140500,
            0x20020100, 0x20030600, 0x200a0200, 0x20150300, 0x20150400, 0x20150500, 0x20150600,
            0x21030000, 0x21030300, 0x21030600, 0x28070400, 0x29030500, 0x2a010300, 0x2a040100, 0x2a080101,
        )
        const val MAX_ATTEMPTS = 5
        const val RETRY_MS = 2000L
        /** Kết quả dò hỗ trợ hết hạn sau 30 s (ECU có thể chưa sẵn sàng lúc mới nối). */
        const val SUPPORT_TTL_MS = 30_000L
        /** Giá trị watcher báo chỉ dùng để xác nhận lệnh trong 6 s (giống cache lạc quan AutoEasy). */
        const val WATCH_TTL_MS = 6_000L
        val INT: Class<*> = Int::class.javaPrimitiveType!!
        val FLOAT: Class<*> = Float::class.javaPrimitiveType!!
    }
}

/** Trạng thái đọc từ ECARX để hiển thị; null = xe không hỗ trợ hoặc không đọc được. */
data class EcarxInfo(
    val hvacOn: Boolean? = null,
    val acOn: Boolean? = null,
    val tempC: Float? = null,
    val fanLevel: Int? = null,
    val driveMode: Int? = null,
    val ambientOn: Boolean? = null,
    val autoOn: Boolean? = null,
    /** 1 gió trong, 2 gió ngoài, 3 tự động. */
    val recirc: Int? = null,
    /** Hướng gió: 1 mặt, 2 chân, 3 mặt+chân, 4 kính… */
    val blowing: Int? = null,
    val defrostFront: Boolean? = null,
    val defrostRear: Boolean? = null,
    val seatHeatLeft: Int? = null,
    val seatHeatRight: Int? = null,
    val tempRightC: Float? = null,
) {
    fun lines(): List<String> = buildList {
        val hvac = buildList {
            hvacOn?.let { add(if (it) "BẬT" else "TẮT") }
            if (hvacOn != false) {
                tempC?.let { add(String.format(java.util.Locale.US, "%.1f°C", it)) }
                fanLevel?.takeIf { it > 0 }?.let { add("quạt $it") }
                if (acOn == true) add("A/C")
            }
        }
        if (hvac.isNotEmpty()) add("Điều hoà: " + hvac.joinToString(" · "))
        driveMode?.let { m ->
            EcarxFunctions.DRIVE_MODES.firstOrNull { it.first == m }?.let { add("Chế độ lái: ${it.second}") }
        }
        ambientOn?.let { add("Đèn viền: " + if (it) "bật" else "tắt") }
    }
}
