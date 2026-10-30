# 桌面 UI / 壳层 / 安全 / 打包审查

- 日期：2026-10-27 · HEAD `9c92fda`（工作树干净）· 范围：`src/app.js`、`src/ui.js`、`src/audio.js`、`src/index.html`、`src/style.css`、`main.js`、`preload.js`、`package.json`、`.github/workflows/build.yml`、`icon.png` 相关打包配置；键盘/键位存档等输入层从 UI 调用方式角度复核。
- 方法：逐文件通读 + Electron 33.4.11 实机（macOS arm64，`--remote-debugging-port=9223` 渲染进程 CDP + `--inspect=9229` 主进程 CDP，独立 `--user-data-dir`）实测；`electron-builder --dir` 打包产物内实测 worker/IPC；探针脚本在 `/tmp/cdp.mjs`，截图 `/tmp/50x50-180.png`。引擎/统计/发布链路与 `docs/review-2026-10-27/desktop-engine.md`、`product-and-release.md` 相互引用，不重复报。
- 上一轮 `docs/audit-2026-10-12.md` 已修的条目只做“已验证修复”标注，不重复计入发现。

## 结论摘要（≤10 行）

- **0 个 P0**；桌面 UI 有 **1 个 P1**（切到更小棋盘后键盘控制被永久锁死），**11 个 P2**、**8 个 P3**。
- 主路径质量不错：dirty 增量渲染 2500 格全量重建实测 ≈10ms；无猜异步生成在**打包产物**内实测可用（worker 72ms 返回、50×50 异步降级 495ms 不冻结）；暂停/失焦/主题跟随系统实测正确。
- 最值得先修的三类：① 键盘光标状态机（越界/不可见/绑定存档损坏）；② 原生菜单 radio 勾选同步被实测证伪（启动时主题勾错、点难度按钮后多选）；③ 对话框边界（焦点陷阱、Esc 在输入框内失效、小窗口溢出、嵌套遮罩）。
- 打包层面：`asarUnpack` 4 个 worker 依赖实测路径正确、打包后 IPC 正常；electron 33 已 EOL、未签名、tag/版本漂移等发布链路问题由 `product-and-release.md` 覆盖，本报告只补桌面壳层独有项。
- 渲染性能、HiDPI、CSP/隔离基线、audio.js 功能逻辑：**无问题**（见“已核实项”）。

## 发现清单（P0→P3）

### [DU-1] 键盘光标越界后所有键盘操作静默失效（P1）

- 类型：缺陷 / UX / 无障碍
- 证据：
  - `src/app.js:513-514`：`const { row, col } = ui.cursor; if (row < 0 || row >= game.rows || col < 0 || col >= game.cols) return` —— 该守卫在任何按键动作解析（`bindings.getActionForKey`）**之前** return。
  - `src/app.js:83-98`：`startGame()` 只 `ui.render()`，从不重置 `ui.cursor`；`src/ui.js:236-239` `hideCursor()` 也只清 class、不改 `cursor` 坐标。
  - 实测（dev 实例）：高级局方向键移到第 11 行 → 点“初级”（9 行）→ 传送 `ArrowDown/ArrowRight/F2`：无 `.cell.cursor`、无重开确认框，键盘全灭；切回高级局立即恢复（`ui.cursor={12,0}` 重新可见）。
- 影响：纯键盘玩家从大棋盘切到任何更小棋盘（含自定义→初级）后，移动/翻开/标旗/F2 全部失效，且无任何提示；只能靠鼠标点一次再……但鼠标点击也不会重置 `ui.cursor`，只有靠升级到更大棋盘才恢复。旧审查未覆盖此路径。
- 建议：`startGame()` / `initCustom` 成功后重置 `ui.cursor` 到合法范围（如保持行列各自 clamp，越界则归零）并在 `render()` 里 `highlightCursor()`；`keydown` 的方向移动键不依赖光标越界守卫（或守卫时把光标 clamp 回棋盘而不是 return）。
- 工作量：S

### [DU-2] 原生菜单难度/主题 radio 勾选状态被程序化同步破坏（macOS 实测多选/错选）（P2）

- 类型：缺陷 / UX / 平台
- 证据：
  - `main.js:91-102`：`updateMenuDifficulty`/`updateMenuTheme` 对**所有** radio 项执行 `item.checked = …`（包括把非目标项设为 `false`）；`src/app.js:96`（每次 `startGame` 后）、`src/app.js:642-645`（初始化/主题变更）触发。
  - 实测（主进程 CDP 直读 `Menu.getApplicationMenu()`）：
    - 启动：`data-theme="dark"`，但“主题”菜单勾选的是 **macOS 深色**（最后一项），其余全 false。
    - 点“中级”按钮后（游戏中已切到中级 16×16）：菜单“难度”同时出现 **高级=true、自定义=true**，初级/中级=false。
    - 对照实验：对同一 radio 组只执行 `item.checked = true` 能正确单选；执行 `item.checked = false` 会**反向选中该项**（Electron 33.4.11 / macOS 的 radio setter 行为，已在隔离 `Menu.buildFromTemplate` 菜单上复现）。
