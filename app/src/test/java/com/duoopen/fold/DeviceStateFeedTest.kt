package com.duoopen.fold

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceStateFeedTest {
    @Test
    fun `posture names`() {
        assertEquals(HingePosture.CLOSED, DeviceStateFeed.postureFor("CLOSED"))
        assertEquals(HingePosture.TENT, DeviceStateFeed.postureFor("TENT"))
        assertEquals(HingePosture.HALF_OPENED, DeviceStateFeed.postureFor("HALF_OPENED"))
        assertEquals(HingePosture.OPENED, DeviceStateFeed.postureFor("OPENED"))
        assertEquals(HingePosture.CLOSED, DeviceStateFeed.postureFor("FOLDED"))
        assertEquals(HingePosture.OPENED, DeviceStateFeed.postureFor("UNFOLDED"))
        assertEquals(HingePosture.HALF_OPENED, DeviceStateFeed.postureFor("HALF_FOLDED"))
    }

    @Test
    fun `states that say nothing about the hinge are ignored`() {
        assertNull(DeviceStateFeed.postureFor("CONCURRENT_INNER_DEFAULT"))
        assertNull(DeviceStateFeed.postureFor("REAR_DISPLAY_STATE"))
        assertNull(DeviceStateFeed.postureFor("REAR_DUAL"))
        assertNull(DeviceStateFeed.postureFor("SOMETHING_ELSE"))
    }
}
