package app.vitals

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Pairing failed or was refused. Not worth an automatic retry. */
class BondFailed(msg: String) : IOException(msg)

/**
 * Byte pipe to a BLE OBD adapter (OBDLink CX and other BLE ELM327/STN devices).
 *
 * Fix for "فشل الإرسال (status 13)" = GATT_INVALID_ATTRIBUTE_LENGTH:
 *  1. Writes are always split into 20-byte chunks. Some adapters accept a large MTU
 *     but reject characteristic writes longer than 20 bytes.
 *  2. The data service is chosen from known BLE-UART services first (FFF0, FFE0,
 *     Nordic UART), and only then falls back to "first non-standard service".
 *     This avoids picking a config/firmware-update service that rejects our writes.
 *
 * OBDLink CX: UART service FFF0 (notify FFF1, write FFF2). FEF5 is internal and
 * rejects our writes. The link is always encrypted, so the adapter must be bonded,
 * and it only accepts new bonds in the first 5 minutes after power-on (PIN 123456).
 */
@SuppressLint("MissingPermission")
class BleTransport private constructor() : BluetoothGattCallback(), Closeable {
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private val rx = LinkedBlockingQueue<Byte>()
    private val connected = CompletableDeferred<Unit>()
    private val discovered = CompletableDeferred<Int>()
    @Volatile private var pending: CompletableDeferred<Int>? = null
    @Volatile private var closed = false
    @Volatile private var mtu = 23

    /** Human-readable GATT layout, written to the console log for debugging. */
    var description = ""; private set

    /** Which service/characteristics were picked, for the console log. */
    var picked = ""; private set

