package ca.scryr.ringcursor

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.pm.PackageManager
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import ca.scryr.ringcursor.macro.ActionRunner
import ca.scryr.ringcursor.macro.Capture
import ca.scryr.ringcursor.macro.Recognizer
import ca.scryr.ringcursor.macro.Registry
import ca.scryr.ringcursor.macro.Transport
import kotlin.math.abs

/**
 * Everything lives here, on purpose.
 *
 * The obvious alternative architecture was: a foreground Service holding the
 * BLE connection, an AccessibilityService doing the clicking, and a binder or
 * broadcast between them. That was rejected. Both components live in the same
 * process, the accessibility toggle is already the natural on/off switch for
 * the whole feature, and folding BLE into the AccessibilityService removes an
 * entire IPC layer plus a foreground-service notification.
 *
 * The cost is that BLE dies when the user turns the service off, which is
 * exactly what should happen anyway.
 *
 * The overlay window is TYPE_ACCESSIBILITY_OVERLAY created from THIS service's
 * context. That matters for two reasons:
 *   1. It needs no SYSTEM_ALERT_WINDOW grant.
 *   2. Since Android 12, ordinary overlays can cause the system to silently
 *      drop touches underneath them. Accessibility overlays are trusted and
 *      exempt.
 */
class RingCursorService : AccessibilityService() {

    companion object {
        @Volatile
        var probeState: ProbeState = ProbeState.IDLE
            private set

        @Volatile
        var instance: RingCursorService? = null
            private set

        /**
         * Last real-time input event seen from ANY source, and how many.
         * This is the "signal received" indicator: the vendor BLE streams
         * (FEA1 every 5s, efe3 every 12s) are fixed-cadence telemetry counters
         * and can never represent a button press, so the only live input the
         * ring can deliver is through Android's own HID stack once paired.
         */
        @Volatile
        var lastSignal: String = "none yet"
            private set

        @Volatile
        var signalCount: Int = 0
            private set

        /**
         * Legacy probe behaviour: turn any external button press into a
         * scroll. Off by default now that the macro layer owns key events.
         */
        @Volatile
        var keyToScroll: Boolean = false

        /** Long-press threshold. */
        private const val LONG_PRESS_MS = 500L

        /** Movement beyond this during a press turns a tap into a drag. */
        private const val DRAG_SLOP_PX = 24f

        /** How often a held-and-moving press extends the drag stroke. */
        private const val DRAG_STEP_MS = 90L

        internal fun recordSignal(what: String) {
            signalCount++
            lastSignal = what
            RingLog.i("SIGNAL #$signalCount  $what")
        }
    }

    private fun signal(what: String) = recordSignal(what)

    private val main = Handler(Looper.getMainLooper())
    private val engine = PointerEngine()

    private var wm: WindowManager? = null
    private var cursor: CursorView? = null
    private var lp: WindowManager.LayoutParams? = null

    private var ble: RingBle? = null

    // Macro layer
    private var actions: ActionRunner? = null
    private var recognizer: Recognizer? = null

    /**
     * Keys whose DOWN edge we swallowed, so the matching UP is swallowed too.
     * Keyed by device as well as keycode: two keyboards can hold the same
     * keycode down at the same time.
     */
    private val consumedKeys = HashSet<String>()

    // Button / gesture state
    private var lastButtons = 0
    private var pressStartMs = 0L
    private var pressStartX = 0f
    private var pressStartY = 0f
    private var longPressFired = false
    private var drag: DragSession? = null

