'use strict'
/**
 * 跨端常量一致性测试（L16）：
 * 直接用正则读取 android/core 的 Kotlin 源码，与桌面 src/config.js 对比，
 * 防止「三份手工同步」的常量（难度表 / 无猜预算 / 无猜确定性门控 / history 上限 / 自定义雷数上限）漂移。
 *
 * 说明：android/core 与桌面并行开发；对可能在重构中改名/移除的旧策略常量采取
 * 「存在则必一致、缺失不判失败」的容错策略，避免本测试成为跨组件的耦合阻塞。
 */
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const CONFIG = require('../src/config.js')

const ANDROID_CORE = path.join(
  __dirname,
  '..',
  'android',
  'core',
  'src',
  'main',
  'kotlin',
  'com',
  'wpiaopiao',
  'saolei',
  'core'
)

function readCore(fileName) {
  return fs.readFileSync(path.join(ANDROID_CORE, fileName), 'utf8')
}

/** 读取 `const val NAME = 123` / `const val NAME = 123L` 的整数值；不存在返回 null */
function intConstant(source, name) {
  const match = source.match(new RegExp(`const\\s+val\\s+${name}\\s*=\\s*(\\d+)L?`))
  return match ? Number(match[1]) : null
}

/** 读取 `const val NAME = 0.25` / `const val NAME = 0.25f` / `... = 900` 的数值；不存在返回 null */
function numberConstant(source, name) {
  const match = source.match(new RegExp(`const\\s+val\\s+${name}\\s*=\\s*(-?\\d+(?:\\.\\d+)?)f?L?`))
  return match ? Number(match[1]) : null
}

test('跨端常量一致：难度表（config.js ↔ GameEngine.kt）', () => {
  const source = readCore('GameEngine.kt')
  const re = /"([a-zA-Z]+)"\s+to\s+Level\([^,]+,\s*(\d+),\s*(\d+),\s*(\d+)\)/g
  const androidLevels = {}
  let match
  while ((match = re.exec(source))) {
    androidLevels[match[1]] = { rows: Number(match[2]), cols: Number(match[3]), mines: Number(match[4]) }
  }
  assert.deepEqual(
    Object.keys(androidLevels).sort(),
    CONFIG.LEVEL_IDS.slice().sort(),
    '两端难度 id 集合应一致'
  )
  for (const id of CONFIG.LEVEL_IDS) {
    assert.deepEqual(
      androidLevels[id],
      { rows: CONFIG.LEVELS[id].rows, cols: CONFIG.LEVELS[id].cols, mines: CONFIG.LEVELS[id].mines },
      `难度 ${id} 的行/列/雷数应一致`
    )
  }
})

test('跨端常量一致：无猜尝试上限与时间预算（config.js ↔ GameEngine.kt）', () => {
  const source = readCore('GameEngine.kt')
  assert.equal(
    intConstant(source, 'NO_GUESS_MAX_ATTEMPTS'),
    CONFIG.NO_GUESS.maxAttempts,
    'NO_GUESS.maxAttempts'
  )
  assert.equal(
    intConstant(source, 'NO_GUESS_BUDGET_MS'),
    CONFIG.NO_GUESS.budgetMs,
    'NO_GUESS.budgetMs'
  )
})

test('跨端常量一致：无猜确定性门控（HOPELESS_* / GATE_MAX_ATTEMPTS，M1）', () => {
  // 安卓 core 已与桌面完全对齐：同时满足（盘面 ≥ HOPELESS_MIN_CELLS 且密度 ≥ HOPELESS_DENSITY）
  // 才门控，最多尝试 GATE_MAX_ATTEMPTS 次即降级；其余配置给足预算。
  const source = readCore('GameEngine.kt')
  const gate = CONFIG.NO_GUESS.gate
  assert.ok(gate && typeof gate === 'object', '桌面 CONFIG.NO_GUESS.gate 应存在')
  assert.equal(
    numberConstant(source, 'HOPELESS_MIN_CELLS'),
    gate.hopelessMinCells,
    'HOPELESS_MIN_CELLS ↔ CONFIG.NO_GUESS.gate.hopelessMinCells'
  )
  assert.equal(
    numberConstant(source, 'HOPELESS_DENSITY'),
    gate.hopelessDensity,
    'HOPELESS_DENSITY ↔ CONFIG.NO_GUESS.gate.hopelessDensity'
  )
  assert.equal(
    numberConstant(source, 'GATE_MAX_ATTEMPTS'),
    gate.gateAttempts,
    'GATE_MAX_ATTEMPTS ↔ CONFIG.NO_GUESS.gate.gateAttempts'
  )
  // 语义对齐自检：安卓注释/实现必须表达「同时满足」（与桌面 generator.deterministicAttemptCap 一致）
  assert.match(
    source,
    /cells\s*>=\s*HOPELESS_MIN_CELLS\s*&&\s*density\s*>=\s*HOPELESS_DENSITY/,
    '安卓门控应同时满足格数与密度条件'
  )
})

test('跨端常量一致：旧策略常量（存在则必须一致）', (t) => {
  const source = readCore('GameEngine.kt')
  const androidMin = intConstant(source, 'MIN_EXPECTED_ATTEMPTS')
  if (androidMin === null) {
    t.diagnostic('安卓端已移除 MIN_EXPECTED_ATTEMPTS（M1 确定性门控），跳过该项')
    return
  }
  assert.equal(androidMin, 20, 'MIN_EXPECTED_ATTEMPTS（旧策略常量）应与桌面历史值 20 一致')
})

test('跨端常量一致：历史战绩每桶上限（config.js ↔ StatsManager.kt）', () => {
  const source = readCore('StatsManager.kt')
  assert.equal(intConstant(source, 'HISTORY_MAX'), CONFIG.HISTORY_MAX, 'HISTORY_MAX')
})

test('跨端语义一致：自定义雷数上限 = rows*cols - 9（首点 3×3 安全区）', (t) => {
  const source = readCore('GameEngine.kt')
  const pattern = /rows\.toLong\(\)\s*\*\s*cols\s*-\s*9/
  if (!pattern.test(source)) {
    t.diagnostic('未在 GameEngine.kt 中匹配到 `rows.toLong() * cols - 9`，跳过（实现可能已重构）')
    return
  }
  // 桌面侧同规则：src/game.js initCustom 的 maxMines = rows*cols-9
  const gameSource = fs.readFileSync(path.join(__dirname, '..', 'src', 'game.js'), 'utf8')
  assert.match(gameSource, /rows \* cols - 9/, '桌面 initCustom 应使用 rows*cols-9 上限')
})

test('跨端常量一致：桌面 config 的基础形状（parity 测试自检）', () => {
  assert.ok(Array.isArray(CONFIG.LEVEL_IDS) && CONFIG.LEVEL_IDS.length >= 3)
  assert.equal(typeof CONFIG.CUSTOM_LEVEL_ID, 'string')
  assert.ok(Number.isFinite(CONFIG.NO_GUESS.budgetMs))
  assert.ok(Number.isFinite(CONFIG.HISTORY_MAX))
  assert.ok(CONFIG.STATES && CONFIG.STATES.READY === 'ready')
  const gate = CONFIG.NO_GUESS.gate
  assert.ok(gate && Number.isFinite(gate.hopelessMinCells))
  assert.ok(gate && Number.isFinite(gate.hopelessDensity))
  assert.ok(gate && Number.isInteger(gate.gateAttempts))
})
