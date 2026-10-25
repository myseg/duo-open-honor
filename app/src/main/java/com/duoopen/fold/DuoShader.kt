package com.duoopen.fold

import android.content.Context
import android.graphics.RuntimeShader
import android.util.Log
import com.duoopen.R
import com.duoopen.settings.DuoConfig

/** Where the hinge sits in a given surface, and how the eye looks at it. */
data class FoldLine(
    /** True when the hinge is a vertical line (splits the x axis). */
    val splitsX: Boolean,
    /** Hinge line position in px along the split axis. */
    val position: Float,
    /** Eye position in px along the split axis. */
    val eyePos: Float = position,
    /** Which side moves (-1 / +1 / 0 = both); null = [DuoConfig.movingSide]. */
    val movingSide: Int? = null,
)

/** Shared glue for res/raw/duo_unfold.agsl — used by the app, wallpaper and overlay. */
object DuoShader {
    /** Pane tilt cap; beyond this the kernel is mostly black anyway. */
    const val MAX_TILT = 45f

    /** Pane tilts below this draw the plain image (effect visually off). */
    const val FLAT_EPSILON = 0.05f

    /**
     * Hinge angle treated as fully flat. Real hinges rest short of 180° (the
     * OnePlus Open reads 176–179° when open), which would otherwise leave a
     * faint permanent frost at the edges.
     */
    const val FLAT_HINGE = 172f

    /**
     * Default hinge angle at which the panels swap (OnePlus Open: ~20°). A
     * Galaxy Z Fold swaps at ~85°; the real value is learned per phone
     * ([HingeProfile]) and passed in by the callers that know it.
     */
    const val PANEL_ON_HINGE = 20f

    /** A closed OnePlus Open idles anywhere from 0° to ~5°; below this the cover counts as at rest. */
    const val CLOSED_HINGE = 6f

    private const val TAG = "DuoShader"
    private const val REFERENCE_PX_PER_MM = 6f

    @Volatile
    private var source: String? = null

    fun create(context: Context): RuntimeShader? {
        val src = source ?: context.resources.openRawResource(R.raw.duo_unfold)
            .bufferedReader().use { it.readText() }
            .also { source = it }
        return try {
            RuntimeShader(src)
        } catch (e: Exception) {
            Log.e(TAG, "AGSL compile failed: ${e.message}", e)
            null
        }
    }

    /**
     * Moving-pane tilt on the inner panel. The physical swing far exceeds what
     * the shader can show (it saturates at [MAX_TILT]), so instead of clamping
     * — which freezes the picture for most of an unfold — the visible range
     * [PANEL_ON_HINGE]..[FLAT_HINGE] is mapped linearly onto 0..[MAX_TILT],
     * so the frost keeps resolving the whole way open. Intensity scales it.
     */
    fun tiltForHinge(hingeDegrees: Float, config: DuoConfig, swapHinge: Float = HingeProfile.swapHingeOr(PANEL_ON_HINGE)): Float {
        val from = swapHinge.coerceIn(CLOSED_HINGE + 1f, FLAT_HINGE - 10f)
        val progress = ((FLAT_HINGE - hingeDegrees) / (FLAT_HINGE - from)).coerceIn(0f, 1f)
        val full = if (config.movingSide == 0) MAX_TILT * 0.6f else MAX_TILT
        return (progress * full * config.intensity).coerceIn(0f, MAX_TILT)
    }

