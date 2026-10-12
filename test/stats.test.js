'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const StatsManager = require('../src/stats.js')
const CONFIG = require('../src/config.js')

/** 与安卓版 FakeStorage 对齐的极简内存存储 */
function fakeStorage() {
  const map = new Map()
  return {
    getItem: (k) => (map.has(k) ? map.get(k) : null),
    setItem: (k, v) => map.set(k, String(v)),
    removeItem: (k) => map.delete(k)
  }
}

function make() {
  const storage = fakeStorage()
  return { storage, stats: new StatsManager(storage, CONFIG) }
}

test('初始值为空统计', () => {
  const { stats } = make()
  const s = stats.getSummary()
  assert.equal(s.totalGames, 0)
  assert.equal(s.gamesWon, 0)
  assert.equal(s.gamesLost, 0)
  assert.equal(s.winRate, 0)
  assert.equal(s.currentStreak, 0)
  assert.equal(s.bestStreak, 0)
  assert.equal(s.totalPlayTime, 0)
  assert.equal(s.bestTime.beginner, null)
})

test('开局计入总局数并记录难度', () => {
  const { stats } = make()
  stats.onGameStart('expert')
  assert.equal(stats.data.totalGames, 1)
  assert.equal(stats.data.lastLevel, 'expert')
})

test('胜利更新胜场、连胜、最快时间', () => {
  const { stats } = make()
  stats.onGameStart('beginner')
  stats.onGameWin(42)
  stats.onGameStart('beginner')
  stats.onGameWin(30)
  const s = stats.getSummary()
  assert.equal(s.gamesWon, 2)
  assert.equal(s.currentStreak, 2)
  assert.equal(s.bestStreak, 2)
  assert.equal(s.bestTime.beginner, 30) // 取更快的一局
})

test('失败清零当前连胜但保留最长连胜', () => {
  const { stats } = make()
  stats.onGameStart('beginner')
  stats.onGameWin(10)
  stats.onGameStart('beginner')
  stats.onGameWin(10)
  stats.onGameStart('beginner')
  stats.onGameLose(20)
  const s = stats.getSummary()
  assert.equal(s.currentStreak, 0)
  assert.equal(s.bestStreak, 2)
  assert.equal(s.gamesWon, 2)
  assert.equal(s.gamesLost, 1)
})

test('总游戏时间按各局用时累计（封顶后的局内秒数）', () => {
  const { stats } = make()
  stats.onGameWin(100)
  stats.onGameLose(50)
  assert.equal(stats.data.totalPlayTime, 150)
})

test('自定义难度的最快时间独立记录', () => {
  const { stats } = make()
  stats.onGameStart('custom')
  stats.onGameWin(77)
  assert.equal(stats.data.bestTime.custom, 77)
  assert.equal(stats.data.bestTime.beginner, null)
})

test('胜率四舍五入', () => {
  const { stats } = make()
  for (let i = 0; i < 3; i++) {
    stats.onGameStart('beginner')
    stats.onGameWin(10)
  }
  stats.onGameStart('beginner')
  stats.onGameLose(10)
  // 3/4 = 75%
  assert.equal(stats.getWinRate(), 75)
})

test('数据经存储持久化，新实例可读回', () => {
  const storage = fakeStorage()
  const a = new StatsManager(storage, CONFIG)
  a.onGameStart('intermediate')
  a.onGameWin(25)

  const b = new StatsManager(storage, CONFIG)
  const s = b.getSummary()
  assert.equal(s.totalGames, 1)
  assert.equal(s.gamesWon, 1)
  assert.equal(s.bestTime.intermediate, 25)
  assert.equal(s.currentStreak, 1)
})

test('损坏的存档 JSON 回退到默认值', () => {
  const storage = fakeStorage()
  storage.setItem(CONFIG.STORAGE_KEYS.stats, '{not-json')
  const stats = new StatsManager(storage, CONFIG)
  assert.equal(stats.data.totalGames, 0)
})

test('reset 清空所有统计', () => {
  const { stats } = make()
  stats.onGameWin(10)
  stats.reset()
  const s = stats.getSummary()
  assert.equal(s.totalGames, 0)
  assert.equal(s.gamesWon, 0)
  assert.equal(s.bestTime.beginner, null)
  assert.equal(s.bestBbbvPerSec.beginner, null)
  assert.equal(stats.data.history.length, 0)
})

test('胜利记录 3BV 效率并写入历史', () => {
  const { stats } = make()
  stats.onGameStart('beginner')
  stats.onGameWin(30, 45) // 45 3BV / 30s = 1.5 3BV/s
  const s = stats.getSummary()
  assert.equal(s.bestBbbvPerSec.beginner, 1.5)
  const h = stats.getHistory('beginner')
  assert.equal(h.length, 1)
  assert.equal(h[0].bbbv, 45)
  assert.equal(h[0].time, 30)
  assert.ok(h[0].ts > 0)
})

test('最佳效率只在更快的一局时刷新', () => {
  const { stats } = make()
  stats.onGameStart('beginner')
  stats.onGameWin(30, 45) // 1.5
  stats.onGameStart('beginner')
  stats.onGameWin(60, 45) // 0.75，不刷新
  assert.equal(stats.getSummary().bestBbbvPerSec.beginner, 1.5)
  stats.onGameStart('beginner')
  stats.onGameWin(20, 50) // 2.5，刷新
  assert.equal(stats.getSummary().bestBbbvPerSec.beginner, 2.5)
})

test('历史按难度过滤，总数受 HISTORY_MAX 上限约束', () => {
  const { stats } = make()
  for (let i = 0; i < 60; i++) {
    stats.onGameStart('beginner')
    stats.onGameWin(30 + i, 40)
  }
  stats.onGameStart('expert')
  stats.onGameWin(100, 120)
  assert.equal(stats.getHistory('beginner').length, 20) // 默认取最近 20 局
  assert.equal(stats.getHistory('expert').length, 1)
  assert.equal(stats.data.history.length, CONFIG.HISTORY_MAX)
  // 截断后保留的是最新的记录
  assert.equal(stats.data.history[stats.data.history.length - 1].level, 'expert')
})

test('未带 3BV 的胜利不写历史（兼容旧调用）', () => {
  const { stats } = make()
  stats.onGameStart('beginner')
  stats.onGameWin(30)
  assert.equal(stats.data.history.length, 0)
  assert.equal(stats.getSummary().bestBbbvPerSec.beginner, null)
})

test('旧存档缺 3BV 相关键时自动补齐', () => {
  const storage = fakeStorage()
  storage.setItem(
    CONFIG.STORAGE_KEYS.stats,
    JSON.stringify({ totalGames: 5, gamesWon: 3, bestTime: { beginner: 20 } })
  )
  const stats = new StatsManager(storage, CONFIG)
  const s = stats.getSummary()
  assert.equal(s.totalGames, 5)
  assert.equal(s.bestTime.beginner, 20)
  assert.equal(s.bestTime.expert, null)
  assert.equal(s.bestBbbvPerSec.expert, null)
  assert.deepEqual(stats.data.history, [])
})
