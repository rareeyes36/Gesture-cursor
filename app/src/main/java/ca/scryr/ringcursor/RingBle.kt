package ca.scryr.ringcursor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.UUID

/** One HID boot-protocol mouse report. dx/dy are signed 8-bit deltas. */
data class MouseReport(val buttons: Int, val dx: Int, val dy: Int, val wheel: Int) {
    val left get() = buttons and 0x01 != 0
    val right get() = buttons and 0x02 != 0
    val middle get() = buttons and 0x04 != 0
}

/**
 * What actually happened when we tried the boot-mouse route. The whole point
 * of v0.1 is to resolve this enum on real hardware.
 */
enum class ProbeState {
    IDLE,
    SCANNING,
    CONNECTED,
    /** Protocol Mode write accepted and read back as 0x00. Waiting for reports. */
    BOOT_MODE_SET,
    /** At least one non-zero 0x2A33 notification arrived. The hypothesis holds. */
    BOOT_MOUSE_LIVE,
    /** Boot mode set, subscribed, but nothing ever arrived. Firmware stubbed it. */
    BOOT_MOUSE_SILENT,
    /** Could not even write Protocol Mode. */
    BOOT_MODE_REFUSED,
    /**
     * The CCCD write on Boot Mouse Input (0x2A33) was rejected by the device.
     * Confirmed on Manridy R6 firmware 3.00: status 13 on 0x2A33 and 0x2A22
     * while every non-HID CCCD write on the same connection returns 0.
     */
    BOOT_SUBSCRIBE_REFUSED,
    FAILED
}

