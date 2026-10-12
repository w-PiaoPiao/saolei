package com.wpiaopiao.saolei.core

/**
 * 方向合成：把当前按住的方向动作合成一步位移。
 * 支持斜向（左+上 → 左上）、相反方向抵消（上+下 → 不动）、边界裁剪。
 * 由桌面版 src/movement.js 移植。
 */
object Movement {

    private val DIRS: Map<String, Pair<Int, Int>> = mapOf(
        "moveUp" to (-1 to 0),
        "moveDown" to (1 to 0),
        "moveLeft" to (0 to -1),
        "moveRight" to (0 to 1)
    )

    /**
     * @param held 当前按住的方向动作（moveUp/moveLeft 等）
     * @return 移动后的 (row, col)；无有效方向时返回 null
     */
    fun combineDirections(
        held: Set<String>,
        rows: Int,
        cols: Int,
        row: Int,
        col: Int
    ): Pair<Int, Int>? {
        var dr = 0
        var dc = 0
        for (action in held) {
            val d = DIRS[action]
            if (d != null) {
                dr += d.first
                dc += d.second
            }
        }
        if (dr == 0 && dc == 0) return null
        // 与桌面版 movement.js 一致用 minOf/maxOf 组合：rows/cols 为 0 时返回 0 而不是
        // coerceIn(0, -1) 的空区间异常（退化棋盘不应让按键路径崩溃）
        return minOf(rows - 1, row + dr).coerceAtLeast(0) to
            minOf(cols - 1, col + dc).coerceAtLeast(0)
    }
}
