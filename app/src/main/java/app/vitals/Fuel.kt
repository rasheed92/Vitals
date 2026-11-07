package app.vitals

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fuel quality per tank + trip / monthly cost.
 *
 * Quality: the DME pulls ignition timing when it detects knock, and poor fuel knocks more.
 * Raw timing depends on rpm and load, so each sample is compared with the car's own
 * average timing for that rpm/load cell (learned across all tanks). A tank's score is the
 * mean difference in degrees: negative = the ECU runs less advance than usual = worse fuel.
 *
 * Refuel: fuel level jumps by [REFUEL_JUMP] points or more versus the last saved level.
 */
class FuelTracker(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("fuel", Context.MODE_PRIVATE)

    class Tank(val start: Long, var station: String, var n: Int, var sum: Double) {
        val score: Double? get() = if (n >= MIN_SAMPLES) sum / n else null
        fun json() = JSONObject().put("start", start).put("station", station).put("n", n).put("sum", sum)
    }

    // ---- persisted state ----
    var price by mutableStateOf(prefs.getInt("price", 0)); private set // IQD per liter
    val tanks = mutableStateListOf<Tank>()
    private val baseline = HashMap<String, DoubleArray>() // cell -> [mean, count]
    private var lastLevel = prefs.getFloat("lastLevel", -1f).toDouble()
    private var monthKey = prefs.getString("monthKey", "") ?: ""
    var monthLiters by mutableStateOf(prefs.getFloat("monthLiters", 0f).toDouble()); private set
    var monthKm by mutableStateOf(prefs.getFloat("monthKm", 0f).toDouble()); private set

    // ---- this trip (session) ----
    var tripLiters by mutableStateOf(0.0); private set
    var tripKm by mutableStateOf(0.0); private set
    var idleLiters by mutableStateOf(0.0); private set
    var idleSeconds by mutableStateOf(0.0); private set
    var instantL100 by mutableStateOf<Double?>(null); private set
    var askStation by mutableStateOf(false); private set

    private var lastSave = 0L

    init {
        prefs.getString("tanks", null)?.let { s ->
            val a = JSONArray(s)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                tanks.add(Tank(o.getLong("start"), o.optString("station", ""), o.getInt("n"), o.getDouble("sum")))
            }
        }
        prefs.getString("baseline", null)?.let { s ->
            val o = JSONObject(s)
            for (k in o.keys()) {
                val a = o.getJSONArray(k)
                baseline[k] = doubleArrayOf(a.getDouble(0), a.getDouble(1))
            }
        }
        if (tanks.isEmpty()) tanks.add(Tank(System.currentTimeMillis(), "", 0, 0.0))
    }

    val current: Tank get() = tanks.last()

    /** Previous tank with a score, for the comparison line. */
    val previous: Tank? get() = tanks.dropLast(1).lastOrNull { it.score != null }

    fun setPrice(iqd: Int) {
        price = iqd.coerceAtLeast(0)
        prefs.edit().putInt("price", price).apply()
    }

    fun nameStation(name: String) {
        current.station = name.trim()
        tanks[tanks.lastIndex] = current // trigger recomposition
        askStation = false
        save()
    }

    fun dismissStation() { askStation = false }

    fun renameCurrent() { askStation = true }

    /** Manual "I just filled up" when auto-detection misses it. */
    fun newTank() {
        tanks.add(Tank(System.currentTimeMillis(), "", 0, 0.0))
        askStation = true
        save()
    }

    fun tripCost() = tripLiters * price
    fun monthCost() = monthLiters * price
    fun idleCost() = idleLiters * price

    /**
     * One sample. All values may be null if not supported or not read yet.
     * [dt] seconds since the previous sample.
     */
    fun onSample(rpm: Double?, load: Double?, timing: Double?, mafGs: Double?, speed: Double?, levelPct: Double?, dt: Double) {
        if (dt <= 0 || dt > 5) return
        val running = (rpm ?: 0.0) > 400

        // Refuel: only trust the level with the engine running (the gauge settles once started).
        if (levelPct != null && running) {
            if (lastLevel >= 0 && levelPct - lastLevel >= REFUEL_JUMP) {
                tanks.add(Tank(System.currentTimeMillis(), "", 0, 0.0))
                askStation = true
            }
            lastLevel = levelPct
        }

        // Fuel used from air mass at stoichiometric (14.7:1), petrol ~745 g/L.
        if (mafGs != null && running) {
            val liters = mafGs / 14.7 / 745.0 * dt
            tripLiters += liters
            rollMonth()
            monthLiters += liters
            val v = speed ?: 0.0
            if (v < 2) {
                idleLiters += liters
                idleSeconds += dt
                instantL100 = null
            } else {
                val km = v / 3600.0 * dt
                tripKm += km
                monthKm += km
                instantL100 = (mafGs / 14.7 / 745.0 * 3600.0) / v * 100.0
            }
        }

        // Knock / fuel quality: steady part load only, where timing is knock-limited and stable.
        if (rpm != null && load != null && timing != null && rpm in 1200.0..4500.0 && load in 35.0..95.0) {
            val cell = "${(rpm / 500).toInt()}_${(load / 10).toInt()}"
            val b = baseline.getOrPut(cell) { doubleArrayOf(timing, 0.0) }
            if (b[1] >= 20) {
                current.n++
                current.sum += timing - b[0]
            }
            // Slow learning so one tank can't redefine "normal" for itself.
            val alpha = if (b[1] < 20) 1.0 / (b[1] + 1) else 0.005
            b[0] += (timing - b[0]) * alpha
            b[1] += 1
        }

        val now = System.currentTimeMillis()
        if (now - lastSave > 15_000) save()
    }

    private fun rollMonth() {
        val k = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
        if (k != monthKey) {
            monthKey = k
            monthLiters = 0.0
            monthKm = 0.0
        }
    }

    fun save() {
        lastSave = System.currentTimeMillis()
        val a = JSONArray()
        for (t in tanks.takeLast(30)) a.put(t.json())
        val b = JSONObject()
        for ((k, v) in baseline) b.put(k, JSONArray().put(v[0]).put(v[1]))
        prefs.edit()
            .putString("tanks", a.toString())
            .putString("baseline", b.toString())
            .putFloat("lastLevel", lastLevel.toFloat())
            .putString("monthKey", monthKey)
            .putFloat("monthLiters", monthLiters.toFloat())
            .putFloat("monthKm", monthKm.toFloat())
            .apply()
    }

    companion object {
        const val REFUEL_JUMP = 8.0
        const val MIN_SAMPLES = 150
    }
}
