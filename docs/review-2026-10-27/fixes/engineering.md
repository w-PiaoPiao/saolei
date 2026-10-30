# 工程 / 发布 / 文档 / 仓库修复记录（engineering）

- Owner：engineering（原 product-features）
- 范围：H4、H5、M18（待 Lead 指令）、M21、L16、L17、L18、L19
- 协作依据：`docs/review-2026-10-27/FIX-PLAN.md` §0/§1/§4
- 状态时间：2026-10-27（M18 尚未执行）

## 状态总表

| ID | 状态 | 关键改动（file:line 或路径） | 验证 |
|---|---|---|---|
| H4 密钥备份移出仓库 | ✅ 已修 | `dist/keystore-backup-v1.2.1.zip` → `~/saolei-secrets-backup/keystore-backup-v1.2.1.zip`（目录 700、文件 600）；`dist/` 内已无 keystore 相关文件；`android/keystore/` 保留（本地签名用，已 gitignore）并 chmod 700/600；`.gitignore` 新增 `*.keystore`/`*.jks`/`*.p12`/`*.pem`/`keystore.properties`/`dist/keystore*`/`android/keystore/`/`**/.DS_Store` 等；README「发布与签名」「已知限制」加安全说明 | `ls dist \| grep -i keystore` 无输出；`ls -la ~/saolei-secrets-backup` 目录 700、文件 600；`git check-ignore -v` 验证规则命中 |
| H5 LICENSE | ✅ 已修 | 新增 `LICENSE`（MIT，Copyright 2026 w-PiaoPiao）；`package.json` 增 `license`/`author` | `node -e` 读取 package.json；`head LICENSE` |
| H5 版本单一来源 | ✅ 已修 | `VERSION`=1.3.0；`package.json.version`=1.3.0（`npm version 1.3.0 --no-git-tag-version` 同步 `package-lock.json` 顶层与 `packages[""].version`）；`CHANGELOG.md` 首条 `[1.3.0]`；`scripts/check-version.mjs` 校验 VERSION↔package.json↔CHANGELOG↔tag | `node scripts/check-version.mjs` 通过；模拟 `desktop-v1.3.0`/`android-v1.3.0` 通过，错误前缀/1.2.9 被拒绝（见下） |
| H5 tag 语义 | ✅ 已修 | `.github/workflows/build.yml` 触发 `desktop-v*`；`android.yml` 触发与解析改为 `android-v*`（`${GITHUB_REF_NAME#android-v}`）；两个 workflow 都在 tag 上跑 `check-version --expect-prefix`；README 说明历史 `v*` 冻结 | `node scripts/check-version.mjs --expect-prefix ...` 正/反用例；workflow YAML 通过 Prettier 解析 |
| H5 CI permissions/job env | ✅ 已修 | 两个 workflow 顶层 `permissions: contents: read`；桌面 `build` job 与 `publish-checksums` job、安卓 `release` job 单独 `contents: write`；安卓 release job 将 4 个 secrets 放 job 级 `env`，用 `Detect signing secrets` step 的 `outputs.available` 做门禁（缺失时 `::error::` + 失败，替代可疑的 step 级 `if` 引用同 step env） | 代码审阅；`actionlint` 不在本机，YAML 用 Prettier 解析通过 |
| H5 产物 SHA256 + 发布说明 | ✅ 已修 | `scripts/sha256sums.mjs`、`scripts/merge-sha256sums.mjs`、`scripts/release-notes.mjs`；桌面每平台生成 `dist/SHA256SUMS-<os>.txt`，`publish-checksums` job 合并成 `SHA256SUMS.txt` 并 `gh release upload/edit`；安卓生成 `SHA256SUMS-android.txt` 一并上传；release notes 自动取 CHANGELOG 最新段 | 脚本在 /tmp 冒烟：sha 生成、合并、release-notes 输出均正常 |
| H5 README 未签名声明 | ✅ 已修 | README 中英文「Build/Releases/Known limitations」明确未签名/未公证、CI 平台矩阵（mac arm64 / win x64 / linux x64） | 阅读校对 |
| H5 package.json 元数据 / ASCII 产物名 | ✅ 已修 | `package.json`：`description`/`license`/`author`/`homepage`/`bugs`/`keywords`/`engines`/`check-version`/`lint`/`format`/`clean` 脚本；`artifactName`=`minesweeper-${version}-${os}-${arch}.${ext}`；`productName` 仍为「扫雷」；`appId` 未改（README 已知限制说明原因）；补 `mac.category`/`linux.category`/`publish` | JSON 解析通过；`node -p require('./package.json').build.artifactName` |
| M21 CI 质量门 | ✅ 已修 | `eslint.config.mjs`（flat config，最小规则集）、`.prettierrc.json`；`package.json` 增 `lint`/`format`/`format:check`；桌面 PR/main push 跑 `check-version → lint → format:check → npm test`；`npm ci` 使用 `cache: npm`（`setup-node`）；两个 workflow 加 `concurrency`（PR 取消旧任务）与 `timeout-minutes`；安卓 PR 增 `:app:testDebugUnitTest` + `:app:lintDebug` | `npm run lint` 0 error（1 个历史 `UIRenderer` unused warning）；`npm run format:check` 通过；`npm test` 148/148 通过（集成后口径；本层改动时 95 项，其余由 desktop-engine/desktop-ui 补齐） |
| L16 lint/editorconfig/权限 | ✅ 已修 | `.editorconfig`、`.nvmrc`（22）、ESLint/Prettier 配置；所有 tracked 文件权限统一为 644（`gradlew`/`*.sh` 保持 755），`git ls-files -s` 无异常 mode | `git ls-files -s \| awk '$1!=100644 && $1!=100755'` 无输出 |
| L17 README 重写 | ✅ 已修 | 中英 README 重写：修正 R-1 Linux 架构、R-2 键盘"全操作"、R-3 F2 归因、R-4 无猜 always、R-5 APK 体积（改为签名 release ≈0.3MB/debug ≈2.2MB）、R-6 自定义范围（9–50、85%）、R-7 暂停语义、R-8 保留键、R-9 中英一致、R-10 版本/下载表、R-11 设备名、R-12 非 vX.Y.Z 中止、R-13 测试范围、R-14 非 ASCII 路径、R-15 截图/许可/已知限制；新增架构/开发/发布签名/已知限制/许可证章节；中英章节结构一一对应 | 阅读校对；链接（README↔zh、LICENSE、docs/README、review）均为相对路径 |
| L17 docs 索引/横幅 | ✅ 已修 | 新增 `docs/README.md`（当前审查/历史文档/约定索引）；`docs/audit-2026-10-12.md` 与 `docs/superpowers/specs/2026-07-14-minesweeper-design.md` 顶部加「历史快照/已被取代」横幅（正文未改） | `head -8` 检查 |
| L17 GitHub 模板 | ✅ 已修 | `.github/ISSUE_TEMPLATE/bug_report.yml`、`feature_request.yml`、`config.yml`、`.github/PULL_REQUEST_TEMPLATE.md`、`.github/dependabot.yml`（npm/gradle/github-actions） | YAML 通过 Prettier 解析 |
| L17 截图 | ✅ 已修 | desktop-ui 产出 `docs/screenshots/desktop-light.png`、`desktop-dark.png`（1768×1140 PNG，高级局/中文明文）；中英 README 截图章节已引用且文件存在 | `ls -la docs/screenshots/`；`file` 确认 PNG；`read_image` 目检浅色图内容正确 |
| L18 仓库卫生 | ✅ 已修 | 删除仓库内 4 个 `.DS_Store`（根、docs、docs/superpowers、android）；新增 `scripts/clean.sh`（默认清理构建缓存+`.DS_Store`，保留 `dist/` 安装包与 `node_modules`；`--dist` 才删安装包）；README 已知限制说明 `npm run clean` | `find . -name .DS_Store` 无输出；`bash -n scripts/clean.sh` 通过 |
| L19 打包元数据 | ✅ 已修 | 见上「package.json 元数据 / ASCII 产物名」；`appId` 保持不变并在 README Known limitations 说明（改动会移动 userData、丢统计） | JSON 解析；README 校对 |
| H5 补充：安装包带 LICENSE | ✅ 已修 | `package.json` `build.files` 增加 `"LICENSE"`（安装包内随附许可文件） | JSON 解析 + Prettier 通过 |
| H5 补充：asarUnpack 依赖链（打包后 worker 修复） | ✅ 已修 | 新增 `src/neighbors.js` 后，`generator-worker.js → generator.js → solver.js/config.js` 均依赖 `neighbors.js`，原 `build.asarUnpack` 缺该项会导致打包后 worker require 失败。已在 `package.json` `build.asarUnpack` 补齐 `src/neighbors.js`（现为 5 项，覆盖 worker 完整依赖链）；`build.files` 保留 `LICENSE`；`artifactName`/`productName`/`appId` 等未变 | 依赖链核对：worker 仅 require `./generator.js`；generator require `solver/config/neighbors`；solver require `neighbors`。用 `cp` 出 5 个文件到 /tmp 后以 Node `worker_threads` 直跑 worker：返回 `{ok:true, mines:81, rows:9, cols:9, safeRow:4, safeCol:4, noGuessFallback:false}`（说明 asarUnpack 集合完整）。`electron-builder --dir` + CDP 复验由 Lead 执行 |
| H5 补充：check-version 本地/双前缀提示 | ✅ 已修 | 无 `GITHUB_REF_NAME` 时打印「本地/PR 模式：仅校验内部一致性」及 `desktop-v*`/`android-v*` 两种发布校验用法；两种前缀均实测通过 | `node scripts/check-version.mjs`、`GITHUB_REF_NAME=desktop-v1.3.0 ... --expect-prefix desktop-v`、`GITHUB_REF_NAME=android-v1.3.0 ... --expect-prefix android-v` 全通过 |
| L17 发布前演练清单 | ✅ 已修 | 新增 `docs/release-checklist.md`（A 版本一致性 / B 本地构建与测试 / C CI 标签演练 / D 未签名声明 / E 发布后与回滚 / F 不在范围）；中英 README「发布与签名」末段、`docs/README.md` 索引均加链接 | 文件存在；README 相对链接路径正确 |
| M18 Electron 升级 | ✅ 已修 | 备份 `/tmp/m18-backup/package.json`、`package-lock.json` 后执行 `npm i -D electron@latest electron-builder@latest`：**electron 33.4.11 → 45.0.0、electron-builder 25.1.8 → 26.18.0**（`package.json` devDeps 为 `^45.0.0`/`^26.18.0`）；`npx electron --version` → `v45.0.0`。`npx electron-builder --dir` 在 builder 26 下 exit 0、无 deprecation/error，产物 `app.asar.unpacked/src/` 含全部 5 个 worker 依赖（含 `neighbors.js`），`app.asar` 175KB；本地临时产物 `dist/mac-arm64`（258MB）已删除，等待 Lead 重建打包产物做 dev+packaged 双 CDP 冒烟 | `npm test` 148/148、`npm run lint` 0 error、`format:check`、`check-version`、`node --check main/preload`、`npm ci --dry-run` 全通过；`npx electron --version` = v45.0.0 |

