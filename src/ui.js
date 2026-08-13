/**
 * UI 渲染层：增量渲染（脏格子驱动）、事件委托、SVG 表情、键盘光标。
 */
const NUMBER_COLORS = [
  '', '#0000FF', '#008000', '#FF0000', '#000080', '#800000', '#008080', '#000000', '#808080'
]

// 用 SVG 替代 emoji，避免跨平台渲染差异
const FACE_SVGS = {
  ready: '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="10.5" fill="#FFD23F" stroke="#8a6d1a" stroke-width="1.2"/><circle cx="8.8" cy="9.4" r="1.6" fill="#3a2c00"/><circle cx="15.2" cy="9.4" r="1.6" fill="#3a2c00"/><path d="M8.2 14.2 Q12 18.2 15.8 14.2" stroke="#3a2c00" stroke-width="1.7" fill="none" stroke-linecap="round"/></svg>',
  pressed: '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="10.5" fill="#FFD23F" stroke="#8a6d1a" stroke-width="1.2"/><circle cx="8.8" cy="9.4" r="1.6" fill="#3a2c00"/><circle cx="15.2" cy="9.4" r="1.6" fill="#3a2c00"/><circle cx="12" cy="14.8" r="2.4" fill="#3a2c00"/></svg>',
  won: '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="10.5" fill="#FFD23F" stroke="#8a6d1a" stroke-width="1.2"/><rect x="3.6" y="7.8" width="16.8" height="5" rx="2.4" fill="#20242b"/><rect x="3.6" y="7.8" width="5.6" height="5" rx="2.4" fill="#0b0d10"/><rect x="14.8" y="7.8" width="5.6" height="5" rx="2.4" fill="#0b0d10"/><path d="M8.2 15.6 Q12 19 15.8 15.6" stroke="#3a2c00" stroke-width="1.7" fill="none" stroke-linecap="round"/></svg>',
  lost: '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="10.5" fill="#FFD23F" stroke="#8a6d1a" stroke-width="1.2"/><path d="M7.2 7.6 L10.4 10.8 M10.4 7.6 L7.2 10.8" stroke="#3a2c00" stroke-width="1.7" stroke-linecap="round"/><path d="M13.6 7.6 L16.8 10.8 M16.8 7.6 L13.6 10.8" stroke="#3a2c00" stroke-width="1.7" stroke-linecap="round"/><path d="M8.6 16.8 Q12 14 15.4 16.8" stroke="#3a2c00" stroke-width="1.7" fill="none" stroke-linecap="round"/></svg>'
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
    this.lastFaceState = null
    this.cellElements = []
    this.cursor = { row: 0, col: 0 }
    this.cursorVisible = false

    // 事件委托：棋盘容器统一处理，避免每格 3 个监听器
    this.container.addEventListener('mousedown', (e) => {
      this.hideCursor()
      if (e.button === 0 && (this.engine.state === 'ready' || this.engine.state === 'playing')) {
        this.setFace('pressed')
      }
      this.onMouseDown(e)
    })
    this.container.addEventListener('mouseup', (e) => {
      this.setFace(this.engine.state)
      this.onMouseUp(e)
    })
    this.container.addEventListener('click', (e) => {
      const cell = e.target.closest('.cell')
      if (!cell) return
      this.onLeftClick(Number(cell.dataset.row), Number(cell.dataset.col))
    })
    this.container.addEventListener('contextmenu', (e) => {
      const cell = e.target.closest('.cell')
      if (!cell) return
      e.preventDefault()
      this.onRightClick(Number(cell.dataset.row), Number(cell.dataset.col))
    })
    this.container.addEventListener('dblclick', (e) => {
      const cell = e.target.closest('.cell')
      if (!cell) return
      this.onDoubleClick(Number(cell.dataset.row), Number(cell.dataset.col))
    })
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

  // ---------- 渲染 ----------

  render() {
    if (this.engine.rows !== this.lastRows || this.engine.cols !== this.lastCols) {
      this.renderAll()
    } else if (this.engine.dirty.size > 0) {
      for (const key of this.engine.dirty) {
        const sep = key.indexOf(',')
        this.updateCell(Number(key.slice(0, sep)), Number(key.slice(sep + 1)))
      }
      this.engine.dirty.clear()
    }
    this.highlightCursor()
    this.updateHUD()
    this.updateDifficultyButtons()
  }

  renderAll() {
    this.container.innerHTML = ''
    this.container.style.gridTemplateColumns = `repeat(${this.engine.cols}, var(--cell-size))`
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
    this.engine.dirty.clear()
  }

  createCell(r, c) {
    const cell = document.createElement('div')
    cell.className = 'cell'
    cell.dataset.row = r
    cell.dataset.col = c
    cell.setAttribute('role', 'gridcell')
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
    let label = '未翻开'

    if (data.revealed) {
      classes.push('revealed')
      if (data.mine) {
        classes.push('mine')
        if (data.exploded) classes.push('exploded')
        text = '💣'
        label = '地雷'
      } else if (data.adjacentMines > 0) {
        classes.push('shown-number')
        color = NUMBER_COLORS[data.adjacentMines]
        text = data.adjacentMines
        label = '数字 ' + data.adjacentMines
      } else {
        label = '空白'
      }
    } else {
      classes.push('hidden')
      if (data.flagged) {
        classes.push('flagged')
        text = '🚩'
        label = '已插旗'
      } else if (data.questioned) {
        classes.push('questioned')
        text = '❓'
        label = '问号'
      }
    }

    const wasHidden = !el.classList.contains('revealed')
    el.className = classes.join(' ')
    el.style.color = color
    el.setAttribute('aria-label', `第 ${r + 1} 行 第 ${c + 1} 列，${label}`)
    if (el.textContent !== String(text)) el.textContent = text
    if (wasHidden && data.revealed && !data.mine) {
      el.classList.add('reveal-anim')
      el.addEventListener('animationend', () => el.classList.remove('reveal-anim'), { once: true })
    }
  }

  // ---------- 键盘光标 ----------

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

  // ---------- 操作入口 ----------

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
      this.chordTarget = {
        row: Number(cell.dataset.row),
        col: Number(cell.dataset.col)
      }
      this.highlightNeighbors(this.chordTarget.row, this.chordTarget.col, true)
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
      const row = Number(cell.dataset.row)
      const col = Number(cell.dataset.col)
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
        const r = row + dr
        const c = col + dc
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
        const r = row + dr
        const c = col + dc
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

  // ---------- 主题 / HUD ----------

  setTheme(theme) {
    document.documentElement.setAttribute('data-theme', theme)
    localStorage.setItem(CONFIG.STORAGE_KEYS.theme, theme)
  }

  setFace(state) {
    if (this.lastFaceState === state) return
    this.faceButton.innerHTML = FACE_SVGS[state] || FACE_SVGS.ready
    this.lastFaceState = state
  }

  updateHUD() {
    const remaining = this.engine.totalMines - this.engine.flagCount
    if (remaining < 0) {
      this.mineCounter.textContent = '-' + String(Math.abs(remaining)).padStart(2, '0')
    } else {
      this.mineCounter.textContent = String(remaining).padStart(3, '0')
    }
    this.timerDisplay.textContent = String(this.engine.timer).padStart(3, '0')
    this.setFace(this.engine.state)
  }

  updateDifficultyButtons() {
    this.difficultyButtons.forEach(btn => {
      btn.classList.toggle('active', btn.dataset.level === this.engine.currentLevel)
    })
  }
}
