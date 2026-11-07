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

    fun bmwReport(): String = buildString {
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
