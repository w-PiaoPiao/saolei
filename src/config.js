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
      bindings: 'minesweeper_bindings',
      noGuess: 'minesweeper_noguess'
    },

    // 无猜模式：布雷后校验可纯逻辑通关，不通过则重新生成
    // （实测高级局单次可解率约 2.5%，600 次尝试内几乎必成，最坏耗时受预算约束）
    NO_GUESS: {
      maxAttempts: 600,
      budgetMs: 500
    },

    // 历史战绩（效率曲线用）：最多保留最近多少局胜利
    HISTORY_MAX: 50,

    CELL_SIZE: {
      min: 12,
      max: 28
    },

    // 键盘移动：长按后的自动重复节奏（近似系统键重复）
    KEYBOARD: {
      repeatDelay: 500,
      repeatInterval: 40
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
