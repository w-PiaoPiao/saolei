const { app, BrowserWindow, Menu, Tray, ipcMain, dialog, nativeImage, screen, session } = require('electron')
const path = require('path')
const fs = require('fs')
const { pathToFileURL } = require('url')
const { Worker } = require('worker_threads')
const { LEVELS, THEMES, WINDOW, CELL_SIZE } = require('./src/config.js')
const I18N = require('./src/i18n.js')

const DIFF_KEYS = Object.keys(LEVELS)
const THEME_KEYS = THEMES.map((theme) => theme.value)
const LANG_PREFS = ['system', 'zh', 'en']

let mainWindow = null
let tray = null
let appMenu = null
// 主进程侧的菜单状态镜像：语言切换重建菜单后用它恢复勾选（渲染进程是偏好来源，经 IPC 同步过来）
const menuState = {
  difficulty: 'beginner',
  theme: 'light',
  muted: false,
  noGuess: false,
  languagePref: 'system'
}
let cachedLocale = 'en'

// 窗口实测边界：用于「用户手动 resize 后不再自动回缩」（M13）
let userResized = false
let lastProgrammaticResizeAt = 0

// 无猜生成 worker 管理（M15/L1）：requestId → {worker, timer, senderId, finish}
const generationJobs = new Map()
let generationSeq = 0

// 打包后 worker 文件被 asarUnpack 解到 app.asar.unpacked 下（worker_threads 不走
// Electron 的 asar 补丁，必须给出真实路径），故这里显式做路径替换。
const GENERATOR_WORKER = path
  .join(__dirname, 'src', 'generator-worker.js')
  .replace(`app.asar${path.sep}`, `app.asar.unpacked${path.sep}`)

// ---------- 基础工具 ----------

function t(key, vars) {
  return I18N.t(key, vars)
}

function windowStateFile() {
  return path.join(app.getPath('userData'), 'window-state.json')
}

function resolveSystemLanguage() {
  return I18N.detectLanguage(cachedLocale || app.getLocale())
}

/** 只允许来自本应用 src/index.html 的 IPC（L2） */
function isTrustedSender(event) {
  try {
    const frame = event.senderFrame
    if (!frame || !frame.url) return false
    const srcDir = pathToFileURL(path.join(__dirname, 'src') + path.sep).href
    return frame.url.startsWith(srcDir)
  } catch {
    return false
  }
}

/** 应用语言（渲染层会通过 update-menu-language 覆盖；启动时先用系统语言） */
function applyLanguage(pref) {
  const value = LANG_PREFS.includes(pref) ? pref : 'system'
  menuState.languagePref = value
  I18N.setLanguage(value, { systemLang: resolveSystemLanguage() })
}

function sendToRenderer(action, payload = {}) {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send('menu-action', { action, ...payload })
  }
}

// ---------- 菜单 ----------

function levelLabel(level) {
  const cfg = LEVELS[level]
  return t('menu.levelItem', {
    label: t('level.' + level),
    rows: cfg.rows,
    cols: cfg.cols,
    mines: cfg.mines
  })
}

function themeLabel(value) {
  const theme = THEMES.find((item) => item.value === value)
  const key = 'theme.' + value
  const text = t(key)
  return text === key ? (theme && theme.label) || value : text
}

/** M10：radio 只对目标项赋 true 让组内自动反选，绝不对其它项赋 false */
function checkRadioById(menu, id) {
  const item = menu.getMenuItemById(id)
  if (item) item.checked = true
}

function checkMenuState(menu) {
  if (!menu) return
  checkRadioById(menu, DIFF_KEYS.includes(menuState.difficulty) ? 'diff-' + menuState.difficulty : 'diff-custom')
  if (THEME_KEYS.includes(menuState.theme)) checkRadioById(menu, 'theme-' + menuState.theme)
  checkRadioById(menu, 'lang-' + menuState.languagePref)
  const muteItem = menu.getMenuItemById('toggle-mute')
  if (muteItem) muteItem.checked = menuState.muted
  const noGuessItem = menu.getMenuItemById('toggle-no-guess')
  if (noGuessItem) noGuessItem.checked = menuState.noGuess
}

