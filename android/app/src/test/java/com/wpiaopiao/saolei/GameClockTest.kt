package com.wpiaopiao.saolei

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameClockTest {

    @Test
    fun `before start nothing counts`() {
        val c = GameClock()
        assertFalse(c.running)
        assertEquals(0L, c.activeMs(5_000L))
        assertEquals(0L, c.durationMs(5_000L))
        assertEquals(0, c.seconds(5_000L))
        // 未开局 resume 应被忽略
        c.resume(5_000L)
        assertFalse(c.running)
    }

    @Test
    fun `active time excludes paused overlay time but duration includes it`() {
        val c = GameClock()
        c.startRound(1_000L)          // t=0
        assertEquals(2_000L, c.activeMs(3_000L))
        c.pause(3_000L)               // 晕 2s
        assertEquals(2_000L, c.activeMs(10_000L))
        assertEquals(9_000L, c.durationMs(10_000L))
        assertEquals(7_000L, c.pausedMs(10_000L))
        c.resume(10_000L)
        assertTrue(c.running)
        assertEquals(3_000L, c.activeMs(11_000L))
        assertEquals(10_000L, c.durationMs(11_000L))
    }

    @Test
    fun `display seconds capped at 999 while stats seconds uncapped`() {
        val c = GameClock()
        c.startRound(0L)
        assertEquals(999, c.displaySeconds(1_000_000L))
        assertEquals(1000, c.seconds(1_000_000L))
    }

    @Test
    fun `repeat pause and resume accumulates`() {
        val c = GameClock()
        c.startRound(0L)
        c.pause(1_000L)
        c.resume(2_000L)
        c.pause(4_000L)
        c.resume(6_000L)
        // active: 0..1s + 2..4s + 6..7s = 4s；paused: 1s + 2s = 3s
        assertEquals(4_000L, c.activeMs(7_000L))
        assertEquals(3_000L, c.pausedMs(7_000L))
        assertEquals(7_000L, c.durationMs(7_000L))
    }

    @Test
    fun `reset clears round state`() {
        val c = GameClock()
        c.startRound(0L)
        c.pause(5_000L)
        c.reset()
        assertFalse(c.running)
        assertEquals(0L, c.durationMs(99_000L))
        assertEquals(0, c.seconds(99_000L))
    }
}
