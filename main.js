const { app, BrowserWindow, Menu, ipcMain } = require('electron')
const path = require('path')

let mainWindow
let muteMenuItem = null

function createMenu() {
  const template = [
    {
      label: '游戏',
      submenu: [
        {
          label: '新游戏',
          click: () => mainWindow.webContents.send('menu-action', { action: 'new-game' })
        },
        {
          label: '静音',
          type: 'checkbox',
          checked: false,
          click: (item) => mainWindow.webContents.send('menu-action', { action: 'toggle-mute', value: item.checked })
        },
        {
          label: '键盘快捷键...',
          click: () => mainWindow.webContents.send('menu-action', { action: 'keybindings' })
        },
        { type: 'separator' },
        { role: 'quit', label: '退出' }
      ]
    },
    {
      label: '难度',
      submenu: [
        {
          label: '初级 (9×9/10雷)',
          type: 'radio',
          checked: true,
          click: () => mainWindow.webContents.send('menu-action', { action: 'difficulty', level: 'beginner' })
        },
        {
          label: '中级 (16×16/40雷)',
          type: 'radio',
          click: () => mainWindow.webContents.send('menu-action', { action: 'difficulty', level: 'intermediate' })
        },
        {
          label: '高级 (16×30/99雷)',
          type: 'radio',
          click: () => mainWindow.webContents.send('menu-action', { action: 'difficulty', level: 'expert' })
        },
        { type: 'separator' },
        {
          label: '自定义...',
          click: () => mainWindow.webContents.send('menu-action', { action: 'custom' })
        }
      ]
    },
    {
      label: '主题',
      submenu: [
        {
          label: '浅色',
          type: 'radio',
          checked: true,
          click: () => mainWindow.webContents.send('menu-action', { action: 'theme', value: 'light' })
        },
        {
          label: '深色',
          type: 'radio',
          click: () => mainWindow.webContents.send('menu-action', { action: 'theme', value: 'dark' })
        },
        {
          label: 'WinXP 银灰',
          type: 'radio',
          click: () => mainWindow.webContents.send('menu-action', { action: 'theme', value: 'xp-silver' })
        },
        {
          label: 'Vista 蓝',
          type: 'radio',
          click: () => mainWindow.webContents.send('menu-action', { action: 'theme', value: 'vista' })
        },
        {
          label: 'macOS',
          type: 'radio',
          click: () => mainWindow.webContents.send('menu-action', { action: 'theme', value: 'macos' })
        },
        {
          label: 'macOS 深色',
          type: 'radio',
          click: () => mainWindow.webContents.send('menu-action', { action: 'theme', value: 'macos-dark' })
        }
      ]
    },
    {
      label: '帮助',
      submenu: [
        {
          label: '游戏统计',
          click: () => mainWindow.webContents.send('menu-action', { action: 'stats' })
        },
        { type: 'separator' },
        {
          label: '关于扫雷',
          click: () => mainWindow.webContents.send('menu-action', { action: 'about' })
        }
      ]
    }
  ]

  const menu = Menu.buildFromTemplate(template)
  muteMenuItem = menu.items.find(m => m.label === '游戏').submenu.items.find(m => m.label === '静音')
  Menu.setApplicationMenu(menu)
}

function updateMenuDifficulty(level) {
  const labelMap = { beginner: '初级', intermediate: '中级', expert: '高级' }
  const label = labelMap[level] || '自定义'
  const menu = Menu.getApplicationMenu()
  if (!menu) return
  const diffMenu = menu.items.find(m => m.label === '难度')
  if (!diffMenu) return
  diffMenu.submenu.items.forEach(item => {
    if (item.type === 'radio') item.checked = item.label.startsWith(label)
  })
}

const THEME_LABELS = {
  light: '浅色',
  dark: '深色',
  'xp-silver': 'WinXP 银灰',
  vista: 'Vista 蓝',
  macos: 'macOS',
  'macos-dark': 'macOS 深色'
}

function updateMenuTheme(theme) {
  const menu = Menu.getApplicationMenu()
  if (!menu) return
  const themeMenu = menu.items.find(m => m.label === '主题')
  if (!themeMenu) return
  const label = THEME_LABELS[theme] || '浅色'
  themeMenu.submenu.items.forEach(item => {
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
    resizable: true,
    title: '扫雷',
    icon: path.join(__dirname, 'icon.png'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false
    }
  })

  mainWindow.loadFile(path.join(__dirname, 'src', 'index.html'))
  createMenu()

  ipcMain.on('resize', (_, width, height) => {
    mainWindow.setSize(width, height)
  })

  ipcMain.on('update-menu-difficulty', (_, level) => {
    updateMenuDifficulty(level)
  })

  ipcMain.on('update-menu-theme', (_, theme) => {
    updateMenuTheme(theme)
  })

  ipcMain.on('update-menu-mute', (_, muted) => {
    updateMenuMute(muted)
  })
}

app.whenReady().then(createWindow)

app.on('window-all-closed', () => {
  app.quit()
})

app.on('activate', () => {
  if (BrowserWindow.getAllWindows().length === 0) {
    createWindow()
  }
})