- 影响：菜单勾选项与实际难度/主题不一致（用户看到的当前难度是错的），且“自定义”与“高级”同时选中属明显的 UI 错误。Windows/Linux 上未实测，但当前写法依赖“赋 false 即取消勾选”的平台语义，跨平台同样脆弱。
- 建议：只对目标项赋 `true`（实测可靠地反选同组其它项），其余项不赋值；或用 `menu.getMenuItemById(...)` + 重建菜单；并补一个主进程单测（可用 `Menu.buildFromTemplate` 断言 checked 状态）。同时给 IPC handler 加白名单校验。
- 工作量：S

### [DU-3] 菜单动作被“取消”后不回写菜单状态（难度/自定义）（P2，静态推断）

- 类型：缺陷 / UX
- 证据：`main.js:46-57` 的难度 radio/“自定义...” click 直接 `sendToRenderer`；`src/app.js:600-611` 收到后走 `requestRestart`（`app.js:151-157`），用户点“取消”路径上没有任何 `updateMenuDifficulty(game.currentLevel)` 回写；`app.js:215` 自定义对话框“取消”同样不回写。`startGame` 只在真正开局后（`app.js:96`）才同步。
- 影响：对局中从菜单选新难度（或自定义）→ 确认框点“取消”→ 原生菜单仍是新难度/自定义的勾选（radio 被点击时由 OS 自动选中），与实际棋盘不一致；叠加 DU-2 后菜单状态更乱。
- 建议：所有会改变菜单勾选但可能被取消的入口，在取消/关闭回调里统一 `electronAPI.updateMenuDifficulty(game.currentLevel)`；或把菜单改成“先发请求、由渲染层确认后再回写菜单”。
- 工作量：S
- 备注：无法用 CDP 触发真实原生菜单点击，因此“原生点击会自动 checked”这一点是 Electron/macOS 常规行为推断，未在本轮实测（unverified）；DU-2 部分为实测。

### [DU-4] 自定义雷区“确定”绕过重开确认，直接丢弃进行中对局（P2）

- 类型：缺陷 / UX / 一致性
- 证据：`src/app.js:186-213`：自定义对话框“确定”直接 `close(); ui.cancelGeneration(); game.initCustom(...); resetTimerClock(); ui.render()`；对比难度按钮/表情/F2 都走 `requestRestart`（`app.js:418-428`、`547`、菜单动作 `604/607`）。
- 影响：对局进行中打开“自定义...”并填写确定，会**无确认地丢掉当前局**（标题栏/表情瞬间重置），与其它重开入口的保护不一致；用户容易误操作。
- 建议：把自定义确定也接到 `requestRestart(() => startCustomGame(rows, cols, mines))`；顺带把 `app.js:186-213` 与 `startGame()` 中重复的 5 步开局流程抽成 `startCustomGame()` 一处（见 DU-20）。
- 工作量：S

### [DU-5] 统计/历史时间格式化错误：秒补 3 位，显示成“2:005”（P2）

- 类型：缺陷 / 数据展示
- 证据：`src/app.js:221-222`：`const fmt = (n) => String(n).padStart(3, '0')`；`fmtTime = (t) => … + ':' + fmt(t % 60)`（`app.js:232`、`233-237`、`242` 全部复用）。实测（注入 `totalPlayTime=3725`、`bestTime={9,65,125}` 后打开统计）：显示 `总游戏时间: 62:005`、`初级: 0:009`、`中级: 1:005`、`高级: 2:005`。
- 影响：统计/历史里所有“分:秒”时间都错，秒位变成 3 位；用户无法正确比较成绩，也与 3BV/s 等数据并排时更显错乱。
- 建议：`fmtTime` 内的秒用 `String(t % 60).padStart(2, '0')`，分钟单独补 2 位（如 `05:09`）；总时长超过 59 分钟可显示 `h:mm:ss`。抽成纯函数并补单测。
- 工作量：S

### [DU-6] 自定义对话框中输入框内按 Esc 无法关闭（P2）

- 类型：缺陷 / UX / 无障碍
- 证据：`src/app.js:468-470`：`const isTyping = ['INPUT','TEXTAREA'].includes(e.target.tagName); if (isTyping) return` 位于 Esc 处理（`503-507`）之前；自定义对话框打开后 `app.js:216` 自动聚焦 `#custom-rows`。
- 实测：通过菜单打开“自定义” → 焦点在 `#custom-rows` → 发送 Esc → 对话框仍在；焦点在按钮/统计框时 Esc 正常关闭。
- 影响：自定义对话框（唯一带输入框的对话框）无法用 Esc 取消，必须点“取消”或遮罩；与其他对话框 Esc 行为不一致，键盘用户容易卡住。
- 建议：Esc 处理提前到 `isTyping` 之前（在输入框里 Esc 也应关闭对话框）；或对 `keydown` 单独在捕获阶段处理 Esc。顺带支持输入框内 Enter=确定。
- 工作量：S

### [DU-7] 快捷键录制未收尾：关闭对话框后下一次按键被吞并静默改键（P2）

