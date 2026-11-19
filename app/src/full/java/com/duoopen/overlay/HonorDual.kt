package com.duoopen.overlay

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import com.duoopen.Log
import android.view.Display
import android.view.SurfaceControl
import com.duoopen.shell.ShizukuBridge
import android.view.WindowManager
import com.duoopen.fold.DuoShader
import com.duoopen.fold.HingeAngleSource
import com.duoopen.fold.TiltFollower
import com.duoopen.settings.DuoConfig
import com.duoopen.settings.DuoSettings
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

private const val HTAG = "DuoHonor"

/** Shell commands through Shizuku, strictly one after another. */
private object HonorShell {
    private val worker = Executors.newSingleThreadExecutor()

    fun ready(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    fun run(cmd: String, before: (() -> Unit)? = null) {
        worker.execute {
            before?.let { runCatching(it).onFailure { e -> Log.w(HTAG, "pre-step failed", e) } }
            try {
                val m = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java, Array<String>::class.java, String::class.java,
                )
                m.isAccessible = true
                val p = m.invoke(null, arrayOf("sh", "-c", "($cmd) 2>&1"), null, null) as Process
                val out = p.inputStream.bufferedReader().readText().trim()
                p.waitFor()
                Log.i(HTAG, "$cmd -> ${out.ifEmpty { "ok" }}")
            } catch (e: Throwable) {
                Log.w(HTAG, "shell failed: $cmd", e)
            }
        }
    }
}

/**
 * Honor Magic V3 (MagicOS) screen switching, through Shizuku:
 *  - from shut, the cover stays lit while opening (device-state override to CLOSED);
 *  - in both-screens mode the inner panel joins at [KEY_DUAL_FROM]° (COORDINATION
 *    state, the one the camera uses), and the cover goes dark at [KEY_OPEN_AT]°;
 *  - closing past [KEY_CLOSE_AT]° lights the cover again;
 *  - system animations are switched off around each switch (MagicOS's own slide
 *    otherwise shoves the new panel's content in from the left).
 *
 * Tuned with `settings put global <key> <value>` (adb or a Shizuku shell).
 */
