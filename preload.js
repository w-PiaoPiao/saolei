const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('electronAPI', {
  platform: process.platform,
  fitWindow: (width, height) => ipcRenderer.send('fit-window', width, height),
  onMenuAction: (callback) => {
    ipcRenderer.on('menu-action', (_, data) => callback(data))
  },
  updateMenuDifficulty: (level) => ipcRenderer.send('update-menu-difficulty', level),
  updateMenuTheme: (theme) => ipcRenderer.send('update-menu-theme', theme),
  updateMenuMute: (muted) => ipcRenderer.send('update-menu-mute', muted)
})
