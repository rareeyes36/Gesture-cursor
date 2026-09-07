package ca.scryr.ringcursor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * The pointer itself. Deliberately a small window (SIZE_DP square) moved by
 * WindowManager.updateViewLayout rather than a full-screen overlay that
 * redraws on every report.
 *
 * The discarded alternative was a full-screen transparent view with the cursor
 * drawn inside it and invalidate() per move. That makes motion trails and
 * effects trivial, but it composites the whole screen area on every single
 * report. If you later want trails, switch to that approach; for a plain
 * pointer this is far cheaper.
 */
class CursorView(context: Context) : View(context) {

    companion object {
        const val SIZE_DP = 44
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(210, 255, 255, 255)
    }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(230, 20, 20, 20)
        strokeWidth = 2f * resources.displayMetrics.density
    }

    private val pressedRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(230, 90, 200, 255)
        strokeWidth = 3f * resources.displayMetrics.density
    }

    /**
     * Named ringPressed, not `pressed`: View already declares
     * setPressed(boolean), and a Kotlin property called `pressed` compiles to
     * the same JVM signature, which the compiler rejects as an accidental
     * override.
     */
    var ringPressed: Boolean = false
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val d = resources.displayMetrics.density
        val core = 5f * d
        val ring = 14f * d

        canvas.drawCircle(cx, cy, core, fill)
        canvas.drawCircle(cx, cy, core, stroke)
        canvas.drawCircle(cx, cy, ring, if (ringPressed) pressedRing else stroke)
    }
}
