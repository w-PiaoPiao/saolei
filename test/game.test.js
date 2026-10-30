'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const GameEngine = require('../src/game.js')
const Generator = require('../src/generator.js')

/** 手动构造已知雷局：设置雷、计算数字、置 minesPlaced */
function setupEngine(level, mines) {
  const g = new GameEngine(level)
  for (let r = 0; r < g.rows; r++) {
    for (let c = 0; c < g.cols; c++) {
      g.board[r][c].mine = false
      g.board[r][c].adjacentMines = 0
    }
  }
  for (const [r, c] of mines) g.board[r][c].mine = true
  g.totalMines = mines.length
  g.minesPlaced = true
  for (let r = 0; r < g.rows; r++) {
    for (let c = 0; c < g.cols; c++) {
      if (!g.board[r][c].mine) g.board[r][c].adjacentMines = g.countAdjacentMines(r, c)
    }
  }
  return g
}

function countMines(g) {
  let n = 0
  for (let r = 0; r < g.rows; r++) {
    for (let c = 0; c < g.cols; c++) {
      if (g.board[r][c].mine) n++
    }
  }
  return n
}

test('初始化：棋盘尺寸与雷数正确', () => {
  const g = new GameEngine('expert')
  assert.equal(g.rows, 16)
  assert.equal(g.cols, 30)
  assert.equal(g.totalMines, 99)
  assert.equal(g.state, 'ready')
  assert.equal(g.board.length, 16)
  assert.equal(g.board[0].length, 30)
})

test('未知难度报错', () => {
  const g = new GameEngine()
  assert.throws(() => g.init('nope'))
})

test('自定义模式', () => {
  const g = new GameEngine()
  g.initCustom(12, 20, 30)
  assert.equal(g.currentLevel, 'custom')
  assert.equal(g.rows, 12)
  assert.equal(g.cols, 20)
  assert.equal(g.totalMines, 30)
})

test('首次点击安全：3×3 无雷、自身为空白、雷数正确', () => {
  const g = new GameEngine('beginner')
  g.reveal(4, 4)
  assert.equal(g.state, 'playing')
  for (let dr = -1; dr <= 1; dr++) {
    for (let dc = -1; dc <= 1; dc++) {
      assert.equal(g.board[4 + dr][4 + dc].mine, false)
    }
  }
  assert.equal(g.board[4][4].adjacentMines, 0)
  assert.equal(countMines(g), 10)
  assert.ok(g.revealedCount > 0)
})

test('角落首次点击同样安全', () => {
  const g = new GameEngine('beginner')
  g.reveal(0, 0)
  for (let r = 0; r <= 1; r++) {
    for (let c = 0; c <= 1; c++) {
      assert.equal(g.board[r][c].mine, false)
    }
  }
  assert.equal(countMines(g), 10)
})

test('踩雷判负并翻开所有雷', () => {
  const g = setupEngine('beginner', [[0, 0], [1, 1]])
  g.reveal(1, 1)
  assert.equal(g.state, 'lost')
  assert.equal(g.board[1][1].exploded, true)
  assert.equal(g.board[0][0].revealed, true)
  assert.equal(g.board[1][1].revealed, true)
})

test('翻开所有安全格判胜', () => {
  const g = setupEngine('beginner', [[0, 0], [0, 1], [1, 1]])
  for (let r = 0; r < g.rows; r++) {
    for (let c = 0; c < g.cols; c++) {
      if (!g.board[r][c].mine) g.reveal(r, c)
    }
  }
  assert.equal(g.state, 'won')
})

test('正确插旗判定胜利', () => {
  const g = setupEngine('beginner', [[0, 0], [0, 1], [1, 1]])
  for (const [r, c] of [[0, 0], [0, 1], [1, 1]]) g.toggleFlag(r, c)
  assert.equal(g.state, 'won')
})

test('错误插旗不判胜', () => {
  const g = setupEngine('beginner', [[0, 0], [0, 1], [1, 1]])
  g.toggleFlag(0, 0)
  g.toggleFlag(0, 1)
  g.toggleFlag(2, 2)
  assert.equal(g.state, 'playing')
})

test('和弦成功：旗数匹配时翻开周围格', () => {
  const g = setupEngine('beginner', [[1, 1], [8, 8]])
  g.reveal(0, 0)
  assert.equal(g.board[0][0].adjacentMines, 1)
  g.toggleFlag(1, 1)
  const didChord = g.chord(0, 0)
  assert.equal(didChord, true)
  assert.equal(g.board[0][1].revealed, true)
  assert.equal(g.board[1][0].revealed, true)
  assert.equal(g.board[1][1].revealed, false) // 已插旗不翻开
})

