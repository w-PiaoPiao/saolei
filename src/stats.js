class StatsManager {
  constructor() {
    this.key = 'minesweeper_stats'
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
      const raw = localStorage.getItem(this.key)
      if (!raw) return this.defaults()
      return { ...this.defaults(), ...JSON.parse(raw) }
    } catch {
      return this.defaults()
    }
  }

  save() {
    localStorage.setItem(this.key, JSON.stringify(this.data))
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
