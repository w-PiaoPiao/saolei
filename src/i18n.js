/**
 * 桌面端 i18n（UMD）：主进程（require）与渲染进程（<script> 全局 I18N）共用。
 *
 * - 语言：`zh` / `en`；偏好：`system` / `zh` / `en`（持久化键 `minesweeper_lang`）
 * - `t(key, vars)`：词典查找 + `{{var}}` 插值；缺失 key 时回退 en → key，绝不返回 undefined
 * - 数字/时间格式化必须与语言无关（见 ui-helpers.js），本模块只负责文案
 */
;(function (root, factory) {
  if (typeof module === 'object' && module.exports) {
    module.exports = factory()
  } else {
    root.I18N = factory()
  }
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict'

  const STORAGE_KEY = 'minesweeper_lang'
  const LANG_PREFS = ['system', 'zh', 'en']

  const DICT = {
    zh: {
      'app.title': '扫雷',
      'app.versionLine': '经典扫雷游戏 v{{version}}',

      'level.beginner': '初级',
      'level.intermediate': '中级',
      'level.expert': '高级',
      'level.custom': '自定义',

      'theme.light': '浅色',
      'theme.dark': '深色',
      'theme.xp-silver': 'WinXP 银灰',
      'theme.vista': 'Vista 蓝',
      'theme.macos': 'macOS',
      'theme.macos-dark': 'macOS 深色',

      'menu.game': '游戏',
      'menu.newGame': '新游戏',
      'menu.mute': '静音',
      'menu.noGuess': '无猜模式',
      'menu.keybindings': '键盘快捷键...',
      'menu.quit': '退出',
      'menu.difficulty': '难度',
      'menu.custom': '自定义...',
      'menu.theme': '主题',
      'menu.language': '语言',
      'menu.langSystem': '跟随系统',
      'menu.langZh': '中文',
      'menu.langEn': 'English',
      'menu.help': '帮助',
      'menu.stats': '游戏统计',
      'menu.history': '效率曲线',
      'menu.about': '关于扫雷',
      'menu.levelItem': '{{label}} ({{rows}}×{{cols}}/{{mines}} 雷)',
      'menu.view': '视图',
      'menu.zoomReset': '实际大小',
      'menu.zoomIn': '放大',
      'menu.zoomOut': '缩小',
      'menu.fullscreen': '全屏',

      'hud.mineCounterAria': '剩余雷数 {{count}}',
      'hud.timerAria': '已用时间 {{time}}',
      'hud.faceNewGame': '新游戏',
      'board.ariaLabel': '扫雷棋盘',

      'pause.text': '⏸ 已暂停',
      'noguess.warning': '⚠ 无解保护不可用，本局可能需要猜',
      'generating.hint': '正在生成无猜雷局…',

      'dialog.confirmRestart.title': '重新开始？',
      'dialog.confirmRestart.message': '当前对局将丢失',
      'dialog.ok': '确定',
      'dialog.cancel': '取消',

      'custom.title': '自定义雷区',
      'custom.rows': '行:',
      'custom.cols': '列:',
      'custom.mines': '雷数:',
      'custom.errRows': '行必须是 9 到 50 之间的整数',
      'custom.errCols': '列必须是 9 到 50 之间的整数',
      'custom.errMines': '雷数必须是 1 到 {{max}} 之间的整数',

      'stats.title': '游戏统计',
      'stats.totalGames': '总局数: {{n}}',
      'stats.wonLost': '胜利: {{won}} / 失败: {{lost}}',
      'stats.winRate': '胜率: {{rate}}%',
      'stats.currentStreak': '当前连胜: {{n}}',
      'stats.bestStreak': '最长连胜: {{n}}',
      'stats.totalTime': '总游戏时间: {{time}}',
      'stats.bestTime': '最快完成:',
      'stats.bestEfficiency': '最佳效率（3BV/s）:',
      'stats.close': '关闭',

      'history.title': '效率曲线（3BV/s）',
      'history.difficulty': '难度:',
      'history.chartNoData': '暂无数据',
      'history.chartRecent': '最近 {{n}} 局（右为新）',
      'history.empty': '该难度暂无胜局记录',
      'history.meta': '样本 {{n}} 局 · 平均 {{avg}} · 最佳 {{best}} 3BV/s',
      'history.pausedNote': '（其中 {{n}} 局含暂停）',

      'about.title': '关于扫雷',
      'about.tagline': '经典扫雷游戏',
      'about.electron': 'Electron 跨平台桌面应用',
      'about.vanilla': '纯 JavaScript 实现',
      'about.style': '经典 WinXP 风格',
      'about.close': '关闭',

      'bind.title': '键盘快捷键',
      'bind.reset': '恢复默认',
      'bind.close': '关闭',
      'bind.pressKey': '按下新键...',
      'bind.conflict': '「{{key}}」已被「{{action}}」占用',

      'action.moveUp': '上移',
      'action.moveDown': '下移',
      'action.moveLeft': '左移',
      'action.moveRight': '右移',
      'action.activate': '翻开/和弦',
      'action.flag': '标旗',
      'action.newGame': '新游戏',

      'cell.aria': '第 {{row}} 行 第 {{col}} 列，{{state}}',
      'cell.hidden': '未翻开',
      'cell.mine': '地雷',
      'cell.number': '数字 {{n}}',
      'cell.empty': '空白',
      'cell.flag': '已插旗',
      'cell.wrongFlag': '错标：此处没有雷',
      'cell.question': '问号',

      'record.banner': '新纪录！{{label}} {{time}}',

      'sr.won': '游戏获胜，用时 {{time}}',
      'sr.lost': '游戏失败',
      'sr.newRecord': '新纪录',
      'sr.generated': '无猜雷局已生成',
      'sr.generateFallback': '无解保护不可用，本局可能需要猜',
      'sr.paused': '游戏已暂停',
      'sr.resumed': '游戏继续',

      'msg.loadFailedTitle': '加载失败',
      'msg.renderGoneTitle': '界面进程异常',
      'msg.renderGoneMessage': '界面进程已退出（{{reason}}），可以重新加载。',
      'msg.reload': '重新加载',
      'msg.quit': '退出',
      'msg.unresponsiveTitle': '窗口无响应',
      'msg.unresponsiveMessage': '界面在一段时间内没有响应，可以继续等待或重新加载。',
      'msg.continue': '继续等待',

      'tray.show': '显示窗口',
      'tray.hide': '隐藏窗口',
      'tray.newGame': '新游戏',
      'tray.mute': '静音',
      'tray.quit': '退出'
    },
    en: {
      'app.title': 'Minesweeper',
      'app.versionLine': 'Classic Minesweeper v{{version}}',

      'level.beginner': 'Beginner',
      'level.intermediate': 'Intermediate',
      'level.expert': 'Expert',
      'level.custom': 'Custom',

      'theme.light': 'Light',
      'theme.dark': 'Dark',
      'theme.xp-silver': 'WinXP Silver',
      'theme.vista': 'Vista Blue',
      'theme.macos': 'macOS',
      'theme.macos-dark': 'macOS Dark',

      'menu.game': 'Game',
      'menu.newGame': 'New Game',
      'menu.mute': 'Mute',
      'menu.noGuess': 'No-guess Mode',
      'menu.keybindings': 'Keyboard Shortcuts...',
      'menu.quit': 'Quit',
      'menu.difficulty': 'Difficulty',
      'menu.custom': 'Custom...',
      'menu.theme': 'Theme',
      'menu.language': 'Language',
      'menu.langSystem': 'System',
      'menu.langZh': '中文',
      'menu.langEn': 'English',
      'menu.help': 'Help',
      'menu.stats': 'Statistics',
      'menu.history': 'Efficiency Chart',
      'menu.about': 'About Minesweeper',
      'menu.levelItem': '{{label}} ({{rows}}×{{cols}}/{{mines}} mines)',
      'menu.view': 'View',
      'menu.zoomReset': 'Actual Size',
      'menu.zoomIn': 'Zoom In',
      'menu.zoomOut': 'Zoom Out',
      'menu.fullscreen': 'Toggle Full Screen',

      'hud.mineCounterAria': 'Mines remaining: {{count}}',
      'hud.timerAria': 'Elapsed time {{time}}',
      'hud.faceNewGame': 'New game',
      'board.ariaLabel': 'Minesweeper board',

      'pause.text': '⏸ Paused',
      'noguess.warning': '⚠ No-guess protection unavailable; this board may require guessing',
      'generating.hint': 'Generating a no-guess board…',

      'dialog.confirmRestart.title': 'Restart?',
      'dialog.confirmRestart.message': 'The current game will be lost.',
      'dialog.ok': 'OK',
      'dialog.cancel': 'Cancel',

      'custom.title': 'Custom Board',
      'custom.rows': 'Rows:',
      'custom.cols': 'Columns:',
      'custom.mines': 'Mines:',
      'custom.errRows': 'Rows must be an integer between 9 and 50',
      'custom.errCols': 'Columns must be an integer between 9 and 50',
      'custom.errMines': 'Mines must be an integer between 1 and {{max}}',

      'stats.title': 'Statistics',
      'stats.totalGames': 'Games played: {{n}}',
      'stats.wonLost': 'Won: {{won}} / Lost: {{lost}}',
      'stats.winRate': 'Win rate: {{rate}}%',
      'stats.currentStreak': 'Current streak: {{n}}',
      'stats.bestStreak': 'Best streak: {{n}}',
      'stats.totalTime': 'Total play time: {{time}}',
      'stats.bestTime': 'Best times:',
      'stats.bestEfficiency': 'Best efficiency (3BV/s):',
      'stats.close': 'Close',

      'history.title': 'Efficiency Chart (3BV/s)',
      'history.difficulty': 'Difficulty:',
      'history.chartNoData': 'No data yet',
      'history.chartRecent': 'Last {{n}} games (newest on the right)',
      'history.empty': 'No wins recorded for this difficulty yet',
      'history.meta': '{{n}} games · avg {{avg}} · best {{best}} 3BV/s',
      'history.pausedNote': ' ({{n}} with pauses)',

      'about.title': 'About Minesweeper',
      'about.tagline': 'Classic Minesweeper',
      'about.electron': 'Cross-platform desktop app built with Electron',
      'about.vanilla': 'Written in plain JavaScript',
      'about.style': 'Classic Windows XP style',
      'about.close': 'Close',

      'bind.title': 'Keyboard Shortcuts',
      'bind.reset': 'Reset to Defaults',
      'bind.close': 'Close',
      'bind.pressKey': 'Press a new key...',
      'bind.conflict': '"{{key}}" is already bound to "{{action}}"',

      'action.moveUp': 'Move up',
      'action.moveDown': 'Move down',
      'action.moveLeft': 'Move left',
      'action.moveRight': 'Move right',
      'action.activate': 'Reveal / chord',
      'action.flag': 'Flag',
      'action.newGame': 'New game',

      'cell.aria': 'Row {{row}}, column {{col}}, {{state}}',
      'cell.hidden': 'hidden',
      'cell.mine': 'mine',
      'cell.number': 'number {{n}}',
      'cell.empty': 'empty',
      'cell.flag': 'flagged',
      'cell.wrongFlag': 'wrong flag, no mine here',
      'cell.question': 'question mark',

      'record.banner': 'New record! {{label}} {{time}}',

      'sr.won': 'You win! Time {{time}}',
      'sr.lost': 'Game over',
      'sr.newRecord': 'New record',
      'sr.generated': 'No-guess board generated',
      'sr.generateFallback': 'No-guess protection unavailable; this board may require guessing',
      'sr.paused': 'Game paused',
      'sr.resumed': 'Game resumed',

      'msg.loadFailedTitle': 'Failed to load',
      'msg.renderGoneTitle': 'Renderer crashed',
      'msg.renderGoneMessage': 'The renderer process exited ({{reason}}). You can reload the app.',
      'msg.reload': 'Reload',
      'msg.quit': 'Quit',
      'msg.unresponsiveTitle': 'Window not responding',
      'msg.unresponsiveMessage': 'The window has not responded for a while. You can keep waiting or reload.',
      'msg.continue': 'Keep waiting',

      'tray.show': 'Show Window',
      'tray.hide': 'Hide Window',
      'tray.newGame': 'New Game',
      'tray.mute': 'Mute',
      'tray.quit': 'Quit'
    }
  }

  function detectLanguage(langTag) {
    return /^zh\b/i.test(String(langTag || '')) || /^zh/i.test(String(langTag || '')) ? 'zh' : 'en'
  }

  function systemLanguage() {
    try {
      if (typeof navigator !== 'undefined' && navigator && navigator.language) {
        return detectLanguage(navigator.language)
      }
    } catch {}
    return 'en'
  }

  function interpolate(template, vars) {
    if (!vars) return template
    return String(template).replace(/\{\{\s*([\w.-]+)\s*\}\}/g, (match, name) => {
      const value = vars[name]
      return value === undefined || value === null ? match : String(value)
    })
  }

  const I18N = {
    STORAGE_KEY,
    LANG_PREFS,
    detectLanguage,

    preference: 'system',
    language: 'en',
    systemLang: null,

    /** @param {'system'|'zh'|'en'} pref */
    resolve(pref) {
      const value = LANG_PREFS.includes(pref) ? pref : 'system'
      if (value !== 'system') return value
      return this.systemLang || systemLanguage()
    },

    /**
     * @param {'system'|'zh'|'en'} pref
     * @param {{systemLang?: string, persist?: boolean}} [opts]
     */
    setLanguage(pref, opts = {}) {
      const value = LANG_PREFS.includes(pref) ? pref : 'system'
      this.preference = value
      if (opts.systemLang) this.systemLang = detectLanguage(opts.systemLang)
      this.language = this.resolve(value)
      if (opts.persist && typeof localStorage !== 'undefined') {
        try {
          localStorage.setItem(STORAGE_KEY, value)
        } catch {}
      }
      return this.language
    },

    /** 读取持久化偏好（仅渲染进程；主进程无 localStorage） */
    loadPreference() {
      try {
        if (typeof localStorage === 'undefined') return 'system'
        const saved = localStorage.getItem(STORAGE_KEY)
        return LANG_PREFS.includes(saved) ? saved : 'system'
      } catch {
        return 'system'
      }
    },

    getPreference() {
      return this.preference
    },

    getLanguage() {
      return this.language
    },

    isZh() {
      return this.language === 'zh'
    },

    has(key) {
      return !!(DICT[this.language] && DICT[this.language][key]) || !!(DICT.zh[key])
    },

    /** @param {string} key @param {Record<string, unknown>} [vars] */
    t(key, vars) {
      const dict = DICT[this.language] || DICT.en
      const value = dict[key] !== undefined ? dict[key] : DICT.en[key] !== undefined ? DICT.en[key] : DICT.zh[key]
      if (value === undefined) return key
      return interpolate(value, vars)
    },

    /** 供测试/调试：某语言完整词典（浅拷贝） */
    dict(lang) {
      return { ...(DICT[lang] || {}) }
    }
  }

  // 主进程可在 require 后调用 setLanguage(pref, { systemLang: app.getLocale() })
  return I18N
})
