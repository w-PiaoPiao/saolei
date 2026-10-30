'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const Solver = require('../src/solver.js')
const Generator = require('../src/generator.js')
const CONFIG = require('../src/config.js')

const ORIGINAL_SOLVE = Solver.solve

/** 统计生成器内部调用 solve 的次数（generator 运行时按属性查找，可被替换） */
function countSolveCalls(fn) {
  let calls = 0
  Solver.solve = (...args) => {
    calls++
    return ORIGINAL_SOLVE(...args)
  }
  try {
    const result = fn()
    return { result, calls }
  } finally {
    Solver.solve = ORIGINAL_SOLVE
  }
}

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

// ---------- 普通模式 ----------

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

test('返回几何字段 rows/cols/safeRow/safeCol（M3）', () => {
  const plan = Generator.generate(9, 9, 10, 4, 4, false)
  assert.deepEqual(
    { rows: plan.rows, cols: plan.cols, safeRow: plan.safeRow, safeCol: plan.safeCol },
    { rows: 9, cols: 9, safeRow: 4, safeCol: 4 }
  )
})

// ---------- 输入校验（M4） ----------

test('雷数超过可用格子时报错', () => {
  // 9×9 中心首点安全区 9 格 → 上限 72
  assert.throws(() => Generator.generate(9, 9, 73, 4, 4, false), /超过可用格子数量/)
})

test('输入参数校验：行列/雷数/安全区坐标非法时立即抛错（M4）', () => {
  assert.throws(() => Generator.generate(0, 9, 1, 0, 0, false), /正整数/)
  assert.throws(() => Generator.generate(9, -1, 1, 0, 0, false), /正整数/)
  assert.throws(() => Generator.generate(9.5, 9, 1, 4, 4, false), /正整数/)
  assert.throws(() => Generator.generate(9, 9, -1, 4, 4, false), /非负整数/)
  assert.throws(() => Generator.generate(9, 9, NaN, 4, 4, false), /非负整数/)
  assert.throws(() => Generator.generate(9, 9, 10, 9, 4, false), /安全区坐标越界/)
  assert.throws(() => Generator.generate(9, 9, 10, 4, -1, false), /安全区坐标越界/)
  assert.throws(() => Generator.generate(9, 9, 10, 4, 9, false), /安全区坐标越界/)
  assert.throws(() => Generator.generate(9, 9, 10, 4.5, 4, false), /安全区坐标越界/)
})

test('退化但合法的棋盘（1×1/0 雷）可生成', () => {
  const plan = Generator.generate(1, 1, 0, 0, 0, false)
  assert.equal(plan.mines.length, 1)
  assert.equal(countMines(plan.mines), 0)
  assert.equal(plan.noGuessFallback, false)
})

// ---------- 无猜模式 ----------

test('无猜模式：高级局能在预算内生成可解雷局', () => {
  const plan = Generator.generate(16, 30, 99, 8, 15, true)
  assert.equal(countMines(plan.mines), 99)
  assert.equal(minesInSafeZone(plan.mines, 16, 30, 8, 15), 0)
  assert.equal(plan.noGuessFallback, false, '高级局应能在预算内找到可解局')
})

test('确定性门控：大盘 + 高密度只试 gateAttempts 次即降级（M1，不空等）', () => {
  // 门控只按尺寸/密度决定尝试次数（不读实测耗时）：命中后最多试 gateAttempts 次。
  // 注意：门控是「值不值得重试」的预算判断，不保证失败——个别高密度盘面仍可能被求解器解出。
  const cases = [
    [50, 50, 1000, 25, 25],
    [40, 40, 400, 20, 20],
    [30, 30, 500, 15, 15]
  ]
  for (const [rows, cols, mines, sr, sc] of cases) {
    const { result, calls } = countSolveCalls(() =>
      Generator.generate(rows, cols, mines, sr, sc, true, () => 0, () => 0.5)
    )
    assert.ok(calls <= CONFIG.NO_GUESS.gate.gateAttempts, `${rows}x${cols}/${mines} 尝试次数应被门控，实际 ${calls}`)
    assert.equal(result.rows, rows)
    assert.equal(result.cols, cols)
    assert.equal(countMines(result.mines), mines)
  }
})

