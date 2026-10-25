package com.duoopen.shell

import java.util.regex.Pattern

/**
 * Parses the logcat lines Samsung's own components write about the hinge.
 * Pure Kotlin so the formats can be unit-tested; used by the shell-side
 * reader in [DuoShellService].
 *
 * - **Sensor HAL** (`sensors-hal` tag), found by Duo Open for Galaxy on a
 *   Galaxy Z Fold 8 Ultra: `[0]folding_angle ts=<boot ns> ns value [ 89/0]`
 *   every 11°, `[1]lid_angle_fusion ts=<boot ns> ns value [2/ 82/3]` every
 *   10°, and `[0]folding_angle device_mode(0 -> 1)` from the hall sensor the
 *   moment the phone shuts (1) or opens (0).
 * - **Fold interactive wallpaper** (`SprWallpaper|FoldInteractive` tag),
 *   format per Duo Fold Live: `onCommand: action=<a>, … mCurrentAngle=<deg>
 *   … isVisible=true` on a Fold 8; a Fold 7 writes `action[<a>] …
 *   mCurrentAngle[<deg>] … isVisible[true]`.
 */
object SamsungHingeLog {
    /** One accepted line. [halTimestampNs] is the sensor's boot-time stamp, 0 when the line had none. */
    data class Reading(val angle: Float, val source: Int, val halTimestampNs: Long = 0L)

    /**
     * @param wallpaperAction the wallpaper command we ping with; "" disables
     *   wallpaper lines (anyone else's commands are not angles for us).
     */
    fun parse(line: String, wallpaperAction: String): Reading? = when {
        line.contains("sensors-hal") -> parseHal(line)
        line.contains("SprWallpaper|FoldInteractive") -> parseWallpaper(line, wallpaperAction)
        else -> null
    }

    private fun parseHal(line: String): Reading? {
        val mode = HAL_DEVICE_MODE.matcher(line)
        if (mode.find()) {
            return Reading(0f, if (mode.group(1) == "1") ShellProtocol.SOURCE_SHUT else ShellProtocol.SOURCE_UNSHUT)
        }
        val folding = HAL_FOLDING.matcher(line)
        val m = if (folding.find()) folding else HAL_LID.matcher(line).takeIf { it.find() } ?: return null
        val v = m.group(2)?.toFloatOrNull() ?: return null
        if (v !in 0f..180f) return null
        val ts = m.group(1)?.toLongOrNull() ?: 0L
        return Reading(v, if (m === folding) ShellProtocol.SOURCE_FOLDING else ShellProtocol.SOURCE_LID, ts)
    }

    private fun parseWallpaper(line: String, action: String): Reading? {
        if (action.isEmpty() || !line.contains("onCommand:")) return null
        if (!(line.contains("action=$action,") || line.contains("action[$action]"))) return null
        if (!(line.contains("isVisible=true") || line.contains("isVisible[true]"))) return null
        val m = WALLPAPER_ANGLE.matcher(line)
        if (!m.find()) return null
        val v = m.group(1)?.toFloatOrNull() ?: return null
        if (v !in 0f..180f) return null
        return Reading(v, ShellProtocol.SOURCE_WALLPAPER)
    }

    /** The logcat filter specs that produce the lines [parse] understands. */
    val LOGCAT_FILTERS = listOf("sensors-hal:I", "SprWallpaper|FoldInteractive:V", "*:S")

    private val HAL_DEVICE_MODE: Pattern = Pattern.compile("\\]folding_angle device_mode\\(\\d+ -> (\\d+)\\)")
    private val HAL_FOLDING: Pattern = Pattern.compile("\\]folding_angle ts=(\\d+) ns value \\[\\s*(\\d+)/")
    private val HAL_LID: Pattern = Pattern.compile("\\]lid_angle_fusion ts=(\\d+) ns value \\[\\d+/\\s*(\\d+)/")
    private val WALLPAPER_ANGLE: Pattern = Pattern.compile("mCurrentAngle(?:=|\\[)([0-9]+(?:\\.[0-9]+)?)")
}
