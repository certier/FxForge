package dev.fxforge.shizuku

import org.junit.Assert.assertEquals
import org.junit.Test

class LogcatLimitsTest {
    @Test
    fun clampsRequestedLogLinesToSafeBounds() {
        assertEquals(1, LogcatLimits.lines(0))
        assertEquals(200, LogcatLimits.lines(LogcatLimits.DEFAULT_LINES))
        assertEquals(LogcatLimits.MAX_LINES, LogcatLimits.lines(Int.MAX_VALUE))
    }

}
