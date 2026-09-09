package ca.scryr.ringcursor.macro

import android.os.Handler
import android.os.SystemClock
import ca.scryr.ringcursor.RingLog

/**
 * Turns a stream of raw press and release edges into trigger matches.
 *
 * The design point that matters: **the delay is only paid where ambiguity
 * actually exists.** Bindings form a prefix set. Every time a burst could
 * terminate, the recogniser asks whether any longer binding still shares the
 * current prefix. If none does, it fires immediately with zero added latency,
 * so a button bound to exactly one thing feels instant. Only genuinely
 * overloaded controls ever wait.
 *
 * Threading: the consume decision is answered synchronously on the caller's
 * thread because AccessibilityService.onKeyEvent must return a verdict right
 * away, but it is a pure read of the registry. All state machine work is
 * posted to the main looper, so the state below is single threaded and needs
 * no locking. Handler.post preserves order, so down and up edges cannot
 * overtake one another.
 *
 * Not in this build: chords (two different controls held together). Each
 * control runs an independent state machine.
 */
class Recognizer(
    private val main: Handler,
    private val onFire: (Binding) -> Unit
) {

    private class State {
        /** Completed bursts, e.g. [2] after a finished double press. */
        val pattern = ArrayList<Int>()
        /** Presses counted in the burst currently under way. */
        var tapsInBurst = 0
        var isDown = false
        var downAt = 0L
        var holdFired = false
        /** Latched at the down edge so the matching up edge agrees. */
        var consuming = false
        var tapTimer: Runnable? = null
        var stepTimer: Runnable? = null
        var holdTimer: Runnable? = null
    }

    private val states = HashMap<String, State>()

    private fun keyOf(device: String, control: String) = device + " " + control

    // ------------------------------------------------------------------
    // Synchronous verdict
    // ------------------------------------------------------------------

    /**
     * Whether this key should be swallowed. Answered without touching the
     * state machine so it is safe to call from the input thread.
     *
     * This is the per-control capture policy from the plan, chosen over
     * swallow-and-replay: only controls the user explicitly bound and enabled
     * are ever taken, so an unbound key can never silently stop working.
     */
    fun shouldConsume(deviceKey: String, controlKey: String): Boolean {
        if (!Registry.masterBypass) return false
        val dev = Registry.device(deviceKey) ?: return false
        if (!dev.bypass) return false
        val ctl = Registry.control(deviceKey, controlKey) ?: return false
        if (!ctl.enabled) return false
        if (ctl.capture != Capture.FULL) return false
        return Registry.hasBinding(deviceKey, controlKey)
    }

    // ------------------------------------------------------------------
    // Edge intake
    // ------------------------------------------------------------------

    /** Feed one edge. Ordering is preserved; all work happens on the main looper. */
    fun submit(deviceKey: String, controlKey: String, down: Boolean, consumed: Boolean) {
        main.post {
            if (down) advanceDown(deviceKey, controlKey, consumed)
            else advanceUp(deviceKey, controlKey)
        }
    }

    fun reset() {
        main.post {
            for (s in states.values) clearTimers(s)
            states.clear()
        }
    }

    // ------------------------------------------------------------------
    // State machine, main thread only
    // ------------------------------------------------------------------

    private fun advanceDown(deviceKey: String, controlKey: String, consumed: Boolean) {
        val bindings = Registry.bindingsFor(deviceKey, controlKey)
        if (bindings.isEmpty()) return

        val k = keyOf(deviceKey, controlKey)
        val st = states.getOrPut(k) { State() }

        // A fresh press extends whatever sequence is in flight, so both the
        // burst timer and the sequence timer stop waiting.
        cancel(st.tapTimer); st.tapTimer = null
        cancel(st.stepTimer); st.stepTimer = null

        st.isDown = true
        st.downAt = SystemClock.uptimeMillis()
        st.holdFired = false
        st.consuming = consumed

        // Hold is only meaningful as the opening gesture, and it must be armed
        // on the down edge. Deciding it at release would mean nothing happens
        // until the user lets go, which reads as broken.
        val hold = bindings.firstOrNull { it.trigger.hold }
        if (hold != null && st.pattern.isEmpty() && st.tapsInBurst == 0) {
            val r = Runnable {
                st.holdFired = true
                st.holdTimer = null
                fire(hold)
                resetState(deviceKey, controlKey)
            }
            st.holdTimer = r
            main.postDelayed(r, Registry.timing.holdMs)
        }
    }

    private fun advanceUp(deviceKey: String, controlKey: String) {
        val k = keyOf(deviceKey, controlKey)
        val st = states[k] ?: return
        if (!st.isDown) return
        st.isDown = false

        cancel(st.holdTimer); st.holdTimer = null

        // The hold already fired and the state was cleared. Swallow the
        // release so it cannot be mistaken for a tap.
        if (st.holdFired) {
            st.holdFired = false
            return
        }

        st.tapsInBurst++

        val bindings = Registry.bindingsFor(deviceKey, controlKey)
            .filter { !it.trigger.hold }
        if (bindings.isEmpty()) { resetState(deviceKey, controlKey); return }

        val prefix = st.pattern
        val candidate: List<Int> = prefix + st.tapsInBurst

        val canGrowTaps = bindings.any { b ->
            val p = b.trigger.pattern
            p.size > prefix.size && startsWith(p, prefix) && p[prefix.size] > st.tapsInBurst
        }
        val terminal = bindings.firstOrNull { it.trigger.pattern == candidate }
        val canExtendSteps = bindings.any { b ->
            val p = b.trigger.pattern
            p.size > candidate.size && startsWith(p, candidate)
        }

        if (!canGrowTaps && !canExtendSteps) {
            // Nothing longer can match, so there is nothing to wait for.
            if (terminal != null) fire(terminal)
            resetState(deviceKey, controlKey)
            return
        }

        if (canGrowTaps) {
            // More presses could still land in this burst.
            val r = Runnable {
                st.tapTimer = null
                burstComplete(deviceKey, controlKey)
            }
            st.tapTimer = r
            main.postDelayed(r, Registry.timing.tapGapMs)
        } else {
            // The burst cannot grow, but another burst could follow.
            burstComplete(deviceKey, controlKey)
        }
    }

    private fun burstComplete(deviceKey: String, controlKey: String) {
        val k = keyOf(deviceKey, controlKey)
        val st = states[k] ?: return

        st.pattern.add(st.tapsInBurst)
        st.tapsInBurst = 0

        val bindings = Registry.bindingsFor(deviceKey, controlKey).filter { !it.trigger.hold }
        val done: List<Int> = st.pattern.toList()
        val terminal = bindings.firstOrNull { it.trigger.pattern == done }
        val canExtendSteps = bindings.any { b ->
            val p = b.trigger.pattern
            p.size > done.size && startsWith(p, done)
        }

        if (!canExtendSteps) {
            if (terminal != null) fire(terminal)
            resetState(deviceKey, controlKey)
            return
        }

        // A longer sequence is still possible. Wait out the sequence gap; if
        // nothing else arrives, settle for the longest match we have.
        val r = Runnable {
            st.stepTimer = null
            if (terminal != null) fire(terminal)
            resetState(deviceKey, controlKey)
        }
        st.stepTimer = r
        main.postDelayed(r, Registry.timing.stepGapMs)
    }

    private fun startsWith(full: List<Int>, prefix: List<Int>): Boolean {
        if (prefix.size > full.size) return false
        for (i in prefix.indices) if (full[i] != prefix[i]) return false
        return true
    }

    private fun fire(b: Binding) {
        RingLog.i("trigger " + b.trigger.label() + " on " + b.controlKey + " -> " + b.summary())
        runCatching { onFire(b) }
            .onFailure { RingLog.e("macro failed: " + it.javaClass.simpleName + ": " + it.message) }
    }

    private fun resetState(deviceKey: String, controlKey: String) {
        val k = keyOf(deviceKey, controlKey)
        val st = states[k] ?: return
        clearTimers(st)
        st.pattern.clear()
        st.tapsInBurst = 0
        // holdFired is deliberately NOT cleared here. The hold path resets the
        // state while the key is still physically down, and the release that
        // follows must still be recognised as the tail of the hold rather than
        // counted as a tap. advanceDown clears it when a new press begins.
    }

    private fun clearTimers(st: State) {
        cancel(st.tapTimer); st.tapTimer = null
        cancel(st.stepTimer); st.stepTimer = null
        cancel(st.holdTimer); st.holdTimer = null
    }

    private fun cancel(r: Runnable?) {
        if (r != null) main.removeCallbacks(r)
    }
}
