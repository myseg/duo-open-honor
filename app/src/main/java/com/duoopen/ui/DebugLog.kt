package com.duoopen.ui

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.duoopen.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Collects this app's own log lines (an app can always read its own logs,
 * no adb or READ_LOGS needed) plus a short device summary, and hands them
 * to the share sheet — for bug reports from people without a computer.
 */
object DebugLog {
    private const val MAX_LINES = 3_000

    suspend fun collect(context: Context): String = withContext(Dispatchers.IO) {
        val header = buildString {
            appendLine("Duo Open debug log")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}")
            appendLine("Build: ${Build.DISPLAY}")
            val app = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
            appendLine("App: ${context.packageName} $app")
            val dm = context.getSystemService(DisplayManager::class.java)
            val listed = dm?.displays.orEmpty().associateBy { it.displayId }
            val all = LinkedHashMap(listed)
            runCatching { dm?.getDisplays("android.hardware.display.category.REAR")?.forEach { all[it.displayId] = it } }
            for (id in 1..8) if (id !in all) runCatching { dm?.getDisplay(id) }.getOrNull()?.let { all[id] = it }
            for (d in all.values) {
                val m = d.mode
                val hidden = if (d.displayId in listed) "" else " (hidden)"
                appendLine("Display ${d.displayId}$hidden \"${d.name}\" ${m.physicalWidth}x${m.physicalHeight} state=${d.state} flags=0x${Integer.toHexString(d.flags)}")
            }
            val cr = context.contentResolver
            for (k in listOf("duoopen_honor_mode", "duoopen_honor_open_at", "duoopen_honor_close_at", "duoopen_honor_dual_from", "duoopen_honor_anim", "duoopen_honor_locked")) {
                Settings.Global.getString(cr, k)?.let { appendLine("$k=$it") }
            }
            val enabled = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            appendLine("Accessibility service enabled: ${enabled.contains(context.packageName + "/")}")
            appendLine("Overlay permission: ${Settings.canDrawOverlays(context)}")
            appendLine("Secure settings permission: ${context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED}")
            appendLine("Shizuku package installed: ${runCatching { context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0); true }.getOrDefault(false)}")
            appendLine()
        }
        // The app's own copy: MagicOS hides third-party logcat output.
        val body = Log.dump().takeLast(MAX_LINES).joinToString("\n")
        header + body.ifBlank { "(no log lines yet — check that Duo Open's accessibility service is on, open and close the phone, then try again)" }
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
