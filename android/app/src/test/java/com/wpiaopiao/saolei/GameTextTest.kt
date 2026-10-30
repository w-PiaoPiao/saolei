package com.wpiaopiao.saolei

import org.junit.Assert.assertEquals
import org.junit.Test

class GameTextTest {

    @Test
    fun `counter pads non-negative to 3 digits`() {
        assertEquals("000", GameText.formatCounter(0))
        assertEquals("007", GameText.formatCounter(7))
        assertEquals("123", GameText.formatCounter(123))
        assertEquals("999", GameText.formatCounter(999))
    }

    @Test
    fun `counter keeps sign and pads absolute value to 2 digits`() {
        assertEquals("-05", GameText.formatCounter(-5))
        assertEquals("-80", GameText.formatCounter(-80))
        // 超过 3 位时原样返回（调用方负责缩小字号）
        assertEquals("-2125", GameText.formatCounter(-2125))
        assertEquals("2125", GameText.formatCounter(2125))
    }

    @Test
    fun `time formats as minutes and zero-padded seconds`() {
        assertEquals("0:00", GameText.formatTime(0))
        assertEquals("0:05", GameText.formatTime(5))
        assertEquals("1:05", GameText.formatTime(65))
        assertEquals("16:39", GameText.formatTime(999))
        assertEquals("1000:00", GameText.formatTime(60_000))
        assertEquals("0:00", GameText.formatTime(-3))
    }

    @Test
    fun `rate uses dot decimal separator and null placeholder`() {
        assertEquals("--", GameText.formatRate(null))
        assertEquals("1.23", GameText.formatRate(1.234))
        assertEquals("0.00", GameText.formatRate(0.0))
        assertEquals("1.0", GameText.formatOneDecimal(1.04f))
    }
}
