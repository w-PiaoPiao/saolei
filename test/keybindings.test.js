'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const KeyBindings = require('../src/keybindings.js')
const CONFIG = require('../src/config.js')

/** 与安卓版 FakeStorage 对齐的极简内存存储 */
function fakeStorage() {
  const map = new Map()
  return {
    getItem: (k) => (map.has(k) ? map.get(k) : null),
    setItem: (k, v) => map.set(k, String(v)),
    removeItem: (k) => map.delete(k)
  }
}

function make() {
  const storage = fakeStorage()
  return { storage, bindings: new KeyBindings(storage, CONFIG) }
}

test('默认键位正确', () => {
  const { bindings } = make()
  assert.equal(bindings.getKey('moveUp'), 'ArrowUp')
  assert.equal(bindings.getKey('activate'), ' ')
  assert.equal(bindings.getKey('flag'), 'f')
  assert.equal(bindings.getKey('newGame'), 'F2')
})

test('setKey 修改键位并写入存储', () => {
  const { bindings, storage } = make()
  assert.equal(bindings.setKey('flag', 'x'), true)
  assert.equal(bindings.getKey('flag'), 'x')
  const saved = JSON.parse(storage.getItem(CONFIG.STORAGE_KEYS.bindings))
  assert.equal(saved.flag.key, 'x')
})

test('未知动作 setKey 返回 false', () => {
  const { bindings } = make()
  assert.equal(bindings.setKey('nope', 'x'), false)
})

test('load 从存储恢复已保存键位', () => {
  const storage = fakeStorage()
  storage.setItem(
    CONFIG.STORAGE_KEYS.bindings,
    JSON.stringify({ flag: { key: 'q' } })
  )
  const bindings = new KeyBindings(storage, CONFIG)
  assert.equal(bindings.getKey('flag'), 'q')
  assert.equal(bindings.getKey('moveUp'), 'ArrowUp') // 未保存的动作保持默认
})

test('损坏的存档 JSON 回退到默认键位', () => {
  const storage = fakeStorage()
  storage.setItem(CONFIG.STORAGE_KEYS.bindings, '{broken')
  const bindings = new KeyBindings(storage, CONFIG)
  assert.equal(bindings.getKey('flag'), 'f')
})

test('findConflict 检测冲突（大小写不敏感）', () => {
  const { bindings } = make()
  assert.equal(bindings.findConflict('flag', 'F'), null) // 自身不算冲突
  assert.equal(bindings.findConflict('flag', 'F2'), 'newGame')
  assert.equal(bindings.findConflict('newGame', 'f'), 'flag')
  assert.equal(bindings.findConflict('moveUp', 'z'), null)
})

test('getActionForKey 反查动作（大小写不敏感）', () => {
  const { bindings } = make()
  assert.equal(bindings.getActionForKey('ArrowUp'), 'moveUp')
  assert.equal(bindings.getActionForKey('f'), 'flag')
  assert.equal(bindings.getActionForKey('F'), 'flag')
  assert.equal(bindings.getActionForKey(' '), 'activate')
  assert.equal(bindings.getActionForKey('z'), null)
})

test('reset 恢复全部默认键位', () => {
  const { bindings } = make()
  bindings.setKey('flag', 'x')
  bindings.setKey('moveLeft', 'a')
  bindings.reset()
  assert.equal(bindings.getKey('flag'), 'f')
  assert.equal(bindings.getKey('moveLeft'), 'ArrowLeft')
})

test('getDisplayKey 对未知键返回占位符', () => {
  const { bindings } = make()
  assert.equal(bindings.getDisplayKey('moveUp'), '↑')
  assert.equal(bindings.getDisplayKey('activate'), 'Space')
})