@SuppressLint("MissingPermission")
class RingBle(
    private val ctx: Context,
    private val onMouse: (MouseReport) -> Unit,
    private val onState: (ProbeState) -> Unit
) {

    private val main = Handler(Looper.getMainLooper())
    private val queue = GattQueue()

    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var mouseReportCount = 0
    private var nonZeroMouseCount = 0

    // FEC7 command probe.
    private var probing = false
    private var probeOpcode = -1
    private var probeWidth = 0
    private var probeSentAt = 0L
    private var probeResponses = 0

    /** Auto-reconnect: the R6 drops the link every minute or two on its own. */
    var autoReconnect = true
    private var reconnectAttempts = 0

    /**
     * Tag a notification with whichever opcode was written just before it.
     * Without this correlation the response stream is unattributable noise.
     */
    private fun probeTag(): String {
        if (probeOpcode < 0) return ""
        val dt = SystemClock.uptimeMillis() - probeSentAt
        if (dt > 2500) return ""
        probeResponses++
        return "   <== ${dt}ms after FEC7 0x%02X (%dB)".format(probeOpcode, probeWidth)
    }

    var state: ProbeState = ProbeState.IDLE
        private set(v) {
            field = v
            RingLog.i("state -> $v")
            main.post { onState(v) }
        }

    private val adapter: BluetoothAdapter? by lazy {
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    // -----------------------------------------------------------------------
    // Scan
    // -----------------------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device ?: return
            val name = try { dev.name } catch (t: Throwable) { null }
            if (name == RingUuids.ADVERTISED_NAME || dev.address == RingUuids.KNOWN_MAC) {
                RingLog.i("found ${dev.address} name=$name rssi=${result.rssi}")
                stopScan()
                connect(dev)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            RingLog.e("scan failed, code=$errorCode")
            state = ProbeState.FAILED
        }
    }

    fun start() {
        val a = adapter
        if (a == null || !a.isEnabled) {
            RingLog.e("bluetooth adapter unavailable or off")
            state = ProbeState.FAILED
            return
        }

        // Try the bonded list first. If the ring has ever been paired, this
        // skips scanning entirely and is far more reliable.
        a.bondedDevices?.firstOrNull {
            it.address == RingUuids.KNOWN_MAC || it.name == RingUuids.ADVERTISED_NAME
        }?.let {
            RingLog.i("using bonded device ${it.address}")
            RingLog.e(
                "NOTE: device is BONDED. Android's HID host is a second GATT client " +
                    "and will contend for Protocol Mode. Forget the device to test cleanly."
            )
            connect(it)
            return
        }

        val scanner = a.bluetoothLeScanner
        if (scanner == null) {
            RingLog.e("no LE scanner")
            state = ProbeState.FAILED
            return
        }
        state = ProbeState.SCANNING
        scanning = true
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        // Deliberately unfiltered: the R6 does not advertise its service UUIDs,
        // so a ScanFilter on 0x1812 finds nothing. Filter by name in the callback.
        scanner.startScan(emptyList<ScanFilter>(), settings, scanCallback)
        RingLog.i("scanning for '${RingUuids.ADVERTISED_NAME}' / ${RingUuids.KNOWN_MAC}")

        main.postDelayed({
            if (scanning) {
                stopScan()
                RingLog.e("scan timed out after 20s")
                state = ProbeState.FAILED
            }
        }, 20_000)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (t: Throwable) {
            RingLog.e("stopScan: ${t.message}")
        }
    }

    fun stop() {
        autoReconnect = false
        probing = false
        stopScan()
        queue.clear()
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (t: Throwable) {
            RingLog.e("close: ${t.message}")
        }
        gatt = null
        state = ProbeState.IDLE
    }

    // -----------------------------------------------------------------------
    // Connect
    // -----------------------------------------------------------------------

    private fun connect(dev: BluetoothDevice) {
        RingLog.i("connectGatt ${dev.address}")
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            dev.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            dev.connectGatt(ctx, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                RingLog.i("connected (status=$status)")
                reconnectAttempts = 0
                state = ProbeState.CONNECTED

                // The ring advertises a 125 ms preferred interval with latency 4,
                // i.e. up to 625 ms between report opportunities. Unusable for a
                // pointer. Ask for the ~11.25-15 ms high-priority interval.
                // The peripheral may refuse; nothing we can do about that.
                val ok = g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                RingLog.i("requestConnectionPriority(HIGH) accepted=$ok")

                // Small delay before discovery. Cheap peripherals frequently drop
                // a discovery request issued in the same tick as the connection.
                main.postDelayed({ g.discoverServices() }, 600)
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                RingLog.e("disconnected (status=$status)")
                queue.clear()
                state = ProbeState.IDLE
                try { g.close() } catch (t: Throwable) { /* already gone */ }
                gatt = null

                if (autoReconnect) {
                    // Back off, but stay responsive: the ring sleeps and wakes
                    // constantly, and a probe sweep outlives several cycles.
                    val delay = (1000L shl reconnectAttempts.coerceAtMost(4)).coerceAtMost(15_000L)
                    reconnectAttempts++
                    RingLog.i("reconnecting in ${delay}ms (attempt $reconnectAttempts)")
                    main.postDelayed({ if (autoReconnect) start() }, delay)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            RingLog.i("services discovered, status=$status")
            dumpTree(g)
            setUp(g)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int
        ) {
            handleRead(ch.uuid, ch.value ?: ByteArray(0), status)
            queue.complete()
        }

        // API 33+ overload
        override fun onCharacteristicRead(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int
        ) {
            handleRead(ch.uuid, value, status)
            queue.complete()
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int
        ) {
            RingLog.d("write ${short(ch.uuid)} status=$status")
            queue.complete()
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int
        ) {
            val uuid = d.characteristic.uuid
            RingLog.d("cccd ${short(uuid)} status=$status")

            // A failed CCCD write on the boot reports is the whole answer, so it
            // must reach ProbeState rather than only the log. Previously this
            // status was discarded and the probe could report BOOT_MODE_SET
            // while nothing was actually subscribed.
            if (status != BluetoothGatt.GATT_SUCCESS &&
                (uuid == RingUuids.BOOT_MOUSE_INPUT || uuid == RingUuids.BOOT_KEYBOARD_INPUT)
            ) {
                RingLog.e(
                    "subscribe REFUSED on ${short(uuid)} (status=$status). The firmware " +
                        "exposes this boot report but will not enable notifications on it."
                )
                if (uuid == RingUuids.BOOT_MOUSE_INPUT) {
                    state = ProbeState.BOOT_SUBSCRIBE_REFUSED
                }
            }
            queue.complete()
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic
        ) {
            handleNotify(ch.uuid, ch.value ?: ByteArray(0))
        }

        // API 33+ overload
        override fun onCharacteristicChanged(
            g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray
        ) {
            handleNotify(ch.uuid, value)
        }
    }

    // -----------------------------------------------------------------------
    // Setup sequence
    // -----------------------------------------------------------------------

    private fun setUp(g: BluetoothGatt) {
        val hid = g.getService(RingUuids.HID_SERVICE)
        if (hid == null) {
            RingLog.e("no HID service - this is not the expected device")
            state = ProbeState.FAILED
            return
        }

        val protocolMode = hid.getCharacteristic(RingUuids.PROTOCOL_MODE)
        val bootMouse = hid.getCharacteristic(RingUuids.BOOT_MOUSE_INPUT)
        val bootKb = hid.getCharacteristic(RingUuids.BOOT_KEYBOARD_INPUT)

        if (protocolMode == null || bootMouse == null) {
            RingLog.e("HID service lacks Protocol Mode or Boot Mouse Input")
            state = ProbeState.BOOT_MODE_REFUSED
            return
        }

        // --- THE EXPERIMENT ---------------------------------------------
        // 1. Write 0x00 to Protocol Mode (0x2A4E) -> Boot Protocol Mode.
        //    Write-without-response. Not on the AOSP HID blocklist.
        // 2. Subscribe to Boot Mouse Input Report (0x2A33). Also not blocked.
        // 3. If deltas arrive, we have a cursor with no root and no
        //    BLUETOOTH_PRIVILEGED.
        // ----------------------------------------------------------------
        queue.enqueue("write ProtocolMode=0x00 (boot)") {
            g.writeCharCompat(
                protocolMode,
                byteArrayOf(0x00),
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            )
        }
        queue.enqueue("read back ProtocolMode") { g.readCharacteristic(protocolMode) }
        queue.enqueue("notify BootMouseInput") { subscribe(g, bootMouse, indicate = false) }

        if (bootKb != null) {
            queue.enqueue("notify BootKeyboardInput") { subscribe(g, bootKb, indicate = false) }
        }

        // --- Diagnostics: everything else we can legally listen to -------
        g.getService(RingUuids.VENDOR_FEE7)?.let { v ->
            v.getCharacteristic(RingUuids.FEA1_NOTIFY)?.let { c ->
                queue.enqueue("notify FEA1") { subscribe(g, c, indicate = false) }
            }
            v.getCharacteristic(RingUuids.FEC8_INDICATE)?.let { c ->
                queue.enqueue("indicate FEC8") { subscribe(g, c, indicate = true) }
            }
            v.getCharacteristic(RingUuids.FEA2_CONTROL)?.let { c ->
                queue.enqueue("read FEA2") { g.readCharacteristic(c) }
            }
            v.getCharacteristic(RingUuids.FEC9_DEVICE_ID)?.let { c ->
                queue.enqueue("read FEC9") { g.readCharacteristic(c) }
            }
        }

        g.getService(RingUuids.TI_SERVICE)?.getCharacteristic(RingUuids.TI_NOTIFY)?.let { c ->
            queue.enqueue("notify TI efe3") { subscribe(g, c, indicate = false) }
        }

        g.getService(RingUuids.BATTERY_SERVICE)?.getCharacteristic(RingUuids.BATTERY_LEVEL)
            ?.let { c ->
                queue.enqueue("notify Battery") { subscribe(g, c, indicate = false) }
                queue.enqueue("read Battery") { g.readCharacteristic(c) }
            }

        // Prove the blocklist is real, in this app's own log, once.
        hid.getCharacteristic(RingUuids.REPORT_MAP)?.let { c ->
            queue.enqueue("read ReportMap (expected to fail)") { g.readCharacteristic(c) }
        }

        // Watchdog: if the ring never speaks boot mouse, say so plainly rather
        // than leaving the user staring at a dead cursor.
        main.postDelayed({
            if (state == ProbeState.BOOT_MODE_SET && mouseReportCount == 0) {
                RingLog.e(
                    "no 0x2A33 notifications in 25s. Boot Protocol Mode is probably " +
                        "declared but not implemented in this firmware."
                )
                state = ProbeState.BOOT_MOUSE_SILENT
            }
        }, 25_000)
    }

    // -----------------------------------------------------------------------
    // FEC7 command probe
    //
    // Every characteristic read so far is passive. FEC7 is the vendor command
    // input and nothing has ever been written to it. If the motion sensor is
    // dormant until the vendor app enables it, the opcode that does so is here.
    // -----------------------------------------------------------------------

    fun stopProbe() {
        probing = false
        probeOpcode = -1
        RingLog.i("=== FEC7 probe stopped ===")
    }

    fun isProbing(): Boolean = probing

    /**
     * Walk opcodes 0x00..0xFF on FEC7, twice: once as a bare byte and once as a
     * 20-byte padded frame, since vendors in this family use both shapes.
     * Everything arriving within 2.5s of a write is tagged with that opcode.
     */
    fun startProbe(gapMs: Long = 260L) {
        if (probing) { RingLog.e("probe already running"); return }
        val g = gatt
        if (g == null) { RingLog.e("probe: not connected"); return }
        val ch = g.getService(RingUuids.VENDOR_FEE7)?.getCharacteristic(RingUuids.FEC7_WRITE)
        if (ch == null) { RingLog.e("probe: FEC7 not found"); return }

        probing = true
        probeResponses = 0
        RingLog.i("=== FEC7 probe start: 0x00..0xFF as 1-byte then 20-byte, ${gapMs}ms apart ===")
        RingLog.i("=== this writes unknown vendor commands; settings may change ===")

        var width = 1
        var op = 0

        fun step() {
            if (!probing) return
            if (op > 0xFF) {
                if (width == 1) {
                    width = 20
                    op = 0
                    RingLog.i("=== switching to 20-byte frames ===")
                } else {
                    probing = false
                    probeOpcode = -1
                    RingLog.i("=== FEC7 probe done: $probeResponses tagged responses ===")
                    return
                }
            }
            val live = gatt
            val c = live?.getService(RingUuids.VENDOR_FEE7)
                ?.getCharacteristic(RingUuids.FEC7_WRITE)
            if (live == null || c == null) {
                // Dropped mid-sweep; wait for auto-reconnect and resume.
                RingLog.e("probe: link down at 0x%02X, waiting".format(op))
                main.postDelayed({ step() }, 2000)
                return
            }

            val cur = op
            val w = width
            probeOpcode = cur
            probeWidth = w
            probeSentAt = SystemClock.uptimeMillis()
            val payload = if (w == 1) {
                byteArrayOf(cur.toByte())
            } else {
                ByteArray(20).also { it[0] = cur.toByte() }
            }
            queue.enqueue("FEC7 <- 0x%02X (%dB)".format(cur, w)) {
                live.writeCharCompat(c, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            }
            if (cur % 0x20 == 0) RingLog.i("probe at 0x%02X (%dB)".format(cur, w))
            op++
            main.postDelayed({ step() }, gapMs)
        }
        step()
    }

    private fun subscribe(
        g: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        indicate: Boolean
    ): Boolean {
        if (!g.setCharacteristicNotification(ch, true)) {
            RingLog.e("setCharacteristicNotification refused for ${short(ch.uuid)}")
            return false
        }
        val cccd = ch.getDescriptor(RingUuids.CCCD)
        if (cccd == null) {
            RingLog.e("no CCCD on ${short(ch.uuid)}")
            return false
        }
        val value = if (indicate) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        return g.writeDescCompat(cccd, value)
    }

    // -----------------------------------------------------------------------
    // Inbound data
    // -----------------------------------------------------------------------

    private fun handleRead(uuid: UUID, value: ByteArray, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            RingLog.e("read ${short(uuid)} failed status=$status")
            return
        }
        when (uuid) {
            RingUuids.PROTOCOL_MODE -> {
                val mode = if (value.isNotEmpty()) value.u8(0) else -1
                RingLog.i("ProtocolMode reads back as 0x%02X".format(mode))
                state = if (mode == 0x00) {
                    ProbeState.BOOT_MODE_SET
                } else {
                    RingLog.e("device stayed in Report Protocol Mode. Is it also bonded as an HID device? The system HID host will fight us for this.")
                    ProbeState.BOOT_MODE_REFUSED
                }
            }

            RingUuids.FEA2_CONTROL -> {
                RingLog.i("FEA2 = ${value.hex()}")
                if (value.size >= 3) {
                    // 01-40-1F-00 -> field 0x01, LE u16 = 0x1F40 = 8000.
                    // Hypothesis: daily step goal. Change it in the vendor app
                    // and re-read to confirm.
                    RingLog.i("  field=0x%02X value(u16le)=%d  <- step goal?"
                        .format(value.u8(0), value.u16le(1)))
                }
            }

            RingUuids.FEC9_DEVICE_ID -> RingLog.i("FEC9 (device MAC) = ${value.hex()}")
            RingUuids.BATTERY_LEVEL -> RingLog.i("battery = ${value.u8(0)}%")
            else -> RingLog.d("read ${short(uuid)} = ${value.hex()}")
        }
    }

    private fun handleNotify(uuid: UUID, value: ByteArray) {
        when (uuid) {
            RingUuids.BOOT_MOUSE_INPUT -> onBootMouse(value)

            RingUuids.BOOT_KEYBOARD_INPUT ->
                RingLog.i("BOOT-KB ${value.hex()}")

            RingUuids.FEA1_NOTIFY ->
                RingLog.d("FEA1 ${value.hex()}${probeTag()}")

            RingUuids.FEC8_INDICATE ->
                RingLog.i("FEC8 ${value.hex()}${probeTag()}")

            RingUuids.TI_NOTIFY ->
                RingLog.d("efe3 ${value.hex()}${decodeTi(value)}${probeTag()}")

            RingUuids.BATTERY_LEVEL ->
                RingLog.d("battery ${value.u8(0)}%")

            else -> RingLog.i("notify ${short(uuid)} ${value.hex()}${probeTag()}")
        }
    }

    private fun onBootMouse(v: ByteArray) {
        mouseReportCount++

        // HID boot mouse protocol: [buttons, dx(int8), dy(int8), (wheel(int8))].
        // Some firmwares prepend a report ID even in boot mode, which is out of
        // spec but happens. Handle 3 and 4 bytes; log anything else so we learn.
        val r = when (v.size) {
            3 -> MouseReport(v.u8(0), v.i8(1), v.i8(2), 0)
            4 -> MouseReport(v.u8(0), v.i8(1), v.i8(2), v.i8(3))
            else -> {
                RingLog.e("BOOT-MOUSE unexpected length ${v.size}: ${v.hex()}")
                return
            }
        }

        if (r.dx != 0 || r.dy != 0 || r.buttons != 0 || r.wheel != 0) {
            nonZeroMouseCount++
            if (state != ProbeState.BOOT_MOUSE_LIVE) {
                RingLog.i("BOOT MOUSE IS LIVE - first non-zero report: ${v.hex()}")
                state = ProbeState.BOOT_MOUSE_LIVE
            }
            // Log the first 40 live reports at full fidelity, then go quiet so
            // the buffer is not flooded during normal use.
            if (nonZeroMouseCount <= 40) {
                RingLog.d("BOOT-MOUSE ${v.hex()}  btn=${r.buttons} dx=${r.dx} dy=${r.dy} w=${r.wheel}")
            }
        }

        onMouse(r)
    }

    /** Partial decode of the TI efe3 telemetry frame, from the 2026-09-07 capture. */
    private fun decodeTi(v: ByteArray): String {
        if (v.size < 17 || v.u8(0) != 0x0F || v.u8(1) != 0x06) return ""
        val minute = v.u8(6)
        val second = v.u8(7)
        val value = v.u8(8) or (v.u8(9) shl 8) or (v.u8(10) shl 16) or (v.u8(11) shl 24)
        val index = v.u16le(12)
        return "   [t=%02d:%02d val=%d idx=%d]".format(minute, second, value, index)
    }

    // -----------------------------------------------------------------------

    private fun dumpTree(g: BluetoothGatt) {
        for (s in g.services) {
            RingLog.d("SVC ${s.uuid}")
            for (c in s.characteristics) {
                val p = c.properties
                val flags = buildString {
                    if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) append("R")
                    if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) append("W")
                    if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) append("w")
                    if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) append("N")
                    if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) append("I")
                }
                RingLog.d("   ${short(c.uuid)} [$flags]")
            }
        }
    }

    private fun short(u: UUID): String {
        val s = u.toString()
        return if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb")) {
            "0x" + s.substring(4, 8).uppercase()
        } else {
            s
        }
    }
}