function requestLanguage(pref) {
  if (!LANG_PREFS.includes(pref)) return
  menuState.languagePref = pref
  sendToRenderer('language', { value: pref })
}

function buildMenu() {
  applyLanguage(menuState.languagePref)
  const difficultyItems = DIFF_KEYS.map((level) => ({
    id: 'diff-' + level,
    label: levelLabel(level),
    type: 'radio',
    checked: menuState.difficulty === level,
    click: () => sendToRenderer('difficulty', { level })
  }))
  const themeItems = THEMES.map((theme) => ({
    id: 'theme-' + theme.value,
    label: themeLabel(theme.value),
    type: 'radio',
    checked: menuState.theme === theme.value,
    click: () => sendToRenderer('theme', { value: theme.value })
  }))
  const languageItems = [
    {
      id: 'lang-system',
      label: t('menu.langSystem'),
      type: 'radio',
      checked: menuState.languagePref === 'system',
      click: () => requestLanguage('system')
    },
    {
      id: 'lang-zh',
      label: t('menu.langZh'),
      type: 'radio',
      checked: menuState.languagePref === 'zh',
      click: () => requestLanguage('zh')
    },
    {
      id: 'lang-en',
      label: t('menu.langEn'),
      type: 'radio',
      checked: menuState.languagePref === 'en',
      click: () => requestLanguage('en')
    }
  ]

  const gameMenu = {
    label: t('menu.game'),
    submenu: [
      { label: t('menu.newGame'), click: () => sendToRenderer('new-game') },
      {
        id: 'toggle-mute',
        label: t('menu.mute'),
        type: 'checkbox',
        checked: menuState.muted,
        click: (item) => {
          menuState.muted = !!item.checked
          sendToRenderer('toggle-mute', { value: item.checked })
        }
      },
      {
        id: 'toggle-no-guess',
        label: t('menu.noGuess'),
        type: 'checkbox',
        checked: menuState.noGuess,
        click: (item) => {
          menuState.noGuess = !!item.checked
          sendToRenderer('toggle-no-guess', { value: item.checked })
        }
      },
      { label: t('menu.keybindings'), click: () => sendToRenderer('keybindings') },
      { type: 'separator' },
      ...(process.platform === 'darwin' ? [] : [{ role: 'quit', label: t('menu.quit') }])
    ]
  }

  const template = []
  if (process.platform === 'darwin') template.push({ role: 'appMenu' })
  template.push(
    gameMenu,
    {
      label: t('menu.difficulty'),
      submenu: [
        // 注意：预设难度与「自定义」必须保持为**连续**的 radio 项（中间不能插 separator，
        // 否则会被 Electron 拆成两个 radio 组，组内自动反选失效，出现两个勾选项）。
        ...difficultyItems,
        {
          id: 'diff-custom',
          label: t('menu.custom'),
          type: 'radio',
          checked: !DIFF_KEYS.includes(menuState.difficulty),
          click: () => sendToRenderer('custom')
        }
      ]
    },
    { label: t('menu.theme'), submenu: themeItems },
    { label: t('menu.language'), submenu: languageItems },
    { role: 'editMenu' },
    {
      label: t('menu.view'),
      submenu: [
        { role: 'resetZoom', label: t('menu.zoomReset') },
        { role: 'zoomIn', label: t('menu.zoomIn') },
        { role: 'zoomOut', label: t('menu.zoomOut') },
        { type: 'separator' },
        { role: 'togglefullscreen', label: t('menu.fullscreen') }
      ]
    },
    { role: 'windowMenu' },
    {
      label: t('menu.help'),
      submenu: [
        { label: t('menu.stats'), click: () => sendToRenderer('stats') },
        { label: t('menu.history'), click: () => sendToRenderer('history') },
        { type: 'separator' },
        { label: t('menu.about'), click: () => sendToRenderer('about') }
      ]
    }
  )

  appMenu = Menu.buildFromTemplate(template)
  Menu.setApplicationMenu(appMenu)
  checkMenuState(appMenu)
  if (mainWindow && !mainWindow.isDestroyed()) mainWindow.setTitle(t('app.title'))
  rebuildTray()
}

