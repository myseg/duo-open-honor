package com.duoopen.fold

import android.view.Choreographer
import kotlin.math.abs
import kotlin.math.exp

/**
 * Eases a displayed value toward a goal, one vsync at a time, and idles once
 * it has arrived. Main thread only.
 *
 * The goal is either a fixed [target] (timed eases, the stops-only sensor) or,
 * via [follow], a function of the frame time — [HingeAngleSource.estimate],
 * which moves between hinge readings — re-read every frame so the frost moves
 * with the hand rather than stepping at each reading. Hides the sensor's 1°
 * steps (~50 Hz on the OnePlus Open) without adding noticeable lag.
 */
class TiltFollower(private val onFrame: (value: Float) -> Unit) : Choreographer.FrameCallback {

    var target = 0f
        private set
    var current = 0f
        private set

    /** Time constant of the ease; larger = slower. */
    var tauS = DEFAULT_TAU_S

    private var goalAt: ((frameNanos: Long) -> Float)? = null
    private var lastGoal = Float.NaN
    private var scheduled = false
    private var lastFrameNanos = 0L

    /** Ease to a fixed value. */
    fun setTarget(value: Float) {
        goalAt = null
        target = value
        if (current != target) schedule()
    }

    /**
     * Track [goal], evaluated at each frame's time; NaN means "hold". Call
     * again whenever new data arrives: once the goal stops changing and the
     * value has caught up, frames stop until then.
     */
    fun follow(goal: (frameNanos: Long) -> Float) {
        goalAt = goal
        lastGoal = Float.NaN
        schedule()
    }

    /** True while [follow] drives the goal. */
    val following: Boolean get() = goalAt != null

    /** Jumps straight to [value] with no animation or callback. */
    fun snap(value: Float) {
        goalAt = null
        target = value
        current = value
        cancel()
    }

    fun cancel() {
        if (!scheduled) return
        scheduled = false
        lastFrameNanos = 0L
        Choreographer.getInstance().removeFrameCallback(this)
    }

    private fun schedule() {
        if (scheduled) return
        scheduled = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        scheduled = false
        val dt = if (lastFrameNanos == 0L) 1f / 60f
        else ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastFrameNanos = frameTimeNanos

        val provider = goalAt
        val goal = provider?.invoke(frameTimeNanos)?.takeIf { it.isFinite() } ?: if (provider != null) current else target
        if (provider != null) target = goal
        current += (goal - current) * (1f - exp(-dt / tauS))
        if (abs(goal - current) < SETTLED) current = goal

        onFrame(current)
        val goalMoving = provider != null && goal != lastGoal
        lastGoal = goal
        if (current != goal || goalMoving) schedule() else lastFrameNanos = 0L
    }

    companion object {
        const val DEFAULT_TAU_S = 0.045f
        /** Following a live estimate: it is already smooth, so only round off its corrections. */
        const val FOLLOW_TAU_S = 0.03f
        private const val SETTLED = 0.02f
    }
}