- 类型：缺陷 / UX
- 证据：`src/app.js:404-411` 点击键位行后置 `listeningForBind = action`；`showDialog` 的关闭逻辑 `app.js:114-118`（含 Esc、遮罩点击、“关闭”按钮）**不清理** `listeningForBind`；`app.js:472-501` 中 `if (listeningForBind)` 分支 `preventDefault()` 并 `bindings.setKey(...)`（无对应 UI，因为对话框已被移除）。
- 实测：菜单打开“键盘快捷键”→ 点第一行（上移）→ 点“关闭”→ 对话框消失 → 按 `z` → `localStorage.minesweeper_bindings` 变为 `"moveUp":{"key":"z"}`（无任何 UI 提示），按键被吞。
- 影响：用户以为只是关闭了对话框，实际下一次按键会静默改掉任意一个键位（并吞掉该次按键）；改错的绑定只能再进对话框发现。ESC 取消路径是好的（`app.js:474-481`），其它关闭路径遗漏。
- 建议：把 `listeningForBind` 与“监听中按钮”的清理封装为 `cancelBindListening()`，在 `showDialog` 的 `close()`（或 overlay `_close`）里统一调用。
- 工作量：S

### [DU-8] 对话框缺 role=dialog/aria-modal/焦点陷阱；打开无焦点，Tab 可逃到背景（P2）

- 类型：无障碍 / 缺陷
- 证据：
  - `src/app.js:102-125` `showDialog()`：只建 `div.dialog-overlay > div.dialog`，无 `role="dialog"`、`aria-modal`、`aria-labelledby`；`close()` 不恢复焦点。
  - 统计/历史/关于对话框没有自动聚焦（`app.js:248-249`、`347-348`、`373-374` 只有关闭按钮事件，无 focus）；只有确认/自定义/键位三个对话框有 `setTimeout(...focus(),50)`。
  - 实测（统计对话框打开后）：`document.activeElement === BODY`；按 Tab → `#face-button`（背景），再 Tab → `.diff-btn`（背景），焦点离开对话框；AX 树中对话框没有任何 dialog/heading 级角色关联（`Accessibility.getFullAXTree` 只看到 4 个按钮 + grid + 静态文本，无 dialog 节点），而 `#pause-overlay` 的“⏸ 已暂停”在隐藏状态下仍暴露为 StaticText（`opacity:0` 不隐藏 a11y 树）。
  - 无 `aria-live` 播报计时/胜负/新纪录；`role=grid` 下的 gridcell 没有 row 包裹（AX 树为 grid → 81×gridcell 直连），棋盘也不可聚焦、无 `aria-activedescendant`（键盘光标对读屏不可见）。
  - 全局无 `:focus-visible` 样式；`src/style.css:473` 对输入框 `outline: none`，输入框获得焦点时无可见指示；也无 `prefers-reduced-motion` 分支（`style.css:260/264/295/536` 四处 animation 在“减少动态效果”系统设置下仍全速播放，同 `product-and-release.md` PF-18 范围）。
- 影响：键盘 + 读屏用户无法感知/安全操作对话框；Tab 可把焦点送到背景的难度/表情按钮后回车，等于在对话框背后触发重开/切难度；键盘光标不可访问。
- 建议：`showDialog` 统一加 `role="dialog" aria-modal="true"` + 标题 `id`/`aria-labelledby`；保存并在关闭时恢复 `activeElement`；实现首尾焦点循环（或 `inert` 背景）；`#board` 加 `tabindex=0` + `aria-activedescendant` 指向光标格、`aria-rowcount/colcount`；`#pause-overlay` 隐藏时加 `aria-hidden`，显示时 `aria-live=polite`；补 `:focus-visible` 与输入框 focus 样式。
- 工作量：M

### [DU-9] 最小窗口尺寸下统计对话框溢出视口且关闭按钮不可达（P2）

- 类型：缺陷 / 布局
- 证据：`src/style.css:398-416`：`.dialog-overlay` 是 `flex center` 且无 `overflow`；`.dialog` 无 `max-height`/`overflow`；`body{overflow:hidden}`（`style.css:113`）；窗口允许缩到 `main.js:116-117` 的 `minHeight: 300`。
- 实测（CDP `Emulation.setDeviceMetricsOverride(350,300)` 后打开统计框）：对话框高 **488.2px**、`top: -94.1px`、`bottom: 394.1px`（视口 350×300）；关闭按钮 rect `top 344.6 / bottom 375.1` → **完全在视口外**，无法点击/滚动到。
- 影响：小窗口或 1366×768+缩放下，统计（以及键位/历史等较高对话框）内容被裁掉、关闭按钮不可达；只能靠 Esc/点遮罩，读屏用户更糟。
- 建议：`.dialog { max-height: calc(100vh - 24px); overflow: auto }`（或 overlay 允许滚动 `overflow:auto; align-items:flex-start`）；同时限制最小窗口下对话框宽度 `max-width: calc(100vw - 24px)`。
- 工作量：S

### [DU-10] 手动缩小窗口后棋盘左侧被裁掉且无法滚动到（P2）

