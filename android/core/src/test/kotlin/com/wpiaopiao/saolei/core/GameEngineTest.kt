package com.wpiaopiao.saolei.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引擎单元测试：由桌面版 test/game.test.js 移植。
 */
class GameEngineTest {

    /** 手动构造已知雷局：设置雷、计算数字、置 minesPlaced（对齐 JS 版 setupEngine） */
    private fun setupEngine(level: String, mines: List<Pair<Int, Int>>): GameEngine {
        val g = GameEngine(level)
        for (r in 0 until g.rows) {
            for (c in 0 until g.cols) {
                g.board[r][c].mine = false
                g.board[r][c].adjacentMines = 0
            }
        }
        for ((r, c) in mines) g.board[r][c].mine = true
        g.totalMines = mines.size
        g.minesPlaced = true
        for (r in 0 until g.rows) {
            for (c in 0 until g.cols) {
                if (!g.board[r][c].mine) g.board[r][c].adjacentMines = g.countAdjacentMines(r, c)
            }
        }
        return g
    }

    private fun countMines(g: GameEngine): Int {
        var n = 0
        for (r in 0 until g.rows) {
            for (c in 0 until g.cols) {
                if (g.board[r][c].mine) n++
            }
        }
        return n
    }

    @Test
    fun `初始化：棋盘尺寸与雷数正确`() {
        val g = GameEngine("expert")
        assertEquals(16, g.rows)
        assertEquals(30, g.cols)
        assertEquals(99, g.totalMines)
        assertEquals("ready", g.state)
        assertEquals(16, g.board.size)
        assertEquals(30, g.board[0].size)
    }

    @Test
    fun `未知难度报错`() {
        val g = GameEngine()
        assertThrows(IllegalArgumentException::class.java) { g.init("nope") }
    }

    @Test
    fun `自定义模式`() {
        val g = GameEngine()
        g.initCustom(12, 20, 30)
        assertEquals("custom", g.currentLevel)
        assertEquals(12, g.rows)
        assertEquals(20, g.cols)
        assertEquals(30, g.totalMines)
    }

    @Test
    fun `首次点击安全：3x3 无雷、自身为空白、雷数正确`() {
        val g = GameEngine("beginner")
        g.reveal(4, 4)
        assertEquals("playing", g.state)
        for (dr in -1..1) {
            for (dc in -1..1) {
                assertFalse("(${4 + dr},${4 + dc}) 不应有雷", g.board[4 + dr][4 + dc].mine)
            }
        }
        assertEquals(0, g.board[4][4].adjacentMines)
        assertEquals(10, countMines(g))
        assertTrue(g.revealedCount > 0)
    }

    @Test
    fun `角落首次点击同样安全`() {
        val g = GameEngine("beginner")
        g.reveal(0, 0)
        for (r in 0..1) {
            for (c in 0..1) {
                assertFalse(g.board[r][c].mine)
            }
        }
        assertEquals(10, countMines(g))
    }

    @Test
    fun `踩雷判负并翻开所有雷`() {
        val g = setupEngine("beginner", listOf(0 to 0, 1 to 1))
        g.reveal(1, 1)
        assertEquals("lost", g.state)
        assertTrue(g.board[1][1].exploded)
        assertTrue(g.board[0][0].revealed)
        assertTrue(g.board[1][1].revealed)
    }

    @Test
    fun `翻开所有安全格判胜`() {
        val g = setupEngine("beginner", listOf(0 to 0, 0 to 1, 1 to 1))
        for (r in 0 until g.rows) {
            for (c in 0 until g.cols) {
                if (!g.board[r][c].mine) g.reveal(r, c)
            }
        }
        assertEquals("won", g.state)
    }

    @Test
    fun `正确插旗判定胜利`() {
        val g = setupEngine("beginner", listOf(0 to 0, 0 to 1, 1 to 1))
        for ((r, c) in listOf(0 to 0, 0 to 1, 1 to 1)) g.toggleFlag(r, c)
        assertEquals("won", g.state)
    }

    @Test
    fun `错误插旗不判胜`() {
        val g = setupEngine("beginner", listOf(0 to 0, 0 to 1, 1 to 1))
        g.toggleFlag(0, 0)
        g.toggleFlag(0, 1)
        g.toggleFlag(2, 2)
        assertEquals("playing", g.state)
    }

    @Test
    fun `和弦成功：旗数匹配时翻开周围格`() {
        val g = setupEngine("beginner", listOf(1 to 1, 8 to 8))
        g.reveal(0, 0)
        assertEquals(1, g.board[0][0].adjacentMines)
        g.toggleFlag(1, 1)
        val didChord = g.chord(0, 0)
        assertTrue(didChord)
        assertTrue(g.board[0][1].revealed)
        assertTrue(g.board[1][0].revealed)
        assertFalse("已插旗不翻开", g.board[1][1].revealed)
    }

    @Test
    fun `和弦失败：旗数不匹配时不翻开`() {
        val g = setupEngine("beginner", listOf(1 to 1))
        g.reveal(0, 0)
        val didChord = g.chord(0, 0)
        assertFalse(didChord)
        assertFalse(g.board[0][1].revealed)
    }

    @Test
    fun `脏标记：操作后记录变更格子`() {
        val g = GameEngine("beginner")
        assertTrue("init 即全量脏", g.dirty.size > 0)
        g.dirty.clear()
        g.reveal(4, 4)
        assertTrue(g.dirty.size > 0)
    }

    @Test
    fun `终局后引擎拒绝继续操作`() {
        val g = setupEngine("beginner", listOf(0 to 0))
        g.reveal(0, 0)
        assertEquals("lost", g.state)
        val revealedBefore = g.revealedCount
        g.reveal(8, 8)
        assertEquals(revealedBefore, g.revealedCount)
    }
}
