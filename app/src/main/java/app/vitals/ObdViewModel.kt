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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.Locale

class ObdViewModel(app: Application) : AndroidViewModel(app) {
    var connected by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var status by mutableStateOf("غير متصل"); private set
    var supported by mutableStateOf<Set<Int>>(emptySet()); private set
    val values = mutableStateMapOf<Int, Reading>()
    val log = mutableStateListOf<String>()

    private var conn: ElmConnection? = null
    private var pollJob: Job? = null

    private val adapter: BluetoothAdapter?
        get() = getApplication<Application>().getSystemService(BluetoothManager::class.java)?.adapter

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
        getApplication<Application>().checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
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
        scanTimer = viewModelScope.launch {
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
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        if (busy) return
        busy = true
        stopScan()
        viewModelScope.launch {
            try {
                status = "جاري الاتصال…"
                val dev = adapter?.getRemoteDevice(address) ?: error("البلوتوث مطفي")
                val c = ElmConnection.open(getApplication(), dev) { status = it }
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
        pollJob = viewModelScope.launch {
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
        viewModelScope.launch {
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
        bmwJob = viewModelScope.launch {
            var did = from
            var neg = 0
            var silent = 0
            try {
                addLog("BMW scan ${ecuName(ecu)} ${hex4(from)}–${hex4(to)}")
                while (did <= to && isActive) {
                    val end = minOf(did + 63, to)
                    c.bmw(ecu) { read ->
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
    private class Ref(val name: String, val did: Int, val fast: Boolean, val decode: (IntArray) -> Double)

    // UDS F4xx = OBD mode-01 PID xx, readable over BMW addressing, so refs and DIDs share one session.
    private val refs = listOf(
        Ref("الدورات rpm", 0xF40C, true) { (it[0] * 256 + it[1]) / 4.0 },
        Ref("ضغط المانيفولد kPa", 0xF40B, true) { it[0].toDouble() },
        Ref("الثروتل %", 0xF411, true) { it[0] * 100 / 255.0 },
        Ref("الحمل %", 0xF404, true) { it[0] * 100 / 255.0 },
        Ref("حرارة الماي °C", 0xF405, false) { it[0] - 40.0 },
        Ref("حرارة الزيت °C", 0xF45C, false) { it[0] - 40.0 },
        Ref("حرارة السحب °C", 0xF40F, false) { it[0] - 40.0 },
    )

    var watchReport by mutableStateOf(""); private set

    fun bmwWatch(ecu: Int, rounds: Int = 10) {
        val c = conn ?: return
        if (bmwRunning) return
        val cands = bmwHits.filter { it.ecu == ecu && it.data.size in 1..4 }.map { it.did }.distinct()
        if (cands.isEmpty()) { bmwProgress = "سوّي فحص ${ecuName(ecu)} أول"; return }
        bmwRunning = true
        watchReport = ""
        bmwJob = viewModelScope.launch {
            val n = refs.size
            val refVal = DoubleArray(n) { Double.NaN }
            val refOk = BooleanArray(n)
            val samples = HashMap<Int, MutableList<DoubleArray>>()
            val first = HashMap<Int, Long>()
            val changed = LinkedHashSet<Int>()
            try {
                for (round in 0 until rounds) {
                    if (!isActive) break
                    val list = if (round < 2) cands else cands.filter { it in changed }
                    if (list.isEmpty()) break
                    c.bmw(ecu) { read ->
                        suspend fun readRefs(fastOnly: Boolean) {
                            for ((i, r) in refs.withIndex()) {
                                if (fastOnly && !r.fast) continue
                                if (round > 0 && !refOk[i]) continue
                                val res = read(r.did)
                                if (res is Uds.Positive) {
                                    try { refVal[i] = r.decode(res.data); refOk[i] = true } catch (_: Exception) {}
                                }
                            }
                        }
                        readRefs(false)
                        for ((k, did) in list.withIndex()) {
                            if (!currentCoroutineContext().isActive) break
                            if (k > 0 && k % 12 == 0) readRefs(true)
                            val d = (read(did) as? Uds.Positive)?.data ?: continue
                            if (d.isEmpty()) continue
                            var u = 0L
                            for (b in d) u = (u shl 8) or b.toLong()
                            val bits = d.size * 8
                            val sg = if (u >= (1L shl (bits - 1))) u - (1L shl bits) else u
                            if (u != first.getOrPut(did) { u }) changed.add(did)
                            samples.getOrPut(did) { mutableListOf() }
                                .add(refVal.copyOf() + doubleArrayOf(u.toDouble(), sg.toDouble()))
                            if (k % 8 == 0) bmwProgress =
                                "مراقبة ${ecuName(ecu)}: جولة ${round + 1}/$rounds · ${list.size} قيمة · تتغير ${changed.size}"
                        }
                    }
                }
                watchReport = watchText(ecu, samples, changed, refOk)
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

    private fun watchText(ecu: Int, samples: Map<Int, List<DoubleArray>>, changed: Set<Int>, refOk: BooleanArray): String =
        buildString {
            val n = refs.size
            append("Vitals BMW watch ${ecuName(ecu)}\n")
            append("refs: ").append(refs.filterIndexed { i, _ -> refOk[i] }.joinToString(", ") { it.name }.ifEmpty { "ماكو" }).append('\n')
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
        disconnect()
        status = "انقطع الاتصال"
    }

    fun disconnect() {
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

    override fun onCleared() {
        stopScan()
        disconnect()
    }
}

fun ecuName(ecu: Int) = when (ecu) { 0x12 -> "DME"; 0x18 -> "EGS"; else -> String.format(Locale.US, "%02X", ecu) }
fun hex4(v: Int) = String.format(Locale.US, "%04X", v)

fun requiredPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
