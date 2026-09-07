package ca.scryr.ringcursor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque

/**
 * Android's BluetoothGatt allows exactly ONE outstanding operation at a time.
 * Firing read/write/descriptor calls back to back silently drops all but the
 * first, and it is the single most common cause of "my BLE code works on one
 * phone and not another". Everything goes through this queue.
 *
 * Each op runs, then waits for its completion callback (or a timeout) before
 * the next one starts.
 */
class GattQueue(private val timeoutMs: Long = 2500L) {

    private data class Op(val label: String, val run: () -> Boolean)

    private val handler = Handler(Looper.getMainLooper())
    private val pending = ArrayDeque<Op>()
    private var busy = false
    private var current: Op? = null

    private val timeoutRunnable = Runnable {
        current?.let { RingLog.e("gatt op timed out: ${it.label}") }
        complete()
    }

    @Synchronized
    fun enqueue(label: String, run: () -> Boolean) {
        pending.addLast(Op(label, run))
        drain()
    }

    @Synchronized
    fun complete() {
        handler.removeCallbacks(timeoutRunnable)
        busy = false
        current = null
        drain()
    }

    @Synchronized
    fun clear() {
        handler.removeCallbacks(timeoutRunnable)
        pending.clear()
        busy = false
        current = null
    }

    @Synchronized
    private fun drain() {
        if (busy) return
        val op = pending.pollFirst() ?: return
        busy = true
        current = op
        handler.postDelayed(timeoutRunnable, timeoutMs)
        val started = try {
            op.run()
        } catch (t: Throwable) {
            // The blocklisted HID characteristics throw SecurityException here.
            // That is expected and informative, not fatal.
            RingLog.e("${op.label} threw: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
        if (!started) {
            RingLog.e("${op.label} did not start")
            handler.post { complete() }
        }
    }
}

// ---------------------------------------------------------------------------
// API-33 compatibility shims.
// Android 13 replaced the "set value then call write" pattern with explicit
// value parameters and deprecated the old forms. Both are handled here so the
// same APK behaves on Android 8 through 15.
// ---------------------------------------------------------------------------

@SuppressLint("MissingPermission")
fun BluetoothGatt.writeCharCompat(
    ch: BluetoothGattCharacteristic,
    value: ByteArray,
    writeType: Int
): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        writeCharacteristic(ch, value, writeType) == BluetoothGatt.GATT_SUCCESS
    } else {
        @Suppress("DEPRECATION")
        ch.writeType = writeType
        @Suppress("DEPRECATION")
        ch.value = value
        @Suppress("DEPRECATION")
        writeCharacteristic(ch)
    }
}

@SuppressLint("MissingPermission")
fun BluetoothGatt.writeDescCompat(
    desc: BluetoothGattDescriptor,
    value: ByteArray
): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        writeDescriptor(desc, value) == BluetoothGatt.GATT_SUCCESS
    } else {
        @Suppress("DEPRECATION")
        desc.value = value
        @Suppress("DEPRECATION")
        writeDescriptor(desc)
    }
}
