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
        suspend fun open(ctx: Context, device: BluetoothDevice): ElmConnection {
            val t = try {
                BleTransport.connect(ctx, device)
            } catch (e: IOException) {
                // GATT error 133 on the first attempt is common on Android; one retry clears most.
                delay(800)
                BleTransport.connect(ctx, device)
            }
            return ElmConnection(t)
        }
    }

    suspend fun send(cmd: String, timeoutMs: Long = 1500): String = mutex.withLock {
        withContext(Dispatchers.IO) {
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
        val hex = String.format(Locale.US, "%02X", pid)
        // Trailing "1" = expect one response, so the adapter doesn't wait for more ECUs.
        val resp = try { send("01${hex}1") } catch (e: ElmTimeout) { return null }
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
