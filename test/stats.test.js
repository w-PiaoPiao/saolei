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

// ---------- 基础统计口径 ----------

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
  assert.equal(s.currentBucket, null)
})

test('开局计入总局数并记录难度', () => {
  const { stats } = make()
  stats.onGameStart('expert')
  assert.equal(stats.data.totalGames, 1)
  assert.equal(stats.data.lastLevel, 'expert')
  assert.equal(stats.currentBucket, 'expert')
})

test('胜利更新胜场、连胜、最快时间（bestTime 单位为毫秒）', () => {
  const { stats } = make()
  stats.onGameStart('beginner')
  stats.onGameWin(42)
  stats.onGameStart('beginner')
  stats.onGameWin(30)
  const s = stats.getSummary()
  assert.equal(s.gamesWon, 2)
  assert.equal(s.currentStreak, 2)
  assert.equal(s.bestStreak, 2)
  assert.equal(s.bestTime.beginner, 30000) // 取更快的一局；无 durationMs 时 time*1000
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

test('总游戏时间按各局用时累计（整秒）', () => {
  const { stats } = make()
  stats.onGameWin(100)
  stats.onGameLose(50)
  assert.equal(stats.data.totalPlayTime, 150)
})

test('自定义难度（旧调用无盘面信息）归入 custom 桶', () => {
  const { stats } = make()
  stats.onGameStart('custom')
  stats.onGameWin(77)
  assert.equal(stats.currentBucket, 'custom')
  assert.equal(stats.data.bestTime.custom, 77000)
  assert.equal(stats.getSummary().bestTime.beginner, null)
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
  assert.equal(s.bestTime.intermediate, 25000)
  assert.equal(s.currentStreak, 1)
})

test('损坏的存档 JSON 回退到默认值', () => {
  const storage = fakeStorage()
  storage.setItem(CONFIG.STORAGE_KEYS.stats, '{not-json')
  const stats = new StatsManager(storage, CONFIG)
  assert.equal(stats.data.totalGames, 0)
})

test('存档为数组/字符串/数字等非对象时回退默认值（M5）', () => {
  for (const raw of ['[]', '"abc"', '5', 'null', 'true']) {
    const storage = fakeStorage()
    storage.setItem(CONFIG.STORAGE_KEYS.stats, raw)
    const stats = new StatsManager(storage, CONFIG)
    assert.equal(stats.data.totalGames, 0, `raw=${raw}`)
    assert.deepEqual(stats.getBuckets().slice(0, 3), CONFIG.LEVEL_IDS)
  }
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

// ---------- 3BV 效率与 history ----------

test('胜利记录 3BV 效率并写入历史（3BV/s 用 durationMs）', () => {
  const { stats } = make()
  stats.onGameStart('beginner')
  stats.onGameWin(30, 45) // 45 3BV / 30s = 1.5 3BV/s
  const s = stats.getSummary()
  assert.equal(s.bestBbbvPerSec.beginner, 1.5)
  const h = stats.getHistory('beginner')
  assert.equal(h.length, 1)
  assert.equal(h[0].bucket, 'beginner')
  assert.equal(h[0].bbbv, 45)
  assert.equal(h[0].time, 30)
  assert.equal(h[0].durationMs, 30000)
  assert.equal(h[0].paused, false)
  assert.equal(h[0].wonByFlags, false)
  assert.ok(h[0].ts > 0)
})

test('3BV/s 用毫秒精度计算（durationMs 优先于整秒 time）', () => {
  const { stats } = make()
  stats.onGameStart('beginner')
  stats.onGameWin(12, 45, { durationMs: 12500 }) // 45 / 12.5 = 3.6
  assert.equal(stats.getSummary().bestBbbvPerSec.beginner, 3.6)
  assert.equal(stats.getSummary().bestTime.beginner, 12500)
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

test('历史按桶过滤，每桶各自受 HISTORY_MAX 上限约束（M6）', () => {
  const { stats } = make()
  for (let i = 0; i < 60; i++) {
    stats.onGameStart('beginner')
    stats.onGameWin(30 + i, 40)
  }
  stats.onGameStart('expert')
  stats.onGameWin(100, 120)
  assert.equal(stats.getHistory('beginner').length, 20) // 默认取最近 20 局
  assert.equal(stats.getHistory('beginner', 99).length, CONFIG.HISTORY_MAX) // 单桶保留 50
  assert.equal(stats.getHistory('expert').length, 1)
  // 分桶后 total 上限为「桶数 × HISTORY_MAX」，不再互相挤占
  assert.equal(stats.data.history.length, CONFIG.HISTORY_MAX + 1)
  // 截断后保留的是最新的记录
  assert.equal(stats.data.history[stats.data.history.length - 1].bucket, 'expert')
  // 旧难度曲线不会被新难度挤空
  assert.ok(stats.getHistory('beginner', 99).length > 0)
})

test('未带 3BV 的胜利不写历史（兼容旧调用）', () => {
  const { stats } = make()
  stats.onGameStart('beginner')
  stats.onGameWin(30)
  assert.equal(stats.data.history.length, 0)
  assert.equal(stats.getSummary().bestBbbvPerSec.beginner, null)
})

// ---------- 分桶（自定义盘面） ----------

test('自定义盘面按 custom:<rows>x<cols>/<mines> 分桶（M6）', () => {
  const { stats } = make()
  stats.onGameStart('custom', { rows: 16, cols: 16, mines: 40 })
  assert.equal(stats.currentBucket, 'custom:16x16/40')
  stats.onGameWin(30, 45)
  assert.equal(stats.bestTimeOf('custom:16x16/40'), 30000)
  assert.equal(stats.getHistory('custom:16x16/40').length, 1)

  stats.onGameStart('custom', { rows: 9, cols: 9, mines: 10 })
  stats.onGameWin(5, 20)
  // 两个盘面互不覆盖
  assert.equal(stats.bestTimeOf('custom:16x16/40'), 30000)
  assert.equal(stats.bestTimeOf('custom:9x9/10'), 5000)
  const buckets = stats.getBuckets()
  assert.ok(buckets.includes('custom:16x16/40'))
  assert.ok(buckets.includes('custom:9x9/10'))
  assert.deepEqual(buckets.slice(0, 3), CONFIG.LEVEL_IDS)
})

test('切回预设难度时 currentCustom 清空，bucket 回到 level', () => {
  const { stats } = make()
  stats.onGameStart('custom', { rows: 9, cols: 9, mines: 10 })
  stats.onGameStart('beginner')
  assert.equal(stats.currentBucket, 'beginner')
  assert.equal(stats.data.currentCustom, null)
})

// ---------- 0 秒 / 暂停 / 插旗胜利标记（M7） ----------

test('0 秒且无 durationMs 的胜利不更新 bestTime / 不写历史（DE-11）', () => {
  const { stats } = make()
  stats.onGameStart('beginner')
  stats.onGameWin(0, 30)
  assert.equal(stats.getSummary().bestTime.beginner, null)
  assert.equal(stats.data.history.length, 0)
})

test('0 秒但有 durationMs 的胜利按毫秒记录（不再出现 0 秒纪录）', () => {
  const { stats } = make()
  stats.onGameStart('beginner')
  stats.onGameWin(0, 30, { durationMs: 350 })
  assert.equal(stats.bestTimeOf('beginner'), 350)
  const h = stats.getHistory('beginner')
  assert.equal(h.length, 1)
  assert.equal(h[0].durationMs, 350)
  assert.equal(stats.getSummary().bestBbbvPerSec.beginner, 30 / 0.35)
  // 更快的一局仍可刷新
  stats.onGameStart('beginner')
  stats.onGameWin(0, 30, { durationMs: 200 })
  assert.equal(stats.bestTimeOf('beginner'), 200)
})

test('含暂停的胜利写 history 并标记 paused，但不更新任何 best（M7）', () => {
  const { stats } = make()
  stats.onGameStart('beginner')
  stats.onGameWin(10, 45, { durationMs: 10000, pausedMs: 4000 })
  const h = stats.getHistory('beginner')
  assert.equal(h.length, 1)
  assert.equal(h[0].paused, true)
  assert.equal(stats.getSummary().bestTime.beginner, null)
  assert.equal(stats.getSummary().bestBbbvPerSec.beginner, null)
  // 后续不含暂停的局仍可正常刷新
  stats.onGameStart('beginner')
  stats.onGameWin(20, 45)
  assert.equal(stats.getSummary().bestTime.beginner, 20000)
  assert.equal(stats.getSummary().bestBbbvPerSec.beginner, 2.25)
})

test('插对全部旗获胜：写 history 标记 wonByFlags，不更新 3BV/s best（M7/DE-13）', () => {
  const { stats } = make()
  stats.onGameStart('beginner')
  stats.onGameWin(3, 25, { durationMs: 3000, wonByFlags: true })
  const h = stats.getHistory('beginner')
  assert.equal(h.length, 1)
  assert.equal(h[0].wonByFlags, true)
  assert.equal(stats.getSummary().bestBbbvPerSec.beginner, null)
  // 契约：wonByFlags 只限制 3BV/s best，最快时间仍记录
  assert.equal(stats.bestTimeOf('beginner'), 3000)
})

// ---------- 损坏/旧版存档（M5）与 API 边界 ----------

test('旧存档迁移：秒 → 毫秒、history 补 bucket、坏条目丢弃', () => {
  const storage = fakeStorage()
  storage.setItem(
    CONFIG.STORAGE_KEYS.stats,
    JSON.stringify({
      totalGames: 5,
      gamesWon: 3,
      bestTime: { beginner: 20, custom: 7 },
      bestBbbvPerSec: { beginner: 1.5 },
      history: [
        { level: 'beginner', time: 30, bbbv: 40, ts: 111 },
        { level: 'custom', time: 9, bbbv: 5, ts: 222 },
        null,
        { level: 'beginner', time: 'bad' },
        'junk'
      ],
      lastLevel: 'beginner'
    })
  )
  const stats = new StatsManager(storage, CONFIG)
  const s = stats.getSummary()
  assert.equal(s.totalGames, 5)
  assert.equal(s.bestTime.beginner, 20000)
  assert.equal(s.bestTime.custom, 7000)
  assert.equal(s.bestBbbvPerSec.beginner, 1.5)
  assert.equal(stats.getHistory('beginner').length, 1)
  assert.equal(stats.getHistory('custom').length, 1)
  assert.equal(stats.data.history[0].bucket, 'beginner')
})

test('类型损坏：数值字段归零、history 条目清洗、getHistory 不再抛错（M5）', () => {
  const storage = fakeStorage()
  storage.setItem(
    CONFIG.STORAGE_KEYS.stats,
    JSON.stringify({
      totalGames: 'abc',
      gamesWon: { bad: 1 },
      currentStreak: null,
      bestStreak: 4,
      bestTime: { beginner: '20', expert: 3 },
      bestBbbvPerSec: { beginner: 'x', expert: 2 },
      history: [null, { level: 'beginner', bbbv: '', time: {} }],
      lastLevel: 42
    })
  )
  const stats = new StatsManager(storage, CONFIG)
  assert.equal(stats.data.totalGames, 0)
  assert.equal(stats.data.gamesWon, 0)
  assert.equal(stats.data.bestStreak, 4)
  assert.equal(stats.data.bestTime.beginner, undefined)
  assert.equal(stats.data.bestTime.expert, 3000)
  assert.equal(stats.data.bestBbbvPerSec.expert, 2)
  assert.deepEqual(stats.data.history, [])
  assert.equal(stats.lastLevel, null)
  assert.equal(stats.getWinRate(), 0)
  assert.doesNotThrow(() => stats.getHistory('beginner'))
})

test('save() 抛错（localStorage 不可写）时不中断对局终局（M5）', () => {
  const storage = fakeStorage()
  const stats = new StatsManager(storage, CONFIG)
  stats.onGameStart('beginner')
  storage.setItem = () => {
    throw new Error('QuotaExceededError')
  }
  assert.doesNotThrow(() => stats.onGameWin(10, 20))
  assert.equal(stats.data.gamesWon, 1) // 内存统计仍生效
})

test('getHistory limit<=0 返回空（与安卓一致）', () => {
  const { stats } = make()
  stats.onGameStart('beginner')
  stats.onGameWin(10, 20)
  assert.equal(stats.getHistory('beginner', 0).length, 0)
  assert.equal(stats.getHistory('beginner', -1).length, 0)
  assert.equal(stats.getHistory('nope').length, 0)
  assert.equal(stats.getHistory(null).length, 0)
})

test('getBuckets 返回 string[]，预设难度恒在', () => {
  const { stats } = make()
  const buckets = stats.getBuckets()
  assert.ok(Array.isArray(buckets))
  assert.deepEqual(buckets.slice(0, 3), CONFIG.LEVEL_IDS)
  for (const key of buckets) assert.equal(typeof key, 'string')
})

test('getSummary/getHistory 返回深拷贝，修改不污染持久化数据（L15）', () => {
  const { stats } = make()
  stats.onGameStart('beginner')
  stats.onGameWin(10, 20)
  const s = stats.getSummary()
  s.bestTime.beginner = 1
  s.bestBbbvPerSec.beginner = 99
  const h = stats.getHistory('beginner')
  h[0].bbbv = 999
  assert.equal(stats.data.bestTime.beginner, 10000)
  assert.equal(stats.data.history[0].bbbv, 20)
})

test('存档中的空串 lastLevel 归一为 null（避免误报新纪录）', () => {
  const storage = fakeStorage()
  storage.setItem(
    CONFIG.STORAGE_KEYS.stats,
    JSON.stringify({ totalGames: 1, gamesWon: 0, lastLevel: '' })
  )
  const stats = new StatsManager(storage, CONFIG)
  assert.equal(stats.lastLevel, null)
  assert.equal(stats.bestTimeOf(stats.lastLevel), null)
})

test('非字符串 lastLevel 同样归一为 null', () => {
  const storage = fakeStorage()
  storage.setItem(CONFIG.STORAGE_KEYS.stats, JSON.stringify({ lastLevel: 42 }))
  const stats = new StatsManager(storage, CONFIG)
  assert.equal(stats.lastLevel, null)
})
