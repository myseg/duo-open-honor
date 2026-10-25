package com.duoopen.fold

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Reports the foldable's hinge angle in degrees (0 = closed, 180 = flat).
 *
 * Every sensor that looks like a real hinge *angle* is registered — the
 * platform `TYPE_HINGE_ANGLE` (non-wake-up first) plus vendor sensors that
 * mention the hinge/fold and report a ~180° range — and the finest one that
 * actually delivers events drives [onAngle]. Posture flags (0/1 range) are
 * skipped, and a sensor the OEM has permission-gated (Samsung's
 * `folding_angle` needs `com.samsung.permission.SSENSOR`) is logged and
 * ignored instead of crashing the registration.
 *
 * Some devices only expose three stops — the Galaxy Z Folds report 0 / 90 /
 * 180 with `resolution 90` — which is [isCoarse]. Two things fill that in:
 *
 * - an **external continuous source** ([feedExternal]): Shizuku mode reads
 *   Samsung's own hinge sensors from the sensor HAL's log (a reading every
 *   10–11° per sensor) or its fold wallpaper (1°, 30 Hz); a [HingeTracker]
 *   carries the hinge between the sparse readings;
 * - the system's **fold posture** ([feedPosture]) from the hidden device
 *   state, which changes earlier than the stops sensor and is the only
 *   reliable "shut" on a hinge sensor that goes quiet on the way down.
 *
 * [estimate] gives the angle for *now* — the tracker's glide between
 * readings, or on a fine sensor a few milliseconds of lead to take the
 * sensor pipeline's latency out of the ease.
 *
 * The sensors are on-change, so nothing arrives while the hinge is still
 * (and on the OnePlus Open nothing arrives on registration either).
 */
