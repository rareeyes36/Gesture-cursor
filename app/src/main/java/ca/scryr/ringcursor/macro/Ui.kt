package ca.scryr.ringcursor.macro

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Small view helpers.
 *
 * The screens are built in code rather than XML on purpose. This module has a
 * history of resource-linking failures on toolchain bumps (see the commit that
 * moved motionEventSources out of the accessibility config), and a UI with no
 * resource references cannot hit that class of build break at all.
 *
 * The palette is fixed rather than theme-derived. The app theme is DayNight,
 * and a control panel that has to stay readable while you are mid-way through
 * rebinding your only keyboard is not the place to find out that a colour
 * resolved badly.
 */
object Ui {
    const val BG = 0xFF101318.toInt()
    const val CARD = 0xFF191E26.toInt()
    const val RULE = 0xFF2A313C.toInt()
    const val TEXT = 0xFFE8EBF0.toInt()
    const val DIM = 0xFF98A1AF.toInt()
    const val FAINT = 0xFF6B7482.toInt()
    const val ACCENT = 0xFF8D9DFF.toInt()
    const val OK = 0xFF4FC08D.toInt()
    const val WARN = 0xFFE0A33E.toInt()
    const val BAD = 0xFFE8798C.toInt()

    fun dp(ctx: Context, v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics
    ).toInt()

    fun column(ctx: Context): LinearLayout {
        val l = LinearLayout(ctx)
        l.orientation = LinearLayout.VERTICAL
        return l
    }

    fun row(ctx: Context): LinearLayout {
        val l = LinearLayout(ctx)
        l.orientation = LinearLayout.HORIZONTAL
        l.gravity = Gravity.CENTER_VERTICAL
        return l
    }

    fun text(
        ctx: Context,
        s: CharSequence,
        size: Float = 14f,
        color: Int = TEXT,
        bold: Boolean = false,
        mono: Boolean = false
    ): TextView {
        val t = TextView(ctx)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
        if (mono) t.typeface = android.graphics.Typeface.MONOSPACE
        return t
    }

    fun card(ctx: Context): LinearLayout {
        val l = column(ctx)
        val bg = GradientDrawable()
        bg.setColor(CARD)
        bg.cornerRadius = dp(ctx, 8).toFloat()
        bg.setStroke(dp(ctx, 1), RULE)
        l.background = bg
        val p = dp(ctx, 14)
        l.setPadding(p, dp(ctx, 12), p, dp(ctx, 12))
        return l
    }

    /** A filled circle used as the live-activity indicator. */
    fun dot(ctx: Context, color: Int, sizeDp: Int = 9): View {
        val v = View(ctx)
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(color)
        v.background = d
        val s = dp(ctx, sizeDp)
        v.layoutParams = LinearLayout.LayoutParams(s, s)
        return v
    }

    fun divider(ctx: Context): View {
        val v = View(ctx)
        v.setBackgroundColor(RULE)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1))
        lp.topMargin = dp(ctx, 10)
        lp.bottomMargin = dp(ctx, 10)
        v.layoutParams = lp
        return v
    }

    fun lp(
        width: Int = ViewGroup.LayoutParams.MATCH_PARENT,
        height: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
        top: Int = 0,
        weight: Float = 0f
    ): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(width, height, weight)
        p.topMargin = top
        return p
    }

    /** Colour for a control's indicator, by how recently it fired. */
    fun activityColour(lastSeen: Long): Int {
        val age = System.currentTimeMillis() - lastSeen
        return when {
            lastSeen == 0L -> FAINT
            age < 400L -> OK
            age < 1200L -> Color.rgb(60, 130, 100)
            else -> FAINT
        }
    }

    fun captureColour(c: Capture): Int = when (c) {
        Capture.FULL -> OK
        Capture.BLOCKED -> BAD
    }

    fun captureLabel(c: Capture): String = when (c) {
        Capture.FULL -> "full"
        Capture.BLOCKED -> "blocked"
    }
}
