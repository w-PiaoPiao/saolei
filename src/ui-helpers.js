/**
 * 桌面端纯函数工具（UMD）：格式化、光标夹取、对话框栈等可单测逻辑。
 * 同时被渲染进程（<script> 全局 UIHelpers）与 node:test（require）使用。
 */
;(function (root, factory) {
  if (typeof module === 'object' && module.exports) {
    module.exports = factory()
  } else {
    root.UIHelpers = factory()
  }
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict'

  /** 把任意值夹取为 [min, max] 内的整数；非有限数返回 fallback */
  function clampInt(value, min, max, fallback = min) {
    const n = Number(value)
    if (!Number.isFinite(n)) return fallback
    return Math.max(min, Math.min(max, Math.round(n)))
  }

  /**
   * 键盘光标夹取到棋盘内。棋盘为空（rows/cols <= 0）时返回 {row:0,col:0}。
   * 用于 H1：开局/越界时把 ui.cursor 拉回合法范围，而不是让按键守卫提前 return。
   */
  function clampCursor(row, col, rows, cols) {
    const r = Number.isFinite(Number(rows)) ? Math.floor(Number(rows)) : 0
    const c = Number.isFinite(Number(cols)) ? Math.floor(Number(cols)) : 0
    if (r <= 0 || c <= 0) return { row: 0, col: 0 }
    return {
      row: clampInt(row, 0, r - 1, 0),
      col: clampInt(col, 0, c - 1, 0)
    }
  }

  /** 整数秒 → "mm:ss"；>= 1 小时 → "h:mm:ss"。负数按 0 处理。 */
  function formatDuration(totalSeconds) {
    const sec = Math.max(0, Math.floor(Number(totalSeconds) || 0))
    const hours = Math.floor(sec / 3600)
    const minutes = Math.floor((sec % 3600) / 60)
    const seconds = sec % 60
    const ss = String(seconds).padStart(2, '0')
    if (hours > 0) return `${hours}:${String(minutes).padStart(2, '0')}:${ss}`
    return `${String(minutes).padStart(2, '0')}:${ss}`
  }

  /** 毫秒 → 同 formatDuration；null/undefined → "--:--"。 */
  function formatDurationMs(ms) {
    if (ms === null || ms === undefined) return '--:--'
    const n = Number(ms)
    if (!Number.isFinite(n)) return '--:--'
    return formatDuration(Math.floor(Math.max(0, n) / 1000))
  }

  /** LED 3 位数字：0..999；负数用于剩余雷数为负（"-01" 风格，最多 3 字符） */
  function formatCounter(value) {
    const n = Number(value)
    const safe = Number.isFinite(n) ? Math.trunc(n) : 0
    if (safe < 0) return '-' + String(Math.abs(safe)).padStart(2, '0')
    return String(safe).padStart(3, '0')
  }

  /** 计时 LED：显示值封顶 999（统计仍用真实毫秒，见 M7） */
  function formatLedSeconds(seconds) {
    const n = Math.max(0, Math.floor(Number(seconds) || 0))
    return String(Math.min(999, n)).padStart(3, '0')
  }

  /** 3BV/s 等速率：非有限/空 → "--"，否则两位小数（与语言无关） */
  function formatRate(rate) {
    const n = Number(rate)
    if (rate === null || rate === undefined || !Number.isFinite(n)) return '--'
    return n.toFixed(2)
  }

  /** 胜率百分数：0..100 取整 */
  function formatPercent(value) {
    const n = Number(value)
    if (!Number.isFinite(n)) return 0
    return Math.round(Math.max(0, Math.min(100, n)))
  }

  /**
   * 对话框栈：Esc 永远关闭栈顶；打开新层时可按需先关旧层。
   * 条目为 {id, close}（app.js 提供 close 回调），纯逻辑便于单测。
   */
  class DialogStack {
    constructor() {
      this.entries = []
    }

    push(entry) {
      if (!entry || typeof entry.close !== 'function') {
        throw new TypeError('DialogStack.push 需要 {id, close}')
      }
      this.entries.push(entry)
      return entry
    }

    remove(id) {
      const index = this.entries.findIndex((e) => e.id === id)
      if (index === -1) return null
      return this.entries.splice(index, 1)[0]
    }

    top() {
      return this.entries.length ? this.entries[this.entries.length - 1] : null
    }

    size() {
      return this.entries.length
    }

    has(id) {
      return this.entries.some((e) => e.id === id)
    }

    /** 返回被清空的条目（调用方负责逐个 close DOM） */
    clear() {
      const all = this.entries.slice()
      this.entries.length = 0
      return all
    }
  }

  return {
    clampInt,
    clampCursor,
    formatDuration,
    formatDurationMs,
    formatCounter,
    formatLedSeconds,
    formatRate,
    formatPercent,
    DialogStack
  }
})
