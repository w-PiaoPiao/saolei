/**
 * 游戏统计（M5/M6/M7）：总局、胜率、连胜、最快时间、总时长、3BV 效率与历史战绩。
 *
 * 存储：单 JSON key（`minesweeper_stats`），读写都做容错（损坏/旧版存档不崩）。
 * 分桶（M6）：`bucket` = 预设难度 id（beginner/intermediate/expert）或
 *   `custom:<rows>x<cols>/<mines>`（自定义盘面）；history 每个桶各自限长 HISTORY_MAX。
 *   旧存档（无 bucket 字段的 history / 秒为单位的 bestTime）在 load() 中自动迁移。
 * 计量（M7）：bestTime 以**毫秒**存储（历史秒值迁移时 ×1000）；3BV/s 用 durationMs 计算；
 *   `time <= 0 && !(durationMs > 0)` 不更新 bestTime；paused 局写 history 但不更新 best；
 *   wonByFlags 局写 history 标记但不更新 3BV/s best。
 *
 * 存储与配置走依赖注入（浏览器缺省用全局 localStorage/CONFIG），
 * 与安卓版 Storage + FakeStorage 模式对齐，node:test 可直接实例化。
 */
class StatsManager {
  constructor(storage = globalThis.localStorage, config = globalThis.CONFIG) {
    this.storage = storage
    this.key = config.STORAGE_KEYS.stats
    this.historyMax = config.HISTORY_MAX ?? 50
    this.customLevel = config.CUSTOM_LEVEL_ID || 'custom'
    this.levelIds = Array.isArray(config.LEVEL_IDS)
      ? config.LEVEL_IDS.slice()
      : ['beginner', 'intermediate', 'expert']
    this.data = this.load()
  }

  // ---------- 默认值与校验工具 ----------

  defaults() {
    return {
      schemaVersion: 2,
      totalGames: 0,
      gamesWon: 0,
      currentStreak: 0,
      bestStreak: 0,
      // bucket -> 毫秒
      bestTime: {},
      // bucket -> 3BV/s
      bestBbbvPerSec: {},
      // {bucket, level, time(秒), durationMs, bbbv, ts, paused, wonByFlags}
      history: [],
      totalPlayTime: 0,
      // 最近一次开局的难度 id（保持旧语义：字符串 level）
      lastLevel: null,
      // 最近一次自定义盘面（用于计算 custom bucket）
      currentCustom: null
    }
  }

  load() {
    try {
      const raw = this.storage.getItem(this.key)
      if (!raw) return this.defaults()
      const parsed = JSON.parse(raw)
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return this.defaults()
      const data = this.defaults()
      data.totalGames = numberOf(parsed.totalGames, 0)
      data.gamesWon = numberOf(parsed.gamesWon, 0)
      data.currentStreak = numberOf(parsed.currentStreak, 0)
      data.bestStreak = numberOf(parsed.bestStreak, 0)
      data.totalPlayTime = numberOf(parsed.totalPlayTime, 0)
      // 空串不是合法难度名：归一为 null，避免被当成"有值但不存在"的难度而误报新纪录
      data.lastLevel =
        typeof parsed.lastLevel === 'string' && parsed.lastLevel !== '' ? parsed.lastLevel : null
      data.currentCustom = normalizeCustom(parsed.currentCustom)
      const legacySeconds = parsed.schemaVersion !== 2
      data.bestTime = normalizeNumberMap(parsed.bestTime, legacySeconds, false)
      data.bestBbbvPerSec = normalizeNumberMap(parsed.bestBbbvPerSec, false, true)
      data.history = this.normalizeHistory(parsed.history, legacySeconds)
      return data
    } catch {
      return this.defaults()
    }
  }

  /**
   * 历史记录逐条校验 + 旧存档迁移：
   * 旧格式 {level, time, bbbv, ts} → {bucket: level, level, time, durationMs, bbbv, ts, paused, wonByFlags}
   * 非法条目（null/缺字段/NaN）直接丢弃。
   */
  normalizeHistory(raw, legacySeconds) {
    if (!Array.isArray(raw)) return []
    const list = []
    for (const entry of raw) {
      if (!entry || typeof entry !== 'object' || Array.isArray(entry)) continue
      let bucket = typeof entry.bucket === 'string' && entry.bucket !== '' ? entry.bucket : null
      const level = typeof entry.level === 'string' && entry.level !== '' ? entry.level : null
      if (!bucket) bucket = level
      if (!bucket) continue
      const timeSec = Number.isFinite(entry.time) && entry.time >= 0 ? entry.time : null
      let durationMs = Number.isFinite(entry.durationMs) && entry.durationMs >= 0 ? entry.durationMs : null
      if (durationMs === null && timeSec !== null) durationMs = timeSec * 1000
      if (durationMs === null) continue
      const bbbv = Number.isFinite(entry.bbbv) && entry.bbbv >= 0 ? entry.bbbv : 0
      const ts = Number.isFinite(entry.ts) && entry.ts >= 0 ? entry.ts : 0
      list.push({
        bucket,
        level: level || (bucket.startsWith(this.customLevel) ? this.customLevel : bucket),
        time: timeSec === null ? durationMs / 1000 : timeSec,
        durationMs,
        bbbv,
        ts,
        paused: entry.paused === true,
        wonByFlags: entry.wonByFlags === true
      })
    }
    // 逐桶裁剪（保留每个桶各自最新 historyMax 条）
    const counts = new Map()
    const trimmed = []
    for (let i = list.length - 1; i >= 0; i--) {
      const entry = list[i]
      const n = counts.get(entry.bucket) || 0
      if (n >= this.historyMax) continue
      counts.set(entry.bucket, n + 1)
      trimmed.push(entry)
    }
    return trimmed.reverse()
  }

