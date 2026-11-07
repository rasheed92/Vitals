package app.vitals

import android.bluetooth.BluetoothDevice
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.util.Locale

/** Reply to a UDS ReadDataByIdentifier (0x22). */
sealed class Uds {
    class Positive(val data: IntArray) : Uds()
    class Negative(val nrc: Int) : Uds()
    object None : Uds()

    companion object {
        private val HEX = Regex("^[0-9A-F]+$")

        /** ELM output (headers off, spaces off) -> result. Handles "014 / 0:… 1:…" multi-frame. */
        fun parse(resp: String, did: Int): Uds {
            val lines = resp.uppercase(Locale.US).lines().map { it.replace(" ", "").trim() }
                .filter { it.isNotEmpty() }
                .filterNot { it.startsWith("7F2278") } // response pending
            if (lines.isEmpty()) return None
            val hex = if (lines.size > 1 && lines[0].length == 3 && HEX.matches(lines[0]) && lines[1].contains(':')) {
                val len = lines[0].toInt(16)
                lines.drop(1).filter { it.contains(':') }.joinToString("") { it.substringAfter(':') }.take(len * 2)
            } else lines.firstOrNull { HEX.matches(it) } ?: return None
            if (hex.length % 2 != 0) return None
            val b = IntArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16) }
            if (b.size >= 3 && b[0] == 0x7F && b[1] == 0x22) return Negative(b[2])
            if (b.size >= 3 && b[0] == 0x62 && ((b[1] shl 8) or b[2]) == did) return Positive(b.copyOfRange(3, b.size))
            return None
        }
    }
}

/** No reply before the timeout. Not fatal: the link is still up. */
class ElmTimeout(msg: String) : Exception(msg)

/**
 * ELM327/STN command layer over BLE (OBDLink CX).
 * One command at a time: the mutex lets the live poller and the console share the link.
 */
class ElmConnection private constructor(private val t: BleTransport) : Closeable {
    private val mutex = Mutex()

    val gattInfo: String get() = t.description

    companion object {
        suspend fun open(ctx: Context, device: BluetoothDevice, onStep: (String) -> Unit = {}): ElmConnection {
            val t = try {
                BleTransport.connect(ctx, device, onStep)
            } catch (e: BondFailed) {
                throw e
            } catch (e: IOException) {
                // GATT error 133 on the first attempt is common on Android; one retry clears most.
                delay(800)
                BleTransport.connect(ctx, device, onStep)
            }
            return ElmConnection(t)
        }
    }

    suspend fun send(cmd: String, timeoutMs: Long = 1500): String = mutex.withLock { raw(cmd, timeoutMs) }

