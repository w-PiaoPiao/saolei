package com.wpiaopiao.saolei.core

/**
 * 扫雷游戏引擎：由桌面版 src/game.js 逐方法移植，纯 JVM 无 Android 依赖。
 * 行为保持一致：首次点击安全（3×3 无雷）、迭代洪水填充、标旗→问号→取消循环、
 * 和弦（旗数匹配才展开）、脏格标记、状态变更、存档快照。
 *
 * 线程模型：本类所有实例方法都应在 UI 线程调用；唯一可跨线程调用的是
 * [Companion.planMines]（纯函数，不读写任何实例状态）与 [Solver.solve]。
 */
class GameEngine(level: String = "beginner") {

    data class Level(val label: String, val rows: Int, val cols: Int, val mines: Int)
    data class CustomConfig(val rows: Int, val cols: Int, val mines: Int)

    /**
     * 雷位规划结果：由 [planMines] 在后台线程算出，再由 [applyPlan] 在 UI 线程应用。
     * 自 v1.3 起携带几何与首点信息，[applyPlan] / [applyPlanAndReveal] 会据此校验，
     * 避免把「为 A 点生成的计划」误用到 B 点或不同尺寸的棋盘。
     *
     * @param rows 生成时的行数
     * @param cols 生成时的列数
     * @param safeRow 生成时保证安全的 3×3 安全区中心行
     * @param safeCol 生成时保证安全的 3×3 安全区中心列
     * @param mines 按行主序的雷位标记（长度 rows*cols）
     * @param noGuessFallback 无猜生成是否降级为普通雷局
     */
    class MinePlan(
        val rows: Int,
        val cols: Int,
        val safeRow: Int,
        val safeCol: Int,
        val mines: BooleanArray,
        val noGuessFallback: Boolean
    )

    /**
     * 对局快照：供 app 在 `onSaveInstanceState` / `onPause` 存档，[restore] 恢复。
     * 所有棋盘字段均为行主序、长度 `rows*cols`。
     */
    data class Snapshot(
        val rows: Int,
        val cols: Int,
        val totalMines: Int,
        val currentLevel: String,
        val currentCustom: CustomConfig?,
        val state: String,
        val minesPlaced: Boolean,
        val timer: Int,
        val flagCount: Int,
        val revealedCount: Int,
        val bbbv: Int,
        val noGuessFallback: Boolean,
        val wonByFlags: Boolean,
        val mines: BooleanArray,
        val revealed: BooleanArray,
        val flagged: BooleanArray,
        val questioned: BooleanArray,
        val exploded: BooleanArray,
        val wrongFlag: BooleanArray
    )

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

        /**
         * 保留常量：桌面 generator.js 仍用它做在线估算。
         * 安卓自 v1.3（M1）起改用下方的确定性门控，本常量不再参与判定，
         * 仅用于跨端常量对照（ParityTest）与历史调用方编译兼容。
         */
        const val MIN_EXPECTED_ATTEMPTS = 20

        /** 引擎允许的最大棋盘格数（app 自定义上限 50×50=2500，留足余量） */
        const val MAX_CELLS = 10_000

        /**
         * M1 确定性门控（与桌面 `src/config.js` 的 `NO_GUESS.gate` 完全一致）：
         * 只有**同时**满足 `cells >= HOPELESS_MIN_CELLS` 且 `density >= HOPELESS_DENSITY`
         * 的配置才被视为"无解率极高"，最多尝试 [GATE_MAX_ATTEMPTS] 次即降级；
         * 其余配置给足 [NO_GUESS_BUDGET_MS] / [NO_GUESS_MAX_ATTEMPTS]（成功优先）。
         */
        const val HOPELESS_MIN_CELLS = 900

        /** 高密度门控阈值（须与 [HOPELESS_MIN_CELLS] 同时满足才生效） */
        const val HOPELESS_DENSITY = 0.25

        /** 确定性门控命中后允许的最大尝试次数（M1，对应桌面 gateAttempts） */
        const val GATE_MAX_ATTEMPTS = 2

        /** 首点 3×3 安全区所占的最多格数（用于反推雷数上限） */
        private const val SAFE_ZONE_CELLS = 9

        private fun checkBoardSize(rows: Int, cols: Int) {
            require(rows >= 1 && cols >= 1) { "行列数必须为正整数" }
            val cells = rows.toLong() * cols
            require(cells <= Int.MAX_VALUE) { "棋盘过大：rows*cols 超出 Int 范围" }
            require(cells <= MAX_CELLS) { "棋盘过大：最多 $MAX_CELLS 格" }
        }

