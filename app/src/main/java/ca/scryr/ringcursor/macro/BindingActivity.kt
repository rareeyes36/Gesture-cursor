package ca.scryr.ringcursor.macro

import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Bind one control.
 *
 * A binding is a trigger plus an ordered list of steps, and a plain
 * single-action binding is just a one-step macro. That keeps the "combo"
 * requirement from the brief and the simple case on the same code path.
 */
class BindingActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DEVICE = "device"
        const val EXTRA_CONTROL = "control"
    }

    private lateinit var deviceKey: String
    private lateinit var controlKey: String

    private lateinit var root: LinearLayout
    private lateinit var existingHost: LinearLayout
    private lateinit var stepHost: LinearLayout
    private lateinit var triggerSpinner: Spinner

    /** Actions this device can actually perform, in menu order. */
    private val actions: List<ActionType> by lazy {
        ActionType.entries.filter { Build.VERSION.SDK_INT >= it.minSdk }
    }

    private class StepView(
        val container: LinearLayout,
        val spinner: Spinner,
        val arg: EditText,
        val pause: EditText
    )

    private val stepViews = ArrayList<StepView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Registry.init(this)
        supportActionBar?.hide()

        deviceKey = intent.getStringExtra(EXTRA_DEVICE) ?: ""
        controlKey = intent.getStringExtra(EXTRA_CONTROL) ?: ""
        if (deviceKey.isEmpty() || controlKey.isEmpty()) { finish(); return }

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Ui.BG)
        root = Ui.column(this)
        val pad = Ui.dp(this, 16)
        root.setPadding(pad, pad, pad, Ui.dp(this, 48))
        scroll.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)

        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        build()
    }

    override fun onResume() {
        super.onResume()
        renderExisting()
    }

    private fun build() {
        val ctl = Registry.control(deviceKey, controlKey)
        val dev = Registry.device(deviceKey)

        root.addView(Ui.text(this, ctl?.label ?: controlKey, 24f, Ui.TEXT, bold = true))
        root.addView(
            Ui.text(this, (dev?.name ?: deviceKey) + "  -  " + controlKey, 12f, Ui.FAINT, mono = true),
            Ui.lp(top = Ui.dp(this, 2))
        )

        if (ctl != null && ctl.capture == Capture.BLOCKED) {
            root.addView(
                Ui.text(
                    this,
                    "This key is consumed by the system before an accessibility service sees it. " +
                        "You can bind it, but the phone will act on it too.",
                    12.5f, Ui.BAD
                ),
                Ui.lp(top = Ui.dp(this, 8))
            )
        }

        // ---- existing bindings ----
        root.addView(
            Ui.text(this, "BINDINGS", 11f, Ui.FAINT, mono = true),
            Ui.lp(top = Ui.dp(this, 20))
        )
        existingHost = Ui.column(this)
        root.addView(existingHost, Ui.lp(top = Ui.dp(this, 6)))

        // ---- new binding ----
        root.addView(
            Ui.text(this, "ADD A BINDING", 11f, Ui.FAINT, mono = true),
            Ui.lp(top = Ui.dp(this, 24))
        )

        val card = Ui.card(this)
        card.addView(Ui.text(this, "When I press", 13f, Ui.DIM))

        triggerSpinner = Spinner(this)
        val triggerNames = Trigger.PRESETS.map { it.label() + explain(it) }
        triggerSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, triggerNames
        )
        card.addView(triggerSpinner, Ui.lp(top = Ui.dp(this, 4)))

        card.addView(
            Ui.text(this, "Do this", 13f, Ui.DIM),
            Ui.lp(top = Ui.dp(this, 14))
        )
        stepHost = Ui.column(this)
        card.addView(stepHost, Ui.lp(top = Ui.dp(this, 4)))

        val addStep = Button(this)
        addStep.text = "Add another step"
        addStep.setOnClickListener { addStepView(null) }
        card.addView(addStep, Ui.lp(top = Ui.dp(this, 6)))

        val save = Button(this)
        save.text = "Save binding"
        save.setOnClickListener { save() }
        card.addView(save, Ui.lp(top = Ui.dp(this, 10)))

        root.addView(card, Ui.lp(top = Ui.dp(this, 6)))

        addStepView(null)
    }

    private fun explain(t: Trigger): String = when {
        t.hold -> "  (press and hold)"
        t.pattern == listOf(2, 2) -> "  (two, pause, two)"
        else -> ""
    }

    // ------------------------------------------------------------------
    // Existing bindings
    // ------------------------------------------------------------------

    private fun renderExisting() {
        existingHost.removeAllViews()
        val list = Registry.bindingsFor(deviceKey, controlKey)
        if (list.isEmpty()) {
            existingHost.addView(Ui.text(this, "None yet.", 13f, Ui.FAINT))
            return
        }
        for (b in list) {
            val card = Ui.card(this)
            val r = Ui.row(this)
            val col = Ui.column(this)
            col.addView(Ui.text(this, b.trigger.label(), 15f, Ui.ACCENT, bold = true))
            for ((i, s) in b.steps.withIndex()) {
                val suffix = if (s.pauseAfterMs > 0 && i < b.steps.size - 1)
                    "   then wait " + s.pauseAfterMs + " ms" else ""
                val argTxt = if (!s.arg.isNullOrEmpty()) " (" + s.arg + ")" else ""
                col.addView(
                    Ui.text(this, (i + 1).toString() + ". " + s.action.label + argTxt + suffix, 13f, Ui.TEXT)
                )
            }
            r.addView(col, Ui.lp(width = 0, weight = 1f))

            val del = Button(this)
            del.text = "Delete"
            del.setOnClickListener {
                Registry.removeBinding(b.id)
                renderExisting()
            }
            r.addView(del)
            card.addView(r)
            existingHost.addView(card, Ui.lp(top = Ui.dp(this, 6)))
        }
    }

    // ------------------------------------------------------------------
    // Step editor
    // ------------------------------------------------------------------

    private fun addStepView(preset: Step?) {
        val box = Ui.column(this)
        box.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6))

        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            actions.map { it.group + " / " + it.label }
        )
        box.addView(spinner)

        val arg = EditText(this)
        arg.hint = "package name, e.g. com.spotify.music"
        arg.setTextColor(Ui.TEXT)
        arg.setHintTextColor(Ui.FAINT)
        arg.inputType = InputType.TYPE_CLASS_TEXT
        arg.visibility = View.GONE
        box.addView(arg, Ui.lp(top = Ui.dp(this, 4)))

        val pause = EditText(this)
        pause.hint = "pause after, ms"
        pause.setTextColor(Ui.TEXT)
        pause.setHintTextColor(Ui.FAINT)
        pause.inputType = InputType.TYPE_CLASS_NUMBER
        box.addView(pause, Ui.lp(top = Ui.dp(this, 4)))

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                arg.visibility = if (actions[pos].needsArg) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(p: AdapterView<*>?) { }
        }

        val remove = Button(this)
        remove.text = "Remove step"
        box.addView(remove, Ui.lp(top = Ui.dp(this, 4)))

        val sv = StepView(box, spinner, arg, pause)
        remove.setOnClickListener {
            if (stepViews.size <= 1) {
                Toast.makeText(this, "A binding needs at least one step", Toast.LENGTH_SHORT).show()
            } else {
                stepViews.remove(sv)
                stepHost.removeView(box)
            }
        }

        if (preset != null) {
            val idx = actions.indexOf(preset.action)
            if (idx >= 0) spinner.setSelection(idx)
            if (preset.arg != null) arg.setText(preset.arg)
            if (preset.pauseAfterMs > 0) pause.setText(preset.pauseAfterMs.toString())
        }

        stepViews.add(sv)
        stepHost.addView(box)
        if (stepViews.size > 1) stepHost.addView(Ui.divider(this))
    }

    // ------------------------------------------------------------------
    // Save
    // ------------------------------------------------------------------

    private fun save() {
        val steps = ArrayList<Step>()
        for (sv in stepViews) {
            val pos = sv.spinner.selectedItemPosition
            if (pos < 0 || pos >= actions.size) continue
            val a = actions[pos]
            val argText = sv.arg.text.toString().trim()
            if (a.needsArg && argText.isEmpty()) {
                Toast.makeText(this, a.label + " needs a package name", Toast.LENGTH_LONG).show()
                return
            }
            steps.add(
                Step(
                    action = a,
                    arg = if (argText.isEmpty()) null else argText,
                    pauseAfterMs = sv.pause.text.toString().trim().toIntOrNull() ?: 0
                )
            )
        }
        if (steps.isEmpty()) {
            Toast.makeText(this, "Nothing to save", Toast.LENGTH_SHORT).show()
            return
        }

        val tPos = triggerSpinner.selectedItemPosition.coerceIn(0, Trigger.PRESETS.size - 1)
        val trigger = Trigger.PRESETS[tPos]

        Registry.putBinding(Binding(deviceKey, controlKey, trigger, steps))

        // A binding is useless while the control is disabled, so saving one
        // re-enables it rather than leaving the user to find the other switch.
        Registry.setControlEnabled(deviceKey, controlKey, true)

        Toast.makeText(this, "Bound " + trigger.label(), Toast.LENGTH_SHORT).show()
        renderExisting()
    }
}