- 类型：缺陷 / 布局
- 证据：`src/style.css:116-130`：`#app` 同时 `display:inline-flex; flex-direction:column; align-items:center; overflow:auto`；子项 `#board` 比 `#app` 宽时，flex 居中使其向**两侧**溢出，左侧出现负坐标（不可滚动，`scrollLeft` 不能为负），而窗口可被用户缩到 `minWidth:350`（`main.js:116`）。
- 实测（高级局，主进程 `win.setSize(600,680)`）：`board` rect `left=-122, right=722, w=844`，`app.scrollLeft=0`，`app.scrollWidth=729` → 最左约 4 列不可见且鼠标无法滚动到。
- 影响：窗口被缩小（或小屏幕上 fit-window 无法放大到棋盘宽度）时，棋盘左半部分不可操作；用户只能手动拉大窗口或切难度。
- 建议：`#app` 改为 `align-items: flex-start`，用 `#board { margin: 0 auto }` 居中；或把棋盘放进独立滚动容器（`overflow:auto` 的 wrapper + 内部 `width:max-content; margin:0 auto`），并加 `resize` 监听重算 `--cell-size`/fitWindow。
- 工作量：S

### [DU-11] 大棋盘键盘光标无 scrollIntoView，移出视口后不可见（P2）

- 类型：无障碍 / UX
- 证据：`src/app.js:455-458`（方向键 → `ui.setCursor`）与 `src/ui.js:221-229`（只改 class，无滚动）；`#app` 是 `overflow:auto`（`style.css:129`），50×50 时内容高 ≈988px > 可视 825px。
- 实测（50×50 自定义，窗口 944×859）：连续下移 49 步后 `cursor` 行 49、`getBoundingClientRect().top=961 > innerHeight=831`，`#app.scrollTop` 仍为 0 → 光标完全不可见，继续按键只能“盲走”。
- 影响：键盘玩家在 30×30 以上棋盘无法看到自己移到哪；与 DU-1 叠加时体验更差。
- 建议：`setCursor` 里对目标格 `scrollIntoView({ block:'nearest', inline:'nearest' })`（或用 `#app.scrollTop/scrollLeft` 精确滚动，避免整页滚动）；大棋盘考虑缩略图/坐标提示。
- 工作量：S

### [DU-12] 键盘绑定存档缺类型校验的端到端影响：键盘静默失效（P2，交叉引用 DE-4）

- 类型：缺陷 / 健壮性
- 证据：`src/keybindings.js:41-51` `load()` 直接 `bindings[action].key = data.key`，不校验 `typeof string`；`src/keybindings.js:84-89` `getActionForKey` 对 `data.key` 调 `.toLowerCase()`。主报告 `desktop-engine.md` DE-4 已列该根因；本轮补充 UI 侧端到端实测：
  - 写入 `minesweeper_bindings={"moveUp":{"key":123}}` → 主进程 `webContents.reload()` → 按 `ArrowDown`：无光标，键盘全部失效；`new KeyBindings().getActionForKey('ArrowDown')` 抛 `TypeError: data.key.toLowerCase is not a function`（`null` 同样）。
  - 异常发生在 `document` keydown 监听内，界面无任何报错/提示，鼠标仍可玩。
- 影响：任何导致存档中 key 为数字/null 的情况（手改、旧版本数据、同步工具）都会让键盘静默失效，用户无从排查。
- 建议：见 DE-4（`load()` 里做 `typeof key === 'string' && key.length` 校验，非法项回落默认值并可选重写存档）；UI 侧可加“键位存档已修复”提示。
- 工作量：S

### [DU-13] 对话框可叠加：Escape 关最旧而非最顶层；嵌套重开会让计时器虚增（P3）

- 类型：缺陷 / 计时正确性
- 证据：
  - `src/app.js:102-125` 无“已有对话框”守卫，任何入口都能再叠一层；`src/app.js:503-506` Escape 用 `document.querySelector('.dialog-overlay')`（DOM 顺序 = 最旧的那个）。
  - `showDialog` 捕获 `shouldResume`（`app.js:105`），`close()` 只在“无其它 overlay”时 `startTimer()`（`app.js:117`），但不校验当前 `game.state`。
  - 实测 A：对局中菜单开“游戏统计”→ 菜单“新游戏”弹确认（两层遮罩）→ 按 Esc：**统计框被关**、顶层“重新开始？”仍在。
  - 实测 B：统计框开着时经菜单“新游戏”确认重开（棋盘变新局、计时 000）→ 关统计框 → 等 3s（显示仍 000）→ 点一格开局 1.5s 后计时显示 **004**（应为 001）——旧对话框的 `shouldResume` 在 `ready` 态调了 `startTimer()`，锚点提前 3s。
- 影响：Esc 关错层、遮罩叠加；嵌套重开会把上一局对话框的暂停逻辑带到新局，导致新局计时多算（直接影响最快时间/3BV/s 记录），且 `startTimer` 在 `ready` 态建立锚点属状态机漏洞。
- 建议：`showDialog` 打开时先关闭已有 overlay（或维护 overlay 栈，Esc 只关栈顶）；`close()` 恢复计时前判断 `game.state === 'playing'`；`startGame`/`initCustom` 时统一关闭所有对话框并作废旧 `shouldResume`。
- 工作量：S

### [DU-14] 计时显示/统计记录封顶 999 秒（P3）

