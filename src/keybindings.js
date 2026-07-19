const KEY_NAMES = {
  'ArrowUp': '↑', 'ArrowDown': '↓', 'ArrowLeft': '←', 'ArrowRight': '→',
  ' ': 'Space', 'Enter': 'Enter', 'Escape': 'Esc', 'F2': 'F2',
  'F1': 'F1', 'F3': 'F3', 'F4': 'F4', 'F5': 'F5', 'F6': 'F6',
  'F7': 'F7', 'F8': 'F8', 'F9': 'F9', 'F10': 'F10', 'F11': 'F11', 'F12': 'F12',
  'Tab': 'Tab', 'Shift': 'Shift', 'Control': 'Ctrl', 'Alt': 'Alt',
  'Backspace': 'Bksp', 'Delete': 'Del', 'Home': 'Home', 'End': 'End',
  'PageUp': 'PgUp', 'PageDown': 'PgDn'
}

function formatKey(key) {
  return KEY_NAMES[key] || (key.length === 1 ? key.toUpperCase() : key)
}

class KeyBindings {
  constructor() {
    this.key = 'minesweeper_bindings'
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
      const saved = JSON.parse(localStorage.getItem(this.key))
      if (!saved) return
      for (const [action, data] of Object.entries(saved)) {
        if (this.bindings[action]) {
          this.bindings[action].key = data.key
        }
      }
    } catch {}
  }

  save() {
    const toStore = {}
    for (const [action, data] of Object.entries(this.bindings)) {
      toStore[action] = { key: data.key }
    }
    localStorage.setItem(this.key, JSON.stringify(toStore))
  }

  getKey(action) {
    return this.bindings[action]?.key ?? null
  }

  getDisplayKey(action) {
    const k = this.getKey(action)
    return k ? formatKey(k) : '-'
  }

  setKey(action, newKey) {
    if (!this.bindings[action]) return false
    this.bindings[action].key = newKey
    this.save()
    return true
  }

  findConflict(action, newKey) {
    for (const [a, data] of Object.entries(this.bindings)) {
      if (a !== action && data.key.toLowerCase() === newKey.toLowerCase()) return a
    }
    return null
  }

  getActionForKey(key) {
    for (const [action, data] of Object.entries(this.bindings)) {
      if (data.key.toLowerCase() === key.toLowerCase()) return action
    }
    return null
  }

  getBindings() {
    return { ...this.bindings }
  }
}
