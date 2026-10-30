# 桌面 UI / 壳层修复记录（desktop-ui）

日期：2026-10-27 · 范围：`main.js`、`preload.js`、`src/app.js`、`src/ui.js`、`src/audio.js`、`src/index.html`、`src/style.css`、新增 `src/i18n.js`/`src/ui-helpers.js`/`test/ui.test.js`、`docs/screenshots/**`
依据：`docs/review-2026-10-27/FIX-PLAN.md` §1/§2 与总报告 H/M/L 清单。

## 状态总表

| ID | 状态 | 关键改动（file:line） | 验证 |
|---|---|---|---|
| **H1** 键盘光标越界死锁 | ✅ 已修 | `src/ui.js:357` `clampCursorToBoard()`（纯函数在 `src/ui-helpers.js:25`）；`renderAll()` 后 `clampCursorToBoard()`；`src/app.js:957`（全局 keydown，先 `ui.clampCursorToBoard()`） 解析动作前先 clamp；`startGame/startCustomGame` 也 clamp | CDP 实测：高级局光标 row=12 → 切初级 → ArrowRight/ArrowDown 正常移动（clamp 到 (8,1)），F2 弹出“重新开始？”确认框；修复前全灭 |
| **H2** 无猜“先插旗再首点”绕过异步 | ✅ 已修（ui 侧） | `src/ui.js:424` `canGenerateAsync()` 条件改为 `noGuess && !minesPlaced && (ready||playing)`；`src/ui.js:525` `revealSync()` 回退带 `{budgetMs:50,maxAttempts:8}`；`main.js:559+` worker 回传 `rows/cols/safeRow/safeCol` 供 `applyPlanAndReveal` 校验 | CDP 实测：无猜开启 → 先右键插旗（state=playing，LED 009）→ 左键首点：生成提示 49.3ms 内完成并翻开 64 格、旗保留、无降级警告；同步回退预算由引擎实测 p50 25ms/max 39ms |
| **M7** 计时展示侧（毫秒/上限） | ✅ 已修 | `src/app.js:167` `finalizeTimer()`：终局先 `syncTimer` 语义 → `game.elapsedMs=activeMs`、`game.pausedMs`、`game.timer=floor`，再停表；`stats.onGameWin(timeSec, bbbv, {durationMs,pausedMs,wonByFlags})`；LED 仍 `min(999,秒)`（`src/ui.js` updateHUD + `src/ui-helpers.js:63` `formatLedSeconds`） | CDP 探针实测胜局 meta：`{durationMs:74898, pausedMs:1460, wonByFlags:false}`，HUD `074`=floor(74898/1000)，history 记 `durationMs:74898/time:74/paused:true` 且自定义桶 bestTime 未更新；`formatLedSeconds(5000)=='999'` 单测 |
| **M9** 对话框统一层 | ✅ 已修 | `src/app.js:300` `openDialog()`：`role=dialog/aria-modal/aria-labelledby`、背景 `#app` `inert`+`aria-hidden`、Tab 焦点陷阱、关闭恢复焦点、Esc 关栈顶、打开新层先关旧层、关闭时 `cancelBindListening()`、首层挂起计时/末层恢复（仅 playing）；`src/app.js:412` `closeAllDialogs()`；输入框内 Esc 生效（Esc 处理在 `isTyping` 之前）；小窗 `max-height` 滚动 + `.actions` sticky（`src/style.css` `.dialog`/`.dialog .actions`） | CDP 实测：AX 树出现 `role=dialog name=Statistics`、背景 grid 被 inert 隐藏；初始焦点 `stats-close`、Tab 循环不逃逸；自定义框输入框内 Esc 关闭；键位录制关闭后按 `z` 不改档不吞键；350×300 视口下对话框 276px 高、内部滚动且关闭按钮 sticky 可见；嵌套统计+重开确认只保留 1 层、Esc 关顶层 |
| **M10** 菜单 radio 勾选 | ✅ 已修 | `main.js:99` `checkRadioById()` 只对目标项赋 `true`；难度菜单**移除预设与“自定义”之间的 separator**（Electron 会把连续 radio 拆组，导致双勾选——本轮实测发现的根因）；`main.js:121` `buildMenu()` 全部条目带 id；`src/app.js:447` `requestRestart(action,onCancel)` 取消时 `syncMenuDifficulty()` 回写（难度/自定义/重开均覆盖） | CDP 主进程直读实测：启动主题仅深色勾选；点“中级”后仅中级勾选（修复前“高级+自定义”双选）；对局中菜单切难度→取消后菜单与棋盘均保持初级；自定义取消同样回写 |
| **M11** 统计时间格式 | ✅ 已修 | `src/app.js` `formatBestTime()` 用毫秒 → `helpers.formatDurationMs`；`src/ui-helpers.js:36` `formatDuration`：秒/分补 2 位，≥1h 用 `h:mm:ss` | CDP 注入 schema v2 统计后打开统计框实测：`Total play time: 1:02:05`、`Beginner: 00:09`、`Intermediate: 01:05`、`Expert: 02:05`；`formatDuration(65)=='01:05'`、`(3725)=='1:02:05'` 单测 |
| **M12** 自定义“确定”走重开确认 + 合并开局入口 | ✅ 已修 | `src/app.js:447` 自定义确定 → `requestRestart(() => startCustomGame(...))`；`src/app.js:481` `startCustomGame()` 与 `startGame()` 共用 `finishStartGame()`（关对话框+取消生成+init+reset 计时+render+菜单同步+rAF fit） | CDP 实测：对局中自定义确定 → 弹出 “Restart?”（此前直接丢局）；取消后原局/计时保持 |
| **M13** 布局/滚动/窗口 | ✅ 已修 | `src/style.css` `#app` 改 `align-items:stretch` + 子项 `margin:auto`（消除负溢出）、`#board width:max-content; align-self:flex-start; margin:auto`；`src/ui.js:386` `el.scrollIntoView({block:'nearest',inline:'nearest'})` + `#board` 聚焦；`main.js:487` `fitWindowToContent()` 用 `screen.workAreaSize` 兜底、允许自动回缩、用户手动 resize 后只放大；`main.js:341/359` 窗口尺寸持久化；`main.js` 默认窗口按初级棋盘紧凑尺寸 | CDP 实测：50×50 自定义 → 2500 格/18px、窗口 944×860，光标 row49 `rect.top=811 < innerH=832` 且 `#app.scrollTop=150`（自动跟随）；切初级窗口自动回缩 350×402，再切高级放大 884×598；手动 resize 后切初级不再回缩；窗口 760×640 关闭后重启恢复 760×640 且不被自动回缩；新版首次启动默认 350×402 |
| **M14** 桌面无障碍 | ✅ 已修 | `src/style.css`：`:focus-visible` 全局/输入框/棋盘样式、`prefers-reduced-motion` 关闭 4 处动画、`.sr-only`；`src/index.html`：`#board tabindex=0 aria-rowcount/colcount`、`#sr-status aria-live=polite`、`#pause-overlay aria-hidden`、HUD `role=img/timer`+aria-label；`src/ui.js`：gridcell `aria-rowindex/colindex`+i18n aria-label、`aria-activedescendant` 跟随键盘光标、`announceStatus()` 播报胜/负/新纪录/降级/暂停；`#app` `inert` | CDP 实测：AX 树 grid 256 gridcell、`aria-activedescendant="cell-0-1"` 且 `#board` 获焦；HUD `aria-label`（Mines remaining/Elapsed time）+ `#sr-status aria-live=polite`；暂停遮罩 `aria-hidden` 随显隐切换；CSS `prefers-reduced-motion`/`:focus-visible` 为静态审查 |
| **M15** 生成生命周期 | ✅ 已修 | `main.js:559` `generateMines()`：requestId + `Map<requestId, job>`、**2.5s 超时**、`webContents destroyed` 终止、`cancel-generate-mines` IPC 按 sender 终止、`before-quit` 全终止；`preload.js` 增 `cancelMinesGeneration`；`src/ui.js:460` `startAsyncReveal()` 3s 渲染层兜底 + `cancelGeneration()` 调用主进程取消 + `isInteractionBlocked()`（对话框/暂停遮罩时不落地）；`src/app.js:300` openDialog / `src/app.js:1053` pauseGame 中 `ui.cancelGeneration()`；`src/app.js:1083` 生成不落地判定 | CDP 实测失焦路径：无猜首点生成中 → blur → 提示立即收起、1.4s 后 0 格翻开、计时 000；focus 后重试 53 格翻开/计时 001。主进程 2.5s 超时与 renderer 3s 兜底为代码审查（无法稳定构造 >2.5s 挂起） |
| **M20** 桌面 i18n | ✅ 已修 | 新增 `src/i18n.js`（UMD 392 行，zh/en 词典 keys 对齐、`t(key,vars)` 插值/回退、`setLanguage/getLanguage/loadPreference`、`detectLanguage`、localStorage `minesweeper_lang`）；`src/app.js` 静态/动态文案全量走 `t()`（对话框/统计/曲线/关于/键位/HUD/aria/记录横幅）；`main.js:121` 菜单/难度/主题/语言/托盘文案由 I18N 构建，`app:getLocale` 检测系统语言；菜单“语言（跟随系统/中文/English）”经 IPC 即时切换并重建菜单；`index.html` 加 `i18n.js` 且按契约顺序加载 `neighbors.js`（`config → i18n → ui-helpers → neighbors → solver → generator → game …`） | CDP 实测：切 English → `lang=en`、title/按钮/HUD aria/暂停提示/难度按钮全英文、主进程菜单重建为 Game/Difficulty/Theme/Language/Help 且仅 English 勾选；切回中文同理；`localStorage.minesweeper_lang` 持久化、重启后保持；`test/ui.test.js` 校验 zh/en 词典 key 集合一致 + 插值无残留 + 系统语言检测 |
| **L1** 壳层失败路径 | ✅ 已修 | `main.js`：`loadFile().catch` → `showErrorBox`；`render-process-gone`（非 clean-exit → 重新加载/退出对话框）；`unresponsive`（继续等待/重新加载）；`whenReady().then().catch`；worker 失败/超时/窗口销毁均有结果返回 | 代码审查（未构造渲染进程崩溃实测）；`npm test`/`eslint` 通过；无未捕获异常日志 |
| **L2** 安全加固 | ✅ 已修 | `src/index.html` CSP 增加 `object-src/base-uri/form-action/frame-src 'none'`；`main.js:679` `setPermissionRequestHandler`/`setPermissionCheckHandler` 全拒；`main.js` `isTrustedSender()` 校验 IPC 来源（file:///…/src/），`update-menu-*` 白名单，`generate-mines` 上界（rows/cols≤100、mines≤10000 且 ≤rows*cols-9）+ 整数校验 | CDP 实测：renderer 调用超大参数 `generateMines({1000×1000})` 返回“非法生成参数”；伪造 `update-menu-difficulty('bogus')/theme('bogus')` 被忽略、菜单状态不变；CSP 指令由 `test/ui.test.js` 断言 |
| **L3** macOS 菜单集成 | ✅ 已修 | `main.js:121` 菜单模板补 `role:'appMenu'/'editMenu'/'viewMenu'…`，自定义“视图”含 `resetZoom/zoomIn/zoomOut/togglefullscreen`（role 自带快捷键与系统本地化）；Difficulty/Theme/Language/Help 自定义菜单保留；`app.setAboutPanelOptions` | CDP 主进程实测菜单结构：`appMenu, 游戏, 难度, 主题, 语言, Edit, 视图, Window, 帮助`；缩放 role 的实际 Cmd± 触发无法用 CDP 模拟原生菜单点击（unverified runtime），role/accelerator 由 Electron 提供 |
| **L10** 平台化（托盘/bounds/缩放） | ✅ 已修 | `main.js:316` `createTray()`（显示/隐藏、新游戏、静音、退出，Linux 失败静默降级）；`main.js:341/359` `window-state.json` 持久化 + `clampBoundsToWorkArea()`（多屏/分辨率变化不丢窗）；`fitWindowToContent` 用 `screen.workAreaSize`；`src/app.js:212` `cellSizeFor()` 考虑 `screen.availWidth/availHeight`；视图菜单缩放/全屏 | CDP 实测：`window-state.json` 保存 `{x:314,y:99,w:760,h:640}`，重启恢复同 bounds 且不被自动回缩；全新 profile 首次启动按初级紧凑尺寸 350×402；托盘创建无错误日志（图标/菜单为代码审查） |
| **L11** LED 4 位/大棋盘 | ✅ 已修 | `src/ui-helpers.js:55` `formatCounter`（负数/4 位）；`src/ui.js` updateHUD 超 3 位加 `.led-wide`；`src/style.css` `.led-wide` 21px/字距 1px；aria-label 同步 | CDP 实测 50×50/2125：LED “2125”、`.led-wide=true`、宽 68.4px（未撑破 3 位 74px 布局）、aria “剩余雷数 2125” |
| **L12** 暂停/flag-win 采集 | ✅ 已修 | `src/app.js` suspension 计数（`pauseGame`/`resumeGame`/对话框，app.js:1053/1068）+ `pausedTotalMs` + `pausedMsNow()`；终局 meta 传 `pausedMs`/`wonByFlags`（`engine.wonByFlags`）；`src/ui.js` 含暂停局不参与“新纪录”判定/不播报 | CDP 探针实测暂停后获胜：meta `pausedMs=1460`，history `paused:true`，该桶 `bestTime` 未更新、无破纪录横幅；`wonByFlags` 由引擎单测覆盖、UI 原样透传（本轮未构造出正确的整盘 flag-win 局面） |
| **L13** 终局精度 | ✅ 已修 | `src/app.js:167` `finalizeTimer()` 先同步 `activeMs()`（毫秒）再 stop，再记账 | 同上 M7：`durationMs=74898` 与 history/统计口径一致；不再使用 250ms 轮询旧值 |
| **L15** 消费方 | ✅ 已修 | `src/ui.js` 邻域遍历优先 `Neighbors.forEachNeighbor`（无则本地兜底）；键位显示用 `KeyBindings.formatKey` 静态导出（回落全局 `formatKey`）；`getBindings()` 深拷贝由引擎侧保证 | 代码 + 交互实测（和弦/抖动/键位对话框正常）；`neighbors.js` 缺失时 UI 有回落 |
| **L17** 截图 | ✅ 已生成 | `docs/screenshots/desktop-light.png`、`docs/screenshots/desktop-dark.png`（高级局 16×30、中文明文、884×598@2x，含 LED/棋盘数字）。生成方式：Electron + CDP `Page.captureScreenshot`（详见下方实测记录） | 已用 `read_image` 目检两张图内容正确 |

