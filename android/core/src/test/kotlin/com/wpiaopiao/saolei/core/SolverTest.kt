package com.wpiaopiao.saolei.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无猜求解器与 3BV 测试：与桌面版 test/solver.test.js 对齐。
 */
class SolverTest {

    /** 手工构造任意尺寸棋盘（设置雷位并计算数字） */
    private fun boardOf(rows: Int, cols: Int, mines: List<Pair<Int, Int>>): GameEngine {
        val g = GameEngine()
        g.initCustom(rows, cols, mines.size)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                g.board[r][c].mine = false
                g.board[r][c].adjacentMines = 0
            }
        }
        for ((r, c) in mines) g.board[r][c].mine = true
        g.minesPlaced = true
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (!g.board[r][c].mine) g.board[r][c].adjacentMines = g.countAdjacentMines(r, c)
            }
        }
        return g
    }

    @Test
    fun `求解器：单雷角局可从远处推理通关`() {
        val g = boardOf(9, 9, listOf(0 to 0))
        assertTrue(Solver.solve(g.board, 9, 9, 1, 4, 4))
    }

    @Test
    fun `求解器：多解的必猜局面判为不可解`() {
        // 5×5：只翻开了 (1,1)（数字 1），其余全未知；雷在 (0,0) 与 (4,4)。
        // 把 (0,0) 换成 (0,1) 后 (1,1) 的数字仍是 1、其他已翻开信息完全不变 → 至少两个解 → 必须猜。
        val g = boardOf(5, 5, listOf(0 to 0, 4 to 4))
        assertEquals(1, g.board[1][1].adjacentMines)
        assertFalse(g.board[0][1].mine)
        assertFalse(Solver.solve(g.board, 5, 5, 2, 1, 1))
    }

    @Test
    fun `无猜模式：生成的初级与中级局必可纯逻辑通关`() {
        for (level in listOf("beginner", "intermediate")) {
            repeat(5) {
                val g = GameEngine(level)
                g.noGuess = true
                val r = (0 until g.rows).random()
                val c = (0 until g.cols).random()
                g.reveal(r, c)
                assertFalse("$level 生成不应降级", g.noGuessFallback)
                assertTrue("$level 生成应可纯逻辑通关", Solver.solve(g.board, g.rows, g.cols, g.totalMines, r, c))
            }
        }
    }

    @Test
    fun `无猜模式：高级局生成成功且首点安全`() {
        val g = GameEngine("expert")
        g.noGuess = true
        g.reveal(8, 15)
        assertFalse(g.noGuessFallback)
        assertEquals("playing", g.state)
        for (dr in -1..1) {
            for (dc in -1..1) {
                assertFalse(g.board[8 + dr][15 + dc].mine)
            }
        }
        assertTrue(Solver.solve(g.board, g.rows, g.cols, g.totalMines, 8, 15))
    }

    @Test
    fun `关闭无猜模式时不触发重生成`() {
        val g = GameEngine("beginner")
        g.noGuess = false
        g.reveal(4, 4)
        assertFalse(g.noGuessFallback)
        assertEquals("playing", g.state)
    }

    @Test
    fun `3BV：单个零区域展开全盘为 1`() {
        val g = boardOf(5, 5, listOf(0 to 0))
        assertEquals(1, g.compute3BV())
    }

    @Test
    fun `3BV：被雷墙分断的两个零区域为 2`() {
        val mines = (0 until 9).map { it to 4 }
        val g = boardOf(9, 9, mines)
        assertEquals(2, g.compute3BV())
    }

    @Test
    fun `3BV：没有零区域时等于未被展开的数字格数`() {
        val mines = mutableListOf<Pair<Int, Int>>()
        for (r in 0 until 9) {
            for (c in 0 until 9) {
                if (r % 2 == 0 && c % 2 == 0) mines.add(r to c)
            }
        }
        val g = boardOf(9, 9, mines)
        var zeros = 0
        var numbers = 0
        for (r in 0 until 9) {
            for (c in 0 until 9) {
                val cell = g.board[r][c]
                if (!cell.mine) {
                    if (cell.adjacentMines == 0) zeros++ else numbers++
                }
            }
        }
        assertEquals("该构造不应存在零区域", 0, zeros)
        assertEquals(numbers, g.compute3BV())
    }

    @Test
    fun `布雷后 bbbv 自动计算且新局重置`() {
        val g = GameEngine("beginner")
        assertEquals(0, g.bbbv)
        g.reveal(4, 4)
        assertTrue(g.bbbv > 0)
        g.init("beginner")
        assertEquals(0, g.bbbv)
        g.reveal(0, 0)
        assertTrue(g.bbbv > 0)
    }
}