    companion object {
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"
        // GAP, GATT, Device Information, Battery
        private val STANDARD = setOf(0x1800, 0x1801, 0x180A, 0x180F)

        /** Known BLE serial (UART) services, in order of preference. */
        private val PREFERRED: List<UUID> = listOf(
            UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"), // Nordic UART
        )

        /** Max bytes per write. 20 = what every BLE device accepts with the default MTU. */
        private const val CHUNK = 20

        suspend fun connect(ctx: Context, device: BluetoothDevice, onStep: (String) -> Unit = {}): BleTransport {
            val t = BleTransport()
            try {
                ensureBonded(ctx, device, onStep)
                onStep("جاري الاتصال…")
                t.setup(ctx, device)
                return t
            } catch (e: Throwable) {
                t.close()
                throw e
            }
        }

        /** The CX only talks over an encrypted link: bond first, with time for the PIN dialog. */
        private suspend fun ensureBonded(ctx: Context, device: BluetoothDevice, onStep: (String) -> Unit) {
            if (device.bondState == BluetoothDevice.BOND_BONDED) return
            onStep("اقتران… وافق على الطلب (الرمز 123456 إذا طلب)")
            val done = CompletableDeferred<Int>()
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    @Suppress("DEPRECATION")
                    val d: BluetoothDevice? = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    if (d?.address != device.address) return
                    val st = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    val prev = i.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)
                    if (st == BluetoothDevice.BOND_BONDED) done.complete(st)
                    else if (st == BluetoothDevice.BOND_NONE && prev == BluetoothDevice.BOND_BONDING) done.complete(st)
                }
            }
            val f = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED)
            else ctx.registerReceiver(r, f)
            try {
                if (device.bondState == BluetoothDevice.BOND_BONDED) return
                if (device.bondState != BluetoothDevice.BOND_BONDING && !device.createBond()) {
                    throw BondFailed(BOND_HINT)
                }
                val st = withTimeoutOrNull(60_000) { done.await() }
                if (st != BluetoothDevice.BOND_BONDED) throw BondFailed(BOND_HINT)
            } finally {
                try { ctx.unregisterReceiver(r) } catch (_: Exception) {}
            }
        }

        private const val BOND_HINT =
            "الاقتران ما تم. الأداپتر يقبل اقتران جديد بس أول 5 دقايق بعد ما ينشغل: " +
                "افصله وارجع ركّبه، وجرّب فوراً، ووافق على الطلب (الرمز 123456)."

        private fun shortId(u: UUID): Int? {
            val s = u.toString()
            return if (s.endsWith(BASE_SUFFIX)) s.substring(4, 8).toInt(16) else null
        }
    }

    private suspend fun setup(ctx: Context, device: BluetoothDevice) {
        val g = device.connectGatt(ctx, false, this, BluetoothDevice.TRANSPORT_LE)
            ?: throw IOException("ما كدرت أفتح اتصال BLE")
        gatt = g
        withTimeoutOrNull(15_000) { connected.await() } ?: throw IOException("الأداپتر ما ردّ على الاتصال")

        if (!g.discoverServices()) throw IOException("فشل فحص خدمات BLE")
        val st = withTimeoutOrNull(10_000) { discovered.await() } ?: throw IOException("فحص الخدمات طوّل")
        if (st != BluetoothGatt.GATT_SUCCESS) throw IOException("فحص الخدمات فشل (status $st)")

        pickCharacteristics(g)
        enableNotifications(g, notifyChar!!)

        // Bigger MTU = fewer notification packets per reply. Best effort; writes stay at 20 bytes.
        try { op("MTU", 3_000) { g.requestMtu(247) } } catch (_: IOException) {}
    }

    private fun isNotify(c: BluetoothGattCharacteristic) =
        c.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0

    private fun isWrite(c: BluetoothGattCharacteristic) =
        c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

    /** Returns true if the service has a usable notify + write pair and selects it. */
    private fun tryService(s: BluetoothGattService): Boolean {
        val n = s.characteristics.firstOrNull { isNotify(it) } ?: return false
        // Prefer a separate write characteristic (e.g. FFF2 next to notify FFF1); else a combined one.
        val w = s.characteristics.firstOrNull { isWrite(it) && it.uuid != n.uuid }
            ?: s.characteristics.firstOrNull { isWrite(it) }
            ?: return false
        notifyChar = n
        writeChar = w
        picked = "svc ${s.uuid}\n  notify ${n.uuid}\n  write  ${w.uuid}"
        return true
    }

    private fun pickCharacteristics(g: BluetoothGatt) {
        val sb = StringBuilder()
        for (s in g.services) {
            sb.append("svc ").append(s.uuid).append('\n')
            for (c in s.characteristics) {
                sb.append("  chr ").append(c.uuid).append(" props=0x").append(Integer.toHexString(c.properties)).append('\n')
            }
        }
        description = sb.toString().trimEnd()

        // 1) Known UART services first.
        for (id in PREFERRED) {
            val s = g.getService(id) ?: continue
            if (tryService(s)) return
        }
        // 2) Fallback: first non-standard service with notify + write.
        for (s in g.services) {
            if (shortId(s.uuid) in STANDARD) continue
            if (tryService(s)) return
        }
        throw IOException("ما لكيت خدمة البيانات بالأداپتر. هل هو أداپتر OBD؟\n$description")
    }

    @Suppress("DEPRECATION")
    private suspend fun enableNotifications(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        if (!g.setCharacteristicNotification(c, true)) throw IOException("فشل تفعيل الإشعارات")
        val d = c.getDescriptor(CCCD) ?: return
        val v = if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        val st = op("تفعيل الإشعارات", 15_000) {
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, v) == BluetoothStatusCodes.SUCCESS
            else { d.value = v; g.writeDescriptor(d) }
        }
        if (st != BluetoothGatt.GATT_SUCCESS) throw IOException("فشل تفعيل الإشعارات (status $st)")
    }

    /** Runs one GATT operation and waits for its callback. */
    private suspend fun op(what: String, timeoutMs: Long = 5_000, start: () -> Boolean): Int {
        val d = CompletableDeferred<Int>()
        pending = d
        if (!start()) {
            pending = null
            throw IOException("$what: انرفضت")
        }
        val r = withTimeoutOrNull(timeoutMs) { d.await() }
        pending = null
        return r ?: throw IOException("$what: طوّلت")
    }

    @Suppress("DEPRECATION")
    suspend fun write(bytes: ByteArray) {
        val g = gatt ?: throw IOException("مو متصل")
        val c = writeChar ?: throw IOException("مو متصل")
        if (closed) throw IOException("انقطع الاتصال بالأداپتر")
        val noResp = c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0
        val type = if (noResp) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        var i = 0
        while (i < bytes.size) {
            val part = bytes.copyOfRange(i, minOf(i + CHUNK, bytes.size))
            val st = op("الإرسال") {
                if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, part, type) == BluetoothStatusCodes.SUCCESS
                else { c.writeType = type; c.value = part; g.writeCharacteristic(c) }
            }
            if (st != BluetoothGatt.GATT_SUCCESS) {
                throw IOException("فشل الإرسال (status $st, ${part.size}B)\n$picked\n\n$description")
            }
            i += CHUNK
        }
    }

    fun clearInput() = rx.clear()

    /** Next byte, or -1 if nothing arrived in time. Throws once the link is gone. */
    fun readByte(timeoutMs: Long): Int {
        val b = rx.poll(timeoutMs.coerceIn(1, 200), TimeUnit.MILLISECONDS)
        if (b != null) return b.toInt() and 0xFF
        if (closed) throw IOException("انقطع الاتصال بالأداپتر")
        return -1
    }

    private fun push(value: ByteArray) {
        for (b in value) rx.offer(b)
    }

    // --- GATT callbacks (binder thread) ---

    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
        if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
            connected.complete(Unit)
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
            closed = true
            val e = IOException("انقطع البلوتوث (status $status)")
            connected.completeExceptionally(e)
            discovered.completeExceptionally(e)
            pending?.completeExceptionally(e)
        }
    }

    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
        discovered.complete(status)
    }

    override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
        pending?.complete(status)
    }

    override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
        pending?.complete(status)
    }

    override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) this.mtu = mtu
        pending?.complete(status)
    }

    // API 33+
    override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
        if (c.uuid == notifyChar?.uuid) push(value)
    }

    // API 26–32
    @Deprecated("Replaced by the 3-argument overload on API 33+")
    @Suppress("DEPRECATION")
    override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        if (Build.VERSION.SDK_INT < 33 && c.uuid == notifyChar?.uuid) push(c.value ?: return)
    }

    override fun close() {
        closed = true
        gatt?.let {
            try { it.disconnect() } catch (_: Exception) {}
            try { it.close() } catch (_: Exception) {}
        }
        gatt = null
    }
}
