const NUMBER_COLORS = [
  '', '#0000FF', '#008000', '#FF0000', '#000080', '#800000', '#008080', '#000000', '#808080'
]

const FACE_STATES = {
  ready: '🙂',
  playing: '🙂',
  won: '😎',
  lost: '😵'
}

class UIRenderer {
  constructor(engine, audio, stats) {
    this.engine = engine
    this.audio = audio
    this.stats = stats
    this.container = document.getElementById('board')
    this.mineCounter = document.getElementById('mine-counter')
    this.timerDisplay = document.getElementById('timer')
    this.faceButton = document.getElementById('face-button')
    this.difficultyButtons = document.querySelectorAll('.diff-btn')
    this.chordPending = false
    this.chordTarget = null
    this.suppressClick = false
    this.prevState = 'ready'
    this.lastRows = 0
    this.lastCols = 0
    this.cellElements = []
    this.cursor = { row: 0, col: 0 }
    this.cursorVisible = false

    this.container.addEventListener('mousedown', (e) => {
      this.hideCursor()
      this.onMouseDown(e)
    })
    this.container.addEventListener('mouseup', (e) => this.onMouseUp(e))
  }

  onStateChange(newState) {
    if (this.prevState === newState) return
    if (newState === 'playing' && this.prevState === 'ready') {
      this.stats.onGameStart(this.engine.currentLevel)
    }
    if (newState === 'won') {
      this.engine.autoFlagRemainingMines()
      this.engine.revealAllSafe()
      this.stats.onGameWin(this.engine.timer)
    }
    if (newState === 'lost') {
      this.stats.onGameLose(this.engine.timer)
    }
    this.prevState = newState
  }

  render() {
    if (this.engine.rows !== this.lastRows || this.engine.cols !== this.lastCols) {
      this.renderAll()
    } else {
      for (let r = 0; r < this.engine.rows; r++) {
        for (let c = 0; c < this.engine.cols; c++) {
          this.updateCell(r, c)
        }
      }
    }
    this.highlightCursor()
    this.updateHUD()
    this.updateDifficultyButtons()
  }

  renderAll() {
    this.container.innerHTML = ''
    this.container.style.gridTemplateColumns = `repeat(${this.engine.cols}, 28px)`
    this.cellElements = Array.from({ length: this.engine.rows }, () => [])
    this.lastRows = this.engine.rows
    this.lastCols = this.engine.cols

    for (let r = 0; r < this.engine.rows; r++) {
      for (let c = 0; c < this.engine.cols; c++) {
        const cell = this.createCell(r, c)
        this.cellElements[r][c] = cell
        this.container.appendChild(cell)
      }
    }
  }

  createCell(r, c) {
    const cell = document.createElement('div')
    cell.className = 'cell'
    cell.dataset.row = r
    cell.dataset.col = c

    cell.addEventListener('click', () => this.onLeftClick(r, c))
    cell.addEventListener('contextmenu', (e) => {
      e.preventDefault()
      this.onRightClick(r, c)
    })
    cell.addEventListener('dblclick', () => this.onDoubleClick(r, c))

    this.syncCellElement(cell, r, c)
    return cell
  }

  updateCell(r, c) {
    const el = this.cellElements[r]?.[c]
    if (!el) return
    this.syncCellElement(el, r, c)
  }

  syncCellElement(el, r, c) {
    const data = this.engine.board[r][c]
    const classes = ['cell']
    let text = ''
    let color = ''

    if (data.revealed) {
      classes.push('revealed')
      if (data.mine) {
        classes.push('mine')
        if (data.exploded) classes.push('exploded')
        text = '💣'
      } else if (data.adjacentMines > 0) {
        classes.push('shown-number')
        color = NUMBER_COLORS[data.adjacentMines]
        text = data.adjacentMines
      }
    } else {
      classes.push('hidden')
      if (data.flagged) {
        classes.push('flagged')
        text = '🚩'
      } else if (data.questioned) {
        classes.push('questioned')
        text = '❓'
      }
    }

    const wasHidden = !el.classList.contains('revealed')
    el.className = classes.join(' ')
    el.style.color = color
    if (el.textContent !== String(text)) el.textContent = text
    if (wasHidden && data.revealed && !data.mine) {
      el.classList.add('reveal-anim')
      el.addEventListener('animationend', () => el.classList.remove('reveal-anim'), { once: true })
    }
  }

  setCursor(row, col) {
    this.cursorVisible = true
    this.clearCursor()
    if (row >= 0 && row < this.engine.rows && col >= 0 && col < this.engine.cols) {
      this.cursor = { row, col }
      const el = this.cellElements[row]?.[col]
      if (el) el.classList.add('cursor')
    }
  }

  clearCursor() {
    const el = this.cellElements[this.cursor.row]?.[this.cursor.col]
    if (el) el.classList.remove('cursor')
  }