- 类型：缺陷 / 数据正确性
- 证据：`src/app.js:25`：`const t = Math.min(999, Math.floor((timerBaseMs + Date.now() - timerAnchorMs) / 1000))`，随后 `game.timer = t`（`app.js:27`）；`src/ui.js:96/100` 胜利/失败统计直接用 `engine.timer`，`stats.onGameWin(time, …)` 也因此永远收到 ≤999 的值。
- 影响：超过 16:39 的对局，HUD 定格 999（预期内），但**统计/历史也记成 999**（超大棋盘/自定义局可达）；`bestTime` 与 3BV/s 失真。终局时刻值与真实值最多差 250ms 的问题见 DE-11，本条是另一个“硬上限”。
- 建议：把显示值与统计值分离——HUD 仍显示 999（或改成 4 位/滚动），统计用未截断的秒数（`Math.floor(realMs/1000)`）；顺手在终局时 `syncTimer()` 一次再记账。
- 工作量：S

### [DU-15] 超大棋盘窗口/信息显示遗留问题：窗口高过工作区、LED 4 位撑宽、50×50 字号仍偏小（P3）

- 类型：UX / 布局
- 证据与实测（50×50/2125 自定义）：
  - 请求窗口高 `50*18+150=1050` → 被 `main.js:147` 的 `WINDOW.maxH=900`（`config.js:64`）截到 900，而本机工作区仅 **859px**，macOS 又把窗口压到 859（`win.getBounds()={944×859}`，`screen.workArea={1512×859}`）→ `#app` 内容高 988 vs 可视 825，底部约 163px 需滚动；窗口尺寸未按 `screen.getDisplayMatching(win.getBounds()).workAreaSize` 兜底。
  - 剩余雷数 2125 → LED 显示 4 位“2125”，`#mine-counter` 宽 **93.2px**（3 位时 74.4px），`min-width:60` 只能被撑宽（`style.css:149-160`），与固定 3 位数计时器不对称、破坏 XP 风格。
  - 50×50 已修复为 18px 格子/10.8px 字号（旧 15/7.5，已验证修复），但 10.8px 数字在 Retina 上仍偏小，且必须滚动才能看全（截图 `/tmp/50x50-180.png`）。
- 影响：小屏/大棋盘下窗口与内容不匹配；高雷数自定义局的 HUD 数字位数溢出预期设计；50×50 只是“可用”而非“好读”。
- 建议：`fit-window` 用 `screen.getDisplayMatching(...).workAreaSize` 作为上限；HUD 改为定宽 N 位 + 溢出时缩小字号/或最高 4 位自适应；大棋盘提供缩放级（见功能机会）。
- 工作量：S/M

### [DU-16] `fit-window` 只放大不缩小：换小棋盘窗口不回缩（P3，旧审查 D3 仍未变）

- 类型：UX / 设计取舍
- 证据：`main.js:139-149` 明确 `Math.max(w, …)`/`Math.max(h, …)`；`src/app.js:71-79` 只在 `startGame` 的 rAF 里调用。
- 实测：50×50 局后窗口 944×859 → 重开/切回初级局，窗口仍 944×859，而初级棋盘仅 256×256（`board` rect 256×256，`#app` 408.8×346 在 944×859 窗口中央）——大片空白。
- 影响：与 XP 原版“窗口随难度改变尺寸”的预期不符；大棋盘玩家切回初级后窗口巨大、布局松散。
- 建议：区分“用户手动尺寸”和“自动尺寸”：新局时允许回缩到 `min(推荐尺寸, 当前用户手动尺寸)`；或提供“适应窗口/固定窗口”设置并在 README 说明。
- 工作量：S

### [DU-17] 壳层失败路径缺失：加载失败/渲染进程崩溃/未响应/worker 无超时都没有用户可见处理（P3）

- 类型：健壮性
- 证据：`main.js:129` `mainWindow.loadFile(...)` 返回的 Promise 未接 `.catch`，加载失败就是空白窗口无提示；全文件无 `render-process-gone` / `unresponsive` / `child-process-gone` 监听；`generate-mines` 的 worker（`main.js:184-204`）只在 message/error/exit 时 settle，无超时，若 worker 卡死，渲染进程会一直停在“正在生成无猜雷局…”且 `ui.generating=true` 忽略棋盘操作（可用“新游戏”恢复，但用户不知道）；`app.whenReady().then(...)` 也无 catch。
- 影响：异常路径下用户面对静默空白/卡死，无法自行恢复也没有日志入口（本地应用无遥测，至少应写日志/弹对话框）。
- 建议：`loadFile().catch(err => dialog.showErrorBox(...))`；加 `render-process-gone`（提示并可选 reload）、`unresponsive`（提示）；worker 加 `setTimeout`（如 10s）超时 finish 并 `terminate()`；暴露“生成失败，已回退同步生成”的用户提示（现在静默回退，滑点会被当成卡顿）。
- 工作量：M

### [DU-18] Electron 安全加固缺口（基线正确，但可再收紧）（P3）

