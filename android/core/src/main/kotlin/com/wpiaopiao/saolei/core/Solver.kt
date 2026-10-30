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
 * 规则语义与桌面版 src/solver.js 对齐；实现（M2）做了性能优化但保持 `solve()` 布尔语义不变：
 * - `known` 用一维 IntArray（UNKNOWN/SAFE/MINE），洪泛用显式 Int 栈（不装箱）；
 * - 约束用可复用的扁平缓冲区（每约束未知邻居 ≤ 8），跨步不重复分配；
 * - 单点规则一次 collect 批量落地全部结论（原实现每步只落地一条）；
 * - 子集规则用"按未知数计数排序分桶 + 有序数组双指针"替代 `Set`/`sortedBy`/`filter`。
 *
 * 线程安全：所有状态都是方法局部变量（无静态可变字段），可在后台线程并发调用；
 * 本方法只读 [Cell.mine]/[Cell.adjacentMines]，不修改 board。
 *
 * 性能约定：本类是极限热点（一次无猜生成可能执行数万个求解步），8 邻域展开与洪泛
 * 故意保留内联手写循环，不调用 [Neighbors.forEachNeighbor]；语义与 helper 一致。
 */
object Solver {

    private const val UNKNOWN = 0
    private const val SAFE = 1
    private const val MINE = 2

    /** 单格最多 8 个邻格，因此单个约束的未知集合大小 ≤ 8 */
    private const val MAX_NEIGHBORS = 8

    /** 推理步数上限（与桌面版 20000 对齐；每步至少推进一格，正常远达不到） */
    private const val MAX_STEPS = 20_000

    /**
     * 从首点出发模拟"只用逻辑推理通关"。
     *
     * @param board 棋盘（推理只读 adjacentMines；mine 仅用于最后校验，不参与推理；本方法不修改 board）
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
        require(rows >= 1 && cols >= 1) { "行列数必须为正整数" }
        require(startRow in 0 until rows && startCol in 0 until cols) { "首点坐标越界" }

        val n = rows * cols
        val known = IntArray(n)
        var minesFound = 0
        var revealedSafe = 0
        var failed = false

        // ---------- 显式 Int 栈（洪泛用，避免 ArrayDeque<Int> 装箱；下同） ----------
        var stack = IntArray(256)
        var top = 0

        fun push(idx: Int) {
            if (top == stack.size) stack = stack.copyOf(stack.size * 2)
            stack[top++] = idx
        }

        /** 翻开一格；数字为 0 时按真实 reveal 行为洪泛展开 */
        fun revealIdx(startIdx: Int) {
            push(startIdx)
            while (top > 0) {
                val idx = stack[--top]
                if (known[idx] != UNKNOWN) continue
                val r = idx / cols
                val c = idx - r * cols
                if (board[r][c].mine) {
                    failed = true // 推理链出错：不应发生
                    top = 0
                    return
                }
                known[idx] = SAFE
                revealedSafe++
                if (board[r][c].adjacentMines == 0) {
                    val r0 = if (r > 0) r - 1 else 0
                    val r1 = if (r + 1 < rows) r + 1 else rows - 1
                    val c0 = if (c > 0) c - 1 else 0
                    val c1 = if (c + 1 < cols) c + 1 else cols - 1
                    for (nr in r0..r1) {
                        val base = nr * cols
                        for (nc in c0..c1) {
                            if (nr == r && nc == c) continue
                            val ni = base + nc
                            if (known[ni] == UNKNOWN) push(ni)
                        }
                    }
                }
            }
        }

        fun markMineIdx(idx: Int) {
            if (known[idx] == UNKNOWN) {
                known[idx] = MINE
                minesFound++
            }
        }

        // ---------- 约束缓冲区（跨步复用，按需扩容） ----------
        var conCap = 64
        var conUnknown = IntArray(conCap * MAX_NEIGHBORS)
        var conCount = IntArray(conCap)
        var conNeed = IntArray(conCap)
        var conN = 0
        var order = IntArray(conCap)
        val sizeCount = IntArray(MAX_NEIGHBORS + 1)
        val sizeStart = IntArray(MAX_NEIGHBORS + 1)
        val sizeCursor = IntArray(MAX_NEIGHBORS + 1)

