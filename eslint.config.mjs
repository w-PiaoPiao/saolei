import js from '@eslint/js'

/**
 * 最小可用的 ESLint 配置（flat config）。
 *
 * 目标：低成本守住"未定义变量/明显错误"，不引入大规模风格规则（历史代码风格由 Prettier
 * 增量接管：目前只格式化 scripts/ 与 package.json，见 package.json 的 format:check）。
 * 若后续规则需要收紧，优先逐目录开启，而不是一次性全仓 --fix。
 */

const browserGlobals = {
  window: 'readonly',
  document: 'readonly',
  localStorage: 'readonly',
  navigator: 'readonly',
  console: 'readonly',
  setTimeout: 'readonly',
  clearTimeout: 'readonly',
  setInterval: 'readonly',
  clearInterval: 'readonly',
  requestAnimationFrame: 'readonly',
  cancelAnimationFrame: 'readonly',
  matchMedia: 'readonly',
  self: 'readonly',
  globalThis: 'readonly',
  module: 'writable',
  require: 'readonly',
  // 由 index.html 按 <script> 顺序注入的全局符号
  CONFIG: 'readonly',
  GameEngine: 'readonly',
  MineGenerator: 'readonly',
  MineSolver: 'readonly',
  AudioManager: 'readonly',
  StatsManager: 'readonly',
  KeyBindings: 'readonly',
  UIRenderer: 'readonly',
  combineDirections: 'readonly',
  formatKey: 'readonly',
  Neighbors: 'readonly',
  I18N: 'readonly'
}

const nodeGlobals = {
  console: 'readonly',
  process: 'readonly',
  Buffer: 'readonly',
  URL: 'readonly',
  URLSearchParams: 'readonly',
  TextEncoder: 'readonly',
  TextDecoder: 'readonly',
  setTimeout: 'readonly',
  clearTimeout: 'readonly',
  setInterval: 'readonly',
  clearInterval: 'readonly',
  fetch: 'readonly',
  navigator: 'readonly',
  require: 'readonly',
  module: 'writable',
  exports: 'writable',
  __dirname: 'readonly',
  __filename: 'readonly'
}

export default [
  {
    ignores: [
      'node_modules/**',
      'dist/**',
      'android/**',
      'docs/**',
      '.codegraph/**',
      '.gradle-user-home/**',
      '*.config.js'
    ]
  },
  js.configs.recommended,
  {
    // 渲染进程（浏览器环境 + UMD 暴露的全局类）
    files: ['src/**/*.js'],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: 'script',
      globals: browserGlobals
    },
    rules: {
      // 历史代码中存在有意保留的空 catch（如 localStorage 解析失败静默回落）
      'no-empty': ['error', { allowEmptyCatch: true }],
      // 迁移期允许冗余变量名/未使用参数，避免一次性大改
      'no-unused-vars': ['warn', { args: 'none', caughtErrors: 'none', varsIgnorePattern: '^_' }],
      'no-fallthrough': 'error',
      // 各文件通过 <script> 顺序共享全局类；文件自身的 class 声明不算重复定义
      'no-redeclare': ['error', { builtinGlobals: false }],
      // 统计面板用全角空格（U+3000）做中文对齐，属有意为之
      'no-irregular-whitespace': 'off'
    }
  },
  {
    // 主进程 / preload / 测试 / 脚本（Node 环境；scripts 为 ESM，若无 import 仍按 commonjs 也能解析）
    files: ['main.js', 'preload.js', 'test/**/*.js', 'scripts/**/*.mjs'],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: 'module',
      globals: nodeGlobals
    },
    rules: {
      'no-empty': ['error', { allowEmptyCatch: true }],
      'no-unused-vars': ['warn', { args: 'none', caughtErrors: 'none', varsIgnorePattern: '^_' }]
    }
  },
  {
    // CommonJS 文件允许 require/module.exports（sourceType 仍是 module，但不会误报）
    files: ['main.js', 'preload.js', 'test/**/*.js'],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: 'commonjs',
      globals: nodeGlobals
    }
  }
]
