class StatsManager {
  // 存储与配置走依赖注入（浏览器缺省用全局 localStorage/CONFIG），
  // 与安卓版 Storage + FakeStorage 模式对齐，node:test 可直接实例化。
  constructor(storage = globalThis.localStorage, config = globalThis.CONFIG) {
    this.storage = storage
    this.key = config.STORAGE_KEYS.stats
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
      totalPlayTime: 0,
      lastLevel: null
    }
  }

  load() {
    try {
      const raw = this.storage.getItem(this.key)
      if (!raw) return this.defaults()
      return { ...this.defaults(), ...JSON.parse(raw) }
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

  onGameWin(time) {
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
      totalPlayTime: d.totalPlayTime
    }
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