  save() {
    try {
      this.storage.setItem(this.key, JSON.stringify(this.data))
    } catch (err) {
      // M5：存储不可写（配额/权限）不应中断对局终局渲染，降级为仅内存统计
      if (typeof console !== 'undefined' && console.warn) {
        console.warn('[stats] 保存统计失败：', (err && err.message) || err)
      }
    }
  }

  // ---------- 开局/终局 ----------

  /** 分桶 key：预设难度=level；自定义=`custom:<rows>x<cols>/<mines>` */
  bucketOf(level, custom) {
    if (typeof level !== 'string' || level === '') return null
    if (level === this.customLevel) {
      const c = normalizeCustom(custom)
      if (c) return `${this.customLevel}:${c.rows}x${c.cols}/${c.mines}`
    }
    return level
  }

  /** 当前局分桶（供 UI 判断破纪录/曲线） */
  get currentBucket() {
    return this.bucketOf(this.data.lastLevel, this.data.currentCustom)
  }

  onGameStart(level, custom = null) {
    this.data.totalGames++
    this.data.lastLevel = typeof level === 'string' && level !== '' ? level : null
    this.data.currentCustom =
      this.data.lastLevel === this.customLevel ? normalizeCustom(custom) : null
    this.save()
  }

  onGameWin(time, bbbv = 0, meta = null) {
    const m = meta && typeof meta === 'object' ? meta : {}
    const timeSec = Number.isFinite(time) && time >= 0 ? time : 0
    const mineCount = Number.isFinite(bbbv) && bbbv > 0 ? bbbv : 0
    const hasDuration = Number.isFinite(m.durationMs) && m.durationMs > 0
    const durationMs = hasDuration ? m.durationMs : timeSec * 1000
    const paused = Number.isFinite(m.pausedMs) && m.pausedMs > 0
    const wonByFlags = m.wonByFlags === true

    this.data.gamesWon++
    this.data.currentStreak++
    if (this.data.currentStreak > this.data.bestStreak) {
      this.data.bestStreak = this.data.currentStreak
    }

    const bucket = this.currentBucket
    if (bucket) {
      // M7 规则：time<=0 且没有 durationMs 的局不更新 bestTime；paused 局不更新任何 best
      const canUpdateBest = !paused && (timeSec > 0 || hasDuration) && durationMs > 0
      if (canUpdateBest) {
        const prev = this.data.bestTime[bucket]
        if (prev == null || durationMs < prev) this.data.bestTime[bucket] = durationMs
      }
      if (mineCount > 0 && durationMs > 0) {
        const rate = mineCount / (durationMs / 1000)
        if (!paused && !wonByFlags) {
          const prevRate = this.data.bestBbbvPerSec[bucket]
          if (prevRate == null || rate > prevRate) this.data.bestBbbvPerSec[bucket] = rate
        }
        this.data.history.push({
          bucket,
          level: this.data.lastLevel || bucket,
          time: timeSec,
          durationMs,
          bbbv: mineCount,
          ts: Date.now(),
          paused,
          wonByFlags
        })
        this.trimBucket(bucket)
      }
    }
    this.data.totalPlayTime += timeSec
    this.save()
  }

  onGameLose(time, meta = null) {
    const timeSec = Number.isFinite(time) && time >= 0 ? time : 0
    this.data.currentStreak = 0
    this.data.totalPlayTime += timeSec
    this.save()
    return meta
  }

  /** 单桶裁剪：只保留该 bucket 最新 historyMax 条 */
  trimBucket(bucket) {
    const list = this.data.history
    let count = 0
    for (let i = 0; i < list.length; i++) if (list[i].bucket === bucket) count++
    while (count > this.historyMax) {
      const idx = list.findIndex((e) => e.bucket === bucket)
      if (idx < 0) break
      list.splice(idx, 1)
      count--
    }
  }

  // ---------- 查询 ----------

  getWinRate() {
    if (this.data.totalGames === 0) return 0
    return Math.round((this.data.gamesWon / this.data.totalGames) * 100)
  }