test('和弦失败：旗数不匹配时不翻开', () => {
  const g = setupEngine('beginner', [[1, 1]])
  g.reveal(0, 0)
  const didChord = g.chord(0, 0)
  assert.equal(didChord, false)
  assert.equal(g.board[0][1].revealed, false)
})

test('脏标记：操作后记录变更格子', () => {
  const g = new GameEngine('beginner')
  assert.ok(g.dirty.size > 0) // init 即全量脏
  g.dirty.clear()
  g.reveal(4, 4)
  assert.ok(g.dirty.size > 0)
})

test('终局后引擎拒绝继续操作', () => {
  const g = setupEngine('beginner', [[0, 0]])
  g.reveal(0, 0)
  assert.equal(g.state, 'lost')
  const revealedBefore = g.revealedCount
  g.reveal(8, 8)
  assert.equal(g.revealedCount, revealedBefore)
})

test('踩雷终局：非雷格上的旗标记为错误旗', () => {
  const g = setupEngine('beginner', [[0, 0]])
  g.toggleFlag(5, 5) // 非雷格插旗
  g.reveal(0, 0) // 踩雷判负
  assert.equal(g.state, 'lost')
  assert.equal(g.board[5][5].wrongFlag, true)
  assert.equal(g.board[5][5].flagged, true) // 保留旗状态，UI 叠加红叉
  assert.equal(g.board[0][0].wrongFlag, false)
})

test('新局重建棋盘清除错误旗标记', () => {
  const g = setupEngine('beginner', [[0, 0]])
  g.toggleFlag(5, 5)
  g.reveal(0, 0)
  g.init('beginner')
  assert.equal(g.board[5][5].wrongFlag, false)
  assert.equal(g.board[0][0].flagged, false)
})

// ---------- 输入校验与边界守卫（防止非法配置把异常延迟到首点） ----------

test('initCustom 拒绝非法配置', () => {
  const g = new GameEngine()
  assert.throws(() => g.initCustom(0, 9, 1), /正整数/)
  assert.throws(() => g.initCustom(9, 0, 1), /正整数/)
  assert.throws(() => g.initCustom(-1, 9, 0), /正整数/)
  assert.throws(() => g.initCustom(9, 9, -5), /不能为负/)
  assert.throws(() => g.initCustom(9.5, 9, 1), /整数/)
  assert.throws(() => g.initCustom(9, 9, NaN), /整数/)
  // 上限 = rows*cols - 首点 3×3 安全区
  assert.throws(() => g.initCustom(9, 9, 73), /超过可用格子数量/)
  assert.doesNotThrow(() => g.initCustom(9, 9, 72))
})

test('initCustom 允许退化但合法的棋盘', () => {
  const g = new GameEngine()
  assert.doesNotThrow(() => g.initCustom(1, 1, 0))
  assert.equal(g.rows, 1)
  assert.equal(g.totalMines, 0)
  assert.doesNotThrow(() => g.initCustom(2, 2, 0))
})

test('越界坐标的操作安全返回且无副作用', () => {
  const g = setupEngine('beginner', [[0, 0]])
  const revealedBefore = g.revealedCount
  const flagsBefore = g.flagCount
  const stateBefore = g.state
  for (const [r, c] of [[-1, 0], [0, -1], [9, 0], [0, 9], [99, 99], [-99, -99]]) {
    assert.doesNotThrow(() => g.reveal(r, c))
    assert.doesNotThrow(() => g.toggleFlag(r, c))
    assert.doesNotThrow(() => g.chord(r, c))
  }
  assert.equal(g.revealedCount, revealedBefore)
  assert.equal(g.flagCount, flagsBefore)
  assert.equal(g.state, stateBefore)
})

test('插旗三态循环：旗→问号→取消，旗数同步增减', () => {
  const g = setupEngine('beginner', [[0, 0]])
  assert.equal(g.flagCount, 0)
  g.toggleFlag(5, 5)
  assert.equal(g.board[5][5].flagged, true)
  assert.equal(g.flagCount, 1)
  g.toggleFlag(5, 5)
  assert.equal(g.board[5][5].flagged, false)
  assert.equal(g.board[5][5].questioned, true)
  assert.equal(g.flagCount, 0)
  g.toggleFlag(5, 5)
  assert.equal(g.board[5][5].questioned, false)
  assert.equal(g.flagCount, 0)
})

test('问号格被翻开时清除问号标记', () => {
  const g = setupEngine('beginner', [[0, 0]])
  g.toggleFlag(5, 5)
  g.toggleFlag(5, 5) // 转为问号
  assert.equal(g.board[5][5].questioned, true)
  g.reveal(5, 5)
  assert.equal(g.board[5][5].revealed, true)
  assert.equal(g.board[5][5].questioned, false)
})

