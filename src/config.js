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
    // 生成在后台线程执行（渲染进程不冻结），故预算可以放宽以换取更高的无猜成功率：
    // 实测 50×50/500 单次尝试约 32ms，800ms 内约 25 次尝试；高级局数十毫秒内即可命中。
    // 若按单次耗时预估、预算内总量仍不足 MIN_EXPECTED_ATTEMPTS，generator 会立即降级。
    NO_GUESS: {
      maxAttempts: 600,
      budgetMs: 800
    },

    // 历史战绩（效率曲线用）：最多保留最近多少局胜利
    HISTORY_MAX: 50,

    // 格子尺寸：max 用于小棋盘；min 是可读性下限——超大棋盘宁可让 #app 滚动
    // 也不把格子压到看不清（50×50 之前被压到 15px、字号 7.5px）
    CELL_SIZE: {
      min: 18,
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
