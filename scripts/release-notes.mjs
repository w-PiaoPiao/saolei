#!/usr/bin/env node
/**
 * 从 CHANGELOG.md 提取最新版本段落，作为 GitHub Release 说明。
 *
 * 用法：node scripts/release-notes.mjs [--version x.y.z] [--output file]
 * 默认输出到 stdout；--output 时写入文件。
 */

import { readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const changelog = readFileSync(join(ROOT, 'CHANGELOG.md'), 'utf8')

function parseArgs(argv) {
  const args = { version: null, output: null }
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i]
    if (arg === '--version') args.version = argv[++i]
    else if (arg === '--output' || arg === '-o') args.output = argv[++i]
    else if (arg === '--help' || arg === '-h') {
      console.log('用法: node scripts/release-notes.mjs [--version x.y.z] [--output file]')
      process.exit(0)
    } else {
      throw new Error(`未知参数: ${arg}`)
    }
  }
  return args
}

const args = parseArgs(process.argv.slice(2))
const version = args.version || readFileSync(join(ROOT, 'VERSION'), 'utf8').trim()

const heading = `## [${version}]`
const start = changelog.indexOf(heading)
if (start === -1) {
  console.error(`CHANGELOG.md 中找不到 ${heading}`)
  process.exit(1)
}
const rest = changelog.slice(start + heading.length)
const nextIndex = rest.search(/^## \[/m)
// 去掉 " - 2026-10-27" 这类标题日期后缀
const body = (nextIndex === -1 ? rest : rest.slice(0, nextIndex)).replace(
  /^\s*-\s*\d{4}-\d{2}-\d{2}\s*/,
  ''
)
const section = body.trim()
const notes = `## 扫雷 v${version}\n\n${section}\n`

if (args.output) {
  writeFileSync(args.output, notes)
  console.log(`已写入 ${args.output}`)
} else {
  process.stdout.write(notes)
}