- 类型：安全
- 证据（基线已做对：`main.js:121-131` `sandbox:true`/`contextIsolation:true`/`nodeIntegration:false` + `setWindowOpenHandler deny` + `will-navigate preventDefault`；`src/index.html:6` CSP 无 `unsafe-inline`）：
  - CSP 缺 `object-src 'none'`、`base-uri 'none'`、`form-action 'none'`、`frame-src 'none'`（`index.html:6` 仅 default-src/script-src/style-src/img-src）。
  - 无 `session.setPermissionRequestHandler`（默认授权路径），本地内容风险低但属纵深防御缺口。
  - `main.js:171-182` 只校验整数与下界，`rows/cols/mines` **无上界**（渲染进程若被攻破，可要求 1e6×1e6 的棋盘让 worker OOM）；`ipcMain.on('update-menu-*')`（`main.js:151-154`）不校验 level/theme 取值，非法值会把菜单 radio 全部置为未选（与 DU-2 相互放大）。
  - 所有 IPC 均未校验 `event.senderFrame.url`/来源（单窗口 + 无远程内容下风险低）。
- 影响：当前无远程内容，实际可利用性低；但一旦将来加外链/更新/统计上报，这些是第一批需要补的边界。
- 建议：CSP 补四指令；加 `setPermissionRequestHandler((_, permission, cb) => cb(false))`；`generate-mines` 加 `rows/cols ≤ 100、mines ≤ rows*cols` 上界；`update-menu-*` 用白名单；IPC 加 sender 校验（local file URL）。
- 工作量：S

### [DU-19] macOS 平台菜单集成缺失：无 Edit/View/Window/App 菜单（P3）

- 类型：UX / 平台集成
- 证据：`main.js:20-89` 只建 4 个自定义菜单（游戏/难度/主题/帮助）；主进程 CDP 读取实际菜单结构确认：无 `role: editMenu`/`viewMenu`/`windowMenu`/`appMenu`。后果（Electron 平台语义，unverified 实测）：自定义对话框的 number input 里 Cmd+A/C/V/X/Z 不可用；Cmd+W 关窗、Cmd+M 最小化、Cmd+/- 缩放、Cmd+Alt+I DevTools 均无菜单项；菜单项都不带 accelerator 提示（键位可在键位对话框里改，但菜单不显示 F2 等）。另外主题 radio 的启动态问题见 DU-2。
- 影响：macOS 用户按系统习惯使用快捷键会“没反应”（尤其输入雷数时粘贴）；菜单看起来不像原生应用。
- 建议：菜单模板补齐 `role: 'appMenu'/'editMenu'/'viewMenu'/'windowMenu'`（保留自定义菜单），动态键位作为菜单 accelerator 展示（或至少加“快捷键...”入口说明）；中文 label/i18n 由 `product-and-release.md` P1-4 覆盖，此处不重复。
- 工作量：M

### [DU-20] 可维护性：开局流程重复、内部不可测、音频异常路径未捕获、preload 暴露未用 API（P3）

- 类型：可维护性 / 测试
- 证据：
  - `src/app.js:186-213` 自定义“确定”把 `startGame()`（`app.js:83-98`）里的 5 步流程逐字重复一遍（是 DU-4 的根因）；`app.js:4-11` 整个应用塞在一个 IIFE 里，`game/ui/stats/bindings` 与对话框函数均不可从外部访问 → `ui.js`/`app.js` 无法写单测（76 项测试只覆盖 config/game/generator/solver/stats/keybindings/movement；另见 `desktop-engine.md` DE-7 与 `product-and-release.md` P2-2）。
  - `preload.js:4` 暴露 `platform`，全仓无消费（`grep` 仅 preload）；`preload.js:9-11` `onMenuAction` 无移除/去重（单实例下无实际影响）。
  - `src/audio.js:7-14/16-29`：`new AudioContext` 与节点创建无 try/catch；`src/ui.js:333-336` 中 `eng.reveal()` → `audio.explosion()/click()` → `render()` 顺序，若音频抛异常，本次 `render()` 被跳过 → 棋盘显示停在操作前（静默不一致）。当前环境不会触发，属健壮性缺口。
  - `src/ui.js:30` `suppressClick` 只在棋盘容器的 click/contextmenu 里消费；按下后把鼠标拖出棋盘再松开（mouseup 不冒泡到容器）会残留 `pressed` 高亮/`chordPending`（`ui.js:353-384` 无 window 级兜底），下次棋盘内 mouseup 可能被误判为和弦收尾（静态推断，unverified）。
- 影响：新手/回归改动容易再次引入 DU-4 类分叉；UI/壳层零自动化回归网；异常路径下界面可能卡在旧状态。
- 建议：抽 `startGame(levelOrCustom)` 单一入口（含关对话框、重置光标、计时）；把 UI 状态机拆成可注入 DOM 的类或至少导出内部函数到测试可用的挂载点（`window.__test` 仅在开发模式）；audio 调用包 try/catch 并放在 `render()` 之后；鼠标手势加 `window` 级 `mouseup/blur` 清理。
- 工作量：M

## 已核实项（已修 / 无问题）

**上一轮已修，本轮实测/代码确认（不再计入发现）**

