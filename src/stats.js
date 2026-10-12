class StatsManager {
  // 存储与配置走依赖注入（浏览器缺省用全局 localStorage/CONFIG），
  // 与安卓版 Storage + FakeStorage 模式对齐，node:test 可直接实例化。
  constructor(storage = globalThis.localStorage, config = globalThis.CONFIG) {
    this.storage = storage
    this.key = config.STORAGE_KEYS.stats
    this.historyMax = config.HISTORY_MAX ?? 50
    this.data = this.load()
  }

  defaults() {
    return {
      totalGames: 0,
      gamesWon: 0,
      currentStreak: 0,
      bestStreak: 0,
      bestTime: {
        beginner: null,
        intermediate: null,
        expert: null,
        custom: null
      },
      // 各难度最佳 3BV/s（效率统计）
      bestBbbvPerSec: {
        beginner: null,
        intermediate: null,
        expert: null,
        custom: null
      },
      // 最近若干胜利局（效率曲线用）：{level, time, bbbv, ts}
      history: [],
      totalPlayTime: 0,
      lastLevel: null
    }
  }

  load() {
    try {
      const raw = this.storage.getItem(this.key)
      if (!raw) return this.defaults()
      const data = { ...this.defaults(), ...JSON.parse(raw) }
      // 逐难度补齐：旧存档可能缺键
      data.bestTime = { ...this.defaults().bestTime, ...data.bestTime }
      data.bestBbbvPerSec = { ...this.defaults().bestBbbvPerSec, ...data.bestBbbvPerSec }
      if (!Array.isArray(data.history)) data.history = []
      // 空串不是合法难度名：归一为 null，避免被当成"有值但不存在"的难度而误报新纪录
      if (typeof data.lastLevel !== 'string' || data.lastLevel === '') data.lastLevel = null
      return data
    } catch {
      return this.defaults()
    }
  }

  save() {
    this.storage.setItem(this.key, JSON.stringify(this.data))
  }

  onGameStart(level) {
    this.data.totalGames++
    this.data.lastLevel = level
    this.save()
  }

  onGameWin(time, bbbv = 0) {
    this.data.gamesWon++
    this.data.currentStreak++
    if (this.data.currentStreak > this.data.bestStreak) {
      this.data.bestStreak = this.data.currentStreak
    }
    const level = this.data.lastLevel
    if (level && level !== 'custom') {
      if (this.data.bestTime[level] === null || time < this.data.bestTime[level]) {
        this.data.bestTime[level] = time
      }
    } else if (level === 'custom') {
      if (this.data.bestTime.custom === null || time < this.data.bestTime.custom) {
        this.data.bestTime.custom = time
      }
    }
    // 3BV 效率：刷新该难度最佳 3BV/s，并把本局写入历史（曲线数据源）
    if (level && bbbv > 0 && time > 0) {
      const rate = bbbv / time
      if (this.data.bestBbbvPerSec[level] == null || rate > this.data.bestBbbvPerSec[level]) {
        this.data.bestBbbvPerSec[level] = rate
      }
      this.data.history.push({ level, time, bbbv, ts: Date.now() })
      if (this.data.history.length > this.historyMax) {
        this.data.history.splice(0, this.data.history.length - this.historyMax)
      }
    }
    this.data.totalPlayTime += time
    this.save()
  }

  onGameLose(time) {
    this.data.currentStreak = 0
    this.data.totalPlayTime += time
    this.save()
  }

  getWinRate() {
    if (this.data.totalGames === 0) return 0
    return Math.round((this.data.gamesWon / this.data.totalGames) * 100)
  }

  /** 最近一次开局的难度（胜利时用于判断破纪录与记录历史） */
  get lastLevel() {
    return this.data.lastLevel
  }

  /** 某难度当前最快时间；null 表示尚无记录 */
  bestTimeOf(level) {
    if (!level) return null
    const t = this.data.bestTime[level]
    return t === undefined ? null : t
  }

  getSummary() {
    const d = this.data
    return {
      totalGames: d.totalGames,
      gamesWon: d.gamesWon,
      gamesLost: d.totalGames - d.gamesWon,
      winRate: this.getWinRate(),
      currentStreak: d.currentStreak,
      bestStreak: d.bestStreak,
      bestTime: d.bestTime,
      bestBbbvPerSec: d.bestBbbvPerSec,
      totalPlayTime: d.totalPlayTime
    }
  }

  /** 某难度最近的胜利记录（时间升序，供效率曲线绘制） */
  getHistory(level, limit = 20) {
    return this.data.history.filter((e) => e.level === level).slice(-limit)
  }

  reset() {
    this.data = this.defaults()
    this.save()
  }
}

// 浏览器端由 index.html <script> 加载为全局类；node:test 环境按 CommonJS 导出
if (typeof module === 'object' && module.exports) {
  module.exports = StatsManager
}
