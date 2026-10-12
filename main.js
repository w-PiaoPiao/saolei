const { app, BrowserWindow, Menu, ipcMain } = require('electron')
const path = require('path')
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
  muteMenuItem = menu.items[0].submenu.items.find((m) => m.label === '静音')
  noGuessMenuItem = menu.items[0].submenu.items.find((m) => m.label === '无猜模式')
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
