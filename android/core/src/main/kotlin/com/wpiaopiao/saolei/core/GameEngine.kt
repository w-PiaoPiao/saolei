package com.wpiaopiao.saolei.core

/**
 * 扫雷游戏引擎：由桌面版 src/game.js 逐方法移植，纯 JVM 无 Android 依赖。
 * 行为保持一致：首次点击安全（3×3 无雷）、迭代洪水填充、标旗→问号→取消循环、
 * 和弦（旗数匹配才展开）、脏格标记、状态监听。
 */
class GameEngine(level: String = "beginner") {

    data class Level(val label: String, val rows: Int, val cols: Int, val mines: Int)
    data class CustomConfig(val rows: Int, val cols: Int, val mines: Int)

    /**
     * 雷位规划结果：由 [planMines] 在后台线程算出，再由 [applyPlan] 在 UI 线程应用。
     * mines 为按行主序的雷位标记（长度 rows*cols）。
     */
    class MinePlan(val mines: BooleanArray, val noGuessFallback: Boolean)

    companion object {
        val LEVELS: Map<String, Level> = mapOf(
            "beginner" to Level("初级", 9, 9, 10),
            "intermediate" to Level("中级", 16, 16, 40),
            "expert" to Level("高级", 16, 30, 99)
        )

        // 无猜模式：重试上限与时间预算（与桌面版 src/config.js 的 NO_GUESS 对齐）。
        // 生成在后台线程执行、界面不冻结，故预算放宽以换取更高成功率。
        const val NO_GUESS_MAX_ATTEMPTS = 600
        const val NO_GUESS_BUDGET_MS = 800L

        /** 预算内预估总尝试次数低于此值时提前降级（与桌面 generator.js 的 MIN_EXPECTED_ATTEMPTS 对齐） */
        const val MIN_EXPECTED_ATTEMPTS = 20

        /** 安全区（首点 3×3）之外随机撒雷（Fisher-Yates），结果写入 [mines] */
        private fun randomize(
            mines: BooleanArray,
            rows: Int, cols: Int, totalMines: Int,
            safeRow: Int, safeCol: Int
        ) {
            mines.fill(false)
            val candidates = ArrayList<Int>(rows * cols)
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    if (Math.abs(r - safeRow) > 1 || Math.abs(c - safeCol) > 1) {
                        candidates.add(r * cols + c)
                    }
                }
            }
            if (candidates.size < totalMines) {
                throw IllegalStateException("雷数超过可用格子数量")
            }
            for (i in candidates.size - 1 downTo 1) {
                val j = kotlin.random.Random.nextInt(i + 1)
                val t = candidates[i]
                candidates[i] = candidates[j]
                candidates[j] = t
            }
            for (k in 0 until totalMines) mines[candidates[k]] = true
        }

        /** 把雷位写入求解器用的临时棋盘并算好相邻雷数（只依赖 mine/adjacentMines） */
        private fun fillGrid(grid: Array<Array<Cell>>, mines: BooleanArray, rows: Int, cols: Int) {
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val cell = grid[r][c]
                    cell.mine = mines[r * cols + c]
                    cell.adjacentMines = 0
                }
            }
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    if (grid[r][c].mine) continue
                    var count = 0
                    for (dr in -1..1) {
                        for (dc in -1..1) {
                            if (dr == 0 && dc == 0) continue
                            val nr = r + dr
                            val nc = c + dc
                            if (nr in 0 until rows && nc in 0 until cols && grid[nr][nc].mine) count++
                        }
                    }
                    grid[r][c].adjacentMines = count
                }
            }
        }

        /**
         * 纯计算：规划一局雷位，不修改任何 [GameEngine] 实例状态，因此可在后台线程调用，
         * 结果经 [applyPlan] 交回 UI 线程。与桌面版 src/generator.js 逐语义对齐。
         *
         * 无猜模式下反复重生成直到求解器判定可纯逻辑通关；受尝试上限与时间预算双重约束，
         * 并在「按当前单次耗时预估、预算内总量不足以期待命中」时提前降级，避免大棋盘白等。
         */
        fun planMines(
            rows: Int, cols: Int, totalMines: Int,
            safeRow: Int, safeCol: Int, noGuess: Boolean
        ): MinePlan {
            require(rows >= 1 && cols >= 1) { "行列数必须为正整数" }
            require(totalMines >= 0) { "雷数不能为负" }
            val mines = BooleanArray(rows * cols)
            if (!noGuess) {
                randomize(mines, rows, cols, totalMines, safeRow, safeCol)
                return MinePlan(mines, false)
            }
            // 每次调用自建临时棋盘（不复用静态字段，保证可安全地在后台线程调用）
            val grid = Array(rows) { Array(cols) { Cell() } }
            val start = System.nanoTime()
            for (attempt in 0 until NO_GUESS_MAX_ATTEMPTS) {
                randomize(mines, rows, cols, totalMines, safeRow, safeCol)
                fillGrid(grid, mines, rows, cols)
                if (Solver.solve(grid, rows, cols, totalMines, safeRow, safeCol)) {
                    return MinePlan(mines, false)
                }
                val elapsedMs = (System.nanoTime() - start) / 1_000_000L
                if (elapsedMs <= 0) continue // 时钟精度不足：只受尝试上限约束
                val avgCostMs = elapsedMs.toDouble() / (attempt + 1)
                if (NO_GUESS_BUDGET_MS / avgCostMs < MIN_EXPECTED_ATTEMPTS) break
                if (elapsedMs >= NO_GUESS_BUDGET_MS) break
            }
            return MinePlan(mines, true)
        }
    }

    var currentLevel: String = "beginner"
        private set
    var currentCustom: CustomConfig? = null
        private set

    var rows: Int = 0
        private set
    var cols: Int = 0
        private set

    /** internal set：测试构造已知雷局时需要直接设置（对齐 JS 版测试） */
    var totalMines: Int = 0
        internal set

    var state: String = "ready"
        private set

    /** internal set：测试构造已知雷局时需要直接设置（对齐 JS 版测试） */
    var minesPlaced: Boolean = false
        internal set

    /** 无猜模式：布雷后校验可纯逻辑通关，不通过则重新生成（由 UI 开关控制） */
    var noGuess: Boolean = false

    /** 本局 3BV（理论最少点击数），布雷后计算；新局重置为 0 */
    var bbbv: Int = 0
        private set

    /** 无猜生成是否降级为普通雷局（尝试次数/时间预算内未找到可解局时置位） */
    var noGuessFallback: Boolean = false
        private set

    var timer: Int = 0
    var flagCount: Int = 0
        private set
    var revealedCount: Int = 0
        private set

    lateinit var board: Array<Array<Cell>>
        private set

    /** 脏格标记（增量渲染用），与桌面版一致：init/布雷/操作后记录变更格子。 */
    val dirty = mutableSetOf<Pair<Int, Int>>()

    private val stateListeners = mutableListOf<(String) -> Unit>()

    init {
        init(level)
    }

    // ---------- 状态与变更通知 ----------

    fun onStateChange(fn: (String) -> Unit) {
        stateListeners.add(fn)
    }

    private fun setState(newState: String) {
        if (state == newState) return
        state = newState
        stateListeners.forEach { it(newState) }
    }

    // ---------- 脏标记 ----------

    fun markDirty(row: Int, col: Int) {
        dirty.add(row to col)
    }

    fun markAllDirty() {
        for (r in 0 until rows) for (c in 0 until cols) dirty.add(r to c)
    }

    // ---------- 初始化 ----------

    fun init(level: String) {
        val cfg = LEVELS[level] ?: throw IllegalArgumentException("未知难度: $level")
        currentLevel = level
        currentCustom = null
        rows = cfg.rows
        cols = cfg.cols
        totalMines = cfg.mines
        resetRound()
    }

    fun initCustom(rows: Int, cols: Int, mines: Int) {
        // 非法配置在构造时就拒绝，避免把异常延迟到「第一次点击」才抛
        require(rows >= 1 && cols >= 1) { "行列数必须为正整数" }
        require(mines >= 0) { "雷数不能为负" }
        // 首点 3×3 安全区不布雷，故最坏（首点居中）时可用格为 rows*cols-9。
        // 用 Long 计算，避免超大行列数下的 Int 溢出让校验形同虚设
        val maxMines = (rows.toLong() * cols - 9).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        require(mines <= maxMines) { "雷数超过可用格子数量（最多 $maxMines）" }
        currentLevel = "custom"
        currentCustom = CustomConfig(rows, cols, mines)
        this.rows = rows
        this.cols = cols
        this.totalMines = mines
        resetRound()
    }

    private fun resetRound() {
        state = "ready"
        minesPlaced = false
        timer = 0
        flagCount = 0
        revealedCount = 0
        bbbv = 0
        noGuessFallback = false
        board = createBoard()
        dirty.clear()
        markAllDirty()
    }

    private fun createBoard(): Array<Array<Cell>> =
        Array(rows) { Array(cols) { Cell() } }

    // ---------- 布雷 ----------

    /**
     * 在安全格首次翻开后布雷（同步路径：普通模式与单元测试走这里）。
     * 无猜模式生成耗时较长，UI 应优先走「后台 planMines + applyPlanAndReveal」异步路径。
     */
    fun placeMines(safeRow: Int, safeCol: Int) {
        applyPlan(planMines(rows, cols, totalMines, safeRow, safeCol, noGuess))
    }

    /** 应用（可能来自后台线程的）雷位规划。同局重复调用无效。 */
    fun applyPlan(plan: MinePlan) {
        if (minesPlaced) return
        noGuessFallback = plan.noGuessFallback
        applyMines(plan.mines)
        minesPlaced = true
        bbbv = compute3BV()
        markAllDirty()
    }

    /** 写入雷位并重算相邻雷数 */
    private fun applyMines(mines: BooleanArray) {
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val cell = board[r][c]
                cell.mine = mines[r * cols + c]
                cell.adjacentMines = 0
            }
        }
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (!board[r][c].mine) board[r][c].adjacentMines = countAdjacentMines(r, c)
            }
        }
    }

    /**
     * 3BV：完成本局所需的最少点击次数 —— 每个 0 区域算 1 次（洪泛展开会连带翻开其边界数字格），
     * 未被 0 区域覆盖的数字格各算 1 次。用于效率统计（3BV/s）。
     */
    fun compute3BV(): Int {
        val opened = BooleanArray(rows * cols)
        val stack = ArrayDeque<Int>()
        var bbbv = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val cell = board[r][c]
                val startIdx = r * cols + c
                if (cell.mine || cell.adjacentMines != 0 || opened[startIdx]) continue
                bbbv++
                stack.addLast(startIdx)
                while (stack.isNotEmpty()) {
                    val idx = stack.removeLast()
                    if (opened[idx]) continue
                    val cr = idx / cols
                    val cc = idx % cols
                    val cur = board[cr][cc]
                    if (cur.mine) continue
                    opened[idx] = true
                    if (cur.adjacentMines == 0) {
                        for (dr in -1..1) {
                            for (dc in -1..1) {
                                if (dr == 0 && dc == 0) continue
                                val nr = cr + dr
                                val nc = cc + dc
                                if (nr in 0 until rows && nc in 0 until cols && !opened[nr * cols + nc]) {
                                    stack.addLast(nr * cols + nc)
                                }
                            }
                        }
                    }
                }
            }
        }
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val cell = board[r][c]
                if (!cell.mine && cell.adjacentMines > 0 && !opened[r * cols + c]) bbbv++
            }
        }
        return bbbv
    }

    fun countAdjacentMines(row: Int, col: Int): Int {
        var count = 0
        for (dr in -1..1) {
            for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                val r = row + dr
                val c = col + dc
                if (r in 0 until rows && c in 0 until cols && board[r][c].mine) count++
            }
        }
        return count
    }

    // ---------- 操作 ----------

    fun reveal(row: Int, col: Int) {
        if (state == "won" || state == "lost") return
        if (row !in 0 until rows || col !in 0 until cols) return
        val cell = board[row][col]
        if (cell.revealed || cell.flagged) return
        if (!minesPlaced) {
            placeMines(row, col)
            setState("playing")
        }
        finishReveal(row, col)
    }

    /**
     * 异步路径入口：雷位已由后台线程生成，这里只做「应用 + 翻开」。
     * 生成期间局面可能变化，故对所有前置条件做竞态检查，任一不满足即放弃。
     * @return 是否成功应用并翻开
     */
    fun applyPlanAndReveal(row: Int, col: Int, plan: MinePlan): Boolean {
        if (minesPlaced) return false
        if (state == "won" || state == "lost") return false
        if (row !in 0 until rows || col !in 0 until cols) return false
        val cell = board[row][col]
        if (cell.revealed || cell.flagged) return false
        applyPlan(plan)
        setState("playing")
        finishReveal(row, col)
        return true
    }

    /** 翻开本格并处理踩雷/胜负（调用方已完成全部前置守卫） */
    private fun finishReveal(row: Int, col: Int) {
        val cell = board[row][col]
        if (cell.mine) {
            cell.revealed = true
            cell.exploded = true
            setState("lost")
            revealAllMines()
            return
        }
        floodFill(row, col)
        if (checkWin()) setState("won")
    }

    /** 迭代式洪水填充（显式栈，避免深递归） */
    fun floodFill(row: Int, col: Int) {
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(row to col)
        while (stack.isNotEmpty()) {
            val (r, c) = stack.removeLast()
            val cell = board[r][c]
            if (cell.revealed || cell.flagged || cell.mine) continue
            cell.revealed = true
            // 问号格不在上面的跳过条件里，会被洪泛翻开并清除问号标记
            cell.questioned = false
            revealedCount++
            markDirty(r, c)
            if (cell.adjacentMines == 0) {
                for (dr in -1..1) {
                    for (dc in -1..1) {
                        if (dr == 0 && dc == 0) continue
                        val nr = r + dr
                        val nc = c + dc
                        if (nr in 0 until rows && nc in 0 until cols) {
                            stack.addLast(nr to nc)
                        }
                    }
                }
            }
        }
    }

    fun toggleFlag(row: Int, col: Int) {
        if (state == "won" || state == "lost") return
        if (row !in 0 until rows || col !in 0 until cols) return
        val cell = board[row][col]
        if (cell.revealed) return
        if (state == "ready") setState("playing")
        when {
            !cell.flagged && !cell.questioned -> {
                cell.flagged = true
                flagCount++
            }
            cell.flagged -> {
                cell.flagged = false
                cell.questioned = true
                flagCount--
            }
            else -> cell.questioned = false
        }
        markDirty(row, col)
        if (checkWin()) setState("won")
    }

    /** 和弦：已翻开数字格周围旗数匹配时展开周围未翻开格。返回是否执行了展开。 */
    fun chord(row: Int, col: Int): Boolean {
        // 终局守卫：终局后和弦不应产生任何效果（与桌面版一致）
        if (state == "won" || state == "lost") return false
        if (row !in 0 until rows || col !in 0 until cols) return false
        val cell = board[row][col]
        if (!cell.revealed || cell.adjacentMines == 0) return false
        var adjFlags = 0
        for (dr in -1..1) {
            for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                val r = row + dr
                val c = col + dc
                if (r in 0 until rows && c in 0 until cols && board[r][c].flagged) adjFlags++
            }
        }
        if (adjFlags != cell.adjacentMines) return false
        for (dr in -1..1) {
            for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                val r = row + dr
                val c = col + dc
                if (r in 0 until rows && c in 0 until cols && !board[r][c].revealed) {
                    reveal(r, c)
                    if (state == "lost") return true
                }
            }
        }
        if (checkWin()) setState("won")
        return true
    }

    // ---------- 终局辅助 ----------

    fun revealAllMines() {
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val cell = board[r][c]
                if (cell.mine) {
                    cell.revealed = true
                } else if (cell.flagged) {
                    // 非雷格上的旗：标记错误旗，UI 绘制红叉
                    cell.wrongFlag = true
                }
            }
        }
        markAllDirty()
    }

    fun revealAllSafe() {
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val cell = board[r][c]
                if (!cell.mine && !cell.revealed) {
                    cell.revealed = true
                    revealedCount++
                }
            }
        }
        markAllDirty()
    }

    fun autoFlagRemainingMines() {
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val cell = board[r][c]
                if (cell.mine && !cell.flagged) {
                    cell.flagged = true
                    flagCount++
                }
            }
        }
        markAllDirty()
    }

    fun checkWin(): Boolean {
        val totalSafe = rows * cols - totalMines
        if (revealedCount >= totalSafe) return true
        if (flagCount == totalMines) {
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    if (board[r][c].flagged != board[r][c].mine) return false
                }
            }
            return true
        }
        return false
    }
}
