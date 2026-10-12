package com.wpiaopiao.saolei.core

/**
 * 扫雷游戏引擎：由桌面版 src/game.js 逐方法移植，纯 JVM 无 Android 依赖。
 * 行为保持一致：首次点击安全（3×3 无雷）、迭代洪水填充、标旗→问号→取消循环、
 * 和弦（旗数匹配才展开）、脏格标记、状态监听。
 */
class GameEngine(level: String = "beginner") {

    data class Level(val label: String, val rows: Int, val cols: Int, val mines: Int)
    data class CustomConfig(val rows: Int, val cols: Int, val mines: Int)

    companion object {
        val LEVELS: Map<String, Level> = mapOf(
            "beginner" to Level("初级", 9, 9, 10),
            "intermediate" to Level("中级", 16, 16, 40),
            "expert" to Level("高级", 16, 30, 99)
        )

        // 无猜模式：重试上限与时间预算（与桌面版 src/config.js 的 NO_GUESS 对齐）
        const val NO_GUESS_MAX_ATTEMPTS = 600
        const val NO_GUESS_BUDGET_MS = 500L
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
     * 在安全格首次翻开后布雷：
     * - 安全格周围 3×3 永不布雷（保证首次点击绝对安全）
     * - 普通模式 Fisher-Yates 洗牌，O(n) 且无拒绝采样退化
     * - 无猜模式反复重生成，直到求解器判定可纯逻辑通关
     */
    fun placeMines(safeRow: Int, safeCol: Int) {
        if (noGuess) {
            placeMinesNoGuess(safeRow, safeCol)
        } else {
            randomizeMines(safeRow, safeCol)
            noGuessFallback = false
        }
        minesPlaced = true
        bbbv = compute3BV()
        markAllDirty()
    }

    /** 随机布雷（先清空上一轮结果，供无猜重试复用同一张棋盘） */
    private fun randomizeMines(safeRow: Int, safeCol: Int) {
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                board[r][c].mine = false
                board[r][c].adjacentMines = 0
            }
        }
        val candidates = mutableListOf<Int>()
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
        for (k in 0 until totalMines) {
            val idx = candidates[k]
            board[idx / cols][idx % cols].mine = true
        }
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (!board[r][c].mine) {
                    board[r][c].adjacentMines = countAdjacentMines(r, c)
                }
            }
        }
    }

    /**
     * 无猜布雷：重生成直到求解器判定可解。
     * 受尝试次数与时间预算双重限制，预算内未找到时沿用最后一次生成的雷局，
     * 并置 [noGuessFallback] 供 UI 提示。
     */
    private fun placeMinesNoGuess(safeRow: Int, safeCol: Int): Boolean {
        val deadline = System.nanoTime() + NO_GUESS_BUDGET_MS * 1_000_000L
        for (attempt in 0 until NO_GUESS_MAX_ATTEMPTS) {
            randomizeMines(safeRow, safeCol)
            if (Solver.solve(board, rows, cols, totalMines, safeRow, safeCol)) {
                noGuessFallback = false
                return true
            }
            if (System.nanoTime() >= deadline) break
        }
        noGuessFallback = true
        return false
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
        val cell = board[row][col]
        if (cell.revealed || cell.flagged) return
        if (!minesPlaced) {
            placeMines(row, col)
            setState("playing")
        }
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
            cell.flagged = false
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
