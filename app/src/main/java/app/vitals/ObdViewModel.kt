package app.vitals

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.Locale

/**
 * Process-wide engine: one BLE link and one set of live values shared by the phone UI and
 * the Android Auto screen. Lives as long as the app process, not a single screen.
 */
class ObdViewModel private constructor(private val app: Application) {
    companion object {
        @Volatile private var instance: ObdViewModel? = null
        fun get(ctx: Context): ObdViewModel =
            instance ?: synchronized(this) {
                instance ?: ObdViewModel(ctx.applicationContext as Application).also { instance = it }
            }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = app.getSharedPreferences("conn", Context.MODE_PRIVATE)

    var connected by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var status by mutableStateOf("غير متصل"); private set
    var supported by mutableStateOf<Set<Int>>(emptySet()); private set
    val values = mutableStateMapOf<Int, Reading>()
    val log = mutableStateListOf<String>()

    private var conn: ElmConnection? = null
    val fuel = FuelTracker(app)
    private var lastFuelTick = 0L
    private var pollJob: Job? = null

    private val adapter: BluetoothAdapter?
        get() = app.getSystemService(BluetoothManager::class.java)?.adapter

    // --- BLE scan ---
    data class Found(val name: String, val address: String, val rssi: Int)

    val found = mutableStateListOf<Found>()
    var scanning by mutableStateOf(false); private set
    private var scanTimer: Job? = null

    private val scanCb = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            val name = r.device.name ?: r.scanRecord?.deviceName ?: return
            val f = Found(name, r.device.address, r.rssi)
            val i = found.indexOfFirst { it.address == f.address }
            if (i >= 0) found[i] = f else found.add(f)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            status = "فشل البحث (code $errorCode)"
        }
    }

    fun hasBtPermission(): Boolean = requiredPermissions().all {
        app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    /** OBDLink first, then strongest signal. */
    fun sortedFound(): List<Found> = found.sortedWith(
        compareByDescending<Found> { it.name.contains("OBD", ignoreCase = true) }.thenByDescending { it.rssi },
    )

    @SuppressLint("MissingPermission")
    fun startScan() {
        val a = adapter
        if (a == null || !a.isEnabled) { status = "شغّل البلوتوث"; return }
        val s = a.bluetoothLeScanner ?: run { status = "شغّل البلوتوث"; return }
        stopScan()
        found.clear()
        scanning = true
        status = "يدوّر على الأداپتر…"
        s.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb)
        scanTimer = scope.launch {
            delay(12_000)
            stopScan()
            if (!connected && !busy) status = if (found.isEmpty()) "ما لكيت شي، تأكد المحرك شغّال" else "اختار الأداپتر"
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanTimer?.cancel()
        scanTimer = null
        if (!scanning) return
        scanning = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Exception) {}
    }

    fun visibleKeys(): List<Int> = Pids.displayOrder.filter {
        if (it == Pids.BOOST) 0x0B in supported else it in supported
    } + listOf(Pids.TRIP_COST, Pids.KNOCK).filter { it in values } +
        (1..4).map { Pids.MISFIRE + it }.filter { it in values } +
        Pids.bmw.map { it.def.pid }.filter { it in values }

    private var lastMisfire = 0L
    private var knockEma = -1.0
    private var knockSeen = 0L

    /** Fuel quality hint: under part load, a cylinder pulling timing alone means knock. */
    private fun updateKnock() {
        val rpm = values[0x0C]?.value ?: return
        val load = values[0x04]?.value ?: return
        val cyl = (0x2011..0x2014).mapNotNull { values[it]?.value }
        if (cyl.size < 4) { if (Pids.KNOCK !in values) put(Pids.KNOCK, -1.0); return }
        if (rpm !in 1200.0..4500.0 || load !in 35.0..95.0) { if (Pids.KNOCK !in values) put(Pids.KNOCK, -1.0); return }
        val now = System.currentTimeMillis()
        if (now - knockSeen < 1400) return // one sample per DME poll
        knockSeen = now
        val spread = cyl.max() - cyl.min()
        knockEma = if (knockEma < 0) spread else knockEma * 0.85 + spread * 0.15
        put(Pids.KNOCK, knockEma)
    }

    private var lastDme = 0L
    private var lastEgs = 0L

    /** One BMW session per ECU: read its live DIDs and store them as cards. */
    private suspend fun pollBmw(c: ElmConnection, ecu: Int) {
        val list = Pids.bmw.filter { it.ecu == ecu }
        val got = c.bmw(ecu) { read, _ -> list.map { it to (read(it.did) as? Uds.Positive)?.data } }
        for ((p, d) in got) {
            if (d == null || d.isEmpty()) continue
            val bytes = if (p.firstByteOnly) d.copyOfRange(0, 1) else d
            var u = 0L
            for (b in bytes) u = (u shl 8) or b.toLong()
            val bits = bytes.size * 8
            val raw = if (p.signed && u >= (1L shl (bits - 1))) u - (1L shl bits) else u
            var v = p.calc(raw.toDouble())
            if (p.def.pid == Pids.BMW_BOOST) v = (v - (values[0x33]?.value ?: 101.3)) / 100.0
            put(p.def.pid, v)
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        if (busy) return
        busy = true
        stopScan()
        scope.launch {
            try {
                status = "جاري الاتصال…"
                val dev = adapter?.getRemoteDevice(address) ?: error("البلوتوث مطفي")
                val c = ElmConnection.open(app, dev) { status = it }
                conn = c
                addLog("GATT:\n" + c.gattInfo)
                status = "تهيئة الأداپتر…"
                c.init()
                status = "فحص القيم المدعومة…"
                supported = c.scanSupported()
                addLog("PIDs مدعومة: " + supported.sorted().joinToString(" ") { String.format(Locale.US, "%02X", it) })
                val volt = try { c.send("ATRV") } catch (e: Exception) { "" }
                status = "متصل · $volt"
                connected = true
                prefs.edit().putString("address", address).apply()
                startPolling(c)
            } catch (e: Exception) {
                status = "فشل الاتصال: ${e.message}"
                addLog("ERROR ${e.message}")
                conn?.close()
                conn = null
            } finally {
                busy = false
            }
        }
    }

    private fun startPolling(c: ElmConnection) {
        pollJob?.cancel()
        val active = Pids.all.filter { it.pid in supported }
        pollJob = scope.launch {
            var tick = 0
            while (isActive) {
                if (bmwRunning) { delay(200); continue } // scanner owns the link
                for (p in active) {
                    if (!p.fast && tick % 6 != 0) continue // temps change slowly
                    val bytes = try {
                        c.query(p.pid)
                    } catch (e: IOException) {
                        lost(e)
                        return@launch
                    }
                    if (bytes != null && bytes.size >= p.bytes) put(p.pid, p.decode(bytes))
                }
                values[0x0B]?.let { map ->
                    val baro = values[0x33]?.value ?: 101.3
                    put(Pids.BOOST, (map.value - baro) / 100.0)
                }
                val now = System.currentTimeMillis()
                if (lastFuelTick > 0) {
                    fuel.onSample(
                        values[0x0C]?.value, values[0x04]?.value, values[0x0E]?.value,
                        values[0x10]?.value, values[0x0D]?.value, values[0x2F]?.value,
                        (now - lastFuelTick) / 1000.0,
                    )
                }
                lastFuelTick = now
                put(Pids.TRIP_COST, if (fuel.price > 0) fuel.tripCost() else -1.0)
                updateKnock()
                try {
                    if (now - lastDme > 1500) { lastDme = now; pollBmw(c, 0x12) }
                    if (now - lastEgs > 4000) { lastEgs = now; pollBmw(c, 0x18) }
                    if (now - lastMisfire > 10_000) {
                        lastMisfire = now
                        for (n in 1..4) c.mode06(0xA1 + n)?.get(0x0C)?.let { put(Pids.MISFIRE + n, it.toDouble()) }
                    }
                } catch (e: IOException) {
                    lost(e)
                    return@launch
                }
                tick++
                delay(20)
            }
        }
    }

    private fun put(key: Int, v: Double) {
        val old = values[key]
        val hist = ((old?.history ?: emptyList()) + v.toFloat()).takeLast(120)
        values[key] = Reading(v, minOf(old?.min ?: v, v), maxOf(old?.max ?: v, v), hist)
    }

    fun resetMinMax() {
        for ((k, r) in values.toMap()) values[k] = r.copy(min = r.value, max = r.value)
    }

    fun sendRaw(cmd: String) {
        val c = conn ?: return
        val t = cmd.trim().uppercase(Locale.US)
        if (t.isEmpty()) return
        scope.launch {
            addLog("> $t")
            try {
                addLog(c.send(t, 4000).ifEmpty { "(فارغ)" })
            } catch (e: Exception) {
                addLog("ERROR ${e.message}")
            }
        }
    }

    // --- BMW DID scanner (read-only, service 0x22) ---
    data class Hit(val ecu: Int, val did: Int, val data: IntArray) {
        val hex: String get() = data.joinToString(" ") { String.format(Locale.US, "%02X", it) }
        val ascii: String? get() = data.filter { it != 0xFF && it != 0x00 }
            .takeIf { d -> d.size >= 3 && d.all { it in 0x20..0x7E } }
            ?.joinToString("") { it.toChar().toString() }
        val line: String get() = String.format(Locale.US, "%s %04X: %s", ecuName(ecu), did, hex) +
            (ascii?.let { "  \"$it\"" } ?: "")
    }

    var bmwRunning by mutableStateOf(false); private set
    var bmwProgress by mutableStateOf(""); private set
    val bmwHits = mutableStateListOf<Hit>()
    private var bmwJob: Job? = null

    fun bmwScan(ecu: Int, from: Int, to: Int) {
        val c = conn ?: return
        if (bmwRunning || from > to) return
        bmwRunning = true
        bmwJob = scope.launch {
            var did = from
            var neg = 0
            var silent = 0
            try {
                addLog("BMW scan ${ecuName(ecu)} ${hex4(from)}–${hex4(to)}")
                while (did <= to && isActive) {
                    val end = minOf(did + 63, to)
                    c.bmw(ecu) { read, _ ->
                        while (did <= end && isActive) {
                            when (val r = read(did)) {
                                is Uds.Positive -> {
                                    val h = Hit(ecu, did, r.data)
                                    bmwHits.add(h)
                                    addLog(h.line)
                                }
                                is Uds.Negative -> neg++
                                Uds.None -> silent++
                            }
                            did++
                            if (did % 8 == 0) bmwProgress = "${ecuName(ecu)} ${hex4(did)} / ${hex4(to)} · لكى ${bmwHits.count { it.ecu == ecu }}"
                        }
                    }
                    // Nothing at all from the ECU for a while = wrong address / ECU asleep.
                    if (silent >= 64 && neg == 0 && bmwHits.none { it.ecu == ecu }) {
                        addLog("ERROR ${ecuName(ecu)} ما يرد. السيارة شغّالة؟")
                        break
                    }
                }
                bmwProgress = "خلص ${ecuName(ecu)} · لكى ${bmwHits.count { it.ecu == ecu }} قيمة"
            } catch (e: IOException) {
                lost(e)
            } catch (e: Exception) {
                bmwProgress = "توقف: ${e.message}"
            } finally {
                bmwRunning = false
            }
        }
    }

    fun bmwStop() { bmwJob?.cancel() }

    // --- Watch: re-read short DIDs, see which move, and match them against standard OBD values ---
    private class Ref(val name: String, val pid: Int, val fast: Boolean, val decode: (IntArray) -> Double)

    // Standard mode-01 PIDs, read between DID batches so each DID sample has fresh references.
    private val refs = listOf(
        Ref("الدورات rpm", 0x0C, true) { (it[0] * 256 + it[1]) / 4.0 },
        Ref("ضغط المانيفولد kPa", 0x0B, true) { it[0].toDouble() },
        Ref("الثروتل %", 0x11, true) { it[0] * 100 / 255.0 },
        Ref("الحمل %", 0x04, true) { it[0] * 100 / 255.0 },
        Ref("توقيت الإشعال °", 0x0E, true) { it[0] / 2.0 - 64 },
        Ref("MAF g/s", 0x10, true) { (it[0] * 256 + it[1]) / 100.0 },
        Ref("ضغط الرايل bar", 0x23, true) { (it[0] * 256 + it[1]) / 10.0 },
        Ref("حرارة الماي °C", 0x05, false) { it[0] - 40.0 },
        Ref("حرارة الزيت °C", 0x5C, false) { it[0] - 40.0 },
        Ref("حرارة السحب °C", 0x0F, false) { it[0] - 40.0 },
    )

    /** DME DIDs that moved during the first watch on this car (no fresh scan needed). */
    private val knownDme: List<Int> = ("4007 4200 4202 4203 4205 4206 4300 4402 4403 4408 4409 4420 4421 4423 4425 4432 " +
        "4436 4505 4506 4507 4520 4521 4522 4523 4524 4525 452A 452B 452C 452E 4536 4600 4601 4602 4603 4609 4617 " +
        "4650 4651 4653 4654 4659 472C 472E 4807 480B 480C 480D 4810 4812 4A05 4A08 4A09 4A0C 4A13 4A21 4A24 4A25 " +
        "4A29 4A2B 4A2D 4A30 4A31 4A32 4A33 4A37 4A38 4A39 4A3A 4A49 4A4A 4A4B 4A4C 4A4D 4A58 4A59 4A7A 4A7B 4A81 " +
        "4A94 4AAA 4AAB 4AB6 4AB7 4AB8 4ABC 4B24 4B25 4B50 4B51 4B52 4B53 4B58 4B59 4B5A 4B5B 4BCB")
        .split(" ").map { it.toInt(16) }

    fun canWatch(ecu: Int) = ecu == 0x12 || bmwHits.any { it.ecu == ecu }

    var watchReport by mutableStateOf(""); private set

    fun bmwWatch(ecu: Int, rounds: Int = 14) {
        val c = conn ?: return
        if (bmwRunning) return
        val scanned = bmwHits.filter { it.ecu == ecu && it.data.size in 1..4 }.map { it.did }.distinct()
        val cands = scanned.ifEmpty { if (ecu == 0x12) knownDme else emptyList() }
        if (cands.isEmpty()) { bmwProgress = "سوّي فحص ${ecuName(ecu)} أول"; return }
        bmwRunning = true
        watchReport = ""
        bmwJob = scope.launch {
            val n = refs.size
            var last = DoubleArray(n) { Double.NaN }
            val refOk = BooleanArray(n)
            val refMin = DoubleArray(n) { Double.MAX_VALUE }
            val refMax = DoubleArray(n) { -Double.MAX_VALUE }
            val samples = HashMap<Int, MutableList<DoubleArray>>()
            val first = HashMap<Int, Long>()
            val changed = LinkedHashSet<Int>()
            try {
                for (round in 0 until rounds) {
                    if (!isActive) break
                    val full = 3 // read every candidate a few times before keeping only the ones that move
                    val list = if (round < full) cands else cands.filter { it in changed }
                    if (list.isEmpty()) break
                    c.bmw(ecu) { read, obd ->
                        suspend fun readRefs(fastOnly: Boolean): DoubleArray {
                            val want = refs.withIndex().filter { (i, r) -> (!fastOnly || r.fast) && (round == 0 || refOk[i]) }
                            val got = obd(want.map { it.value.pid })
                            val out = last.copyOf()
                            for ((i, r) in want) {
                                val d = got[r.pid] ?: continue
                                try {
                                    out[i] = r.decode(d); refOk[i] = true
                                    refMin[i] = minOf(refMin[i], out[i]); refMax[i] = maxOf(refMax[i], out[i])
                                } catch (_: Exception) {}
                            }
                            last = out
                            return out
                        }
                        var before = readRefs(false)
                        for (chunk in list.chunked(8)) {
                            if (!currentCoroutineContext().isActive) break
                            val vals = chunk.map { did -> did to (read(did) as? Uds.Positive)?.data }
                            val after = readRefs(true)
                            val mid = DoubleArray(n) { i ->
                                val a = before[i]; val b = after[i]
                                if (a.isNaN()) b else if (b.isNaN()) a else (a + b) / 2
                            }
                            for ((did, d) in vals) {
                                if (d == null || d.isEmpty()) continue
                                var u = 0L
                                for (x in d) u = (u shl 8) or x.toLong()
                                val bits = d.size * 8
                                val sg = if (u >= (1L shl (bits - 1))) u - (1L shl bits) else u
                                if (u != first.getOrPut(did) { u }) changed.add(did)
                                samples.getOrPut(did) { mutableListOf() }.add(mid + doubleArrayOf(u.toDouble(), sg.toDouble()))
                            }
                            before = after
                            bmwProgress = "مراقبة ${ecuName(ecu)}: جولة ${round + 1}/$rounds · ${list.size} قيمة · " +
                                "تتغير ${changed.size} · مراجع ${refOk.count { it }}/$n"
                        }
                    }
                }
                watchReport = watchText(ecu, samples, changed, refOk, refMin, refMax)
                addLog(watchReport)
                bmwProgress = "خلصت المراقبة · ${changed.size} قيمة تتغير"
            } catch (e: IOException) {
                lost(e)
            } catch (e: Exception) {
                bmwProgress = "توقف: ${e.message}"
            } finally {
                bmwRunning = false
            }
        }
    }

    private class Fit(val r: Double, val a: Double, val b: Double)

    /** Least squares y = a*x + b with Pearson r, or null if x or y is flat. */
    private fun fit(x: List<Double>, y: List<Double>): Fit? {
        val m = x.size
        if (m < 4) return null
        val mx = x.average(); val my = y.average()
        var sxy = 0.0; var sxx = 0.0; var syy = 0.0
        for (i in 0 until m) {
            val dx = x[i] - mx; val dy = y[i] - my
            sxy += dx * dy; sxx += dx * dx; syy += dy * dy
        }
        if (sxx < 1e-9 || syy < 1e-9) return null
        val a = sxy / sxx
        return Fit(sxy / Math.sqrt(sxx * syy), a, my - a * mx)
    }

    private fun watchText(
        ecu: Int, samples: Map<Int, List<DoubleArray>>, changed: Set<Int>, refOk: BooleanArray,
        refMin: DoubleArray, refMax: DoubleArray,
    ): String =
        buildString {
            val n = refs.size
            append("Vitals BMW watch ${ecuName(ecu)}\n")
            append("refs: ").append(
                refs.withIndex().filter { refOk[it.index] }.joinToString(", ") { (i, r) ->
                    String.format(Locale.US, "%s %.0f–%.0f", r.name, refMin[i], refMax[i])
                }.ifEmpty { "ماكو" },
            ).append('\n')
            for (did in changed.sorted()) {
                val ss = samples[did] ?: continue
                val raw = ss.map { it[n] }
                append(String.format(Locale.US, "%04X  min=%.0f max=%.0f n=%d", did, raw.min(), raw.max(), ss.size))
                var bestName = ""; var best: Fit? = null; var signed = false
                for (i in 0 until n) {
                    if (!refOk[i]) continue
                    for (col in listOf(n, n + 1)) {
                        val pts = ss.filter { !it[i].isNaN() }
                        val f = fit(pts.map { it[col] }, pts.map { it[i] }) ?: continue
                        if (best == null || Math.abs(f.r) > Math.abs(best.r)) { best = f; bestName = refs[i].name; signed = col == n + 1 }
                    }
                }
                best?.let {
                    if (Math.abs(it.r) >= 0.8) append(String.format(Locale.US, "  ≈ %s r=%.2f (%s×%.4g%+.4g)",
                        bestName, it.r, if (signed) "s" else "u", it.a, it.b))
                }
                append('\n')
            }
        }

    fun bmwReport(): String = buildString {
        if (watchReport.isNotEmpty()) append(watchReport).append('\n')
        append("Vitals BMW scan\n")
        for (h in bmwHits) append(h.line).append('\n')
    }

    private fun lost(e: Exception) {
        addLog("ERROR ${e.message}")
        disconnect(user = false)
        status = "انقطع الاتصال، يرجع يتصل تلقائياً"
    }

    /** [user] = the driver pressed Disconnect: stop auto-reconnect until they pick a device. */
    fun disconnect(user: Boolean = true) {
        if (user) userStopped = true
        fuel.save()
        lastFuelTick = 0L
        bmwJob?.cancel()
        pollJob?.cancel()
        pollJob = null
        conn?.close()
        conn = null
        connected = false
        values.clear()
        status = "غير متصل"
    }

    private fun addLog(s: String) {
        log.add(s)
        while (log.size > 400) log.removeAt(0)
    }

    // --- auto-connect to the last adapter ---
    val savedAddress: String? get() = prefs.getString("address", null)
    val savedName: String? get() = prefs.getString("name", null)
    private var userStopped = false
    private var autoJob: Job? = null

    /** Keep trying the saved adapter while nothing is connected. Safe to call repeatedly. */
    fun ensureAutoConnect() {
        if (autoJob?.isActive == true) return
        autoJob = scope.launch {
            while (isActive) {
                val addr = savedAddress
                if (addr != null && !connected && !busy && !userStopped && !bmwRunning && hasBtPermission() &&
                    adapter?.isEnabled == true
                ) {
                    status = "يتصل تلقائياً بـ ${savedName ?: "الأداپتر"}…"
                    connect(addr)
                }
                delay(15_000)
            }
        }
    }

    /** Picking a device by hand re-enables auto-connect. */
    fun connectManually(address: String, name: String) {
        userStopped = false
        prefs.edit().putString("name", name).apply()
        connect(address)
    }
}

fun ecuName(ecu: Int) = when (ecu) { 0x12 -> "DME"; 0x18 -> "EGS"; else -> String.format(Locale.US, "%02X", ecu) }
fun hex4(v: Int) = String.format(Locale.US, "%04X", v)

fun requiredPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
