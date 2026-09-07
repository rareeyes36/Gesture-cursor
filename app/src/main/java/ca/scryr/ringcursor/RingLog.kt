package ca.scryr.ringcursor

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Process-wide log. The AccessibilityService writes to it, MainActivity reads it.
 * Deliberately not a bound service or a broadcast: both live in the same process,
 * and a shared object with a lock is less to go wrong than binder plumbing.
 */
object RingLog {

    private const val TAG = "RingCursor"
    private const val CAP = 1200

    private val lines = ArrayDeque<String>(CAP)
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile var version: Long = 0L
        private set

    fun d(msg: String) = add("  ", msg)
    fun i(msg: String) = add("* ", msg)
    fun e(msg: String) = add("!!", msg)

    private fun add(prefix: String, msg: String) {
        val line = "${fmt.format(Date())} $prefix $msg"
        synchronized(lines) {
            if (lines.size >= CAP) lines.removeFirst()
            lines.addLast(line)
            version++
        }
        Log.d(TAG, line)
    }

    fun snapshot(): String = synchronized(lines) { lines.joinToString("\n") }

    fun clear() {
        synchronized(lines) {
            lines.clear()
            version++
        }
    }

    /** Writes the buffer to app-external files dir. Returns the file, or null. */
    fun dump(ctx: Context): File? = try {
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val f = File(dir, "ringcursor-$stamp.log")
        f.writeText(snapshot())
        i("dumped log -> ${f.absolutePath}")
        f
    } catch (t: Throwable) {
        e("dump failed: ${t.message}")
        null
    }
}

fun ByteArray.hex(): String = joinToString("-") { "%02X".format(it) }

/** Signed 8-bit read. Kotlin Bytes are already signed, this just makes intent explicit. */
fun ByteArray.i8(index: Int): Int = this[index].toInt()

/** Unsigned 8-bit read. */
fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF

/** Little-endian unsigned 16-bit read. */
fun ByteArray.u16le(index: Int): Int = u8(index) or (u8(index + 1) shl 8)
