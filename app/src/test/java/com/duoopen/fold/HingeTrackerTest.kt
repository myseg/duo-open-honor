package com.duoopen.fold

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HingeTrackerTest {
    private val s = 1_000_000_000L

    private fun ms(v: Long) = v * 1_000_000L

    @Test
    fun `before any reading the estimate is NaN`() {
        val t = HingeTracker()
        assertTrue(t.estimate(ms(100)).isNaN())
    }

    @Test
    fun `a plain reading is taken as is and held`() {
        val t = HingeTracker()
        t.set(90f, ms(0))
        assertEquals(90f, t.estimate(ms(0)), 0f)
        assertEquals(90f, t.estimate(ms(500)), 0f)
    }

    @Test
    fun `two steps of one sensor give a speed and the estimate glides on`() {
        val t = HingeTracker()
        // Opening at 55°/s: folding_angle logs every 11° → every 200 ms.
        t.add(44f, ms(0), HingeTracker.SOURCE_FOLDING)
        t.add(55f, ms(200), HingeTracker.SOURCE_FOLDING)
        t.add(66f, ms(400), HingeTracker.SOURCE_FOLDING)
        val mid = t.estimate(ms(500))
        assertTrue("glides forward: $mid", mid > 68f && mid < 74f)
        assertTrue(t.cruising(ms(450)))
    }

    @Test
    fun `the glide never passes where the next reading must have come`() {
        val t = HingeTracker()
        t.add(44f, ms(0), HingeTracker.SOURCE_FOLDING)
        t.add(55f, ms(200), HingeTracker.SOURCE_FOLDING)
        t.add(66f, ms(400), HingeTracker.SOURCE_FOLDING)
        // The hand stopped; no reading arrives. Limit: one step (11°) + slack.
        val late = t.estimate(ms(1_300))
        assertTrue("capped near the next step: $late", late < 66f + 11f + 55f * 0.06f + 0.5f)
        // After the coast window it holds where it was, no jump back.
        val held = t.estimate(ms(2_500))
        assertTrue(held >= 66f && held <= late + 0.01f)
    }

    @Test
    fun `the estimate is monotonic while folding one way`() {
        val t = HingeTracker()
        var prev = Float.NaN
        var now = 0L
        for (angle in listOf(33f, 44f, 55f, 66f, 77f, 88f)) {
            t.add(angle, ms(now), HingeTracker.SOURCE_FOLDING)
            for (f in 1..12) {
                val e = t.estimate(ms(now + f * 16))
                if (prev.isFinite()) assertTrue("went back: $prev -> $e", e >= prev - 0.001f)
                prev = e
            }
            now += 200
        }
    }

    @Test
    fun `two sensors interleaved average out their disagreement`() {
        val t = HingeTracker()
        // lid reads ~2° higher than folding, both at 50°/s.
        t.add(44f, ms(0), HingeTracker.SOURCE_FOLDING)
        t.add(56f, ms(200), HingeTracker.SOURCE_LID)
        t.add(55f, ms(220), HingeTracker.SOURCE_FOLDING)
        t.add(66f, ms(400), HingeTracker.SOURCE_LID)
        t.add(66f, ms(440), HingeTracker.SOURCE_FOLDING)
        val e = t.estimate(ms(500))
        assertTrue("plausible mid value: $e", e > 66f && e < 72f)
    }

    @Test
    fun `a reversal starts a new movement`() {
        val t = HingeTracker()
        t.add(44f, ms(0), HingeTracker.SOURCE_FOLDING)
        t.add(55f, ms(200), HingeTracker.SOURCE_FOLDING)
        t.add(66f, ms(400), HingeTracker.SOURCE_FOLDING)
        t.add(55f, ms(900), HingeTracker.SOURCE_FOLDING) // closing again
        val e = t.estimate(ms(950))
        assertTrue("no speed yet after a turn: $e", e <= 66f && e >= 55f)
    }

    @Test
    fun `readings a long time apart do not imply a speed`() {
        val t = HingeTracker()
        t.add(44f, ms(0), HingeTracker.SOURCE_FOLDING)
        t.add(55f, ms(5_000), HingeTracker.SOURCE_FOLDING)
        assertEquals(55f, t.estimate(ms(5_100)), 0.001f)
    }
}
