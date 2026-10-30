package com.wpiaopiao.saolei

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShakeAnimationTest {

    private val eps = 0.0001f

    @Test
    fun `端点与越界进度返回 0`() {
        assertEquals(0f, ShakeAnimation.horizontalOffset(0f), eps)
        assertEquals(0f, ShakeAnimation.horizontalOffset(1f), eps)
        assertEquals(0f, ShakeAnimation.horizontalOffset(-0.2f), eps)
        assertEquals(0f, ShakeAnimation.horizontalOffset(1.2f), eps)
        assertEquals(0f, ShakeAnimation.horizontalOffset(Float.NaN), eps)
    }

    @Test
    fun `关键帧与桌面 CSS 对齐：0 -3 3 -2 2 0`() {
        assertEquals(0f, ShakeAnimation.horizontalOffset(0.0f), eps)
        assertEquals(-3f, ShakeAnimation.horizontalOffset(0.2f), eps)
        assertEquals(3f, ShakeAnimation.horizontalOffset(0.4f), eps)
        assertEquals(-2f, ShakeAnimation.horizontalOffset(0.6f), eps)
        assertEquals(2f, ShakeAnimation.horizontalOffset(0.8f), eps)
        assertEquals(0f, ShakeAnimation.horizontalOffset(1.0f), eps)
    }

    @Test
    fun `关键帧之间线性插值`() {
        assertEquals(-1.5f, ShakeAnimation.horizontalOffset(0.1f), eps)
        assertEquals(-2.25f, ShakeAnimation.horizontalOffset(0.15f), eps)
        assertEquals(0f, ShakeAnimation.horizontalOffset(0.3f), eps)
    }

    @Test
    fun `位移幅度不超过桌面 3px`() {
        var maxAbs = 0f
        for (i in 0..600) {
            val v = ShakeAnimation.horizontalOffset(i / 600f)
            maxAbs = maxOf(maxAbs, kotlin.math.abs(v))
        }
        assertTrue("max=$maxAbs", maxAbs <= 3.0001f)
    }

    @Test
    fun `总时长与桌面 0_3s 对齐`() {
        assertEquals(300L, ShakeAnimation.DURATION_MS)
    }
}
