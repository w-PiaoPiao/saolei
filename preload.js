const { contextBridge, ipcRenderer } = require('electron')

// 暴露面保持最小：只给渲染进程需要的 IPC 能力（无 Node、无任意 channel）。
contextBridge.exposeInMainWorld('electronAPI', {
  platform: process.platform,
  /** 系统语言标签（BCP-47），供 i18n 的 system 语言使用 */
  locale: (() => {
    try {
      return navigator.language
    } catch {
      return 'en'
    }
  })(),
  getAppVersion: () => ipcRenderer.invoke('app-version'),
  getLocale: () => ipcRenderer.invoke('app-locale'),

  /** 无猜雷位生成（主进程 worker）：{rows, cols, mines, safeRow, safeCol} → {ok, mines, noGuessFallback} */
  generateMines: (req) => ipcRenderer.invoke('generate-mines', req),
  /** 作废/终止主进程里还在跑的生成 worker（新游戏、切难度、打开对话框、失焦时调用） */
  cancelMinesGeneration: () => ipcRenderer.send('cancel-generate-mines'),

  fitWindow: (width, height) => ipcRenderer.send('fit-window', width, height),

  onMenuAction: (callback) => {
    ipcRenderer.on('menu-action', (_, data) => callback(data))
  },

  updateMenuDifficulty: (level) => ipcRenderer.send('update-menu-difficulty', level),
  updateMenuTheme: (theme) => ipcRenderer.send('update-menu-theme', theme),
  updateMenuMute: (muted) => ipcRenderer.send('update-menu-mute', muted),
  updateMenuNoGuess: (enabled) => ipcRenderer.send('update-menu-no-guess', enabled),
  updateMenuLanguage: (pref) => ipcRenderer.send('update-menu-language', pref)
})