test('终局后和弦不再生效', () => {
  const g = setupEngine('beginner', [[1, 1], [8, 8]])
  g.reveal(0, 0)
  g.toggleFlag(1, 1) // 只插对 1 个旗，共 2 雷 → 不判胜
  g.reveal(8, 8) // 踩雷判负
  assert.equal(g.state, 'lost')
  const revealedBefore = g.revealedCount
  assert.equal(g.chord(0, 0), false)
  assert.equal(g.revealedCount, revealedBefore)
})

test('胜利终局后同样拒绝操作', () => {
  // 雷相邻 (0,0)：胜局下它的相邻旗数仍与数字相等，去掉守卫会误判为「可和弦」
  const g = setupEngine('beginner', [[1, 1], [1, 2]])
  g.reveal(0, 0)
  assert.equal(g.board[0][0].adjacentMines, 1)
  g.toggleFlag(1, 1)
  g.toggleFlag(1, 2)
  assert.equal(g.state, 'won')
  const revealedBefore = g.revealedCount
  assert.equal(g.chord(0, 0), false)
  g.reveal(5, 5)
  g.toggleFlag(6, 6)
  assert.equal(g.revealedCount, revealedBefore)
  assert.equal(g.flagCount, 2)
})

// ---------- 第二轮修复回归（H2/M3/M8/L12/L15/L20） ----------

function totalMinesOnBoard(g) {
  let n = 0
  for (let r = 0; r < g.rows; r++) for (let c = 0; c < g.cols; c++) if (g.board[r][c].mine) n++
  return n
}

test('API 签名：reveal/placeMines 接受可选的 options 参数（H2，UI 用 arity 探测）', () => {
  assert.equal(GameEngine.prototype.reveal.length, 3)
  assert.equal(GameEngine.prototype.placeMines.length, 3)
  assert.equal(GameEngine.prototype.applyPlanAndReveal.length, 3)
})

test('wonByFlags：插对全部旗获胜时置 true，翻格获胜为 false，新局重置（L12/DE-13）', () => {
  const flagWin = setupEngine('beginner', [[0, 0], [0, 1], [1, 1]])
  for (const [r, c] of [[0, 0], [0, 1], [1, 1]]) flagWin.toggleFlag(r, c)
  assert.equal(flagWin.state, 'won')
  assert.equal(flagWin.wonByFlags, true)

  const revealWin = setupEngine('beginner', [[0, 0], [0, 1], [1, 1]])
  for (let r = 0; r < revealWin.rows; r++) {
    for (let c = 0; c < revealWin.cols; c++) if (!revealWin.board[r][c].mine) revealWin.reveal(r, c)
  }
  assert.equal(revealWin.state, 'won')
  assert.equal(revealWin.wonByFlags, false)

  flagWin.init('beginner')
  assert.equal(flagWin.wonByFlags, false)
  assert.equal(flagWin.elapsedMs, 0)
})

test('0 雷棋盘：插旗→问号不判胜，必须真正翻开（M8）', () => {
  const g = new GameEngine()
  g.initCustom(9, 9, 0)
  g.toggleFlag(5, 5)
  assert.equal(g.state, 'playing')
  g.toggleFlag(5, 5) // 旗 → 问号：flagCount 归 0
  assert.equal(g.flagCount, 0)
  assert.equal(g.state, 'playing')
  assert.equal(g.revealedCount, 0)
  g.reveal(5, 5)
  assert.equal(g.state, 'won')
  assert.equal(g.revealedCount, 81)
})

test('applyPlanAndReveal：合法 plan 应用并翻开首点，几何字段透传', () => {
  const g = new GameEngine('beginner')
  const plan = Generator.generate(g.rows, g.cols, g.totalMines, 4, 4, false)
  const applied = g.applyPlanAndReveal(4, 4, plan)
  assert.equal(applied, true)
  assert.equal(g.state, 'playing')
  assert.equal(g.minesPlaced, true)
  assert.equal(totalMinesOnBoard(g), 10)
  assert.ok(g.bbbv > 0)
  assert.equal(g.board[4][4].mine, false)
  assert.equal(g.board[4][4].revealed, true)
})

