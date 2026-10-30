/**
 * 桌面 UI 纯逻辑回归：ui-helpers（光标夹取/时间格式化/对话框栈）、i18n、index.html 契约。
 * 这些模块不依赖 DOM/Electron，可直接在 node:test 下运行。
 */
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const UIHelpers = require('../src/ui-helpers.js')
const I18N = require('../src/i18n.js')

const {
  clampInt,
  clampCursor,
  formatDuration,
  formatDurationMs,
  formatCounter,
  formatLedSeconds,
  formatRate,
  formatPercent,
  DialogStack
} = UIHelpers

// ---------- 光标夹取（H1） ----------

test('clampCursor: 合法坐标原样返回', () => {
  assert.deepEqual(clampCursor(3, 4, 9, 9), { row: 3, col: 4 })
  assert.deepEqual(clampCursor(0, 0, 50, 50), { row: 0, col: 0 })
})

test('clampCursor: 越界坐标夹取到棋盘内（H1 键盘不再死锁）', () => {
  assert.deepEqual(clampCursor(11, 0, 9, 9), { row: 8, col: 0 })
  assert.deepEqual(clampCursor(-1, -5, 9, 9), { row: 0, col: 0 })
  assert.deepEqual(clampCursor(999, 999, 16, 30), { row: 15, col: 29 })
})

test('clampCursor: 非法数字/空棋盘回落 (0,0)', () => {
  assert.deepEqual(clampCursor(NaN, NaN, 9, 9), { row: 0, col: 0 })
  assert.deepEqual(clampCursor(3, 3, 0, 0), { row: 0, col: 0 })
  assert.deepEqual(clampCursor(3, 3, -1, -1), { row: 0, col: 0 })
  assert.deepEqual(clampCursor(2.6, 2.4, 9, 9), { row: 3, col: 2 })
})

test('clampInt: 边界与非法值', () => {
  assert.equal(clampInt(5, 0, 10), 5)
  assert.equal(clampInt(-3, 0, 10), 0)
  assert.equal(clampInt(99, 0, 10), 10)
  assert.equal(clampInt('4', 0, 10), 4)
  assert.equal(clampInt('x', 2, 10, 2), 2)
  assert.equal(clampInt(Infinity, 2, 10, 2), 2)
})

// ---------- 时间/数字格式化（M11） ----------

test('formatDuration: 秒补 2 位、超过 1 小时用 h:mm:ss', () => {
  assert.equal(formatDuration(0), '00:00')
  assert.equal(formatDuration(9), '00:09')
  assert.equal(formatDuration(65), '01:05')
  assert.equal(formatDuration(3599), '59:59')
  assert.equal(formatDuration(3600), '1:00:00')
  assert.equal(formatDuration(3725), '1:02:05')
  assert.equal(formatDuration(-5), '00:00')
})

test('formatDurationMs: 毫秒取整到秒；null/非法值显示 --:--', () => {
  assert.equal(formatDurationMs(null), '--:--')
  assert.equal(formatDurationMs(undefined), '--:--')
  assert.equal(formatDurationMs(NaN), '--:--')
  assert.equal(formatDurationMs(0), '00:00')
  assert.equal(formatDurationMs(999), '00:00')
  assert.equal(formatDurationMs(1000), '00:01')
  assert.equal(formatDurationMs(65432), '01:05')
})

test('formatCounter: 3 位 LED，负数与 4 位剩余雷数', () => {
  assert.equal(formatCounter(10), '010')
  assert.equal(formatCounter(0), '000')
  assert.equal(formatCounter(999), '999')
  assert.equal(formatCounter(2125), '2125')
  assert.equal(formatCounter(-1), '-01')
  assert.equal(formatCounter(-10), '-10')
  assert.equal(formatCounter('abc'), '000')
})

test('formatLedSeconds: 显示值封顶 999（统计值仍用毫秒）', () => {
  assert.equal(formatLedSeconds(0), '000')
  assert.equal(formatLedSeconds(9), '009')
  assert.equal(formatLedSeconds(65), '065')
  assert.equal(formatLedSeconds(999), '999')
  assert.equal(formatLedSeconds(1000), '999')
  assert.equal(formatLedSeconds(5000), '999')
  assert.equal(formatLedSeconds(-1), '000')
})

test('formatRate / formatPercent: 与语言无关的两位小数', () => {
  assert.equal(formatRate(null), '--')
  assert.equal(formatRate(undefined), '--')
  assert.equal(formatRate(1.234), '1.23')
  assert.equal(formatRate(0), '0.00')
  assert.equal(formatRate(Infinity), '--')
  assert.equal(formatPercent(0.5), 1)
  assert.equal(formatPercent(50), 50)
  assert.equal(formatPercent(120), 100)
  assert.equal(formatPercent('x'), 0)
})

// ---------- 对话框栈（M9） ----------

test('DialogStack: 后进先出，Esc 关最顶层', () => {
  const stack = new DialogStack()
  const a = { id: 'a', close() {} }
  const b = { id: 'b', close() {} }
  stack.push(a)
  stack.push(b)
  assert.equal(stack.top().id, 'b')
  assert.equal(stack.size(), 2)
  stack.remove('b')
  assert.equal(stack.top().id, 'a')
  stack.remove('a')
  assert.equal(stack.top(), null)
})

