package com.wpiaopiao.saolei.core

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
 * 与桌面版 src/solver.js 逐句对齐。
 */
object Solver {

    private const val UNKNOWN = 0
    private const val SAFE = 1
    private const val MINE = 2

    private class Constraint(val unknown: IntArray, val need: Int, val set: HashSet<Int>)

    /**
     * 从首点出发模拟"只用逻辑推理通关"。
     *
     * @param board 棋盘（推理只读 adjacentMines；mine 仅用于最后校验，不参与推理）
     * @param startRow 首点行（已保证 3×3 无雷）
     * @param startCol 首点列
     * @return 是否可纯逻辑通关
     */
    fun solve(
        board: Array<Array<Cell>>,
        rows: Int,
        cols: Int,
        totalMines: Int,
        startRow: Int,
        startCol: Int,
    ): Boolean {
        val known = Array(rows) { IntArray(cols) }
        var minesFound = 0
        var failed = false
        val stack = ArrayDeque<Int>()

        // 翻开一格；数字为 0 时按真实 reveal 行为洪泛展开
        fun revealIdx(startIdx: Int) {
            stack.addLast(startIdx)
            while (stack.isNotEmpty()) {
                val idx = stack.removeLast()
                val r = idx / cols
                val c = idx % cols
                if (known[r][c] != UNKNOWN) continue
                if (board[r][c].mine) {
                    failed = true // 推理链出错：不应发生
                    return
                }
                known[r][c] = SAFE
                if (board[r][c].adjacentMines == 0) {
                    for (dr in -1..1) {
                        for (dc in -1..1) {
                            if (dr == 0 && dc == 0) continue
                            val nr = r + dr
                            val nc = c + dc
                            if (nr in 0 until rows && nc in 0 until cols && known[nr][nc] == UNKNOWN) {
                                stack.addLast(nr * cols + nc)
                            }
                        }
                    }
                }
            }
        }

        fun markMineIdx(idx: Int) {
            val r = idx / cols
            val c = idx % cols
            if (known[r][c] == UNKNOWN) {
                known[r][c] = MINE
                minesFound++
            }
        }

        /** 收集当前所有边界约束；约束矛盾时返回 null */
        fun collect(): List<Constraint>? {
            val list = ArrayList<Constraint>()
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    if (known[r][c] != SAFE) continue
                    val n = board[r][c].adjacentMines
                    if (n == 0) continue
                    var flagged = 0
                    val unknown = ArrayList<Int>(8)
                    for (dr in -1..1) {
                        for (dc in -1..1) {
                            if (dr == 0 && dc == 0) continue
                            val nr = r + dr
                            val nc = c + dc
                            if (nr !in 0 until rows || nc !in 0 until cols) continue
                            when (known[nr][nc]) {
                                MINE -> flagged++
                                UNKNOWN -> unknown.add(nr * cols + nc)
                            }
                        }
                    }
                    if (unknown.isEmpty()) continue
                    val need = n - flagged
                    if (need < 0 || need > unknown.size) {
                        failed = true // 约束矛盾：不应发生
                        return null
                    }
                    list.add(Constraint(unknown.toIntArray(), need, HashSet(unknown)))
                }
            }
            return list
        }

        /** 应用一步推理：1=有进展 0=无进展 -1=推理矛盾 */
        fun step(): Int {
            val cons = collect() ?: return -1
            if (failed) return -1

            // 单点规则
            for (con in cons) {
                if (con.need == con.unknown.size) {
                    for (idx in con.unknown) markMineIdx(idx)
                    return 1
                }
                if (con.need == 0) {
                    for (idx in con.unknown) revealIdx(idx)
                    return if (failed) -1 else 1
                }
            }

            // 子集规则：按未知格数升序，只在长度严格更长时检查包含关系
            val order = cons.indices.sortedBy { cons[it].unknown.size }
            for (oi in order.indices) {
                val a = cons[order[oi]]
                for (oj in oi + 1 until order.size) {
                    val b = cons[order[oj]]
                    if (b.unknown.size == a.unknown.size) continue
                    var subset = true
                    for (u in a.unknown) {
                        if (!b.set.contains(u)) {
                            subset = false
                            break
                        }
                    }
                    if (!subset) continue
                    val diff = b.unknown.filter { !a.set.contains(it) }
                    val needDiff = b.need - a.need
                    if (needDiff == 0) {
                        for (idx in diff) revealIdx(idx)
                        return if (failed) -1 else 1
                    }
                    if (needDiff == diff.size) {
                        for (idx in diff) markMineIdx(idx)
                        return 1
                    }
                }
            }
            return 0
        }

        revealIdx(startRow * cols + startCol)
        if (failed) return false

        var guard = 0
        while (guard++ < 20000) {
            val res = step()
            if (res == -1) return false
            if (res == 1) continue

            // 全局规则：剩余雷数耗尽 / 恰好等于未知格数
            var unknownCount = 0
            for (r in 0 until rows) {
                for (c in 0 until cols) if (known[r][c] == UNKNOWN) unknownCount++
            }
            if (unknownCount == 0) break
            val remaining = totalMines - minesFound
            if (remaining == 0) {
                for (r in 0 until rows) {
                    for (c in 0 until cols) if (known[r][c] == UNKNOWN) revealIdx(r * cols + c)
                }
                if (failed) return false
                continue
            }
            if (remaining == unknownCount) {
                for (r in 0 until rows) {
                    for (c in 0 until cols) if (known[r][c] == UNKNOWN) markMineIdx(r * cols + c)
                }
                continue
            }
            break
        }

        // 通关判定：所有非雷格都能被推理翻开
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (!board[r][c].mine && known[r][c] != SAFE) return false
            }
        }
        return true
    }
}