    private val longPressRunnable = Runnable {
        if (drag == null && !longPressFired) {
            longPressFired = true
            RingLog.i("long press at (${engine.x.toInt()}, ${engine.y.toInt()})")
            dispatchTap(engine.x, engine.y, LONG_PRESS_MS + 60L)
        }
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        RingLog.i("accessibility service connected")

        Registry.init(this)
        val runner = ActionRunner(this, main)
        actions = runner
        recognizer = Recognizer(main) { binding -> runner.run(binding) }
        RingLog.i("macro layer ready: " + Registry.bindingCount() + " binding(s)")

        // Motion events are requested at runtime rather than through
        // android:motionEventSources in the XML: that attribute is not present
        // in this SDK's resource table and fails resource linking, while the
        // AccessibilityServiceInfo setter does exist.
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                val info = serviceInfo
                info.motionEventSources =
                    InputDevice.SOURCE_MOUSE or
                        InputDevice.SOURCE_TOUCHPAD or
                        InputDevice.SOURCE_JOYSTICK or
                        InputDevice.SOURCE_TRACKBALL
                serviceInfo = info
                RingLog.i("motionEventSources set: mouse|touchpad|joystick|trackball")
            } catch (t: Throwable) {
                RingLog.e("could not request motion events: ${t.javaClass.simpleName}: ${t.message}")
            }
        } else {
            RingLog.e("motion events need API 34+; key events still active")
        }

        wm = getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
        addCursor()

        startBle()

        try {
            (getSystemService(Context.INPUT_SERVICE) as InputManager)
                .registerInputDeviceListener(inputListener, main)
            RingLog.i("watching for input devices appearing/disappearing")
        } catch (t: Throwable) {
            RingLog.e("registerInputDeviceListener: ${t.message}")
        }
    }

    /**
     * (Re)open the app's own GATT link. Not needed for HID input, and not
     * needed by the macro layer at all.
     *
     * This is guarded because it runs from onServiceConnected. RingBle.start()
     * touches bondedDevices and startScan, both of which throw SecurityException
     * on API 31+ without BLUETOOTH_CONNECT. Unguarded, turning the accessibility
     * service on without that grant took the whole service down before a single
     * key could be observed, which made the macro layer look broken when the
     * only thing missing was a permission it does not use.
     */
    fun startBle() {
        ble?.stop()
        ble = null

        if (!hasBluetoothPermission()) {
            RingLog.e(
                "BLE skipped: no Bluetooth permission. The macro layer does not need it. " +
                    "Grant it on the Developer screen to run the ring probe."
            )
            return
        }

        try {
            ble = RingBle(
                ctx = this,
                onMouse = ::onMouseReport,
                onState = { st ->
                    probeState = st
                    // Show the pointer as soon as any source can drive it. Gating
                    // this on BOOT_MOUSE_LIVE alone meant that on firmware which
                    // refuses boot mode the overlay stayed invisible forever, with
                    // no way to tell a dead overlay from a dead radio.
                    if (st == ProbeState.BOOT_MOUSE_LIVE) showCursor(true)
                }
            ).also { it.start() }
        } catch (t: Throwable) {
            // Never let the optional radio path kill the service.
            RingLog.e("BLE failed to start: " + t.javaClass.simpleName + ": " + t.message)
            ble = null
        }
    }

    /** BLUETOOTH_CONNECT is only a runtime permission from API 31. */
    private fun hasBluetoothPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun stopBle() {
        ble?.stop()
        ble = null
        RingLog.i("BLE stopped by user")
    }

    fun isBleRunning(): Boolean = ble != null

    /** Sweep vendor opcodes on FEC7 and correlate every response. */
    fun toggleProbe(): Boolean {
        val b = ble
        if (b == null) {
            RingLog.e("probe: BLE not running - press 'BLE: reconnect' first")
            return false
        }
        if (b.isProbing()) {
            b.stopProbe()
            return false
        }
        b.startProbe()
        return true
    }

    fun isProbing(): Boolean = ble?.isProbing() == true

    override fun onDestroy() {
        RingLog.i("accessibility service destroyed")
        instance = null
        try {
            (getSystemService(Context.INPUT_SERVICE) as InputManager)
                .unregisterInputDeviceListener(inputListener)
        } catch (t: Throwable) {
            RingLog.e("unregisterInputDeviceListener: ${t.message}")
        }
        recognizer?.reset()
        recognizer = null
        actions = null
        consumedKeys.clear()
        main.removeCallbacksAndMessages(null)
        ble?.stop()
        ble = null
        removeCursor()
        super.onDestroy()
    }

    // -----------------------------------------------------------------------
    // Self-test
    // -----------------------------------------------------------------------

    /**
     * Drives the pointer from synthetic deltas so the overlay, PointerEngine and
     * WindowManager path can be proven independently of the ring. The R6's
     * firmware refuses boot-protocol mode, so without this there is no way to
     * confirm the rest of the stack is sound.
     */
    private var demoTicks = 0
    private val demoRunnable = object : Runnable {
        override fun run() {
            val t = demoTicks++ / 12.0
            val dx = (kotlin.math.cos(t) * 9).toInt()
            val dy = (kotlin.math.sin(t * 1.7) * 9).toInt()
            if (engine.update(dx, dy)) moveCursor()
            main.postDelayed(this, 16L)
        }
    }

    fun setDemo(on: Boolean) {
        main.removeCallbacks(demoRunnable)
        if (on) {
            val (w, h) = screenBounds()
            engine.setScreen(w, h)
            engine.centre()
            showCursor(true)
            moveCursor()
            main.post(demoRunnable)
            RingLog.i("demo cursor ON ($w x $h) - proves overlay + engine + WM path")
        } else {
            showCursor(false)
            RingLog.i("demo cursor OFF")
        }
    }

    // -----------------------------------------------------------------------
    // Real-time input from Android's HID stack
    //
    // This is the route that does not touch HID-over-GATT at all. Pair the ring
    // normally, let the system decode it, and observe the input events it
    // produces here. Nothing below needs BLUETOOTH_PRIVILEGED.
    // -----------------------------------------------------------------------

    private val inputListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(id: Int) = signal("DEVICE ADDED ${describe(id)}")
        override fun onInputDeviceRemoved(id: Int) = signal("DEVICE REMOVED dev=$id")
        override fun onInputDeviceChanged(id: Int) = signal("DEVICE CHANGED ${describe(id)}")
    }

    private fun describe(id: Int): String {
        val d = InputDevice.getDevice(id) ?: return "dev=$id(?)"
        return "dev=$id '${d.name}' src=0x${Integer.toHexString(d.sources)} ext=${d.isExternal}"
    }

    /**
     * The macro layer's only input in this build.
     *
     * onKeyEvent returns Boolean, so a key can genuinely be swallowed here and
     * never reach the foreground app. That is the whole bypass mechanism.
     * Axes are not handled: onMotionEvent returns Unit and cannot consume, so
     * binding one could never actually take the input away from the system.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val dev = InputDevice.getDevice(event.deviceId)
        val external = dev?.isExternal == true
        val name = KeyEvent.keyCodeToString(event.keyCode)
        val down = event.action == KeyEvent.ACTION_DOWN
        val slot = event.deviceId.toString() + ":" + event.keyCode

        signal("KEY $name (${if (down) "DOWN" else "UP"}) code=${event.keyCode} ${describe(event.deviceId)}")

        // Auto-repeat from a held key would inflate the tap count into a
        // sequence the user never performed.
        if (down && event.repeatCount > 0) return consumedKeys.contains(slot)

        // Our own media and volume actions inject key events. Without this the
        // first macro bound to volume up would re-trigger itself forever.
        if (ActionRunner.suppressed()) return false

        val deviceKey = deviceKeyOf(dev, event.deviceId)
        val deviceName = dev?.name ?: ("device " + event.deviceId)
        val controlKey = "key:" + event.keyCode

        // Power never reaches accessibility, and Home is eaten first on many
        // OEM builds. Record them so the table is honest about it.
        val capture =
            if (event.keyCode == KeyEvent.KEYCODE_POWER || event.keyCode == KeyEvent.KEYCODE_HOME)
                Capture.BLOCKED
            else
                Capture.FULL

        if (down) {
            Registry.observe(
                deviceKey = deviceKey,
                deviceName = deviceName,
                transport = Transport.HID_KEY,
                external = external,
                controlKey = controlKey,
                controlLabel = name.removePrefix("KEYCODE_"),
                capture = capture
            )
        }

        val rec = recognizer ?: return false

        val consume: Boolean
        if (down) {
            consume = rec.shouldConsume(deviceKey, controlKey)
            if (consume) consumedKeys.add(slot)
        } else {
            consume = consumedKeys.remove(slot)
        }

        rec.submit(deviceKey, controlKey, down, consume)

        // Legacy probe behaviour, off unless explicitly switched on.
        if (keyToScroll && external && down && !consume) {
            showCursor(true)
            dispatchScroll(engine.x, engine.y, 600f)
        }

        return consume
    }

    /**
     * A key that survives reconnection. InputDevice.getDescriptor() is a stable
     * hash of the device's identity, so a keyboard keeps its bindings after it
     * is turned off and on again; the numeric deviceId does not survive that.
     */
    private fun deviceKeyOf(dev: InputDevice?, id: Int): String {
        val d = dev?.descriptor
        if (d != null && d.isNotEmpty()) return d
        return "dev:" + id
    }

    override fun onMotionEvent(event: MotionEvent) {
        val x = event.getAxisValue(MotionEvent.AXIS_X)
        val y = event.getAxisValue(MotionEvent.AXIS_Y)
        val hs = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
        val vs = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        val rx = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
        val ry = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)

        // Build the numbers with format() first: a device name containing a '%'
        // would otherwise be parsed as a format specifier and throw.
        val nums = "x=%.1f y=%.1f rel=(%.1f,%.1f) scroll=(%.1f,%.1f)"
            .format(x, y, rx, ry, hs, vs)
        signal("MOTION src=0x${Integer.toHexString(event.source)} $nums ${describe(event.deviceId)}")

        // Drive the pointer from whatever the system gives us.
        showCursor(true)
        if (rx != 0f || ry != 0f) {
            if (engine.update(rx.toInt(), ry.toInt())) moveCursor()
        } else if (x != 0f || y != 0f) {
            engine.moveTo(x, y)
            moveCursor()
        }
        if (vs != 0f) dispatchScroll(engine.x, engine.y, vs * 220f)
    }

    /** Everything Android currently considers an input device. */
    fun dumpInputDevices() {
        val ids = InputDevice.getDeviceIds()
        RingLog.i("--- input devices (${ids.size}) ---")
        for (id in ids) {
            val d = InputDevice.getDevice(id) ?: continue
            // Source constants overlap (SOURCE_DPAD 0x201 shares bits with
            // SOURCE_KEYBOARD 0x101), so a bare AND reports classes the device
            // does not have. Require the full mask.
            val kinds = buildString {
                val s = d.sources
                fun has(mask: Int) = (s and mask) == mask
                if (has(InputDevice.SOURCE_KEYBOARD)) append("KEYBOARD ")
                if (has(InputDevice.SOURCE_MOUSE)) append("MOUSE ")
                if (has(InputDevice.SOURCE_TOUCHPAD)) append("TOUCHPAD ")
                if (has(InputDevice.SOURCE_JOYSTICK)) append("JOYSTICK ")
                if (has(InputDevice.SOURCE_TRACKBALL)) append("TRACKBALL ")
                if (has(InputDevice.SOURCE_DPAD)) append("DPAD ")
                if (has(InputDevice.SOURCE_TOUCHSCREEN)) append("TOUCHSCREEN ")
            }
            RingLog.i("  [$id] '${d.name}' external=${d.isExternal} $kinds")
            RingLog.d("       sources=0x${Integer.toHexString(d.sources)} vendor=0x${Integer.toHexString(d.vendorId)} product=0x${Integer.toHexString(d.productId)}")
        }
        RingLog.i("--- if the ring is not listed, Android is not receiving input from it ---")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* unused */ }

    override fun onInterrupt() { /* unused */ }

    // -----------------------------------------------------------------------
    // Overlay
    // -----------------------------------------------------------------------

    /**
     * The real, full-screen bounds.
     *
     * resources.displayMetrics on a Service context reports the *content* area,
     * excluding system bars: on a Pixel 8 Pro it returned 1344x2659 against a
     * true 1344x2992. The pointer space was 333px short, so the cursor could not
     * reach the bottom of the screen and dispatched taps missed their targets.
     */
    private fun screenBounds(): Pair<Int, Int> {
        val manager = wm
        if (manager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = manager.maximumWindowMetrics.bounds
            return b.width() to b.height()
        }
        val d = resources.displayMetrics
        return d.widthPixels to d.heightPixels
    }

    private fun addCursor() {
        val d = resources.displayMetrics
        val (w, h) = screenBounds()
        engine.setScreen(w, h)
        engine.centre()

        val sizePx = (CursorView.SIZE_DP * d.density).toInt()

        val params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (engine.x - sizePx / 2).toInt()
            y = (engine.y - sizePx / 2).toInt()
        }

        val view = CursorView(this)
        view.visibility = android.view.View.INVISIBLE  // shown once data arrives

        try {
            wm?.addView(view, params)
            cursor = view
            lp = params
            RingLog.i("cursor overlay added ($w x $h)")
        } catch (t: Throwable) {
            RingLog.e("addView failed: ${t.message}")
        }
    }

    private fun removeCursor() {
        try {
            cursor?.let { wm?.removeView(it) }
        } catch (t: Throwable) {
            RingLog.e("removeView: ${t.message}")
        }
        cursor = null
        lp = null
    }

    private fun showCursor(visible: Boolean) {
        cursor?.visibility =
            if (visible) android.view.View.VISIBLE else android.view.View.INVISIBLE
    }

    private fun moveCursor() {
        val v = cursor ?: return
        val p = lp ?: return
        val size = v.width.takeIf { it > 0 }
            ?: (CursorView.SIZE_DP * resources.displayMetrics.density).toInt()
        p.x = (engine.x - size / 2).toInt()
        p.y = (engine.y - size / 2).toInt()
        try {
            wm?.updateViewLayout(v, p)
        } catch (t: Throwable) {
            RingLog.e("updateViewLayout: ${t.message}")
        }
    }

    // -----------------------------------------------------------------------
    // Input
    // -----------------------------------------------------------------------

    private fun onMouseReport(r: MouseReport) {
        if (engine.update(r.dx, r.dy)) {
            moveCursor()
            drag?.let { if (it.dueForStep()) it.step(engine.x, engine.y) }
        }

        if (r.wheel != 0 && drag == null) {
            dispatchScroll(engine.x, engine.y, engine.scrollDistance(r.wheel))
        }

        handleButtons(r.buttons)
    }

    private fun handleButtons(buttons: Int) {
        val was = lastButtons
        lastButtons = buttons

        val leftNow = buttons and 0x01 != 0
        val leftWas = was and 0x01 != 0

        // Secondary buttons map to navigation. A ring has very few inputs, so
        // Back and Home are worth more than right-click, which Android has no
        // real concept of anyway.
        if (buttons and 0x02 != 0 && was and 0x02 == 0) {
            RingLog.i("button 2 -> BACK")
            performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
        if (buttons and 0x04 != 0 && was and 0x04 == 0) {
            RingLog.i("button 3 -> HOME")
            performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }

        if (leftNow && !leftWas) {
            // press
            pressStartMs = SystemClock.uptimeMillis()
            pressStartX = engine.x
            pressStartY = engine.y
            longPressFired = false
            cursor?.ringPressed = true
            main.postDelayed(longPressRunnable, LONG_PRESS_MS)
        } else if (leftNow && leftWas) {
            // held: promote to a drag once we've travelled far enough
            if (drag == null && !longPressFired &&
                (abs(engine.x - pressStartX) > DRAG_SLOP_PX ||
                    abs(engine.y - pressStartY) > DRAG_SLOP_PX)
            ) {
                main.removeCallbacks(longPressRunnable)
                RingLog.i("drag start")
                drag = DragSession(pressStartX, pressStartY).also { it.begin() }
            }
        } else if (!leftNow && leftWas) {
            // release
            main.removeCallbacks(longPressRunnable)
            cursor?.ringPressed = false
            val held = SystemClock.uptimeMillis() - pressStartMs
            val d = drag
            if (d != null) {
                d.end(engine.x, engine.y)
                drag = null
            } else if (!longPressFired && held < LONG_PRESS_MS) {
                RingLog.i("tap at (${engine.x.toInt()}, ${engine.y.toInt()})")
                dispatchTap(engine.x, engine.y, 60L)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Gesture dispatch
    // -----------------------------------------------------------------------

    private fun dispatchTap(x: Float, y: Float, durationMs: Long) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val ok = dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(), null, null
        )
        if (!ok) RingLog.e("dispatchGesture(tap) refused - is canPerformGestures set?")
    }

    private fun dispatchScroll(x: Float, y: Float, distance: Float) {
        val endY = (y - distance).coerceIn(1f, (engine.screenH - 2).toFloat())
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 120L)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    /**
     * Continuous drag.
     *
     * dispatchGesture cannot be handed an open-ended stroke, so a drag is built
     * from a chain of short strokes: each one is created with willContinue =
     * true and the next is produced by continueStroke() on the previous. The
     * continuation must not be dispatched until the previous stroke has
     * completed, hence the callback chaining rather than a plain timer.
     */
    private inner class DragSession(private val startX: Float, private val startY: Float) {

        private var last: GestureDescription.StrokeDescription? = null
        private var lastX = startX
        private var lastY = startY
        private var lastStepMs = 0L
        private var inFlight = false
        private var finished = false

        fun dueForStep(): Boolean =
            !inFlight && !finished && SystemClock.uptimeMillis() - lastStepMs >= DRAG_STEP_MS

        fun begin() {
            val path = Path().apply { moveTo(startX, startY) }
            val s = GestureDescription.StrokeDescription(path, 0L, DRAG_STEP_MS, true)
            last = s
            dispatch(s)
        }

        fun step(x: Float, y: Float) {
            val prev = last ?: return
            if (finished) return
            val path = Path().apply {
                moveTo(lastX, lastY)
                lineTo(x, y)
            }
            lastX = x
            lastY = y
            val s = prev.continueStroke(path, 0L, DRAG_STEP_MS, true)
            last = s
            dispatch(s)
        }

        fun end(x: Float, y: Float) {
            val prev = last ?: return
            finished = true
            val path = Path().apply {
                moveTo(lastX, lastY)
                lineTo(x, y)
            }
            val s = prev.continueStroke(path, 0L, DRAG_STEP_MS, false)
            last = null
            RingLog.i("drag end at (${x.toInt()}, ${y.toInt()})")
            dispatch(s)
        }

        private fun dispatch(s: GestureDescription.StrokeDescription) {
            inFlight = true
            lastStepMs = SystemClock.uptimeMillis()
            val cb = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    inFlight = false
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    inFlight = false
                    finished = true
                    RingLog.e("drag stroke cancelled")
                }
            }
            val ok = dispatchGesture(
                GestureDescription.Builder().addStroke(s).build(), cb, main
            )
            if (!ok) {
                inFlight = false
                finished = true
                RingLog.e("dispatchGesture(drag) refused")
            }
        }
    }
}
