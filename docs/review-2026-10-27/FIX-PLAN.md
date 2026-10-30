# 修复计划与协作约定（2026-10-27）

目标：修复 [README.md](README.md) §三 中的全部 P1/P2/P3 问题（H1–H5、M1–M23、L1–L20），双端代码 + 工程/发布/文档；保持测试全绿。
本文件由 Lead 维护；各成员只写自己 write scope 内的文件，**不要修改本文件**。

## 0. 硬性约定

1. 只改自己 write scope 内的文件；需要动别人的文件时先 `send_message` 给该 owner 与 `lead`，不要直接改。
2. 不执行 `git commit/push/checkout/reset`；保留工作区改动供 Lead 汇总。
3. 每个成员最后写 `docs/review-2026-10-27/fixes/<area>.md`：逐项状态表（ID | 状态 已修/部分/未做 | 关键改动 file:line | 验证）。
4. 完成后 `send_message` 给 `lead`：已修 ID、测试结果、未完成/阻塞项、改动文件列表。
5. 测试命令：
   - 桌面：`cd /Users/yizhiwang/Desktop/opencode/扫雷 && npm test`
   - 安卓：`cd /Users/yizhiwang/Desktop/opencode/扫雷/android && GRADLE_USER_HOME=/Users/yizhiwang/Desktop/opencode/扫雷/.gradle-user-home ./gradlew :core:test --offline --console=plain`（app 用 `:app:assembleDebug` / `:app:testDebugUnitTest`）
   - 若 Gradle 被其他项目占用，等 1–2 分钟重试；不要用默认 `~/.gradle`。
6. 桌面 UI 实测：`env -u ELECTRON_RUN_AS_NODE npx electron . --remote-debugging-port=9223 --user-data-dir=/tmp/ms-fix-profile`（用完 kill）。桌面-ui 负责清理。
7. 改 API 必须同步通知跨端调用方；桌面 engine↔ui、安卓 core↔app 的接口见 §2/§3，以本文件为准；需要调整先与 Lead 对齐。

## 1. 分工与 item 映射

| Owner | 负责项 | write scope |
|---|---|---|
| **desktop-engine** | H2（生成器/引擎部分）、M1、M2、M3（桌面 applyPlan）、M4（generator 校验）、M5（keybindings/stats）、M6（桌面 stats）、M7（stats API/0 秒守卫）、M8、L15（neighbors/常量/导出/深拷贝）、L16（parity test）、L20（桌面引擎测试）、DE-13 的 flag-win 数据 | `src/game.js` `src/solver.js` `src/generator.js` `src/generator-worker.js` `src/config.js` `src/movement.js` `src/neighbors.js`(新) `src/stats.js` `src/keybindings.js` `test/` |
| **desktop-ui** | H1、H2（ui 条件）、M7（桌面计时/展示侧）、M9、M10、M11、M12、M13、M14（桌面无障碍）、M15（桌面 worker 超时/取消/落地守卫）、L1、L2、L3、L10、L11、L12（暂停/flag-win 采集）、L13、L15（消费方）、L17 的截图、M20（桌面 i18n） | `src/app.js` `src/ui.js` `src/audio.js` `src/i18n.js`(新) `src/index.html` `src/style.css` `main.js` `preload.js` `test/ui.test.js`(新) `docs/screenshots/**` |
| **android-core** | AC-1（core 注释/辅助）、AC-2、AC-3、AC-5、AC-6、AC-7、AC-8、AC-9、AC-10、AC-11、AC-12、M1（安卓策略对齐）、M2（安卓求解器）、M6（安卓 stats 分桶）、M7（安卓 stats 守卫）、M8、L7（Synth）、L15（安卓侧死代码/封装）、L20（安卓 core 测试） | `android/core/**` |
| **android-app** | H2（安卓异步判定）、H3、AA-3、AA-4、AA-5（+core Snapshot 消费）、AA-7、AA-8、AA-9、AA-10、AA-11、AA-12（SoundManager）、AA-13（i18n）、AA-14、AA-15（部分重构+app 测试）、AA-16（图标/版本默认值）、L5、L6、L7（SoundManager 侧）、L8、L9、L14、L11（安卓 LED）、M7（安卓计时展示）、M14（安卓无障碍）、M19、M20（安卓 i18n）、M22、M23 | `android/app/**` |
| **engineering**（原 product-features） | H4、H5、M18（Electron 升级，最后做）、M21、L16（lint/editorconfig/权限）、L17（README/文档）、L18（仓库卫生）、L19（打包元数据）、VERSION/CHANGELOG/LICENSE/CI | `LICENSE` `README.md` `README.zh-CN.md` `CHANGELOG.md` `.gitignore` `.editorconfig` `.nvmrc` `VERSION` `package.json` `package-lock.json` `.github/**` `scripts/**` `docs/README.md`（仅索引）；旧文档加状态横幅（`docs/audit-2026-10-12.md`、`docs/superpowers/specs/2026-07-14-minesweeper-design.md`） |
| **lead** | 集成、契约裁决、FIX-PLAN/FIXES、最终测试与桌面/APK 验收；不改上述 owner 的 scope（紧急修复除外，改后通知 owner） | 本目录 `README.md`/`FIXES.md`/`FIX-PLAN.md`、`VERSION` 初值 |

