package com.duoopen.shell

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.duoopen.fold.HingeAngleSource
import com.duoopen.fold.HingeTracker

/**
 * The real hinge angle on Samsung foldables, where the public sensor only
 * reports 0/90/180. The Shizuku-side log reader ([DuoShellService]) follows
 * two of Samsung's own components:
 *
 * - the **sensor HAL's** hinge-sensor lines — a reading every 10–11° per
 *   sensor, two sensors interleaved, over the whole fold, whatever is on
 *   screen, nothing to set up (technique: Duo Open for Galaxy, MIT);
 * - the **Fold interactive wallpaper**, which logs the angle to 1° whenever
 *   it's sent a wallpaper command and is visible. If that wallpaper is the
 *   home wallpaper, a 1×1 wallpaper-showing anchor window pings it at
 *   [POLL_MS] (technique: Duo Fold Live, MIT).
 *
 * Both are fed into [HingeAngleSource]; the wallpaper's readings win while
 * they flow (finer, faster), the HAL's take over the moment they stop (an
 * app in front hides the wallpaper), and the tracker fills in between the
 * HAL's steps. Requires: Shizuku authorised.
 */
class SamsungAngleFeed(
    private val context: Context,
    private val handler: Handler,
    private val hinge: HingeAngleSource,
) {
    private var running = false
    private var action = ""
    private var anchor: View? = null
    private var anchorWm: WindowManager? = null
    private var anchorKey = ""
    private var lastCallbackUptime = 0L
    private var lastWallpaperUptime = 0L
    private var startedAt = 0L
    private var halReadings = 0
    private var wallpaperReadings = 0

    /** One line for the UI. */
    @Volatile
    var status: String = "Idle"
        private set

    val active: Boolean get() = running

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            if (!foldWallpaperActive(context)) {
                removeAnchor()
                handler.postDelayed(this, WALLPAPER_RECHECK_MS)
                return
            }
            val pm = context.getSystemService(PowerManager::class.java)
            if (pm?.isInteractive != false) {
                runCatching { ensureAnchor() }.onFailure { Log.w(TAG, "anchor: ${it.message}") }
                val a = anchor
                if (a != null && a.windowToken != null) {
                    runCatching {
                        WallpaperManager.getInstance(a.context).sendWallpaperCommand(a.windowToken, action, 0, 0, 0, null)
                    }.onFailure { Log.w(TAG, "wallpaper command: ${it.message}") }
                }
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    private val statusTick = object : Runnable {
        override fun run() {
            if (!running) return
            val b = ShizukuBridge.angleStatus()
            val age = if (lastCallbackUptime == 0L) -1 else SystemClock.uptimeMillis() - lastCallbackUptime
            status = if (b == null) "Reader unreachable" else buildString {
                append("Reader ${b.getString("state")}: $halReadings sensor + $wallpaperReadings wallpaper readings")
                append(if (age >= 0) " · last ${if (age < 10_000) "$age ms" else "${age / 1000} s"} ago" else " · fold the phone to see one")
                if (!foldWallpaperActive(context)) append(" · (Fold interactive wallpaper not set: sensor log only)")
            }
            // Nothing is logged while the hinge is still; let the posture and
            // the stops sensor speak for it until it moves again.
            if (age > STALE_MS || (age < 0 && SystemClock.uptimeMillis() - startedAt > STALE_MS)) hinge.clearExternal()
            handler.postDelayed(this, 1_000)
        }
    }

    fun start() {
        if (running) return
        if (!ShizukuBridge.ready) { status = "Shizuku not ready"; return }
        action = "com.duoopen.angle.READ_${SystemClock.elapsedRealtime()}"
        if (!ShizukuBridge.startAngles(action) { angle, at, source -> handler.post { onReading(angle, at, source) } }) {
            status = "Couldn't start the log reader"
            return
        }
        running = true
        startedAt = SystemClock.uptimeMillis()
        status = "Starting"
        Log.i(TAG, "Samsung angle feed started ($action)")
        handler.post(poll)
        handler.postDelayed(statusTick, 1_000)
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(poll)
        handler.removeCallbacks(statusTick)
        ShizukuBridge.stopAngles()
        hinge.externalFeedRunning = false
        hinge.clearExternal()
        removeAnchor()
        status = "Stopped"
        Log.i(TAG, "Samsung angle feed stopped")
    }

    /** Re-check the anchor after a panel swap. */
    fun onDisplayChanged() {
        if (running) runCatching { ensureAnchor() }
    }

    private fun onReading(angle: Float, sampleUptimeMs: Long, source: Int) {
        if (!running) return
        val now = SystemClock.uptimeMillis()
        when (source) {
            ShellProtocol.SOURCE_UNSHUT -> return
            ShellProtocol.SOURCE_SHUT -> {
                // The hall sensor: the phone is shut, whatever the last angle line said.
                lastCallbackUptime = now
                hinge.feedExternal(0f, sampleUptimeMs, HingeTracker.SOURCE_OTHER)
                return
            }
            ShellProtocol.SOURCE_WALLPAPER -> {
                lastWallpaperUptime = now
                wallpaperReadings++
                lastCallbackUptime = now
                hinge.externalFeedRunning = true
                hinge.feedExternal(angle, sampleUptimeMs, HingeTracker.SOURCE_OTHER)
            }
            ShellProtocol.SOURCE_FOLDING, ShellProtocol.SOURCE_LID -> {
                halReadings++
                lastCallbackUptime = now
                // Proven to work: real angles come from every posture, so the
                // device state needn't stand in for them from now on.
                hinge.externalFeedRunning = true
                // The wallpaper is finer and faster while it's visible; the
                // HAL's steps only matter once it has gone quiet.
                if (now - lastWallpaperUptime < WALLPAPER_PRIORITY_MS) return
                hinge.feedExternal(angle, sampleUptimeMs, source)
            }
        }
    }

    private fun displayKey(d: Display): String {
        val m = runCatching { d.mode }.getOrNull() ?: return "${d.displayId}"
        return "${d.displayId}:${m.physicalWidth}x${m.physicalHeight}"
    }

    /**
     * The wallpaper only answers commands from its current target window, so
     * the anchor shows wallpaper and lives on the default display; it is
     * re-created when that display swaps panels.
     */
    private fun ensureAnchor() {
        val dm = context.getSystemService(DisplayManager::class.java)
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val key = displayKey(display)
        if (anchor != null && key == anchorKey) return
        removeAnchor()
        val c = context.createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
        val wm = c.getSystemService(WindowManager::class.java)
        val v = View(c)
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "DuoOpenAngleAnchor"
        }
        wm.addView(v, params)
        anchor = v
        anchorWm = wm
        anchorKey = key
    }

    private fun removeAnchor() {
        val v = anchor ?: return
        runCatching { anchorWm?.removeViewImmediate(v) }
        anchor = null
        anchorWm = null
        anchorKey = ""
    }

    companion object {
        private const val TAG = "DuoAngleFeed"
        const val POLL_MS = 33L
        private const val WALLPAPER_RECHECK_MS = 3_000L
        private const val STALE_MS = 2_500L
        /** A HAL step this soon after a wallpaper reading adds nothing. */
        private const val WALLPAPER_PRIORITY_MS = 300L
        val FOLD_WALLPAPER = ComponentName(
            "com.samsung.android.wallpaper.live",
            "com.samsung.android.wallpaper.live.fold.FoldInteractive",
        )

        /** Whether Samsung's fold-reactive wallpaper is the current home wallpaper. */
        fun foldWallpaperActive(context: Context): Boolean {
            val info = runCatching { WallpaperManager.getInstance(context).wallpaperInfo }.getOrNull() ?: return false
            return info.packageName == FOLD_WALLPAPER.packageName &&
                (info.serviceName == FOLD_WALLPAPER.className || info.serviceName.contains("FoldInteractive"))
        }
    }
}
