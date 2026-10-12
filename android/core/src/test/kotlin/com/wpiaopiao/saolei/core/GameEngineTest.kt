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
    fun `踩雷终局：非雷格上的旗标记为错误旗`() {
        val g = setupEngine("beginner", listOf(0 to 0))
        g.toggleFlag(5, 5)                 // 非雷格插旗
        g.reveal(0, 0)                     // 踩雷判负
        assertEquals("lost", g.state)
        assertTrue("非雷格上的旗应标记为错误旗", g.board[5][5].wrongFlag)
        assertTrue("错误旗保留旗状态供 UI 叠加红叉", g.board[5][5].flagged)
        assertFalse("雷格本身不是错误旗", g.board[0][0].wrongFlag)
    }

    @Test
    fun `新局重置清除错误旗标记`() {
        val g = setupEngine("beginner", listOf(0 to 0, 1 to 1))
        g.toggleFlag(5, 5)
        g.reveal(0, 0)
        assertEquals("lost", g.state)
        g.init("beginner")
        assertFalse(g.board[5][5].wrongFlag)
        assertFalse(g.board[0][0].flagged)
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

    // ---------- 输入校验与边界守卫（防止非法配置把异常延迟到首点） ----------

    @Test
    fun `initCustom 拒绝非法配置`() {
        val g = GameEngine()
        assertThrows(IllegalArgumentException::class.java) { g.initCustom(0, 9, 1) }
        assertThrows(IllegalArgumentException::class.java) { g.initCustom(9, 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { g.initCustom(-1, 9, 0) }
        assertThrows(IllegalArgumentException::class.java) { g.initCustom(9, 9, -5) }
        // 上限 = rows*cols - 首点 3×3 安全区
        assertThrows(IllegalArgumentException::class.java) { g.initCustom(9, 9, 73) }
        g.initCustom(9, 9, 72)
        assertEquals(72, g.totalMines)
    }

    @Test
    fun `initCustom 允许退化但合法的棋盘`() {
        val g = GameEngine()
        g.initCustom(1, 1, 0)
        assertEquals(1, g.rows)
        assertEquals(0, g.totalMines)
        g.initCustom(2, 2, 0)
        assertEquals(4, g.rows * g.cols)
    }

    @Test
    fun `越界坐标的操作安全返回且无副作用`() {
        val g = setupEngine("beginner", listOf(0 to 0))
        val revealedBefore = g.revealedCount
        val flagsBefore = g.flagCount
        val stateBefore = g.state
        for ((r, c) in listOf(-1 to 0, 0 to -1, 9 to 0, 0 to 9, 99 to 99, -99 to -99)) {
            g.reveal(r, c)
            g.toggleFlag(r, c)
            g.chord(r, c)
        }
        assertEquals(revealedBefore, g.revealedCount)
        assertEquals(flagsBefore, g.flagCount)
        assertEquals(stateBefore, g.state)
    }

    @Test
    fun `插旗三态循环：旗→问号→取消，旗数同步增减`() {
        val g = setupEngine("beginner", listOf(0 to 0))
        assertEquals(0, g.flagCount)
        g.toggleFlag(5, 5)
        assertTrue(g.board[5][5].flagged)
        assertEquals(1, g.flagCount)
        g.toggleFlag(5, 5)
        assertFalse(g.board[5][5].flagged)
        assertTrue(g.board[5][5].questioned)
        assertEquals(0, g.flagCount)
        g.toggleFlag(5, 5)
        assertFalse(g.board[5][5].questioned)
        assertEquals(0, g.flagCount)
    }

    @Test
    fun `问号格被翻开时清除问号标记`() {
        val g = setupEngine("beginner", listOf(0 to 0))
        g.toggleFlag(5, 5)
        g.toggleFlag(5, 5) // 转为问号
        assertTrue(g.board[5][5].questioned)
        g.reveal(5, 5)
        assertTrue(g.board[5][5].revealed)
        assertFalse(g.board[5][5].questioned)
    }

    @Test
    fun `终局后和弦不再生效`() {
        val g = setupEngine("beginner", listOf(1 to 1, 8 to 8))
        g.reveal(0, 0)
        g.toggleFlag(1, 1) // 只插对 1 个旗，共 2 雷 → 不判胜
        g.reveal(8, 8) // 踩雷判负
        assertEquals("lost", g.state)
        val revealedBefore = g.revealedCount
        assertFalse(g.chord(0, 0))
        assertEquals(revealedBefore, g.revealedCount)
    }

    @Test
    fun `胜利终局后同样拒绝操作`() {
        // 雷相邻 (0,0)：胜局下它的相邻旗数仍与数字相等，去掉守卫会误判为「可和弦」
        val g = setupEngine("beginner", listOf(1 to 1, 1 to 2))
        g.reveal(0, 0)
        assertEquals(1, g.board[0][0].adjacentMines)
        g.toggleFlag(1, 1)
        g.toggleFlag(1, 2)
        assertEquals("won", g.state)
        val revealedBefore = g.revealedCount
        assertFalse(g.chord(0, 0))
        g.reveal(5, 5)
        g.toggleFlag(6, 6)
        assertEquals(revealedBefore, g.revealedCount)
        assertEquals(2, g.flagCount)
    }

    // ---------- 后台生成（planMines / applyPlan 拆分，对应桌面 generator.js） ----------

    @Test
    fun `planMines 普通模式：雷数与首点安全区正确`() {
        val plan = GameEngine.planMines(16, 30, 99, 8, 15, false)
        assertEquals(16 * 30, plan.mines.size)
        assertEquals(99, plan.mines.count { it })
        for (dr in -1..1) {
            for (dc in -1..1) {
                assertFalse("首点 3×3 不应有雷", plan.mines[(8 + dr) * 30 + (15 + dc)])
            }
        }
        assertFalse(plan.noGuessFallback)
    }

    @Test
    fun `planMines 不修改引擎实例状态（可安全在后台线程调用）`() {
        val g = GameEngine("beginner")
        GameEngine.planMines(16, 30, 99, 8, 15, true)
        assertFalse("生成不应触碰实例棋盘", g.minesPlaced)
        assertEquals(9, g.rows)
        assertEquals(10, g.totalMines)
    }

    @Test
    fun `planMines 无猜：高级局能在预算内生成可解雷局`() {
        val plan = GameEngine.planMines(16, 30, 99, 8, 15, true)
        assertEquals(99, plan.mines.count { it })
        assertFalse("高级局应能在预算内找到可解局", plan.noGuessFallback)
    }

    @Test
    fun `applyPlanAndReveal：应用后台雷位后正常翻开且首点无雷`() {
        val g = GameEngine("expert")
        val plan = GameEngine.planMines(g.rows, g.cols, g.totalMines, 8, 15, true)
        assertTrue(g.applyPlanAndReveal(8, 15, plan))
        assertEquals("playing", g.state)
        assertTrue(g.revealedCount > 0)
        for (dr in -1..1) {
            for (dc in -1..1) {
                assertFalse("首点 3×3 不应有雷", g.board[8 + dr][15 + dc].mine)
            }
        }
        assertEquals(99, g.board.sumOf { row -> row.count { it.mine } })
    }

    @Test
    fun `applyPlan 幂等：同局重复应用被忽略`() {
        val g = GameEngine("beginner")
        val plan = GameEngine.planMines(g.rows, g.cols, g.totalMines, 4, 4, false)
        g.applyPlan(plan)
        assertTrue(g.minesPlaced)
        val firstMine = g.board[0][0].mine
        // 换一份全雷规划再应用：应被忽略，不污染既有雷局
        g.applyPlan(GameEngine.MinePlan(BooleanArray(g.rows * g.cols) { true }, true))
        assertEquals(firstMine, g.board[0][0].mine)
        assertFalse(g.noGuessFallback)
    }

    @Test
    fun `planMines 参数非法时报错`() {
        assertThrows(IllegalArgumentException::class.java) { GameEngine.planMines(0, 9, 1, 0, 0, false) }
        assertThrows(IllegalArgumentException::class.java) { GameEngine.planMines(9, 9, -1, 4, 4, false) }
    }
}