**不在本次范围**：功能路线图 PF-1…PF-18（新功能）；Google Play/F-Droid 上架材料（AAB、隐私政策、商店截图）；AGP/Kotlin/compileSdk 大版本升级（可在 L9 记录为后续项）。

## 2. 桌面端接口契约（desktop-engine ↔ desktop-ui）

以最终实现为准，签名必须兼容旧调用/旧测试；engine 完成后把最终签名发给 desktop-ui 与 lead。

1. `GameEngine.wonByFlags: boolean`：由 `toggleFlag` 触发胜利时置 true；`init/initCustom` 重置 false；`wonByFlags` 用于统计标注（L12/DE-13）。
2. `generator.generate(rows, cols, totalMines, safeRow, safeCol, noGuess, now = Date.now, random = Math.random, options = {})`：
   - `options = { budgetMs, maxAttempts }` 可选覆盖 CONFIG.NO_GUESS；无 options 时行为兼容。
   - 返回 `{ mines, noGuessFallback, rows, cols, safeRow, safeCol }`（新增后 4 字段，旧字段不变）。
   - M1 策略：按尺寸/密度做确定性预判（大棋盘/高密度 1–2 次即降级；低密度给足预算），修正 config 注释。
3. `GameEngine.placeMines(safeRow, safeCol, options)`（options 可选）→ 透传 generator options；`applyPlan(plan)` 校验：`plan.rows/cols` 与当前一致、`mines.length === rows*cols`、雷数 === `totalMines`、`safeRow/safeCol` 的 3×3 无雷（缺字段时向后兼容不校验）；失败返回 false / 不开局。
4. `stats.js`：
   - `onGameStart(level, custom = null)`；`get currentBucket()`（预设=`level`；自定义=`custom:<rows>x<cols>/<mines>`）；`lastLevel` 语义保留（字符串 level）。
   - `onGameWin(time, bbbv = 0, meta = null)`、`onGameLose(time, meta = null)`；`meta = { durationMs, pausedMs, wonByFlags }`。
   - history 按 bucket 分别限长（HISTORY_MAX=50）；`getHistory(bucketKey, limit = 20)`、`bestTimeOf(bucketKey)`、`getBuckets()`；旧存档数组按 `level` 迁移。
   - 规则：`time <= 0 && !(durationMs > 0)` 不更新 bestTime；`pausedMs > 0` 的局写 history 但标记 `paused:true` 且不更新 best；`wonByFlags` 写 history 标记但不更新 3BV/s best。
5. `keybindings.js`：`load()/setKey()` 校验 `typeof key === 'string' && key.trim().length > 0`（非法回落默认/忽略）；保留全局 `formatKey`（兼容 `app.js`），同时 `KeyBindings.formatKey` 静态导出；`getBindings()` 深拷贝。
6. 新增 `src/neighbors.js`（UMD `Neighbors`，导出 offsets + `forEachNeighbor(rows, cols, r, c, fn)`）；`index.html` 需在 `solver.js` 之前加载（由 desktop-ui 加 `<script>`）。game/solver/generator 逐步改用（不强求全改，但不能回归）。
7. 桌面 i18n（desktop-ui 实现）：`src/i18n.js` UMD `I18N`（`t(key, vars)`、`setLanguage(lang)`、`getLanguage()`，语言 `zh`/`en`，持久化 `minesweeper_lang`，主进程 `app.getLocale()` 检测）；`main.js` require 同一文件构建菜单；主题/难度/动作 label 映射在 i18n 字典（不改 `config.js` 中文 label，显示层映射 id→文案）。
8. 计时（desktop-ui）：`app.js` 在 `won/lost` 时先 `syncTimer(true)` 再 `stopTimer()`；新增 `game.elapsedMs` 或把 `durationMs` 直接传给 `stats`；LED 仍显示 `min(999, sec)`；`stats` 用 `durationMs` 算 3BV/s。
9. `main.js`（desktop-ui）：worker 请求带 `requestId`，`Map<requestId, Worker>`；2.5s 超时 finish 并 terminate；`webContents` 销毁/`cancelGeneration` IPC 时终止；`update-menu-*` 白名单；CSP 补 `object-src/base-uri/form-action/frame-src 'none'`；`setPermissionRequestHandler` 全拒；`loadFile` catch + `render-process-gone/unresponsive` 提示；窗口 bounds 持久化 + `screen.workArea` 兜底 + 缩放菜单 + 托盘；macOS 补 `appMenu/editMenu/viewMenu/windowMenu` role。

## 3. 安卓端接口契约（android-core ↔ android-app）

以最终实现为准；core 完成后把签名发给 android-app 与 lead。