class HonorScreens(
    private val context: Context,
    private val handler: Handler,
    private val onSwitch: () -> Unit,
    /**
     * Called on a background thread just before both panels light: grab what
     * display 0 shows now, because the cover shows nothing by itself once
     * both are lit and needs a picture to frost (live blur takes none).
     */
    private val snapshotBeforeDual: () -> Unit = {},
) : SensorEventListener {

    private enum class Target { NONE, COVER, DUAL }

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val prefs = context.getSharedPreferences("honor_screens", Context.MODE_PRIVATE)
    private var hingeSensor: Sensor? = null
    private var angle = -1f
    private var anchor = -1f
    private var dir = 0
    private var fresh = false
    private var target = Target.NONE
    private var releaseAt = 0f
    private var lastRelease = 0L
    private var dualOpening = true
    private var setupDone = false
    private var started = false
    private var warnedNoShell = false
    /** Seen shut while the screen was on: the next open plays. */
    private var shutArmed = false

    /** Both panels are lit on purpose right now (the cover gets a [RearCoverEngine]). */
    val dualActive: Boolean get() = target == Target.DUAL

    private val restoreAnim = Runnable { restoreAnimations() }
    private val dualTimeout = Runnable { if (target == Target.DUAL) go(Target.NONE, false) }

    /** The hinge can go quiet near the end of a close: stopped low means shut. */
    private val shutGuess = Runnable {
        if (target == Target.DUAL && !dualOpening && angle in 0f..35f) {
            shutArmed = true
            go(Target.NONE, true)
        }
    }

    private val binderListener = Shizuku.OnBinderReceivedListener { handler.post { setup() } }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    shutArmed = false
                    go(Target.NONE, false)
                }
                Intent.ACTION_SCREEN_ON -> listen()
                else -> evaluate()
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        restoreAnimations() // undo anything a crash left behind
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        hingeSensor = sensors?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE, true)
            ?: sensors?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
        listen()
        context.registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            Context.RECEIVER_NOT_EXPORTED, // protected system broadcasts still arrive
        )
        Log.i(HTAG, "started; mode=${mode()} hinge=${hingeSensor?.name} shizuku=${HonorShell.ready()}")
        if (!HonorShell.ready()) {
            Log.w(
                HTAG,
                "Shizuku isn't running or authorised: the cover can't be kept lit and both-screens mode is off. " +
                    "Without it MagicOS turns the cover off at the swap, so expect a short black gap there.",
            )
        }
    }

    fun stop() {
        if (!started) return
        started = false
        sensors?.unregisterListener(this)
        runCatching { context.unregisterReceiver(screenReceiver) }
        Shizuku.removeBinderReceivedListener(binderListener)
        go(Target.NONE, false)
        handler.removeCallbacks(dualTimeout)
        handler.removeCallbacks(shutGuess)
        handler.removeCallbacks(restoreAnim)
        restoreAnimations()
    }

    /**
     * Shizuku came up or was authorised: set up now and arm the cover if the
     * phone is shut, instead of waiting for the hinge to move (by then
     * MagicOS has already swapped panels, ~5° into an open).
     */
    fun onShizukuChanged() {
        handler.post {
            if (!started || !HonorShell.ready()) return@post
            if (!setupDone) setup()
            warnedNoShell = false
            listen()
            evaluate()
        }
    }

    /** Clear any override a previous run left behind, and grant what the switch and the cover need. */
    private fun setup() {
        if (!HonorShell.ready()) return
        setupDone = true
        target = Target.NONE
        val pkg = context.packageName
        HonorShell.run(
            "$RESET_ALL; pm grant $pkg android.permission.WRITE_SECURE_SETTINGS; " +
                "appops set $pkg SYSTEM_ALERT_WINDOW allow",
        )
    }

    /** (Re)register so we get a fresh angle instead of trusting an old one. */
    private fun listen() {
        val sm = sensors ?: return
        sm.unregisterListener(this)
        fresh = false
        hingeSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onSensorChanged(e: SensorEvent) {
        angle = e.values[0]
        fresh = true
        if (anchor < 0f) anchor = angle
        if (angle <= anchor - 3f) {
            dir = -1; anchor = angle
        } else if (angle >= anchor + 3f) {
            dir = 1; anchor = angle
        }
        evaluate()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun setting(key: String, def: Int) = Settings.Global.getInt(context.contentResolver, key, def)
    private fun mode() = setting(KEY_MODE, 2)
    private fun openAt() = setting(KEY_OPEN_AT, 70).toFloat()
    private fun closeAt() = setting(KEY_CLOSE_AT, 90).toFloat()
    private fun dualFrom() = setting(KEY_DUAL_FROM, 3).toFloat()

    /** Mode 2 asks for both panels as soon as the hinge leaves shut (retrying until MagicOS allows it); mode 1's override needs the panels already swapped. */
    private fun openTrigger(m: Int) = if (m == 2) dualFrom() else maxOf(dualFrom(), 10f)

    private fun evaluate() {
        val a = angle
        if (a < 0f) return
        val m = mode()
        val interactive = power?.isInteractive == true
        val locked = keyguard?.isKeyguardLocked == true
        if (m == 0 || !interactive || (locked && setting(KEY_LOCKED, 0) == 0)) {
            go(Target.NONE, false)
            return
        }
        if (!setupDone && HonorShell.ready()) setup()

        val openAt = openAt()
        val closeAt = closeAt()
        val now = SystemClock.uptimeMillis()
        when (target) {
            Target.NONE -> when {
                // Shut: nothing to send yet. MagicOS cancels a device-state
                // override the moment the phone stops being physically closed,
                // so an override set while shut never survives the open.
                a <= SHUT_DEG -> shutArmed = true
                shutArmed && a >= openTrigger(m) && a < openAt -> {
                    shutArmed = false
                    releaseAt = openAt
                    dualOpening = true
                    go(if (m == 2) Target.DUAL else Target.COVER, true)
                }
                shutArmed && a >= openAt -> shutArmed = false
                dir < 0 && a > 5f && a <= closeAt && now - lastRelease > 700 -> {
                    releaseAt = maxOf(openAt, a + 15f)
                    dualOpening = false
                    go(if (m == 2) Target.DUAL else Target.COVER, true)
                }
            }
            Target.COVER -> when {
                a >= releaseAt || a >= 165f -> go(Target.NONE, true)
                m == 2 && a >= dualFrom() && a < releaseAt - 2f -> {
                    dualOpening = true
                    go(Target.DUAL, true)
                }
            }
            Target.DUAL -> when {
                a >= releaseAt || a >= 165f -> go(Target.NONE, true)
                a <= SHUT_DEG -> {
                    shutArmed = true
                    go(Target.NONE, true)
                }
                !dualOpening -> {
                    handler.removeCallbacks(shutGuess)
                    handler.postDelayed(shutGuess, SHUT_GUESS_MS)
                }
            }
        }
    }

    private fun go(next: Target, switching: Boolean) {
        if (next == target) return
        if (next != Target.NONE && !HonorShell.ready()) {
            if (!warnedNoShell) {
                warnedNoShell = true
                Log.w(HTAG, "would switch to ${next.name.lowercase()} at ${angle.toInt()}° but Shizuku isn't ready")
            }
            return
        }
        val prev = target
        target = next
        if (next == Target.NONE) lastRelease = SystemClock.uptimeMillis()
        if (switching) animationsOff()
        handler.removeCallbacks(dualTimeout)
        handler.removeCallbacks(shutGuess)
        if (next == Target.DUAL) handler.postDelayed(dualTimeout, DUAL_MAX_MS)
        // The cover picture is grabbed alongside, never ahead of, the switch:
        // a capture taken as the panels swap can take ~0.5 s, and every ms
        // before the dual request reaches MagicOS is black on the cover.
        if (next == Target.DUAL) {
            snapshotWorker.execute { runCatching(snapshotBeforeDual).onFailure { Log.w(HTAG, "cover snapshot failed", it) } }
        }
        HonorShell.run(
            cmd = when (next) {
                Target.COVER -> "cmd device_state base-state $CLOSED_STATE" +
                    (if (prev == Target.DUAL) "; cmd device_state state reset" else "")
                // MagicOS refuses the dual state ("Please use it in expanded
                // state") while the base state is overridden to CLOSED.
                // Opening: MagicOS allows it only once the phone is physically
                // half open (the same moment it swaps panels), so retry until
                // it's accepted, ~40 ms apart, for up to ~1.5 s.
                Target.DUAL -> (if (prev == Target.COVER) "cmd device_state base-state reset; " else "") +
                    "i=0; while [ \$i -lt $DUAL_RETRIES ]; do i=\$((i+1)); " +
                    "if cmd device_state state $DUAL_STATE 2>&1 | grep -q Exception; then sleep 0.04; else echo accepted after \$i; break; fi; done"
                Target.NONE -> RESET_ALL
            },
        )
        Log.i(HTAG, "${prev.name.lowercase()} -> ${next.name.lowercase()} at ${angle.toInt()}°")
        onSwitch()
    }

    private fun canWriteSettings() =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private fun animKeys(): List<String> = when (setting(KEY_ANIM, 1)) {
        0 -> emptyList()
        2 -> ALL_ANIM_KEYS
        else -> listOf(Settings.Global.TRANSITION_ANIMATION_SCALE, Settings.Global.WINDOW_ANIMATION_SCALE)
    }

    private fun animationsOff() {
        val keys = animKeys()
        if (keys.isEmpty() || !canWriteSettings()) return
        val cr = context.contentResolver
        if (!prefs.getBoolean("held", false)) {
            val e = prefs.edit()
            for (k in ALL_ANIM_KEYS) e.putFloat(k, Settings.Global.getFloat(cr, k, 1f))
            e.putBoolean("held", true).commit()
        }
        for (k in keys) Settings.Global.putFloat(cr, k, 0f)
        handler.removeCallbacks(restoreAnim)
        handler.postDelayed(restoreAnim, ANIM_RESTORE_MS)
    }

    private fun restoreAnimations() {
        if (!prefs.getBoolean("held", false) || !canWriteSettings()) return
        val cr = context.contentResolver
        for (k in ALL_ANIM_KEYS) Settings.Global.putFloat(cr, k, prefs.getFloat(k, 1f))
        prefs.edit().putBoolean("held", false).apply()
    }

    companion object {
        /** MagicOS phones (Honor Magic V2 / V3 / V5 …). */
        fun isHonor(): Boolean =
            Build.MANUFACTURER.equals("HONOR", ignoreCase = true) || Build.BRAND.equals("HONOR", ignoreCase = true)

        /** 0 off, 1 keep the cover on (one screen at a time), 2 both screens (default). */
        const val KEY_MODE = "duoopen_honor_mode"
        const val KEY_OPEN_AT = "duoopen_honor_open_at"
        const val KEY_CLOSE_AT = "duoopen_honor_close_at"
        const val KEY_DUAL_FROM = "duoopen_honor_dual_from"

        /** 0 leave animations alone, 1 transition + window scales (default), 2 also the animator scale. */
        const val KEY_ANIM = "duoopen_honor_anim"

        /** 1 = also on the lock screen. */
        const val KEY_LOCKED = "duoopen_honor_locked"

        private const val CLOSED_STATE = 4   // STATE_CLOSED (cmd device_state print-states)
        private const val DUAL_STATE = 14    // COORDINATION_DISPLAY_MODE
        private const val RESET_ALL = "cmd device_state state reset; cmd device_state base-state reset"
        private const val ANIM_RESTORE_MS = 1_500L
        private const val DUAL_MAX_MS = 15_000L
        private const val SHUT_GUESS_MS = 500L
        private const val SHUT_DEG = 2f
        private val snapshotWorker = Executors.newSingleThreadExecutor()
        private const val DUAL_RETRIES = 30
        private val ALL_ANIM_KEYS = listOf(
            Settings.Global.TRANSITION_ANIMATION_SCALE,
            Settings.Global.WINDOW_ANIMATION_SCALE,
            Settings.Global.ANIMATOR_DURATION_SCALE,
        )
    }
}

/**
 * The cover panel while both panels are lit. The system draws nothing on it,
 * so it gets the picture the cover showed when the fold began (taken by the
 * cover's own [PanelEngine]), through the same shader, following the hinge on
 * the curve upstream uses for a cover that stays lit next to the inner panel.
 * The display is hidden from apps' normal list and has no accessibility window
 * token, so this uses an app overlay window.
 */
class RearCoverEngine(
    private val service: AccessibilityService,
    val display: Display,
    private val hinge: HingeAngleSource,
    private val handler: Handler,
    private val cache: SnapshotCache,
    /** Our own overlay layers on display 0, left out of the live mirror (main thread). */
    private val excludedOnDefault: () -> List<SurfaceControl> = { emptyList() },
) {
    private val windowManager: WindowManager? = runCatching {
        service.createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            .getSystemService(WindowManager::class.java)
    }.getOrNull()
    private var surface: SnapshotSurface? = null
    private var follower: TiltFollower? = null
    private var tries = 0
    private var destroyed = false
    private val showRunnable = Runnable { tryShow() }
    private var liveBusy = false
    private var liveFrames = 0
    private val liveTick = Runnable { liveFrame() }

    init {
        handler.post(showRunnable)
    }

    private fun tiltFor(angle: Float): Float =
        if (angle.isNaN()) 0f
        else DuoShader.coverTiltForHinge(angle, DuoSettings.config.value, hinge.panelSwapHinge, staysLit = true)

    private fun tryShow() {
        if (destroyed || surface != null) return
        val wm = windowManager ?: return
        if (!Settings.canDrawOverlays(service)) {
            Log.w(HTAG, "rear cover ${display.displayId}: no overlay permission yet (authorise Shizuku in Tune)")
            return
        }
        // Opening: display 0 was the cover until a moment ago, so the cover
        // picture is fresh. Closing from open: the cover has nothing recent,
        // but the inner screen was captured as both panels lit, and that's the
        // app that's about to land on the cover.
        val coverAge = cache.ageMs(false) ?: Long.MAX_VALUE
        val innerAge = cache.ageMs(true) ?: Long.MAX_VALUE
        val cover = cache.latest(innerPanel = false, anyAge = true)
        val inner = cache.latest(innerPanel = true, anyAge = true)
        val waiting = tries < WAIT_TRIES
        val fresh = when {
            cover != null && coverAge <= FRESH_MS -> cover
            inner != null && innerAge <= FRESH_MS -> inner
            else -> null
        }
        if (fresh == null && waiting) {
            // The capture taken as both panels lit may still be landing.
            tries++
            handler.postDelayed(showRunnable, WAIT_STEP_MS)
            return
        }
        val pic = fresh ?: cover ?: inner
        if (pic == null) {
            Log.w(HTAG, "rear cover ${display.displayId}: no picture to show")
            return
        }
        val age = if (pic === cover) coverAge else innerAge
        // Own copy, cropped to the cover's shape (an inner picture is near-square).
        val copy = fitToDisplay(pic) ?: return
        val config = DuoSettings.config.value
        val foldLine = { w: Float, h: Float, c: DuoConfig -> DuoShader.foldFor(false, w, h, c) }
        val s = SnapshotSurface(service, wm, copy, config, foldLine, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        if (!s.attached) {
            Log.e(HTAG, "rear cover ${display.displayId}: could not attach overlay")
            return
        }
        val start = tiltFor(hinge.lastAngle)
        s.tilt = start
        s.fadeIn(FADE_IN_MS)
        surface = s
        val f = TiltFollower { t -> s.tilt = t }.also { it.snap(start) }
        f.tauS = if (hinge.externalActive) TiltFollower.FOLLOW_TAU_S else TiltFollower.DEFAULT_TAU_S
        f.follow { now -> tiltFor(hinge.estimate(now)) }
        follower = f
        handler.postDelayed(liveTick, LIVE_INTERVAL_MS)
        Log.i(HTAG, "rear cover ${display.displayId}: showing ${if (pic === cover) "cover" else "inner"} picture ${copy.width}x${copy.height} (${age} ms old) at tilt=$start")
    }

    /**
     * The cover shows nothing by itself while both panels are lit, so keep
     * mirroring display 0 (the inner panel by then: the same app that is on
     * its way to or from the cover) through Shizuku, cropped to the cover's
     * shape, under the frost. Skips black frames of a panel still waking.
     */
    private fun liveFrame() {
        if (destroyed || surface == null) return
        if (liveBusy || !ShizukuBridge.ready) {
            handler.postDelayed(liveTick, LIVE_INTERVAL_MS)
            return
        }
        liveBusy = true
        val excluded = runCatching { excludedOnDefault() }.getOrDefault(emptyList())
        liveWorker.execute {
            val frame = runCatching {
                ShizukuBridge.capture(Display.DEFAULT_DISPLAY, excluded, LIVE_SCALE)?.let { fitToDisplay(it, skipBlack = true) }
            }.getOrNull()
            handler.post {
                liveBusy = false
                val s = surface
                if (destroyed || s == null) return@post
                if (frame != null) {
                    s.replaceSnapshot(frame)
                    liveFrames++
                    if (liveFrames == 1 || liveFrames % 25 == 0) {
                        Log.i(HTAG, "rear cover ${display.displayId}: live frame $liveFrames (${frame.width}x${frame.height})")
                    }
                }
                handler.postDelayed(liveTick, LIVE_INTERVAL_MS)
            }
        }
    }

    /** Software copy center-cropped to this display's aspect; null if unusable (or black, with [skipBlack]). */
    private fun fitToDisplay(src: Bitmap, skipBlack: Boolean = false): Bitmap? {
        val sw = runCatching { src.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return null
        if (skipBlack && isMostlyBlack(sw)) {
            sw.recycle()
            return null
        }
        val mode = runCatching { display.mode }.getOrNull() ?: return sw
        val target = mode.physicalWidth.toFloat() / mode.physicalHeight.toFloat()
        val have = sw.width.toFloat() / sw.height.toFloat()
        if (kotlin.math.abs(have - target) < 0.02f) return sw
        val (cw, ch) = if (have > target) {
            (sw.height * target).toInt().coerceAtLeast(1) to sw.height
        } else {
            sw.width to (sw.width / target).toInt().coerceAtLeast(1)
        }
        val cropped = Bitmap.createBitmap(sw, (sw.width - cw) / 2, (sw.height - ch) / 2, cw, ch)
        if (cropped !== sw) sw.recycle()
        return cropped
    }

    private fun isMostlyBlack(b: Bitmap): Boolean {
        val n = 16
        var max = 0
        for (iy in 0 until n) for (ix in 0 until n) {
            val c = b.getPixel(((ix + 0.5f) * b.width / n).toInt(), ((iy + 0.5f) * b.height / n).toInt())
            max = maxOf(max, ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF))
        }
        return max < 30
    }

    fun destroy() {
        destroyed = true
        handler.removeCallbacks(liveTick)
        handler.removeCallbacks(showRunnable)
        follower?.cancel()
        follower = null
        surface?.detach()
        surface = null
    }

    private companion object {
        const val FADE_IN_MS = 120L
        const val FRESH_MS = 4_000L
        const val WAIT_TRIES = 15
        const val WAIT_STEP_MS = 40L
        const val LIVE_INTERVAL_MS = 80L
        const val LIVE_SCALE = 0.4f
        val liveWorker = Executors.newSingleThreadExecutor()
    }
}