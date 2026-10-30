/**
 * 扫雷游戏引擎：纯逻辑、无 DOM 依赖。
 * 同时支持浏览器（全局 GameEngine）与 Node 测试（module.exports）。
 * 雷位生成抽到 generator.js：可在其他进程/线程运行后再由 applyPlan 应用（无猜模式异步生成）。
 * 邻居遍历统一走 neighbors.js 的 CSR 缓存（L15）。
 */
;(function (root, factory) {
  if (typeof module === 'object' && module.exports) {
    module.exports = factory(require('./config.js'), require('./generator.js'), require('./neighbors.js'))
  } else {
    root.GameEngine = factory(root.CONFIG, root.MineGenerator, root.Neighbors)
  }
})(typeof self !== 'undefined' ? self : this, function (CONFIG, Generator, Neighbors) {
  // 状态常量（值保持历史字符串不变；config.STATES 为单一来源，缺失时兜底）
  const STATES = CONFIG.STATES || Object.freeze({
    READY: 'ready',
    PLAYING: 'playing',
    WON: 'won',
    LOST: 'lost'
  })
  const CUSTOM_LEVEL = CONFIG.CUSTOM_LEVEL_ID || 'custom'

  class GameEngine {
    constructor(level = 'beginner') {
      this.levels = CONFIG.LEVELS
      this.stateListeners = []
      this.dirty = new Set()
      // 无猜模式（由 UI 开关控制）：布雷后校验可纯逻辑通关，不通过则重新生成
      this.noGuess = false
      // 本局 3BV（理论最少点击数）与无猜生成是否降级为普通雷局
      this.bbbv = 0
      this.noGuessFallback = false
      // 本局是否由「插对全部旗」触发胜利（DE-13/统计标注用）
      this.wonByFlags = false
      // 本局用时（毫秒，由 UI 计时器写入；引擎自身不推进时钟）
      this.elapsedMs = 0
      this.init(level)
    }

    // ---------- 状态与变更通知 ----------

    onStateChange(fn) {
      this.stateListeners.push(fn)
    }

    setState(state) {
      if (this.state === state) return
      this.state = state
      for (const fn of this.stateListeners) fn(state)
    }

    // ---------- 脏标记（增量渲染用） ----------

    markDirty(row, col) {
      this.dirty.add(row + ',' + col)
    }

    markAllDirty() {
      for (let r = 0; r < this.rows; r++) {
        for (let c = 0; c < this.cols; c++) {
          this.dirty.add(r + ',' + c)
        }
      }
    }

    // ---------- 初始化 ----------

    /** 公共的新局字段重置（init/initCustom 共用，避免两份重复） */
    resetRound() {
      this.state = STATES.READY
      this.minesPlaced = false
      this.timer = 0
      this.elapsedMs = 0
      this.flagCount = 0
      this.revealedCount = 0
      this.bbbv = 0
      this.noGuessFallback = false
      this.wonByFlags = false
      this.board = this.createBoard()
      this.dirty.clear()
      this.markAllDirty()
    }

    init(level) {
      const cfg = this.levels[level]
      if (!cfg) throw new Error('未知难度: ' + level)
      this.currentLevel = level
      this.currentCustom = null
      this.rows = cfg.rows
      this.cols = cfg.cols
      this.totalMines = cfg.mines
      this.resetRound()
    }

    initCustom(rows, cols, mines) {
      // 非法配置在构造时就拒绝，避免把异常延迟到「第一次点击」才抛
      if (!Number.isInteger(rows) || !Number.isInteger(cols) || !Number.isInteger(mines)) {
        throw new Error('行列数与雷数必须是整数')
      }
      if (rows < 1 || cols < 1) throw new Error('行列数必须为正整数')
      if (mines < 0) throw new Error('雷数不能为负')
      // 首点 3×3 安全区不布雷，故最坏（首点居中）时可用格为 rows*cols-9
      const maxMines = Math.max(0, rows * cols - 9)
      if (mines > maxMines) throw new Error(`雷数超过可用格子数量（最多 ${maxMines}）`)
      this.currentLevel = CUSTOM_LEVEL
      this.currentCustom = { rows, cols, mines }
      this.rows = rows
      this.cols = cols
      this.totalMines = mines
      this.resetRound()
    }

    createBoard() {
      return Array.from({ length: this.rows }, () =>
        Array.from({ length: this.cols }, () => ({
          mine: false,
          revealed: false,
          flagged: false,
          questioned: false,
          adjacentMines: 0,
          exploded: false,
          // 失败终局时置位：插在非雷格上的旗（UI 绘制红叉提示）
          wrongFlag: false
        }))
      )
    }

    // ---------- 布雷 ----------

    /**
     * 在安全格首次翻开后布雷（同步路径：普通模式与单元测试走这里）。
     * 无猜模式生成耗时较长，UI 应优先走「后台生成 + applyPlanAndReveal」异步路径；
     * 若必须同步回退，可通过 options 传小预算（如 {budgetMs: 50, maxAttempts: 8}）限制冻结时长。
     * @param {number} safeRow 首点行（3×3 安全区中心）
     * @param {number} safeCol 首点列
     * @param {{budgetMs?: number, maxAttempts?: number}} [options] 透传 generator 的预算覆盖
     * @returns {boolean} 雷位是否成功应用
     */
    placeMines(safeRow, safeCol, options) {
      const plan = Generator.generate(
        this.rows,
        this.cols,
        this.totalMines,
        safeRow,
        safeCol,
        this.noGuess,
        Date.now,
        Math.random,
        options
      )
      return this.applyPlan(plan)
    }

    /**
     * 应用（可能来自其他进程/线程的）雷位规划。同局重复调用无效。
     * M3：校验 plan 几何与内容——行列一致、长度、雷数、首点 3×3 安全区；
     * 缺几何字段（旧调用）时向后兼容，仅校验长度与雷数。
     * @param {{mines: Uint8Array|Array|Object, noGuessFallback?: boolean, rows?: number,
     *   cols?: number, safeRow?: number, safeCol?: number}} plan
     * @returns {boolean} 是否成功应用（失败时不开局、状态不变）
     */
    applyPlan(plan) {
      if (this.minesPlaced) return false
      if (!plan || typeof plan !== 'object' || !plan.mines || typeof plan.mines.length !== 'number') {
        return false
      }
      const mines = plan.mines
      const cells = this.rows * this.cols
      if (mines.length !== cells) return false
      if (Number.isInteger(plan.rows) && plan.rows !== this.rows) return false
      if (Number.isInteger(plan.cols) && plan.cols !== this.cols) return false
      let mineCount = 0
      for (let idx = 0; idx < cells; idx++) {
        const v = mines[idx]
        if (v === 1 || v === true) mineCount++
      }
      if (mineCount !== this.totalMines) return false
      if (Number.isInteger(plan.safeRow) && Number.isInteger(plan.safeCol)) {
        const { safeRow, safeCol } = plan
        if (safeRow < 0 || safeRow >= this.rows || safeCol < 0 || safeCol >= this.cols) return false
        for (let dr = -1; dr <= 1; dr++) {
          for (let dc = -1; dc <= 1; dc++) {
            const r = safeRow + dr
            const c = safeCol + dc
            if (r < 0 || r >= this.rows || c < 0 || c >= this.cols) continue
            const v = mines[r * this.cols + c]
            if (v === 1 || v === true) return false
          }
        }
      }
      this.noGuessFallback = plan.noGuessFallback === true
      this.applyMines(mines)
      this.minesPlaced = true
      this.bbbv = this.compute3BV()
      this.markAllDirty()
      return true
    }

    /** 写入雷位并重算相邻雷数；mines 为按行主序的 0/1 序列（跨 IPC 后可能不是 Uint8Array） */
    applyMines(mines) {
      const { rows, cols } = this
      const cells = rows * cols
      const graph = Neighbors.get(rows, cols)
      const nbStart = graph.start
      const nbList = graph.list
      const flat = new Uint8Array(cells)
      for (let idx = 0; idx < cells; idx++) {
        const v = mines[idx]
        const isMine = v === 1 || v === true
        flat[idx] = isMine ? 1 : 0
        this.board[(idx / cols) | 0][idx % cols].mine = isMine
        this.board[(idx / cols) | 0][idx % cols].adjacentMines = 0
      }
      for (let idx = 0; idx < cells; idx++) {
        if (flat[idx] === 1) continue
        let count = 0
        for (let k = nbStart[idx]; k < nbStart[idx + 1]; k++) {
          if (flat[nbList[k]] === 1) count++
        }
        this.board[(idx / cols) | 0][idx % cols].adjacentMines = count
      }
    }

    /**
     * 3BV：完成本局所需的最少点击次数 —— 每个 0 区域算 1 次（洪泛展开会连带翻开其边界数字格），
     * 未被 0 区域覆盖的数字格各算 1 次。用于效率统计（3BV/s）。
     */
    compute3BV() {
      const { rows, cols, board } = this
      const cells = rows * cols
      const graph = Neighbors.get(rows, cols)
      const nbStart = graph.start
      const nbList = graph.list
      const opened = new Uint8Array(cells)
      const stack = []
      let bbbv = 0
      for (let idx = 0; idx < cells; idx++) {
        const cell = board[(idx / cols) | 0][idx % cols]
        if (cell.mine || cell.adjacentMines !== 0 || opened[idx]) continue
        bbbv++
        stack.push(idx)
        while (stack.length) {
          const curIdx = stack.pop()
          if (opened[curIdx]) continue
          const cur = board[(curIdx / cols) | 0][curIdx % cols]
          if (cur.mine) continue
          opened[curIdx] = 1
          if (cur.adjacentMines === 0) {
            for (let k = nbStart[curIdx]; k < nbStart[curIdx + 1]; k++) {
              const n = nbList[k]
              if (!opened[n]) stack.push(n)
            }
          }
        }
      }
      for (let idx = 0; idx < cells; idx++) {
        const cell = board[(idx / cols) | 0][idx % cols]
        if (!cell.mine && cell.adjacentMines > 0 && !opened[idx]) bbbv++
      }
      return bbbv
    }

    countAdjacentMines(row, col) {
      let count = 0
      Neighbors.forEachNeighbor(this.rows, this.cols, row, col, (r, c) => {
        if (this.board[r][c].mine) count++
      })
      return count
    }

    // ---------- 操作 ----------

    /**
     * 翻开一格；首次翻开会先布雷（同步路径）。
     * @param {number} row
     * @param {number} col
     * @param {{budgetMs?: number, maxAttempts?: number}} [options] 仅在同步布雷时透传（H2）
     */
    reveal(row, col, options) {
      if (this.state === STATES.WON || this.state === STATES.LOST) return
      if (row < 0 || row >= this.rows || col < 0 || col >= this.cols) return
      const cell = this.board[row][col]
      if (cell.revealed || cell.flagged) return
      if (!this.minesPlaced) {
        this.placeMines(row, col, options)
        this.setState(STATES.PLAYING)
      }
      this.finishReveal(row, col)
    }

    /**
     * 异步路径入口：雷位已由后台（主进程/工作线程）生成，这里只做「应用 + 翻开」。
     * 生成期间局面可能变化，故对所有前置条件做竞态检查，任一不满足即放弃。
     * M3：plan 带上 safeRow/safeCol 时必须与点击格一致（防止过期 plan 落到别的位置）。
     * @returns {boolean} 是否成功应用并翻开
     */
    applyPlanAndReveal(row, col, plan) {
      if (this.minesPlaced) return false
      if (this.state === STATES.WON || this.state === STATES.LOST) return false
      if (row < 0 || row >= this.rows || col < 0 || col >= this.cols) return false
      const cell = this.board[row][col]
      if (cell.revealed || cell.flagged) return false
      if (plan && Number.isInteger(plan.safeRow) && Number.isInteger(plan.safeCol)) {
        if (plan.safeRow !== row || plan.safeCol !== col) return false
      }
      if (!this.applyPlan(plan)) return false
      this.setState(STATES.PLAYING)
      this.finishReveal(row, col)
      return true
    }

    /** 翻开本格并处理踩雷/胜负（调用方已完成全部前置守卫） */
    finishReveal(row, col) {
      const cell = this.board[row][col]
      if (cell.mine) {
        cell.revealed = true
        cell.exploded = true
        this.setState(STATES.LOST)
        this.revealAllMines()
        return
      }
      this.floodFill(row, col)
      if (this.checkWin()) this.setState(STATES.WON)
    }

    // 迭代式洪水填充（显式栈，避免深递归；邻居走 CSR 索引）
    floodFill(row, col) {
      const { rows, cols } = this
      const graph = Neighbors.get(rows, cols)
      const nbStart = graph.start
      const nbList = graph.list
      const stack = [row * cols + col]
      while (stack.length) {
        const idx = stack.pop()
        const r = (idx / cols) | 0
        const c = idx % cols
        const cell = this.board[r][c]
        if (cell.revealed || cell.flagged || cell.mine) continue
        cell.revealed = true
        // 问号格不在上面的跳过条件里，会被洪泛翻开并清除问号标记
        cell.questioned = false
        this.revealedCount++
        this.markDirty(r, c)
        if (cell.adjacentMines === 0) {
          for (let k = nbStart[idx]; k < nbStart[idx + 1]; k++) {
            stack.push(nbList[k])
          }
        }
      }
    }

    toggleFlag(row, col) {
      if (this.state === STATES.WON || this.state === STATES.LOST) return
      if (row < 0 || row >= this.rows || col < 0 || col >= this.cols) return
      const cell = this.board[row][col]
      if (cell.revealed) return
      if (this.state === STATES.READY) this.setState(STATES.PLAYING)
      if (!cell.flagged && !cell.questioned) {
        cell.flagged = true
        this.flagCount++
      } else if (cell.flagged) {
        cell.flagged = false
        cell.questioned = true
        this.flagCount--
      } else {
        cell.questioned = false
      }
      this.markDirty(row, col)
      if (this.checkWin()) {
        // L12/DE-13：记录「插对全部旗」触发的胜利，供统计标注
        this.wonByFlags = true
        this.setState(STATES.WON)
      }
    }

    chord(row, col) {
      // 终局守卫与安卓版一致：终局后和弦不应产生任何效果
      if (this.state === STATES.WON || this.state === STATES.LOST) return false
      if (row < 0 || row >= this.rows || col < 0 || col >= this.cols) return false
      const cell = this.board[row][col]
      if (!cell.revealed || cell.adjacentMines === 0) return false
      let adjFlags = 0
      Neighbors.forEachNeighbor(this.rows, this.cols, row, col, (r, c) => {
        if (this.board[r][c].flagged) adjFlags++
      })
      if (adjFlags !== cell.adjacentMines) return false
      let neighborCount = 0
      Neighbors.forEachNeighbor(this.rows, this.cols, row, col, (r, c) => {
        neighborCount++
        if (!this.board[r][c].revealed) {
          this.reveal(r, c)
        }
      })
      if (this.state === STATES.LOST) return true
      // 理论上 reveal 内部已判胜；保留一次兜底（与安卓一致），但仅在已有邻居时检查
      if (neighborCount > 0 && this.checkWin()) this.setState(STATES.WON)
      return true
    }

    // ---------- 终局辅助 ----------

    revealAllMines() {
      for (let r = 0; r < this.rows; r++) {
        for (let c = 0; c < this.cols; c++) {
          const cell = this.board[r][c]
          if (cell.mine) {
            cell.revealed = true
          } else if (cell.flagged) {
            // 非雷格上的旗：标记错误旗，UI 绘制红叉
            cell.wrongFlag = true
          }
        }
      }
      this.markAllDirty()
    }

    revealAllSafe() {
      for (let r = 0; r < this.rows; r++) {
        for (let c = 0; c < this.cols; c++) {
          const cell = this.board[r][c]
          if (!cell.mine && !cell.revealed) {
            cell.revealed = true
            this.revealedCount++
          }
        }
      }
      this.markAllDirty()
    }

    autoFlagRemainingMines() {
      for (let r = 0; r < this.rows; r++) {
        for (let c = 0; c < this.cols; c++) {
          const cell = this.board[r][c]
          if (cell.mine && !cell.flagged) {
            cell.flagged = true
            this.flagCount++
          }
        }
      }
      this.markAllDirty()
    }

    checkWin() {
      const totalSafe = this.rows * this.cols - this.totalMines
      if (this.revealedCount >= totalSafe) return true
      // M8：0 雷棋盘不允许靠「旗→问号」把 flagCount 归零后直接判胜（必须真正翻开）
      if (this.totalMines > 0 && this.flagCount === this.totalMines) {
        for (let r = 0; r < this.rows; r++) {
          for (let c = 0; c < this.cols; c++) {
            const cell = this.board[r][c]
            if (cell.flagged !== cell.mine) return false
          }
        }
        return true
      }
      return false
    }
  }

  GameEngine.STATES = STATES
  return GameEngine
})
