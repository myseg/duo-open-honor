package com.duoopen.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SamsungHingeLogTest {
    private val action = "com.duoopen.angle.READ_123"

    @Test
    fun `folding_angle step from the sensor HAL`() {
        val r = SamsungHingeLog.parse(
            "1761400000.123  1234  1300 I sensors-hal: [0]folding_angle ts=123456789012 ns value [ 89/0]",
            action,
        )!!
        assertEquals(89f, r.angle, 0f)
        assertEquals(ShellProtocol.SOURCE_FOLDING, r.source)
        assertEquals(123456789012L, r.halTimestampNs)
    }

    @Test
    fun `lid_angle_fusion step from the sensor HAL`() {
        val r = SamsungHingeLog.parse(
            "1761400000.456  1234  1300 I sensors-hal: [1]lid_angle_fusion ts=223456789012 ns value [2/ 82/3]",
            action,
        )!!
        assertEquals(82f, r.angle, 0f)
        assertEquals(ShellProtocol.SOURCE_LID, r.source)
        assertEquals(223456789012L, r.halTimestampNs)
    }

    @Test
    fun `hall sensor shut and open`() {
        val shut = SamsungHingeLog.parse("1761400001.000  1234  1300 I sensors-hal: [0]folding_angle device_mode(0 -> 1)", action)!!
        assertEquals(ShellProtocol.SOURCE_SHUT, shut.source)
        assertEquals(0f, shut.angle, 0f)
        val open = SamsungHingeLog.parse("1761400002.000  1234  1300 I sensors-hal: [0]folding_angle device_mode(1 -> 0)", action)!!
        assertEquals(ShellProtocol.SOURCE_UNSHUT, open.source)
    }

    @Test
    fun `other sensors-hal lines are ignored`() {
        assertNull(SamsungHingeLog.parse("1761400000.1  1234  1300 I sensors-hal: [3]light ts=1 ns value [ 120/0]", action))
        assertNull(SamsungHingeLog.parse("1761400000.1  1234  1300 I sensors-hal: [0]folding_angle ts=1 ns value [ 999/0]", action))
    }

    @Test
    fun `fold wallpaper line, Fold 8 format`() {
        val r = SamsungHingeLog.parse(
            "1761400000.1  2345  2400 V SprWallpaper|FoldInteractive: onCommand: action=$action, x=0 y=0 z=0 mCurrentAngle=123.4, isVisible=true",
            action,
        )!!
        assertEquals(123.4f, r.angle, 0.001f)
        assertEquals(ShellProtocol.SOURCE_WALLPAPER, r.source)
    }

    @Test
    fun `fold wallpaper line, Fold 7 bracketed format`() {
        val r = SamsungHingeLog.parse(
            "1761400000.1  2345  2400 V SprWallpaper|FoldInteractive: onCommand: action[$action] mCurrentAngle[77] isVisible[true]",
            action,
        )!!
        assertEquals(77f, r.angle, 0f)
    }

    @Test
    fun `wallpaper lines for other commands, hidden wallpaper, or when disabled are ignored`() {
        assertNull(SamsungHingeLog.parse("1761400000.1  2345  2400 V SprWallpaper|FoldInteractive: onCommand: action=android.wallpaper.tap, mCurrentAngle=120, isVisible=true", action))
        assertNull(SamsungHingeLog.parse("1761400000.1  2345  2400 V SprWallpaper|FoldInteractive: onCommand: action=$action, mCurrentAngle=120, isVisible=false", action))
        assertNull(SamsungHingeLog.parse("1761400000.1  2345  2400 V SprWallpaper|FoldInteractive: onCommand: action=$action, mCurrentAngle=120, isVisible=true", ""))
    }
}
