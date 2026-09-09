package ca.scryr.ringcursor.macro

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * The observation layer and the store, together.
 *
 * Devices and controls are discovered, never configured: the first time a
 * signal arrives from a key nobody has seen before, a row appears. That is the
 * "press the button and watch the table update" behaviour from the brief, and
 * it falls straight out of keying the table on device + control.
 *
 * A process-wide singleton on purpose. The accessibility service writes to it
 * from the input thread and the UI reads it from the main thread, and every
 * mutating entry point is synchronized on the instance.
 */
object Registry {

    private const val PREFS = "overwrite.registry"
    private const val K_DEVICES = "devices"
    private const val K_CONTROLS = "controls"
    private const val K_BINDINGS = "bindings"
    private const val K_TIMING = "timing"
    private const val K_MASTER = "masterBypass"

    private var prefs: SharedPreferences? = null

    private val devices = LinkedHashMap<String, DeviceRow>()
    private val controls = LinkedHashMap<String, ControlRow>()
    private val bindings = LinkedHashMap<String, Binding>()

    @Volatile
    var timing: Timing = Timing()
        private set

    /**
     * Global off switch for capture. Nothing is ever swallowed while this is
     * false, whatever the per-device flags say.
     *
     * It deliberately defaults to false and is NOT persisted as true across a
     * fresh install, so a bad binding set cannot lock you out of your own
     * keyboard on first run.
     */
    @Volatile
    var masterBypass: Boolean = false
        private set