function updateMenuDifficulty(level) {
  if (!DIFF_KEYS.includes(level) && level !== 'custom') return
  menuState.difficulty = level
  if (appMenu) checkRadioById(appMenu, DIFF_KEYS.includes(level) ? 'diff-' + level : 'diff-custom')
}

function updateMenuTheme(theme) {
  if (!THEME_KEYS.includes(theme)) return
  menuState.theme = theme
  if (appMenu) checkRadioById(appMenu, 'theme-' + theme)
}

function updateMenuMute(muted) {
  menuState.muted = !!muted
  const item = appMenu && appMenu.getMenuItemById('toggle-mute')
  if (item) item.checked = menuState.muted
  rebuildTray()
}

function updateMenuNoGuess(enabled) {
  menuState.noGuess = !!enabled
  const item = appMenu && appMenu.getMenuItemById('toggle-no-guess')
  if (item) item.checked = menuState.noGuess
}

function updateMenuLanguage(pref) {
  if (!LANG_PREFS.includes(pref)) return
  if (menuState.languagePref === pref) {
    menuState.languagePref = pref
    buildMenu()
    return
  }
  menuState.languagePref = pref
  buildMenu()
}

// ---------- 托盘（L10） ----------

function buildTrayMenu() {
  return Menu.buildFromTemplate([
    {
      label: mainWindow && mainWindow.isVisible() ? t('tray.hide') : t('tray.show'),
      click: () => {
        if (!mainWindow || mainWindow.isDestroyed()) return
        if (mainWindow.isVisible()) mainWindow.hide()
        else {
          mainWindow.show()
          mainWindow.focus()
        }
        rebuildTray()
      }
    },
    { label: t('tray.newGame'), click: () => sendToRenderer('new-game') },
    {
      label: t('tray.mute'),
      type: 'checkbox',
      checked: menuState.muted,
      click: (item) => {
        menuState.muted = !!item.checked
        sendToRenderer('toggle-mute', { value: item.checked })
        rebuildTray()
      }
    },
    { type: 'separator' },
    { label: t('tray.quit'), click: () => app.quit() }
  ])
}

function rebuildTray() {
  if (!tray || tray.isDestroyed()) return
  tray.setToolTip(t('app.title'))
  tray.setContextMenu(buildTrayMenu())
}

function createTray() {
  try {
    let image = nativeImage.createFromPath(path.join(__dirname, 'icon.png'))
    if (image.isEmpty()) return
    image = image.resize({ width: 18, height: 18 })
    if (process.platform === 'darwin') image.setTemplateImage(true)
    tray = new Tray(image)
    tray.setToolTip(t('app.title'))
    tray.setContextMenu(buildTrayMenu())
    tray.on('click', () => {
      if (!mainWindow || mainWindow.isDestroyed()) return
      if (mainWindow.isVisible()) mainWindow.hide()
      else {
        mainWindow.show()
        mainWindow.focus()
      }
      rebuildTray()
    })
  } catch {
    tray = null // 部分 Linux 桌面环境不支持托盘：静默降级
  }
}

// ---------- 窗口状态持久化（L10） ----------

function loadWindowState() {
  try {
    const raw = fs.readFileSync(windowStateFile(), 'utf8')
    const data = JSON.parse(raw)
    const width = Number(data.width)
    const height = Number(data.height)
    if (!Number.isFinite(width) || !Number.isFinite(height)) return null
    return {
      width: Math.max(350, Math.round(width)),
      height: Math.max(300, Math.round(height)),
      x: Number.isFinite(Number(data.x)) ? Math.round(Number(data.x)) : undefined,
      y: Number.isFinite(Number(data.y)) ? Math.round(Number(data.y)) : undefined
    }
  } catch {
    return null
  }
}

function saveWindowState() {
  if (!mainWindow || mainWindow.isDestroyed()) return
  try {
    const bounds = mainWindow.getBounds()
    fs.writeFileSync(windowStateFile(), JSON.stringify({ x: bounds.x, y: bounds.y, width: bounds.width, height: bounds.height }))
  } catch {
    // 持久化失败不影响使用
  }
}

