package com.duoopen.fold

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign

/**
 * Turns sparse hinge readings into an angle for every frame.
 *
 * Samsung's sensor HAL logs each of its two hinge sensors only when it has
 * moved a fixed step since that sensor's last log line (folding_angle: 11°,
 * lid_angle_fusion: 10°), so readings arrive every ~5° when the two
 * interleave, and seconds apart on a slow fold. Each is stamped with when it
 * was sampled. The tracker uses three facts about that:
 *
 * - **Speed** comes from consecutive readings of the *same* sensor (a full,
 *   exact step apart), so the couple of degrees the two sensors disagree by
 *   never shows up as a speed change.
 * - **Position** is each sensor's last reading carried forward at that speed,
 *   averaged over the sensors heard during this movement, which also cancels
 *   their disagreement.
 * - **Silence is information**: while no sensor has logged again, the hinge
 *   can't yet be a full step past any of them. The estimate glides up to that
 *   limit and waits there, so a fold that stops or slows can't run away, and
 *   one that keeps going never has to stop and jump.
 *
 * Readings without step semantics ([SOURCE_OTHER]: the fold wallpaper, a
 * posture, a hall-sensor "shut") are taken as they are, nothing predicted.
 * Clock: `System.nanoTime()` / uptime nanoseconds. Pure Kotlin, no Android.
 *
 * Adapted from Duo Open for Galaxy (github.com/OrangeHubX/duo-open-for-galaxy,
 * MIT), which measured the HAL's step sizes on a Galaxy Z Fold 8 Ultra.
 */
class HingeTracker {

    private class Track(val step: Float) {
        var angle = Float.NaN
        var nanos = 0L
        /** Speed between this sensor's last two readings in the current movement; NaN if unknown. */
        var speed = Float.NaN
        /** Logged since the current movement began. */
        var live = false
    }

    private val tracks = arrayOf(Track(FOLDING_STEP), Track(LID_STEP))

    /** Latest reading of any kind, for when nothing better is known. */
    private var lastAngle = Float.NaN
    private var lastNanos = 0L
    /** +1 opening, -1 closing, 0 at rest or unknown. */
    private var direction = 0
    /** Degrees per second along [direction]'s sign; 0 when unknown. */
    private var velocity = 0f
    /** Value handed out last, so a stop holds where the frost already is. */
    private var held = Float.NaN
    /** The last [estimate] was in the full-speed part of the glide. */
    private var cruise = false

    val hasValue: Boolean get() = lastAngle.isFinite()

    /** Degrees per second of the current movement (signed), 0 when still or unknown. */
    val velocityDps: Float get() = velocity

    /** A reading from one of Samsung's hinge sensors ([SOURCE_FOLDING] / [SOURCE_LID]), or any other source. */
    fun add(angle: Float, sampleNanos: Long, source: Int) {
        val track = tracks.getOrNull(source) ?: return set(angle, sampleNanos)
        if (lastNanos != 0L && sampleNanos - lastNanos > GAP_NANOS) beginMovement()

        val previous = track.angle
        val moved = if (previous.isFinite()) angle - previous else 0f
        val turn = moved != 0f && direction != 0 && sign(moved).toInt() != direction
        if (turn) {
            // A sensor only logs after a full step, so a step against the
            // current direction is a real reversal, not noise.
            beginMovement()
        }
        if (previous.isFinite() && moved != 0f) {
            val dt = (sampleNanos - track.nanos) / 1e9f
            track.speed = if (track.live && dt in MIN_DT_S..MAX_STEP_S) moved / dt else Float.NaN
            direction = sign(moved).toInt()
        }
        track.angle = angle
        track.nanos = sampleNanos
        track.live = true

        val speeds = tracks.filter { it.live && it.speed.isFinite() }.map { it.speed }
        velocity = when {
            speeds.isNotEmpty() -> speeds.average().toFloat()
            // First readings of a movement: only the two sensors against each
            // other, which is rough but better than standing still.
            else -> crossSpeed(sampleNanos)
        }
        if (direction == 0 && velocity != 0f) direction = sign(velocity).toInt()
        if (sign(velocity).toInt() != direction) velocity = 0f
        lastAngle = angle
        lastNanos = sampleNanos
    }

    /** A reading without step semantics: taken as is, nothing predicted. */
    fun set(angle: Float, sampleNanos: Long) {
        beginMovement()
        lastAngle = angle
        lastNanos = sampleNanos
        held = angle
    }

