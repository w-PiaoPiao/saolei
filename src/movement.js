/**
 * 键盘移动方向合成：把当前按住的方向动作合成一步位移。
 * 支持斜向（如 左+上 → 左上）、相反方向抵消（上+下 → 不动）、边界裁剪。
 * 同时被渲染进程（<script> 全局 combineDirections）和测试（require）使用。
 */
;(function (root, factory) {
  if (typeof module === 'object' && module.exports) {
    module.exports = factory()
  } else {
    root.combineDirections = factory()
  }
})(typeof self !== 'undefined' ? self : this, function () {
  const DIRS = {
    moveUp: { dr: -1, dc: 0 },
    moveDown: { dr: 1, dc: 0 },
    moveLeft: { dr: 0, dc: -1 },
    moveRight: { dr: 0, dc: 1 }
  }

  /**
   * @param {Set<string>} heldActions 当前按住的方向动作（如 moveUp / moveLeft）
   * @param {number} rows 棋盘行数
   * @param {number} cols 棋盘列数
   * @param {number} row 光标当前行
   * @param {number} col 光标当前列
   * @returns {{row: number, col: number}|null} 移动后的位置；无有效方向时返回 null
   */
  function combineDirections(heldActions, rows, cols, row, col) {
    let dr = 0
    let dc = 0
    for (const action of heldActions) {
      const d = DIRS[action]
      if (d) {
        dr += d.dr
        dc += d.dc
      }
    }
    if (dr === 0 && dc === 0) return null
    return {
      row: Math.max(0, Math.min(rows - 1, row + dr)),
      col: Math.max(0, Math.min(cols - 1, col + dc))
    }
  }

  return combineDirections
})