test('确定性门控：实测无解配置会降级（30×30/500、50×50/1000）', () => {
  for (const [rows, cols, mines, sr, sc] of [[30, 30, 500, 15, 15], [50, 50, 1000, 25, 25]]) {
    const plan = Generator.generate(rows, cols, mines, sr, sc, true, () => 0, () => 0.5)
    assert.equal(plan.noGuessFallback, true, `${rows}x${cols}/${mines} 应降级`)
  }
})

test('确定性门控：中等密度大盘不受门控（M2 后值得给足预算）', () => {
  // 50×50/500（20% 密度）不触发门控：用 maxAttempts 覆盖验证会超过 gateAttempts 继续重试
  const { result, calls } = countSolveCalls(() =>
    Generator.generate(50, 50, 500, 25, 25, true, () => 0, () => 0.5, { maxAttempts: 12 })
  )
  assert.equal(calls, 12, `应跑满 maxAttempts=12，实际 ${calls}`)
  assert.equal(result.noGuessFallback, true)
  assert.equal(countMines(result.mines), 500)
})

test('确定性门控：小盘/低密度不受门控，仍给足尝试次数', () => {
  // 16×30/99 只有 480 格：不触发门控，用假时钟禁止预算中断时会持续重试
  const { result, calls } = countSolveCalls(() =>
    Generator.generate(16, 30, 99, 8, 15, true, () => 0, () => 0.5, { maxAttempts: 30 })
  )
  assert.equal(calls, 30, `应跑满 maxAttempts=30，实际 ${calls}`)
  assert.equal(typeof result.noGuessFallback, 'boolean')
  assert.equal(countMines(result.mines), 99)
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
  // now 恒为 0：elapsed 永远 <= 0，循环应走到尝试上限
  const plan = Generator.generate(9, 9, 10, 4, 4, true, () => 0, () => 0.5)
  assert.equal(typeof plan.noGuessFallback, 'boolean')
  assert.equal(countMines(plan.mines), 10)
})

test('options 可覆盖预算/尝试上限（H2：同步回退小预算）', () => {
  const { result, calls } = countSolveCalls(() =>
    Generator.generate(50, 50, 500, 25, 25, true, () => 0, () => 0.5, {
      budgetMs: 50,
      maxAttempts: 8
    })
  )
  assert.ok(calls <= 8, `尝试次数应受 maxAttempts=8 约束，实际 ${calls}`)
  assert.equal(result.noGuessFallback, true)
  // 非法 options 值回落默认，不抛错
  assert.doesNotThrow(() =>
    Generator.generate(9, 9, 10, 4, 4, true, () => 0, () => 0.5, { budgetMs: 'x', maxAttempts: -3 })
  )
})

test('门控降级时返回的雷局仍合法（雷数正确 + 3×3 安全区无雷）', () => {
  const plan = Generator.generate(30, 30, 500, 15, 15, true, () => 0, () => 0.5)
  assert.equal(plan.noGuessFallback, true)
  assert.equal(countMines(plan.mines), 500)
  assert.equal(minesInSafeZone(plan.mines, 30, 30, 15, 15), 0)
  assert.equal(plan.rows, 30)
  assert.equal(plan.cols, 30)
  assert.equal(plan.safeRow, 15)
  assert.equal(plan.safeCol, 15)
})

test('MIN_EXPECTED_ATTEMPTS 与配置常量一致（跨端调参依据）', () => {
  // 旧策略常量保留导出以兼容旧调用/旧测试，但 M1 已改确定性门控
  assert.equal(Generator.MIN_EXPECTED_ATTEMPTS, 20)
  assert.equal(CONFIG.NO_GUESS.budgetMs, 800)
  assert.equal(CONFIG.NO_GUESS.maxAttempts, 600)
  const gate = CONFIG.NO_GUESS.gate
  assert.ok(gate && typeof gate === 'object')
  assert.ok(Number.isFinite(gate.hopelessDensity))
  assert.ok(Number.isFinite(gate.hopelessMinCells))
  assert.ok(Number.isInteger(gate.gateAttempts) && gate.gateAttempts >= 1)
})
