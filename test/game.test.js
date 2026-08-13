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