test('DialogStack: remove/clear/has 语义', () => {
  const stack = new DialogStack()
  const a = { id: 'a', close() {} }
  stack.push(a)
  assert.equal(stack.has('a'), true)
  assert.equal(stack.remove('nope'), null)
  const cleared = stack.clear()
  assert.equal(cleared.length, 1)
  assert.equal(cleared[0].id, 'a')
  assert.equal(stack.size(), 0)
  assert.throws(() => stack.push({ id: 'x' }), TypeError)
})

// ---------- i18n（M20） ----------

test('i18n: 中英文词典 key 集合一致（无漏译）', () => {
  const zh = Object.keys(I18N.dict('zh')).sort()
  const en = Object.keys(I18N.dict('en')).sort()
  assert.deepEqual(zh, en)
})

test('i18n: t() 插值与未知 key 兜底', () => {
  I18N.setLanguage('zh', { systemLang: 'zh-CN' })
  assert.equal(I18N.t('dialog.ok'), '确定')
  assert.equal(I18N.t('custom.errMines', { max: 100 }), '雷数必须是 1 到 100 之间的整数')
  I18N.setLanguage('en', { systemLang: 'en-US' })
  assert.equal(I18N.t('dialog.ok'), 'OK')
  assert.equal(I18N.t('level.expert'), 'Expert')
  assert.equal(I18N.t('no.such.key'), 'no.such.key')
})

test('i18n: 插值后不残留 {{placeholder}}', () => {
  for (const lang of ['zh', 'en']) {
    I18N.setLanguage(lang, { systemLang: lang === 'zh' ? 'zh-CN' : 'en-US' })
    const text = I18N.t('menu.levelItem', { label: 'X', rows: 9, cols: 9, mines: 10 })
    assert.equal(text.includes('{{'), false, `${lang} menu.levelItem 有未替换占位符: ${text}`)
    const cellText = I18N.t('cell.aria', { row: 1, col: 2, state: 'Y' })
    assert.equal(cellText.includes('{{'), false, `${lang} cell.aria 有未替换占位符: ${cellText}`)
  }
})

test('i18n: 系统语言检测（zh-* → zh，其余 → en）', () => {
  assert.equal(I18N.detectLanguage('zh-CN'), 'zh')
  assert.equal(I18N.detectLanguage('zh-Hant-TW'), 'zh')
  assert.equal(I18N.detectLanguage('en-US'), 'en')
  assert.equal(I18N.detectLanguage('fr-FR'), 'en')
  assert.equal(I18N.detectLanguage(''), 'en')
  assert.equal(I18N.detectLanguage(undefined), 'en')
})

test('i18n: system 偏好解析与非法偏好回落', () => {
  I18N.setLanguage('system', { systemLang: 'zh-Hans-CN' })
  assert.equal(I18N.getPreference(), 'system')
  assert.equal(I18N.getLanguage(), 'zh')
  I18N.setLanguage('system', { systemLang: 'en-GB' })
  assert.equal(I18N.getLanguage(), 'en')
  I18N.setLanguage('nonsense')
  assert.equal(I18N.getPreference(), 'system')
  I18N.setLanguage('zh', { systemLang: 'en-US' })
  assert.equal(I18N.getLanguage(), 'zh')
})

// ---------- index.html / CSP 契约（M14/L2/脚本顺序） ----------

function readIndexHtml() {
  return fs.readFileSync(path.join(__dirname, '..', 'src', 'index.html'), 'utf8')
}

test('index.html: 脚本顺序满足依赖（i18n/helpers/neighbors 在 solver/game 之前）', () => {
  const html = readIndexHtml()
  const order = ['config.js', 'i18n.js', 'ui-helpers.js', 'neighbors.js', 'solver.js', 'generator.js', 'game.js', 'audio.js', 'stats.js', 'keybindings.js', 'movement.js', 'ui.js', 'app.js']
  const positions = order.map((file) => {
    const index = html.indexOf(`src="${file}"`)
    assert.notEqual(index, -1, `index.html 缺少 ${file}`)
    return index
  })
  for (let i = 1; i < positions.length; i++) {
    assert.ok(positions[i] > positions[i - 1], `${order[i]} 必须排在 ${order[i - 1]} 之后`)
  }
})

test('index.html: CSP 含 object/base/form/frame 收紧指令（L2）', () => {
  const html = readIndexHtml()
  for (const directive of ["object-src 'none'", "base-uri 'none'", "form-action 'none'", "frame-src 'none'"]) {
    assert.ok(html.includes(directive), `CSP 缺少 ${directive}`)
  }
})

test('index.html: 棋盘可聚焦 + grid 尺寸语义（M14）', () => {
  const html = readIndexHtml()
  assert.ok(html.includes('role="grid"'))
  assert.ok(html.includes('tabindex="0"'))
  assert.ok(html.includes('aria-rowcount'))
  assert.ok(html.includes('aria-colcount'))
  assert.ok(html.includes('aria-live="polite"'))
})
