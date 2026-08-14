/**
 * 主流程：计时器、窗口自适应、对话框、菜单动作、键盘操作、暂停/静音。
 */
;(function () {
  'use strict'

  const game = new GameEngine()
  const audio = new AudioManager()
  const stats = new StatsManager()
  const bindings = new KeyBindings()
  const ui = new UIRenderer(game, audio, stats)

  let timerInterval = null
  let listeningForBind = null

  // ---------- 计时器 ----------

  function startTimer() {
    if (timerInterval) return
    timerInterval = setInterval(() => {
      if (game.state === 'playing') {
        game.timer = Math.min(999, game.timer + 1)
        ui.updateHUD()
      }
    }, 1000)
  }

  function stopTimer() {
    if (timerInterval) {
      clearInterval(timerInterval)
      timerInterval = null
    }
  }

  // 引擎状态变化 → 统一管理计时器（替代原先的 monkey-patch）
  game.onStateChange((state) => {
    if (state === 'playing') startTimer()
    if (state === 'won' || state === 'lost') stopTimer()
  })

  // ---------- 窗口自适应 ----------

  function cellSizeFor() {
    const byWidth = Math.floor(CONFIG.WINDOW.boardMaxW / game.cols)
    const byHeight = Math.floor(CONFIG.WINDOW.boardMaxH / game.rows)
    return Math.min(
      CONFIG.CELL_SIZE.max,
      Math.max(CONFIG.CELL_SIZE.min, Math.min(byWidth, byHeight))
    )
  }

  function fitWindow() {
    if (!window.electronAPI) return
    const cell = cellSizeFor()
    document.documentElement.style.setProperty('--cell-size', cell + 'px')
    window.electronAPI.fitWindow(
      game.cols * cell + CONFIG.WINDOW.boardPadX,
      game.rows * cell + CONFIG.WINDOW.boardPadY
    )
  }

  // ---------- 新游戏 ----------

  function startGame(level) {
    if (level === 'custom' && game.currentCustom) {
      game.initCustom(game.currentCustom.rows, game.currentCustom.cols, game.currentCustom.mines)
    } else {
      game.init(level)
    }
    stopTimer()
    ui.render()
    ui.prevState = 'ready'
    if (window.electronAPI) window.electronAPI.updateMenuDifficulty(game.currentLevel)
    setTimeout(fitWindow, 50)
  }

  // ---------- 对话框 ----------

  function showDialog(innerHTML, extraClass) {
    clearHeldMoves()
    const overlay = document.createElement('div')
    overlay.className = 'dialog-overlay'
    const dialog = document.createElement('div')
    dialog.className = 'dialog' + (extraClass ? ' ' + extraClass : '')
    dialog.innerHTML = innerHTML
    overlay.appendChild(dialog)
    document.body.appendChild(overlay)
    const close = () => overlay.remove()
    overlay.addEventListener('click', (e) => {
      if (e.target === overlay) close()
    })
    return { overlay, dialog, close }
  }

  function showCustomDialog() {
    const { dialog, close } = showDialog(`
      <h2>自定义雷区</h2>
      <div class="error" id="custom-error"></div>
      <div class="row">
        <label>行:</label>
        <input type="number" id="custom-rows" value="16" min="9" max="50">
      </div>
      <div class="row">
        <label>列:</label>
        <input type="number" id="custom-cols" value="16" min="9" max="50">
      </div>
      <div class="row">
        <label>雷数:</label>
        <input type="number" id="custom-mines" value="40" min="1">
      </div>
      <div class="actions">
        <button class="btn" id="custom-ok">确定</button>
        <button class="btn" id="custom-cancel">取消</button>
      </div>
    `)

    const err = dialog.querySelector('#custom-error')
    const rowsInput = dialog.querySelector('#custom-rows')
    const colsInput = dialog.querySelector('#custom-cols')
    const minesInput = dialog.querySelector('#custom-mines')

    dialog.querySelector('#custom-ok').addEventListener('click', () => {
      const rows = Number(rowsInput.value)
      const cols = Number(colsInput.value)
      const mines = Number(minesInput.value)
      const maxMines = Math.floor(rows * cols * 0.85)

      if (!Number.isInteger(rows) || rows < 9 || rows > 50) {
        err.textContent = '行必须是 9 到 50 之间的整数'
        return
      }
      if (!Number.isInteger(cols) || cols < 9 || cols > 50) {
        err.textContent = '列必须是 9 到 50 之间的整数'
        return
      }
      if (!Number.isInteger(mines) || mines < 1 || mines > maxMines) {
        err.textContent = '雷数必须是 1 到 ' + maxMines + ' 之间的整数'
        return
      }

      close()
      game.initCustom(rows, cols, mines)
      stopTimer()
      ui.render()
      ui.prevState = 'ready'
      if (window.electronAPI) window.electronAPI.updateMenuDifficulty('custom')
      setTimeout(fitWindow, 50)
    })

    dialog.querySelector('#custom-cancel').addEventListener('click', close)
    setTimeout(() => rowsInput.focus(), 50)
  }

  function showStatsDialog() {
    const s = stats.getSummary()
    const fmt = (n) => String(n).padStart(3, '0')
    const fmtTime = (t) => (t === null ? '--:--' : Math.floor(t / 60) + ':' + fmt(t % 60))

    const { dialog, close } = showDialog(`
      <h2>游戏统计</h2>
      <p>总局数: ${s.totalGames}</p>
      <p>胜利: ${s.gamesWon} / 失败: ${s.gamesLost}</p>
      <p>胜率: ${s.winRate}%</p>
      <p>当前连胜: ${s.currentStreak}</p>
      <p>最长连胜: ${s.bestStreak}</p>
      <p>总游戏时间: ${fmtTime(s.totalPlayTime)}</p>
      <p class="mt">最快完成:</p>
      <p>　初级: ${fmtTime(s.bestTime.beginner)}</p>
      <p>　中级: ${fmtTime(s.bestTime.intermediate)}</p>
      <p>　高级: ${fmtTime(s.bestTime.expert)}</p>
      <div class="actions">
        <button class="btn" id="stats-close">关闭</button>
      </div>
    `, 'wide')

    dialog.querySelector('#stats-close').addEventListener('click', close)
  }

  function showAboutDialog() {
    const { dialog, close } = showDialog(`
      <h2>关于扫雷</h2>
      <p>经典扫雷游戏 v1.2.0</p>
      <p class="mt small">
        Electron 跨平台桌面应用<br>
        纯 JavaScript 实现<br>
        经典 WinXP 风格
      </p>
      <div class="actions">
        <button class="btn" id="about-close">关闭</button>
      </div>
    `, 'narrow')

    dialog.querySelector('#about-close').addEventListener('click', close)
  }

  function showKeyBindingsDialog() {
    const rows = Object.entries(bindings.getBindings()).map(([action, data]) => `
      <div class="bind-row">
        <span class="bind-label">${data.label}</span>
        <button class="bind-key" data-action="${action}">${bindings.getDisplayKey(action)}</button>
      </div>
    `).join('')

    const { dialog, close } = showDialog(`
      <h2>键盘快捷键</h2>
      <div class="error" id="bind-error"></div>
      ${rows}
      <div class="actions">
        <button class="btn" id="bind-reset">恢复默认</button>
        <button class="btn" id="bind-close">关闭</button>
      </div>
    `, 'wide')

    dialog.querySelector('#bind-close').addEventListener('click', close)

    dialog.querySelector('#bind-reset').addEventListener('click', () => {
      bindings.reset()
      bindings.save()
      dialog.querySelectorAll('.bind-key').forEach(btn => {
        btn.textContent = bindings.getDisplayKey(btn.dataset.action)
      })
    })

    dialog.querySelectorAll('.bind-key').forEach(btn => {
      btn.addEventListener('click', () => {
        if (listeningForBind) return
        listeningForBind = btn.dataset.action
        btn.classList.add('listening')
        btn.textContent = '按下新键...'
      })
    })

    setTimeout(() => dialog.querySelector('#bind-close').focus(), 50)
  }

  // ---------- 按钮 ----------

  document.querySelectorAll('.diff-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      ui.hideCursor()
      startGame(btn.dataset.level)
    })
  })

  document.getElementById('face-button').addEventListener('click', () => {
    ui.hideCursor()
    startGame(game.currentLevel)
  })

  // ---------- 键盘 ----------

  // 方向移动：记录所有按下的方向键，合成方向向量。
  // 系统键重复只作用于最后按下的键，无法支持斜向，因此长按重复由自己的定时器驱动。
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
    const next = combineDirections(heldMoves, game.rows, game.cols, ui.cursor.row, ui.cursor.col)
    if (next) ui.setCursor(next.row, next.col)
  }

  function startMoveRepeat() {
    stopMoveTimers()
    moveDelayTimer = setTimeout(() => {
      moveDelayTimer = null
      moveRepeatTimer = setInterval(stepMove, CONFIG.KEYBOARD.repeatInterval)
    }, CONFIG.KEYBOARD.repeatDelay)
  }

  document.addEventListener('keydown', (e) => {
    const isTyping = ['INPUT', 'TEXTAREA'].includes(e.target.tagName)
    if (isTyping) return

    if (listeningForBind) {
      e.preventDefault()
      if (e.key === 'Escape') {
        listeningForBind = null
        document.querySelectorAll('.bind-key.listening').forEach(btn => {
          btn.classList.remove('listening')
          btn.textContent = bindings.getDisplayKey(btn.dataset.action)
        })
        return
      }
      const action = listeningForBind
      const conflict = bindings.findConflict(action, e.key)
      if (conflict) {
        const err = document.querySelector('#bind-error')
        if (err) err.textContent = `"${formatKey(e.key)}" 已被「${bindings.defaults[conflict].label}」占用`
        listeningForBind = null
        document.querySelectorAll('.bind-key.listening').forEach(btn => {
          btn.classList.remove('listening')
          btn.textContent = bindings.getDisplayKey(btn.dataset.action)
        })
        return
      }
      bindings.setKey(action, e.key)
      listeningForBind = null
      document.querySelectorAll('.bind-key').forEach(btn => {
        btn.classList.remove('listening')
        btn.textContent = bindings.getDisplayKey(btn.dataset.action)
      })
      return
    }

    if (e.key === 'Escape') {
      const overlay = document.querySelector('.dialog-overlay')
      if (overlay) overlay.remove()
      return
    }

    // 对话框打开时不响应游戏按键
    if (document.querySelector('.dialog-overlay')) return
    if (document.getElementById('pause-overlay').classList.contains('visible')) return

    const { row, col } = ui.cursor
    if (row < 0 || row >= game.rows || col < 0 || col >= game.cols) return
    const action = bindings.getActionForKey(e.key)
    if (!action) return
    e.preventDefault()

    // 方向键：加入按住集合，立即走一步（组合键即斜向，如 左+上 → 左上）。
    // 忽略系统键重复（e.repeat），长按重复由 startMoveRepeat 的定时器驱动。
    if (MOVE_ACTIONS.includes(action)) {
      if (!e.repeat) {
        heldMoves.add(action)
        stepMove()
        startMoveRepeat()
      }
      return
    }

    switch (action) {
      case 'activate': {
        const cell = game.board[row]?.[col]
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
        startGame(game.currentLevel)
        break
    }
  })

  // 松开方向键：从按住集合移除；仍有其他方向按住时立即按新方向走一步
  window.addEventListener('keyup', (e) => {
    const action = bindings.getActionForKey(e.key)
    if (!action || !MOVE_ACTIONS.includes(action)) return
    heldMoves.delete(action)
    if (heldMoves.size === 0) {
      stopMoveTimers()
    } else {
      stepMove()
    }
  })

  // ---------- 暂停（窗口失焦） ----------

  function pauseGame() {
    clearHeldMoves()
    if (game.state !== 'playing') return
    stopTimer()
    document.getElementById('pause-overlay').classList.add('visible')
  }

  function resumeGame() {
    document.getElementById('pause-overlay').classList.remove('visible')
    if (game.state === 'playing') startTimer()
  }

  window.addEventListener('blur', pauseGame)
  window.addEventListener('focus', resumeGame)

  // ---------- 静音 ----------

  function setMute(muted) {
    audio.enabled = !muted
    localStorage.setItem(CONFIG.STORAGE_KEYS.muted, String(muted))
    if (window.electronAPI) window.electronAPI.updateMenuMute(muted)
  }

  // ---------- 原生菜单动作 ----------

  if (window.electronAPI) {
    window.electronAPI.onMenuAction((data) => {
      switch (data.action) {
        case 'new-game':
          startGame(game.currentLevel)
          break
        case 'difficulty':
          startGame(data.level)
          break
        case 'custom':
          showCustomDialog()
          break
        case 'theme':
          ui.setTheme(data.value)
          break
        case 'toggle-mute':
          setMute(data.value)
          break
        case 'stats':
          showStatsDialog()
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

  // ---------- 初始化 ----------

  const savedTheme = localStorage.getItem(CONFIG.STORAGE_KEYS.theme)
  if (savedTheme) {
    ui.setTheme(savedTheme)
    if (window.electronAPI) window.electronAPI.updateMenuTheme(savedTheme)
  }

  const savedMuted = localStorage.getItem(CONFIG.STORAGE_KEYS.muted)
  if (savedMuted !== null) {
    setMute(savedMuted === 'true')
  }

  ui.render()
  setTimeout(fitWindow, 100)
})()
