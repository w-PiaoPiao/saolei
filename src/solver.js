/**
 * 无猜求解器：只用玩家可见信息（已翻开格的数字）做确定性推理，
 * 判断雷局能否纯逻辑通关。
 *
 * 规则集（都是"纯逻辑"，不需要猜）：
 *   1. 单点规则：数字格周围「剩余未标雷数 == 未知邻居数」→ 全是雷；「已标雷 == 数字」→ 其余全安全
 *   2. 子集规则：约束 A 的未知集合 ⊂ 约束 B 的未知集合时，差集的雷数 = needB - needA，
 *      为 0 则差集全安全、等于差集大小则全是雷
 *   3. 全局规则：剩余雷数耗尽 / 恰好等于未知格数
 *
 * 属保守判定：仍可能漏判（重生成一次即可），但判 true 的局一定不含必须猜的步骤。
 * 与安卓版 android/core Solver.kt 逐句对齐。
 */
;(function (root, factory) {
  if (typeof module === 'object' && module.exports) {
    module.exports = factory()
  } else {
    root.MineSolver = factory()
  }
})(typeof self !== 'undefined' ? self : this, function () {
  const UNKNOWN = 0
  const SAFE = 1
  const MINE = 2

  /**
   * 从首点出发模拟"只用逻辑推理通关"。
   * @param {Array} board 棋盘（推理只读 adjacentMines；mine 仅用于最后校验，不参与推理）
   * @param {number} startRow 首点行（已保证 3×3 无雷）
   * @param {number} startCol 首点列
   * @returns {boolean} 是否可纯逻辑通关
   */
  function solve(board, rows, cols, totalMines, startRow, startCol) {
    const known = []
    for (let r = 0; r < rows; r++) known.push(new Uint8Array(cols))
    let minesFound = 0
    let failed = false
    const stack = []

    // 翻开一格；数字为 0 时按真实 reveal 行为洪泛展开
    function revealIdx(startIdx) {
      stack.push(startIdx)
      while (stack.length) {
        const idx = stack.pop()
        const r = (idx / cols) | 0
        const c = idx % cols
        if (known[r][c] !== UNKNOWN) continue
        if (board[r][c].mine) {
          failed = true // 推理链出错：不应发生
          return
        }
        known[r][c] = SAFE
        if (board[r][c].adjacentMines === 0) {
          for (let dr = -1; dr <= 1; dr++) {
            for (let dc = -1; dc <= 1; dc++) {
              if (dr === 0 && dc === 0) continue
              const nr = r + dr
              const nc = c + dc
              if (nr >= 0 && nr < rows && nc >= 0 && nc < cols && known[nr][nc] === UNKNOWN) {
                stack.push(nr * cols + nc)
              }
            }
          }
        }
      }
    }

    function markMineIdx(idx) {
      const r = (idx / cols) | 0
      const c = idx % cols
      if (known[r][c] === UNKNOWN) {
        known[r][c] = MINE
        minesFound++
      }
    }

    /** 收集当前所有边界约束：{unknown: [idx], need: 还需标多少雷在这批未知格里} */
    function collect() {
      const list = []
      for (let r = 0; r < rows; r++) {
        for (let c = 0; c < cols; c++) {
          if (known[r][c] !== SAFE) continue
          const n = board[r][c].adjacentMines
          if (n === 0) continue
          let flagged = 0
          const unknown = []
          for (let dr = -1; dr <= 1; dr++) {
            for (let dc = -1; dc <= 1; dc++) {
              if (dr === 0 && dc === 0) continue
              const nr = r + dr
              const nc = c + dc
              if (nr < 0 || nr >= rows || nc < 0 || nc >= cols) continue
              if (known[nr][nc] === MINE) flagged++
              else if (known[nr][nc] === UNKNOWN) unknown.push(nr * cols + nc)
            }
          }
          if (unknown.length === 0) continue
          const need = n - flagged
          if (need < 0 || need > unknown.length) {
            failed = true // 约束矛盾：不应发生
            return null
          }
          list.push({ unknown, need, set: new Set(unknown) })
        }
      }
      return list
    }

    /** 应用一步推理：1=有进展 0=无进展 -1=推理矛盾 */
    function step() {
      const cons = collect()
      if (cons === null || failed) return -1

      // 单点规则
      for (const con of cons) {
        if (con.need === con.unknown.length) {
          for (const idx of con.unknown) markMineIdx(idx)
          return 1
        }
        if (con.need === 0) {
          for (const idx of con.unknown) revealIdx(idx)
          return failed ? -1 : 1
        }
      }

      // 子集规则：找 A ⊂ B 的约束对，用差集推出新结论
      const order = cons.map((_, i) => i).sort((a, b) => cons[a].unknown.length - cons[b].unknown.length)
      for (let oi = 0; oi < order.length; oi++) {
        const A = cons[order[oi]]
        for (let oj = oi + 1; oj < order.length; oj++) {
          const B = cons[order[oj]]
          // 已按长度升序：长度相等不可能是真子集，更长的才需要检查
          if (B.unknown.length === A.unknown.length) continue
          let subset = true
          for (const u of A.unknown) {
            if (!B.set.has(u)) {
              subset = false
              break
            }
          }
          if (!subset) continue
          const diff = []
          for (const u of B.unknown) if (!A.set.has(u)) diff.push(u)
          const needDiff = B.need - A.need
          if (needDiff === 0) {
            for (const idx of diff) revealIdx(idx)
            return failed ? -1 : 1
          }
          if (needDiff === diff.length) {
            for (const idx of diff) markMineIdx(idx)
            return 1
          }
        }
      }
      return 0
    }

    revealIdx(startRow * cols + startCol)
    if (failed) return false

    let guard = 0
    while (guard++ < 20000) {
      const res = step()
      if (res === -1) return false
      if (res === 1) continue

      // 全局规则：剩余雷数耗尽 / 恰好等于未知格数
      let unknownCount = 0
      for (let r = 0; r < rows; r++) {
        for (let c = 0; c < cols; c++) if (known[r][c] === UNKNOWN) unknownCount++
      }
      if (unknownCount === 0) break
      const remaining = totalMines - minesFound
      if (remaining === 0) {
        for (let r = 0; r < rows; r++) {
          for (let c = 0; c < cols; c++) {
            if (known[r][c] === UNKNOWN) revealIdx(r * cols + c)
          }
        }
        if (failed) return false
        continue
      }
      if (remaining === unknownCount) {
        for (let r = 0; r < rows; r++) {
          for (let c = 0; c < cols; c++) if (known[r][c] === UNKNOWN) markMineIdx(r * cols + c)
        }
        continue
      }
      break
    }

    // 通关判定：所有非雷格都能被推理翻开
    for (let r = 0; r < rows; r++) {
      for (let c = 0; c < cols; c++) {
        if (!board[r][c].mine && known[r][c] !== SAFE) return false
      }
    }
    return true
  }

  return { solve }
})