  hideCursor() {
    this.cursorVisible = false
    this.clearCursor()
  }

  highlightCursor() {
    if (!this.cursorVisible) return
    const { row, col } = this.cursor
    if (row < 0 || row >= this.engine.rows || col < 0 || col >= this.engine.cols) return
    const el = this.cellElements[row]?.[col]
    if (el) el.classList.add('cursor')
  }

  onLeftClick(row, col) {
    if (this.suppressClick) {
      this.suppressClick = false
      return
    }
    const eng = this.engine
    if (eng.state === 'won' || eng.state === 'lost') return
    if (eng.state === 'ready' || eng.state === 'playing') {
      eng.reveal(row, col)
      this.onStateChange(eng.state)
      if (eng.state === 'lost') this.audio.explosion()
      else if (eng.state === 'won') this.audio.win()
      else this.audio.click()
      this.render()
    }
  }

  onRightClick(row, col) {
    if (this.suppressClick) {
      this.suppressClick = false
      return
    }
    const eng = this.engine
    if (eng.state === 'won' || eng.state === 'lost') return
    if (eng.state === 'ready' || eng.state === 'playing') {
      eng.toggleFlag(row, col)
      this.onStateChange(eng.state)
      if (eng.state === 'won') this.audio.win()
      else this.audio.flag()
      this.render()
    }
  }

  onMouseDown(e) {
    const cell = e.target.closest('.cell')
    if (!cell) return
    if (e.buttons === 3) {
      e.preventDefault()
      this.chordPending = true
      const row = parseInt(cell.dataset.row)
      const col = parseInt(cell.dataset.col)
      this.chordTarget = { row, col }
      this.highlightNeighbors(row, col, true)
    }
  }

  onMouseUp(e) {
    if (!this.chordPending) return
    e.preventDefault()
    const cell = e.target.closest('.cell')
    const target = this.chordTarget
    this.highlightNeighbors(target.row, target.col, false)
    this.chordPending = false
    this.chordTarget = null
    this.suppressClick = true
    if (cell) {
      const row = parseInt(cell.dataset.row)
      const col = parseInt(cell.dataset.col)
      if (row === target.row && col === target.col) {
        this.doChord(row, col)
      }
    }
  }

  doChord(row, col) {
    const eng = this.engine
    if (eng.state === 'playing') {
      const didChord = eng.chord(row, col)
      this.onStateChange(eng.state)
      this.render()
      if (eng.state === 'won') {
        this.audio.win()
      } else if (eng.state === 'lost') {
        this.audio.explosion()
      } else if (didChord) {
        this.audio.chordSuccess()
      } else {
        this.audio.chordFail()
        this.animateFailedChord(row, col)
      }
    }
  }

  highlightNeighbors(row, col, pressed) {
    for (let dr = -1; dr <= 1; dr++) {
      for (let dc = -1; dc <= 1; dc++) {
        const r = row + dr, c = col + dc
        if (r >= 0 && r < this.engine.rows && c >= 0 && c < this.engine.cols) {
          const el = this.cellElements[r]?.[c]
          if (el) {
            if (pressed) el.classList.add('pressed')
            else el.classList.remove('pressed')
          }
        }
      }
    }
  }

  onDoubleClick(row, col) {
    this.doChord(row, col)
  }

  animateFailedChord(row, col) {
    const cell = this.engine.board[row][col]
    if (!cell.revealed || cell.adjacentMines === 0) return
    for (let dr = -1; dr <= 1; dr++) {
      for (let dc = -1; dc <= 1; dc++) {
        if (dr === 0 && dc === 0) continue
        const r = row + dr, c = col + dc
        if (r >= 0 && r < this.engine.rows && c >= 0 && c < this.engine.cols) {
          const neighbor = this.engine.board[r][c]
          if (!neighbor.revealed) {
            const el = this.cellElements[r]?.[c]
            if (el) {
              el.classList.add('shake')
              setTimeout(() => el.classList.remove('shake'), 350)
            }
          }
        }
      }
    }
  }

  setTheme(theme) {
    document.documentElement.setAttribute('data-theme', theme)
    localStorage.setItem('minesweeper_theme', theme)
  }

  updateHUD() {
    const remaining = this.engine.totalMines - this.engine.flagCount
    if (remaining < 0) {
      this.mineCounter.textContent = '-' + String(Math.abs(remaining)).padStart(2, '0')
    } else {
      this.mineCounter.textContent = String(remaining).padStart(3, '0')
    }
    this.timerDisplay.textContent = String(this.engine.timer).padStart(3, '0')
    this.faceButton.textContent = FACE_STATES[this.engine.state]
  }

  updateDifficultyButtons() {
    this.difficultyButtons.forEach(btn => {
      btn.classList.toggle('active', btn.dataset.level === this.engine.currentLevel)
    })
  }
}
