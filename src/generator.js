/**
 * 雷位生成：纯计算，不持有棋盘状态，可被渲染进程、Node 测试与主进程（IPC 异步生成）共用。
 *
 * - 普通模式：Fisher-Yates 洗牌，O(n) 且无拒绝采样退化
 * - 无猜模式：反复重生成直到求解器判定可纯逻辑通关；受尝试上限与时间预算双重约束。
 *   策略为「确定性门控」（M1）：按棋盘尺寸/密度预判是否值得重试，不再用「按单次耗时外推
 *   预算内尝试次数」的启发式——后者在单次 ≈ budget/20 时会在 1 次与数百次之间抖动，
 *   同一配置时快时慢。门控命中（实测基本无解的「大盘 + 高密度」）时只试 1–2 次即降级；
 *   其余配置给足预算（求解器 M2 提速后 50×50/500 在 800ms 内也值得重试）。
 * - 首点 3×3 安全区永不布雷（保证首次点击绝对安全）
 * - 输入参数严格校验（M4）：行列/雷数/安全区坐标非法时抛错，不把异常延迟到首次点击
 *
 * 与安卓版 android/core 的 GameEngine.planMines 逐语义对齐。
 */
;(function (root, factory) {
  if (typeof module === 'object' && module.exports) {
    module.exports = factory(require('./solver.js'), require('./config.js'), require('./neighbors.js'))
  } else {
    root.MineGenerator = factory(root.MineSolver, root.CONFIG, root.Neighbors)
  }
})(typeof self !== 'undefined' ? self : this, function (Solver, CONFIG, Neighbors) {
  /**
   * 旧策略常量，保留导出以兼容既有测试/调用方；确定性门控（M1）已不再使用耗时外推。
   */
  const MIN_EXPECTED_ATTEMPTS = 20

  // 复用的临时棋盘：求解器只读 mine/adjacentMines，避免每次尝试分配 rows*cols 个对象
  let scratch = null
  let scratchRows = 0
  let scratchCols = 0

  function scratchFor(rows, cols) {
    if (!scratch || scratchRows !== rows || scratchCols !== cols) {
      scratch = Array.from({ length: rows }, () =>
        Array.from({ length: cols }, () => ({ mine: false, adjacentMines: 0 }))
      )
      scratchRows = rows
      scratchCols = cols
    }
    return scratch
  }

  /** 输入校验（M4）：非法参数立即抛错（与 game.initCustom / 安卓 planMines 的 require 对齐） */
  function assertInputs(rows, cols, totalMines, safeRow, safeCol) {
    if (!Number.isInteger(rows) || !Number.isInteger(cols) || rows < 1 || cols < 1) {
      throw new Error('行列数必须是正整数')
    }
    if (!Number.isInteger(totalMines) || totalMines < 0) {
      throw new Error('雷数必须是非负整数')
    }
    if (
      !Number.isInteger(safeRow) || !Number.isInteger(safeCol) ||
      safeRow < 0 || safeRow >= rows || safeCol < 0 || safeCol >= cols
    ) {
      throw new Error('安全区坐标越界')
    }
    // 首点 3×3 安全区不布雷，故最坏（首点居中）时可用格为 rows*cols-9
    const maxMines = Math.max(0, rows * cols - 9)
    if (totalMines > maxMines) {
      throw new Error(`雷数超过可用格子数量（最多 ${maxMines}）`)
    }
  }

  /** 安全区（首点 3×3）之外的格子索引，洗牌后取前 totalMines 个作为雷位 */
  function shuffledCandidates(rows, cols, safeRow, safeCol, totalMines, random) {
    const candidates = []
    for (let r = 0; r < rows; r++) {
      for (let c = 0; c < cols; c++) {
        if (Math.abs(r - safeRow) > 1 || Math.abs(c - safeCol) > 1) {
          candidates.push(r * cols + c)
        }
      }
    }
    if (candidates.length < totalMines) {
      throw new Error('雷数超过可用格子数量')
    }
    for (let i = candidates.length - 1; i > 0; i--) {
      const j = Math.floor(random() * (i + 1))
      const t = candidates[i]
      candidates[i] = candidates[j]
      candidates[j] = t
    }
    return candidates
  }

  /** 把雷位写入复用的临时棋盘并算好相邻雷数，返回可供求解器使用的棋盘视图 */
  function gridFromMines(mines, rows, cols) {
    const grid = scratchFor(rows, cols)
    const graph = Neighbors.get(rows, cols)
    const nbStart = graph.start
    const nbList = graph.list
    const cells = rows * cols
    for (let idx = 0; idx < cells; idx++) {
      const cell = grid[(idx / cols) | 0][idx % cols]
      cell.mine = mines[idx] === 1
      cell.adjacentMines = 0
    }
    for (let idx = 0; idx < cells; idx++) {
      if (mines[idx] === 1) continue
      let count = 0
      for (let k = nbStart[idx]; k < nbStart[idx + 1]; k++) {
        if (mines[nbList[k]] === 1) count++
      }
      grid[(idx / cols) | 0][idx % cols].adjacentMines = count
    }
    return grid
  }

  /**
   * 确定性尝试上限（M1）：只按尺寸/密度预判，不读取实际耗时。
   * 实测「大盘 + 高密度」（≥ hopelessDensity 且 ≥ hopelessMinCells 格）无猜可解率极低，
   * 例如 40×40/400 在约 8800 次尝试内 0 成功、30×30/500 为 0/30——只试 gateAttempts 次。
   * 其余配置（含 50×50/500：M2 后单次 solve 仅 ~3ms，800ms 内可重试数百次）给足预算。
   */
  function deterministicAttemptCap(rows, cols, totalMines, maxAttempts) {
    const gate = (CONFIG.NO_GUESS && CONFIG.NO_GUESS.gate) || {}
    const hopelessDensity = Number.isFinite(gate.hopelessDensity) ? gate.hopelessDensity : 0.25
    const hopelessMinCells = Number.isFinite(gate.hopelessMinCells) ? gate.hopelessMinCells : 900
    const gateAttempts = Number.isFinite(gate.gateAttempts) ? gate.gateAttempts : 2
    const cells = rows * cols
    const density = cells > 0 ? totalMines / cells : 1
    if (cells >= hopelessMinCells && density >= hopelessDensity) {
      return Math.max(1, Math.min(gateAttempts, maxAttempts))
    }
    return maxAttempts
  }

  function positiveNumber(value, fallback) {
    return Number.isFinite(value) && value > 0 ? value : fallback
  }

  function positiveInt(value, fallback) {
    return Number.isInteger(value) && value > 0 ? value : fallback
  }

  /**
   * 规划一局雷位。
   * @param {number} rows 行数（正整数）
   * @param {number} cols 列数（正整数）
   * @param {number} totalMines 雷数（0 ≤ totalMines ≤ rows*cols-9）
   * @param {number} safeRow 首点行（3×3 安全区中心，必须在界内）
   * @param {number} safeCol 首点列（同上）
   * @param {boolean} noGuess 是否启用无猜生成
   * @param {function(): number} [now] 时间源（毫秒），默认 Date.now；测试可注入
   * @param {function(): number} [random] 随机源，默认 Math.random；测试可注入
   * @param {{budgetMs?: number, maxAttempts?: number}} [options] 覆盖 CONFIG.NO_GUESS（同步回退小预算用）
   * @returns {{mines: Uint8Array, noGuessFallback: boolean, rows: number, cols: number,
   *   safeRow: number, safeCol: number}} 雷位（长度 rows*cols，1=雷）与生成元数据
   */
  function generate(
    rows, cols, totalMines, safeRow, safeCol, noGuess,
    now = Date.now, random = Math.random, options = {}
  ) {
    assertInputs(rows, cols, totalMines, safeRow, safeCol)
    const opts = options && typeof options === 'object' ? options : {}
    const mines = new Uint8Array(rows * cols)
    const geometry = { rows, cols, safeRow, safeCol }
    const pick = (candidates) => {
      mines.fill(0)
      for (let k = 0; k < totalMines; k++) mines[candidates[k]] = 1
    }

    if (!noGuess) {
      pick(shuffledCandidates(rows, cols, safeRow, safeCol, totalMines, random))
      return { mines, noGuessFallback: false, ...geometry }
    }

    const cfg = CONFIG.NO_GUESS || {}
    const budgetMs = positiveNumber(opts.budgetMs, positiveNumber(cfg.budgetMs, 800))
    const maxAttempts = positiveInt(opts.maxAttempts, positiveInt(cfg.maxAttempts, 600))
    const attemptCap = deterministicAttemptCap(rows, cols, totalMines, maxAttempts)
    const start = now()
    for (let attempt = 0; attempt < attemptCap; attempt++) {
      pick(shuffledCandidates(rows, cols, safeRow, safeCol, totalMines, random))
      const grid = gridFromMines(mines, rows, cols)
      if (Solver.solve(grid, rows, cols, totalMines, safeRow, safeCol)) {
        return { mines, noGuessFallback: false, ...geometry }
      }
      const elapsed = now() - start
      if (elapsed <= 0) continue // 时间源精度不足（如注入的假时钟）：只受尝试上限约束
      if (elapsed >= budgetMs) break
    }
    return { mines, noGuessFallback: true, ...geometry }
  }

  return { generate, MIN_EXPECTED_ATTEMPTS }
})
