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

    // 状态机常量：引擎/UI/统计统一引用，避免裸字符串（值必须与历史字符串保持兼容）
    STATES: Object.freeze({ READY: 'ready', PLAYING: 'playing', WON: 'won', LOST: 'lost' }),

    // 预设难度 id 顺序（UI 展示顺序）；自定义难度 id 固定为 'custom'
    LEVEL_IDS: Object.freeze(['beginner', 'intermediate', 'expert']),
    CUSTOM_LEVEL_ID: 'custom',

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

    // 无猜模式：布雷后校验可纯逻辑通关，不通过则重新生成。生成在后台线程执行，预算 800ms。
    // 实测（2026-10-27，M2 求解器提速后，本机）：16×30/99 100%（avg 7.5ms）；16×16/40、9×9/10
    // 100%（<1ms）；50×50/100、50×50/160、50×50/250 100%（≤3ms）；50×50/500（20% 密度）
    // 8/8 成功、avg 178ms / max 383ms；40×40/300（18.75%）6/6 成功（avg 22ms）。
    // 显式无解的配置：40×40/400（25% 密度，约 8800 次尝试 0 成功）、30×30/500、50×50/1000，
    // 门控命中后只试 1–2 次即降级（否则会白等满 800ms）。
    // 旧策略「按单次耗时外推、预算内不足 20 次即降级」已移除：单次 solve 在 50×50 上 ~3ms，
    // 按耗时外推会在 40ms/次的阈值两侧抖动，导致同一配置时快时慢（见 generator.js 注释）。
    NO_GUESS: {
      maxAttempts: 600,
      budgetMs: 800,
      gate: Object.freeze({
        // 确定性门控（M1）：只按尺寸/密度预判，不读取实际耗时
        hopelessDensity: 0.25, // ≥ 该密度且棋盘够大：实测无解率极高，试 1–2 次即降级
        hopelessMinCells: 900, // 密度门控只对 ≥ 该格数的棋盘生效（小盘重试成本低，保留机会）
        gateAttempts: 2 // 门控命中时的最大尝试次数
      })
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
