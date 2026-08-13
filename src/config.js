/**
 * 单一数据源：难度、主题、存储键、窗口/格子尺寸常量。
 * 同时被渲染进程（<script> 全局 CONFIG）和主进程（require）使用。
 */
;(function (root, factory) {
  if (typeof module === 'object' && module.exports) {
    module.exports = factory()
  } else {
    root.CONFIG = factory()
  }
})(typeof self !== 'undefined' ? self : this, function () {
  return {
    LEVELS: {
      beginner: { label: '初级', rows: 9, cols: 9, mines: 10 },
      intermediate: { label: '中级', rows: 16, cols: 16, mines: 40 },
      expert: { label: '高级', rows: 16, cols: 30, mines: 99 }
    },

    THEMES: [
      { value: 'light', label: '浅色' },
      { value: 'dark', label: '深色' },
      { value: 'xp-silver', label: 'WinXP 银灰' },
      { value: 'vista', label: 'Vista 蓝' },
      { value: 'macos', label: 'macOS' },
      { value: 'macos-dark', label: 'macOS 深色' }
    ],

    STORAGE_KEYS: {
      theme: 'minesweeper_theme',
      muted: 'minesweeper_muted',
      stats: 'minesweeper_stats',
      bindings: 'minesweeper_bindings'
    },

    CELL_SIZE: {
      min: 12,
      max: 28
    },

    // 窗口自适应参数（主进程与渲染进程共用）
    WINDOW: {
      maxW: 1200,
      maxH: 900,
      boardMaxW: 1150,
      boardMaxH: 760,
      boardPadX: 44,
      boardPadY: 150
    }
  }
})