        fun ensureConCap(need: Int) {
            if (need <= conCap) return
            var cap = conCap
            while (cap < need) cap *= 2
            conUnknown = conUnknown.copyOf(cap * MAX_NEIGHBORS)
            conCount = conCount.copyOf(cap)
            conNeed = conNeed.copyOf(cap)
            order = IntArray(cap)
            conCap = cap
        }

        /** 插入排序单个约束的未知索引（≤8 个），供子集规则双指针使用 */
        fun sortConstraint(i: Int) {
            val off = i * MAX_NEIGHBORS
            val u = conCount[i]
            for (k in 1 until u) {
                val v = conUnknown[off + k]
                var j = k - 1
                while (j >= 0 && conUnknown[off + j] > v) {
                    conUnknown[off + j + 1] = conUnknown[off + j]
                    j--
                }
                conUnknown[off + j + 1] = v
            }
        }

        /** 收集当前所有边界约束；矛盾返回 false */
        fun collect(): Boolean {
            conN = 0
            var idx = 0
            while (idx < n) {
                if (known[idx] != SAFE) {
                    idx++
                    continue
                }
                val r = idx / cols
                val c = idx - r * cols
                val number = board[r][c].adjacentMines
                if (number == 0) {
                    idx++
                    continue
                }
                ensureConCap(conN + 1)
                val off = conN * MAX_NEIGHBORS
                var flagged = 0
                var u = 0
                val r0 = if (r > 0) r - 1 else 0
                val r1 = if (r + 1 < rows) r + 1 else rows - 1
                val c0 = if (c > 0) c - 1 else 0
                val c1 = if (c + 1 < cols) c + 1 else cols - 1
                for (nr in r0..r1) {
                    val base = nr * cols
                    for (nc in c0..c1) {
                        if (nr == r && nc == c) continue
                        val ni = base + nc
                        when (known[ni]) {
                            MINE -> flagged++
                            UNKNOWN -> conUnknown[off + u++] = ni
                        }
                    }
                }
                if (u == 0) {
                    idx++
                    continue
                }
                val need = number - flagged
                if (need < 0 || need > u) {
                    failed = true // 约束矛盾：不应发生
                    return false
                }
                conCount[conN] = u
                conNeed[conN] = need
                conN++
                idx++
            }
            return true
        }

        /**
         * 单点规则批量落地：对所有 `need == 0` 约束批量翻开、对所有
         * `need == unknown.size` 约束批量标雷。返回 1=有进展、0=无进展、-1=推理矛盾。
         * 同一批结论都来自同一份快照、都是可靠结论，彼此不会冲突。
         */
        fun applySinglePoint(): Int {
            var progress = false
            var i = 0
            while (i < conN) {
                val u = conCount[i]
                val need = conNeed[i]
                if (need == 0) {
                    val off = i * MAX_NEIGHBORS
                    for (k in 0 until u) {
                        revealIdx(conUnknown[off + k])
                        if (failed) return -1
                    }
                    progress = true
                } else if (need == u) {
                    val off = i * MAX_NEIGHBORS
                    for (k in 0 until u) markMineIdx(conUnknown[off + k])
                    progress = true
                }
                i++
            }
            return if (progress) 1 else 0
        }

