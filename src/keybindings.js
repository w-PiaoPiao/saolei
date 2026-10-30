const KEY_NAMES = {
  'ArrowUp': '↑', 'ArrowDown': '↓', 'ArrowLeft': '←', 'ArrowRight': '→',
  ' ': 'Space', 'Enter': 'Enter', 'Escape': 'Esc', 'F2': 'F2',
  'F1': 'F1', 'F3': 'F3', 'F4': 'F4', 'F5': 'F5', 'F6': 'F6',
  'F7': 'F7', 'F8': 'F8', 'F9': 'F9', 'F10': 'F10', 'F11': 'F11', 'F12': 'F12',
  'Tab': 'Tab', 'Shift': 'Shift', 'Control': 'Ctrl', 'Alt': 'Alt',
  'Backspace': 'Bksp', 'Delete': 'Del', 'Home': 'Home', 'End': 'End',
  'PageUp': 'PgUp', 'PageDown': 'PgDn'
}

/** 最大可接受键名长度（防御损坏存档写入超长字符串） */
const MAX_KEY_LENGTH = 32

/**
 * 键位是否合法：非空字符串且不是「纯空白」；但单个空格（' '）是默认的「翻开/和弦」键，必须允许。
 * M5：load/setKey 统一用它校验，非字符串（如旧存档里的数字/null）一律回落默认/忽略。
 */
function isValidKey(key) {
  if (typeof key !== 'string' || key.length === 0 || key.length > MAX_KEY_LENGTH) return false
  return key === ' ' || key.trim().length > 0
}

function formatKey(key) {
  // 兼容历史调用（app.js 全局 formatKey）：非法输入返回占位符而不是抛错
  if (!isValidKey(key)) return '-'
  return KEY_NAMES[key] || (key.length === 1 ? key.toUpperCase() : key)
}

class KeyBindings {
  // 存储与配置走依赖注入（浏览器缺省用全局 localStorage/CONFIG），
  // 与安卓版 Storage + FakeStorage 模式对齐，node:test 可直接实例化。
  constructor(storage = globalThis.localStorage, config = globalThis.CONFIG) {
    this.storage = storage
    this.key = config.STORAGE_KEYS.bindings
    this.defaults = {
      moveUp: { key: 'ArrowUp', label: '上移' },
      moveDown: { key: 'ArrowDown', label: '下移' },
      moveLeft: { key: 'ArrowLeft', label: '左移' },
      moveRight: { key: 'ArrowRight', label: '右移' },
      activate: { key: ' ', label: '翻开/和弦' },
      flag: { key: 'f', label: '标旗' },
      newGame: { key: 'F2', label: '新游戏' }
    }
    this.bindings = {}
    this.reset()
    this.load()
  }

  reset() {
    for (const [action, data] of Object.entries(this.defaults)) {
      this.bindings[action] = { key: data.key, label: data.label }
    }
  }

  load() {
    try {
      const raw = this.storage.getItem(this.key)
      if (!raw) return
      const saved = JSON.parse(raw)
      // 存档必须是普通对象（数组/字符串/数字/null 一律视为损坏，保留默认）
      if (!saved || typeof saved !== 'object' || Array.isArray(saved)) return
      for (const [action, data] of Object.entries(saved)) {
        if (!this.bindings[action]) continue
        if (!data || typeof data !== 'object') continue
        // M5：非字符串 / 空串 / 超长 / 纯空白一律忽略（保留默认），避免 keydown 时抛 TypeError
        if (isValidKey(data.key)) this.bindings[action].key = data.key
      }
    } catch {}
  }

  save() {
    const toStore = {}
    for (const [action, data] of Object.entries(this.bindings)) {
      toStore[action] = { key: data.key }
    }
    this.storage.setItem(this.key, JSON.stringify(toStore))
  }

  getKey(action) {
    return this.bindings[action]?.key ?? null
  }

  getDisplayKey(action) {
    const k = this.getKey(action)
    return isValidKey(k) ? formatKey(k) : '-'
  }

  setKey(action, newKey) {
    if (!this.bindings[action]) return false
    if (!isValidKey(newKey)) return false
    this.bindings[action].key = newKey
    this.save()
    return true
  }

  findConflict(action, newKey) {
    if (!isValidKey(newKey)) return null
    const normalized = newKey.toLowerCase()
    for (const [a, data] of Object.entries(this.bindings)) {
      if (a !== action && isValidKey(data.key) && data.key.toLowerCase() === normalized) return a
    }
    return null
  }

  getActionForKey(key) {
    if (typeof key !== 'string') return null
    const normalized = key.toLowerCase()
    for (const [action, data] of Object.entries(this.bindings)) {
      if (isValidKey(data.key) && data.key.toLowerCase() === normalized) return action
    }
    return null
  }

  /** 深拷贝（L15）：调用方修改返回值不会污染内部绑定/落盘数据 */
  getBindings() {
    const copy = {}
    for (const [action, data] of Object.entries(this.bindings)) {
      copy[action] = { key: data.key, label: data.label }
    }
    return copy
  }
}

// L15：保留浏览器全局 formatKey（app.js 历史调用），同时挂到类上供模块化调用与单测
KeyBindings.formatKey = formatKey
KeyBindings.isValidKey = isValidKey

// 浏览器端由 index.html <script> 加载为全局类；node:test 环境按 CommonJS 导出
if (typeof module === 'object' && module.exports) {
  module.exports = KeyBindings
  module.exports.formatKey = formatKey
  module.exports.isValidKey = isValidKey
}
