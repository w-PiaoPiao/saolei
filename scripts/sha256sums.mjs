#!/usr/bin/env node
/**
 * 生成发布产物的 SHA-256 校验和（跨平台，CI 与本地通用）。
 *
 * 用法：
 *   node scripts/sha256sums.mjs dist
 *   node scripts/sha256sums.mjs <dir-or-file>... [--output <file>] [--ext .dmg,.exe,.AppImage,.apk]
 *
 * 默认只收集常见安装包扩展名，忽略 *.blockmap / latest*.yml 等更新元数据。
 */

import { createHash } from 'node:crypto'
import { readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { basename, join } from 'node:path'

const DEFAULT_EXTS = ['.dmg', '.exe', '.AppImage', '.apk', '.aab', '.zip']

function parseArgs(argv) {
  const targets = []
  const args = { output: null, exts: DEFAULT_EXTS.slice() }
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i]
    if (arg === '--output' || arg === '-o') {
      args.output = argv[++i]
    } else if (arg === '--ext') {
      args.exts = (argv[++i] || '')
        .split(',')
        .map((e) => e.trim())
        .filter(Boolean)
    } else if (arg === '--help' || arg === '-h') {
      console.log(
        '用法: node scripts/sha256sums.mjs <dir-or-file>... [--output <file>] [--ext .dmg,.exe]'
      )
      process.exit(0)
    } else if (arg.startsWith('-')) {
      throw new Error(`未知参数: ${arg}`)
    } else {
      targets.push(arg)
    }
  }
  return { args, targets }
}

function collect(path, exts, out) {
  const st = statSync(path)
  if (st.isDirectory()) {
    for (const entry of readdirSync(path)) {
      collect(join(path, entry), exts, out)
    }
    return
  }
  if (!st.isFile()) return
  const name = basename(path)
  if (!exts.some((ext) => (ext.startsWith('.') ? name.endsWith(ext) : name.endsWith(`.${ext}`)))) {
    return
  }
  out.push(path)
}

function sha256(file) {
  return createHash('sha256').update(readFileSync(file)).digest('hex')
}

const { args, targets } = parseArgs(process.argv.slice(2))
if (targets.length === 0) {
  console.error('至少需要一个文件或目录参数')
  process.exit(1)
}

const files = []
for (const target of targets) collect(target, args.exts, files)
if (files.length === 0) {
  console.error(`未找到匹配 ${args.exts.join(',')} 的文件：${targets.join(', ')}`)
  process.exit(1)
}

const lines = files
  .map((file) => ({ name: basename(file), hash: sha256(file) }))
  .sort((a, b) => a.name.localeCompare(b.name))
  .map(({ name, hash }) => `${hash}  ${name}`)

const output = args.output || join(targets[0], 'SHA256SUMS.txt')
writeFileSync(output, `${lines.join('\n')}\n`)
console.log(`已写入 ${output}（${lines.length} 个文件）`)
for (const line of lines) console.log(`  ${line}`)