    /**
     * Tilt on the cover panel: flat when closed, fully frosted at the swap
     * angle, where the inner panel takes over. On a phone that lights one
     * panel at a time the cover is off beyond that point, so this is all of
     * it. On a phone that keeps the cover lit alongside the inner panel
     * ([staysLit]) there is no swap to hand over to, so past the peak the
     * cover clears again on its own, over the same range the inner panel
     * clears over, reaching flat with it. One continuous curve either way —
     * the mapping must not change when the inner panel lights or dies, or the
     * frost would jump.
     */
    fun coverTiltForHinge(
        hingeDegrees: Float,
        config: DuoConfig,
        swapHinge: Float = HingeProfile.swapHingeOr(PANEL_ON_HINGE),
        staysLit: Boolean = false,
    ): Float {
        val peakAt = swapHinge.coerceIn(CLOSED_HINGE + 1f, FLAT_HINGE - 10f)
        val shape = if (hingeDegrees <= peakAt) {
            ((hingeDegrees - CLOSED_HINGE) / (peakAt - CLOSED_HINGE)).coerceIn(0f, 1f)
        } else if (!staysLit) {
            1f
        } else {
            1f - ((hingeDegrees - peakAt) / (FLAT_HINGE - peakAt)).coerceIn(0f, 1f)
        }
        // Ease the peak so the turn from frosting to clearing has no corner.
        val eased = if (staysLit) shape * shape * (3f - 2f * shape) else shape
        return (eased * MAX_TILT * config.intensity).coerceIn(0f, MAX_TILT)
    }

    fun tiltFor(hingeDegrees: Float, config: DuoConfig, innerPanel: Boolean, swapHinge: Float = HingeProfile.swapHingeOr(PANEL_ON_HINGE)): Float =
        if (hingeDegrees.isNaN()) 0f
        else if (innerPanel) tiltForHinge(hingeDegrees, config, swapHinge)
        else coverTiltForHinge(hingeDegrees, config, swapHinge)

    /** Fallback hinge placement (centered) when no FoldingFeature is available. */
    fun centeredFold(width: Float, height: Float, foldSplitsLong: Boolean): FoldLine {
        val splitsX = if (foldSplitsLong) width >= height else width < height
        return FoldLine(splitsX, (if (splitsX) width else height) * 0.5f)
    }

    /**
     * The cover screen as a single pane hinged on one edge, viewed from its
     * center. [DuoConfig.coverFrostFromRight] puts the hinge on the left (the
     * spine side on the OnePlus Open) so the frost is heaviest at the right
     * edge and grows leftward; false mirrors it.
     */
    fun coverFold(width: Float, height: Float, config: DuoConfig): FoldLine =
        if (config.coverFrostFromRight) {
            FoldLine(splitsX = true, position = 0f, eyePos = width * 0.5f, movingSide = 1)
        } else {
            FoldLine(splitsX = true, position = width, eyePos = width * 0.5f, movingSide = -1)
        }

    fun foldFor(innerPanel: Boolean, width: Float, height: Float, config: DuoConfig): FoldLine =
        if (innerPanel) centeredFold(width, height, config.foldSplitsLong) else coverFold(width, height, config)

    fun pxPerMm(context: Context): Float {
        val xdpi = context.resources.displayMetrics.xdpi
        return if (xdpi.isFinite() && xdpi > 0f) xdpi / 25.4f else REFERENCE_PX_PER_MM
    }

    fun setUniforms(
        shader: RuntimeShader,
        width: Float,
        height: Float,
        tiltDegrees: Float,
        config: DuoConfig,
        pxPerMm: Float,
        fold: FoldLine,
    ) {
        shader.setFloatUniform("resolution", width, height)
        shader.setFloatUniform("tiltDegrees", tiltDegrees)
        shader.setFloatUniform("eyeDistancePx", config.eyeDistanceMm * pxPerMm)
        shader.setFloatUniform("hingePos", fold.position)
        shader.setFloatUniform("eyeX", fold.eyePos)
        shader.setFloatUniform("axisSwap", if (fold.splitsX) 0f else 1f)
        shader.setFloatUniform("paneSide", (fold.movingSide ?: config.movingSide).toFloat())
        shader.setFloatUniform("blurSpread", config.blurSpread)
        // Blur radius is in device px; renormalize the per-px darkening from the
        // original's ~6 px/mm so dense panels don't crush to black.
        shader.setFloatUniform("darkening", config.darkening * REFERENCE_PX_PER_MM / pxPerMm)
    }
}
