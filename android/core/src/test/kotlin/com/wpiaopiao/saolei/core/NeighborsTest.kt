package com.wpiaopiao.saolei.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L15：八邻域 helper 的边界与一致性测试。
 */
class NeighborsTest {

    private fun neighborsOf(rows: Int, cols: Int, row: Int, col: Int): Set<Pair<Int, Int>> {
        val out = linkedSetOf<Pair<Int, Int>>()
        Neighbors.forEachNeighbor(rows, cols, row, col) { r, c -> out.add(r to c) }
        return out
    }

    @Test
    fun `角落-边缘-中心的邻居集合正确`() {
        val board = 5 to 5
        assertEquals(
            setOf(0 to 1, 1 to 0, 1 to 1),
            neighborsOf(board.first, board.second, 0, 0)
        )
        assertEquals(
            setOf(0 to 1, 0 to 3, 1 to 1, 1 to 2, 1 to 3),
            neighborsOf(board.first, board.second, 0, 2)
        )
        assertEquals(8, neighborsOf(board.first, board.second, 2, 2).size)
        assertEquals(
            Neighbors.NEIGHBOR_OFFSETS.toSet(),
            neighborsOf(board.first, board.second, 2, 2).map { (r, c) -> r - 2 to c - 2 }.toSet()
        )
    }

    @Test
    fun `邻居数量与退化棋盘不崩`() {
        assertEquals(3, Neighbors.neighborCount(5, 5, 0, 0))
        assertEquals(5, Neighbors.neighborCount(5, 5, 0, 2))
        assertEquals(8, Neighbors.neighborCount(5, 5, 2, 2))
        assertEquals(3, Neighbors.neighborCount(2, 2, 0, 0))
        assertEquals(0, Neighbors.neighborCount(1, 1, 0, 0))
        // 0 尺寸/越界：不回调、不抛异常
        assertEquals(0, Neighbors.neighborCount(0, 0, 0, 0))
        assertTrue(neighborsOf(0, 0, 0, 0).isEmpty())
        assertTrue(neighborsOf(0, 5, 0, 0).isEmpty())
        assertTrue(neighborsOf(5, 0, 0, 0).isEmpty())
        assertTrue(neighborsOf(5, 5, 9, 9).isEmpty())
        assertEquals(0, Neighbors.neighborCount(5, 5, -1, 0))
    }

    @Test
    fun `偏移表与内联参考实现一致`() {
        // 8 个方向、无 (0,0)、无重复、分量范围正确
        assertEquals(8, Neighbors.NEIGHBOR_OFFSETS.size)
        assertEquals(Neighbors.NEIGHBOR_OFFSETS.size, Neighbors.NEIGHBOR_OFFSETS.toSet().size)
        assertFalse(Neighbors.NEIGHBOR_OFFSETS.contains(0 to 0))
        assertTrue(
            Neighbors.NEIGHBOR_OFFSETS.all { (dr, dc) ->
                dr in -1..1 && dc in -1..1 && !(dr == 0 && dc == 0)
            }
        )
        // 与手写三重循环逐格对照（含边界/退化，避免 helper 与旧实现语义漂移）
        for (rows in listOf(1, 2, 5)) {
            for (cols in listOf(1, 2, 5)) {
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        val reference = linkedSetOf<Pair<Int, Int>>()
                        for (dr in -1..1) {
                            for (dc in -1..1) {
                                if (dr == 0 && dc == 0) continue
                                val nr = r + dr
                                val nc = c + dc
                                if (nr in 0 until rows && nc in 0 until cols) reference.add(nr to nc)
                            }
                        }
                        assertEquals("$rows x $cols ($r,$c)", reference, neighborsOf(rows, cols, r, c))
                    }
                }
            }
        }
    }
}