1. `GameEngine.dirty: MutableSet<Int>`（编码 `row*cols+col`）；`markDirty` 签名不变。
2. `MinePlan(rows: Int, cols: Int, safeRow: Int, safeCol: Int, mines: BooleanArray, noGuessFallback: Boolean)`；`applyPlan/applyPlanAndReveal` 校验几何/长度/雷数/3×3 安全区，失败返回 false / 抛 `require` 由调用方处理；`applyPlanAndReveal` 还校验 `plan.safeRow==row && plan.safeCol==col`。
3. `planMines(..., now: () -> Long = System::nanoTime, random: kotlin.random.Random = kotlin.random.Random.Default)`（新增默认参数保持旧调用可用）；入口 `require`：安全区坐标在界内、`totalMines <= rows*cols-9`、`rows.toLong()*cols` 不溢出且 ≤ MAX_CELLS(10_000)；异常统一 `IllegalArgumentException`。
4. Snapshot：`data class Snapshot(...)` + `fun snapshot(): Snapshot` + `fun restore(snapshot: Snapshot)`（含 rows/cols/totalMines/currentLevel/currentCustom/board mine/adjacent/revealed/flagged/questioned/exploded/wrongFlag/minesPlaced/state/timer/flagCount/revealedCount/bbbv/noGuessFallback）；供 app 的 `onSaveInstanceState`/onPause 存档。
5. `StatsManager`：改为**单 JSON key**（如 `stats.v2`，一次写入）并兼容读取旧多键；API：
   - `onGameStart(level: String, custom: GameEngine.CustomConfig? = null)`
   - `onGameWin(time: Int, bbbv: Int = 0, durationMs: Long = -1L, pausedMs: Long = 0L, wonByFlags: Boolean = false)`
   - `onGameLose(time: Int, durationMs: Long = -1L, pausedMs: Long = 0L)`
   - `bucketOf(level, custom)`；history 按 bucket 分桶限 50；`getHistory(key, limit=20)`；`bestTimeOf(key)`；`getBuckets()`
   - 规则同桌面 §2.4（0 秒/暂停/flag-win）。
6. `Solver`：性能重构（批量落地单点结论/约束增量/位集），规则与 `solve()` 布尔签名不变；`Synth.noise` 补指数包络；新增 `const val SFX_VERSION = 2`（app 用缓存文件名版本）。
7. 安卓 i18n：`GameView`/`MainActivity` 文案迁 `res/values/strings.xml`，加 `values-en/strings.xml`；core 的 LEVELS/KeyBindings label 保留（app 用资源映射 id→文案，不把中文搬进 core 之外的新硬编码）。
8. 安卓计时：`GameView` 维护真实 `elapsedMs`（不受 999 封顶），LED 显示 `min(999, sec)`；终局先同步再 `stats`；向 `StatsManager` 传 `durationMs`；暂停时长 `pausedMs` 累计传入。
9. 安卓重建恢复：`MainActivity` 实现 `onSaveInstanceState` + `onRestoreInstanceState`（或 `onPause` 写 prefs），用 Snapshot；`configChanges` 补 `keyboard|density|fontScale|locale`。
10. 音效缓存：`SoundManager` 文件名 `"$name-v${Synth.SFX_VERSION}.wav"`，清理旧缓存；`setOnLoadCompleteListener` + `autoPause/autoResume`。
11. 无障碍：`GameView` 用 `AccessibilityDelegateCompat`/虚拟节点暴露 HUD/棋盘关键控件，`performClick()`；状态变化 `announceForAccessibility`；`contentDescription` 资源化。
12. 构建版本：`android/app/build.gradle.kts` 的默认 `versionName/versionCode` 从仓库根 `VERSION` 读取（`rootProject.file("../VERSION")`），CI 的 `-PversionName/-PversionCode` 仍优先；versionCode = major*10000+minor*100+patch。

## 4. 版本与发布（engineering + Lead）

- 根 `VERSION`（Lead 先创建）= `1.3.0`；`package.json.version` 与之一致；Android Gradle 默认读它。
- tag 语义定稿：桌面 `desktop-v*`、安卓 `android-v*`；历史 `v*` 仅存档说明，不再触发新发布（`android.yml` 触发改为 `android-v*`，tag 解析剥离前缀）。
- CI：PR 跑 `npm test` + `npm run lint`（若加）+ `:core:test` + `:app:lintDebug`；发布 workflow 加 `permissions: contents: write`、job 级 env 传 secrets、`check-version` 校验 tag==VERSION==package.json==CHANGELOG 首条；产物加 `SHA256SUMS`。
- LICENSE 采用 **MIT**（Lead 决定，可后续更换）；`package.json` 加 `license/author/homepage/bugs/engines`；产物名改 ASCII `minesweeper-${version}-${os}-${arch}`；README 声明未签名/未公证。
- Electron 升级（33→最新受支持）**最后做**：先完成其他修复与 UI 验证，由 engineering 执行 `npm install -D electron@latest electron-builder@latest`，Lead/desktop-ui 用 CDP 冒烟验证后保留；失败则回滚并记录原因。

## 5. 汇报模板

```
【<owner> 完成】
已修：H1, M9, ...（对应 ID + file:line 简述）
测试：npm test 88/88 通过；Electron CDP 验证 ...
未完成/部分：...
风险/接口变更：...
文件：...
```