## 验证记录

```text
$ node scripts/check-version.mjs
版本一致性检查通过：VERSION=1.3.0
  本地/PR 模式：未检测到 GITHUB_REF_NAME，仅校验 VERSION/package.json/CHANGELOG 内部一致性

$ GITHUB_REF_NAME=desktop-v1.3.0 node scripts/check-version.mjs --expect-prefix desktop-v
版本一致性检查通过：VERSION=1.3.0
  标签: desktop-v1.3.0

$ GITHUB_REF_NAME=android-v1.3.0 node scripts/check-version.mjs --expect-prefix android-v
版本一致性检查通过：VERSION=1.3.0
  标签: android-v1.3.0

$ GITHUB_REF_NAME=android-v1.2.9 node scripts/check-version.mjs --expect-prefix android-v
::error::标签版本 1.2.9 与 VERSION=1.3.0 不一致   （exit 1）

$ npm test
tests 148 / pass 148 / fail 0   （集成后口径：desktop-engine 新增 ~52 项 + desktop-ui 的 ui.test.js 19 项）

$ npm run lint
0 errors, 1 warning (src/ui.js UIRenderer unused — 历史全局类，不阻塞)

$ npm run format:check
All matched files use Prettier code style!

$ npm ci --dry-run --ignore-scripts
exit 0（package-lock 与 package.json 一致）

$ npx electron --version
v45.0.0

$ npx electron-builder --dir
exit 0（electron-builder 26.18.0，asarUnpacked 5 文件齐全，无 deprecation/error；产物已清理）

# 其他 owner 的构建验证（用于交叉确认发布链路，非本文件执行）
:core:test 110/110；:app:testDebugUnitTest 15/15；:app:assembleRelease 成功（R8 + lintVital，
versionName=1.3.0 / versionCode=10300，release APK 289,999B，V2 签名）
```