test('applyPlanAndReveal：竞态/非法 plan 一律拒绝且不开局（M3/L20）', () => {
  const g = new GameEngine('beginner')
  const plan = Generator.generate(g.rows, g.cols, g.totalMines, 4, 4, false)
  // 点击格已插旗
  g.toggleFlag(4, 4)
  assert.equal(g.applyPlanAndReveal(4, 4, plan), false)
  assert.equal(g.minesPlaced, false)
  g.toggleFlag(4, 4) // 取消旗（回到问号）
  g.toggleFlag(4, 4) // 问号 → 取消
  // plan 的 safeRow/safeCol 与点击格不一致（过期/串位结果）
  assert.equal(g.applyPlanAndReveal(5, 5, plan), false)
  assert.equal(g.minesPlaced, false)
  // plan 非法（长度/雷数/安全区）
  assert.equal(g.applyPlanAndReveal(4, 4, null), false)
  assert.equal(g.applyPlanAndReveal(4, 4, { mines: new Uint8Array(0) }), false)
  assert.equal(g.applyPlanAndReveal(4, 4, { mines: plan.mines, rows: 99, safeRow: 4, safeCol: 4 }), false)
  assert.equal(g.minesPlaced, false)
  // 合法 plan 应用后，重复调用返回 false（同局幂等）
  assert.equal(g.applyPlanAndReveal(4, 4, plan), true)
  assert.equal(g.applyPlanAndReveal(4, 4, plan), false)
})

test('applyPlanAndReveal：缺几何字段的旧 plan 向后兼容（仅校验长度/雷数）', () => {
  const g = new GameEngine('beginner')
  const plan = Generator.generate(g.rows, g.cols, g.totalMines, 4, 4, false)
  const legacy = { mines: plan.mines, noGuessFallback: false }
  assert.equal(g.applyPlanAndReveal(4, 4, legacy), true)
  assert.equal(g.minesPlaced, true)
})

test('applyPlan：长度/雷数/安全区/行列不符时返回 false 且不修改任何状态（M3）', () => {
  const g = new GameEngine('beginner')
  const good = Generator.generate(g.rows, g.cols, g.totalMines, 4, 4, false)
  const stateBefore = g.state
  const badCases = [
    new Uint8Array(g.rows * g.cols), // 长度对但雷数不符（0 ≠ 10）
    new Uint8Array(g.rows * g.cols - 1), // 长度不符
    Object.assign({}, good, { rows: 99 }),
    Object.assign({}, good, { cols: 99 })
  ]
  for (const plan of badCases) {
    assert.equal(g.applyPlan(plan), false)
    assert.equal(g.minesPlaced, false)
    assert.equal(g.state, stateBefore)
  }
  // 安全区有雷：把一颗非安全区的雷挪进 3×3 安全区（保持总雷数不变）
  const safeIdx = 4 * g.cols + 4
  assert.equal(good.mines[safeIdx], 0)
  const planted = Uint8Array.from(good.mines)
  let moved = false
  for (let i = 0; i < planted.length; i++) {
    const r = (i / g.cols) | 0
    const c = i % g.cols
    if (Math.abs(r - 4) > 1 || Math.abs(c - 4) > 1) {
      if (planted[i] === 1) {
        planted[i] = 0
        planted[safeIdx] = 1
        moved = true
        break
      }
    }
  }
  assert.equal(moved, true)
  assert.equal(
    g.applyPlan({ mines: planted, rows: g.rows, cols: g.cols, safeRow: 4, safeCol: 4 }),
    false
  )
  assert.equal(g.minesPlaced, false)
  // 合法 plan 仍可应用
  assert.equal(g.applyPlan(good), true)
  assert.equal(g.minesPlaced, true)
})

test('placeMines 透传 options：noGuess 大棋盘用小预算快速降级（H2）', () => {
  const g = new GameEngine()
  g.initCustom(50, 50, 500)
  g.noGuess = true
  const t0 = Date.now()
  const applied = g.placeMines(25, 25, { budgetMs: 1, maxAttempts: 1 })
  const elapsed = Date.now() - t0
  assert.equal(applied, true)
  assert.ok(elapsed < 500, `同步生成应被小预算限制（实际 ${elapsed}ms）`)
  assert.equal(g.minesPlaced, true)
  assert.equal(g.noGuessFallback, true)
  assert.equal(totalMinesOnBoard(g), 500)
  assert.ok(g.bbbv > 0)
})

test('插旗→首点状态机：先插旗进入 playing 后，reveal 仍会布雷并计算 3BV（L20）', () => {
  const g = new GameEngine('beginner')
  g.toggleFlag(0, 0)
  assert.equal(g.state, 'playing')
  assert.equal(g.minesPlaced, false)
  g.reveal(4, 4, { budgetMs: 50, maxAttempts: 8 })
  assert.equal(g.state, 'playing')
  assert.equal(g.minesPlaced, true)
  assert.equal(totalMinesOnBoard(g), 10)
  assert.ok(g.bbbv > 0)
  // 首点 3×3 安全区无雷
  for (let dr = -1; dr <= 1; dr++) {
    for (let dc = -1; dc <= 1; dc++) assert.equal(g.board[4 + dr][4 + dc].mine, false)
  }
})
