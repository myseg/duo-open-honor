package com.duoopen.fold

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * What this phone's hinge has been seen to do, learned while the effect runs
 * and kept across restarts so the second fold is already right:
 *
 * - [swapHinge]: the hinge angle at which the cover panel hands over to the
 *   inner panel on an open. The OnePlus Open swaps at ~20°, a Galaxy Z Fold
 *   at ~85°, and the Pixel Folds somewhere in between — the cover is fully
 *   frosted and the inner panel picks up fully frosted right there, so a
 *   wrong guess either clamps the cover early or starts the inner panel
 *   half-cleared.
 *
 * Values are smoothed (a swap's angle varies a little with how fast you
 * open) and clamped to what a book-style hinge can plausibly do.
 */
object HingeProfile {
    private const val PREFS = "duo_hinge"
    private const val KEY_SWAP = "swapHinge"
    private const val KEY_SWAP_SAMPLES = "swapSamples"

    private var prefs: SharedPreferences? = null

    /** Learned cover→inner swap angle, NaN until a swap has been observed. */
    @Volatile
    var swapHinge: Float = Float.NaN
        private set

    @Volatile
    private var swapSamples = 0

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        swapHinge = p.getFloat(KEY_SWAP, Float.NaN).takeIf { it in SWAP_RANGE } ?: Float.NaN
        swapSamples = p.getInt(KEY_SWAP_SAMPLES, 0)
    }

    /** The swap angle to use: learned if known, else [fallback]. */
    fun swapHingeOr(fallback: Float): Float = swapHinge.takeIf { it.isFinite() } ?: fallback

    /**
     * A cover→inner swap was just seen at [hingeDegrees] (from a continuous
     * angle source, so the value means something). The first few observations
     * are averaged, later ones nudge the estimate.
     */
    fun observeSwap(hingeDegrees: Float) {
        if (!hingeDegrees.isFinite() || hingeDegrees !in SWAP_RANGE) return
        val n = swapSamples
        val current = swapHinge
        val next = if (!current.isFinite() || n == 0) hingeDegrees
        else current + (hingeDegrees - current) * (1f / (n + 1)).coerceAtLeast(MIN_WEIGHT)
        swapHinge = next
        swapSamples = n + 1
        prefs?.edit {
            putFloat(KEY_SWAP, next)
            putInt(KEY_SWAP_SAMPLES, n + 1)
        }
    }

    /** Forget what was learned (diagnostics). */
    fun reset() {
        swapHinge = Float.NaN
        swapSamples = 0
        prefs?.edit { remove(KEY_SWAP); remove(KEY_SWAP_SAMPLES) }
    }

    fun summary(): String = if (swapHinge.isFinite()) "panels swap at ~${swapHinge.toInt()}° (learned from $swapSamples fold${if (swapSamples == 1) "" else "s"})" else "panel swap angle not learned yet"

    private val SWAP_RANGE = 5f..140f
    /** Keep adapting slowly forever: a software update can move the swap. */
    private const val MIN_WEIGHT = 0.15f
}