    /** One command, caller must hold [mutex]. */
    private suspend fun raw(cmd: String, timeoutMs: Long): String = withContext(Dispatchers.IO) {
        t.clearInput()
        t.write((cmd + "\r").toByteArray(Charsets.US_ASCII))

        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) throw ElmTimeout("ماكو رد على $cmd")
            val c = t.readByte(left)
            if (c < 0) continue
            if (c == '>'.code) break
            sb.append(c.toChar())
        }
        sb.toString().replace("\r", "\n").lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != cmd && !it.startsWith("SEARCHING") }
            .joinToString("\n")
    }

    /**
     * Talks to one BMW ECU over UDS with BMW extended addressing (tester F1, request id 6F1,
     * reply id 600+ecu). Holds the link for the whole block, then puts the adapter back into
     * plain OBD mode so the live poller keeps working. [ecu]: 0x12 DME, 0x18 EGS.
     * Read-only use: the block only gets [read] for service 0x22.
     */
    /**
     * Talks to one BMW ECU over UDS with BMW extended addressing (tester F1, request id 6F1,
     * reply id 600+ecu), then puts the adapter back into plain OBD mode. [ecu]: 0x12 DME, 0x18 EGS.
     * Read-only: [read] is service 0x22; [obd] briefly drops back to standard addressing to read
     * mode-01 PIDs (the DME does not answer F4xx over BMW addressing) and returns to the ECU.
     */
    suspend fun <T> bmw(
        ecu: Int,
        block: suspend (read: suspend (did: Int) -> Uds, obd: suspend (pids: List<Int>) -> Map<Int, IntArray>) -> T,
    ): T = mutex.withLock {
        val e = String.format(Locale.US, "%02X", ecu)
        val enter = listOf("ATSH6F1", "ATCEA$e", "ATCRA6$e", "ATFCSH6F1", "ATFCSD${e}300000", "ATFCSM1")
        val leave = listOf("ATFCSM0", "ATCEA", "ATCRA", "ATSH7DF")
        suspend fun run(cmds: List<String>) { for (c in cmds) try { raw(c, 1500) } catch (_: ElmTimeout) {} }
        run(enter)
        try {
            block(
                read@{ did ->
                    val r = try {
                        raw(String.format(Locale.US, "22%04X", did), 1200)
                    } catch (_: ElmTimeout) {
                        return@read Uds.None
                    }
                    Uds.parse(r, did)
                },
                { pids ->
                    val out = HashMap<Int, IntArray>()
                    run(leave)
                    try {
                        for (p in pids) {
                            val r = try { raw(String.format(Locale.US, "01%02X1", p), 1200) } catch (_: ElmTimeout) { continue }
                            parse01(r, p)?.let { out[p] = it }
                        }
                    } finally {
                        run(enter)
                    }
                    out
                },
            )
        } finally {
            run(leave)
        }
    }

    private fun parse01(resp: String, pid: Int): IntArray? {
        val hex = String.format(Locale.US, "%02X", pid)
        val line = resp.lines().map { it.replace(" ", "") }
            .firstOrNull { it.startsWith("41$hex") } ?: return null
        val data = line.substring(4)
        if (data.isEmpty() || data.length % 2 != 0) return null
        return try {
            IntArray(data.length / 2) { data.substring(it * 2, it * 2 + 2).toInt(16) }
        } catch (e: NumberFormatException) {
            null
        }
    }

    suspend fun init() {
        try { send("ATZ", 3000) } catch (_: ElmTimeout) {}
        delay(300)
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP6")) send(c)
        val r = send("0100", 6000)
        if (!r.replace(" ", "").contains("4100")) {
            throw IOException("السيارة ما ردّت، شغّل المحرك وجرّب. الرد: $r")
        }
    }

    /** Mode 01 request. Returns the data bytes after "41 xx", or null if unsupported/no reply. */
    suspend fun query(pid: Int): IntArray? {
        // Trailing "1" = expect one response, so the adapter doesn't wait for more ECUs.
        val resp = try { send(String.format(Locale.US, "01%02X1", pid)) } catch (e: ElmTimeout) { return null }
        return parse01(resp, pid)
    }

    /**
     * Mode 06 on-board monitor results for one MID, as tid -> value.
     * MIDs A2..A5 are misfire per cylinder: TID 0B = average of last 10 driving cycles,
     * TID 0C = count in the current driving cycle.
     */
    suspend fun mode06(mid: Int): Map<Int, Int>? {
        val resp = try { send(String.format(Locale.US, "06%02X", mid), 2000) } catch (e: ElmTimeout) { return null }
        val lines = resp.uppercase(Locale.US).lines().map { it.replace(" ", "").trim() }.filter { it.isNotEmpty() }
        val hex = if (lines.size > 1 && lines[0].length == 3 && lines[1].contains(':')) {
            val len = lines[0].toIntOrNull(16) ?: return null
            lines.drop(1).filter { it.contains(':') }.joinToString("") { it.substringAfter(':') }.take(len * 2)
        } else lines.firstOrNull { it.startsWith("46") } ?: return null
        if (!hex.startsWith("46") || hex.length % 2 != 0) return null
        val b = try { IntArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16) } } catch (e: NumberFormatException) { return null }
        val out = HashMap<Int, Int>()
        var i = 1
        while (i + 8 < b.size) { // MID TID UAS value(2) min(2) max(2)
            if (b[i] == mid) out[b[i + 1]] = (b[i + 3] shl 8) or b[i + 4]
            i += 9
        }
        return out
    }

    /** Walks PID 00, 20, 40… bitmasks and returns every supported mode-01 PID. */
    suspend fun scanSupported(): Set<Int> {
        val set = mutableSetOf<Int>()
        var base = 0x00
        while (base <= 0xC0) {
            val b = query(base) ?: break
            if (b.size < 4) break
            val mask = (b[0].toLong() shl 24) or (b[1].toLong() shl 16) or (b[2].toLong() shl 8) or b[3].toLong()
            for (i in 0 until 32) if (mask and (1L shl (31 - i)) != 0L) set.add(base + i + 1)
            if (base + 0x20 !in set) break
            base += 0x20
        }
        return set
    }

    override fun close() = t.close()
}
