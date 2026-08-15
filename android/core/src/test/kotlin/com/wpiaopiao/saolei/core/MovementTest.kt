package com.wpiaopiao.saolei.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 方向合成测试：由桌面版 test/movement.test.js 移植。
 */
class MovementTest {

    private fun held(vararg actions: String): Set<String> = actions.toSet()

    @Test
    fun `空集合不移动`() {
        assertNull(Movement.combineDirections(held(), 9, 9, 5, 5))
    }

    @Test
    fun `单方向移动`() {
        assertEquals(4 to 5, Movement.combineDirections(held("moveUp"), 9, 9, 5, 5))
        assertEquals(6 to 5, Movement.combineDirections(held("moveDown"), 9, 9, 5, 5))
        assertEquals(5 to 4, Movement.combineDirections(held("moveLeft"), 9, 9, 5, 5))
        assertEquals(5 to 6, Movement.combineDirections(held("moveRight"), 9, 9, 5, 5))
    }

    @Test
    fun `斜向组合：左加上 → 左上`() {
        assertEquals(4 to 4, Movement.combineDirections(held("moveLeft", "moveUp"), 9, 9, 5, 5))
        assertEquals(4 to 6, Movement.combineDirections(held("moveRight", "moveUp"), 9, 9, 5, 5))
        assertEquals(6 to 4, Movement.combineDirections(held("moveLeft", "moveDown"), 9, 9, 5, 5))
        assertEquals(6 to 6, Movement.combineDirections(held("moveRight", "moveDown"), 9, 9, 5, 5))
    }

    @Test
    fun `相反方向抵消：上+下 或 左+右 不移动`() {
        assertNull(Movement.combineDirections(held("moveUp", "moveDown"), 9, 9, 5, 5))
        assertNull(Movement.combineDirections(held("moveLeft", "moveRight"), 9, 9, 5, 5))
    }

    @Test
    fun `三个方向组合：仍按剩余方向移动`() {
        assertEquals(5 to 4, Movement.combineDirections(held("moveUp", "moveDown", "moveLeft"), 9, 9, 5, 5))
    }

    @Test
    fun `边界裁剪：左上角按住 上+左 不动`() {
        assertEquals(0 to 0, Movement.combineDirections(held("moveUp", "moveLeft"), 9, 9, 0, 0))
    }

    @Test
    fun `边界裁剪：右下角按住 下+右 不动`() {
        assertEquals(8 to 8, Movement.combineDirections(held("moveDown", "moveRight"), 9, 9, 8, 8))
    }

    @Test
    fun `未知动作被忽略`() {
        assertEquals(4 to 5, Movement.combineDirections(held("moveUp", "activate"), 9, 9, 5, 5))
    }
}