## README 复核记录（以最终实现为准，2026-10-27 集成前）

| 原声明 | 复核结果 | 处理 |
|---|---|---|
| 桌面「显式暂停/恢复」 | 最终实现只有窗口失焦/打开对话框自动暂停（`app.js:1051-1078` 仅 `blur/focus` 监听；无 pause 键位/菜单项） | 中英 README 与 CHANGELOG 已改为「自动暂停（窗口失焦/对话框）」，不宣称显式暂停 |
| 安卓「TalkBack 可聚焦 HUD 与棋盘控件」 | 实现为单个 View 的 `AccessibilityDelegateCompat` + 4 个虚拟动作 + 摘要播报（`GameView.kt:2305-2400`），非逐格虚拟节点 | 改为「把游戏视图作为单个可操作控件，暴露翻开/标雷/新游戏/菜单动作，状态变化播报（摘要级）」 |
| 安卓语言 | 资源化 `values/strings.xml` 99 条 + `values-en/strings.xml` 99 条，跟随系统语言；无应用内语言切换 | Android 章节新增「界面跟随系统语言」；语言切换只在桌面 QoL 表中声明 |
| APK 体积 | android-app 最终实测签名 release APK 289,999B（非历史 170KB/189KB 口径） | README 改为「约 0.3 MB（Debug 约 2.2 MB）」 |
| 统计「暂停/旗胜不刷新任何纪录」 | 最终规则：暂停局不刷新 best；旗胜局仍计入 bestTime、仅跳过 3BV/s best（`stats.js:156-206`） | README 改为分开描述，避免绝对化 |
| 无猜「总是可解」 | 仍可能因确定性门控/预算降级（`generator.js:115-140`），HUD 明示 | 保持「生成成功时可解，降级会提示」的限定措辞 |
| 键盘「全操作」/F2 归因/自定义范围/暂停语义 | 均已按最终代码修正（见 README 表格与 Controls 章节） | ✅ |
| 窗口记忆/缩放/托盘/减少动画/i18n/统计分桶/安卓状态恢复/版本读 VERSION | 读码确认已落地（`main.js` Tray/window-state/zoom/语言菜单；`style.css:617` reduced-motion；`stats.js` bucket；`MainActivity.kt:182-209`；`app/build.gradle.kts:11-18`） | ✅ 保留声明 |

