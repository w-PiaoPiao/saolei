/**
 * UI 渲染层：dirty 增量渲染、事件委托、SVG 表情、键盘光标、无障碍状态播报。
 * 依赖全局：CONFIG / I18N / UIHelpers / Neighbors(可选) / GameEngine 等（见 index.html 脚本顺序）。
 */
const NUMBER_COLORS = [
  '',
  '#0000FF',
  '#008000',
  '#FF0000',
  '#000080',
  '#800000',
  '#008080',
  '#000000',
  '#808080'
]

// 用 SVG 替代 emoji，避免跨平台渲染差异
const FACE_SVGS = {
  ready:
    '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="10.5" fill="#FFD23F" stroke="#8a6d1a" stroke-width="1.2"/><circle cx="8.8" cy="9.4" r="1.6" fill="#3a2c00"/><circle cx="15.2" cy="9.4" r="1.6" fill="#3a2c00"/><path d="M8.2 14.2 Q12 18.2 15.8 14.2" stroke="#3a2c00" stroke-width="1.7" fill="none" stroke-linecap="round"/></svg>',
  pressed:
    '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="10.5" fill="#FFD23F" stroke="#8a6d1a" stroke-width="1.2"/><circle cx="8.8" cy="9.4" r="1.6" fill="#3a2c00"/><circle cx="15.2" cy="9.4" r="1.6" fill="#3a2c00"/><circle cx="12" cy="14.8" r="2.4" fill="#3a2c00"/></svg>',
  won:
    '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="10.5" fill="#FFD23F" stroke="#8a6d1a" stroke-width="1.2"/><rect x="3.6" y="7.8" width="16.8" height="5" rx="2.4" fill="#20242b"/><rect x="3.6" y="7.8" width="5.6" height="5" rx="2.4" fill="#0b0d10"/><rect x="14.8" y="7.8" width="5.6" height="5" rx="2.4" fill="#0b0d10"/><path d="M8.2 15.6 Q12 19 15.8 15.6" stroke="#3a2c00" stroke-width="1.7" fill="none" stroke-linecap="round"/></svg>',
  lost:
    '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="10.5" fill="#FFD23F" stroke="#8a6d1a" stroke-width="1.2"/><path d="M7.2 7.6 L10.4 10.8 M10.4 7.6 L7.2 10.8" stroke="#3a2c00" stroke-width="1.7" stroke-linecap="round"/><path d="M13.6 7.6 L16.8 10.8 M16.8 7.6 L13.6 10.8" stroke="#3a2c00" stroke-width="1.7" stroke-linecap="round"/><path d="M8.6 16.8 Q12 14 15.4 16.8" stroke="#3a2c00" stroke-width="1.7" fill="none" stroke-linecap="round"/></svg>'
}

function t(key, vars) {
  try {
    return I18N.t(key, vars)
  } catch {
    return key
  }
}

