const { app, BrowserWindow, Menu, ipcMain } = require('electron')
const path = require('path')
const { Worker } = require('worker_threads')
const { LEVELS, THEMES, WINDOW } = require('./src/config.js')

let mainWindow = null
let muteMenuItem = null
let noGuessMenuItem = null
// 菜单项引用：创建菜单时按固定结构抓取，更新状态不再依赖中文 label 匹配
let diffRadioItems = []
let themeRadioItems = []
const DIFF_KEYS = Object.keys(LEVELS)

function sendToRenderer(action, payload = {}) {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send('menu-action', { action, ...payload })
  }
}

function createMenu() {
  const template = [
    {
      label: '游戏',
      submenu: [
        { label: '新游戏', click: () => sendToRenderer('new-game') },
        {
          label: '静音',
          type: 'checkbox',
          checked: false,
          click: (item) => sendToRenderer('toggle-mute', { value: item.checked })
        },
        {
          label: '无猜模式',
          type: 'checkbox',
          checked: false,
          click: (item) => sendToRenderer('toggle-no-guess', { value: item.checked })
        },
        { label: '键盘快捷键...', click: () => sendToRenderer('keybindings') },
        { type: 'separator' },
        { role: 'quit', label: '退出' }
      ]
    },
    {
      label: '难度',
      submenu: [
        ...Object.keys(LEVELS).map((level) => {
          const cfg = LEVELS[level]
          return {
            label: `${cfg.label} (${cfg.rows}×${cfg.cols}/${cfg.mines}雷)`,
            type: 'radio',
            checked: level === 'beginner',
            click: () => sendToRenderer('difficulty', { level })
          }
        }),
        { type: 'separator' },
        { label: '自定义...', type: 'radio', checked: false, click: () => sendToRenderer('custom') }
      ]
    },
    {
      label: '主题',
      submenu: THEMES.map((theme, i) => ({
        label: theme.label,
        type: 'radio',
        checked: i === 0,
        click: () => sendToRenderer('theme', { value: theme.value })
      }))
    },
    {
      label: '帮助',
      submenu: [
        { label: '游戏统计', click: () => sendToRenderer('stats') },
        { label: '效率曲线', click: () => sendToRenderer('history') },
        { type: 'separator' },
        { label: '关于扫雷', click: () => sendToRenderer('about') }
      ]
    }
  ]

  const menu = Menu.buildFromTemplate(template)
  // 菜单结构固定：0=游戏 1=难度 2=主题 3=帮助
  // 按类型与顺序抓取引用（不依赖中文 label，改文案不会破坏状态同步）：
  // 「游戏」里 checkbox 依次为 静音、无猜模式；两个 radio 组各取全部 radio 项
  const checkItems = menu.items[0].submenu.items.filter((m) => m.type === 'checkbox')
  muteMenuItem = checkItems[0]
  noGuessMenuItem = checkItems[1]
  diffRadioItems = menu.items[1].submenu.items.filter((m) => m.type === 'radio')
  themeRadioItems = menu.items[2].submenu.items.filter((m) => m.type === 'radio')
  Menu.setApplicationMenu(menu)
}

function updateMenuDifficulty(level) {
  // 前 DIFF_KEYS.length 个 radio 对应预设难度，最后一个是「自定义」
  diffRadioItems.forEach((item, i) => {
    item.checked = i < DIFF_KEYS.length ? DIFF_KEYS[i] === level : level === 'custom'
  })
}

function updateMenuTheme(theme) {
  themeRadioItems.forEach((item, i) => {
    item.checked = THEMES[i].value === theme
  })
}

function updateMenuMute(muted) {
  if (muteMenuItem) muteMenuItem.checked = muted
}

function updateMenuNoGuess(enabled) {
  if (noGuessMenuItem) noGuessMenuItem.checked = enabled
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 860,
    height: 680,
    minWidth: 350,
    minHeight: 300,
    resizable: true,
    title: '扫雷',
    icon: path.join(__dirname, 'icon.png'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  })

  mainWindow.loadFile(path.join(__dirname, 'src', 'index.html'))
  mainWindow.webContents.setWindowOpenHandler(() => ({ action: 'deny' }))
  mainWindow.webContents.on('will-navigate', (event) => event.preventDefault())
  mainWindow.on('closed', () => {
    mainWindow = null
  })
}

