package com.duoopen.fold

import com.duoopen.settings.DuoConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuoShaderMappingTest {
    private val config = DuoConfig()

    @Test
    fun `inner panel is fully frosted at the swap and flat past the flat threshold`() {
        assertEquals(DuoShader.MAX_TILT, DuoShader.tiltForHinge(20f, config, swapHinge = 20f), 0.01f)
        assertEquals(DuoShader.MAX_TILT, DuoShader.tiltForHinge(85f, config, swapHinge = 85f), 0.01f)
        assertEquals(0f, DuoShader.tiltForHinge(DuoShader.FLAT_HINGE, config, swapHinge = 85f), 0.01f)
        assertEquals(0f, DuoShader.tiltForHinge(179f, config, swapHinge = 20f), 0.01f)
    }

    @Test
    fun `inner panel clears over the whole range from a late swap`() {
        val a = DuoShader.tiltForHinge(100f, config, swapHinge = 85f)
        val b = DuoShader.tiltForHinge(140f, config, swapHinge = 85f)
        assertTrue(a > b && b > 0f)
    }

    @Test
    fun `cover is flat when shut and full at the swap, one panel at a time`() {
        assertEquals(0f, DuoShader.coverTiltForHinge(0f, config, swapHinge = 20f), 0.01f)
        assertEquals(0f, DuoShader.coverTiltForHinge(DuoShader.CLOSED_HINGE, config, swapHinge = 20f), 0.01f)
        assertEquals(DuoShader.MAX_TILT, DuoShader.coverTiltForHinge(20f, config, swapHinge = 20f), 0.01f)
        // Still lit a little past the swap (fast open): stays full, never clears.
        assertEquals(DuoShader.MAX_TILT, DuoShader.coverTiltForHinge(34f, config, swapHinge = 20f), 0.01f)
    }

    @Test
    fun `cover that stays lit peaks at the swap and is flat again when open`() {
        val swap = 85f
        assertEquals(0f, DuoShader.coverTiltForHinge(0f, config, swap, staysLit = true), 0.01f)
        assertEquals(DuoShader.MAX_TILT, DuoShader.coverTiltForHinge(swap, config, swap, staysLit = true), 0.01f)
        assertEquals(0f, DuoShader.coverTiltForHinge(DuoShader.FLAT_HINGE, config, swap, staysLit = true), 0.01f)
        assertTrue(DuoShader.coverTiltForHinge(120f, config, swap, staysLit = true) in 1f..44f)
    }

    @Test
    fun `cover curve is the same below the peak whether or not the inner panel is lit`() {
        // The inner panel lights at the swap; the mapping must not jump there.
        for (swap in listOf(20f, 45f, 85f)) {
            for (h in 0..swap.toInt()) {
                val a = DuoShader.coverTiltForHinge(h.toFloat(), config, swap, staysLit = false)
                val b = DuoShader.coverTiltForHinge(h.toFloat(), config, swap, staysLit = true)
                // Smoothstep vs linear differ in shape, but agree at both ends…
                if (h.toFloat() <= DuoShader.CLOSED_HINGE || h.toFloat() == swap) assertEquals("hinge $h swap $swap", a, b, 0.01f)
            }
            // …and at the swap itself both are at full frost, so the handover is continuous.
            assertEquals(DuoShader.MAX_TILT, DuoShader.coverTiltForHinge(swap, config, swap, staysLit = true), 0.01f)
            assertEquals(DuoShader.MAX_TILT, DuoShader.coverTiltForHinge(swap, config, swap, staysLit = false), 0.01f)
        }
    }

    @Test
    fun `cover and inner clear together past the swap on a both-panels phone`() {
        val swap = 85f
        for (h in 90..170 step 10) {
            val cover = DuoShader.coverTiltForHinge(h.toFloat(), config, swap, staysLit = true)
            val inner = DuoShader.tiltForHinge(h.toFloat(), config, swap)
            assertTrue("both still frosted at $h", cover > 0f && inner > 0f)
        }
    }

    @Test
    fun `absurd swap angles are clamped`() {
        assertEquals(0f, DuoShader.coverTiltForHinge(0f, config, swapHinge = 2f), 0.01f)
        assertTrue(DuoShader.tiltForHinge(90f, config, swapHinge = 400f) > 0f)
    }
}
