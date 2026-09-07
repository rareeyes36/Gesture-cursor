package ca.scryr.ringcursor

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
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

        /** Long-press threshold. */
        private const val LONG_PRESS_MS = 500L

        /** Movement beyond this during a press turns a tap into a drag. */
        private const val DRAG_SLOP_PX = 24f

        /** How often a held-and-moving press extends the drag stroke. */
        private const val DRAG_STEP_MS = 90L
    }

    private val main = Handler(Looper.getMainLooper())
    private val engine = PointerEngine()

    private var wm: WindowManager? = null
    private var cursor: CursorView? = null
    private var lp: WindowManager.LayoutParams? = null

    private var ble: RingBle? = null

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

        wm = getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
        addCursor()

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
    }

    override fun onDestroy() {
        RingLog.i("accessibility service destroyed")
        instance = null
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