- **无猜生成移出 UI 线程（旧 P1-1）**：`main.js:167-205` worker + `src/ui.js:279-316` 异步首点；实测 dev 与**打包产物**（`/tmp/pack-test`）可用：专家局 `generateMines` IPC 72ms 返回 `{ok:true,noGuessFallback:false,mines:480}`；50×50/2125 首点异步路径 ~495ms 完成，期间出“正在生成…”提示、不冻结、随后正确显示 `#noguess-warning`（降级）。
- **桌面 50×50 可读性（旧 P1-5）**：实测 `--cell-size=18px`、字号 10.8px、边框 `clamp` 生效（旧 15/7.5），`#app` 可滚动兜底；仍偏小/需滚动的遗留见 DU-15。
- **无猜降级提示（旧 P1-7）**：`#noguess-warning` 实测显示/隐藏正确（`ui.js:478-484`）。
- **暂停遮罩 `position:fixed`（旧 §10）**：`style.css:341-359` 已 fixed，实测 50×50 滚动后仍覆盖视口。
- **单实例锁（旧 D2）**：`main.js:207-219` 已加；本轮未重复做双开实测（旧审查已验）。
- **fit-window IPC 有限性校验（旧 §10）**：`main.js:143` `Number.isFinite` 已加（但只放大不缩小见 DU-16）。
- **菜单不再依赖中文 label（旧 C8）**：`main.js:80-88` 按 `type` 抓取引用已确认（checked 赋值方式问题另见 DU-2）。
- **`showDialog` 关闭路径统一 + 暂停/恢复语义**：`app.js:102-125`；实测（非嵌套场景）对话框打开停表、关闭恢复、失焦遮罩 + 聚焦恢复均正确（`timer` 001→暂停 1.5s 不变→恢复后 002）。
- **主题跟随系统**：`app.js:640-654`；实测系统 dark 时 `data-theme=dark` 且无落盘；显式选主题后固定（菜单勾选显示 bug 属 DU-2）。
- **`app.js:157` 格式瑕疵**：已修复（现为正常多行）。

**确认无问题的模块/行为**

- **渲染性能（dirty 增量）**：`ui.js:121-151` 只在行列变化时重建、否则只刷 dirty；实测切 50×50 自定义（2500 格 DOM 全建 + 引擎重置）≈10ms，单格操作同步完成无掉帧；无需优化。
- **HiDPI**：DPR=2 下全部为 DOM/CSS + 内联 SVG，无 canvas 位图，无模糊/坐标错位问题（`devicePixelRatio=2`、`cell=18px` 实测）。
- **安全基线**：`contextIsolation/sandbox/nodeIntegration` + 双导航拦截 + CSP 均在（加固建议见 DU-18）；渲染进程无 Node 能力、preload 暴露面窄（7 个方法），无 XSS 面（对话框 `innerHTML` 只拼静态文案与数字，自定义输入仅 `Number()` 转换、错误用 `textContent`）。
- **`audio.js` 功能逻辑**：6 音效合成、音量包络、mute 开关、phaser 无异常；唯一问题是异常路径未捕获（DU-20）。
- **计时墙钟锚点实现**：`app.js:18-52` 无 `setInterval` 漂移，暂停累计语义正确（仅 999 上限见 DU-14、终局 250ms 精度见 DE-11）。
- **浏览器降级**：`window.electronAPI` 全路径有守卫（`app.js:71-72/367/600`、`ui.js:252-259`），纯浏览器打开不崩。
- **进程/端口清理**：本轮 dev 与打包实例均已 kill，无残留。

**打包（本报告实测补充，其余由 `product-and-release.md` 覆盖）**

- `package.json:29-34` `asarUnpack` 与 worker 实际依赖一致：`electron-builder --dir` 产物 `Contents/Resources/app.asar.unpacked/src/` 内确实有 `generator-worker.js`、`generator.js`、`solver.js`、`config.js` 四个文件；打包后 `generateMines` IPC 实测 `ok:true`（72ms），非法参数返回“非法生成参数”——**旧审查提出的“打包后 worker 不可用”风险确认已修复**。
- 打包日志：`author is missed in the package.json`、`skipped macOS application code signing`（未签名/未公证、electron 33.4.11 EOL、tag/版本漂移、中文产物名、缺 `publish` 配置等见 `product-and-release.md` P1-1/P1-2/P0-3，本报告不重复）；`icon.icns` 由根 `icon.png` 正确生成（与 Electron 默认图标不同，实测 md5 不同），非问题。
- 桌面 CI 独有的小缺口（P3，1 行）：`build.yml:27-37` 发布矩阵只有 `macos-latest/windows-latest/ubuntu-latest` 各一个 arch——macos-latest 为 arm64 → 无 Intel mac 产物；且只跑测试+打包，**没有对打包产物做冒烟**（如启动 `--dir` 产物 + 检查 worker/IPC），worker 路径回归只能在发布后暴露；建议加 `arch` 矩阵或 universal 构建 + 产物冒烟 job。

## 实测记录

**环境**：macOS arm64（workArea 1512×859，scaleFactor 2，DPR 2）；Node 26.8.2；Electron 33.4.11；`ELECTRON_RUN_AS_NODE=1` 环境中启动必须 `env -u ELECTRON_RUN_AS_NODE`（否则 electron 以纯 Node 运行、`require('electron').ipcMain` 为 undefined）。

**命令与结果**

