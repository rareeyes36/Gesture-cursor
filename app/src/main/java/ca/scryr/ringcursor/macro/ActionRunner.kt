package ca.scryr.ringcursor.macro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import ca.scryr.ringcursor.RingLog

/**
 * Every output the app can perform, plus the macro sequencer that walks a list
 * of them with pauses in between.
 *
 * Nothing here needs a privileged permission. The two entries the brief asked
 * for that are missing are power off, which no non-system app can do, and
 * rebinding the power button, which the window manager consumes long before an
 * accessibility service sees it.
 */
class ActionRunner(
    private val svc: AccessibilityService,
    private val main: Handler
) {

    companion object {
        /**
         * Self-trigger guard.
         *
         * dispatchMediaKeyEvent injects real key events, and volume changes can
         * echo back through the input stack. Without a suppression window, a
         * macro bound to volume up that performs volume up re-triggers itself
         * forever. The service checks this before feeding the recogniser.
         */
        @Volatile
        var suppressUntilMs: Long = 0L
            private set

        fun suppress(ms: Long) {
            val until = SystemClock.uptimeMillis() + ms
            if (until > suppressUntilMs) suppressUntilMs = until
        }

        fun suppressed(): Boolean = SystemClock.uptimeMillis() < suppressUntilMs
    }

    private val audio: AudioManager?
        get() = svc.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var torchOn = false

    // ------------------------------------------------------------------
    // Macro sequencing
    // ------------------------------------------------------------------

    /** Run a macro. Steps execute in order, each followed by its own pause. */
    fun run(binding: Binding) {
        runSteps(binding.steps, 0)
    }

    private fun runSteps(steps: List<Step>, index: Int) {
        if (index >= steps.size) return
        val step = steps[index]
        perform(step)
        val next = index + 1
        if (next >= steps.size) return
        val pause = step.pauseAfterMs.toLong().coerceAtLeast(0L)
        main.postDelayed({ runSteps(steps, next) }, pause)
    }

    // ------------------------------------------------------------------
    // Individual actions
    // ------------------------------------------------------------------

    fun perform(step: Step) {
        val a = step.action
        if (Build.VERSION.SDK_INT < a.minSdk) {
            RingLog.e(a.label + " needs API " + a.minSdk + "; this device is " + Build.VERSION.SDK_INT)
            return
        }
        when (a) {
            ActionType.BACK -> global(AccessibilityService.GLOBAL_ACTION_BACK)
            ActionType.HOME -> global(AccessibilityService.GLOBAL_ACTION_HOME)
            ActionType.RECENTS -> global(AccessibilityService.GLOBAL_ACTION_RECENTS)
            ActionType.NOTIFICATIONS -> global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            ActionType.QUICK_SETTINGS -> global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            ActionType.POWER_DIALOG -> global(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
            ActionType.SCREENSHOT -> global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            ActionType.LOCK_SCREEN -> global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)

            ActionType.VOLUME_UP -> volume(AudioManager.ADJUST_RAISE)
            ActionType.VOLUME_DOWN -> volume(AudioManager.ADJUST_LOWER)
            ActionType.VOLUME_MUTE -> volume(AudioManager.ADJUST_TOGGLE_MUTE)

            ActionType.MEDIA_PLAY_PAUSE -> media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            ActionType.MEDIA_NEXT -> media(KeyEvent.KEYCODE_MEDIA_NEXT)
            ActionType.MEDIA_PREV -> media(KeyEvent.KEYCODE_MEDIA_PREVIOUS)

            ActionType.TORCH_TOGGLE -> toggleTorch()

            ActionType.SWIPE_UP -> swipe(0f, -1f)
            ActionType.SWIPE_DOWN -> swipe(0f, 1f)
            ActionType.SWIPE_LEFT -> swipe(-1f, 0f)
            ActionType.SWIPE_RIGHT -> swipe(1f, 0f)
            ActionType.TAP_CENTRE -> tapCentre()

            ActionType.LAUNCH_APP -> launch(step.arg)
        }
    }

    private fun global(action: Int) {
        val ok = svc.performGlobalAction(action)
        if (!ok) RingLog.e("performGlobalAction(" + action + ") refused")
    }

    private fun volume(direction: Int) {
        // Volume changes can echo back as input; hold the guard open long
        // enough to cover the round trip.
        suppress(350L)
        val am = audio
        if (am == null) { RingLog.e("no AudioManager"); return }
        try {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        } catch (t: SecurityException) {
            // Toggling mute touches Do Not Disturb policy on some builds.
            RingLog.e("volume refused: " + t.message)
        }
    }

    private fun media(keyCode: Int) {
        suppress(350L)
        val am = audio ?: return
        val now = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun toggleTorch() {
        val cm = svc.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (cm == null) { RingLog.e("no CameraManager"); return }
        try {
            val id = cm.cameraIdList.firstOrNull { camId ->
                cm.getCameraCharacteristics(camId)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (id == null) { RingLog.e("no camera with a flash"); return }
            torchOn = !torchOn
            cm.setTorchMode(id, torchOn)
        } catch (t: Throwable) {
            RingLog.e("torch: " + t.javaClass.simpleName + ": " + t.message)
        }
    }

    private fun launch(pkg: String?) {
        if (pkg.isNullOrEmpty()) { RingLog.e("launch: no package set on this step"); return }
        val intent = svc.packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) { RingLog.e("launch: " + pkg + " has no launcher activity"); return }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            svc.startActivity(intent)
        } catch (t: Throwable) {
            RingLog.e("launch failed: " + t.message)
        }
    }

    // ------------------------------------------------------------------
    // Gestures
    // ------------------------------------------------------------------

    private fun screen(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = svc.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (wm != null) {
                val b = wm.maximumWindowMetrics.bounds
                return b.width() to b.height()
            }
        }
        val d = svc.resources.displayMetrics
        return d.widthPixels to d.heightPixels
    }

    /** dx and dy are unit direction; the stroke covers 40% of that axis. */
    private fun swipe(dx: Float, dy: Float) {
        val (w, h) = screen()
        val cx = w / 2f
        val cy = h / 2f
        val spanX = w * 0.20f
        val spanY = h * 0.20f
        val path = Path()
        path.moveTo(cx - dx * spanX, cy - dy * spanY)
        path.lineTo(cx + dx * spanX, cy + dy * spanY)
        dispatch(path, 0L, 260L, "swipe")
    }

    private fun tapCentre() {
        val (w, h) = screen()
        val path = Path()
        path.moveTo(w / 2f, h / 2f)
        path.lineTo(w / 2f, h / 2f)
        dispatch(path, 0L, 60L, "tap")
    }

    private fun dispatch(path: Path, start: Long, duration: Long, what: String) {
        val stroke = GestureDescription.StrokeDescription(path, start, duration)
        val ok = svc.dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(), null, null
        )
        if (!ok) RingLog.e("dispatchGesture(" + what + ") refused")
    }
}