    private val listeners = ArrayList<() -> Unit>()

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Synchronized
    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        load(p)
    }

    private fun load(p: SharedPreferences) {
        readArray(p.getString(K_DEVICES, null)) { o ->
            DeviceRow.fromJson(o)?.let { devices[it.key] = it }
        }
        readArray(p.getString(K_CONTROLS, null)) { o ->
            ControlRow.fromJson(o)?.let { controls[it.id] = it }
        }
        readArray(p.getString(K_BINDINGS, null)) { o ->
            Binding.fromJson(o)?.let { bindings[it.id] = it }
        }
        timing = Timing.fromJson(
            p.getString(K_TIMING, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        )
        masterBypass = p.getBoolean(K_MASTER, false)
    }

    private inline fun readArray(raw: String?, each: (JSONObject) -> Unit) {
        if (raw.isNullOrEmpty()) return
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            each(o)
        }
    }

    @Synchronized
    private fun persist() {
        val p = prefs ?: return
        val d = JSONArray(); for (v in devices.values) d.put(v.toJson())
        val c = JSONArray(); for (v in controls.values) c.put(v.toJson())
        val b = JSONArray(); for (v in bindings.values) b.put(v.toJson())
        p.edit()
            .putString(K_DEVICES, d.toString())
            .putString(K_CONTROLS, c.toString())
            .putString(K_BINDINGS, b.toString())
            .putString(K_TIMING, timing.toJson().toString())
            .putBoolean(K_MASTER, masterBypass)
            .apply()
    }

    // ------------------------------------------------------------------
    // Change notification
    // ------------------------------------------------------------------

    @Synchronized
    fun addListener(l: () -> Unit) { listeners.add(l) }

    @Synchronized
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    private fun fire() {
        val snapshot: List<() -> Unit>
        synchronized(this) { snapshot = ArrayList(listeners) }
        for (l in snapshot) runCatching { l() }
    }

    // ------------------------------------------------------------------
    // Observation
    // ------------------------------------------------------------------

    /**
     * Record one signal. Inserts the device and control on first sight, bumps
     * the hit count and timestamp otherwise.
     *
     * @return the control row, so the caller can consult its capture state.
     */
    fun observe(
        deviceKey: String,
        deviceName: String,
        transport: Transport,
        external: Boolean,
        controlKey: String,
        controlLabel: String,
        capture: Capture
    ): ControlRow {
        val now = System.currentTimeMillis()
        val row: ControlRow
        var isNew = false
        synchronized(this) {
            val dev = devices[deviceKey]
            if (dev == null) {
                devices[deviceKey] = DeviceRow(
                    key = deviceKey,
                    name = deviceName,
                    transport = transport,
                    external = external,
                    connected = true,
                    lastSeen = now
                )
                isNew = true
            } else {
                dev.connected = true
                dev.lastSeen = now
                if (dev.name != deviceName && deviceName.isNotEmpty()) dev.name = deviceName
            }

            val id = deviceKey + " " + controlKey
            var c = controls[id]
            if (c == null) {
                c = ControlRow(
                    deviceKey = deviceKey,
                    controlKey = controlKey,
                    label = controlLabel,
                    capture = capture
                )
                controls[id] = c
                isNew = true
            }
            c.capture = capture
            c.hits++
            c.lastSeen = now
            row = c
        }
        // Only a structural change notifies listeners. Firing on every press
        // would make the UI rebuild the whole device list while you type; the
        // indicator dots are repainted from lastSeen on the screen's own tick.
        if (isNew) { persist(); fire() }
        return row
    }

    /** Mark a device connected or gone, without inventing controls for it. */
    fun setConnected(deviceKey: String, connected: Boolean) {
        var changed = false
        synchronized(this) {
            val d = devices[deviceKey]
            if (d != null && d.connected != connected) {
                d.connected = connected
                if (connected) d.lastSeen = System.currentTimeMillis()
                changed = true
            }
        }
        if (changed) fire()
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    @Synchronized
    fun deviceList(): List<DeviceRow> =
        devices.values.sortedWith(
            compareByDescending<DeviceRow> { it.connected }.thenByDescending { it.lastSeen }
        )

    @Synchronized
    fun device(key: String): DeviceRow? = devices[key]

    @Synchronized
    fun controlsFor(deviceKey: String): List<ControlRow> =
        controls.values.filter { it.deviceKey == deviceKey }.sortedByDescending { it.lastSeen }

    @Synchronized
    fun control(deviceKey: String, controlKey: String): ControlRow? =
        controls[deviceKey + " " + controlKey]

    @Synchronized
    fun bindingsFor(deviceKey: String, controlKey: String): List<Binding> =
        bindings.values.filter { it.deviceKey == deviceKey && it.controlKey == controlKey }

    @Synchronized
    fun bindingCount(): Int = bindings.size

    @Synchronized
    fun allBindings(): List<Binding> = ArrayList(bindings.values)

    /**
     * True if this control has any binding at all. The recogniser uses this to
     * decide whether the key is even worth holding on to, which is what keeps
     * unbound keys at zero added latency.
     */
    @Synchronized
    fun hasBinding(deviceKey: String, controlKey: String): Boolean =
        bindings.values.any { it.deviceKey == deviceKey && it.controlKey == controlKey }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    fun putBinding(b: Binding) {
        synchronized(this) { bindings[b.id] = b }
        persist(); fire()
    }

    fun removeBinding(id: String) {
        synchronized(this) { bindings.remove(id) }
        persist(); fire()
    }

    fun setDeviceBypass(deviceKey: String, on: Boolean) {
        synchronized(this) { devices[deviceKey]?.bypass = on }
        persist(); fire()
    }

    fun setControlEnabled(deviceKey: String, controlKey: String, on: Boolean) {
        synchronized(this) { controls[deviceKey + " " + controlKey]?.enabled = on }
        persist(); fire()
    }

    fun setControlLabel(deviceKey: String, controlKey: String, label: String) {
        synchronized(this) { controls[deviceKey + " " + controlKey]?.label = label }
        persist(); fire()
    }

    fun updateMasterBypass(on: Boolean) {
        masterBypass = on
        persist(); fire()
    }

    fun updateTiming(t: Timing) {
        timing = t.sane()
        persist(); fire()
    }

    /**
     * The panic path: drop every capture flag at once. Reachable from a button
     * in the UI, because a binding set that swallows the wrong key is otherwise
     * only recoverable by turning the accessibility service off.
     */
    fun panic() {
        synchronized(this) {
            masterBypass = false
            for (d in devices.values) d.bypass = false
        }
        persist(); fire()
    }

    fun forgetDevice(deviceKey: String) {
        synchronized(this) {
            devices.remove(deviceKey)
            controls.keys.filter { it.startsWith(deviceKey + " ") }.forEach { controls.remove(it) }
            bindings.values.filter { it.deviceKey == deviceKey }.map { it.id }
                .forEach { bindings.remove(it) }
        }
        persist(); fire()
    }

    // ------------------------------------------------------------------
    // Profile import and export
    // ------------------------------------------------------------------

    @Synchronized
    fun exportProfile(): String {
        val d = JSONArray(); for (v in devices.values) d.put(v.toJson())
        val c = JSONArray(); for (v in controls.values) c.put(v.toJson())
        val b = JSONArray(); for (v in bindings.values) b.put(v.toJson())
        val o = JSONObject()
        o.put("version", 1)
        o.put("devices", d)
        o.put("controls", c)
        o.put("bindings", b)
        o.put("timing", timing.toJson())
        return o.toString(2)
    }

    /** Additive: existing rows survive, incoming rows win on conflict. */
    fun importProfile(raw: String): Int {
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return 0
        var n = 0
        synchronized(this) {
            o.optJSONArray("devices")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val row = arr.optJSONObject(i)?.let { DeviceRow.fromJson(it) } ?: continue
                    devices[row.key] = row
                }
            }
            o.optJSONArray("controls")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val row = arr.optJSONObject(i)?.let { ControlRow.fromJson(it) } ?: continue
                    controls[row.id] = row
                }
            }
            o.optJSONArray("bindings")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val row = arr.optJSONObject(i)?.let { Binding.fromJson(it) } ?: continue
                    bindings[row.id] = row
                    n++
                }
            }
            timing = Timing.fromJson(o.optJSONObject("timing"))
        }
        persist(); fire()
        return n
    }
}