1. `npm test` → **76 tests / pass 76 / fail 0**（与 Lead 描述一致）。
2. 启动 dev：`env -u ELECTRON_RUN_AS_NODE npx electron . --remote-debugging-port=9223 --inspect=9229 --user-data-dir=/tmp/ms-review-profile`；`/tmp/cdp.mjs` 连渲染进程(9223)/主进程(9229)做 eval、`Input.dispatchKeyEvent`、`Emulation.setDeviceMetricsOverride`、`Page.captureScreenshot`、`Accessibility.getFullAXTree`。
3. 布局/性能：初/中/高级 256/448/844px 棋盘；50×50 自定义（50×50/2125）初始化含 2500 格 DOM ≈10ms，`cell=18px`/`font=10.8px`，窗口 944×859（请求 1050 被 macOS 工作区压到 859），`#app` scrollHeight 988 vs clientHeight 825；LED “2125” 宽 93.2px。
4. 菜单：主进程读 `Menu.getApplicationMenu()`：启动主题实为 `dark` 但菜单勾选 “macOS 深色”；点“中级”后菜单“高级+自定义”同时 checked（DU-2）。隔离 `Menu.buildFromTemplate` 复现 `checked=false` 反而选中该 radio。
5. 交互/状态：`ESC` 在自定义输入框无效（DU-6）；键位对话框关闭后按 `z` 静默改 `moveUp`（DU-7）；`localStorage` 写入 numeric key 后 reload → 键盘全灭（DU-12）；Tab 从 body → `#face-button` → `.diff-btn`（DU-8）；统计框 350×300 下高 488.2px、关闭按钮 344.6–375.1（DU-9）；高级局窗口缩到 600 → board left=-122 且不可滚（DU-10）；50×50 光标移到 row 49 → rect.top 961 > innerH 831、scrollTop 0（DU-11）；统计对话框文本 “总游戏时间: 62:005 / 初级: 0:009”（DU-5）。
6. 暂停/对话框计时：playing 下 blur → 遮罩 visible、计时冻结 1.5s；focus → 遮罩消失、恢复；对话框打开停表、关闭恢复（正常路径）；嵌套“统计+确认重开”后计时 004（应 001，DU-13）。
7. 打包：`env -u ELECTRON_RUN_AS_NODE npx electron-builder --dir --config.directories.output=/tmp/pack-test` → exit 0；产物 `app.asar.unpacked/src/` 4 文件齐全；启动打包 app（`--remote-debugging-port=9224`）后 `window.electronAPI.generateMines({16,30,99,…})` → `{ok:true,noGuessFallback:false,mines:480,耗时72ms}`；非法参数 → `{ok:false,error:"非法生成参数"}`。日志有 `author is missed`、`skipped macOS application code signing`；`Contents/Resources/icon.icns` 与 Electron 默认图标 md5 不同（由 `icon.png` 生成）。
8. 截图：`/tmp/50x50-180.png`（50×50@18px、LED 2125、需纵向滚动；Retina 1888×1662）。所有 Electron 进程已 kill，`git status --short` 仅 `?? docs/review-2026-10-27/`（本报告）。

**未做/限制**：无法用 CDP 触发真实原生菜单点击（DU-3 的原生自动勾选为推断）；50×50 无猜降级耗时受已加载机器负载影响（495ms 为单次观测）；未做 17 分钟以上对局（DU-14 为代码断言）；未做 Windows/Linux 实测（菜单/窗口行为以 macOS 为准）。

## 功能机会（桌面 UX / 平台集成角度，3–6 条）

> 与 `product-and-release.md` 的 PF-x 清单互补：只列桌面壳层/交互向、能直接吃掉本报告 P2/P3 的项。

1. **棋盘缩放与“跟随光标”导航（吃掉 DU-10/11/15/16）**：`Ctrl+滚轮`/`Ctrl±` 调整 `--cell-size`（16/20/24/28 档并记忆），窗口 resize 时按可用工作区重算；键盘光标用 `scrollIntoView`，大棋盘提供 5×5 缩略图或行列坐标提示。工作量 M，ROI 最高（同时解决窗口不缩、裁剪、不可见三个问题）。
2. **对局自动保存/恢复 + 显式暂停（吃掉 DU-13 边缘与崩溃丢局）**：每步把 board/timer/noGuess/custom 配置写 localStorage（或 `userData` JSON），启动检测“继续上一局”；加空格/`P` 暂停与“暂停”菜单项，暂停时记录并计入统计标注。工作量 M。
3. **对话框/键盘体验统一层（吃掉 DU-6/7/8/9/13）**：抽 `openDialog({title, content, primary, onClose})` 统一 role/aria-modal/焦点陷阱/首尾循环/焦点恢复/Esc/遮罩关闭/小窗口滚动，键位录制带 5s 超时与取消回调。工作量 S/M，直接消除半数的 P2。
4. **桌面平台集成包（吃掉 DU-19、DU-15 的 HUD 问题）**：macOS 补 App/Edit/View/Window 标准菜单（中文 label + 动态键位 accelerator 提示）；系统托盘（新游戏/暂停/静音/退出）、全局快捷键（暂停/新游戏）、窗口尺寸/位置记忆、Dock 角标显示当前连胜或进行中局；HUD 支持 4 位 LED/自适应。工作量 M。
5. **成绩与数据可视化增强**：历史曲线加悬停 tooltip/坐标轴（现在无 x 轴语义）、成绩导出 CSV/分享成绩卡（与 `product-and-release.md` PF-11 呼应）；配合 DE-6/DE-13 的口径修复，在统计里标注“含暂停”“<1s/0s”局。工作量 S/M。
