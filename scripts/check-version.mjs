#!/usr/bin/env node
/**
 * 版本一致性检查（CI 与本地通用）。
 *
 * 规则：
 *   - 根 VERSION、package.json.version、CHANGELOG.md 首个数字版本必须一致；
 *   - 传入 --expect-prefix desktop-v / android-v（或设置 GITHUB_REF_NAME）时，
 *     tag 必须带对应前缀且 tag 版本号与 VERSION 一致；
 *   - 历史 v* 标签仅存档，不再用于发布（脚本会给出警告并按失败处理）。
 *
 * 用法：
 *   node scripts/check-version.mjs                              # PR / 本地：只查内部一致性
 *   node scripts/check-version.mjs --expect-prefix desktop-v    # 发布：同时校验标签
 */

import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const errors = []
const warnings = []

function read(rel) {
  return readFileSync(join(ROOT, rel), 'utf8')
}

function fail(message) {
  errors.push(message)
}

function parseArgs(argv) {
  const args = { expectPrefix: null }
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i]
    if (arg === '--expect-prefix') {
      args.expectPrefix = argv[++i] || ''
    } else if (arg.startsWith('--expect-prefix=')) {
      args.expectPrefix = arg.slice('--expect-prefix='.length)
    } else if (arg === '--help' || arg === '-h') {
      console.log('用法: node scripts/check-version.mjs [--expect-prefix desktop-v|android-v]')
      process.exit(0)
    } else {
      fail(`未知参数: ${arg}`)
    }
  }
  return args
}

const args = parseArgs(process.argv.slice(2))

const semver = /^\d+\.\d+\.\d+$/
const version = read('VERSION').trim()
if (!semver.test(version)) fail(`VERSION 不是合法 x.y.z：${JSON.stringify(version)}`)

const pkg = JSON.parse(read('package.json'))
if (pkg.version !== version) {
  fail(`package.json.version=${pkg.version} 与 VERSION=${version} 不一致`)
}

const changelog = read('CHANGELOG.md')
const released = [...changelog.matchAll(/^## \[(\d+\.\d+\.\d+)\]/gm)].map((m) => m[1])
if (released.length === 0) {
  fail('CHANGELOG.md 中找不到形如 "## [x.y.z]" 的版本条目')
} else {
  if (!released.includes(version)) fail(`CHANGELOG.md 缺少 ${version} 的条目`)
  if (released[0] !== version) {
    fail(`CHANGELOG.md 首条版本是 ${released[0]}，应为 ${version}（最新版本必须放在最前）`)
  }
}

// 标签校验（仅 tag 触发时）
const tag = process.env.GITHUB_REF_NAME || ''
const prefixes = ['desktop-v', 'android-v']
if (tag.startsWith('refs/tags/')) fail('请传入纯 tag 名而不是 ref 全名')
if (tag) {
  const expected = args.expectPrefix
  if (expected && !prefixes.includes(expected))
    fail(`--expect-prefix 只支持 ${prefixes.join(' / ')}`)
  if (expected) {
    if (!tag.startsWith(expected)) {
      fail(`当前标签 "${tag}" 不带前缀 "${expected}"（发布标签必须是 ${expected}x.y.z）`)
    } else {
      const tagVersion = tag.slice(expected.length)
      if (!semver.test(tagVersion)) fail(`标签 "${tag}" 的版本部分不是 x.y.z`)
      else if (tagVersion !== version) {
        fail(`标签版本 ${tagVersion} 与 VERSION=${version} 不一致`)
      }
    }
  } else if (tag.startsWith('v') && semver.test(tag.slice(1))) {
    warnings.push(
      `标签 "${tag}" 是历史 v* 语义（已冻结，不再触发发布）；新发布请用 desktop-v* / android-v*`
    )
  } else {
    const hit = prefixes.find((p) => tag.startsWith(p))
    if (hit) {
      const tagVersion = tag.slice(hit.length)
      if (tagVersion !== version) fail(`标签版本 ${tagVersion} 与 VERSION=${version} 不一致`)
    } else {
      warnings.push(`未识别的标签 "${tag}"，跳过标签校验`)
    }
  }
}

for (const w of warnings) console.warn(`::warning::${w}`)
if (errors.length > 0) {
  for (const e of errors) console.error(`::error::${e}`)
  console.error(`\n版本一致性检查失败（${errors.length} 项）`)
  process.exit(1)
}
console.log(`版本一致性检查通过：VERSION=${version}`)
if (tag) {
  console.log(`  标签: ${tag}`)
  if (args.expectPrefix) console.log(`  期望前缀: ${args.expectPrefix}`)
} else {
  console.log(
    '  本地/PR 模式：未检测到 GITHUB_REF_NAME，仅校验 VERSION/package.json/CHANGELOG 内部一致性'
  )
  console.log(
    '  发布校验用法：GITHUB_REF_NAME=desktop-v1.3.0 node scripts/check-version.mjs --expect-prefix desktop-v'
  )
  console.log(
    '                GITHUB_REF_NAME=android-v1.3.0 node scripts/check-version.mjs --expect-prefix android-v'
  )
}
