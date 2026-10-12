/**
 * 扫雷游戏引擎：纯逻辑、无 DOM 依赖。
 * 同时支持浏览器（全局 GameEngine）与 Node 测试（module.exports）。
 */
;(function (root, factory) {
  if (typeof module === 'object' && module.exports) {
    module.exports = factory(require('./config.js'), require('./solver.js'))
  } else {
    root.GameEngine = factory(root.CONFIG, root.MineSolver)
  }
})(typeof self !== 'undefined' ? self : this, function (CONFIG, Solver) {
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

    init(level) {
      const cfg = this.levels[level]
      if (!cfg) throw new Error('未知难度: ' + level)
      this.currentLevel = level
      this.currentCustom = null
      this.rows = cfg.rows
      this.cols = cfg.cols
      this.totalMines = cfg.mines
      this.state = 'ready'
      this.minesPlaced = false
      this.timer = 0
      this.flagCount = 0
      this.revealedCount = 0
      this.bbbv = 0
      this.noGuessFallback = false
      this.board = this.createBoard()
      this.dirty.clear()
      this.markAllDirty()
    }

    initCustom(rows, cols, mines) {
      this.currentLevel = 'custom'
      this.currentCustom = { rows, cols, mines }
      this.rows = rows
      this.cols = cols
      this.totalMines = mines
      this.state = 'ready'
      this.minesPlaced = false
      this.timer = 0
      this.flagCount = 0
      this.revealedCount = 0
      this.bbbv = 0
      this.noGuessFallback = false
      this.board = this.createBoard()
      this.dirty.clear()
      this.markAllDirty()
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
     * 在安全格首次翻开后布雷：
     * - 安全格周围 3×3 永不布雷（保证首次点击绝对安全）
     * - 普通模式 Fisher-Yates 洗牌，O(n) 且无拒绝采样退化
     * - 无猜模式反复重生成，直到求解器判定可纯逻辑通关
     */
    placeMines(safeRow, safeCol) {
      if (this.noGuess) {
        this.placeMinesNoGuess(safeRow, safeCol)
      } else {
        this.randomizeMines(safeRow, safeCol)
        this.noGuessFallback = false
      }
      this.minesPlaced = true
      this.bbbv = this.compute3BV()
      this.markAllDirty()
    }

    /** 随机布雷（先清空上一轮结果，供无猜重试复用同一张棋盘） */
    randomizeMines(safeRow, safeCol) {
      for (let r = 0; r < this.rows; r++) {
        for (let c = 0; c < this.cols; c++) {
          this.board[r][c].mine = false
          this.board[r][c].adjacentMines = 0
        }
      }
      const candidates = []
      for (let r = 0; r < this.rows; r++) {
        for (let c = 0; c < this.cols; c++) {
          if (Math.abs(r - safeRow) > 1 || Math.abs(c - safeCol) > 1) {
            candidates.push(r * this.cols + c)
          }
        }
      }
      if (candidates.length < this.totalMines) {
        throw new Error('雷数超过可用格子数量')
      }
      for (let i = candidates.length - 1; i > 0; i--) {
        const j = Math.floor(Math.random() * (i + 1))
        const t = candidates[i]
        candidates[i] = candidates[j]
        candidates[j] = t
      }
      for (let k = 0; k < this.totalMines; k++) {
        const idx = candidates[k]
        this.board[Math.floor(idx / this.cols)][idx % this.cols].mine = true
      }
      for (let r = 0; r < this.rows; r++) {
        for (let c = 0; c < this.cols; c++) {
          if (!this.board[r][c].mine) {
            this.board[r][c].adjacentMines = this.countAdjacentMines(r, c)
          }
        }
      }
    }

    /**
     * 无猜布雷：重生成直到求解器判定可解。
     * 受 maxAttempts 与时间预算双重限制，预算内未找到时沿用最后一次生成的雷局，
     * 并置 noGuessFallback = true 供 UI 提示。
     */
    placeMinesNoGuess(safeRow, safeCol) {
      const { maxAttempts, budgetMs } = CONFIG.NO_GUESS
      const deadline = Date.now() + budgetMs
      for (let attempt = 0; attempt < maxAttempts; attempt++) {
        this.randomizeMines(safeRow, safeCol)
        if (Solver.solve(this.board, this.rows, this.cols, this.totalMines, safeRow, safeCol)) {
          this.noGuessFallback = false
          return true
        }
        if (Date.now() >= deadline) break
      }
      this.noGuessFallback = true
      return false
    }

    /**
     * 3BV：完成本局所需的最少点击次数 —— 每个 0 区域算 1 次（洪泛展开会连带翻开其边界数字格），
     * 未被 0 区域覆盖的数字格各算 1 次。用于效率统计（3BV/s）。
     */
    compute3BV() {
      const { rows, cols, board } = this
      const opened = []
      for (let r = 0; r < rows; r++) opened.push(new Uint8Array(cols))
      const stack = []
      let bbbv = 0
      for (let r = 0; r < rows; r++) {
        for (let c = 0; c < cols; c++) {
          const cell = board[r][c]
          if (cell.mine || cell.adjacentMines !== 0 || opened[r][c]) continue
          bbbv++
          stack.push(r * cols + c)
          while (stack.length) {
            const idx = stack.pop()
            const cr = (idx / cols) | 0
            const cc = idx % cols
            if (opened[cr][cc]) continue
            const cur = board[cr][cc]
            if (cur.mine) continue
            opened[cr][cc] = 1
            if (cur.adjacentMines === 0) {
              for (let dr = -1; dr <= 1; dr++) {
                for (let dc = -1; dc <= 1; dc++) {
                  if (dr === 0 && dc === 0) continue
                  const nr = cr + dr
                  const nc = cc + dc
                  if (nr >= 0 && nr < rows && nc >= 0 && nc < cols && !opened[nr][nc]) {
                    stack.push(nr * cols + nc)
                  }
                }
              }
            }
          }
        }
      }
      for (let r = 0; r < rows; r++) {
        for (let c = 0; c < cols; c++) {
          const cell = board[r][c]
          if (!cell.mine && cell.adjacentMines > 0 && !opened[r][c]) bbbv++
        }
      }
      return bbbv
    }

    countAdjacentMines(row, col) {
      let count = 0
      for (let dr = -1; dr <= 1; dr++) {
        for (let dc = -1; dc <= 1; dc++) {
          if (dr === 0 && dc === 0) continue
          const r = row + dr
          const c = col + dc
          if (r >= 0 && r < this.rows && c >= 0 && c < this.cols && this.board[r][c].mine) count++
        }
      }
      return count
    }

    // ---------- 操作 ----------

    reveal(row, col) {
      if (this.state === 'won' || this.state === 'lost') return
      const cell = this.board[row][col]
      if (cell.revealed || cell.flagged) return
      if (!this.minesPlaced) {
        this.placeMines(row, col)
        this.setState('playing')
      }
      if (cell.mine) {
        cell.revealed = true
        cell.exploded = true
        this.setState('lost')
        this.revealAllMines()
        return
      }
      this.floodFill(row, col)
      if (this.checkWin()) this.setState('won')
    }

    // 迭代式洪水填充（显式栈，避免深递归）
    floodFill(row, col) {
      const stack = [[row, col]]
      while (stack.length) {
        const [r, c] = stack.pop()
        const cell = this.board[r][c]
        if (cell.revealed || cell.flagged || cell.mine) continue
        cell.revealed = true
        cell.flagged = false
        cell.questioned = false
        this.revealedCount++
        this.markDirty(r, c)
        if (cell.adjacentMines === 0) {
          for (let dr = -1; dr <= 1; dr++) {
            for (let dc = -1; dc <= 1; dc++) {
              if (dr === 0 && dc === 0) continue
              const nr = r + dr
              const nc = c + dc
              if (nr >= 0 && nr < this.rows && nc >= 0 && nc < this.cols) {
                stack.push([nr, nc])
              }
            }
          }
        }
      }
    }

    toggleFlag(row, col) {
      if (this.state === 'won' || this.state === 'lost') return
      const cell = this.board[row][col]
      if (cell.revealed) return
      if (this.state === 'ready') this.setState('playing')
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
      if (this.checkWin()) this.setState('won')
    }

    chord(row, col) {
      const cell = this.board[row][col]
      if (!cell.revealed || cell.adjacentMines === 0) return false
      let adjFlags = 0
      for (let dr = -1; dr <= 1; dr++) {
        for (let dc = -1; dc <= 1; dc++) {
          if (dr === 0 && dc === 0) continue
          const r = row + dr
          const c = col + dc
          if (r >= 0 && r < this.rows && c >= 0 && c < this.cols && this.board[r][c].flagged) adjFlags++
        }
      }
      if (adjFlags !== cell.adjacentMines) return false
      for (let dr = -1; dr <= 1; dr++) {
        for (let dc = -1; dc <= 1; dc++) {
          if (dr === 0 && dc === 0) continue
          const r = row + dr
          const c = col + dc
          if (r >= 0 && r < this.rows && c >= 0 && c < this.cols && !this.board[r][c].revealed) {
            this.reveal(r, c)
            if (this.state === 'lost') return true
          }
        }
      }
      if (this.checkWin()) this.setState('won')
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
      if (this.flagCount === this.totalMines) {
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

  return GameEngine
})