class HingeAngleSource(
    context: Context,
    private val onAngle: (Float) -> Unit,
) : SensorEventListener {

    private class Stats(val sensor: Sensor) {
        var events = 0
        var last = Float.NaN
        var registered = false
        /** Distinct readings seen (rounded), capped: three or fewer over many events means stops only. */
        val distinct = LinkedHashSet<Int>()
        val resolution: Float
            get() = sensor.resolution.takeIf { it.isFinite() && it > 0f } ?: 1f
        val isStandard: Boolean get() = sensor.type == Sensor.TYPE_HINGE_ANGLE

        fun observe(value: Float) {
            events++
            last = value
            if (distinct.size < MAX_DISTINCT) distinct += value.roundToInt()
        }

        /** Only ever 0/90/180 (±2°) after enough events, or declared so. */
        val looksCoarse: Boolean
            get() = resolution >= COARSE_RESOLUTION ||
                (events >= COARSE_MIN_EVENTS && distinct.size <= 3 &&
                    distinct.all { v -> STOPS.any { abs(v - it) <= 2 } })
    }

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val candidates: List<Stats> = discover().map(::Stats)

    /** Candidate sensors, best first. */
    val sensors: List<Sensor> get() = candidates.map { it.sensor }

    /** The sensor currently driving the effect, else the best guess before any reports. */
    val sensor: Sensor? get() = activeSensor ?: candidates.firstOrNull()?.sensor

    /** Null until a candidate has reported. */
    var activeSensor: Sensor? = null
        private set

    /** Latest angle from whichever source spoke last. NaN until the first reading. */
    var lastAngle: Float = Float.NaN
        private set

    /** Smoothed event rate over the last ~half second; 0 at rest. */
    var rateHz: Float = 0f
        private set

    /** True when the best sensor we can get only reports the 0/90/180 stops. */
    val isCoarse: Boolean
        get() = !externalActive && (active ?: candidates.firstOrNull())?.looksCoarse == true

    /** Stops only, and no continuous source standing by either: the fold can only be played, not followed. */
    val stopsOnly: Boolean get() = isCoarse && !externalFeedRunning

    /**
     * Whether [estimate] tracks the hand: a continuous source is live, or the
     * platform sensor is fine-grained and has reported.
     */
    val canFollow: Boolean get() = externalActive || (active != null && !isCoarse)

    private var active: Stats? = null
    private var started = false

    // ---- external continuous source ---------------------------------------

    /**
     * A continuous angle fed from outside the sensor framework (Shizuku mode).
     * While fresh it overrides the sensors, which on a Galaxy Z Fold only give
     * 0/90/180.
     */
    var externalActive = false
        private set

    /** A continuous source is running, even if the hinge is still and it has nothing to say. */
    var externalFeedRunning = false

    private var externalLastUptime = 0L

    /** Fills in the hinge between the external source's sparse readings. */
    private val tracker = HingeTracker()

    /**
     * @param sampleUptimeMs when the reading was taken (uptime clock), so a
     *   log line that arrives late is placed correctly in time.
     * @param source which of Samsung's hinge sensors logged it
     *   ([HingeTracker.SOURCE_FOLDING], [HingeTracker.SOURCE_LID]) or
     *   [HingeTracker.SOURCE_OTHER] for a reading taken at face value.
     */
    fun feedExternal(
        angle: Float,
        sampleUptimeMs: Long = SystemClock.uptimeMillis(),
        source: Int = HingeTracker.SOURCE_OTHER,
    ) {
        if (!angle.isFinite()) return
        // Shut: the sensors' last words are late log lines from the way down.
        if (posture == HingePosture.CLOSED && angle > CLOSED_SLACK) return
        val now = SystemClock.uptimeMillis()
        val value = angle.coerceIn(0f, 180f)
        if (!externalActive) tracker.reset(lastAngle.takeIf { it.isFinite() } ?: value, now * 1_000_000L)
        externalActive = true
        externalLastUptime = now
        lastEventUptime = now
        tickRate(now)
        tracker.add(value, sampleUptimeMs.coerceIn(now - MAX_SAMPLE_AGE_MS, now) * 1_000_000L, source)
        lastAngle = value
        onAngle(lastAngle)
    }

    fun clearExternal() {
        externalActive = false
    }

    // ---- posture ------------------------------------------------------------

    /** Latest fold posture from the system's device state, or null if unavailable. */
    var posture: HingePosture? = null
        private set

    /** Names of the device states the system reported, for the report. */
    var deviceStates: List<String> = emptyList()

    /**
     * The device state changes well before a stops-only sensor does (on a Z
     * Fold 8 Ultra TENT arrives ~0.4 s before the first 90° report, and a slow
     * open may never report 90° before the panels swap). CLOSED is
     * authoritative and ends any external feed, whose last reading would
     * otherwise linger; the open postures stand in for the angle only while
     * nothing finer is speaking.
     */
    fun feedPosture(p: HingePosture) {
        if (p == posture) return
        val previous = posture
        val at = estimate()
        posture = p
        if (p == HingePosture.HALF_OPENED && previous == HingePosture.TENT && externalActive && at in SWAP_LEARN_RANGE) {
            // Opening: this is where a Z Fold swaps panels. Learn it so the
            // cover is fully frosted right there rather than at a guess.
            learnSwap(at)
        }
        val now = SystemClock.uptimeMillis()
        when {
            p == HingePosture.CLOSED -> {
                externalActive = false
                // A fine sensor still reporting is on its way to 0° itself (the
                // emulator's CLOSED begins at 30°); only a sensor that has gone
                // quiet on the way down (OnePlus Open, ~30°) needs the posture
                // to say "shut".
                if (!isCoarse && active != null && now - lastEventUptime < CLOSED_FINE_FRESH_MS) return
            }
            // Real angles are on their way (Samsung's sensor log covers every
            // posture); a stand-in would only make the frost jump.
            externalFeedRunning -> return
            // A fine sensor that is talking knows better than a posture.
            !isCoarse && active != null && now - lastEventUptime < FINE_FRESH_MS -> return
            p == HingePosture.TENT -> externalActive = false
            externalActive && now - externalLastUptime < EXTERNAL_STALE_MS -> return
        }
        lastEventUptime = now
        lastAngle = p.angle
        fineVelocity = 0f
        tracker.reset(lastAngle, now * 1_000_000L)
        onAngle(lastAngle)
    }

    // ---- panel swap angle -----------------------------------------------------

    /**
     * Hinge angle at which this phone hands over from the cover to the inner
     * panel: learned ([HingeProfile]) when a swap has been seen with a
     * continuous angle; else ~85° where the platform exposes a device state
     * (Galaxy Z Fold), else the OnePlus Open's ~20°.
     */
    val panelSwapHinge: Float
        get() = HingeProfile.swapHingeOr(if (posture != null) POSTURE_SWAP_HINGE else DuoShader.PANEL_ON_HINGE)

    /** The panels were just seen to swap at [hingeDegrees] (only meaningful when [canFollow]). */
    fun learnSwap(hingeDegrees: Float) {
        if (!canFollow || !hingeDegrees.isFinite()) return
        HingeProfile.observeSwap(hingeDegrees)
        Log.i(TAG, "panel swap seen at ${"%.0f".format(hingeDegrees)}°; ${HingeProfile.summary()}")
    }

    // ---- estimate -------------------------------------------------------------

    /** Signed °/s from the fine sensor's recent events; 0 when still. */
    private var fineVelocity = 0f
    private var lastEventUptime = 0L
    private var rateWindowStart = 0L
    private var rateWindowCount = 0

    /**
     * The hinge angle at [nowNanos] (`System.nanoTime()` clock, the same as
     * Choreographer frame times): between an external source's readings,
     * where the hinge has got to since; on a fine sensor, the last reading
     * plus a little lead to cancel the pipeline's latency; otherwise just the
     * latest reading.
     */
    fun estimate(nowNanos: Long = System.nanoTime()): Float {
        if (externalActive) return tracker.estimate(nowNanos).takeIf { it.isFinite() } ?: lastAngle
        if (lastAngle.isNaN() || isCoarse || fineVelocity == 0f) return lastAngle
        val ageS = ((nowNanos / 1_000_000L - lastEventUptime) / 1000f).coerceAtLeast(0f)
        if (ageS >= LEAD_FADE_END_S) return lastAngle
        val fade = 1f - ((ageS - LEAD_FADE_START_S) / (LEAD_FADE_END_S - LEAD_FADE_START_S)).coerceIn(0f, 1f)
        return (lastAngle + fineVelocity * minOf(ageS, LEAD_S) * fade).coerceIn(0f, 180f)
    }

    /**
     * Whether the hinge has surely reached [estimate] at [nowNanos]: moving at
     * its measured speed and well short of where the next reading is due, so
     * not just gliding on in the hope that it kept going.
     */
    fun estimateIsSure(nowNanos: Long = System.nanoTime()): Boolean = externalActive && tracker.cruising(nowNanos)

    /** Milliseconds since the last reading, or [Long.MAX_VALUE] if none yet. */
    fun lastEventAgeMs(): Long =
        if (lastEventUptime == 0L) Long.MAX_VALUE else SystemClock.uptimeMillis() - lastEventUptime

    /** One line for the UI: which source, how fine, how fast, what it says. */
    fun statusText(): String {
        val raw = if (lastAngle.isNaN()) "—" else "%.0f°".format(lastAngle)
        val age = lastEventAgeMs()
        val ageText = if (age == Long.MAX_VALUE) "no events yet" else if (age < 10_000) "$age ms ago" else "${age / 1000} s ago"
        if (externalActive || externalFeedRunning) {
            return "Samsung hinge sensors via Shizuku · continuous, tracked between readings · last $raw, $ageText" +
                (posture?.let { " · posture $it" } ?: "")
        }
        val s = active ?: candidates.firstOrNull()
            ?: return if (posture != null) "No hinge angle sensor; fold posture only ($posture)" else "No hinge sensor found on this device"
        val res = if (s.resolution >= 1f) "${s.resolution.roundToInt()}°" else "%.2f°".format(s.resolution)
        val rate = if (rateHz > 0f) "%.0f Hz".format(rateHz) else "idle"
        return "${s.sensor.name.trim()} · res $res · $rate · raw $raw · $ageText" +
            (posture?.let { " · posture $it" } ?: "")
    }

    /** Full dump for a bug report: every fold-related sensor and what it did. */
    fun report(): String = buildString {
        appendLine("Duo Open hinge sensor report")
        appendLine("device=${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) android=${Build.VERSION.RELEASE}")
        appendLine("active=${activeSensor?.name ?: "none"} coarse=$isCoarse stopsOnly=$stopsOnly canFollow=$canFollow rate=${"%.1f".format(rateHz)}Hz")
        appendLine("external: active=$externalActive running=$externalFeedRunning")
        appendLine("posture=${posture ?: "unavailable"} deviceStates=$deviceStates")
        appendLine("swap: ${HingeProfile.summary()}; using ${"%.0f".format(panelSwapHinge)}°")
        appendLine("candidates:")
        if (candidates.isEmpty()) appendLine("- (none)")
        for (c in candidates) {
            val s = c.sensor
            appendLine(
                "- ${s.name} type=${s.type} (${s.stringType}) vendor=${s.vendor} wakeUp=${s.isWakeUpSensor} " +
                    "res=${s.resolution} range=${s.maximumRange} mode=${s.reportingMode} minDelay=${s.minDelay}us " +
                    "registered=${c.registered} events=${c.events} distinct=${c.distinct} last=${c.last}",
            )
        }
        val others = allSensors().filter { s -> isFoldRelated(s) && candidates.none { it.sensor == s } }
        if (others.isNotEmpty()) {
            appendLine("other fold-related sensors (not angle candidates):")
            for (s in others) {
                appendLine("- ${s.name} type=${s.type} (${s.stringType}) range=${s.maximumRange} wakeUp=${s.isWakeUpSensor}")
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        val sm = sensorManager ?: return
        if (candidates.isEmpty()) {
            Log.w(TAG, "no hinge angle sensor found")
            return
        }
        for (c in candidates) {
            // Vendor sensors can be permission-gated (Samsung's folding_angle
            // needs com.samsung.permission.SSENSOR); that throws instead of
            // returning false.
            c.registered = try {
                sm.registerListener(this, c.sensor, SAMPLING_PERIOD_US)
            } catch (e: SecurityException) {
                Log.w(TAG, "register denied for ${c.sensor.name}: ${e.message}")
                false
            }
            Log.i(TAG, "candidate ${c.sensor.name} type=${c.sensor.stringType} res=${c.sensor.resolution} wakeUp=${c.sensor.isWakeUpSensor} registered=${c.registered}")
        }
    }

    fun stop() {
        if (!started) return
        started = false
        sensorManager?.unregisterListener(this)
        for (c in candidates) c.registered = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        val value = event.values.firstOrNull() ?: return
        val stats = candidates.firstOrNull { it.sensor == event.sensor } ?: return
        // An external continuous source wins while it's alive; if it goes
        // quiet the sensors take over again.
        if (externalActive) {
            if (SystemClock.uptimeMillis() - externalLastUptime < EXTERNAL_STALE_MS) return
            externalActive = false
        }
        // Not an angle in degrees (state code, radians, normalized): ignore.
        if (!value.isFinite() || value < -PLAUSIBLE_SLACK || value > 180f + PLAUSIBLE_SLACK) return
        stats.observe(value)
        if (choose() !== stats) return
        // A stops-only sensor on a Z Fold 8 Ultra sends a stale 0° while the
        // device state already says the phone is opening; the posture knows.
        if (stats.looksCoarse && posture != null && posture != HingePosture.CLOSED && value <= CLOSED_SLACK) return

        val now = SystemClock.uptimeMillis()
        val angle = value.coerceIn(0f, 180f)
        if (!stats.looksCoarse && lastEventUptime != 0L && !lastAngle.isNaN()) {
            val dtS = (now - lastEventUptime) / 1000f
            fineVelocity = if (dtS in MIN_VELOCITY_DT_S..MAX_VELOCITY_DT_S) {
                val v = (angle - lastAngle) / dtS
                if (fineVelocity == 0f) v else fineVelocity + (v - fineVelocity) * VELOCITY_SMOOTHING
            } else 0f
        } else {
            fineVelocity = 0f
        }
        lastEventUptime = now
        tickRate(now)
        lastAngle = angle
        tracker.reset(lastAngle, now * 1_000_000L)
        onAngle(lastAngle)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * Finest reporting sensor wins, the standard type on ties; sticky once
     * chosen unless a strictly finer one starts reporting, so a slow vendor
     * sensor can't flip-flop with the platform one mid-fold.
     */
    private fun choose(): Stats? {
        val reporting = candidates.filter { it.events > 0 }
        if (reporting.isEmpty()) return null
        val best = reporting.minWithOrNull(compareBy<Stats> { it.resolution }.thenBy { !it.isStandard })!!
        val current = active
        if (current == null || best.resolution < current.resolution) {
            if (current !== best) Log.i(TAG, "hinge angle source: ${best.sensor.name} (res ${best.resolution})")
            active = best
            activeSensor = best.sensor
            return best
        }
        return current
    }

    private fun tickRate(now: Long) {
        if (rateWindowStart == 0L || now - lastEventUptime > 1_000L) {
            rateWindowStart = now
            rateWindowCount = 0
        }
        rateWindowCount++
        val elapsed = now - rateWindowStart
        if (elapsed >= 500L) {
            rateHz = rateWindowCount * 1000f / elapsed
            rateWindowStart = now
            rateWindowCount = 0
        }
    }

    private fun allSensors(): List<Sensor> =
        runCatching { sensorManager?.getSensorList(Sensor.TYPE_ALL) }.getOrNull().orEmpty()

    private fun discover(): List<Sensor> {
        val all = allSensors()
        val standard = all.filter { it.type == Sensor.TYPE_HINGE_ANGLE }
            .ifEmpty { listOfNotNull(sensorManager?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)) }
            .sortedBy { it.isWakeUpSensor }
        val vendor = all.filter { it.type >= Sensor.TYPE_DEVICE_PRIVATE_BASE && isAngleCandidate(it) }
        return (standard + vendor).distinctBy { "${it.type}|${it.name}|${it.isWakeUpSensor}" }
    }

    /** A vendor sensor that reports a hinge *angle*: named for it, with a ~180° range. */
    private fun isAngleCandidate(s: Sensor): Boolean {
        if (!isFoldRelated(s)) return false
        val range = s.maximumRange
        if (!range.isFinite() || range !in 150f..360f) return false
        return s.reportingMode == Sensor.REPORTING_MODE_CONTINUOUS ||
            s.reportingMode == Sensor.REPORTING_MODE_ON_CHANGE
    }

    private fun isFoldRelated(s: Sensor): Boolean {
        val text = "${s.name} ${s.stringType}".lowercase()
        return text.contains("hinge") || text.contains("fold")
    }

    private companion object {
        const val TAG = "DuoHinge"
        /** ~125 Hz ceiling; the sensors only report on change anyway. */
        const val SAMPLING_PERIOD_US = 8_000
        const val PLAUSIBLE_SLACK = 5f
        const val COARSE_RESOLUTION = 45f
        const val COARSE_MIN_EVENTS = 6
        const val MAX_DISTINCT = 8
        const val EXTERNAL_STALE_MS = 3_000L
        /** A reading claiming to be older than this is mistimed; clamp it. */
        const val MAX_SAMPLE_AGE_MS = 300L
        /** A fine sensor that reported this recently outranks a posture stand-in. */
        const val FINE_FRESH_MS = 1_500L
        /** …and one that reported this recently is still tracking the close itself; CLOSED can wait. */
        const val CLOSED_FINE_FRESH_MS = 250L
        /** Readings at or below this count as "shut". */
        const val CLOSED_SLACK = 3f
        /** Where a device-state phone swaps panels before it has been seen to (Z Fold 8 Ultra: HALF_OPENED at ~83–86°). */
        const val POSTURE_SWAP_HINGE = 85f
        val SWAP_LEARN_RANGE = 30f..140f
        /** Fine-sensor lead: cancels ~one sensor interval plus HAL latency, fading out once events stop. */
        const val LEAD_S = 0.025f
        const val LEAD_FADE_START_S = 0.05f
        const val LEAD_FADE_END_S = 0.15f
        const val MIN_VELOCITY_DT_S = 0.004f
        const val MAX_VELOCITY_DT_S = 0.3f
        const val VELOCITY_SMOOTHING = 0.5f
        val STOPS = intArrayOf(0, 90, 180)
    }
}
