package com.superboox.quickaccess

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small on-device event log for troubleshooting ("Copy debug report").
 *
 * Kept in memory and mirrored to a file in app-private storage so it survives
 * the service being restarted or the app crashing. Never leaves the device
 * unless the user copies or shares the report.
 */
object DebugLog {
    private const val TAG = "SuperBoox"
    private const val MAX_LINES = 800
    private const val FILE_NAME = "debug.log"

    private val lines = ArrayDeque<String>()
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private var file: File? = null
    private var appendsSinceCompact = 0

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, FILE_NAME)
        file = f
        runCatching { if (f.exists()) f.readLines().takeLast(MAX_LINES).forEach(lines::addLast) }
    }

    fun i(area: String, msg: String) = write("I", area, msg, null)

    fun w(area: String, msg: String, t: Throwable? = null) = write("W", area, msg, t)

    fun e(area: String, msg: String, t: Throwable? = null) = write("E", area, msg, t)

    @Synchronized
    fun entries(): List<String> = lines.toList()

    @Synchronized
    fun clear() {
        lines.clear()
        runCatching { file?.writeText("") }
    }

    @Synchronized
    private fun write(level: String, area: String, msg: String, t: Throwable?) {
        val text = buildString {
            append(time.format(Date())).append(' ').append(level).append(" [").append(area).append("] ").append(msg)
            if (t != null) append("\n    ").append(Log.getStackTraceString(t).trim().lines().take(12).joinToString("\n    "))
        }
        when (level) {
            "E" -> Log.e(TAG, text)
            "W" -> Log.w(TAG, text)
            else -> Log.i(TAG, text)
        }
        lines.addLast(text)
        while (lines.size > MAX_LINES) lines.removeFirst()
        val f = file ?: return
        runCatching {
            // Append cheaply; rewrite the file from memory now and then so it stays bounded.
            if (++appendsSinceCompact >= 200) {
                f.writeText(lines.joinToString("\n", postfix = "\n"))
                appendsSinceCompact = 0
            } else {
                f.appendText(text + "\n")
            }
        }
    }
}