// ---------- IPC（顶层注册一次） ----------

// 只放大不缩小：不覆盖用户手动调整的窗口尺寸
ipcMain.on('fit-window', (event, width, height) => {
  const win = BrowserWindow.fromWebContents(event.sender)
  if (!win) return
  if (!Number.isFinite(width) || !Number.isFinite(height)) return
  const [w, h] = win.getSize()
  win.setSize(
    Math.max(w, Math.min(WINDOW.maxW, Math.round(width))),
    Math.max(h, Math.min(WINDOW.maxH, Math.round(height)))
  )
})

ipcMain.on('update-menu-difficulty', (_, level) => updateMenuDifficulty(level))
ipcMain.on('update-menu-theme', (_, theme) => updateMenuTheme(theme))
ipcMain.on('update-menu-mute', (_, muted) => updateMenuMute(muted))
ipcMain.on('update-menu-no-guess', (_, enabled) => updateMenuNoGuess(enabled))

// 渲染进程读取应用版本（package.json 单一来源）
ipcMain.handle('app-version', () => app.getVersion())

/**
 * 无猜雷位生成（耗时，可能数百毫秒）：放到 worker 线程执行，
 * 主进程与渲染进程的事件循环都不被阻塞，渲染进程可显示「生成中」并随时作废。
 * 每次请求新建 worker（首点每局仅一次），避免长期驻留线程与请求配对状态。
 *
 * 打包后 worker 文件被 asarUnpack 解到 app.asar.unpacked 下（worker_threads 不走
 * Electron 的 asar 补丁，必须给出真实路径），故这里显式做路径替换。
 */
const GENERATOR_WORKER = path
  .join(__dirname, 'src', 'generator-worker.js')
  .replace(`app.asar${path.sep}`, `app.asar.unpacked${path.sep}`)

ipcMain.handle('generate-mines', (event, req) => {
  const rows = Math.floor(Number(req && req.rows))
  const cols = Math.floor(Number(req && req.cols))
  const mines = Math.floor(Number(req && req.mines))
  const safeRow = Math.floor(Number(req && req.safeRow))
  const safeCol = Math.floor(Number(req && req.safeCol))
  const valid =
    Number.isInteger(rows) && Number.isInteger(cols) && Number.isInteger(mines) &&
    Number.isInteger(safeRow) && Number.isInteger(safeCol) &&
    rows >= 1 && cols >= 1 && mines >= 0 &&
    safeRow >= 0 && safeRow < rows && safeCol >= 0 && safeCol < cols
  if (!valid) return { ok: false, error: '非法生成参数' }

  return new Promise((resolve) => {
    const worker = new Worker(GENERATOR_WORKER, {
      workerData: { rows, cols, mines, safeRow, safeCol, noGuess: true }
    })
    let settled = false
    const finish = (result) => {
      if (settled) return
      settled = true
      resolve(result)
      worker.terminate()
    }
    worker.once('message', (msg) => {
      finish(msg && msg.ok
        ? { ok: true, mines: msg.mines, noGuessFallback: msg.noGuessFallback }
        : { ok: false, error: (msg && msg.error) || '生成失败' })
    })
    worker.once('error', (err) => finish({ ok: false, error: String((err && err.message) || err) }))
    worker.once('exit', (code) => {
      if (code !== 0) finish({ ok: false, error: '生成线程异常退出' })
    })
  })
})

// ---------- 单实例 ----------

// 两个实例共享同一 userData 目录：localStorage 后写覆盖前写，会丢统计/键位/主题。
// 第二个实例直接退出，并把已有窗口提到前台。
if (!app.requestSingleInstanceLock()) {
  app.quit()
  return
}
app.on('second-instance', () => {
  if (!mainWindow || mainWindow.isDestroyed()) return
  if (mainWindow.isMinimized()) mainWindow.restore()
  mainWindow.focus()
})

// ---------- 生命周期 ----------

app.whenReady().then(() => {
  createMenu()
  createWindow()
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit()
})

app.on('activate', () => {
  if (BrowserWindow.getAllWindows().length === 0) createWindow()
})
