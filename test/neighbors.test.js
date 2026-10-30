'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const Neighbors = require('../src/neighbors.js')

function bruteForce(rows, cols, r, c) {
  const out = []
  for (let dr = -1; dr <= 1; dr++) {
    for (let dc = -1; dc <= 1; dc++) {
      if (dr === 0 && dc === 0) continue
      const nr = r + dr
      const nc = c + dc
      if (nr >= 0 && nr < rows && nc >= 0 && nc < cols) out.push([nr, nc])
    }
  }
  return out
}

test('NEIGHBOR_OFFSETS：8 个固定偏移且被冻结', () => {
  assert.equal(Neighbors.NEIGHBOR_OFFSETS.length, 8)
  const set = new Set(Neighbors.NEIGHBOR_OFFSETS.map((o) => `${o.dr},${o.dc}`))
  assert.equal(set.size, 8)
  assert.ok(!set.has('0,0'))
  assert.ok(Object.isFrozen(Neighbors.NEIGHBOR_OFFSETS))
})

test('forEachNeighbor：角/边/中心/1×1 的邻居数正确', () => {
  const collect = (rows, cols, r, c) => {
    const out = []
    Neighbors.forEachNeighbor(rows, cols, r, c, (nr, nc) => out.push([nr, nc]))
    return out
  }
  assert.deepEqual(collect(9, 9, 0, 0), bruteForce(9, 9, 0, 0))
  assert.equal(collect(9, 9, 0, 0).length, 3)
  assert.equal(collect(9, 9, 0, 4).length, 5)
  assert.equal(collect(9, 9, 4, 4).length, 8)
  assert.equal(collect(1, 1, 0, 0).length, 0)
  assert.equal(collect(3, 3, 1, 1).length, 8)
})

test('forEachNeighbor：越界坐标安全返回（不调用回调）', () => {
  let called = 0
  Neighbors.forEachNeighbor(9, 9, -1, 0, () => called++)
  Neighbors.forEachNeighbor(9, 9, 0, 9, () => called++)
  Neighbors.forEachNeighbor(9, 9, 9, 9, () => called++)
  assert.equal(called, 0)
})

test('get：CSR 索引与暴力遍历一致（行主序下标）', () => {
  for (const [rows, cols] of [[1, 1], [2, 3], [5, 5], [9, 16], [16, 30]]) {
    const graph = Neighbors.get(rows, cols)
    assert.equal(graph.cells, rows * cols)
    assert.equal(graph.start.length, rows * cols + 1)
    for (let r = 0; r < rows; r++) {
      for (let c = 0; c < cols; c++) {
        const idx = r * cols + c
        const expected = bruteForce(rows, cols, r, c).map(([nr, nc]) => nr * cols + nc)
        const actual = []
        for (let k = graph.start[idx]; k < graph.start[idx + 1]; k++) actual.push(graph.list[k])
        assert.deepEqual(actual, expected, `${rows}x${cols} @(${r},${c})`)
      }
    }
  }
})

test('get：同尺寸命中缓存、不同尺寸各自缓存、clearCache 后重建', () => {
  const a = Neighbors.get(9, 9)
  const b = Neighbors.get(9, 9)
  assert.equal(a, b, '同尺寸应复用缓存')
  const other = Neighbors.get(16, 16)
  assert.notEqual(a, other)
  Neighbors.clearCache()
  const rebuilt = Neighbors.get(9, 9)
  assert.notEqual(a, rebuilt, 'clearCache 后应重建')
  assert.deepEqual(Array.from(rebuilt.start), Array.from(a.start))
  assert.deepEqual(Array.from(rebuilt.list), Array.from(a.list))
})
