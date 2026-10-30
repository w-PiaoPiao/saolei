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

// ---------- 第二轮修复回归（M5：损坏存档防护 / 深拷贝 / formatKey 导出） ----------

test('损坏存档：非字符串/null key 回落默认，不再抛 TypeError（M5）', () => {
  const cases = [
    JSON.stringify({ flag: { key: 123 } }),
    JSON.stringify({ flag: { key: null } }),
    JSON.stringify({ flag: { key: undefined } }),
    JSON.stringify({ flag: 'x' }),
    JSON.stringify({ flag: null }),
    JSON.stringify({ moveUp: { key: {} } })
  ]
  for (const raw of cases) {
    const storage = fakeStorage()
    storage.setItem(CONFIG.STORAGE_KEYS.bindings, raw)
    const bindings = new KeyBindings(storage, CONFIG)
    assert.equal(bindings.getKey('flag'), 'f', `raw=${raw}`)
    assert.doesNotThrow(() => bindings.getActionForKey('f'))
    assert.doesNotThrow(() => bindings.getActionForKey('ArrowUp'))
    assert.doesNotThrow(() => bindings.findConflict('flag', 'x'))
  }
})

test('损坏存档：整体为数组/字符串/数字时忽略，保留默认（M5）', () => {
  for (const raw of ['[]', '"abc"', '5', 'null', 'true']) {
    const storage = fakeStorage()
    storage.setItem(CONFIG.STORAGE_KEYS.bindings, raw)
    const bindings = new KeyBindings(storage, CONFIG)
    assert.equal(bindings.getKey('flag'), 'f')
    assert.equal(bindings.getKey('activate'), ' ')
  }
})

test('损坏存档：空串/纯空白/超长 key 一律忽略（M5）', () => {
  const raw = JSON.stringify({
    flag: { key: '' },
    moveUp: { key: '   ' },
    moveDown: { key: 'x'.repeat(100) }
  })
  const storage = fakeStorage()
  storage.setItem(CONFIG.STORAGE_KEYS.bindings, raw)
  const bindings = new KeyBindings(storage, CONFIG)
  assert.equal(bindings.getKey('flag'), 'f')
  assert.equal(bindings.getKey('moveUp'), 'ArrowUp')
  assert.equal(bindings.getKey('moveDown'), 'ArrowDown')
})

test('setKey：拒绝非法键（非字符串/空串/纯空白/超长），允许空格键', () => {
  const { bindings } = make()
  assert.equal(bindings.setKey('flag', 123), false)
  assert.equal(bindings.setKey('flag', null), false)
  assert.equal(bindings.setKey('flag', ''), false)
  assert.equal(bindings.setKey('flag', '   '), false)
  assert.equal(bindings.setKey('flag', 'x'.repeat(100)), false)
  assert.equal(bindings.getKey('flag'), 'f')
  assert.equal(bindings.setKey('flag', ' '), true)
  assert.equal(bindings.getKey('flag'), ' ')
  assert.equal(bindings.setKey('activate', ' '), true) // 默认空格键必须可用
})

test('setKey 保存后新实例可读回；非法值不会把默认键位写坏', () => {
  const storage = fakeStorage()
  const a = new KeyBindings(storage, CONFIG)
  a.setKey('flag', 'q')
  const b = new KeyBindings(storage, CONFIG)
  assert.equal(b.getKey('flag'), 'q')
})

test('getBindings 返回深拷贝（L15）', () => {
  const { bindings } = make()
  const copy = bindings.getBindings()
  copy.flag.key = 'z'
  copy.newAction = { key: 'x' }
  assert.equal(bindings.getKey('flag'), 'f')
  assert.equal(bindings.getBindings().newAction, undefined)
})

test('formatKey 通过类静态与模块导出暴露（L15）', () => {
  assert.equal(typeof KeyBindings.formatKey, 'function')
  assert.equal(KeyBindings.formatKey('ArrowUp'), '↑')
  assert.equal(KeyBindings.formatKey(' '), 'Space')
  assert.equal(KeyBindings.formatKey('f'), 'F')
  assert.equal(KeyBindings.formatKey(123), '-')
  assert.equal(KeyBindings.formatKey(''), '-')
  assert.equal(typeof KeyBindings.isValidKey, 'function')
  assert.equal(KeyBindings.isValidKey(' '), true)
  assert.equal(KeyBindings.isValidKey(''), false)
})

test('getDisplayKey 对损坏值返回占位符（M5）', () => {
  const storage = fakeStorage()
  storage.setItem(CONFIG.STORAGE_KEYS.bindings, JSON.stringify({ flag: { key: 123 } }))
  const bindings = new KeyBindings(storage, CONFIG)
  assert.equal(bindings.getDisplayKey('flag'), 'F') // 回落默认 f → F
  assert.equal(bindings.getDisplayKey('nope'), '-')
})