function uiHelpers() {
  try {
    return globalThis.UIHelpers || {}
  } catch {
    return {}
  }
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
    this.noGuessWarning = document.getElementById('noguess-warning')
    this.generatingHint = document.getElementById('generating-hint')
    this.srStatus = document.getElementById('sr-status')
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
    this.lastNoGuessWarning = null

    // 无猜首点的异步生成：generating 期间忽略棋盘操作，generationSeq 用于作废过期结果
    this.generating = false
    this.generationSeq = 0
    this.generationTimer = null

    // 由 app.js 注入：对话框打开/窗口失焦时，生成结果不落地（M15）
    this.isInteractionBlocked = () => false

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
      if (this.suppressClick) {
        this.suppressClick = false
        return
      }
      const cell = e.target.closest('.cell')
      if (!cell) return
      this.onLeftClick(Number(cell.dataset.row), Number(cell.dataset.col))
    })
    this.container.addEventListener('contextmenu', (e) => {
      e.preventDefault()
      if (this.suppressClick) {
        this.suppressClick = false
        return
      }
      const cell = e.target.closest('.cell')
      if (!cell) return
      this.onRightClick(Number(cell.dataset.row), Number(cell.dataset.col))
    })
    this.container.addEventListener('dblclick', (e) => {
      const cell = e.target.closest('.cell')
      if (!cell) return
      this.onDoubleClick(Number(cell.dataset.row), Number(cell.dataset.col))
    })
    // 鼠标在棋盘外松开：清理和弦临时态，避免 pressed/和弦挂起
    window.addEventListener('mouseup', (e) => {
      if (this.chordPending) {
        const inside = e.target && e.target.closest && e.target.closest('#board')
        if (!inside) {
          this.cancelChord()
          this.suppressClick = false
        }
      }
      this.setFace(this.engine.state)
    })
    window.addEventListener('blur', () => this.resetTransientInput())
  }

  // ---------- 状态 / 统计 ----------

  /** 统计分桶 key（引擎 M6 契约：预设=level；自定义=custom:rowsxcols/mines） */
  statsBucketKey() {
    try {
      if (this.stats && typeof this.stats.bucketOf === 'function') {
        const bucket = this.stats.bucketOf(this.engine.currentLevel, this.engine.currentCustom || null)
        if (typeof bucket === 'string' && bucket) return bucket
      }
      if (this.stats && typeof this.stats.currentBucket === 'string' && this.stats.currentBucket) {
        return this.stats.currentBucket
      }
    } catch {}
    return this.engine.currentLevel
  }

  /** 真实毫秒（M7）：优先 engine.elapsedMs，旧引擎回落到秒*1000 */
  elapsedMs() {
    const ms = Number(this.engine.elapsedMs)
    if (Number.isFinite(ms) && ms >= 0) return ms
    return Math.max(0, Math.floor(Number(this.engine.timer) || 0) * 1000)
  }

  onStateChange(newState) {
    if (this.prevState === newState) return
    // ready → 任意终态/进行态都计入总局数：
    // 覆盖「一次操作直接 ready→won」的路径，避免胜率统计出现 >100% 的口径洞
    if (newState !== 'ready' && this.prevState === 'ready') {
      this.stats.onGameStart(this.engine.currentLevel, this.engine.currentCustom || null)
    }
    const bucket = this.statsBucketKey()
    const durationMs = this.elapsedMs()
    const timeSec = Math.floor(durationMs / 1000)
    const pausedMs = Number(this.engine.pausedMs) || 0
    const wonByFlags = this.engine.wonByFlags === true
    const meta = { durationMs, pausedMs, wonByFlags }

    if (newState === 'won') {
      // 破纪录判断必须在 onGameWin 刷新 bestTime 之前；含暂停的对局不参与最快纪录（L12）
      let prevBest = null
      try {
        prevBest = this.stats.bestTimeOf(bucket)
      } catch {
        prevBest = null
      }
      // bestTimeOf 的契约单位是毫秒（M7），故用 durationMs 比较，避免与秒混用
      const isRecord = pausedMs === 0 && (prevBest === null || durationMs < prevBest)
      this.engine.autoFlagRemainingMines()
      this.engine.revealAllSafe()
      this.stats.onGameWin(timeSec, this.engine.bbbv, meta)
      if (isRecord) this.announceRecord()
      this.announceStatus('sr.won', { time: this.formatDuration(timeSec) })
    }
    if (newState === 'lost') {
      this.stats.onGameLose(timeSec, meta)
      this.announceStatus('sr.lost')
    }
    this.prevState = newState
  }

  formatDuration(seconds) {
    const helpers = uiHelpers()
    if (typeof helpers.formatDuration === 'function') return helpers.formatDuration(seconds)
    const s = Math.max(0, Math.floor(Number(seconds) || 0))
    return String(Math.floor(s / 60)).padStart(2, '0') + ':' + String(s % 60).padStart(2, '0')
  }

  /** 胜利破纪录横幅（首次通关也算）：2.2s 淡出动画结束后自动移除 */
  announceRecord() {
    const cfg = CONFIG.LEVELS[this.engine.currentLevel]
    const label = cfg ? t('level.' + this.engine.currentLevel) : t('level.custom')
    const time = this.formatDuration(Math.floor(this.elapsedMs() / 1000))
    const el = document.createElement('div')
    el.className = 'record-banner'
    el.setAttribute('role', 'status')
    el.textContent = t('record.banner', { label, time })
    document.body.appendChild(el)
    el.addEventListener('animationend', () => el.remove())
    this.announceStatus('sr.newRecord')
  }

  /** 屏幕阅读器状态播报（M14）：HUD 数字不适合逐秒 aria-live，用单独状态区播报关键事件 */
  announceStatus(key, vars) {
    if (!this.srStatus) return
    const text = t(key, vars)
    try {
      this.srStatus.textContent = ''
      window.requestAnimationFrame(() => {
        this.srStatus.textContent = text
      })
    } catch {
      this.srStatus.textContent = text
    }
  }

  // ---------- 渲染 ----------

  render() {
    if (this.engine.rows !== this.lastRows || this.engine.cols !== this.lastCols) {
      this.renderAll()
    } else if (this.engine.dirty && this.engine.dirty.size > 0) {
      for (const key of this.engine.dirty) {
        const raw = String(key)
        const sep = raw.indexOf(',')
        if (sep === -1) continue
        const r = Number(raw.slice(0, sep))
        const c = Number(raw.slice(sep + 1))
        if (!Number.isInteger(r) || !Number.isInteger(c)) continue
        this.updateCell(r, c)
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
    this.container.setAttribute('aria-rowcount', String(this.engine.rows))
    this.container.setAttribute('aria-colcount', String(this.engine.cols))
    this.cellElements = Array.from({ length: this.engine.rows }, () => [])
    this.lastRows = this.engine.rows
    this.lastCols = this.engine.cols

    const fragment = document.createDocumentFragment()
    for (let r = 0; r < this.engine.rows; r++) {
      for (let c = 0; c < this.engine.cols; c++) {
        const cell = this.createCell(r, c)
        this.cellElements[r][c] = cell
        fragment.appendChild(cell)
      }
    }
    this.container.appendChild(fragment)
    if (this.engine.dirty) this.engine.dirty.clear()
    // H1：棋盘尺寸变化后把光标夹回合法范围（而不是让它越界后让按键静默失效）
    this.clampCursorToBoard()
  }

  createCell(r, c) {
    const cell = document.createElement('div')
    cell.className = 'cell hidden'
    cell.id = `cell-${r}-${c}`
    cell.dataset.row = r
    cell.dataset.col = c
    cell.setAttribute('role', 'gridcell')
    cell.setAttribute('aria-rowindex', String(r + 1))
    cell.setAttribute('aria-colindex', String(c + 1))
    this.syncCellElement(cell, r, c)
    return cell
  }

  updateCell(r, c) {
    const el = this.cellElements[r] && this.cellElements[r][c]
    if (!el) return
    this.syncCellElement(el, r, c)
  }

  cellStateText(data) {
    if (data.revealed) {
      if (data.mine) return t('cell.mine')
      if (data.adjacentMines > 0) return t('cell.number', { n: data.adjacentMines })
      return t('cell.empty')
    }
    if (data.flagged) return data.wrongFlag ? t('cell.wrongFlag') : t('cell.flag')
    if (data.questioned) return t('cell.question')
    return t('cell.hidden')
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
        if (data.wrongFlag) classes.push('wrong-flag')
      } else if (data.questioned) {
        classes.push('questioned')
        text = '❓'
      }
    }

    const wasHidden = !el.classList.contains('revealed')
    el.className = classes.join(' ')
    el.style.color = color
    el.setAttribute('aria-label', t('cell.aria', { row: r + 1, col: c + 1, state: this.cellStateText(data) }))
    if (el.textContent !== String(text)) el.textContent = text
    if (wasHidden && data.revealed && !data.mine) {
      el.classList.add('reveal-anim')
      el.addEventListener('animationend', () => el.classList.remove('reveal-anim'), { once: true })
    }
  }

  /** 语言切换后刷新全部 aria 文案（M20/M14） */
  refreshI18n() {
    for (let r = 0; r < this.engine.rows; r++) {
      for (let c = 0; c < this.engine.cols; c++) {
        const el = this.cellElements[r] && this.cellElements[r][c]
        if (!el) continue
        const data = this.engine.board[r][c]
        el.setAttribute('aria-label', t('cell.aria', { row: r + 1, col: c + 1, state: this.cellStateText(data) }))
      }
    }
    this.faceButton.setAttribute('aria-label', t('hud.faceNewGame'))
    this.container.setAttribute('aria-label', t('board.ariaLabel'))
    this.updateHUD()
    this.updateDifficultyButtons()
  }

  // ---------- 键盘光标（H1/M13/M14） ----------

  clampCursorToBoard() {
    const helpers = uiHelpers()
    const clamp =
      typeof helpers.clampCursor === 'function'
        ? helpers.clampCursor
        : (row, col, rows, cols) => ({
            row: Math.max(0, Math.min(rows - 1, Math.floor(Number(row) || 0))),
            col: Math.max(0, Math.min(cols - 1, Math.floor(Number(col) || 0)))
          })
    const next = clamp(this.cursor.row, this.cursor.col, this.engine.rows, this.engine.cols)
    const changed = next.row !== this.cursor.row || next.col !== this.cursor.col
    this.cursor = next
    if (changed && this.cursorVisible) this.highlightCursor()
    return changed
  }

  setCursor(row, col) {
    this.cursorVisible = true
    this.clearCursor()
    const helpers = uiHelpers()
    const clamp = typeof helpers.clampCursor === 'function' ? helpers.clampCursor : (r, c) => ({ row: r, col: c })
    const next = clamp(row, col, this.engine.rows, this.engine.cols)
    this.cursor = { row: next.row, col: next.col }
    const el = this.cellElements[next.row] && this.cellElements[next.row][next.col]
    if (el) {
      el.classList.add('cursor')
      this.container.setAttribute('aria-activedescendant', el.id)
      // M13：大棋盘（#app 可滚动）下光标移出视口时自动跟随滚动
      try {
        el.scrollIntoView({ block: 'nearest', inline: 'nearest' })
      } catch {}
      try {
        this.container.focus({ preventScroll: true })
      } catch {}
    }
  }

  clearCursor() {
    const el = this.cellElements[this.cursor.row] && this.cellElements[this.cursor.row][this.cursor.col]
    if (el) el.classList.remove('cursor')
  }

  hideCursor() {
    this.cursorVisible = false
    this.clearCursor()
    try {
      this.container.removeAttribute('aria-activedescendant')
    } catch {}
  }

  highlightCursor() {
    if (!this.cursorVisible) return
    this.clampCursorToBoard()
    const { row, col } = this.cursor
    if (row < 0 || row >= this.engine.rows || col < 0 || col >= this.engine.cols) return
    const el = this.cellElements[row] && this.cellElements[row][col]
    if (el) {
      el.classList.add('cursor')
      try {
        this.container.setAttribute('aria-activedescendant', el.id)
      } catch {}
    }
  }

  // ---------- 操作入口 ----------

  /** H2：先插旗（state=playing）再首点也必须走异步生成，不能退回同步求解冻结 UI */
  canGenerateAsync() {
    return !!(
      this.engine.noGuess &&
      !this.engine.minesPlaced &&
      (this.engine.state === 'ready' || this.engine.state === 'playing') &&
      window.electronAPI &&
      typeof window.electronAPI.generateMines === 'function'
    )
  }

  /** 作废进行中的生成（新游戏/切难度/打开对话框/失焦时调用），避免过期结果落到新局上 */
  cancelGeneration() {
    if (!this.generating) return
    this.generationSeq++
    this.generating = false
    if (this.generationTimer) {
      clearTimeout(this.generationTimer)
      this.generationTimer = null
    }
    this.setGeneratingHint(false)
    if (window.electronAPI && typeof window.electronAPI.cancelMinesGeneration === 'function') {
      try {
        window.electronAPI.cancelMinesGeneration()
      } catch {}
    }
  }

  setGeneratingHint(on) {
    if (!this.generatingHint) return
    this.generatingHint.hidden = !on
  }

  /**
   * 无猜首点：把重生成循环交给主进程的工作线程，渲染进程保持可交互。
   * 生成失败/超时（M15）时回退到同步小预算路径，保证功能始终可用。
   */
  startAsyncReveal(row, col) {
    this.generating = true
    this.generationSeq++
    const seq = this.generationSeq
    this.setGeneratingHint(true)

    const clearGenerationTimer = () => {
      if (this.generationTimer) {
        clearTimeout(this.generationTimer)
        this.generationTimer = null
      }
    }

    const applyPlan = (plan) => {
      if (seq !== this.generationSeq) return
      clearGenerationTimer()
      this.generating = false
      this.setGeneratingHint(false)
      // M15：对话框打开 / 窗口失焦期间生成完成 → 不落地（用户回到棋盘后重新点击即可）
      if (this.isInteractionBlocked()) return
      let applied = false
      try {
        applied = this.engine.applyPlanAndReveal(row, col, plan) !== false
      } catch {
        applied = false
      }
      if (!applied) {
        this.onLeftClickSync(row, col)
        return
      }
      this.onStateChange(this.engine.state)
      if (this.engine.state === 'won') this.audio.win()
      else this.audio.click()
      this.render()
      this.announceStatus('sr.generated')
    }

    const fallbackSync = () => {
      if (seq !== this.generationSeq) return
      clearGenerationTimer()
      this.generating = false
      this.setGeneratingHint(false)
      if (this.isInteractionBlocked()) return
      this.onLeftClickSync(row, col)
    }

    // M15：渲染层兜底超时（主进程 2.5s 已有超时，这里再兜 3s 防 IPC 异常）
    this.generationTimer = setTimeout(fallbackSync, 3000)

    window.electronAPI
      .generateMines({
        rows: this.engine.rows,
        cols: this.engine.cols,
        mines: this.engine.totalMines,
        safeRow: row,
        safeCol: col
      })
      .then((res) => {
        if (res && res.ok) applyPlan({ mines: res.mines, noGuessFallback: res.noGuessFallback })
        else fallbackSync()
      })
      .catch(fallbackSync)
  }

  /** 同步翻开的无猜路径给一个小预算，避免回退时长时间冻结主线程（H2） */
  revealSync(row, col) {
    const eng = this.engine
    const useSmallBudget = eng.noGuess && !eng.minesPlaced
    if (useSmallBudget && typeof eng.reveal === 'function' && eng.reveal.length >= 3) {
      try {
        eng.reveal(row, col, { budgetMs: 50, maxAttempts: 8 })
        return
      } catch {}
    }
    eng.reveal(row, col)
  }

  onLeftClick(row, col) {
    if (this.generating) return
    if (this.canGenerateAsync()) {
      this.startAsyncReveal(row, col)
      return
    }
    this.onLeftClickSync(row, col)
  }

  onLeftClickSync(row, col) {
    const eng = this.engine
    if (eng.state === 'won' || eng.state === 'lost') return
    if (eng.state === 'ready' || eng.state === 'playing') {
      this.revealSync(row, col)
      this.onStateChange(eng.state)
      if (eng.state === 'lost') this.audio.explosion()
      else if (eng.state === 'won') this.audio.win()
      else this.audio.click()
      this.render()
    }
  }

  onRightClick(row, col) {
    if (this.generating) return
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
    // 和弦触发：左右键同时按住（buttons===3）或中键单独按下
    if (e.buttons === 3 || e.button === 1) {
      e.preventDefault()
      this.chordPending = true
      this.chordTarget = {
        row: Number(cell.dataset.row),
        col: Number(cell.dataset.col)
      }
      this.highlightNeighbors(this.chordTarget.row, this.chordTarget.col, true)
    }
  }

  cancelChord() {
    if (this.chordTarget) this.highlightNeighbors(this.chordTarget.row, this.chordTarget.col, false)
    this.chordPending = false
    this.chordTarget = null
  }

  onMouseUp(e) {
    if (!this.chordPending) return
    e.preventDefault()
    const cell = e.target.closest('.cell')
    const target = this.chordTarget
    if (target) this.highlightNeighbors(target.row, target.col, false)
    this.chordPending = false
    this.chordTarget = null
    this.suppressClick = true
    if (cell && target) {
      const row = Number(cell.dataset.row)
      const col = Number(cell.dataset.col)
      if (row === target.row && col === target.col) this.doChord(row, col)
    }
  }

  /** 窗口失焦时清理临时输入态，避免 pressed/和弦挂起 */
  resetTransientInput() {
    this.cancelChord()
    this.suppressClick = false
    this.setFace(this.engine.state)
  }

  doChord(row, col) {
    if (this.generating) return
    const eng = this.engine
    if (eng.state !== 'playing') return
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

  /** 8 邻域遍历：优先用 engine 侧共享实现（L15），无则本地兜底 */
  forEachNeighbor(row, col, fn) {
    const N = globalThis.Neighbors
    if (N && typeof N.forEachNeighbor === 'function') {
      N.forEachNeighbor(this.engine.rows, this.engine.cols, row, col, fn)
      return
    }
    for (let dr = -1; dr <= 1; dr++) {
      for (let dc = -1; dc <= 1; dc++) {
        if (dr === 0 && dc === 0) continue
        const r = row + dr
        const c = col + dc
        if (r >= 0 && r < this.engine.rows && c >= 0 && c < this.engine.cols) fn(r, c)
      }
    }
  }

  highlightNeighbors(row, col, pressed) {
    this.forEachNeighbor(row, col, (r, c) => {
      const el = this.cellElements[r] && this.cellElements[r][c]
      if (!el) return
      if (pressed) el.classList.add('pressed')
      else el.classList.remove('pressed')
    })
  }

  onDoubleClick(row, col) {
    this.doChord(row, col)
  }

  animateFailedChord(row, col) {
    const cell = this.engine.board[row][col]
    if (!cell || !cell.revealed || cell.adjacentMines === 0) return
    this.forEachNeighbor(row, col, (r, c) => {
      const neighbor = this.engine.board[r][c]
      if (!neighbor || neighbor.revealed) return
      const el = this.cellElements[r] && this.cellElements[r][c]
      if (!el) return
      el.classList.add('shake')
      setTimeout(() => el.classList.remove('shake'), 350)
    })
  }

  // ---------- 主题 / HUD ----------

  /**
   * @param {boolean} persist false 时不写偏好：用于"用户未选过主题"时的系统深浅色跟随，
   *   这样系统切换深浅色可以继续生效；用户显式选过主题后固定不变。
   */
  setTheme(theme, persist = true) {
    document.documentElement.setAttribute('data-theme', theme)
    if (persist) localStorage.setItem(CONFIG.STORAGE_KEYS.theme, theme)
  }

  setFace(state) {
    if (this.lastFaceState === state) return
    this.faceButton.innerHTML = FACE_SVGS[state] || FACE_SVGS.ready
    this.lastFaceState = state
  }

  updateHUD() {
    const helpers = uiHelpers()
    const counter =
      typeof helpers.formatCounter === 'function'
        ? helpers.formatCounter(this.engine.totalMines - this.engine.flagCount)
        : String(this.engine.totalMines - this.engine.flagCount).padStart(3, '0')
    this.mineCounter.textContent = counter
    if (counter.length > 3) this.mineCounter.classList.add('led-wide')
    else this.mineCounter.classList.remove('led-wide')
    this.mineCounter.setAttribute('aria-label', t('hud.mineCounterAria', { count: counter }))

    const led = typeof helpers.formatLedSeconds === 'function' ? helpers.formatLedSeconds(this.engine.timer) : String(this.engine.timer)
    this.timerDisplay.textContent = led
    this.timerDisplay.setAttribute(
      'aria-label',
      t('hud.timerAria', { time: this.formatDuration(Math.floor(this.elapsedMs() / 1000)) })
    )
    this.setFace(this.engine.state)
    this.updateNoGuessWarning()
  }

  /** 无猜模式未能在预算内找到可解雷局时，明确告知玩家（否则会误以为本局保证无猜） */
  updateNoGuessWarning() {
    if (!this.noGuessWarning) return
    const show = this.engine.noGuessFallback === true
    if (show === this.lastNoGuessWarning) return
    this.lastNoGuessWarning = show
    this.noGuessWarning.hidden = !show
    if (show) this.announceStatus('sr.generateFallback')
  }

  updateDifficultyButtons() {
    this.difficultyButtons.forEach((btn) => {
      const active = btn.dataset.level === this.engine.currentLevel
      btn.classList.toggle('active', active)
      btn.setAttribute('aria-pressed', active ? 'true' : 'false')
    })
  }
}

if (typeof module === 'object' && module.exports) {
  module.exports = UIRenderer
}
