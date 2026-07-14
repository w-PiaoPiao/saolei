const { app, BrowserWindow, Menu, ipcMain } = require('electron')
const path = require('path')

let mainWindow

function createMenu() {
  const template = [
    {
      label: '游戏',
      submenu: [
        {
          label: '新游戏',
          accelerator: 'F2',
          click: () => mainWindow.webContents.send('menu-action', { action: 'new-game' })
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

  Menu.setApplicationMenu(Menu.buildFromTemplate(template))
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

function updateMenuTheme(theme) {
  const menu = Menu.getApplicationMenu()
  if (!menu) return
  const themeMenu = menu.items.find(m => m.label === '主题')
  if (!themeMenu) return
  themeMenu.submenu.items.forEach(item => {
    if (item.type === 'radio') item.checked = item.label === (theme === 'light' ? '浅色' : '深色')
  })
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 860,
    height: 680,
    resizable: true,
    title: '扫雷',
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
