const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('electronAPI', {
  platform: process.platform,
  resize: (width, height) => ipcRenderer.send('resize', width, height)
})
