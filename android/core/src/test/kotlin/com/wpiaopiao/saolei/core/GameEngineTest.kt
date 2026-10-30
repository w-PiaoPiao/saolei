package com.wpiaopiao.saolei.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引擎单元测试：由桌面版 test/game.test.js 移植，并补充 v1.3 的
 * 生成器校验/注入时钟/快照/0 雷盘/脏格编码等回归用例。
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

    private fun beginnerPlan(safeRow: Int = 4, safeCol: Int = 4) =
        GameEngine.planMines(9, 9, 10, safeRow, safeCol, false)

    private fun GameEngine.toggleFlag(cell: Pair<Int, Int>) = toggleFlag(cell.first, cell.second)

    private fun reveal(g: GameEngine, cell: Pair<Int, Int>) = g.reveal(cell.first, cell.second)

    // ---------- 基础引擎行为 ----------

    @Test
    fun `初始化：棋盘尺寸与雷数正确`() {
        val g = GameEngine("expert")
        assertEquals(16, g.rows)
        assertEquals(30, g.cols)
        assertEquals(99, g.totalMines)
        assertEquals("ready", g.state)
        assertTrue(g.hasPendingMines())
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
        assertEquals(12, g.currentCustom!!.rows)
        assertEquals(30, g.currentCustom!!.mines)
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
        assertFalse(g.hasPendingMines())
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
        assertFalse("翻开获胜不应标记为旗胜", g.wonByFlags)
    }

    @Test
    fun `正确插旗判定胜利并标记 wonByFlags`() {
        val g = setupEngine("beginner", listOf(0 to 0, 0 to 1, 1 to 1))
        for ((r, c) in listOf(0 to 0, 0 to 1, 1 to 1)) g.toggleFlag(r, c)
        assertEquals("won", g.state)
        assertTrue(g.wonByFlags)
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
    fun `踩雷终局：非雷格上的旗标记为错误旗`() {
        val g = setupEngine("beginner", listOf(0 to 0))
        g.toggleFlag(5, 5) // 非雷格插旗
        g.reveal(0, 0) // 踩雷判负
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
        assertFalse(g.wonByFlags)
        assertTrue(g.hasPendingMines())
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
        val g = setupEngine("beginner", listOf(1 to 1, 1 to 2))
        g.reveal(0, 0)
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

    // ---------- 输入校验与边界守卫 ----------

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
    fun `initCustom 拒绝超过最大格数的棋盘`() {
        val g = GameEngine()
        assertThrows(IllegalArgumentException::class.java) {
            g.initCustom(101, 100, 0) // 10100 > MAX_CELLS
        }
        assertEquals(9, g.rows)
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

    // ---------- 脏格（M16：Int 编码） ----------

    @Test
    fun `脏标记：init 全量、操作后记录变更格且编码在界内`() {
        val g = GameEngine("beginner")
        assertEquals(81, g.dirty.size)
        assertTrue(g.dirty.all { it in 0 until 81 })
        g.consumeDirty()
        assertEquals(0, g.dirty.size)
        g.reveal(4, 4)
        assertTrue(g.dirty.isNotEmpty())
        val revealedIdx = g.consumeDirty()
        assertTrue("首点洪水应产生脏格", revealedIdx.isNotEmpty())
        assertTrue(revealedIdx.all { it in 0 until 81 })
        assertEquals(0, g.dirty.size)
    }

    @Test
    fun `markDirty 使用行列索引编码`() {
        val g = GameEngine("expert")
        g.dirty.clear()
        g.markDirty(2, 5)
        assertEquals(setOf(2 * 30 + 5), g.dirty)
    }

    // ---------- planMines：普通模式、注入、校验、降级 ----------

    @Test
    fun `planMines 普通模式：雷数与首点安全区正确`() {
        val plan = GameEngine.planMines(16, 30, 99, 8, 15, false)
        assertEquals(16, plan.rows)
        assertEquals(30, plan.cols)
        assertEquals(8, plan.safeRow)
        assertEquals(15, plan.safeCol)
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
    fun `planMines 注入相同随机种子结果可复现`() {
        val a = GameEngine.planMines(9, 9, 10, 4, 4, false, random = kotlin.random.Random(42))
        val b = GameEngine.planMines(9, 9, 10, 4, 4, false, random = kotlin.random.Random(42))
        assertArrayEquals(a.mines, b.mines)
        assertEquals(10, a.mines.count { it })
    }

    @Test
    fun `planMines 参数非法统一抛 IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) { GameEngine.planMines(0, 9, 1, 0, 0, false) }
        assertThrows(IllegalArgumentException::class.java) { GameEngine.planMines(9, 9, -1, 4, 4, false) }
        // 雷数超过可用格（9×9 最多 72）
        assertThrows(IllegalArgumentException::class.java) { GameEngine.planMines(9, 9, 73, 4, 4, false) }
        // 首点越界（普通/无猜都不再静默）
        assertThrows(IllegalArgumentException::class.java) { GameEngine.planMines(9, 9, 10, 9, 0, false) }
        assertThrows(IllegalArgumentException::class.java) { GameEngine.planMines(9, 9, 10, 0, 9, false) }
        assertThrows(IllegalArgumentException::class.java) { GameEngine.planMines(9, 9, 10, 4, 4, true, maxAttempts = 0) }
        // 尺寸上限与 Int 溢出
        assertThrows(IllegalArgumentException::class.java) { GameEngine.planMines(101, 100, 0, 0, 0, false) }
        assertThrows(IllegalArgumentException::class.java) { GameEngine.planMines(46341, 46341, 0, 0, 0, false) }
        // 注入参数非法
        assertThrows(IllegalArgumentException::class.java) {
            GameEngine.planMines(9, 9, 10, 4, 4, false, budgetMs = 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GameEngine.planMines(9, 9, 10, 4, 4, false, maxAttempts = 0)
        }
    }

    @Test
    fun `planMines M1 门控：40x40-400（900 格以上且密度 0点25）最多 2 次即降级`() {
        var nowCalls = 0
        val plan = GameEngine.planMines(
            40, 40, 400, 20, 20, true,
            now = { nowCalls++; 0L },
            random = kotlin.random.Random(7)
        )
        assertEquals(400, plan.mines.count { it })
        assertTrue("大盘+高密度应快速降级", plan.noGuessFallback)
        assertEquals("门控命中只应尝试 GATE_MAX_ATTEMPTS 次", GameEngine.GATE_MAX_ATTEMPTS, nowCalls)
    }

    @Test
    fun `planMines M1 门控：50x50-500（密度 0点20 未达阈值）不被门控，可成功`() {
        var nowCalls = 0
        val plan = GameEngine.planMines(
            50, 50, 500, 25, 25, true,
            now = { nowCalls++; 0L }, // 假时钟不耗预算
            random = kotlin.random.Random(31676)
        )
        assertEquals(500, plan.mines.count { it })
        assertFalse("50×50/500 密度 20% < 25%，不应被门控降级", plan.noGuessFallback)
        assertTrue("应尝试超过门控上限的次数（证明未门控），实际 nowCalls=$nowCalls", nowCalls > GameEngine.GATE_MAX_ATTEMPTS)
        for (dr in -1..1) {
            for (dc in -1..1) {
                assertFalse("首点 3×3 不应有雷", plan.mines[(25 + dr) * 50 + (25 + dc)])
            }
        }
    }

    @Test
    fun `planMines 假时钟预算耗尽后提前降级`() {
        var nowCalls = 0
        val plan = GameEngine.planMines(
            20, 20, 100, 10, 10, true,
            now = {
                nowCalls++
                if (nowCalls == 1) 0L else 1_000_000_000L // 第二次起返回 1000ms > 800ms 预算
            },
            random = kotlin.random.Random(5)
        )
        assertEquals("首试失败后应立即降级", 2, nowCalls)
        assertTrue(plan.noGuessFallback)
    }

    @Test
    fun `planMines 无猜高级局能在预算内生成可解雷局`() {
        val plan = GameEngine.planMines(16, 30, 99, 8, 15, true)
        assertEquals(99, plan.mines.count { it })
        assertFalse("高级局应能在预算内找到可解局", plan.noGuessFallback)
    }

    // ---------- applyPlan / applyPlanAndReveal 校验（M3） ----------

    @Test
    fun `applyPlan 合法计划成功并计算 3BV`() {
        val g = GameEngine("beginner")
        val plan = beginnerPlan()
        assertTrue(g.applyPlan(plan))
        assertTrue(g.minesPlaced)
        assertEquals(10, countMines(g))
        assertTrue(g.bbbv > 0)
        for (dr in -1..1) {
            for (dc in -1..1) {
                assertFalse(g.board[4 + dr][4 + dc].mine)
            }
        }
    }

    @Test
    fun `applyPlan 拒绝几何不匹配`() {
        val g = GameEngine("beginner")
        val plan = GameEngine.planMines(9, 16, 10, 4, 4, false)
        assertFalse(g.applyPlan(plan))
        assertFalse(g.minesPlaced)
    }

    @Test
    fun `applyPlan 拒绝长度不匹配`() {
        val g = GameEngine("beginner")
        assertFalse(g.applyPlan(GameEngine.MinePlan(9, 9, 4, 4, BooleanArray(3), false)))
        assertFalse(g.minesPlaced)
    }

    @Test
    fun `applyPlan 拒绝雷数不匹配`() {
        val g = GameEngine("beginner")
        val base = beginnerPlan()
        val wrongCount = GameEngine.MinePlan(9, 9, 4, 4, BooleanArray(81) { it < 9 }, false)
        assertFalse(g.applyPlan(wrongCount))
        // 长度/安全区都对、仅雷数少一个（把任一真雷改成 false）
        val oneLessMines = base.mines.copyOf()
        oneLessMines[oneLessMines.indexOfFirst { it }] = false
        assertFalse(g.applyPlan(GameEngine.MinePlan(9, 9, 4, 4, oneLessMines, false)))
        assertFalse(g.minesPlaced)
    }

    @Test
    fun `applyPlan 拒绝安全区内有雷或安全区越界`() {
        val g = GameEngine("beginner")
        // 雷数 10 且都在安全区外，但把 (4,4) 换成雷并撤掉 (0,0)
        val safeMine = BooleanArray(81) { it < 10 }
        safeMine[40] = true // (4,4) 在 3×3 安全区内
        safeMine[0] = false // 保持 10 个雷
        assertFalse(g.applyPlan(GameEngine.MinePlan(9, 9, 4, 4, safeMine, false)))
        // safeRow 越界
        val base = beginnerPlan()
        assertFalse(g.applyPlan(GameEngine.MinePlan(9, 9, 9, 4, base.mines.copyOf(), false)))
        assertFalse(g.minesPlaced)
    }

    @Test
    fun `applyPlan 失败时不修改棋盘与状态`() {
        val g = GameEngine("beginner")
        assertFalse(g.applyPlan(GameEngine.MinePlan(9, 9, 4, 4, BooleanArray(3), false)))
        assertFalse(g.minesPlaced)
        assertEquals("ready", g.state)
        assertEquals(10, g.totalMines)
        assertTrue(g.board.all { row -> row.none { it.mine } })
    }

    @Test
    fun `applyPlan 幂等：同局重复应用被忽略并返回 false`() {
        val g = GameEngine("beginner")
        val plan = beginnerPlan()
        assertTrue(g.applyPlan(plan))
        val firstMine = g.board[0][0].mine
        val second = GameEngine.planMines(9, 9, 10, 0, 0, false)
        assertFalse(g.applyPlan(second))
        assertEquals(firstMine, g.board[0][0].mine)
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
    fun `applyPlanAndReveal 拒绝计划与首点不匹配（防止首点踩雷）`() {
        val g = GameEngine("beginner")
        // 计划为 (4,4) 生成，却应用到 (0,0)
        val plan = GameEngine.planMines(9, 9, 10, 4, 4, false)
        assertFalse(g.applyPlanAndReveal(0, 0, plan))
        assertFalse(g.minesPlaced)
        assertEquals("ready", g.state)
    }

    @Test
    fun `applyPlanAndReveal 拒绝几何不匹配`() {
        val g = GameEngine("beginner")
        val plan = GameEngine.planMines(9, 16, 10, 4, 4, false)
        assertFalse(g.applyPlanAndReveal(4, 4, plan))
        assertFalse(g.minesPlaced)
    }

    @Test
    fun `applyPlanAndReveal 失败分支：已布雷、终局、越界、已翻开、已插旗`() {
        // 已布雷
        val placed = GameEngine("beginner")
        placed.reveal(4, 4)
        assertFalse(placed.applyPlanAndReveal(4, 4, beginnerPlan()))

        // 终局
        val lost = setupEngine("beginner", listOf(0 to 0))
        lost.reveal(0, 0)
        assertEquals("lost", lost.state)
        assertFalse(lost.applyPlanAndReveal(4, 4, beginnerPlan(4, 4)))

        // 越界
        val fresh = GameEngine("beginner")
        assertFalse(fresh.applyPlanAndReveal(9, 9, beginnerPlan(0, 0)))

        // 已翻开（未布雷时手动置位，模拟并发/异常状态）
        val revealedCell = GameEngine("beginner")
        revealedCell.board[4][4].revealed = true
        assertFalse(revealedCell.applyPlanAndReveal(4, 4, beginnerPlan()))

        // 已插旗
        val flaggedCell = GameEngine("beginner")
        flaggedCell.toggleFlag(4, 4)
        assertFalse(flaggedCell.applyPlanAndReveal(4, 4, beginnerPlan()))
        assertFalse(flaggedCell.minesPlaced)
    }

    // ---------- 0 雷盘（M8）与胜利判定 ----------

    @Test
    fun `0 雷盘：未布雷不判胜、插旗两次不判胜、首次翻开即胜`() {
        val g = GameEngine()
        g.initCustom(9, 9, 0)
        assertFalse("未布雷时 checkWin 必须为 false", g.checkWin())
        g.toggleFlag(4, 4) // 旗
        assertEquals("playing", g.state)
        g.toggleFlag(4, 4) // 旗→问号，flagCount 归 0
        assertFalse("0 雷盘不能靠插旗判胜", g.checkWin())
        assertEquals("playing", g.state)
        g.reveal(4, 4)
        assertEquals("won", g.state)
        assertFalse("0 雷盘靠翻开取胜，不是旗胜", g.wonByFlags)
    }

    @Test
    fun `checkWin 在未布雷时恒为 false`() {
        for (level in listOf("beginner", "intermediate", "expert")) {
            val g = GameEngine(level)
            assertFalse(level, g.checkWin())
        }
    }

    // ---------- 快照 Snapshot ----------

    @Test
    fun `snapshot 与 restore 往返一致`() {
        val g = GameEngine("beginner")
        g.reveal(4, 4)
        g.toggleFlag(0, 0)
        g.timer = 12
        val snap = g.snapshot()

        val restored = GameEngine("expert") // 先初始化成别的难度，验证 restore 会完整覆盖
        restored.noGuess = true
        restored.restore(snap)

        assertEquals(g.rows, restored.rows)
        assertEquals(g.cols, restored.cols)
        assertEquals(g.totalMines, restored.totalMines)
        assertEquals(g.currentLevel, restored.currentLevel)
        assertEquals(g.state, restored.state)
        assertEquals(g.minesPlaced, restored.minesPlaced)
        assertEquals(g.timer, restored.timer)
        assertEquals(g.flagCount, restored.flagCount)
        assertEquals(g.revealedCount, restored.revealedCount)
        assertEquals(g.bbbv, restored.bbbv)
        assertEquals(g.noGuessFallback, restored.noGuessFallback)
        assertEquals(g.wonByFlags, restored.wonByFlags)
        assertEquals("noGuess 是 UI 偏好，不应被快照覆盖", true, restored.noGuess)
        for (r in 0 until g.rows) {
            for (c in 0 until g.cols) {
                val a = g.board[r][c]
                val b = restored.board[r][c]
                assertEquals("($r,$c).mine", a.mine, b.mine)
                assertEquals("($r,$c).adjacentMines", a.adjacentMines, b.adjacentMines)
                assertEquals("($r,$c).revealed", a.revealed, b.revealed)
                assertEquals("($r,$c).flagged", a.flagged, b.flagged)
                assertEquals("($r,$c).questioned", a.questioned, b.questioned)
                assertEquals("($r,$c).exploded", a.exploded, b.exploded)
                assertEquals("($r,$c).wrongFlag", a.wrongFlag, b.wrongFlag)
            }
        }
    }

    @Test
    fun `restore 后可继续操作且与未恢复的引擎一致`() {
        val g = GameEngine("beginner")
        g.reveal(4, 4)
        val snap = g.snapshot()
        val restored = GameEngine("beginner")
        restored.restore(snap)

        var target: Pair<Int, Int>? = null
        outer@ for (r in 0 until g.rows) {
            for (c in 0 until g.cols) {
                if (!g.board[r][c].revealed && !g.board[r][c].flagged) {
                    target = r to c
                    break@outer
                }
            }
        }
        val cell = target ?: throw AssertionError("应存在未翻开的格子")
        g.reveal(cell.first, cell.second)
        restored.reveal(cell.first, cell.second)
        assertEquals(g.state, restored.state)
        assertEquals(g.revealedCount, restored.revealedCount)
        assertEquals(g.flagCount, restored.flagCount)
        for (r in 0 until g.rows) {
            for (c in 0 until g.cols) {
                assertEquals(g.board[r][c].revealed, restored.board[r][c].revealed)
            }
        }
    }

    @Test
    fun `restore 拒绝非法快照`() {
        val g = GameEngine("beginner")
        g.reveal(4, 4)
        val snap = g.snapshot()
        assertThrows(IllegalArgumentException::class.java) {
            g.restore(snap.copy(mines = BooleanArray(3)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            g.restore(snap.copy(rows = 16, cols = 16))
        }
        assertThrows(IllegalArgumentException::class.java) {
            g.restore(snap.copy(totalMines = 999))
        }
        assertThrows(IllegalArgumentException::class.java) {
            g.restore(snap.copy(currentLevel = "nope"))
        }
    }

    @Test
    fun `snapshot 返回副本，修改快照不影响引擎`() {
        val g = GameEngine("beginner")
        g.reveal(4, 4)
        val snap = g.snapshot()
        val wasMine = snap.mines[0]
        snap.mines[0] = !wasMine
        snap.revealed.fill(false)
        assertEquals(wasMine, g.board[0][0].mine)
        assertTrue("引擎已翻开的格子不应被快照数组影响", g.revealedCount > 0)
        assertTrue(g.board[4][4].revealed)
    }
}
