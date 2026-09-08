package ca.scryr.ringcursor

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sign

/**
 * Turns HID mouse deltas into an absolute on-screen position.
 *
 * Note on a branch we did NOT take: earlier in the design we discussed velocity
 * mode (tilt sets cursor speed) versus position mode (integrate to get
 * displacement). That fork only applies to raw IMU data. HID mouse reports are
 * already deltas, so we are in displacement mode by construction and the drift
 * problem never arises. This class is therefore much simpler than the
 * accelerometer-based engine would have been.
 *
 * Three things separate a usable pointer from an infuriating one, and all three
 * are here: a dead zone, a non-linear gain curve, and output smoothing.
 */
class PointerEngine {

    var screenW: Int = 1080
    var screenH: Int = 2400

    /** Pixels per unit delta at unit speed. */
    var sensitivity: Float = 2.2f

    /**
     * Pointer acceleration exponent. 1.0 = linear (constant gain).
     * >1 makes fast movements travel disproportionately further, which is what
     * lets you cross the screen and still hit a small target.
     */
    var accelExponent: Float = 1.35f

    /** Deltas with magnitude below this are treated as sensor noise. */
    var deadZone: Float = 0.8f

    /** Output smoothing, 0 = none, approaching 1 = heavy lag. */
    var smoothing: Float = 0.35f

    /** Invert axes if the ring is worn rotated. */
    var invertX: Boolean = false
    var invertY: Boolean = false

    var x: Float = 540f
        private set
    var y: Float = 1200f
        private set

    private var smoothedDx = 0f
    private var smoothedDy = 0f

    fun setScreen(w: Int, h: Int) {
        screenW = w
        screenH = h
        x = x.coerceIn(0f, (w - 1).toFloat())
        y = y.coerceIn(0f, (h - 1).toFloat())
    }

    /** Place the pointer at an absolute screen position (mouse/hover events). */
    fun moveTo(nx: Float, ny: Float) {
        x = nx.coerceIn(0f, (screenW - 1).toFloat())
        y = ny.coerceIn(0f, (screenH - 1).toFloat())
    }

    fun centre() {
        x = screenW / 2f
        y = screenH / 2f
        smoothedDx = 0f
        smoothedDy = 0f
    }

    /**
     * Feed one report. Returns true if the position actually moved, so callers
     * can skip a WindowManager update on no-op reports (the ring sends plenty).
     */
    fun update(dxRaw: Int, dyRaw: Int): Boolean {
        var dx = dxRaw.toFloat()
        var dy = dyRaw.toFloat()
        if (invertX) dx = -dx
        if (invertY) dy = -dy

        val mag = hypot(dx, dy)
        if (mag < deadZone) {
            // Bleed the smoothing state so a stopped hand settles rather than
            // coasting on stale momentum.
            smoothedDx *= 0.5f
            smoothedDy *= 0.5f
            if (abs(smoothedDx) < 0.01f && abs(smoothedDy) < 0.01f) return false
        }

        // Gain is a function of speed, applied to the vector as a whole so that
        // diagonal movement is not faster than axis-aligned movement.
        val gain = sensitivity * mag.pow(accelExponent - 1f).coerceIn(0.2f, 12f)

        val targetDx = dx * gain
        val targetDy = dy * gain

        smoothedDx = smoothedDx * smoothing + targetDx * (1f - smoothing)
        smoothedDy = smoothedDy * smoothing + targetDy * (1f - smoothing)

        val prevX = x
        val prevY = y
        x = (x + smoothedDx).coerceIn(0f, (screenW - 1).toFloat())
        y = (y + smoothedDy).coerceIn(0f, (screenH - 1).toFloat())

        return x != prevX || y != prevY
    }

    /** Wheel deltas map to a scroll distance in pixels. */
    fun scrollDistance(wheel: Int): Float = wheel.sign * 220f * abs(wheel).coerceAtMost(4)
}
