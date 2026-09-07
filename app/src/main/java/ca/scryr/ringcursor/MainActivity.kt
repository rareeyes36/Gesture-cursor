package ca.scryr.ringcursor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Console, not a UI. Its job is to get the two permissions granted and then
 * show you exactly what the radio is doing, because v0.1 is a probe: the point
 * is to find out whether Boot Protocol Mode is real on this firmware.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView

    private val ui = Handler(Looper.getMainLooper())
    private var lastVersion = -1L
    private var demoOn = false

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 400)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // targetSdk 35+ enforces edge-to-edge with no opt-out, so the window no
        // longer stops at the system bars. Without this the status line sits
        // under the clock and the log runs beneath the navigation bar.
        val root = findViewById<android.view.View>(R.id.root)
        val basePad = (12 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                bars.left + basePad,
                bars.top + basePad,
                bars.right + basePad,
                bars.bottom + basePad
            )
            insets
        }

        status = findViewById(R.id.status)
        log = findViewById(R.id.log)
        scroll = findViewById(R.id.scroll)

        findViewById<Button>(R.id.btnPerms).setOnClickListener { requestBt() }

        findViewById<Button>(R.id.btnA11y).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(
                this,
                "Enable \"Ring Cursor\" under Installed apps / Downloaded services",
                Toast.LENGTH_LONG
            ).show()
        }

        findViewById<Button>(R.id.btnDump).setOnClickListener {
            val f = RingLog.dump(this)
            Toast.makeText(
                this,
                f?.absolutePath ?: "dump failed",
                Toast.LENGTH_LONG
            ).show()
        }

        findViewById<Button>(R.id.btnClear).setOnClickListener {
            RingLog.clear()
            refresh()
        }

        findViewById<Button>(R.id.btnDemo).setOnClickListener {
            val svc = RingCursorService.instance
            if (svc == null) {
                Toast.makeText(this, "Enable the service first", Toast.LENGTH_SHORT).show()
            } else {
                demoOn = !demoOn
                svc.setDemo(demoOn)
                Toast.makeText(
                    this,
                    if (demoOn) "Demo cursor on" else "Demo cursor off",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        RingLog.i("--- Ring Cursor 0.1-probe ---")
        RingLog.i("target: Manridy R6, ${RingUuids.KNOWN_MAC}")
        RingLog.i("experiment: write 0x00 to 0x2A4E, subscribe 0x2A33")
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }

    private fun refresh() {
        val bt = if (hasBt()) "BT ok" else "BT MISSING"
        val a11y = if (isServiceEnabled()) "service on" else "service OFF"
        status.text = "${RingCursorService.probeState}   |   $bt   |   $a11y"

        val v = RingLog.version
        if (v != lastVersion) {
            lastVersion = v
            log.text = RingLog.snapshot()
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    // -----------------------------------------------------------------------

    private fun btPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasBt(): Boolean = btPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestBt() {
        if (hasBt()) {
            Toast.makeText(this, "already granted", Toast.LENGTH_SHORT).show()
            return
        }
        ActivityCompat.requestPermissions(this, btPermissions(), 1)
    }

    private fun isServiceEnabled(): Boolean {
        val expected = "$packageName/${RingCursorService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }
}
