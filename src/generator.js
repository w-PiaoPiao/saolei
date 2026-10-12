/**
 * 雷位生成：纯计算，不持有棋盘状态，可被渲染进程、Node 测试与主进程（IPC 异步生成）共用。
 *
 * - 普通模式：Fisher-Yates 洗牌，O(n) 且无拒绝采样退化
 * - 无猜模式：反复重生成直到求解器判定可纯逻辑通关；受尝试上限与时间预算双重约束，
 *   并在「预算内可尝试次数已明显不足以期待命中」时提前降级，避免大棋盘白等
 * - 首点 3×3 安全区永不布雷（保证首次点击绝对安全）
 *
 * 与安卓版 android/core 的 GameEngine.planMines 逐语义对齐。
 */
;(function (root, factory) {
  if (typeof module === 'object' && module.exports) {
    module.exports = factory(require('./solver.js'), require('./config.js'))
  } else {
    root.MineGenerator = factory(root.MineSolver, root.CONFIG)
  }
})(typeof self !== 'undefined' ? self : this, function (Solver, CONFIG) {
  /**
   * 预算内预估总尝试次数低于此值时提前降级：可解率不足，继续等待只会拖长首点卡顿
   * （高级局单次尝试约 1ms、预算内可跑数百次，不受影响；50×50 单次约 30ms 会立即降级）
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
    for (let r = 0; r < rows; r++) {
      for (let c = 0; c < cols; c++) {
        grid[r][c].mine = mines[r * cols + c] === 1
        grid[r][c].adjacentMines = 0
      }
    }
    for (let r = 0; r < rows; r++) {
      for (let c = 0; c < cols; c++) {
        if (grid[r][c].mine) continue
        let count = 0
        for (let dr = -1; dr <= 1; dr++) {
          for (let dc = -1; dc <= 1; dc++) {
            if (dr === 0 && dc === 0) continue
            const nr = r + dr
            const nc = c + dc
            if (nr >= 0 && nr < rows && nc >= 0 && nc < cols && grid[nr][nc].mine) count++
          }
        }
        grid[r][c].adjacentMines = count
      }
    }
    return grid
  }

  /**
   * 规划一局雷位。
   * @param {number} rows 行数
   * @param {number} cols 列数
   * @param {number} totalMines 雷数
   * @param {number} safeRow 首点行（3×3 安全区中心）
   * @param {number} safeCol 首点列
   * @param {boolean} noGuess 是否启用无猜生成
   * @param {function(): number} [now] 时间源（毫秒），默认 Date.now；测试可注入
   * @param {function(): number} [random] 随机源，默认 Math.random；测试可注入
   * @returns {{mines: Uint8Array, noGuessFallback: boolean}} 雷位（长度 rows*cols，1=雷）与是否降级
   */
  function generate(
    rows, cols, totalMines, safeRow, safeCol, noGuess,
    now = Date.now, random = Math.random
  ) {
    const mines = new Uint8Array(rows * cols)
    const pick = (candidates) => {
      mines.fill(0)
      for (let k = 0; k < totalMines; k++) mines[candidates[k]] = 1
    }

    if (!noGuess) {
      pick(shuffledCandidates(rows, cols, safeRow, safeCol, totalMines, random))
      return { mines, noGuessFallback: false }
    }

    const { maxAttempts, budgetMs } = CONFIG.NO_GUESS
    const start = now()
    for (let attempt = 0; attempt < maxAttempts; attempt++) {
      pick(shuffledCandidates(rows, cols, safeRow, safeCol, totalMines, random))
      const grid = gridFromMines(mines, rows, cols)
      if (Solver.solve(grid, rows, cols, totalMines, safeRow, safeCol)) {
        return { mines, noGuessFallback: false }
      }
      const elapsed = now() - start
      if (elapsed <= 0) continue // 时间源精度不足（如注入的假时钟）：只受尝试上限约束
      // 预估：按当前单次耗时，预算内总量不足以期待命中可解局 → 立即降级
      if (budgetMs / (elapsed / (attempt + 1)) < MIN_EXPECTED_ATTEMPTS) break
      if (elapsed >= budgetMs) break
    }
    return { mines, noGuessFallback: true }
  }

  return { generate, MIN_EXPECTED_ATTEMPTS }
})
