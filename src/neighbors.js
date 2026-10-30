/**
 * 8 邻域遍历工具（UMD）：集中「邻居偏移 + 边界裁剪 + CSR 索引缓存」，
 * 供 game.js / solver.js / generator.js 共用，避免 5 处重复的双层 for + 边界判断，
 * 也为后续变体棋盘（环形/六边形）留统一入口（L15/DE-12）。
 *
 * 用法：
 *   Neighbors.forEachNeighbor(rows, cols, r, c, (nr, nc) => { ... })   // 通用/低频
 *   const { start, list } = Neighbors.get(rows, cols)                  // 热路径：按 CSR 索引直取
 *   for (let k = start[idx]; k < start[idx + 1]; k++) { const n = list[k]; ... }
 *
 * CSR 索引按 (rows, cols) 缓存（默认最多 8 组，超出后淘汰最早的一组），
 * 50×50 约 80KB Int32 内存；缓存为进程内惰性构建，Node 与浏览器行为一致。
 */
;(function (root, factory) {
  if (typeof module === 'object' && module.exports) {
    module.exports = factory()
  } else {
    root.Neighbors = factory()
  }
})(typeof self !== 'undefined' ? self : this, function () {
  /** 8 邻域偏移（顺序固定：行优先，dr/dc 均 -1..1） */
  const NEIGHBOR_OFFSETS = Object.freeze([
    Object.freeze({ dr: -1, dc: -1 }),
    Object.freeze({ dr: -1, dc: 0 }),
    Object.freeze({ dr: -1, dc: 1 }),
    Object.freeze({ dr: 0, dc: -1 }),
    Object.freeze({ dr: 0, dc: 1 }),
    Object.freeze({ dr: 1, dc: -1 }),
    Object.freeze({ dr: 1, dc: 0 }),
    Object.freeze({ dr: 1, dc: 1 })
  ])

  const MAX_CACHED_SIZES = 8
  const cache = new Map()

  function cacheKey(rows, cols) {
    return rows + 'x' + cols
  }

  /**
   * 指定尺寸的 CSR 邻域索引（惰性构建、缓存复用）。
   * @returns {{rows:number, cols:number, cells:number, start:Int32Array, list:Int32Array}}
   *   start[idx]..start[idx+1]-1 为 list 中该格的邻居下标区间（行主序下标 r*cols+c）
   */
  function get(rows, cols) {
    const key = cacheKey(rows, cols)
    const hit = cache.get(key)
    if (hit) return hit

    const cells = rows * cols
    const start = new Int32Array(cells + 1)
    let total = 0
    for (let r = 0; r < rows; r++) {
      for (let c = 0; c < cols; c++) {
        start[r * cols + c] = total
        for (let dr = -1; dr <= 1; dr++) {
          for (let dc = -1; dc <= 1; dc++) {
            if (dr === 0 && dc === 0) continue
            const nr = r + dr
            const nc = c + dc
            if (nr >= 0 && nr < rows && nc >= 0 && nc < cols) total++
          }
        }
      }
    }
    start[cells] = total

    const list = new Int32Array(total)
    let k = 0
    for (let r = 0; r < rows; r++) {
      for (let c = 0; c < cols; c++) {
        for (let dr = -1; dr <= 1; dr++) {
          for (let dc = -1; dc <= 1; dc++) {
            if (dr === 0 && dc === 0) continue
            const nr = r + dr
            const nc = c + dc
            if (nr >= 0 && nr < rows && nc >= 0 && nc < cols) list[k++] = nr * cols + nc
          }
        }
      }
    }

    const graph = { rows, cols, cells, start, list }
    cache.set(key, graph)
    if (cache.size > MAX_CACHED_SIZES) {
      const oldest = cache.keys().next().value
      cache.delete(oldest)
    }
    return graph
  }

  /**
   * 遍历 (r, c) 的所有盘内邻居。
   * @param {number} rows
   * @param {number} cols
   * @param {number} r
   * @param {number} c
   * @param {(nr:number, nc:number)=>void} fn
   */
  function forEachNeighbor(rows, cols, r, c, fn) {
    if (!(r >= 0 && r < rows && c >= 0 && c < cols)) return
    const graph = get(rows, cols)
    const idx = r * cols + c
    for (let k = graph.start[idx]; k < graph.start[idx + 1]; k++) {
      const n = graph.list[k]
      fn((n / cols) | 0, n % cols)
    }
  }

  /** 清空索引缓存（测试/释放内存用） */
  function clearCache() {
    cache.clear()
  }

  return { NEIGHBOR_OFFSETS, forEachNeighbor, get, clearCache }
})
