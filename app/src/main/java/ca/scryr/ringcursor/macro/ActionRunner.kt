package ca.scryr.ringcursor.macro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.NotificationManager
import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import ca.scryr.ringcursor.RingLog

/**
 * Every output the app can perform, plus the macro sequencer that walks a list
 * of them with pauses in between.
 *
 * Nothing here needs a privileged permission. A few entries need a special
 * grant the user makes once in Settings, and each of those checks for it and
 * logs what is missing rather than failing silently.
 *
 * The when over ActionType is deliberately exhaustive with no else branch, so
 * adding an action to the enum is a compile error until it is implemented here.
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

        private const val BRIGHTNESS_STEP = 26
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
    // Dispatch
    // ------------------------------------------------------------------

    fun perform(step: Step) {
        val a = step.action
        if (Build.VERSION.SDK_INT < a.minSdk) {
            RingLog.e(a.label + " needs API " + a.minSdk + "; this device is " + Build.VERSION.SDK_INT)
            return
        }
        val arg = step.arg
        when (a) {
            // ---- Navigation ----
            ActionType.BACK -> global(AccessibilityService.GLOBAL_ACTION_BACK)
            ActionType.HOME -> global(AccessibilityService.GLOBAL_ACTION_HOME)
            ActionType.RECENTS -> global(AccessibilityService.GLOBAL_ACTION_RECENTS)
            ActionType.NOTIFICATIONS -> global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            ActionType.QUICK_SETTINGS -> global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            ActionType.POWER_DIALOG -> global(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
            ActionType.MENU -> global(AccessibilityService.GLOBAL_ACTION_MENU)
            ActionType.SPLIT_SCREEN -> global(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
            ActionType.ALL_APPS -> global(AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_ALL_APPS)
            ActionType.DPAD_UP -> global(AccessibilityService.GLOBAL_ACTION_DPAD_UP)
            ActionType.DPAD_DOWN -> global(AccessibilityService.GLOBAL_ACTION_DPAD_DOWN)
            ActionType.DPAD_LEFT -> global(AccessibilityService.GLOBAL_ACTION_DPAD_LEFT)
            ActionType.DPAD_RIGHT -> global(AccessibilityService.GLOBAL_ACTION_DPAD_RIGHT)
            ActionType.DPAD_CENTER -> global(AccessibilityService.GLOBAL_ACTION_DPAD_CENTER)
            ActionType.ACCESSIBILITY_BUTTON ->
                global(AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_BUTTON)
            ActionType.ACCESSIBILITY_SHORTCUT ->
                global(AccessibilityService.GLOBAL_ACTION_ACCESSIBILITY_SHORTCUT)
            ActionType.HEADSET_HOOK ->
                global(AccessibilityService.GLOBAL_ACTION_KEYCODE_HEADSETHOOK)

            ActionType.SCREENSHOT -> global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            ActionType.LOCK_SCREEN -> global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)

            // ---- Audio ----
            ActionType.VOLUME_UP -> volume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE)
            ActionType.VOLUME_DOWN -> volume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER)
            ActionType.VOLUME_MUTE -> volume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE)
            ActionType.VOLUME_UP_RING -> volume(AudioManager.STREAM_RING, AudioManager.ADJUST_RAISE)
            ActionType.VOLUME_DOWN_RING -> volume(AudioManager.STREAM_RING, AudioManager.ADJUST_LOWER)
            ActionType.VOLUME_UP_ALARM -> volume(AudioManager.STREAM_ALARM, AudioManager.ADJUST_RAISE)
            ActionType.VOLUME_DOWN_ALARM -> volume(AudioManager.STREAM_ALARM, AudioManager.ADJUST_LOWER)
            ActionType.VOLUME_UP_CALL -> volume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_RAISE)
            ActionType.VOLUME_DOWN_CALL -> volume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_LOWER)
            ActionType.VOLUME_MEDIA_MAX -> setVolume(AudioManager.STREAM_MUSIC, max = true)
            ActionType.VOLUME_MEDIA_MIN -> setVolume(AudioManager.STREAM_MUSIC, max = false)

            ActionType.MEDIA_PLAY_PAUSE -> media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            ActionType.MEDIA_NEXT -> media(KeyEvent.KEYCODE_MEDIA_NEXT)
            ActionType.MEDIA_PREV -> media(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            ActionType.MEDIA_STOP -> media(KeyEvent.KEYCODE_MEDIA_STOP)
            ActionType.MEDIA_REWIND -> media(KeyEvent.KEYCODE_MEDIA_REWIND)
            ActionType.MEDIA_FAST_FORWARD -> media(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)

            ActionType.RINGER_NORMAL -> ringer(AudioManager.RINGER_MODE_NORMAL)
            ActionType.RINGER_VIBRATE -> ringer(AudioManager.RINGER_MODE_VIBRATE)
            ActionType.RINGER_SILENT -> ringer(AudioManager.RINGER_MODE_SILENT)

            // ---- System ----
            ActionType.TORCH_TOGGLE -> torch(!torchOn)
            ActionType.TORCH_ON -> torch(true)
            ActionType.TORCH_OFF -> torch(false)
            ActionType.BRIGHTNESS_UP -> brightnessBy(BRIGHTNESS_STEP)
            ActionType.BRIGHTNESS_DOWN -> brightnessBy(-BRIGHTNESS_STEP)
            ActionType.BRIGHTNESS_MAX -> brightnessTo(255)
            ActionType.BRIGHTNESS_MIN -> brightnessTo(1)
            ActionType.AUTO_ROTATE_TOGGLE -> toggleAutoRotate()
            ActionType.VIBRATE_SHORT -> vibrate(40L)
            ActionType.VIBRATE_LONG -> vibrate(300L)
            ActionType.INPUT_METHOD_PICKER -> inputMethodPicker()

            // ---- Settings ----
            ActionType.SETTINGS_MAIN -> settings(Settings.ACTION_SETTINGS)
            ActionType.SETTINGS_WIFI -> settings(Settings.ACTION_WIFI_SETTINGS)
            ActionType.SETTINGS_BLUETOOTH -> settings(Settings.ACTION_BLUETOOTH_SETTINGS)
            ActionType.SETTINGS_DISPLAY -> settings(Settings.ACTION_DISPLAY_SETTINGS)
            ActionType.SETTINGS_SOUND -> settings(Settings.ACTION_SOUND_SETTINGS)
            ActionType.SETTINGS_BATTERY -> settings(Intent.ACTION_POWER_USAGE_SUMMARY)
            ActionType.SETTINGS_APPS -> settings(Settings.ACTION_APPLICATION_SETTINGS)
            ActionType.SETTINGS_STORAGE -> settings(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
            ActionType.SETTINGS_LOCATION -> settings(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            ActionType.SETTINGS_DATE -> settings(Settings.ACTION_DATE_SETTINGS)
            ActionType.SETTINGS_ACCESSIBILITY -> settings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            ActionType.SETTINGS_DEVELOPER -> settings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            ActionType.SETTINGS_APP_INFO -> appInfo(arg)
            ActionType.PANEL_INTERNET -> settings(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
            ActionType.PANEL_WIFI -> settings(Settings.Panel.ACTION_WIFI)
            ActionType.PANEL_VOLUME -> settings(Settings.Panel.ACTION_VOLUME)
            ActionType.PANEL_NFC -> settings(Settings.Panel.ACTION_NFC)

            // ---- Apps ----
            ActionType.LAUNCH_APP -> launchPackage(arg)
            ActionType.OPEN_URL -> view(arg, "https://")
            ActionType.WEB_SEARCH -> webSearch(arg)
            ActionType.DIAL_NUMBER -> dial(arg)
            ActionType.SEND_SMS -> sms(arg)
            ActionType.SHARE_TEXT -> share(arg)
            ActionType.COPY_TEXT -> copyText(arg)
            ActionType.OPEN_CAMERA -> settings(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            ActionType.OPEN_DIALER -> settings(Intent.ACTION_DIAL)
            ActionType.OPEN_CONTACTS -> contacts()
            ActionType.OPEN_ALARMS -> settings(AlarmClock.ACTION_SHOW_ALARMS)
            ActionType.SET_TIMER -> timer(arg)
            ActionType.OPEN_ASSISTANT -> settings(Intent.ACTION_ASSIST)
            ActionType.LAUNCH_ACTIVITY -> launchActivity(arg)
            ActionType.SEND_INTENT_ACTION -> settings(arg)

            // ---- Gesture ----
            ActionType.SWIPE_UP -> swipe(0f, -1f, 0.20f, 260L)
            ActionType.SWIPE_DOWN -> swipe(0f, 1f, 0.20f, 260L)
            ActionType.SWIPE_LEFT -> swipe(-1f, 0f, 0.20f, 260L)
            ActionType.SWIPE_RIGHT -> swipe(1f, 0f, 0.20f, 260L)
            ActionType.SWIPE_UP_LONG -> swipe(0f, -1f, 0.40f, 400L)
            ActionType.SWIPE_DOWN_LONG -> swipe(0f, 1f, 0.40f, 400L)
            ActionType.TAP_CENTRE -> tapAt(centreX(), centreY(), 60L)
            ActionType.LONG_PRESS_CENTRE -> tapAt(centreX(), centreY(), 600L)
            ActionType.DOUBLE_TAP_CENTRE -> doubleTap(centreX(), centreY())
            ActionType.EDGE_BACK_LEFT -> edgeSwipe(fromLeft = true)
            ActionType.EDGE_BACK_RIGHT -> edgeSwipe(fromLeft = false)
            ActionType.PULL_DOWN_SHADE -> pullDown()
            ActionType.PINCH_IN -> pinch(inward = true)
            ActionType.PINCH_OUT -> pinch(inward = false)
            ActionType.TAP_AT -> tapAtArg(arg)
            ActionType.SWIPE_CUSTOM -> swipeArg(arg)

            // ---- Overwrite itself ----
            ActionType.BYPASS_OFF -> {
                Registry.panic()
                RingLog.i("capture released by macro")
            }
            ActionType.BYPASS_ON -> Registry.updateMasterBypass(true)
            ActionType.BYPASS_TOGGLE -> Registry.updateMasterBypass(!Registry.masterBypass)
            ActionType.OPEN_OVERWRITE -> {
                val i = Intent(svc, MacroActivity::class.java)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startSafely(i, "Overwrite")
            }
        }
    }

    // ------------------------------------------------------------------
    // Global actions
    // ------------------------------------------------------------------

    private fun global(action: Int) {
        val ok = svc.performGlobalAction(action)
        if (!ok) RingLog.e("performGlobalAction(" + action + ") refused")
    }

    // ------------------------------------------------------------------
    // Audio
    // ------------------------------------------------------------------

    private fun volume(stream: Int, direction: Int) {
        // Volume changes can echo back as input; hold the guard open long
        // enough to cover the round trip.
        suppress(350L)
        val am = audio
        if (am == null) { RingLog.e("no AudioManager"); return }
        try {
            am.adjustStreamVolume(stream, direction, AudioManager.FLAG_SHOW_UI)
        } catch (t: SecurityException) {
            // Muting a stream touches Do Not Disturb policy on some builds.
            RingLog.e("volume refused: " + t.message)
        }
    }

    private fun setVolume(stream: Int, max: Boolean) {
        suppress(350L)
        val am = audio ?: return
        try {
            val v = if (max) am.getStreamMaxVolume(stream) else 0
            am.setStreamVolume(stream, v, AudioManager.FLAG_SHOW_UI)
        } catch (t: SecurityException) {
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

    private fun ringer(mode: Int) {
        val am = audio ?: return
        // Leaving or entering silent and vibrate is Do Not Disturb policy on
        // M and up, and throws without the grant.
        if (mode != AudioManager.RINGER_MODE_NORMAL && !hasDndAccess()) {
            RingLog.e(
                "ringer change needs Do Not Disturb access. " +
                    "Settings, Notifications, Do Not Disturb access, allow Overwrite."
            )
            return
        }
        try {
            am.ringerMode = mode
        } catch (t: SecurityException) {
            RingLog.e("ringer refused: " + t.message)
        }
    }

    private fun hasDndAccess(): Boolean {
        val nm = svc.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return false
        return nm.isNotificationPolicyAccessGranted
    }

    // ------------------------------------------------------------------
    // System
    // ------------------------------------------------------------------

    private fun torch(on: Boolean) {
        val cm = svc.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (cm == null) { RingLog.e("no CameraManager"); return }
        try {
            val id = cm.cameraIdList.firstOrNull { camId ->
                cm.getCameraCharacteristics(camId)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (id == null) { RingLog.e("no camera with a flash"); return }
            cm.setTorchMode(id, on)
            torchOn = on
        } catch (t: Throwable) {
            RingLog.e("torch: " + t.javaClass.simpleName + ": " + t.message)
        }
    }

    /** True only if the user granted Modify system settings. */
    private fun canWriteSettings(): Boolean {
        if (Settings.System.canWrite(svc)) return true
        RingLog.e(
            "this action needs Modify system settings. " +
                "Settings, Apps, Overwrite, Modify system settings."
        )
        return false
    }

    private fun brightnessBy(delta: Int) {
        if (!canWriteSettings()) return
        val cur = try {
            Settings.System.getInt(svc.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (t: Settings.SettingNotFoundException) {
            128
        }
        brightnessTo(cur + delta)
    }

    private fun brightnessTo(value: Int) {
        if (!canWriteSettings()) return
        val v = value.coerceIn(1, 255)
        try {
            // Automatic brightness overrides a manual write moments later.
            Settings.System.putInt(
                svc.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(svc.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v)
        } catch (t: Throwable) {
            RingLog.e("brightness: " + t.javaClass.simpleName + ": " + t.message)
        }
    }

    private fun toggleAutoRotate() {
        if (!canWriteSettings()) return
        try {
            val cur = Settings.System.getInt(
                svc.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0
            )
            Settings.System.putInt(
                svc.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (cur == 0) 1 else 0
            )
        } catch (t: Throwable) {
            RingLog.e("auto-rotate: " + t.javaClass.simpleName + ": " + t.message)
        }
    }

    private fun vibrate(ms: Long) {
        try {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
                val vm = svc.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                svc.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (v == null) { RingLog.e("no vibrator"); return }
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (t: Throwable) {
            RingLog.e("vibrate: " + t.javaClass.simpleName + ": " + t.message)
        }
    }

    private fun inputMethodPicker() {
        val imm = svc.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        if (imm == null) { RingLog.e("no InputMethodManager"); return }
        imm.showInputMethodPicker()
    }

    // ------------------------------------------------------------------
    // Intents
    // ------------------------------------------------------------------

    private fun startSafely(intent: Intent, what: String) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            svc.startActivity(intent)
        } catch (t: Throwable) {
            RingLog.e("could not open " + what + ": " + t.javaClass.simpleName + ": " + t.message)
        }
    }

    /** Fire a bare action. Also the escape hatch for any intent action at all. */
    private fun settings(action: String?) {
        if (action.isNullOrEmpty()) { RingLog.e("no intent action set on this step"); return }
        startSafely(Intent(action), action)
    }

    private fun appInfo(pkg: String?) {
        if (pkg.isNullOrEmpty()) { RingLog.e("app info: no package set"); return }
        val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        i.data = Uri.fromParts("package", pkg, null)
        startSafely(i, "app info for " + pkg)
    }

    private fun launchPackage(pkg: String?) {
        if (pkg.isNullOrEmpty()) { RingLog.e("launch: no package set on this step"); return }
        val intent = svc.packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) { RingLog.e("launch: " + pkg + " has no launcher activity"); return }
        startSafely(intent, pkg)
    }

    private fun launchActivity(spec: String?) {
        if (spec.isNullOrEmpty() || !spec.contains("/")) {
            RingLog.e("launch activity: expected package/class, got " + spec)
            return
        }
        val pkg = spec.substringBefore("/")
        var cls = spec.substringAfter("/")
        if (cls.startsWith(".")) cls = pkg + cls
        val i = Intent()
        i.component = ComponentName(pkg, cls)
        startSafely(i, spec)
    }

    private fun view(target: String?, prefix: String) {
        if (target.isNullOrEmpty()) { RingLog.e("no URL set on this step"); return }
        val url = if (target.contains("://")) target else prefix + target
        startSafely(Intent(Intent.ACTION_VIEW, Uri.parse(url)), url)
    }

    private fun webSearch(q: String?) {
        if (q.isNullOrEmpty()) { RingLog.e("web search: nothing to search for"); return }
        val i = Intent(Intent.ACTION_WEB_SEARCH)
        i.putExtra(SearchManager.QUERY, q)
        startSafely(i, "search for " + q)
    }

    private fun dial(number: String?) {
        if (number.isNullOrEmpty()) { RingLog.e("dial: no number set"); return }
        startSafely(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number)), number)
    }

    private fun sms(number: String?) {
        if (number.isNullOrEmpty()) { RingLog.e("sms: no number set"); return }
        startSafely(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + number)), number)
    }

    private fun share(text: String?) {
        if (text.isNullOrEmpty()) { RingLog.e("share: nothing to share"); return }
        val i = Intent(Intent.ACTION_SEND)
        i.type = "text/plain"
        i.putExtra(Intent.EXTRA_TEXT, text)
        startSafely(Intent.createChooser(i, null), "share sheet")
    }

    private fun copyText(text: String?) {
        if (text.isNullOrEmpty()) { RingLog.e("copy: nothing to copy"); return }
        val cb = svc.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (cb == null) { RingLog.e("no ClipboardManager"); return }
        cb.setPrimaryClip(ClipData.newPlainText("Overwrite", text))
        RingLog.i("copied " + text.length + " chars to the clipboard")
    }

    private fun contacts() {
        startSafely(Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI), "contacts")
    }

    private fun timer(seconds: String?) {
        val s = seconds?.trim()?.toIntOrNull()
        if (s == null || s <= 0) { RingLog.e("timer: expected a number of seconds, got " + seconds); return }
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
        i.putExtra(AlarmClock.EXTRA_LENGTH, s)
        i.putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        startSafely(i, s.toString() + "s timer")
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

    private fun centreX(): Float = screen().first / 2f
    private fun centreY(): Float = screen().second / 2f

    /** dx and dy are a unit direction; span is a fraction of that axis. */
    private fun swipe(dx: Float, dy: Float, span: Float, duration: Long) {
        val (w, h) = screen()
        val cx = w / 2f
        val cy = h / 2f
        val spanX = w * span
        val spanY = h * span
        val path = Path()
        path.moveTo(cx - dx * spanX, cy - dy * spanY)
        path.lineTo(cx + dx * spanX, cy + dy * spanY)
        dispatch(listOf(GestureDescription.StrokeDescription(path, 0L, duration)), "swipe")
    }

    private fun tapAt(x: Float, y: Float, duration: Long) {
        val path = Path()
        path.moveTo(x, y)
        path.lineTo(x, y)
        dispatch(listOf(GestureDescription.StrokeDescription(path, 0L, duration)), "tap")
    }

    private fun doubleTap(x: Float, y: Float) {
        val p1 = Path(); p1.moveTo(x, y); p1.lineTo(x, y)
        val p2 = Path(); p2.moveTo(x, y); p2.lineTo(x, y)
        dispatch(
            listOf(
                GestureDescription.StrokeDescription(p1, 0L, 50L),
                GestureDescription.StrokeDescription(p2, 140L, 50L)
            ),
            "double tap"
        )
    }

    private fun edgeSwipe(fromLeft: Boolean) {
        val (w, h) = screen()
        val y = h / 2f
        val path = Path()
        if (fromLeft) {
            path.moveTo(2f, y)
            path.lineTo(w * 0.45f, y)
        } else {
            path.moveTo(w - 2f, y)
            path.lineTo(w * 0.55f, y)
        }
        dispatch(listOf(GestureDescription.StrokeDescription(path, 0L, 220L)), "edge swipe")
    }

    private fun pullDown() {
        val (w, h) = screen()
        val path = Path()
        path.moveTo(w / 2f, 2f)
        path.lineTo(w / 2f, h * 0.6f)
        dispatch(listOf(GestureDescription.StrokeDescription(path, 0L, 320L)), "pull down")
    }

    private fun pinch(inward: Boolean) {
        val (w, h) = screen()
        val cx = w / 2f
        val cy = h / 2f
        val near = h * 0.08f
        val far = h * 0.28f
        val a = Path()
        val b = Path()
        if (inward) {
            a.moveTo(cx, cy - far); a.lineTo(cx, cy - near)
            b.moveTo(cx, cy + far); b.lineTo(cx, cy + near)
        } else {
            a.moveTo(cx, cy - near); a.lineTo(cx, cy - far)
            b.moveTo(cx, cy + near); b.lineTo(cx, cy + far)
        }
        dispatch(
            listOf(
                GestureDescription.StrokeDescription(a, 0L, 300L),
                GestureDescription.StrokeDescription(b, 0L, 300L)
            ),
            "pinch"
        )
    }

    private fun tapAtArg(arg: String?) {
        val p = numbers(arg, 2) ?: run {
            RingLog.e("tap at: expected x,y in pixels, got " + arg); return
        }
        tapAt(p[0], p[1], 60L)
    }

    private fun swipeArg(arg: String?) {
        val p = numbers(arg, 4) ?: run {
            RingLog.e("swipe: expected x1,y1,x2,y2 in pixels, got " + arg); return
        }
        val path = Path()
        path.moveTo(p[0], p[1])
        path.lineTo(p[2], p[3])
        dispatch(listOf(GestureDescription.StrokeDescription(path, 0L, 300L)), "custom swipe")
    }

    private fun numbers(arg: String?, count: Int): List<Float>? {
        if (arg.isNullOrEmpty()) return null
        val parts = arg.split(",").mapNotNull { it.trim().toFloatOrNull() }
        return if (parts.size == count) parts else null
    }

    private fun dispatch(strokes: List<GestureDescription.StrokeDescription>, what: String) {
        val b = GestureDescription.Builder()
        for (s in strokes) b.addStroke(s)
        val ok = svc.dispatchGesture(b.build(), null, null)
        if (!ok) RingLog.e("dispatchGesture(" + what + ") refused")
    }
}