/** 把窗口状态夹到当前屏幕工作区内（显示器拔插/分辨率变化后不消失） */
function clampBoundsToWorkArea(bounds) {
  const display = screen.getDisplayMatching({ x: bounds.x || 0, y: bounds.y || 0, width: bounds.width, height: bounds.height })
  const area = display.workArea
  const width = Math.min(Math.max(350, bounds.width), area.width)
  const height = Math.min(Math.max(300, bounds.height), area.height)
  let x = bounds.x === undefined ? area.x + Math.round((area.width - width) / 2) : bounds.x
  let y = bounds.y === undefined ? area.y + Math.round((area.height - height) / 2) : bounds.y
  x = Math.min(Math.max(area.x, x), area.x + Math.max(0, area.width - width))
  y = Math.min(Math.max(area.y, y), area.y + Math.max(0, area.height - height))
  return { x, y, width, height }
}

// ---------- 窗口 ----------

/** 新用户默认窗口：按初级棋盘紧凑尺寸（窗口不跳变，也不留大片空白） */
function defaultWindowSize() {
  const beginner = LEVELS.beginner
  const area = screen.getPrimaryDisplay().workAreaSize
  const availW = Math.min(WINDOW.boardMaxW, area.width - 80)
  const availH = Math.min(WINDOW.boardMaxH, area.height - 220)
  const cell = Math.min(
    CELL_SIZE.max,
    Math.max(CELL_SIZE.min, Math.min(Math.floor(availW / beginner.cols), Math.floor(availH / beginner.rows)))
  )
  return {
    width: beginner.cols * cell + WINDOW.boardPadX,
    height: beginner.rows * cell + WINDOW.boardPadY
  }
}