## 新增文件

- `src/i18n.js`（UMD，392 行）：zh/en 词典（key 集合由单测保证一致）、插值/回退、偏好持久化、系统语言检测。
- `src/ui-helpers.js`（UMD，136 行）：`clampCursor/clampInt/formatDuration/formatDurationMs/formatCounter/formatLedSeconds/formatRate/formatPercent/DialogStack`，app/ui 共用、node:test 可直接测。
- `test/ui.test.js`（19 项）：光标夹取（H1）、时间/LED/速率格式（M11/L11）、DialogStack（M9）、I18N 词典一致性与插值（M20）、index.html 脚本顺序/CSP/棋盘 aria 契约。
- `docs/screenshots/desktop-{light,dark}.png`。

## 测试与实测记录

- `npm test`：**148/148 通过**（含新增 `test/ui.test.js` 19 项；desktop-engine 129 项同轮落地后全绿）。
- `npm run lint`（ESLint）：**0 error / 0 warning**（修复了 1 处 `UIHelpers` 未定义告警：改用 `globalThis.UIHelpers`）。
- `node --check`：main/preload/app/ui/i18n/ui-helpers/audio 全部通过。
- Electron CDP 实测（`env -u ELECTRON_RUN_AS_NODE npx electron . --remote-debugging-port=9223 --inspect=9229 --user-data-dir=/tmp/ms-fix-profile`，主进程+渲染进程双调试，探针脚本 `/tmp/cdp.mjs`）：
  - H1：expert cursor row12 → beginner → 方向键 clamp 生效、F2 弹确认。
  - H2：flag-first 后 async 生成 49.3ms 完成、翻 64 格、旗保留。
  - M7/L12/L13：胜局 meta `{durationMs:74898,pausedMs:1460,wonByFlags:false}`、HUD 074、history `{time:74,durationMs:74898,paused:true}`、桶 bestTime 未更新。
  - M9：AX `role=dialog`、背景 inert、焦点 `stats-close`、Tab 陷阱、输入框 Esc、键位录制清理、350×300 sticky 关闭按钮、嵌套对话框先关旧层。
  - M10：菜单单选（含 separator 拆组根因修复）、取消回写。
  - M11：`Total play time: 1:02:05 / 00:09 / 01:05 / 02:05`。
  - M13：50×50 光标 scrollIntoView（rect.top 811 < 832、scrollTop 150）、窗口自动缩/放/持久化/手动 resize 锁。
  - M15：blur 取消生成（0 格翻开、计时 000）；focus 后重试成功。
  - M20：中英切换（DOM+菜单+持久化+AX aria 同步）。
  - L2：非法 IPC 参数/伪造菜单参数被拒。
  - L10：`window-state.json` 保存/恢复；全新 profile 默认 350×402。
  - L11：LED 2125 + `.led-wide` 68.4px。
  - 加载后控制台**零 error/warning**（`Runtime.exceptionThrown`/`Log.entryAdded` 采集）。

## 未完成 / 已知限制

- 主进程 worker **2.5s 超时**、`render-process-gone`/`unresponsive` 对话框未做运行时触发验证（构造成本高），按代码审查 + 错误路径静态检查记录。
- 菜单 role（zoom/fullscreen/edit）的**原生快捷键**无法用 CDP 模拟真实菜单点击验证（`zoomIn.click()` 不改变 zoomFactor），依赖 Electron role 标准行为；已实测菜单结构存在。
- `wonByFlags` 的运行时端到端未命中（需要整盘正确插旗的确定局面）；引擎单测覆盖该规则，UI 侧原样透传 `engine.wonByFlags`。
- 语言切换时已打开的对话框会被关闭（统一层行为），不在原地重绘；属于可接受取舍，已在 i18n 实现中注明。
- 截图使用本机深/浅主题与高级局；未包含对话框/多语言版本（README 引用两张主图即可）。
