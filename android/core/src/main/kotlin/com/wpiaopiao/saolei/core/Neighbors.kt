package com.wpiaopiao.saolei.core

/**
 * 八邻域遍历（L15）：统一 8 方向偏移与边界裁剪，避免各模块重复手写
 * `for (dr in -1..1) for (dc in -1..1)` 循环。API 语义与桌面 `src/neighbors.js`
 * 的 `NEIGHBOR_OFFSETS` / `forEachNeighbor` 对齐。
 *
 * 性能约定（为什么不是所有地方都用它）：
 * - [forEachNeighbor] 声明为 `inline`，调用点不会分配 lambda、也不会产生额外函数调用，
 *   适用于普通热路径：数字计算（[GameEngine.countAdjacentMines]）、3BV 展开、和弦、
 *   生成器填格等；
 * - [GameEngine.floodFill] 与 [Solver] 的逐格 8 邻展开是**极限热点**（求解器一次
 *   无猜生成可能执行数万次），故意保留内联手写循环，避免参数传递与边界函数调用；
 *   两处内联实现与本文件语义完全一致（见各自 KDoc）。
 */
object Neighbors {

    /**
     * 八方向偏移（行偏移, 列偏移），固定顺序：左上、上、右上、左、右、左下、下、右下。
     * 只读约定：与桌面 `NEIGHBOR_OFFSETS` 一样，调用方不应修改。
     */
    val NEIGHBOR_OFFSETS: List<Pair<Int, Int>> = listOf(
        -1 to -1, -1 to 0, -1 to 1,
        0 to -1, 0 to 1,
        1 to -1, 1 to 0, 1 to 1
    )

    /**
     * 对 (row, col) 的所有**在界内**的 8 邻格依次调用 [action]（顺序同 [NEIGHBOR_OFFSETS]）。
     *
     * 越界/退化棋盘安全：`rows`/`cols` 为 0（或 [row]/[col] 越界）时不回调、不抛异常，
     * 与桌面 `forEachNeighbor` 的边界裁剪一致。
     */
    inline fun forEachNeighbor(
        rows: Int,
        cols: Int,
        row: Int,
        col: Int,
        action: (neighborRow: Int, neighborCol: Int) -> Unit
    ) {
        for (dr in -1..1) {
            val nr = row + dr
            if (nr < 0 || nr >= rows) continue
            for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                val nc = col + dc
                if (nc >= 0 && nc < cols) action(nr, nc)
            }
        }
    }

    /**
     * 在界内的邻居数量：角落 3、边缘非角 5、内部 8；退化棋盘/越界坐标返回 0。
     * 供测试与"邻居数"类逻辑使用，热路径仍建议直接用 [forEachNeighbor]。
     */
    fun neighborCount(rows: Int, cols: Int, row: Int, col: Int): Int {
        if (row < 0 || row >= rows || col < 0 || col >= cols) return 0
        var count = 0
        forEachNeighbor(rows, cols, row, col) { _, _ -> count++ }
        return count
    }
}