  /** 最近一次开局的难度（胜利时用于判断破纪录与记录历史） */
  get lastLevel() {
    return this.data.lastLevel
  }

  /** 某桶当前最快时间（毫秒）；null 表示尚无记录 */
  bestTimeOf(bucketKey) {
    if (!bucketKey) return null
    const t = this.data.bestTime[bucketKey]
    return Number.isFinite(t) ? t : null
  }

  /** 某桶最佳 3BV/s；null 表示尚无记录 */
  bestBbbvPerSecOf(bucketKey) {
    if (!bucketKey) return null
    const r = this.data.bestBbbvPerSec[bucketKey]
    return Number.isFinite(r) ? r : null
  }

  /** 存在的桶 key（预设难度恒在，自定义桶按出现顺序/字典序补齐；供 UI 下拉） */
  getBuckets() {
    const keys = this.levelIds.slice()
    const customKeys = new Set()
    const collect = (key) => {
      if (typeof key === 'string' && key.startsWith(this.customLevel) && key !== this.customLevel) {
        customKeys.add(key)
      } else if (key === this.customLevel) {
        customKeys.add(key)
      }
    }
    for (const key of Object.keys(this.data.bestTime)) collect(key)
    for (const key of Object.keys(this.data.bestBbbvPerSec)) collect(key)
    for (const entry of this.data.history) collect(entry.bucket)
    if (this.currentBucket && !keys.includes(this.currentBucket)) collect(this.currentBucket)
    const custom = Array.from(customKeys)
    // 旧存档的 `custom` 桶排在最前，其余按字典序稳定输出
    custom.sort((a, b) => {
      if (a === this.customLevel) return -1
      if (b === this.customLevel) return 1
      return a < b ? -1 : a > b ? 1 : 0
    })
    return keys.concat(custom)
  }

  /** 某桶最近的胜利记录（时间升序，供效率曲线绘制） */
  getHistory(bucketKey, limit = 20) {
    if (!bucketKey) return []
    const n = Math.floor(limit)
    if (!Number.isFinite(n) || n <= 0) return [] // 与安卓一致：limit<=0 返回空
    return this.data.history
      .filter((e) => e && e.bucket === bucketKey)
      .slice(-n)
      .map((e) => ({ ...e }))
  }

  /** 汇总（getSummary 返回深拷贝，调用方修改不会污染持久化数据） */
  getSummary() {
    const d = this.data
    // 兼容旧调用方：预设难度 + custom 键恒存在（无记录为 null），值分别为毫秒 / 3BV/s
    const bestTime = {}
    const bestBbbvPerSec = {}
    for (const key of this.levelIds.concat(this.customLevel)) {
      bestTime[key] = null
      bestBbbvPerSec[key] = null
    }
    Object.assign(bestTime, d.bestTime)
    Object.assign(bestBbbvPerSec, d.bestBbbvPerSec)
    return {
      totalGames: d.totalGames,
      gamesWon: d.gamesWon,
      gamesLost: Math.max(0, d.totalGames - d.gamesWon),
      winRate: this.getWinRate(),
      currentStreak: d.currentStreak,
      bestStreak: d.bestStreak,
      bestTime,
      bestBbbvPerSec,
      totalPlayTime: d.totalPlayTime,
      buckets: this.getBuckets(),
      currentBucket: this.currentBucket
    }
  }

  reset() {
    this.data = this.defaults()
    this.save()
  }
}

// ---------- 模块内校验/归一工具 ----------

function isPlainObject(value) {
  return !!value && typeof value === 'object' && !Array.isArray(value)
}

function numberOf(value, fallback) {
  return Number.isFinite(value) ? value : fallback
}

function normalizeCustom(custom) {
  if (!isPlainObject(custom)) return null
  const { rows, cols, mines } = custom
  if (!Number.isInteger(rows) || !Number.isInteger(cols) || !Number.isInteger(mines)) return null
  if (rows < 1 || cols < 1 || mines < 0) return null
  return { rows, cols, mines }
}

/**
 * 归一 bucket -> number 映射；非法 key/值丢弃。
 * @param {boolean} legacySeconds 旧存档里 bestTime 以秒存储 → 转毫秒
 * @param {boolean} positiveOnly 3BV/s 只接受正数
 */
function normalizeNumberMap(raw, legacySeconds, positiveOnly) {
  const out = {}
  if (!isPlainObject(raw)) return out
  for (const [key, value] of Object.entries(raw)) {
    if (typeof key !== 'string' || key === '') continue
    if (!Number.isFinite(value)) continue
    if (positiveOnly ? value <= 0 : value < 0) continue
    out[key] = legacySeconds ? value * 1000 : value
  }
  return out
}

// 浏览器端由 index.html <script> 加载为全局类；node:test 环境按 CommonJS 导出
if (typeof module === 'object' && module.exports) {
  module.exports = StatsManager
  module.exports.StatsManager = StatsManager
}