        /** 与 initCustom 同口径的雷数上限（最坏情况：首点在棋盘内部，安全区占 9 格） */
        private fun maxMinesFor(rows: Int, cols: Int): Int =
            (rows.toLong() * cols - SAFE_ZONE_CELLS).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

        /** 安全区（首点 3×3）之外随机撒雷（Fisher-Yates），结果写入 [mines] */
        private fun randomize(
            mines: BooleanArray,
            rows: Int, cols: Int, totalMines: Int,
            safeRow: Int, safeCol: Int,
            random: kotlin.random.Random
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
                throw IllegalArgumentException("雷数超过可用格子数量")
            }
            for (i in candidates.size - 1 downTo 1) {
                val j = random.nextInt(i + 1)
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
                    Neighbors.forEachNeighbor(rows, cols, r, c) { nr, nc ->
                        if (grid[nr][nc].mine) count++
                    }
                    grid[r][c].adjacentMines = count
                }
            }
        }

        /**
         * 纯计算：规划一局雷位，不修改任何 [GameEngine] 实例状态，因此可在后台线程调用，
         * 结果经 [applyPlan] 交回 UI 线程。
         *
         * 普通模式：Fisher-Yates 洗牌（O(n)，无拒绝采样退化）。
         * 无猜模式：反复重生成直到 [Solver] 判定可纯逻辑通关；采用 M1 的**确定性门控**
         * （与桌面 `generator.js` 一致）：仅当棋盘 ≥ [HOPELESS_MIN_CELLS] 格**且**雷密度
         * ≥ [HOPELESS_DENSITY] 时只尝试 [GATE_MAX_ATTEMPTS] 次即降级，避免注定失败的配置
         * 白等预算；其余配置（含 50×50/500 这类大盘低密度）在 [maxAttempts] / [budgetMs]
         * 双约束内尽量找到可解局（成功优先）。
         *
         * @param now 时间源（纳秒），默认 [System.nanoTime]；测试可注入假时钟
         * @param random 随机源，默认 [kotlin.random.Random.Default]；测试可注入固定种子
         * @param budgetMs 无猜时间预算（毫秒）
         * @param maxAttempts 无猜最大尝试次数
         * @throws IllegalArgumentException 参数不合法（坐标越界/雷数超限/棋盘过大/预算非正）
         */
        fun planMines(
            rows: Int, cols: Int, totalMines: Int,
            safeRow: Int, safeCol: Int, noGuess: Boolean,
            now: () -> Long = System::nanoTime,
            random: kotlin.random.Random = kotlin.random.Random.Default,
            budgetMs: Long = NO_GUESS_BUDGET_MS,
            maxAttempts: Int = NO_GUESS_MAX_ATTEMPTS
        ): MinePlan {
            checkBoardSize(rows, cols)
            require(safeRow in 0 until rows) { "首点行越界: $safeRow（应在 0 until $rows）" }
            require(safeCol in 0 until cols) { "首点列越界: $safeCol（应在 0 until $cols）" }
            require(totalMines >= 0) { "雷数不能为负" }
            require(totalMines <= maxMinesFor(rows, cols)) {
                "雷数超过可用格子数量（最多 ${maxMinesFor(rows, cols)}）"
            }
            require(budgetMs > 0) { "无猜时间预算必须为正数" }
            require(maxAttempts >= 1) { "无猜尝试次数必须为正数" }

            val cells = rows * cols
            val mines = BooleanArray(cells)
            if (!noGuess) {
                randomize(mines, rows, cols, totalMines, safeRow, safeCol, random)
                return MinePlan(rows, cols, safeRow, safeCol, mines, false)
            }

            // M1：按尺寸/密度做确定性预判；命中的配置不依赖在线耗时估算，行为可复现。
            val density = totalMines.toDouble() / cells
            // 与桌面一致：只有"大盘 + 高密度"同时命中才门控（两者缺一都保留成功机会）
            val gated = cells >= HOPELESS_MIN_CELLS && density >= HOPELESS_DENSITY
            val attemptLimit = if (gated) minOf(maxAttempts, GATE_MAX_ATTEMPTS) else maxAttempts

            // 每次调用自建临时棋盘（不复用静态字段，保证可安全地在后台线程调用）
            val grid = Array(rows) { Array(cols) { Cell() } }
            val start = now()
            for (attempt in 0 until attemptLimit) {
                randomize(mines, rows, cols, totalMines, safeRow, safeCol, random)
                fillGrid(grid, mines, rows, cols)
                if (Solver.solve(grid, rows, cols, totalMines, safeRow, safeCol)) {
                    return MinePlan(rows, cols, safeRow, safeCol, mines, false)
                }
                if (attempt + 1 >= attemptLimit) break
                // now() 返回纳秒：预算按毫秒声明，比较前统一换算
                if (now() - start >= budgetMs * 1_000_000L) break
            }
            return MinePlan(rows, cols, safeRow, safeCol, mines, true)
        }
    }

    // ---------- 状态 ----------

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

    /** 本局是否由「插对全部旗」触发胜利（用于统计标注；由 reveal/chord 触发时为 false） */
    var wonByFlags: Boolean = false
        private set

    var timer: Int = 0
    var flagCount: Int = 0
        private set
    var revealedCount: Int = 0
        private set

    lateinit var board: Array<Array<Cell>>
        private set

    /**
     * 脏格标记（增量渲染用）：元素为 `row * cols + col`。
     * 注意编码依赖当前的 [cols]，切换棋盘后请先消费再重开。
     */
    val dirty = mutableSetOf<Int>()

    init {
        init(level)
    }

    /**
     * 是否有待生成的雷局（即首点尚未布雷）。
     * UI 用它判断"无猜首点是否该走异步生成"，不要用 state == "ready"（插旗会提前改 state）。
     */
    fun hasPendingMines(): Boolean = !minesPlaced

    // ---------- 脏标记 ----------

    fun markDirty(row: Int, col: Int) {
        dirty.add(row * cols + col)
    }

    fun markAllDirty() {
        val cells = rows * cols
        for (i in 0 until cells) dirty.add(i)
    }

    /**
     * 返回本次累积的脏格索引副本并清空（推荐 UI 用这个，避免直接持有内部集合）。
     */
    fun consumeDirty(): IntArray {
        if (dirty.isEmpty()) return IntArray(0)
        val out = IntArray(dirty.size)
        var i = 0
        for (idx in dirty) out[i++] = idx
        dirty.clear()
        return out
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
        checkBoardSize(rows, cols)
        require(mines >= 0) { "雷数不能为负" }
        val maxMines = maxMinesFor(rows, cols)
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
        wonByFlags = false
        board = createBoard()
        dirty.clear()
        markAllDirty()
    }

    private fun createBoard(): Array<Array<Cell>> =
        Array(rows) { Array(cols) { Cell() } }

    // ---------- 存档快照 ----------

    /** 生成当前对局快照（不修改引擎状态）。 */
    fun snapshot(): Snapshot {
        val cells = rows * cols
        fun copy(select: (Cell) -> Boolean): BooleanArray =
            BooleanArray(cells) { i -> select(board[i / cols][i % cols]) }
        return Snapshot(
            rows = rows,
            cols = cols,
            totalMines = totalMines,
            currentLevel = currentLevel,
            currentCustom = currentCustom,
            state = state,
            minesPlaced = minesPlaced,
            timer = timer,
            flagCount = flagCount,
            revealedCount = revealedCount,
            bbbv = bbbv,
            noGuessFallback = noGuessFallback,
            wonByFlags = wonByFlags,
            mines = copy { it.mine },
            revealed = copy { it.revealed },
            flagged = copy { it.flagged },
            questioned = copy { it.questioned },
            exploded = copy { it.exploded },
            wrongFlag = copy { it.wrongFlag }
        )
    }

    /**
     * 从快照恢复对局。几何/难度/数组长度不合法时抛 [IllegalArgumentException]。
     * [noGuess] 属于 UI 偏好，不在快照内，调用方应在恢复前后自行设置。
     */
    fun restore(snapshot: Snapshot) {
        checkBoardSize(snapshot.rows, snapshot.cols)
        val cells = snapshot.rows * snapshot.cols
        require(snapshot.totalMines in 0..maxMinesFor(snapshot.rows, snapshot.cols)) {
            "快照雷数超出合法范围: ${snapshot.totalMines}"
        }
        val level = snapshot.currentLevel
        if (level == "custom") {
            val custom = snapshot.currentCustom
            require(custom != null) { "自定义对局快照缺少 currentCustom" }
            require(custom.rows == snapshot.rows && custom.cols == snapshot.cols && custom.mines == snapshot.totalMines) {
                "快照的 currentCustom 与棋盘几何不一致"
            }
        } else {
            val cfg = LEVELS[level] ?: throw IllegalArgumentException("快照中的未知难度: $level")
            require(cfg.rows == snapshot.rows && cfg.cols == snapshot.cols && cfg.mines == snapshot.totalMines) {
                "快照尺寸/雷数与难度 $level 不一致"
            }
        }
        for ((name, arr) in listOf(
            "mines" to snapshot.mines,
            "revealed" to snapshot.revealed,
            "flagged" to snapshot.flagged,
            "questioned" to snapshot.questioned,
            "exploded" to snapshot.exploded,
            "wrongFlag" to snapshot.wrongFlag
        )) {
            require(arr.size == cells) { "快照 $name 长度应为 $cells，实际 ${arr.size}" }
        }

        rows = snapshot.rows
        cols = snapshot.cols
        totalMines = snapshot.totalMines
        currentLevel = level
        currentCustom = snapshot.currentCustom
        board = createBoard()
        for (i in 0 until cells) {
            val cell = board[i / cols][i % cols]
            cell.mine = snapshot.mines[i]
            cell.revealed = snapshot.revealed[i]
            cell.flagged = snapshot.flagged[i]
            cell.questioned = snapshot.questioned[i]
            cell.exploded = snapshot.exploded[i]
            cell.wrongFlag = snapshot.wrongFlag[i]
            cell.adjacentMines = 0
        }
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (!board[r][c].mine) board[r][c].adjacentMines = countAdjacentMines(r, c)
            }
        }
        state = snapshot.state
        minesPlaced = snapshot.minesPlaced
        timer = snapshot.timer
        flagCount = snapshot.flagCount
        revealedCount = snapshot.revealedCount
        bbbv = snapshot.bbbv
        noGuessFallback = snapshot.noGuessFallback
        wonByFlags = snapshot.wonByFlags
        dirty.clear()
        markAllDirty()
    }

    // ---------- 布雷 ----------

    /**
     * 在安全格首次翻开后布雷（同步路径：普通模式与单元测试走这里）。
     *
     * ⚠️ UI 线程注意：无猜模式下本方法会同步执行 [planMines]，大棋盘可能阻塞到预算上限
     * （[NO_GUESS_BUDGET_MS]）。UI 应优先使用 [planMines] + [applyPlanAndReveal] 的异步路径，
     * 并用 [hasPendingMines] 判断是否需要异步。
     */
    fun placeMines(safeRow: Int, safeCol: Int) {
        applyPlan(planMines(rows, cols, totalMines, safeRow, safeCol, noGuess))
    }

    /**
     * 应用（可能来自后台线程的）雷位规划。同局重复调用无效。
     * 会校验：几何（rows/cols）、长度、雷数、3×3 安全区。不合法返回 false 且不修改棋盘。
     *
     * @return 是否成功应用
     */
    fun applyPlan(plan: MinePlan): Boolean {
        if (minesPlaced) return false
        if (!validatePlan(plan)) return false
        noGuessFallback = plan.noGuessFallback
        applyMines(plan.mines)
        minesPlaced = true
        bbbv = compute3BV()
        markAllDirty()
        return true
    }

    /** 校验计划是否与当前棋盘匹配（几何/长度/雷数/安全区），不修改状态。 */
    private fun validatePlan(plan: MinePlan): Boolean {
        if (plan.rows != rows || plan.cols != cols) return false
        val cells = rows * cols
        if (plan.mines.size != cells) return false
        if (plan.safeRow !in 0 until rows || plan.safeCol !in 0 until cols) return false
        var count = 0
        for (m in plan.mines) if (m) count++
        if (count != totalMines) return false
        for (dr in -1..1) {
            for (dc in -1..1) {
                val r = plan.safeRow + dr
                val c = plan.safeCol + dc
                if (r in 0 until rows && c in 0 until cols && plan.mines[r * cols + c]) return false
            }
        }
        return true
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
        val cells = rows * cols
        val opened = BooleanArray(cells)
        var bbbv = 0
        // 显式 Int 栈（不装箱）
        var stack = IntArray(64)
        var top = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val cell = board[r][c]
                val startIdx = r * cols + c
                if (cell.mine || cell.adjacentMines != 0 || opened[startIdx]) continue
                bbbv++
                if (top == stack.size) stack = stack.copyOf(stack.size * 2)
                stack[top++] = startIdx
                while (top > 0) {
                    val idx = stack[--top]
                    if (opened[idx]) continue
                    val cr = idx / cols
                    val cc = idx % cols
                    val cur = board[cr][cc]
                    if (cur.mine) continue
                    opened[idx] = true
                    if (cur.adjacentMines == 0) {
                        Neighbors.forEachNeighbor(rows, cols, cr, cc) { nr, nc ->
                            val nIdx = nr * cols + nc
                            if (!opened[nIdx]) {
                                if (top == stack.size) stack = stack.copyOf(stack.size * 2)
                                stack[top++] = nIdx
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
        Neighbors.forEachNeighbor(rows, cols, row, col) { r, c ->
            if (board[r][c].mine) count++
        }
        return count
    }

    // ---------- 操作 ----------

    /**
     * 翻开一格。终局/越界/已翻开/已插旗时静默返回。
     *
     * ⚠️ UI 线程注意：若尚未布雷且 `noGuess = true`，本方法会同步生成可解雷局
     * （最长 [NO_GUESS_BUDGET_MS]）。UI 应先用 [hasPendingMines] 判断并走异步路径。
     */
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
     * 生成期间局面可能变化，故对所有前置条件做竞态检查（含计划与首点/几何的匹配），
     * 任一不满足即返回 false，由调用方走同步回退。
     *
     * @return 是否成功应用并翻开
     */
    fun applyPlanAndReveal(row: Int, col: Int, plan: MinePlan): Boolean {
        if (minesPlaced) return false
        if (state == "won" || state == "lost") return false
        if (row !in 0 until rows || col !in 0 until cols) return false
        // 计划必须是为本次点击生成、且与当前棋盘几何一致
        if (plan.rows != rows || plan.cols != cols) return false
        if (plan.safeRow != row || plan.safeCol != col) return false
        val cell = board[row][col]
        if (cell.revealed || cell.flagged) return false
        if (!applyPlan(plan)) return false
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

    /**
     * 迭代式洪水填充（显式 Int 栈，避免递归深度与装箱）。
     * 8 邻域展开属极限热点，故意保留内联手写循环（不调用 [Neighbors.forEachNeighbor]），
     * 语义与 helper 完全一致；详见 [Neighbors] 的性能约定。
     */
    fun floodFill(row: Int, col: Int) {
        var stack = IntArray(64)
        var top = 0
        stack[top++] = row * cols + col
        while (top > 0) {
            val idx = stack[--top]
            val r = idx / cols
            val c = idx % cols
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
                            if (top == stack.size) stack = stack.copyOf(stack.size * 2)
                            stack[top++] = nr * cols + nc
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
        if (checkWin()) {
            wonByFlags = true
            setState("won")
        }
    }

    /** 和弦：已翻开数字格周围旗数匹配时展开周围未翻开格。返回是否执行了展开。 */
    fun chord(row: Int, col: Int): Boolean {
        // 终局守卫：终局后和弦不应产生任何效果（与桌面版一致）
        if (state == "won" || state == "lost") return false
        if (row !in 0 until rows || col !in 0 until cols) return false
        val cell = board[row][col]
        if (!cell.revealed || cell.adjacentMines == 0) return false
        var adjFlags = 0
        Neighbors.forEachNeighbor(rows, cols, row, col) { r, c ->
            if (board[r][c].flagged) adjFlags++
        }
        if (adjFlags != cell.adjacentMines) return false
        var lost = false
        Neighbors.forEachNeighbor(rows, cols, row, col) { r, c ->
            if (!lost && !board[r][c].revealed) {
                reveal(r, c)
                if (state == "lost") lost = true
            }
        }
        if (lost) return true
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

    /**
     * 胜利判定：
     * 1. 翻开的安全格数达到上限；
     * 2. 插旗数等于雷数且全部插对（仅当雷数 > 0；0 雷盘不能靠插旗取胜）。
     * 未布雷（ready）时恒为 false，避免 0 雷盘在开局即"胜利"。
     */
    fun checkWin(): Boolean {
        if (!minesPlaced) return false
        val totalSafe = rows * cols - totalMines
        if (revealedCount >= totalSafe) return true
        if (totalMines > 0 && flagCount == totalMines) {
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    if (board[r][c].flagged != board[r][c].mine) return false
                }
            }
            return true
        }
        return false
    }

    private fun setState(newState: String) {
        state = newState
    }
}