## 待办 / 风险

1. **README 声明复核**：已按最终实现抽查桌面 i18n（`src/i18n.js` + `main.js` 菜单）、托盘/窗口记忆/缩放（`main.js:316/341/359`）、无障碍（`index.html` aria + `ui.js` 播报）、统计分桶/暂停规则（`stats.js`）、安卓状态恢复/TalkBack 摘要级/版本读 VERSION。**集成后 Lead 最终验收时建议再抽查一次中英 README 与最终代码的措辞一致性**（尤其 Android 英文排版未真机验证）。
2. **截图已就绪**：`docs/screenshots/desktop-light.png`/`desktop-dark.png` 均为 1768×1140 PNG（desktop-ui 用 Electron CDP 生成），README 中英引用路径正确。
3. **CHANGELOG 1.3.0 摘要**：可在 Lead 生成 `FIXES.md` 后补具体 ID/数字（当前按领域概述，避免与个人分工细节耦合）。
4. **workflow 未经真实 GitHub Actions 运行**：仅静态校验（YAML 解析 + 脚本本地冒烟 + `npm ci --dry-run`）。首次真实 tag 前建议按 `docs/release-checklist.md` 在 fork 演练。
5. **安卓 `:app:lintDebug` 门禁**：android-app 本地已跑 `lintDebug` 0 error（11 warning），CI 应可通过；若 CI 的 lint 版本/环境不同导致新增 error，需要在该 PR 处理。
6. **`dist/` 旧产物未删**（按 Lead 要求保留用户安装包）；keystore 备份已移出，但 `dist/` 273MB 仍建议发布前用 `bash scripts/clean.sh --dist` 清理。
7. **M18 已完成**：electron 45.0.0 / electron-builder 26.18.0；备份在 `/tmp/m18-backup/`（如需回滚：`cp /tmp/m18-backup/package*.json .`）。升级后本地 `npx electron --version`、单测/lint/format/check-version、`electron-builder --dir` 均通过；打包产物与 CDP 双冒烟由 Lead 复验。CI 首次跑 `npm ci` 会按新的 package-lock 安装 Electron 45（下载体积更大，`setup-node` 缓存可缓解）。
