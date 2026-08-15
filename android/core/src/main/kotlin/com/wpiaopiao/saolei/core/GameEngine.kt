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
     * - Fisher-Yates 洗牌，O(n) 且无拒绝采样退化
     */
    fun placeMines(safeRow: Int, safeCol: Int) {
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
        minesPlaced = true
        markAllDirty()
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
                if (board[r][c].mine) board[r][c].revealed = true
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