    /** Forget the motion (the hinge jumped to a known state). */
    fun reset(angle: Float, sampleNanos: Long) = set(angle, sampleNanos)

    /** The hinge angle at [nowNanos], or NaN before any reading. */
    fun estimate(nowNanos: Long): Float {
        cruise = false
        if (!lastAngle.isFinite()) return Float.NaN
        if (velocity == 0f || direction == 0) return hold(lastAngle)
        if (nowNanos - lastNanos > MAX_COAST_NANOS) return hold(held.takeIf { it.isFinite() } ?: lastAngle)

        var sum = 0f
        var n = 0
        for (t in tracks) {
            if (!t.live) continue
            sum += t.angle + velocity * ((nowNanos - t.nanos) / 1e9f)
            n++
        }
        val glide = ((if (n > 0) sum / n else lastAngle) - lastAngle) * direction

        // No sensor has logged again, so none can be a full step past its
        // last reading yet (plus what a 25 Hz sensor and the log add).
        val slack = abs(velocity) * SLACK_S
        var room = Float.MAX_VALUE
        for (t in tracks) {
            if (t.angle.isFinite()) room = minOf(room, (t.angle - lastAngle) * direction + t.step + slack)
        }
        val x = glide.coerceAtLeast(0f)
        val limit = room.coerceAtLeast(0f)
        cruise = x <= limit * CRUISE
        var value = lastAngle + direction * softLimit(x, limit)
        // Never back past what is already on screen: a reading that lands a
        // little behind the glide slows it instead of pulling it back.
        held.takeIf { it.isFinite() }?.let { value = if (direction > 0) maxOf(value, it) else minOf(value, it) }
        return hold(value.coerceIn(0f, 180f))
    }

    /** Whether the hinge, at [nowNanos], is moving at its measured speed and well short of its next reading. */
    fun cruising(nowNanos: Long): Boolean {
        estimate(nowNanos)
        return cruise
    }

    /**
     * [x] up to [CRUISE] of [limit], then easing into the limit without ever
     * reaching it: a hand that slows down is met with a slowing frost rather
     * than one that runs to the limit and stops dead, and one that keeps up
     * its speed loses only a degree or so before its next reading lands.
     */
    private fun softLimit(x: Float, limit: Float): Float {
        val knee = limit * CRUISE
        if (x <= knee || limit <= 0f) return minOf(x, limit)
        val tail = limit - knee
        return knee + tail * (1f - exp(-(x - knee) / tail))
    }

    private fun hold(value: Float): Float {
        held = value
        return value
    }

    private fun beginMovement() {
        for (t in tracks) {
            t.live = false
            t.speed = Float.NaN
        }
        direction = 0
        velocity = 0f
        held = Float.NaN
    }

    /** Speed between the two sensors' latest readings, when both are from this movement. */
    private fun crossSpeed(nowNanos: Long): Float {
        val (a, b) = tracks
        if (!a.live || !b.live || a.nanos == b.nanos) return 0f
        val (older, newer) = if (a.nanos < b.nanos) a to b else b to a
        val dt = (newer.nanos - older.nanos) / 1e9f
        if (dt !in MIN_DT_S..MAX_STEP_S || nowNanos < newer.nanos) return 0f
        val v = (newer.angle - older.angle) / dt
        // Two sensors a degree or two apart can't vouch for a direction.
        return if (abs(newer.angle - older.angle) >= MIN_CROSS_DEG) v else 0f
    }

    companion object {
        const val SOURCE_FOLDING = 0
        const val SOURCE_LID = 1
        const val SOURCE_OTHER = 2

        /** How far each sensor moves between log lines (Galaxy Z Fold 8 Ultra). */
        const val FOLDING_STEP = 11f
        const val LID_STEP = 10f
        /** Readings further apart than this belong to separate movements. */
        private const val GAP_NANOS = 1_500_000_000L
        /** Slowest step still counted as motion (~7°/s). */
        private const val MAX_STEP_S = 1.5f
        private const val MIN_DT_S = 0.02f
        private const val MIN_CROSS_DEG = 3f
        /** Share of the way to the next possible reading covered at full speed. */
        private const val CRUISE = 0.7f
        /** One 25 Hz sample plus the log's delivery. */
        private const val SLACK_S = 0.06f
        /** Stop gliding this long after the last reading, whatever happens. */
        private const val MAX_COAST_NANOS = 1_500_000_000L
    }
}
