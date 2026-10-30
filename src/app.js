/**
 * 主流程：i18n、计时器（真实毫秒 + 暂停累计）、窗口自适应、统一对话框、菜单动作、键盘操作、暂停/静音。
 *
 * 设计约束（对应 docs/review-2026-10-27/FIX-PLAN.md / 报告 ID）：
 * - H1：光标越界不会再让键盘静默失效（clamp 而非 return）
 * - M7/L13：终局先同步真实毫秒再记账；LED 显示封顶 999，统计用毫秒
 * - M9：统一 openDialog（role=dialog/焦点陷阱/Esc/小窗滚动/叠层/Esc 顶层/清键位录制）
 * - M20：I18N 驱动的静态/动态文案与系统语言检测
 * - M15：对话框/失焦时作废无猜生成，生成结果不落地
 */
;(function () {
  'use strict'

  // ---------- i18n ----------

  function t(key, vars) {
    try {
      return I18N.t(key, vars)
    } catch {
      return key
    }
  }

  function systemLanguage() {
    try {
      if (window.electronAPI && window.electronAPI.locale) return window.electronAPI.locale
    } catch {}
    try {
      return navigator.language || 'en'
    } catch {
      return 'en'
    }
  }

  I18N.setLanguage(I18N.loadPreference(), { systemLang: systemLanguage() })

  function applyDocumentLanguage() {
    document.documentElement.lang = I18N.getLanguage() === 'zh' ? 'zh-CN' : 'en'
    document.title = t('app.title')
  }

  /** index.html 中的静态文案（data-i18n / data-i18n-aria） */
  function applyStaticI18n() {
    applyDocumentLanguage()
    document.querySelectorAll('[data-i18n]').forEach((el) => {
      el.textContent = t(el.dataset.i18n)
    })
    document.querySelectorAll('[data-i18n-aria]').forEach((el) => {
      el.setAttribute('aria-label', t(el.dataset.i18nAria))
    })
    const face = document.getElementById('face-button')
    if (face) face.setAttribute('aria-label', t('hud.faceNewGame'))
    const boardEl = document.getElementById('board')
    if (boardEl) boardEl.setAttribute('aria-label', t('board.ariaLabel'))
  }

  // ---------- 实例 ----------

  const game = new GameEngine()
  const audio = new AudioManager()
  const stats = new StatsManager()
  const bindings = new KeyBindings()
  const ui = new UIRenderer(game, audio, stats)
  const helpers = globalThis.UIHelpers || {}
  const dialogStack = new (helpers.DialogStack || class {
    constructor() {
      this.entries = []
    }
    push(entry) {
      this.entries.push(entry)
      return entry
    }
    remove(id) {
      const i = this.entries.findIndex((e) => e.id === id)
      return i === -1 ? null : this.entries.splice(i, 1)[0]
    }
    top() {
      return this.entries[this.entries.length - 1] || null
    }
    size() {
      return this.entries.length
    }
    clear() {
      const all = this.entries.slice()
      this.entries.length = 0
      return all
    }
  })()

  const appRoot = document.getElementById('app')
  const pauseOverlay = document.getElementById('pause-overlay')

  let listeningForBind = null
  let dialogSeq = 0

  // ---------- 计时器（M7/L12/L13） ----------

  let timerInterval = null
  let timerBaseMs = 0
  let timerAnchorMs = null
  let pausedTotalMs = 0
  let pauseStartedAt = null
  const suspensions = new Set()

  function activeMs() {
    return timerBaseMs + (timerAnchorMs === null ? 0 : Date.now() - timerAnchorMs)
  }

  function syncTimer(force = false) {
    if (timerAnchorMs === null || game.state !== 'playing') return
    const seconds = Math.max(0, Math.floor(activeMs() / 1000))
    if (force || seconds !== game.timer) {
      game.timer = seconds
      ui.updateHUD()
    }
  }

  function startTimer() {
    if (timerAnchorMs !== null) return
    if (game.state !== 'playing') return
    if (dialogStack.size() > 0 || pauseOverlay.classList.contains('visible')) return
    timerAnchorMs = Date.now()
    if (!timerInterval) timerInterval = setInterval(() => syncTimer(), 250)
  }

  function stopTimerInterval() {
    if (timerInterval) {
      clearInterval(timerInterval)
      timerInterval = null
    }
  }

  /** 直接停表（不记暂停） */
  function stopTimer() {
    if (timerAnchorMs !== null) {
      timerBaseMs += Date.now() - timerAnchorMs
      timerAnchorMs = null
    }
    stopTimerInterval()
  }

  /** 多个挂起来源（对话框 / 失焦）任一存在即停表；全部解除才恢复 */
  function suspendTimer(reason) {
    if (suspensions.has(reason)) return
    if (suspensions.size === 0) {
      pauseStartedAt = Date.now()
      stopTimer()
    }
    suspensions.add(reason)
  }

  function resumeTimer(reason) {
    if (!suspensions.delete(reason)) return
    if (suspensions.size > 0) return
    if (pauseStartedAt !== null) {
      pausedTotalMs += Date.now() - pauseStartedAt
      pauseStartedAt = null
    }
    startTimer()
  }

  function pausedMsNow() {
    return pausedTotalMs + (pauseStartedAt === null ? 0 : Date.now() - pauseStartedAt)
  }

  /** 终局：先同步真实耗时（含毫秒）再停表，供统计/显示使用（M7/L13） */
  function finalizeTimer() {
    const durationMs = activeMs()
    stopTimer()
    stopTimerInterval()
    game.elapsedMs = durationMs
    game.pausedMs = pausedMsNow()
    game.timer = Math.max(0, Math.floor(durationMs / 1000))
    ui.updateHUD()
  }

  function resetTimerClock() {
    stopTimer()
    stopTimerInterval()
    timerBaseMs = 0
    timerAnchorMs = null
    pausedTotalMs = 0
    pauseStartedAt = null
    suspensions.clear()
    game.elapsedMs = 0
    game.pausedMs = 0
    game.timer = 0
  }

  // 引擎状态变化 → 计时器（先 sync 再 stop 的时序由 finalizeTimer 保证）
  game.onStateChange((state) => {
    if (state === 'playing') startTimer()
    if (state === 'won' || state === 'lost') finalizeTimer()
  })

  // ---------- 窗口自适应（M13） ----------

  function cellSizeFor() {
    const availWidth = Math.max(
      320,
      Math.min(CONFIG.WINDOW.boardMaxW, (window.screen && window.screen.availWidth ? window.screen.availWidth : 1280) - 80)
    )
    const availHeight = Math.max(
      240,
      Math.min(CONFIG.WINDOW.boardMaxH, (window.screen && window.screen.availHeight ? window.screen.availHeight : 900) - 220)
    )
    const byWidth = Math.floor(availWidth / game.cols)
    const byHeight = Math.floor(availHeight / game.rows)
    return Math.min(CONFIG.CELL_SIZE.max, Math.max(CONFIG.CELL_SIZE.min, Math.min(byWidth, byHeight)))
  }

  function fitWindow() {
    if (!window.electronAPI || typeof window.electronAPI.fitWindow !== 'function') return
    const cell = cellSizeFor()
    document.documentElement.style.setProperty('--cell-size', cell + 'px')
    window.electronAPI.fitWindow(game.cols * cell + CONFIG.WINDOW.boardPadX, game.rows * cell + CONFIG.WINDOW.boardPadY)
  }

  // ---------- 菜单状态同步工具 ----------

  function syncMenuDifficulty() {
    if (!window.electronAPI || typeof window.electronAPI.updateMenuDifficulty !== 'function') return
    try {
      window.electronAPI.updateMenuDifficulty(game.currentLevel)
    } catch {}
  }

  function syncMenuLanguage() {
    if (!window.electronAPI || typeof window.electronAPI.updateMenuLanguage !== 'function') return
    try {
      window.electronAPI.updateMenuLanguage(I18N.getPreference())
    } catch {}
  }

  function syncAllMenuState() {
    syncMenuDifficulty()
    if (window.electronAPI) {
      try {
        window.electronAPI.updateMenuTheme(document.documentElement.getAttribute('data-theme') || 'light')
      } catch {}
      try {
        window.electronAPI.updateMenuMute(!audio.enabled)
      } catch {}
      try {
        window.electronAPI.updateMenuNoGuess(game.noGuess)
      } catch {}
    }
    syncMenuLanguage()
  }

  // ---------- 语言切换（M20） ----------

  function setLanguage(pref) {
    const allowed = ['system', 'zh', 'en']
    const value = allowed.includes(pref) ? pref : 'system'
    I18N.setLanguage(value, { systemLang: systemLanguage(), persist: true })
    closeAllDialogs('language')
    applyStaticI18n()
    ui.refreshI18n()
    syncAllMenuState()
    // 菜单文案由主进程重建（update-menu-language → buildMenu）
    ui.announceStatus('sr.resumed')
  }

  // ---------- 对话框统一层（M9） ----------

  function dialogOpen() {
    return dialogStack.size() > 0
  }

  function setBackgroundInert(inert) {
    if (!appRoot) return
    if (inert) {
      appRoot.setAttribute('inert', '')
      appRoot.setAttribute('aria-hidden', 'true')
    } else {
      appRoot.removeAttribute('inert')
      appRoot.removeAttribute('aria-hidden')
    }
  }

  function cancelBindListening() {
    if (!listeningForBind) return
    listeningForBind = null
    document.querySelectorAll('.bind-key.listening').forEach((btn) => {
      btn.classList.remove('listening')
      try {
        btn.textContent = bindings.getDisplayKey(btn.dataset.action)
      } catch {}
    })
  }

  /**
   * 统一对话框：
   * - role=dialog / aria-modal / aria-labelledby，背景 inert
   * - 打开新层先关旧层；Esc 关栈顶；Tab 焦点陷阱；关闭恢复焦点
   * - 第一次打开挂起计时，最后一层关闭恢复（仅 playing 且无暂停遮罩）
   * - 关闭时清理键位录制状态（M9/DU-7）
   */
  function openDialog(config) {
    closeAllDialogs('replace')
    const id = 'dialog-' + ++dialogSeq
    const titleId = id + '-title'
    const content = config.contentHtml || ''
    const overlay = document.createElement('div')
    overlay.className = 'dialog-overlay'
    const dialog = document.createElement('div')
    dialog.className = 'dialog' + (config.className ? ' ' + config.className : '')
    dialog.setAttribute('role', 'dialog')
    dialog.setAttribute('aria-modal', 'true')
    dialog.setAttribute('aria-labelledby', titleId)
    const heading = document.createElement('h2')
    heading.id = titleId
    heading.textContent = config.title || ''
    dialog.appendChild(heading)
    const body = document.createElement('div')
    body.innerHTML = content
    dialog.appendChild(body)
    overlay.appendChild(dialog)
    document.body.appendChild(overlay)

    const previousFocus = document.activeElement
    let closed = false
    const entry = {
      id,
      overlay,
      dialog,
      close: (reason = 'cancel') => closeDialog(entry, reason)
    }
    const closeDialog = (target, reason) => {
      if (closed) return
      closed = true
      try {
        overlay.remove()
      } catch {}
      dialogStack.remove(id)
      cancelBindListening()
      setBackgroundInert(dialogStack.size() > 0)
      if (previousFocus && typeof previousFocus.focus === 'function' && previousFocus.isConnected) {
        try {
          previousFocus.focus({ preventScroll: true })
        } catch {}
      }
      if (dialogStack.size() === 0) resumeTimer('dialog')
      try {
        if (config.onClose) config.onClose(reason)
      } catch {}
    }
    // Esc / Tab 在 overlay 上统一处理（不依赖 document 级顺序）
    overlay.addEventListener('keydown', (event) => {
      if (event.key === 'Tab') {
        const focusables = Array.from(
          dialog.querySelectorAll('button, input, select, textarea, a[href], [tabindex]:not([tabindex="-1"])')
        ).filter((el) => !el.disabled && el.offsetParent !== null)
        if (focusables.length === 0) {
          event.preventDefault()
          return
        }
        const first = focusables[0]
        const last = focusables[focusables.length - 1]
        const active = document.activeElement
        if (!dialog.contains(active)) {
          event.preventDefault()
          first.focus()
        } else if (event.shiftKey && active === first) {
          event.preventDefault()
          last.focus()
        } else if (!event.shiftKey && active === last) {
          event.preventDefault()
          first.focus()
        }
        return
      }
      if (event.key === 'Escape') {
        event.preventDefault()
        event.stopPropagation()
        entry.close('cancel')
      }
    })
    overlay.addEventListener('click', (event) => {
      if (event.target === overlay) entry.close('cancel')
    })

    dialogStack.push(entry)
    setBackgroundInert(true)
    if (dialogStack.size() === 1) suspendTimer('dialog')
    ui.cancelGeneration()

    // 初始焦点：优先 config.initialFocus 选择器，否则第一个可聚焦控件。
    // 同时用 rAF + setTimeout 兜底：窗口被遮挡时 rAF 可能被节流，不能让对话框打开后焦点留在背景。
    let focused = false
    const focusInitial = () => {
      if (closed || focused) return
      let target = null
      if (config.initialFocus) target = dialog.querySelector(config.initialFocus)
      if (!target) {
        const focusables = dialog.querySelectorAll('button, input, select, textarea')
        target = focusables[0] || null
      }
      if (target && typeof target.focus === 'function') {
        try {
          target.focus({ preventScroll: true })
          focused = document.activeElement === target
        } catch {}
      }
    }
    window.requestAnimationFrame(focusInitial)
    setTimeout(focusInitial, 60)
    return entry
  }

  function closeAllDialogs(reason = 'cancel') {
    // 从栈顶逐层关闭，保证 onClose/cancel 回调按顺序触发
    while (dialogStack.size() > 0) {
      const top = dialogStack.top()
      if (!top) break
      top.close(reason)
    }
  }

  /** 通用确认框：点「确定」才执行 onConfirm（取消/遮罩/Esc 不执行，并回调 onCancel） */
  function showConfirmDialog(title, message, onConfirm, onCancel) {
    const entry = openDialog({
      title,
      className: 'narrow',
      contentHtml: `<p class="mt">${message}</p>
        <div class="actions">
          <button class="btn" id="confirm-ok">${t('dialog.ok')}</button>
          <button class="btn" id="confirm-cancel">${t('dialog.cancel')}</button>
        </div>`,
      initialFocus: '#confirm-cancel',
      onClose: (reason) => {
        if (reason !== 'ok' && typeof onCancel === 'function') onCancel()
      }
    })
    entry.dialog.querySelector('#confirm-ok').addEventListener('click', () => {
      entry.close('ok')
      onConfirm()
    })
    entry.dialog.querySelector('#confirm-cancel').addEventListener('click', () => entry.close('cancel'))
  }

  /**
   * 重开保护：对局进行中（已有进展）先确认再执行，避免 F2 / 难度按钮/自定义误触丢局；
   * 未开局或已终局时直接执行。取消时通过 onCancel 回写菜单勾选（M10/M12）。
   */
  function requestRestart(action, onCancel) {
    if (game.state !== 'playing') {
      action()
      return
    }
    showConfirmDialog(t('dialog.confirmRestart.title'), t('dialog.confirmRestart.message'), action, () => {
      if (typeof onCancel === 'function') onCancel()
      syncMenuDifficulty()
    })
  }

  // ---------- 开局入口（M12：单一入口） ----------

  function finishStartGame() {
    resetTimerClock()
    ui.render()
    ui.prevState = 'ready'
    syncMenuDifficulty()
    window.requestAnimationFrame(fitWindow)
  }

  function startGame(level) {
    closeAllDialogs('new-game')
    ui.cancelGeneration()
    if (level === 'custom') {
      if (!game.currentCustom) return
      game.initCustom(game.currentCustom.rows, game.currentCustom.cols, game.currentCustom.mines)
    } else {
      game.init(level)
    }
    ui.clampCursorToBoard()
    finishStartGame()
  }

  function startCustomGame(rows, cols, mines) {
    closeAllDialogs('new-game')
    ui.cancelGeneration()
    game.initCustom(rows, cols, mines)
    ui.clampCursorToBoard()
    finishStartGame()
  }

  // ---------- 各对话框 ----------

  function showCustomDialog() {
    const entry = openDialog({
      title: t('custom.title'),
      contentHtml: `
        <div class="error" id="custom-error"></div>
        <div class="row">
          <label for="custom-rows">${t('custom.rows')}</label>
          <input type="number" id="custom-rows" value="16" min="9" max="50">
        </div>
        <div class="row">
          <label for="custom-cols">${t('custom.cols')}</label>
          <input type="number" id="custom-cols" value="16" min="9" max="50">
        </div>
        <div class="row">
          <label for="custom-mines">${t('custom.mines')}</label>
          <input type="number" id="custom-mines" value="40" min="1">
        </div>
        <div class="actions">
          <button class="btn" id="custom-ok">${t('dialog.ok')}</button>
          <button class="btn" id="custom-cancel">${t('dialog.cancel')}</button>
        </div>`,
      initialFocus: '#custom-rows',
      onClose: (reason) => {
        // 菜单“自定义...”被取消时回写真实难度，避免菜单停留错误勾选（M10）
        if (reason !== 'ok') syncMenuDifficulty()
      }
    })
    const dialog = entry.dialog
    const err = dialog.querySelector('#custom-error')
    const rowsInput = dialog.querySelector('#custom-rows')
    const colsInput = dialog.querySelector('#custom-cols')
    const minesInput = dialog.querySelector('#custom-mines')

    const submit = () => {
      const rows = Number(rowsInput.value)
      const cols = Number(colsInput.value)
      const mines = Number(minesInput.value)
      const maxMines = Math.floor(rows * cols * 0.85)
      if (!Number.isInteger(rows) || rows < 9 || rows > 50) {
        err.textContent = t('custom.errRows')
        return
      }
      if (!Number.isInteger(cols) || cols < 9 || cols > 50) {
        err.textContent = t('custom.errCols')
        return
      }
      if (!Number.isInteger(mines) || mines < 1 || mines > maxMines) {
        err.textContent = t('custom.errMines', { max: maxMines })
        return
      }
      entry.close('ok')
      requestRestart(() => startCustomGame(rows, cols, mines))
    }

    dialog.querySelector('#custom-ok').addEventListener('click', submit)
    dialog.querySelector('#custom-cancel').addEventListener('click', () => entry.close('cancel'))
    ;[rowsInput, colsInput, minesInput].forEach((input) => {
      input.addEventListener('keydown', (event) => {
        if (event.key === 'Enter') {
          event.preventDefault()
          submit()
        }
      })
    })
  }

  function formatDuration(seconds) {
    return typeof helpers.formatDuration === 'function' ? helpers.formatDuration(seconds) : String(seconds)
  }

  /** bestTimeOf 返回毫秒（M7 契约），显示层用毫秒格式化 */
  function formatBestTime(value) {
    if (value === null || value === undefined || !Number.isFinite(Number(value))) return '--:--'
    if (typeof helpers.formatDurationMs === 'function') return helpers.formatDurationMs(Number(value))
    return formatDuration(Math.floor(Number(value) / 1000))
  }

  function statBuckets() {
    let keys = null
    try {
      if (stats && typeof stats.getBuckets === 'function') {
        const raw = stats.getBuckets()
        if (Array.isArray(raw)) {
          keys = raw
            .map((item) => (typeof item === 'string' ? item : item && (item.key || item.bucket)))
            .filter((item) => typeof item === 'string' && item.length > 0)
        }
      }
    } catch {
      keys = null
    }
    if (!keys || keys.length === 0) keys = ['beginner', 'intermediate', 'expert', 'custom']
    const levelKeys = Object.keys(CONFIG.LEVELS)
    const ordered = [
      ...levelKeys.filter((key) => keys.includes(key)),
      ...keys.filter((key) => !levelKeys.includes(key))
    ]
    return ordered
  }

  function bucketLabel(key) {
    if (Object.prototype.hasOwnProperty.call(CONFIG.LEVELS, key)) return t('level.' + key)
    if (key === 'custom') return t('level.custom')
    const match = /^custom:(\d+)x(\d+)\/(\d+)$/.exec(String(key))
    if (match) return `${match[1]}×${match[2]}/${match[3]}`
    return String(key)
  }

  function bestTimeFor(bucket) {
    try {
      if (stats && typeof stats.bestTimeOf === 'function') return stats.bestTimeOf(bucket)
    } catch {}
    try {
      const summary = stats.getSummary()
      return summary.bestTime ? summary.bestTime[bucket] : null
    } catch {
      return null
    }
  }

  function bestRateFor(bucket) {
    try {
      if (stats && typeof stats.bestBbbvPerSecOf === 'function') return stats.bestBbbvPerSecOf(bucket)
    } catch {}
    try {
      const summary = stats.getSummary()
      if (summary.bestBbbvPerSec && summary.bestBbbvPerSec[bucket] !== undefined) {
        return summary.bestBbbvPerSec[bucket]
      }
    } catch {}
    return null
  }

  function currentStatsBucket() {
    try {
      if (stats && typeof stats.currentBucket === 'string' && stats.currentBucket) return stats.currentBucket
    } catch {}
    return game.currentLevel
  }

  function showStatsDialog() {
    const summary = stats.getSummary()
    const buckets = statBuckets()
    const bestRows = buckets
      .map((bucket) => {
        const time = bestTimeFor(bucket)
        return `<p>　${bucketLabel(bucket)}: ${formatBestTime(time)}</p>`
      })
      .join('')
    const rateRows = buckets
      .map((bucket) => {
        const rate = bestRateFor(bucket)
        return `<p>　${bucketLabel(bucket)}: ${typeof helpers.formatRate === 'function' ? helpers.formatRate(rate) : rate}</p>`
      })
      .join('')
    const rate = typeof helpers.formatPercent === 'function' ? helpers.formatPercent(summary.winRate) : summary.winRate
    const entry = openDialog({
      title: t('stats.title'),
      className: 'wide',
      contentHtml: `
        <p>${t('stats.totalGames', { n: summary.totalGames })}</p>
        <p>${t('stats.wonLost', { won: summary.gamesWon, lost: summary.gamesLost })}</p>
        <p>${t('stats.winRate', { rate })}</p>
        <p>${t('stats.currentStreak', { n: summary.currentStreak })}</p>
        <p>${t('stats.bestStreak', { n: summary.bestStreak })}</p>
        <p>${t('stats.totalTime', { time: formatDuration(summary.totalPlayTime) })}</p>
        <p class="mt">${t('stats.bestTime')}</p>
        ${bestRows}
        <p class="mt">${t('stats.bestEfficiency')}</p>
        ${rateRows}
        <div class="actions"><button class="btn" id="stats-close">${t('stats.close')}</button></div>`,
      initialFocus: '#stats-close'
    })
    entry.dialog.querySelector('#stats-close').addEventListener('click', () => entry.close('ok'))
  }

  // ---------- 效率曲线（3BV/s，M6 分桶） ----------

  function buildChartSvg(entries) {
    const W = 520
    const H = 190
    const padL = 44
    const padR = 14
    const padT = 14
    const padB = 26
    if (entries.length === 0) {
      return `<svg viewBox="0 0 ${W} ${H}" width="100%" height="${H}">
        <text x="${W / 2}" y="${H / 2}" text-anchor="middle" fill="currentColor" opacity="0.5" font-size="13">${t('history.chartNoData')}</text>
      </svg>`
    }
    const rates = entries.map((e) => {
      const seconds = Number.isFinite(e.durationMs) && e.durationMs > 0 ? e.durationMs / 1000 : Number(e.time) || 0
      return seconds > 0 ? e.bbbv / seconds : 0
    })
    const yMax = Math.max(1, Math.ceil(Math.max(...rates) * 1.15 * 10) / 10)
    const innerW = W - padL - padR
    const innerH = H - padT - padB
    const x = (i) => padL + (entries.length === 1 ? innerW / 2 : (innerW * i) / (entries.length - 1))
    const y = (v) => padT + innerH * (1 - v / yMax)
    const grid = [0, 0.5, 1]
      .map((f) => {
        const v = yMax * f
        return (
          `<line x1="${padL}" y1="${y(v).toFixed(1)}" x2="${W - padR}" y2="${y(v).toFixed(1)}" ` +
          `stroke="currentColor" stroke-opacity="0.18"/>` +
          `<text x="${padL - 6}" y="${(y(v) + 4).toFixed(1)}" text-anchor="end" font-size="10" ` +
          `fill="currentColor" opacity="0.7">${v.toFixed(1)}</text>`
        )
      })
      .join('')
    const avg = rates.reduce((a, b) => a + b, 0) / rates.length
    const points = rates.map((v, i) => `${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(' ')
    const dots = rates
      .map((v, i) => `<circle cx="${x(i).toFixed(1)}" cy="${y(v).toFixed(1)}" r="2.6" fill="currentColor"/>`)
      .join('')
    return `<svg viewBox="0 0 ${W} ${H}" width="100%" height="${H}" role="img" aria-label="${t('history.title')}">
      ${grid}
      <line x1="${padL}" y1="${y(avg).toFixed(1)}" x2="${W - padR}" y2="${y(avg).toFixed(1)}"
        stroke="currentColor" stroke-opacity="0.45" stroke-dasharray="4 3"/>
      <polyline points="${points}" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/>
      ${dots}
      <text x="${W - padR}" y="${H - 6}" text-anchor="end" font-size="10" fill="currentColor" opacity="0.6">
        ${t('history.chartRecent', { n: entries.length })}
      </text>
    </svg>`
  }

  function showHistoryDialog() {
    const buckets = statBuckets()
    let bucket = currentStatsBucket()
    if (!buckets.includes(bucket)) bucket = buckets[0]
    const options = buckets
      .map((key) => `<option value="${key}"${key === bucket ? ' selected' : ''}>${bucketLabel(key)}</option>`)
      .join('')
    const entry = openDialog({
      title: t('history.title'),
      className: 'wide',
      contentHtml: `
        <div class="row">
          <label for="hist-level">${t('history.difficulty')}</label>
          <select id="hist-level">${options}</select>
        </div>
        <div id="hist-chart"></div>
        <div id="hist-meta"></div>
        <div class="actions"><button class="btn" id="hist-close">${t('history.close')}</button></div>`,
      initialFocus: '#hist-level'
    })
    const dialog = entry.dialog
    const select = dialog.querySelector('#hist-level')
    const chart = dialog.querySelector('#hist-chart')
    const meta = dialog.querySelector('#hist-meta')
    const formatRate = (r) => (typeof helpers.formatRate === 'function' ? helpers.formatRate(r) : r)

    const render = () => {
      let entries = []
      try {
        entries = stats.getHistory(bucket, 20) || []
      } catch {
        entries = []
      }
      chart.innerHTML = buildChartSvg(entries)
      if (entries.length === 0) {
        meta.textContent = t('history.empty')
        return
      }
      const rates = entries.map((e) => {
      const seconds = Number.isFinite(e.durationMs) && e.durationMs > 0 ? e.durationMs / 1000 : Number(e.time) || 0
      return seconds > 0 ? e.bbbv / seconds : 0
    })
      const avg = rates.reduce((a, b) => a + b, 0) / rates.length
      const pausedCount = entries.filter((e) => e && e.paused).length
      meta.textContent =
        t('history.meta', { n: entries.length, avg: formatRate(avg), best: formatRate(Math.max(...rates)) }) +
        (pausedCount > 0 ? t('history.pausedNote', { n: pausedCount }) : '')
    }

    render()
    select.addEventListener('change', () => {
      bucket = select.value
      render()
    })
    dialog.querySelector('#hist-close').addEventListener('click', () => entry.close('ok'))
  }

  function showAboutDialog() {
    const entry = openDialog({
      title: t('about.title'),
      className: 'narrow',
      contentHtml: `
        <p id="about-version">${t('about.tagline')}</p>
        <p class="mt small">
          ${t('about.electron')}<br>
          ${t('about.vanilla')}<br>
          ${t('about.style')}
        </p>
        <div class="actions"><button class="btn" id="about-close">${t('about.close')}</button></div>`,
      initialFocus: '#about-close'
    })
    const versionEl = entry.dialog.querySelector('#about-version')
    if (window.electronAPI && window.electronAPI.getAppVersion) {
      window.electronAPI
        .getAppVersion()
        .then((version) => {
          if (versionEl && version) versionEl.textContent = t('app.versionLine', { version })
        })
        .catch(() => {})
    }
    entry.dialog.querySelector('#about-close').addEventListener('click', () => entry.close('ok'))
  }

  function formatKeyLabel(key) {
    try {
      if (typeof KeyBindings !== 'undefined' && KeyBindings && typeof KeyBindings.formatKey === 'function') {
        return KeyBindings.formatKey(key)
      }
    } catch {}
    try {
      if (typeof globalThis.formatKey === 'function') return globalThis.formatKey(key)
    } catch {}
    return String(key)
  }

  function showKeyBindingsDialog() {
    let allBindings = {}
    try {
      allBindings = bindings.getBindings()
    } catch {
      allBindings = {}
    }
    const rows = Object.entries(allBindings)
      .map(
        ([action]) => `
      <div class="bind-row">
        <span class="bind-label">${t('action.' + action)}</span>
        <button class="bind-key" data-action="${action}">${formatKeyLabel(bindings.getKey ? bindings.getKey(action) : '')}</button>
      </div>`
      )
      .join('')
    const entry = openDialog({
      title: t('bind.title'),
      className: 'wide',
      contentHtml: `
        <div class="error" id="bind-error"></div>
        ${rows}
        <div class="actions">
          <button class="btn" id="bind-reset">${t('bind.reset')}</button>
          <button class="btn" id="bind-close">${t('bind.close')}</button>
        </div>`,
      initialFocus: '#bind-close'
    })
    const dialog = entry.dialog
    dialog.querySelector('#bind-close').addEventListener('click', () => entry.close('ok'))
    dialog.querySelector('#bind-reset').addEventListener('click', () => {
      bindings.reset()
      bindings.save()
      refreshBindButtons()
    })

    function refreshBindButtons() {
      dialog.querySelectorAll('.bind-key').forEach((btn) => {
        const action = btn.dataset.action
        btn.classList.remove('listening')
        btn.textContent = formatKeyLabel(bindings.getKey ? bindings.getKey(action) : '')
      })
    }

    dialog.querySelectorAll('.bind-key').forEach((btn) => {
      btn.addEventListener('click', () => {
        if (listeningForBind) return
        listeningForBind = btn.dataset.action
        btn.classList.add('listening')
        btn.textContent = t('bind.pressKey')
      })
    })
  }

  // ---------- 按钮 ----------

  document.querySelectorAll('.diff-btn').forEach((btn) => {
    btn.addEventListener('click', () => {
      ui.hideCursor()
      requestRestart(
        () => startGame(btn.dataset.level),
        () => syncMenuDifficulty()
      )
    })
  })

  document.getElementById('face-button').addEventListener('click', () => {
    ui.hideCursor()
    requestRestart(
      () => startGame(game.currentLevel),
      () => syncMenuDifficulty()
    )
  })

  // ---------- 键盘 ----------

  const MOVE_ACTIONS = ['moveUp', 'moveDown', 'moveLeft', 'moveRight']
  const heldMoves = new Set()
  let moveDelayTimer = null
  let moveRepeatTimer = null

  function stopMoveTimers() {
    if (moveDelayTimer) {
      clearTimeout(moveDelayTimer)
      moveDelayTimer = null
    }
    if (moveRepeatTimer) {
      clearInterval(moveRepeatTimer)
      moveRepeatTimer = null
    }
  }

  function clearHeldMoves() {
    heldMoves.clear()
    stopMoveTimers()
  }

  function stepMove() {
    const combine = typeof combineDirections === 'function' ? combineDirections : null
    if (!combine) return
    const next = combine(heldMoves, game.rows, game.cols, ui.cursor.row, ui.cursor.col)
    if (next) ui.setCursor(next.row, next.col)
  }

  function startMoveRepeat() {
    stopMoveTimers()
    moveDelayTimer = setTimeout(() => {
      moveDelayTimer = null
      moveRepeatTimer = setInterval(stepMove, CONFIG.KEYBOARD.repeatInterval)
    }, CONFIG.KEYBOARD.repeatDelay)
  }

  /** 键位存档损坏时（M5 引擎侧修复前的兜底）：重置键位而不是让每次 keydown 抛错 */
  function repairBindingsIfNeeded() {
    try {
      bindings.reset()
      bindings.save()
    } catch {}
  }

  function actionForKey(key) {
    try {
      return bindings.getActionForKey(key)
    } catch {
      repairBindingsIfNeeded()
      try {
        return bindings.getActionForKey(key)
      } catch {
        return null
      }
    }
  }

  function pauseOverlayVisible() {
    return !!(pauseOverlay && pauseOverlay.classList.contains('visible'))
  }

  function cancelTopDialog() {
    const top = dialogStack.top()
    if (!top) return false
    top.close('cancel')
    return true
  }

  document.addEventListener('keydown', (event) => {
    // 键位录制优先：Esc 取消录制（不关对话框，便于继续编辑）
    if (listeningForBind) {
      event.preventDefault()
      if (event.key === 'Escape') {
        cancelBindListening()
        return
      }
      const action = listeningForBind
      let conflict = null
      try {
        conflict = bindings.findConflict(action, event.key)
      } catch {}
      if (conflict) {
        const err = document.querySelector('#bind-error')
        if (err) err.textContent = t('bind.conflict', { key: formatKeyLabel(event.key), action: t('action.' + conflict) })
        cancelBindListening()
        return
      }
      try {
        bindings.setKey(action, event.key)
      } catch {}
      cancelBindListening()
      return
    }

    // M9：Esc 永远最先处理（输入框内也生效），关栈顶对话框
    if (event.key === 'Escape') {
      if (dialogOpen()) {
        event.preventDefault()
        event.stopPropagation()
        cancelTopDialog()
      }
      return
    }

    const targetTag = event.target && event.target.tagName ? event.target.tagName : ''
    const isTyping = ['INPUT', 'TEXTAREA', 'SELECT'].includes(targetTag)
    if (isTyping) return
    if (dialogOpen()) return
    if (pauseOverlayVisible()) return

    // H1：光标越界时先夹回棋盘，保证方向键/F2/翻开/标旗始终可用
    ui.clampCursorToBoard()
    const { row, col } = ui.cursor
    const action = actionForKey(event.key)
    if (!action) return
    event.preventDefault()

    // 方向键：加入按住集合，立即走一步（组合键即斜向，如 左+上 → 左上）。
    // 忽略系统键重复（e.repeat），长按重复由 startMoveRepeat 的定时器驱动。
    if (MOVE_ACTIONS.includes(action)) {
      if (!event.repeat) {
        heldMoves.add(action)
        stepMove()
        startMoveRepeat()
      }
      return
    }

    // 非方向动作忽略系统键重复：避免按住 F 连续翻旗、按住 F2 连续重开
    if (event.repeat) return

    switch (action) {
      case 'activate': {
        const cell = game.board[row] && game.board[row][col]
        if (cell && cell.revealed && cell.adjacentMines > 0) {
          ui.doChord(row, col)
        } else {
          ui.onLeftClick(row, col)
        }
        break
      }
      case 'flag':
        ui.onRightClick(row, col)
        break
      case 'newGame':
        requestRestart(
          () => startGame(game.currentLevel),
          () => syncMenuDifficulty()
        )
        break
    }
  })

  // 松开方向键：从按住集合移除；仍有其他方向按住时立即按新方向走一步
  window.addEventListener('keyup', (event) => {
    const action = actionForKey(event.key)
    if (!action || !MOVE_ACTIONS.includes(action)) return
    heldMoves.delete(action)
    if (heldMoves.size === 0) stopMoveTimers()
    else stepMove()
  })

  // ---------- 暂停（窗口失焦，M15 同时作废生成） ----------

  function pauseGame() {
    clearHeldMoves()
    ui.resetTransientInput()
    ui.cancelGeneration()
    if (game.state !== 'playing') {
      pauseOverlay.classList.remove('visible')
      pauseOverlay.setAttribute('aria-hidden', 'true')
      return
    }
    suspendTimer('blur')
    pauseOverlay.classList.add('visible')
    pauseOverlay.setAttribute('aria-hidden', 'false')
    ui.announceStatus('sr.paused')
  }

  function resumeGame() {
    pauseOverlay.classList.remove('visible')
    pauseOverlay.setAttribute('aria-hidden', 'true')
    if (game.state === 'playing') {
      resumeTimer('blur')
      if (!dialogOpen()) ui.announceStatus('sr.resumed')
    }
  }

  window.addEventListener('blur', pauseGame)
  window.addEventListener('focus', resumeGame)

  // M15：对话框/暂停遮罩期间生成完成不落地。
  // 失焦场景由 window blur → pauseGame → ui.cancelGeneration() 处理，这里不再用 document.hasFocus()
  //（某些环境下 hasFocus 可能为 false，会把正常对局的生成结果也丢弃）。
  ui.isInteractionBlocked = () => dialogOpen() || pauseOverlayVisible()

  // ---------- 静音 / 无猜 ----------

  function setMute(muted) {
    audio.enabled = !muted
    try {
      localStorage.setItem(CONFIG.STORAGE_KEYS.muted, String(muted))
    } catch {}
    if (window.electronAPI) {
      try {
        window.electronAPI.updateMenuMute(muted)
      } catch {}
    }
  }

  function setNoGuess(enabled) {
    game.noGuess = enabled
    try {
      localStorage.setItem(CONFIG.STORAGE_KEYS.noGuess, String(enabled))
    } catch {}
    if (window.electronAPI) {
      try {
        window.electronAPI.updateMenuNoGuess(enabled)
      } catch {}
    }
  }

  // ---------- 原生菜单动作 ----------

  const MENU_DIFFICULTIES = Object.keys(CONFIG.LEVELS)

  if (window.electronAPI && typeof window.electronAPI.onMenuAction === 'function') {
    window.electronAPI.onMenuAction((data) => {
      if (!data || typeof data.action !== 'string') return
      switch (data.action) {
        case 'new-game':
          requestRestart(
            () => startGame(game.currentLevel),
            () => syncMenuDifficulty()
          )
          break
        case 'difficulty':
          if (MENU_DIFFICULTIES.includes(data.level)) {
            requestRestart(
              () => startGame(data.level),
              () => syncMenuDifficulty()
            )
          } else {
            syncMenuDifficulty()
          }
          break
        case 'custom':
          showCustomDialog()
          break
        case 'theme':
          ui.setTheme(data.value)
          if (window.electronAPI) {
            try {
              window.electronAPI.updateMenuTheme(data.value)
            } catch {}
          }
          break
        case 'toggle-mute':
          setMute(data.value)
          break
        case 'toggle-no-guess':
          setNoGuess(data.value)
          break
        case 'language':
          setLanguage(data.value)
          break
        case 'stats':
          showStatsDialog()
          break
        case 'history':
          showHistoryDialog()
          break
        case 'about':
          showAboutDialog()
          break
        case 'keybindings':
          showKeyBindingsDialog()
          break
      }
    })
  }

  // ---------- 主题 ----------

  const systemThemeQuery = window.matchMedia('(prefers-color-scheme: dark)')

  function applyTheme(value, persist) {
    ui.setTheme(value, persist)
    if (window.electronAPI) {
      try {
        window.electronAPI.updateMenuTheme(value)
      } catch {}
    }
  }

  // ---------- 初始化 ----------

  applyStaticI18n()

  let savedTheme = null
  try {
    savedTheme = localStorage.getItem(CONFIG.STORAGE_KEYS.theme)
  } catch {}
  applyTheme(savedTheme || (systemThemeQuery.matches ? 'dark' : 'light'), savedTheme !== null)
  // 主进程菜单启动时用系统语言，这里用渲染层最终偏好重建一次（含主题/难度勾选）
  syncAllMenuState()

  systemThemeQuery.addEventListener('change', () => {
    try {
      if (localStorage.getItem(CONFIG.STORAGE_KEYS.theme) === null) {
        applyTheme(systemThemeQuery.matches ? 'dark' : 'light', false)
      }
    } catch {}
  })

  let savedMuted = null
  try {
    savedMuted = localStorage.getItem(CONFIG.STORAGE_KEYS.muted)
  } catch {}
  if (savedMuted !== null) setMute(savedMuted === 'true')

  let savedNoGuess = null
  try {
    savedNoGuess = localStorage.getItem(CONFIG.STORAGE_KEYS.noGuess)
  } catch {}
  setNoGuess(savedNoGuess === 'true')

  ui.render()
  ui.prevState = 'ready'
  syncMenuDifficulty()
  window.requestAnimationFrame(fitWindow)
})()
