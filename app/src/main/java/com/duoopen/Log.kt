package com.duoopen

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Drop-in for [android.util.Log] that also keeps the last lines in memory,
 * so the app can share its own log. Some phones (Honor / MagicOS) hide or
 * encrypt third-party apps' logcat output, even from the app itself.
 */
object Log {
    private const val MAX_LINES = 4_000
    private val lines = ArrayDeque<String>(MAX_LINES)
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun d(tag: String, msg: String): Int = put('D', tag, msg, null) { android.util.Log.d(tag, msg) }
    fun i(tag: String, msg: String): Int = put('I', tag, msg, null) { android.util.Log.i(tag, msg) }
    fun i(tag: String, msg: String, t: Throwable?): Int = put('I', tag, msg, t) { android.util.Log.i(tag, msg, t) }
    fun w(tag: String, msg: String): Int = put('W', tag, msg, null) { android.util.Log.w(tag, msg) }
    fun w(tag: String, msg: String, t: Throwable?): Int = put('W', tag, msg, t) { android.util.Log.w(tag, msg, t) }
    fun e(tag: String, msg: String): Int = put('E', tag, msg, null) { android.util.Log.e(tag, msg) }
    fun e(tag: String, msg: String, t: Throwable?): Int = put('E', tag, msg, t) { android.util.Log.e(tag, msg, t) }

    /** Everything kept so far, oldest first. */
    fun dump(): List<String> = synchronized(lines) { lines.toList() }

    private inline fun put(level: Char, tag: String, msg: String, t: Throwable?, system: () -> Int): Int {
        val line = buildString {
            synchronized(time) { append(time.format(Date())) }
            append(' ').append(level).append('/').append(tag).append(": ").append(msg)
            if (t != null) append("  [").append(t.javaClass.simpleName).append(": ").append(t.message).append(']')
        }
        synchronized(lines) {
            if (lines.size >= MAX_LINES) lines.removeFirst()
            lines.addLast(line)
        }
        // Unit tests run without the Android log.
        return runCatching { system() }.getOrDefault(0)
    }
}
