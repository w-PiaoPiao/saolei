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
  constructor(engine) {
    this.engine = engine
    this.container = document.getElementById('board')
    this.mineCounter = document.getElementById('mine-counter')
    this.timerDisplay = document.getElementById('timer')
    this.faceButton = document.getElementById('face-button')
    this.difficultyButtons = document.querySelectorAll('.diff-btn')
    this.chordPending = false
    this.chordTarget = null
    this.suppressClick = false

    this.container.addEventListener('mousedown', (e) => this.onMouseDown(e))
    this.container.addEventListener('mouseup', (e) => this.onMouseUp(e))
  }

  render() {
    this.container.innerHTML = ''
    this.container.style.gridTemplateColumns = `repeat(${this.engine.cols}, 28px)`

    for (let r = 0; r < this.engine.rows; r++) {
      for (let c = 0; c < this.engine.cols; c++) {
        const cell = document.createElement('div')
        cell.className = 'cell'
        cell.dataset.row = r
        cell.dataset.col = c

        const data = this.engine.board[r][c]
        if (data.revealed) {
          cell.classList.add('revealed')
          if (data.mine) {
            cell.classList.add('mine')
            if (data.exploded) cell.classList.add('exploded')
            cell.textContent = '💣'
          } else if (data.adjacentMines > 0) {
            cell.style.color = NUMBER_COLORS[data.adjacentMines]
            cell.textContent = data.adjacentMines
          }
        } else {
          cell.classList.add('hidden')
          if (data.flagged) {
            cell.classList.add('flagged')
            cell.textContent = '🚩'
          } else if (data.questioned) {
            cell.classList.add('questioned')
            cell.textContent = '❓'
          }
        }

        cell.addEventListener('click', () => this.onLeftClick(r, c))
        cell.addEventListener('contextmenu', (e) => {
          e.preventDefault()
          this.onRightClick(r, c)
        })
        cell.addEventListener('dblclick', () => this.onDoubleClick(r, c))

        this.container.appendChild(cell)
      }
    }

    this.updateHUD()
    this.updateDifficultyButtons()
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
        const eng = this.engine
        if (eng.state === 'playing') {
          const didChord = eng.chord(row, col)
          this.render()
          if (!didChord) this.animateFailedChord(row, col)
        }
      }
    }
  }

  highlightNeighbors(row, col, pressed) {
    for (let dr = -1; dr <= 1; dr++) {
      for (let dc = -1; dc <= 1; dc++) {
        const r = row + dr, c = col + dc
        if (r >= 0 && r < this.engine.rows && c >= 0 && c < this.engine.cols) {
          const el = this.container.querySelector(`[data-row="${r}"][data-col="${c}"]`)
          if (el) {
            if (pressed) el.classList.add('pressed')
            else el.classList.remove('pressed')
          }
        }
      }
    }
  }

  onDoubleClick(row, col) {
    const eng = this.engine
    if (eng.state === 'won' || eng.state === 'lost') return
    if (eng.state === 'playing') {
      const didChord = eng.chord(row, col)
      this.render()
      if (!didChord) this.animateFailedChord(row, col)
    }
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
            const el = this.container.querySelector(`[data-row="${r}"][data-col="${c}"]`)
            if (el) {
              el.classList.add('shake')
              setTimeout(() => el.classList.remove('shake'), 350)
            }
          }
        }
      }
    }
  }

  updateHUD() {
    const remaining = this.engine.totalMines - this.engine.flagCount
    this.mineCounter.textContent = String(Math.max(remaining, 0)).padStart(3, '0')
    this.timerDisplay.textContent = String(this.engine.timer).padStart(3, '0')
    this.faceButton.textContent = FACE_STATES[this.engine.state]
  }

  updateDifficultyButtons() {
    this.difficultyButtons.forEach(btn => {
      btn.classList.toggle('active', btn.dataset.level === this.engine.currentLevel)
    })
  }
}
