package ca.scryr.ringcursor.macro

import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * The three numbers the recogniser runs on, plus the invariant between two of
 * them.
 *
 * stepGap has to clear tapGap by a wide margin. If the two windows sit close
 * together there is no reliable way to tell "still tapping" from "starting the
 * next burst", and a double-double becomes a coin flip. Rather than trust the
 * user to respect that, save() clamps it.
 */
class TimingActivity : AppCompatActivity() {

    private lateinit var tapGap: EditText
    private lateinit var stepGap: EditText
    private lateinit var hold: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Registry.init(this)
        supportActionBar?.hide()

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Ui.BG)
        val root = Ui.column(this)
        val pad = Ui.dp(this, 16)
        root.setPadding(pad, pad, pad, Ui.dp(this, 48))
        scroll.addView(root, ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(scroll)

        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        root.addView(Ui.text(this, "Timing", 24f, Ui.TEXT, bold = true))
        root.addView(
            Ui.text(
                this,
                "A control bound to exactly one trigger fires with no added delay. " +
                    "These windows only cost time on controls where a longer trigger " +
                    "shares the same opening.",
                13f, Ui.DIM
            ),
            Ui.lp(top = Ui.dp(this, 6))
        )

        val t = Registry.timing
        val card = Ui.card(this)

        tapGap = field(card, "Tap gap (ms)",
            "Longest pause still counted as part of one burst.", t.tapGapMs)
        stepGap = field(card, "Sequence gap (ms)",
            "Longest pause between bursts in a multi-step trigger.", t.stepGapMs)
        hold = field(card, "Hold threshold (ms)",
            "A press held longer than this becomes a hold.", t.holdMs)

        val note = Ui.text(
            this,
            "Sequence gap is forced to at least 2.5x the tap gap on save.",
            12f, Ui.WARN
        )
        card.addView(note, Ui.lp(top = Ui.dp(this, 10)))

        val save = Button(this)
        save.text = "Save"
        save.setOnClickListener { save() }
        card.addView(save, Ui.lp(top = Ui.dp(this, 10)))

        val reset = Button(this)
        reset.text = "Reset to defaults"
        reset.setOnClickListener {
            Registry.updateTiming(Timing())
            val d = Registry.timing
            tapGap.setText(d.tapGapMs.toString())
            stepGap.setText(d.stepGapMs.toString())
            hold.setText(d.holdMs.toString())
            Toast.makeText(this, "Defaults restored", Toast.LENGTH_SHORT).show()
        }
        card.addView(reset, Ui.lp(top = Ui.dp(this, 6)))

        root.addView(card, Ui.lp(top = Ui.dp(this, 14)))
    }

    private fun field(host: LinearLayout, label: String, help: String, value: Long): EditText {
        host.addView(Ui.text(this, label, 14f, Ui.TEXT, bold = true), Ui.lp(top = Ui.dp(this, 12)))
        host.addView(Ui.text(this, help, 12f, Ui.DIM), Ui.lp(top = Ui.dp(this, 1)))
        val e = EditText(this)
        e.inputType = InputType.TYPE_CLASS_NUMBER
        e.setTextColor(Ui.TEXT)
        e.setHintTextColor(Ui.FAINT)
        e.setText(value.toString())
        host.addView(e, Ui.lp(top = Ui.dp(this, 2)))
        return e
    }

    private fun save() {
        val t = Timing(
            tapGapMs = tapGap.text.toString().trim().toLongOrNull() ?: 260L,
            stepGapMs = stepGap.text.toString().trim().toLongOrNull() ?: 700L,
            holdMs = hold.text.toString().trim().toLongOrNull() ?: 500L
        )
        Registry.updateTiming(t)
        val applied = Registry.timing
        stepGap.setText(applied.stepGapMs.toString())
        Toast.makeText(
            this,
            "Saved. Sequence gap " + applied.stepGapMs + " ms",
            Toast.LENGTH_SHORT
        ).show()
    }
}