function createWindow() {
  const saved = loadWindowState()
  // 恢复用户上次的窗口尺寸时，视为「用户已手动定过尺寸」：之后 fit-window 只放大不自动回缩（L10/M13）
  if (saved) userResized = true
  const initial = clampBoundsToWorkArea(saved || defaultWindowSize())
  mainWindow = new BrowserWindow({
    width: initial.width,
    height: initial.height,
    x: initial.x,
    y: initial.y,
    minWidth: 350,
    minHeight: 300,
    resizable: true,
    title: t('app.title'),
    icon: path.join(__dirname, 'icon.png'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  })

  mainWindow.on('resize', () => {
    // 程序化 setSize 也触发 resize：短时间窗口内的 resize 视为自动调整
    if (Date.now() - lastProgrammaticResizeAt < 250) return
    userResized = true
  })
  mainWindow.on('close', saveWindowState)
  mainWindow.on('closed', () => {
    mainWindow = null
    rebuildTray()
  })
  mainWindow.on('show', rebuildTray)
  mainWindow.on('hide', rebuildTray)

  mainWindow.webContents.setWindowOpenHandler(() => ({ action: 'deny' }))
  mainWindow.webContents.on('will-navigate', (event) => event.preventDefault())

  // L1：加载失败/渲染进程崩溃/无响应都要有用户可见的恢复路径
  mainWindow.loadFile(path.join(__dirname, 'src', 'index.html')).catch((error) => {
    dialog.showErrorBox(t('msg.loadFailedTitle'), String((error && error.message) || error))
  })
  mainWindow.webContents.on('render-process-gone', (event, details) => {
    if (!mainWindow || mainWindow.isDestroyed()) return
    const reason = (details && details.reason) || 'unknown'
    if (reason === 'clean-exit') return
    dialog
      .showMessageBox(mainWindow, {
        type: 'error',
        title: t('msg.renderGoneTitle'),
        message: t('msg.renderGoneMessage', { reason }),
        buttons: [t('msg.reload'), t('msg.quit')],
        defaultId: 0,
        cancelId: 1
      })
      .then(({ response }) => {
        if (!mainWindow || mainWindow.isDestroyed()) return
        if (response === 0) mainWindow.webContents.reload()
        else app.quit()
      })
      .catch(() => {})
  })
  mainWindow.webContents.on('unresponsive', () => {
    if (!mainWindow || mainWindow.isDestroyed()) return
    dialog
      .showMessageBox(mainWindow, {
        type: 'warning',
        title: t('msg.unresponsiveTitle'),
        message: t('msg.unresponsiveMessage'),
        buttons: [t('msg.continue'), t('msg.reload')],
        defaultId: 0,
        cancelId: 0
      })
      .then(({ response }) => {
        if (response === 1 && mainWindow && !mainWindow.isDestroyed()) mainWindow.webContents.reload()
      })
      .catch(() => {})
  })
}

/**
 * M13：按棋盘内容调整窗口尺寸。
 * - 目标尺寸受屏幕 workArea 约束；
 * - 用户从未手动 resize 时允许自动回缩；
 * - 用户手动 resize 之后只允许自动放大（不覆盖用户选择）。
 */
function fitWindowToContent(width, height) {
  if (!mainWindow || mainWindow.isDestroyed()) return
  if (!Number.isFinite(width) || !Number.isFinite(height)) return
  const [currentW, currentH] = mainWindow.getSize()
  const display = screen.getDisplayMatching(mainWindow.getBounds())
  const area = display.workAreaSize
  const maxW = Math.min(WINDOW.maxW, area.width)
  const maxH = Math.min(WINDOW.maxH, area.height)
  const desiredW = Math.min(maxW, Math.max(350, Math.round(width)))
  const desiredH = Math.min(maxH, Math.max(300, Math.round(height)))
  const nextW = userResized ? Math.max(currentW, desiredW) : desiredW
  const nextH = userResized ? Math.max(currentH, desiredH) : desiredH
  if (nextW === currentW && nextH === currentH) return
  lastProgrammaticResizeAt = Date.now()
  mainWindow.setSize(nextW, nextH)
}

// ---------- IPC ----------

function registerIpc() {
  ipcMain.on('fit-window', (event, width, height) => {
    if (!isTrustedSender(event)) return
    fitWindowToContent(width, height)
  })

  ipcMain.on('update-menu-difficulty', (event, level) => {
    if (!isTrustedSender(event)) return
    updateMenuDifficulty(level)
  })
  ipcMain.on('update-menu-theme', (event, theme) => {
    if (!isTrustedSender(event)) return
    updateMenuTheme(theme)
  })
  ipcMain.on('update-menu-mute', (event, muted) => {
    if (!isTrustedSender(event)) return
    updateMenuMute(muted)
  })
  ipcMain.on('update-menu-no-guess', (event, enabled) => {
    if (!isTrustedSender(event)) return
    updateMenuNoGuess(enabled)
  })
  ipcMain.on('update-menu-language', (event, pref) => {
    if (!isTrustedSender(event)) return
    updateMenuLanguage(pref)
  })

  // 渲染进程读取应用版本（package.json 单一来源）
  ipcMain.handle('app-version', (event) => (isTrustedSender(event) ? app.getVersion() : null))
  // 主进程系统语言（渲染层 system 语言检测用）
  ipcMain.handle('app-locale', (event) => (isTrustedSender(event) ? app.getLocale() : null))

  ipcMain.handle('generate-mines', (event, req) => generateMines(event, req))

  // M15：渲染进程作废生成（新游戏/切难度/开对话框/失焦）时终止仍在跑的 worker
  ipcMain.on('cancel-generate-mines', (event) => {
    if (!isTrustedSender(event)) return
    cancelGenerationForSender(event.sender.id)
  })
}

function cancelGenerationForSender(senderId) {
  for (const job of Array.from(generationJobs.values())) {
    if (job.senderId === senderId) job.finish({ ok: false, error: '生成已取消' })
  }
}

function cancelAllGeneration() {
  for (const job of Array.from(generationJobs.values())) {
    job.finish({ ok: false, error: '应用退出' })
  }
}

function generateMines(event, req) {
  if (!isTrustedSender(event)) return { ok: false, error: '非法来源' }
  const rows = Math.floor(Number(req && req.rows))
  const cols = Math.floor(Number(req && req.cols))
  const mines = Math.floor(Number(req && req.mines))
  const safeRow = Math.floor(Number(req && req.safeRow))
  const safeCol = Math.floor(Number(req && req.safeCol))
  const valid =
    Number.isInteger(rows) &&
    Number.isInteger(cols) &&
    Number.isInteger(mines) &&
    Number.isInteger(safeRow) &&
    Number.isInteger(safeCol) &&
    rows >= 1 &&
    rows <= 100 &&
    cols >= 1 &&
    cols <= 100 &&
    mines >= 0 &&
    mines <= 10000 &&
    mines <= rows * cols - 9 &&
    safeRow >= 0 &&
    safeRow < rows &&
    safeCol >= 0 &&
    safeCol < cols
  if (!valid) return { ok: false, error: '非法生成参数' }

  const requestId = ++generationSeq
  const sender = event.sender
  return new Promise((resolve) => {
    let worker
    try {
      worker = new Worker(GENERATOR_WORKER, {
        workerData: { rows, cols, mines, safeRow, safeCol, noGuess: true, requestId }
      })
    } catch (error) {
      resolve({ ok: false, error: String((error && error.message) || error), requestId })
      return
    }

    const job = { requestId, worker, senderId: sender.id, alive: true, timer: null, finish: null }
    let onSenderDestroyed = null
    const cleanup = () => {
      job.alive = false
      if (job.timer) clearTimeout(job.timer)
      generationJobs.delete(requestId)
      try {
        if (onSenderDestroyed) sender.removeListener('destroyed', onSenderDestroyed)
      } catch {}
    }
    const finish = (result) => {
      if (!job.alive) return
      cleanup()
      resolve({ ...result, requestId })
      try {
        worker.terminate()
      } catch {}
    }
    job.finish = finish
    onSenderDestroyed = () => finish({ ok: false, error: '窗口已关闭' })
    sender.once('destroyed', onSenderDestroyed)

    // M15：2.5s 超时回退（渲染层还有 3s 兜底 race）
    job.timer = setTimeout(() => finish({ ok: false, error: '生成超时' }), 2500)
    generationJobs.set(requestId, job)

    worker.once('message', (msg) => {
      if (msg && msg.ok) {
        // M3：把几何信息一起回传给渲染进程，供 applyPlanAndReveal 做一致性校验
        finish({
          ok: true,
          mines: msg.mines,
          noGuessFallback: msg.noGuessFallback,
          rows: msg.rows,
          cols: msg.cols,
          safeRow: msg.safeRow,
          safeCol: msg.safeCol
        })
      } else {
        finish({ ok: false, error: (msg && msg.error) || '生成失败' })
      }
    })
    worker.once('error', (err) => finish({ ok: false, error: String((err && err.message) || err) }))
    worker.once('exit', (code) => {
      if (!job.alive) return
      finish({ ok: false, error: code === 0 ? '生成线程提前退出' : '生成线程异常退出' })
    })
  })
}

// ---------- 单实例 ----------

// 两个实例共享同一 userData 目录：localStorage 后写覆盖前写，会丢统计/键位/主题。
// 第二个实例直接退出，并把已有窗口提到前台。
if (!app.requestSingleInstanceLock()) {
  app.quit()
  return
}
app.on('second-instance', () => {
  if (!mainWindow || mainWindow.isDestroyed()) {
    createWindow()
    return
  }
  if (mainWindow.isMinimized()) mainWindow.restore()
  mainWindow.show()
  mainWindow.focus()
})

// ---------- 生命周期 ----------

app
  .whenReady()
  .then(() => {
    cachedLocale = app.getLocale()
    applyLanguage('system')
    app.setAboutPanelOptions({
      applicationName: t('app.title'),
      applicationVersion: app.getVersion()
    })
    // L2：不授予任何 Web 权限（本地内容不需要摄像头/通知/剪贴板读取等）
    try {
      session.defaultSession.setPermissionRequestHandler((webContents, permission, callback) => callback(false))
      session.defaultSession.setPermissionCheckHandler(() => false)
    } catch {}
    registerIpc()
    createTray()
    buildMenu()
    createWindow()
  })
  .catch((error) => {
    dialog.showErrorBox(t('msg.loadFailedTitle'), String((error && error.stack) || error))
  })

app.on('before-quit', () => {
  cancelAllGeneration()
  try {
    if (tray && !tray.isDestroyed()) tray.destroy()
  } catch {}
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit()
})

app.on('activate', () => {
  if (BrowserWindow.getAllWindows().length === 0) createWindow()
})