        /**
         * 子集规则：按未知数计数排序分桶后，只在长度严格更短的 A 与更长的 B 之间检查
         * 包含关系；用有序数组双指针（无 Set）。返回 1=有进展、0=无进展、-1=推理矛盾。
         */
        fun applySubset(): Int {
            for (s in 0..MAX_NEIGHBORS) sizeCount[s] = 0
            var i = 0
            while (i < conN) {
                sizeCount[conCount[i]]++
                i++
            }
            var acc = 0
            for (s in 1..MAX_NEIGHBORS) {
                sizeStart[s] = acc
                sizeCursor[s] = acc
                acc += sizeCount[s]
            }
            i = 0
            while (i < conN) {
                val s = conCount[i]
                order[sizeCursor[s]++] = i
                i++
            }

            var sa = 1
            while (sa < MAX_NEIGHBORS) {
                val aStart = sizeStart[sa]
                val aEnd = aStart + sizeCount[sa]
                var sb = sa + 1
                while (aEnd > aStart && sb <= MAX_NEIGHBORS) {
                    val bStart = sizeStart[sb]
                    val bEnd = bStart + sizeCount[sb]
                    var ai = aStart
                    while (ai < aEnd) {
                        val a = order[ai]
                        val aOff = a * MAX_NEIGHBORS
                        val aU = conCount[a]
                        var bi = bStart
                        while (bi < bEnd) {
                            val b = order[bi]
                            val bOff = b * MAX_NEIGHBORS
                            val bU = conCount[b]
                            // 有序数组双指针判断 A ⊂ B
                            var p = 0
                            var q = 0
                            while (p < aU && q < bU) {
                                val av = conUnknown[aOff + p]
                                val bv = conUnknown[bOff + q]
                                when {
                                    av == bv -> {
                                        p++
                                        q++
                                    }
                                    av > bv -> q++
                                    else -> break
                                }
                            }
                            if (p == aU) {
                                // A ⊂ B：差集 = B \ A，大小 = bU - aU
                                val diffSize = bU - aU
                                val needDiff = conNeed[b] - conNeed[a]
                                if (needDiff == 0) {
                                    p = 0
                                    q = 0
                                    while (q < bU) {
                                        val bv = conUnknown[bOff + q]
                                        if (p < aU && conUnknown[aOff + p] == bv) {
                                            p++
                                            q++
                                        } else {
                                            revealIdx(bv)
                                            if (failed) return -1
                                            q++
                                        }
                                    }
                                    return 1
                                }
                                if (needDiff == diffSize) {
                                    p = 0
                                    q = 0
                                    while (q < bU) {
                                        val bv = conUnknown[bOff + q]
                                        if (p < aU && conUnknown[aOff + p] == bv) {
                                            p++
                                            q++
                                        } else {
                                            markMineIdx(bv)
                                            q++
                                        }
                                    }
                                    return 1
                                }
                            }
                            bi++
                        }
                        ai++
                    }
                    sb++
                }
                sa++
            }
            return 0
        }

        // ---------- 主循环 ----------
        revealIdx(startRow * cols + startCol)
        if (failed) return false

        var guard = 0
        while (guard++ < MAX_STEPS) {
            if (!collect()) return false

            val single = applySinglePoint()
            if (single < 0) return false
            if (single > 0) continue

            // 单点无进展时，为子集规则准备有序的未知索引
            var i = 0
            while (i < conN) {
                sortConstraint(i)
                i++
            }
            val subset = applySubset()
            if (subset < 0) return false
            if (subset > 0) continue

            // 全局规则：剩余雷数耗尽 / 恰好等于未知格数
            val unknownCount = n - revealedSafe - minesFound
            if (unknownCount == 0) break
            val remaining = totalMines - minesFound
            if (remaining == 0) {
                var idx = 0
                while (idx < n) {
                    if (known[idx] == UNKNOWN) {
                        revealIdx(idx)
                        if (failed) return false
                    }
                    idx++
                }
                continue
            }
            if (remaining == unknownCount) {
                var idx = 0
                while (idx < n) {
                    if (known[idx] == UNKNOWN) markMineIdx(idx)
                    idx++
                }
                continue
            }
            break
        }

        // 通关判定：所有非雷格都能被推理翻开（不修改 board）
        var idx = 0
        while (idx < n) {
            if (known[idx] != SAFE) {
                val r = idx / cols
                val c = idx - r * cols
                if (!board[r][c].mine) return false
            }
            idx++
        }
        return true
    }
}
