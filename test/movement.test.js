'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const combineDirections = require('../src/movement.js')

const held = (...actions) => new Set(actions)

test('空集合不移动', () => {
  assert.equal(combineDirections(held(), 9, 9, 5, 5), null)
})

test('单方向移动', () => {
  assert.deepEqual(combineDirections(held('moveUp'), 9, 9, 5, 5), { row: 4, col: 5 })
  assert.deepEqual(combineDirections(held('moveDown'), 9, 9, 5, 5), { row: 6, col: 5 })
  assert.deepEqual(combineDirections(held('moveLeft'), 9, 9, 5, 5), { row: 5, col: 4 })
  assert.deepEqual(combineDirections(held('moveRight'), 9, 9, 5, 5), { row: 5, col: 6 })
})

test('斜向组合：左+上 → 左上', () => {
  assert.deepEqual(combineDirections(held('moveLeft', 'moveUp'), 9, 9, 5, 5), { row: 4, col: 4 })
  assert.deepEqual(combineDirections(held('moveRight', 'moveUp'), 9, 9, 5, 5), { row: 4, col: 6 })
  assert.deepEqual(combineDirections(held('moveLeft', 'moveDown'), 9, 9, 5, 5), { row: 6, col: 4 })
  assert.deepEqual(combineDirections(held('moveRight', 'moveDown'), 9, 9, 5, 5), { row: 6, col: 6 })
})

test('相反方向抵消：上+下 / 左+右 不移动', () => {
  assert.equal(combineDirections(held('moveUp', 'moveDown'), 9, 9, 5, 5), null)
  assert.equal(combineDirections(held('moveLeft', 'moveRight'), 9, 9, 5, 5), null)
})

test('三个方向组合：仍按剩余方向移动', () => {
  assert.deepEqual(combineDirections(held('moveUp', 'moveDown', 'moveLeft'), 9, 9, 5, 5), { row: 5, col: 4 })
})

test('边界裁剪：左上角按住 上+左 不动', () => {
  assert.deepEqual(combineDirections(held('moveUp', 'moveLeft'), 9, 9, 0, 0), { row: 0, col: 0 })
})

test('边界裁剪：右下角按住 下+右 不动', () => {
  assert.deepEqual(combineDirections(held('moveDown', 'moveRight'), 9, 9, 8, 8), { row: 8, col: 8 })
})

test('未知动作被忽略', () => {
  assert.deepEqual(combineDirections(held('moveUp', 'activate'), 9, 9, 5, 5), { row: 4, col: 5 })
})
