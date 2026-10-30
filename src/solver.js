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
 * 与安卓版 android/core Solver.kt 规则一致。
 *
 * 性能（M2）：旧实现每轮 `step()` 只落地一条结论、且每轮全盘重建约束（每约束一个 Set），
 * 导致 O(结论数 × 棋盘格数) 次全盘扫描 + 大量短命对象。现改为：
 *   - 棋盘/已知状态/邻域全部摊平成 TypedArray，棋盘只摊平一次（不修改传入 board）；
 *   - 同一快照内把单点规则与子集规则的结论**批量**收集后统一落地（规则单调 ⇒ 闭包不变）；
 *   - 子集判定用时间戳数组替代 Set，约束对象用池复用；
 *   - 邻域索引复用 Neighbors 的 CSR 缓存。
 * 求解结果（true/false）与原实现逐盘一致（见 test/solver.test.js 与 /tmp 差分脚本）。
 */
;(function (root, factory) {
  if (typeof module === 'object' && module.exports) {
    module.exports = factory(require('./neighbors.js'))
  } else {
    root.MineSolver = factory(root.Neighbors)
  }
})(typeof self !== 'undefined' ? self : this, function (Neighbors) {
  const UNKNOWN = 0
  const SAFE = 1
  const MINE = 2
  /** 循环上限：防止病态棋盘上的推理死循环（与原实现一致） */
  const MAX_STEPS = 20000

  /**
   * 从首点出发模拟"只用逻辑推理通关"。
   * @param {Array} board 棋盘（推理只读 adjacentMines；mine 仅用于最后校验，不参与推理）
   * @param {number} rows 行数
   * @param {number} cols 列数
   * @param {number} totalMines 总雷数
   * @param {number} startRow 首点行（已保证 3×3 无雷）
   * @param {number} startCol 首点列
   * @returns {boolean} 是否可纯逻辑通关
   */
  function solve(board, rows, cols, totalMines, startRow, startCol) {
    const cells = rows * cols
    const graph = Neighbors.get(rows, cols)
    const nbStart = graph.start
    const nbList = graph.list

    // 摊平棋盘：热路径只用 TypedArray；不修改传入的 board
    const mine = new Uint8Array(cells)
    const adj = new Uint8Array(cells)
    for (let r = 0; r < rows; r++) {
      const row = board[r]
      const base = r * cols
      for (let c = 0; c < cols; c++) {
        const cell = row[c]
        mine[base + c] = cell.mine ? 1 : 0
        adj[base + c] = cell.adjacentMines
      }
    }

    const known = new Uint8Array(cells)
    const stack = []
    let minesFound = 0
    let failed = false

    // 翻开一格；数字为 0 时按真实 reveal 行为洪泛展开
    function revealIdx(startIdx) {
      stack.push(startIdx)
      while (stack.length) {
        const idx = stack.pop()
        if (known[idx] !== UNKNOWN) continue
        if (mine[idx]) {
          failed = true // 推理链出错：不应发生
          return
        }
        known[idx] = SAFE
        if (adj[idx] === 0) {
          for (let k = nbStart[idx]; k < nbStart[idx + 1]; k++) {
            const n = nbList[k]
            if (known[n] === UNKNOWN) stack.push(n)
          }
        }
      }
    }

    function markMineIdx(idx) {
      if (known[idx] === UNKNOWN) {
        known[idx] = MINE
        minesFound++
      }
    }

    // 约束对象池：{unknown: Int32Array(8), len, need}（单格最多 8 个邻居）
    const pool = []
    let poolUsed = 0
    function takeConstraint() {
      const con = pool[poolUsed]
      if (con) {
        poolUsed++
        return con
      }
      const fresh = { unknown: new Int32Array(8), len: 0, need: 0 }
      pool.push(fresh)
      poolUsed++
      return fresh
    }

    /** 收集当前所有边界约束；约束矛盾时置 failed 并返回 false */
    function collect() {
      poolUsed = 0
      for (let idx = 0; idx < cells; idx++) {
        if (known[idx] !== SAFE) continue
        const n = adj[idx]
        if (n === 0) continue
        let flagged = 0
        let len = 0
        const con = takeConstraint()
        const unknown = con.unknown
        for (let k = nbStart[idx]; k < nbStart[idx + 1]; k++) {
          const ni = nbList[k]
          const s = known[ni]
          if (s === MINE) flagged++
          else if (s === UNKNOWN) unknown[len++] = ni
        }
        if (len === 0) {
          poolUsed--
          continue
        }
        const need = n - flagged
        if (need < 0 || need > len) {
          failed = true // 约束矛盾：不应发生
          return false
        }
        con.len = len
        con.need = need
      }
      return true
    }

    // 子集判定时间戳（替代每约束一个 Set），以及每步复用的结论缓冲
    const stamp = new Int32Array(cells).fill(-1)
    let stampSeq = 0
    const safeBuf = []
    const mineBuf = []
    const diffBuf = []
    const order = []

    /** 应用一步推理：1=有进展 0=无进展 -1=推理矛盾 */
    function step() {
      if (!collect()) return -1
      safeBuf.length = 0
      mineBuf.length = 0

      // 1) 单点规则：本快照内所有结论批量收集
      for (let ci = 0; ci < poolUsed; ci++) {
        const con = pool[ci]
        if (con.need === con.len) {
          for (let i = 0; i < con.len; i++) mineBuf.push(con.unknown[i])
        } else if (con.need === 0) {
          for (let i = 0; i < con.len; i++) safeBuf.push(con.unknown[i])
        }
      }

      // 2) 子集规则：按未知数升序比较 A ⊂ B，差集结论同样批量收集
      order.length = 0
      for (let ci = 0; ci < poolUsed; ci++) order.push(ci)
      order.sort((a, b) => pool[a].len - pool[b].len)
      for (let oi = 0; oi < order.length; oi++) {
        const A = pool[order[oi]]
        if (A.len === 0 || oi + 1 >= order.length) continue
        if (stampSeq > 0x3fffffff) {
          stamp.fill(-1)
          stampSeq = 0
        }
        stampSeq++
        for (let i = 0; i < A.len; i++) stamp[A.unknown[i]] = stampSeq
        for (let oj = oi + 1; oj < order.length; oj++) {
          const B = pool[order[oj]]
          if (B.len === A.len) continue // 已按长度升序：等长不可能是真子集
          let matched = 0
          let diffLen = 0
          for (let i = 0; i < B.len; i++) {
            const u = B.unknown[i]
            if (stamp[u] === stampSeq) matched++
            else diffBuf[diffLen++] = u
          }
          if (matched !== A.len) continue // A ⊄ B
          const needDiff = B.need - A.need
          if (needDiff === 0) {
            for (let i = 0; i < diffLen; i++) safeBuf.push(diffBuf[i])
          } else if (needDiff === diffLen) {
            for (let i = 0; i < diffLen; i++) mineBuf.push(diffBuf[i])
          }
        }
      }

      // 3) 落地：先翻安全格（洪泛可能连带翻开更多），再标雷；重复项自动跳过
      let progressed = false
      for (let i = 0; i < safeBuf.length; i++) {
        revealIdx(safeBuf[i])
        if (failed) return -1
      }
      if (safeBuf.length) progressed = true
      for (let i = 0; i < mineBuf.length; i++) {
        const idx = mineBuf[i]
        if (known[idx] === UNKNOWN) {
          known[idx] = MINE
          minesFound++
          progressed = true
        }
      }
      return progressed ? 1 : 0
    }

    revealIdx(startRow * cols + startCol)
    if (failed) return false

    let guard = 0
    while (guard++ < MAX_STEPS) {
      const res = step()
      if (res === -1) return false
      if (res === 1) continue

      // 全局规则：剩余雷数耗尽 / 恰好等于未知格数
      let unknownCount = 0
      for (let idx = 0; idx < cells; idx++) if (known[idx] === UNKNOWN) unknownCount++
      if (unknownCount === 0) break
      const remaining = totalMines - minesFound
      if (remaining === 0) {
        for (let idx = 0; idx < cells; idx++) if (known[idx] === UNKNOWN) revealIdx(idx)
        if (failed) return false
        continue
      }
      if (remaining === unknownCount) {
        for (let idx = 0; idx < cells; idx++) if (known[idx] === UNKNOWN) markMineIdx(idx)
        continue
      }
      break
    }

    // 通关判定：所有非雷格都能被推理翻开
    for (let idx = 0; idx < cells; idx++) {
      if (!mine[idx] && known[idx] !== SAFE) return false
    }
    return true
  }

  return { solve }
})
