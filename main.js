const { app, BrowserWindow, Menu, ipcMain } = require('electron')
const path = require('path')
const { LEVELS, THEMES, WINDOW } = require('./src/config.js')

let mainWindow = null
let muteMenuItem = null

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
        { label: '自定义...', click: () => sendToRenderer('custom') }
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
        { type: 'separator' },
        { label: '关于扫雷', click: () => sendToRenderer('about') }
      ]
    }
  ]

  const menu = Menu.buildFromTemplate(template)
  muteMenuItem = menu.items
    .find((m) => m.label === '游戏')
    .submenu.items.find((m) => m.label === '静音')
  Menu.setApplicationMenu(menu)
}

function updateMenuDifficulty(level) {
  const label = LEVELS[level] ? LEVELS[level].label : '自定义'
  const menu = Menu.getApplicationMenu()
  if (!menu) return
  const diffMenu = menu.items.find((m) => m.label === '难度')
  if (!diffMenu) return
  diffMenu.submenu.items.forEach((item) => {
    if (item.type === 'radio') item.checked = item.label.startsWith(label)
  })
}

function updateMenuTheme(theme) {
  const def = THEMES.find((t) => t.value === theme)
  const label = def ? def.label : THEMES[0].label
  const menu = Menu.getApplicationMenu()
  if (!menu) return
  const themeMenu = menu.items.find((m) => m.label === '主题')
  if (!themeMenu) return
  themeMenu.submenu.items.forEach((item) => {
    if (item.type === 'radio') item.checked = item.label === label
  })
}

function updateMenuMute(muted) {
  if (muteMenuItem) muteMenuItem.checked = muted
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
