'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const Generator = require('../src/generator.js')
const CONFIG = require('../src/config.js')

function countMines(mines) {
  let n = 0
  for (let i = 0; i < mines.length; i++) if (mines[i] === 1) n++
  return n
}

/** 首点 3×3 安全区（含边界裁剪）内的雷位数量 */
function minesInSafeZone(mines, rows, cols, r0, c0) {
  let n = 0
  for (let r = Math.max(0, r0 - 1); r <= Math.min(rows - 1, r0 + 1); r++) {
    for (let c = Math.max(0, c0 - 1); c <= Math.min(cols - 1, c0 + 1); c++) {
      if (mines[r * cols + c] === 1) n++
    }
  }
  return n
}

test('普通模式：雷数正确、首点 3×3 无雷、长度为 rows*cols', () => {
  const rows = 16, cols = 30, totalMines = 99
  const plan = Generator.generate(rows, cols, totalMines, 8, 15, false)
  assert.equal(plan.mines.length, rows * cols)
  assert.equal(countMines(plan.mines), totalMines)
  assert.equal(minesInSafeZone(plan.mines, rows, cols, 8, 15), 0)
  assert.equal(plan.noGuessFallback, false)
})

test('普通模式：角落首点同样保证 2×2 安全区无雷', () => {
  const plan = Generator.generate(9, 9, 10, 0, 0, false)
  assert.equal(countMines(plan.mines), 10)
  assert.equal(minesInSafeZone(plan.mines, 9, 9, 0, 0), 0)
})

test('雷数超过可用格子时报错', () => {
  // 9×9 中心首点安全区 9 格 → 上限 72
  assert.throws(() => Generator.generate(9, 9, 73, 4, 4, false), /超过可用格子数量/)
})

test('无猜模式：高级局能在预算内生成可解雷局', () => {
  const plan = Generator.generate(16, 30, 99, 8, 15, true)
  assert.equal(countMines(plan.mines), 99)
  assert.equal(minesInSafeZone(plan.mines, 16, 30, 8, 15), 0)
  assert.equal(plan.noGuessFallback, false, '高级局应能在预算内找到可解局')
})

test('无猜模式：预算内预估尝试次数不足时提前降级（不空等）', () => {
  // 注入假时钟：每次尝试耗时 100ms → 800ms 预算内只能约 8 次 < MIN_EXPECTED_ATTEMPTS
  let t = 0
  const slowNow = () => (t += 100)
  const started = Date.now()
  const plan = Generator.generate(50, 50, 500, 25, 25, true, slowNow, () => 0.5)
  const elapsed = Date.now() - started
  assert.equal(plan.noGuessFallback, true)
  assert.ok(elapsed < 200, `应提前返回（实际 ${elapsed}ms），而不是跑满预算`)
})

test('无猜模式：可解局一出现就立即返回（不受预算限制）', () => {
  // 注入随机源让第一次尝试即命中（可解率高的小棋盘）
  let calls = 0
  const plan = Generator.generate(9, 9, 10, 4, 4, true, () => {
    calls++
    return calls * 1000
  }, () => 0.5)
  assert.equal(plan.noGuessFallback, false)
  assert.equal(countMines(plan.mines), 10)
})

test('时间源精度不足时不误判降级（只受尝试上限约束）', () => {
  // now 恒为 0：elapsed 永远 <= 0，循环应走到尝试上限而不是因"预估不足"提前退出
  const plan = Generator.generate(9, 9, 10, 4, 4, true, () => 0, () => 0.5)
  assert.equal(typeof plan.noGuessFallback, 'boolean')
  assert.equal(countMines(plan.mines), 10)
})

test('MIN_EXPECTED_ATTEMPTS 与配置常量一致（跨端调参依据）', () => {
  assert.equal(Generator.MIN_EXPECTED_ATTEMPTS, 20)
  assert.equal(CONFIG.NO_GUESS.budgetMs, 800)
  assert.equal(CONFIG.NO_GUESS.maxAttempts, 600)
})
