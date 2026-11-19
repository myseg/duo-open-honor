package com.duoopen.ui

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Process
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Collects this app's own log lines (an app can always read its own logs,
 * no adb or READ_LOGS needed) plus a short device summary, and hands them
 * to the share sheet — for bug reports from people without a computer.
 */
object DebugLog {
    private val TAGS = listOf(
        "DuoOverlay", "DuoHonor", "DuoHinge", "DuoDeviceState", "DuoDual",
        "DuoShizuku", "DuoAngleFeed", "DuoShader", "DuoWallpaper",
    )
    private const val MAX_LINES = 3_000

    suspend fun collect(context: Context): String = withContext(Dispatchers.IO) {
        val header = buildString {
            appendLine("Duo Open debug log")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}")
            appendLine("Build: ${Build.DISPLAY}")
            val app = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
            appendLine("App: ${context.packageName} $app")
            val dm = context.getSystemService(DisplayManager::class.java)
            for (d in dm?.displays.orEmpty()) {
                val m = d.mode
                appendLine("Display ${d.displayId} \"${d.name}\" ${m.physicalWidth}x${m.physicalHeight} state=${d.state} flags=0x${Integer.toHexString(d.flags)}")
            }
            appendLine()
        }
        val cmd = mutableListOf("logcat", "-d", "-v", "time", "--pid=${Process.myPid()}", "-s")
        cmd += TAGS
        val lines = runCatching {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readLines()
            p.waitFor()
            out
        }.getOrElse { listOf("Couldn't read the log: ${it.message}") }
        val body = lines.takeLast(MAX_LINES).joinToString("\n")
        header + body.ifBlank { "(no log lines yet — enable the service, open and close the phone, then try again)" }
    }

    fun share(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Duo Open debug log")
            .putExtra(Intent.EXTRA_TEXT, text)
        val chooser = Intent.createChooser(send, "Share debug log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(chooser) }.isFailure) {
            Toast.makeText(context, "Couldn't open the share sheet", Toast.LENGTH_SHORT).show()
        }
    }
}
