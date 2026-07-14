class GameEngine {
  constructor() {
    this.levels = {
      beginner: { rows: 9, cols: 9, mines: 10 },
      intermediate: { rows: 16, cols: 16, mines: 40 },
      expert: { rows: 16, cols: 30, mines: 99 }
    }
    this.currentLevel = 'beginner'
    this.currentCustom = null
    this.init('beginner')
  }

  init(level) {
    const cfg = this.levels[level]
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

  placeMines(safeRow, safeCol) {
    let placed = 0
    while (placed < this.totalMines) {
      const r = Math.floor(Math.random() * this.rows)
      const c = Math.floor(Math.random() * this.cols)
      if (this.board[r][c].mine) continue
      if (Math.abs(r - safeRow) <= 1 && Math.abs(c - safeCol) <= 1) continue
      this.board[r][c].mine = true
      placed++
    }
    for (let r = 0; r < this.rows; r++) {
      for (let c = 0; c < this.cols; c++) {
        if (this.board[r][c].mine) continue
        this.board[r][c].adjacentMines = this.countAdjacentMines(r, c)
      }
    }
    this.minesPlaced = true

    while (this.countAdjacentMines(safeRow, safeCol) !== 0) {
      this.relocateClosestMine(safeRow, safeCol)
    }
  }

  relocateClosestMine(safeRow, safeCol) {
    for (let dr = -1; dr <= 1; dr++) {
      for (let dc = -1; dc <= 1; dc++) {
        const r = safeRow + dr, c = safeCol + dc
        if (r < 0 || r >= this.rows || c < 0 || c >= this.cols) continue
        if (!this.board[r][c].mine) continue
        for (let tr = 0; tr < this.rows; tr++) {
          for (let tc = 0; tc < this.cols; tc++) {
            if (this.board[tr][tc].mine) continue
            if (Math.abs(tr - safeRow) > 2 || Math.abs(tc - safeCol) > 2) {
              this.board[r][c].mine = false
              this.board[tr][tc].mine = true
              this.recalcMines()
              return
            }
          }
        }
      }
    }
  }

  recalcMines() {
    for (let r = 0; r < this.rows; r++) {
      for (let c = 0; c < this.cols; c++) {
        if (!this.board[r][c].mine) {
          this.board[r][c].adjacentMines = this.countAdjacentMines(r, c)
        }
      }
    }
  }

  countAdjacentMines(row, col) {
    let count = 0
    for (let dr = -1; dr <= 1; dr++) {
      for (let dc = -1; dc <= 1; dc++) {
        if (dr === 0 && dc === 0) continue
        const r = row + dr, c = col + dc
        if (r >= 0 && r < this.rows && c >= 0 && c < this.cols && this.board[r][c].mine) count++
      }
    }
    return count
  }

  reveal(row, col) {
    const cell = this.board[row][col]
    if (cell.revealed || cell.flagged) return
    if (!this.minesPlaced) {
      this.placeMines(row, col)
      this.state = 'playing'
    }
    if (cell.mine) {
      cell.revealed = true
      cell.exploded = true
      this.state = 'lost'
      this.revealAllMines()
      return
    }
    this.floodFill(row, col)
    if (this.checkWin()) this.state = 'won'
  }

  floodFill(row, col) {
    const cell = this.board[row][col]
    if (cell.revealed || cell.flagged || cell.mine) return
    cell.revealed = true
    cell.flagged = false
    cell.questioned = false
    this.revealedCount++
    if (cell.adjacentMines === 0) {
      for (let dr = -1; dr <= 1; dr++) {
        for (let dc = -1; dc <= 1; dc++) {
          if (dr === 0 && dc === 0) continue
          const r = row + dr, c = col + dc
          if (r >= 0 && r < this.rows && c >= 0 && c < this.cols) {
            this.floodFill(r, c)
          }
        }
      }
    }
  }

  toggleFlag(row, col) {
    const cell = this.board[row][col]
    if (cell.revealed) return
    if (this.state === 'ready') this.state = 'playing'
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
    if (this.checkWin()) this.state = 'won'
  }

  chord(row, col) {
    const cell = this.board[row][col]
    if (!cell.revealed || cell.adjacentMines === 0) return false
    let adjFlags = 0
    for (let dr = -1; dr <= 1; dr++) {
      for (let dc = -1; dc <= 1; dc++) {
        if (dr === 0 && dc === 0) continue
        const r = row + dr, c = col + dc
        if (r >= 0 && r < this.rows && c >= 0 && c < this.cols && this.board[r][c].flagged) adjFlags++
      }
    }
    if (adjFlags !== cell.adjacentMines) return false
    for (let dr = -1; dr <= 1; dr++) {
      for (let dc = -1; dc <= 1; dc++) {
        if (dr === 0 && dc === 0) continue
        const r = row + dr, c = col + dc
        if (r >= 0 && r < this.rows && c >= 0 && c < this.cols && !this.board[r][c].revealed) {
          this.reveal(r, c)
          if (this.state === 'lost') return true
        }
      }
    }
    if (this.checkWin()) this.state = 'won'
    return true
  }

  revealAllMines() {
    for (let r = 0; r < this.rows; r++) {
      for (let c = 0; c < this.cols; c++) {
        if (this.board[r][c].mine) {
          this.board[r][c].revealed = true
        }
      }
    }
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
  }

  checkWin() {
    const totalSafe = this.rows * this.cols - this.totalMines
    return this.revealedCount >= totalSafe
  }
}
