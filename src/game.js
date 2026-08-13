/**
 * 扫雷游戏引擎：纯逻辑、无 DOM 依赖。
 * 同时支持浏览器（全局 GameEngine）与 Node 测试（module.exports）。
 */
;(function (root, factory) {
  if (typeof module === 'object' && module.exports) {
    module.exports = factory(require('./config.js'))
  } else {
    root.GameEngine = factory(root.CONFIG)
  }
})(typeof self !== 'undefined' ? self : this, function (CONFIG) {
  class GameEngine {
    constructor(level = 'beginner') {
      this.levels = CONFIG.LEVELS
      this.stateListeners = []
      this.dirty = new Set()
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
          exploded: false
        }))
      )
    }

    // ---------- 布雷 ----------

    /**
     * 在安全格首次翻开后布雷：
     * - 安全格周围 3×3 永不布雷（保证首次点击绝对安全）
     * - Fisher-Yates 洗牌，O(n) 且无拒绝采样退化
     */
    placeMines(safeRow, safeCol) {
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
      this.minesPlaced = true
      this.markAllDirty()
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
          if (this.board[r][c].mine) this.board[r][c].revealed = true
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
