package com.wpiaopiao.saolei

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverlayHitRegistryTest {

    @Test
    fun `child registered after parent wins the hit test`() {
        val hits = OverlayHitRegistry()
        var parent = 0
        var child = 0
        // 父行先登记，子按钮后登记（与绘制顺序一致）
        hits.add(0f, 0f, 100f, 40f) { parent++ }
        hits.add(60f, 5f, 80f, 35f) { child++ }
        assertEquals(1, hits.hitAt(70f, 20f))
        assertEquals(0, hits.hitAt(10f, 20f))
        hits.actionAt(1)?.invoke()
        assertEquals(0, parent)
        assertEquals(1, child)
    }

    @Test
    fun `miss returns null and boundaries are left-top inclusive`() {
        val hits = OverlayHitRegistry()
        hits.add(10f, 10f, 20f, 20f) {}
        assertNull(hits.hitAt(9.9f, 15f))
        assertNull(hits.hitAt(20f, 15f))
        assertEquals(0, hits.hitAt(10f, 10f))
        assertNull(hits.hitAt(19.99f, 20f))
    }

    @Test
    fun `reset clears everything`() {
        val hits = OverlayHitRegistry()
        hits.add(0f, 0f, 10f, 10f) {}
        assertEquals(1, hits.size)
        hits.reset()
        assertEquals(0, hits.size)
        assertNull(hits.hitAt(5f, 5f))
    }
}
