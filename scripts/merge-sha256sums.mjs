#!/usr/bin/env node
/**
 * 合并多个 SHA256SUMS 文件（CI 中每个平台 job 各产出一份，发布前合并成一份）。
 *
 * 用法：node scripts/merge-sha256sums.mjs <输入目录> <输出文件>
 * 输入目录会递归查找形如 SHA256SUMS*.txt / *-checksums.txt 的文件。
 */

import { mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'

const [inputDir, outputFile] = process.argv.slice(2)
if (!inputDir || !outputFile) {
  console.error('用法: node scripts/merge-sha256sums.mjs <输入目录> <输出文件>')
  process.exit(1)
}

const entries = new Map()

function walk(dir) {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name)
    const st = statSync(path)
    if (st.isDirectory()) {
      walk(path)
    } else if (/^(SHA256SUMS.*\.txt|.*-checksums\.txt)$/.test(name)) {
      for (const line of readFileSync(path, 'utf8').split('\n')) {
        const trimmed = line.trim()
        if (!trimmed) continue
        const match = trimmed.match(/^([0-9a-f]{64})\s{2}(.+)$/)
        if (!match) continue
        entries.set(match[2], `${match[1]}  ${match[2]}`)
      }
    }
  }
}

walk(inputDir)
if (entries.size === 0) {
  console.error(`在 ${inputDir} 中没有找到任何 SHA256SUMS 记录`)
  process.exit(1)
}
const lines = [...entries.values()].sort((a, b) => a.localeCompare(b))
// CI 里输出路径的父目录可能不存在（如 checksums/SHA256SUMS.txt），先创建
mkdirSync(dirname(outputFile), { recursive: true })
writeFileSync(outputFile, `${lines.join('\n')}\n`)
console.log(`已合并 ${lines.length} 条校验和到 ${outputFile}`)
