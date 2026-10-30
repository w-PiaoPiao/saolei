#!/usr/bin/env bash
# 清理本地构建/缓存/系统垃圾文件。
#
# 用法：
#   bash scripts/clean.sh          # 清理构建缓存 + .DS_Store（保留 dist/ 安装包与 node_modules）
#   bash scripts/clean.sh --dist   # 额外删除 dist/ 安装包产物（会先打印将要删除的路径）
#
# 注意：默认不删除 dist/ —— 那里可能有你自己的安装包；也绝不触碰源码和 git 历史。

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

REMOVE_DIST=0
for arg in "$@"; do
  case "$arg" in
    --dist) REMOVE_DIST=1 ;;
    --help | -h)
      sed -n '2,10p' "$0"
      exit 0
      ;;
    *)
      echo "未知参数: $arg（支持 --dist / --help）" >&2
      exit 1
      ;;
  esac
done

echo "== 清理系统/编辑器临时文件 =="
find "$ROOT" -name '.DS_Store' -not -path '*/node_modules/*' -print -delete 2>/dev/null || true

echo "== 清理安卓构建目录 =="
rm -rf \
  "$ROOT/android/app/build" \
  "$ROOT/android/core/build" \
  "$ROOT/android/.gradle" \
  "$ROOT/android/.kotlin"
echo "  android/app/build, android/core/build, android/.gradle, android/.kotlin"

echo "== 清理桌面打包临时目录 =="
# electron-builder 的 --dir 产物（dist/ 本身保留，见下）
rm -rf "$ROOT/dist/mac" "$ROOT/dist/win-unpacked" "$ROOT/dist/linux-unpacked"

if [[ "$REMOVE_DIST" -eq 1 ]]; then
  echo "== --dist：删除 dist/ 下所有安装包产物 =="
  if [[ -d "$ROOT/dist" ]]; then
    du -sh "$ROOT/dist" 2>/dev/null || true
    rm -rf "${ROOT:?}/dist"
    echo "  已删除 dist/"
  else
    echo "  dist/ 不存在，跳过"
  fi
else
  echo "== dist/ 保留（如需连同安装包一起清理：bash scripts/clean.sh --dist）=="
fi

echo "== node_modules/ 保留（如需重装：rm -rf node_modules && npm ci）=="
echo "完成。"
