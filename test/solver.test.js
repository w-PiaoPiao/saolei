'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const Solver = require('../src/solver.js')
const GameEngine = require('../src/game.js')

/** 手工构造棋盘（rows×cols，指定雷位），返回 board + 数字已算好的引擎 */
function boardOf(rows, cols, mines) {
  const g = new GameEngine('beginner')
  g.rows = rows
  g.cols = cols
  g.totalMines = mines.length
  g.board = []
  for (let r = 0; r < rows; r++) {
    g.board.push([])
    for (let c = 0; c < cols; c++) {
      g.board[r].push({
        mine: false,
        revealed: false,
        flagged: false,
        questioned: false,
        adjacentMines: 0,
        exploded: false
      })
    }
  }
  for (const [r, c] of mines) g.board[r][c].mine = true
  for (let r = 0; r < rows; r++) {
    for (let c = 0; c < cols; c++) {
      if (!g.board[r][c].mine) g.board[r][c].adjacentMines = g.countAdjacentMines(r, c)
    }
  }
  return g
}

test('求解器：单雷角局可从远处推理通关', () => {
  const g = boardOf(9, 9, [[0, 0]])
  assert.equal(Solver.solve(g.board, 9, 9, 1, 4, 4), true)
})

test('求解器：开局零区域洪泛可展开大片（3BV 为 1 的简单局）', () => {
  const g = boardOf(9, 9, [[4, 4]])
  assert.equal(Solver.solve(g.board, 9, 9, 1, 0, 0), true)
})

test('求解器：多解的必猜局面判为不可解（保守性）', () => {
  // 5×5：只翻开了 (1,1)（数字 1），其余全未知；雷在 (0,0) 与 (4,4)。
  // 把 (0,0) 换成 (0,1) 后 (1,1) 的数字仍是 1、其他已翻开信息完全不变 → 至少两个解 → 必须猜。
  const g = boardOf(5, 5, [[0, 0], [4, 4]])
  assert.equal(g.board[1][1].adjacentMines, 1)
  assert.equal(g.board[0][1].mine, false)
  assert.equal(Solver.solve(g.board, 5, 5, 2, 1, 1), false)
})

test('求解器：子集规则可解出单点规则推不出的差集', () => {
  // 经典 1-2 结构：数字 1 的未知集是数字 2 的子集时，差集恰有 1 雷
  // 布局（M=雷，. =0，数字为相邻雷数）：
  //   M . M
  //   1 2 1
  //   . . .
  const g = boardOf(3, 4, [[0, 0], [0, 2]])
  // 首点取底部中央 (2,1)：洪泛展开底部
  const ok = Solver.solve(g.board, 3, 4, 2, 2, 1)
  assert.equal(ok, true)
})

test('无猜模式：生成后求解器判定必可解（初级/中级各 5 局）', () => {
  for (const level of ['beginner', 'intermediate']) {
    for (let i = 0; i < 5; i++) {
      const g = new GameEngine(level)
      g.noGuess = true
      const r = Math.floor(Math.random() * g.rows)
      const c = Math.floor(Math.random() * g.cols)
      g.reveal(r, c)
      assert.equal(g.noGuessFallback, false, `${level} 第 ${i + 1} 局不应降级`)
      assert.equal(
        Solver.solve(g.board, g.rows, g.cols, g.totalMines, r, c),
        true,
        `${level} 第 ${i + 1} 局应可纯逻辑通关`
      )
    }
  }
})

test('无猜模式：高级局生成成功且首点安全', () => {
  const g = new GameEngine('expert')
  g.noGuess = true
  g.reveal(8, 15)
  assert.equal(g.noGuessFallback, false)
  assert.equal(g.state, 'playing')
  for (let dr = -1; dr <= 1; dr++) {
    for (let dc = -1; dc <= 1; dc++) {
      assert.equal(g.board[8 + dr][15 + dc].mine, false)
    }
  }
  assert.equal(Solver.solve(g.board, g.rows, g.cols, g.totalMines, 8, 15), true)
})

test('关闭无猜模式时不触发重生成', () => {
  const g = new GameEngine('beginner')
  g.noGuess = false
  g.reveal(4, 4)
  assert.equal(g.noGuessFallback, false)
  assert.equal(g.state, 'playing')
})

test('3BV：单个零区域展开全盘为 1', () => {
  const g = boardOf(5, 5, [[0, 0]])
  assert.equal(g.compute3BV(), 1)
})

test('3BV：被雷墙分断的两个零区域为 2', () => {
  // 9×9，第 4 列全是雷 → 左右各一个零区域，边界数字格都被展开
  const mines = []
  for (let r = 0; r < 9; r++) mines.push([r, 4])
  const g = boardOf(9, 9, mines)
  assert.equal(g.compute3BV(), 2)
})

test('3BV：孤立的数字格各自计数', () => {
  // 5×5，单个雷在中心：四周数字格都被零区域展开覆盖 → 3BV = 1
  const g = boardOf(5, 5, [[2, 2]])
  assert.equal(g.compute3BV(), 1)
  // 两个相距很远的雷 → 仍是一个零区域包住两个数字簇？左侧与右侧 0 区域连通 → 1
  const g2 = boardOf(5, 5, [[0, 0], [4, 4]])
  assert.equal(g2.compute3BV(), 1)
})

test('3BV：整盘无零区域时等于数字格数', () => {
  // 9×9 棋盘上铺满雷的棋盘格（雷在偶数行的偶数列 + 全奇数列），确保没有 adjacentMines === 0 的格
  const mines = []
  for (let r = 0; r < 9; r++) {
    for (let c = 0; c < 9; c++) {
      if (r % 2 === 0 && c % 2 === 0) mines.push([r, c])
    }
  }
  const g = boardOf(9, 9, mines)
  let zeros = 0
  let numbers = 0
  for (let r = 0; r < 9; r++) {
    for (let c = 0; c < 9; c++) {
      const cell = g.board[r][c]
      if (!cell.mine) {
        if (cell.adjacentMines === 0) zeros++
        else numbers++
      }
    }
  }
  assert.equal(zeros, 0, '该构造不应存在零区域')
  assert.equal(g.compute3BV(), numbers)
})

test('布雷后 bbbv 自动计算且为新局重置', () => {
  const g = new GameEngine('beginner')
  assert.equal(g.bbbv, 0)
  g.reveal(4, 4)
  assert.ok(g.bbbv > 0)
  const first = g.bbbv
  g.init('beginner')
  assert.equal(g.bbbv, 0)
  g.reveal(0, 0)
  assert.ok(g.bbbv > 0)
  assert.equal(typeof first, 'number')
})
