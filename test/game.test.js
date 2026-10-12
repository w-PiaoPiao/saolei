'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const GameEngine = require('../src/game.js')

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
