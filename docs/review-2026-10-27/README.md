# 扫雷 · 全量代码审查总报告

> **修复状态（2026-10-27）**：本报告 §三 的 H1–H5 / M1–M23 / L1–L20 已全部处理并验收（46 项完整修复、2 项有理由部分修复：L4、L9），
> 详见 [FIXES.md](FIXES.md)，分领域过程记录见 [fixes/](fixes/)。§四 功能路线图为新功能，不在本次“修复问题”范围。

- 审查日期：2026-10-27
- 代码基线：`HEAD 9c92fda`（26 个提交，工作树干净；本轮只新增 `docs/review-2026-10-27/`，未改动任何源码）
- 范围：桌面 Electron 版（`main.js` / `preload.js` / `src/` 约 3.2k 行 + `test/` 855 行）+ 安卓 Kotlin 版（`android/core` + `android/app` 4.7k 行）+ 构建/CI/发布/文档
- 方法：5 个并行工作流逐文件通读 + 实测。桌面用 `node:test`、Node 探针、Electron 33 + CDP（渲染进程 9223 / 主进程 9229）与 `electron-builder --dir` 打包产物；安卓用仓库本地 Gradle 缓存在 `--offline` 下重编译复跑、JUnitCore 交叉验证、JVM 探针、`aapt`/`apksigner` 检查产物。所有临时脚本均在 `/tmp`。
- 分领域报告（本目录）：
  - [desktop-engine.md](desktop-engine.md) — 桌面前端引擎/算法（13 条）
  - [desktop-ui.md](desktop-ui.md) — 桌面 UI / Electron 壳层 / 安全 / 打包（20 条）
  - [android-core.md](android-core.md) — 安卓 core 引擎（13 条）
  - [android-app.md](android-app.md) — 安卓 app 层（16 条）
  - [product-and-release.md](product-and-release.md) — 产品功能路线图 + README/CI/发布/仓库卫生（功能 18 条 + 工程发现 25 条）
- 上一轮审查：[../audit-2026-10-12.md](../audit-2026-10-12.md)（基于 `461c395`）。本轮逐条复核其“已修项”，**全部确认已修复**，不重复计入问题。

---

## 一、一页结论

**总体：代码质量扎实，没有 P0（无稳定崩溃/死局/数据丢失）；双端引擎高度等价；短板集中在“交互细节”和“发布工程”。**

- 引擎正确性：桌面 76/76、安卓 core 69/69 单测全绿（本轮重编译复跑）；随机对局不变量、3BV 独立复算、并发生成、打包后 worker 均零错误。旧审查修复的输入校验/终局守卫/无猜后台化/触摸覆盖层/增量渲染全部在位。
- 最严重的两个可达缺陷都在“无猜首点”和“键盘光标”这两个入口上：
  1. **无猜模式先插旗/先标旗再首点，会绕过异步生成**，退回渲染进程/UI 线程同步求解，实测 50×50/500 冻结 **p50 376ms / max 841ms**（桌面；安卓同条件最长 ~0.8s 且无提示）。两端各改 1 行即可。
  2. **桌面键盘光标越界后所有键盘操作永久静默失效**：大棋盘玩过再切小棋盘，`ui.cursor` 仍是旧坐标，`keydown` 在动作解析前 `return`；鼠标也无法恢复，切回大棋盘才恢复。实测可稳定复现。
- 未验证但影响最大的安卓风险：`MainActivity.kt:88-91` 仍按 `device.isVirtual` 一刀切丢弃手柄事件。KPA 若如此上报，**唯一主输入会完全失效**，必须在真机确认后改掉。
- 交互层有一批“小而实”的 P2：统计页时间显示成 `2:005`、原生菜单 radio 勾选错乱、自定义输入框 Esc 无效、快捷键录制关闭后下一次按键静默改键、自定义“确定”绕过重开确认、50×50 键盘光标不滚动、安卓底栏难度字号 28px、安卓自定义 ◀▶ 触摸热区被整行抢走。
- 性能不是主要矛盾，但有明确 ROI 的算法优化：求解器每步全盘 `collect()` + 每约束 `new Set`，`16×30` 单次 ~0.9ms、`50×50` 单次 27–38ms（安卓 JVM 30.9ms），占无猜生成 95%+；大棋盘因此“必然降级”。优化后可直接提升无猜成功率，并为“提示”功能铺路。
- 发布工程是最大短板（若打算对外分发）：**无 LICENSE**、签名密钥+明文密码备份混在 `dist/`、桌面发布流程历史上从未经 CI 验证（无 `desktop-v*` tag）、版本号三处漂移、Android release job 的 `if` 疑似恒假（需验证）、Electron 33 已 EOL、macOS/Windows 产物未签名。
- 功能侧：双端已对齐“经典扫雷 + 无猜 + 3BV/统计”，真正缺的是**新产品层**。ROI 最高的顺序：种子化开局/重开同局 → 提示/安全点击（与求解器优化同源）→ 设置中心+显式暂停 → Web/PWA → 每日挑战/竞速模式。

**建议先做的 8 件事（约 1–2 天，均为 S/M 工作量）**

| # | 事项 | 涉及 | 量 |
|---|---|---|---|
| 1 | 修键盘光标越界：开局重置/夹取光标，或把越界守卫移到动作解析之后 | 桌面 `app.js`/`ui.js` | S |
| 2 | 无猜异步判定放宽为 `!minesPlaced`（先插旗再首点也走后台） | 桌面 `ui.js` + 安卓 `GameView.kt` | S |
| 3 | 菜单 radio 只对目标项赋 `checked=true`；取消/关闭时回写当前难度 | `main.js`/`app.js` | S |
| 4 | 统计时间格式修正（秒补 2 位）；自定义“确定”接重开确认；输入框 Esc 可关闭；关闭对话框清理键位录制 | `app.js` | S |
| 5 | 存档类型校验：键位 `key` 必须是字符串、统计数值/历史逐条校验 | `keybindings.js`/`stats.js` | S |
| 6 | 0 秒纪录守卫 + 终局补一次计时同步 + 统计不再用 999 上限值 | 双端 stats/计时 | S |
| 7 | 安卓底栏按钮字号 + 自定义 ◀▶ 命中优先级 | `GameView.kt` | S |
| 8 | 安卓 `isVirtual` 过滤改白名单/来源判断，并在 KPA 真机验证一次 | `MainActivity.kt` | S（+真机） |

---

## 二、验证基线（本轮实测）

### 2.1 测试与构建

| 项 | 结果 |
|---|---|
| 桌面单测 | `npm test` → **76/76 通过**，176–181ms（Node v26.8.2） |
| 安卓 core 单测 | `:core:test --rerun-tasks` → **69/69 通过**（28+9+9+9+14；4s），JUnitCore 直跑交叉验证 OK |
| 安卓构建 | `:app:assembleDebug` 成功（2,163,241B）；`:app:assembleRelease` 成功，R8 + `lintVitalRelease` 通过，**189,447B，V2 签名**（`CN=Saolei`），minSdk 26 / targetSdk 34 |
| 桌面打包 | `electron-builder --dir` 成功；`app.asar.unpacked/src/` 内 4 个 worker 依赖齐全；**打包产物内** `generateMines` IPC 72ms 正常返回；asarUnpack 路径替换确认有效 |
| 启动/渲染 | Electron CDP 实测：2500 格 DOM 全量重建 ≈10ms，单格增量无掉帧；HiDPI(DPR=2) 无模糊；CSP/隔离基线正确 |

### 2.2 无猜生成成功率（桌面，800ms 预算 + 600 次上限）

| 配置 | 成功率 | 平均尝试 | 平均耗时 |
|---|---|---|---|
| 9×9/10（12%） | 100% | 1.2 | 0.1ms |
| 16×16/40（16%） | 100% | 1.7 | 0.9ms |
| 16×30/99（21%） | 100% | 19.9 | 18ms |
| 30×30/300（33%） | **0/30** | 600（撞上限） | 29–54ms |
| 30×30/500（56%） | **0/30** | 600 | 29ms |
| 40×40/400（25%） | **0/20** | 平均 440 | **~803ms（撞满预算）** |
| 50×50/500（20%） | **15–18%（3/20 + 2/11）** | 12.6 | 84–355ms（抖动大） |
| 50×50/100（4%） | 100% | 1.0 | 6ms |

> 另测：安卓 JVM 侧 expert 无猜 100 次 **0 降级**（p50 22ms / p99 149ms）；50×50/500 在 JVM 上 5/5 降级（~123ms）。单盘“可解率”随密度骤降（安卓 JVM 探针：16×30 约 6.5%、50×50/500 约 2%；桌面探针 expert 2/50）——**大棋盘无猜失败是算法能力 + 预算共同决定的结构性结果**，不是 bug，但值得优化（见 §3.2）。

### 2.3 引擎与渲染热路径（50×50 规模，桌面 / 安卓 JVM）

| 操作 | 桌面 | 安卓 JVM |
|---|---|---|
| 首点洪泛（约 2463 格） | 0.68ms | 1.99ms（洪泛+3BV） |
| `compute3BV` | 0.97ms | 同上 |
| `markAllDirty`（2500 格） | 0.35ms（字符串键） | 56µs（2500 个 `Pair`） |
| 普通布雷（非无猜） | 0.056ms | <1ms |
| 求解器单次 solve | 16×30：0.87ms；50×50：27–38ms | 16×30：1.25ms；50×50：30.9ms |

### 2.4 旧审查修复项复核（全部 confirmed）

引擎输入校验 / 越界守卫 / 终局守卫、`floodFill` 死代码、无猜后台 worker + 打包路径、无猜降级 HUD 提示、50×50 可读性（18px/10.8px）、单实例锁、菜单按 type 引用、暂停遮罩 fixed、安卓静态层 `dirty` 增量重绘、动画截断、六覆盖层触摸、拖动重锚、多点触控、表情热区统一、键位录制时钟驱动、无猜降级提示、`Storage.remove`/`reset` 语义、`getHistory(limit≤0)`、`Synth` 参数校验、系统深浅色跟随、Android 版本号 tag 注入 —— 逐条在本轮复跑/实测中通过，详见各分报告“已核实项”。

---

## 三、优化项清单（合并去重）

> 严重度：**P1** = 可稳定触发且明显影响可用性/安全，建议本轮修；**P2** = 应修，影响体验/数据/维护；**P3** = 择机优化/加固。
> 工作量：S（≤0.5 天）、M（1–3 天）、L（>3 天）。证据行只给当前 `file:line` 起点，完整复现过程见对应分报告。
> 说明：代码层面无 P0；`product-and-release.md` 中标为 P0 的发布阻断项（LICENSE、上架材料、tag/版本体系）统一折叠进本节 **H5**，不重复计数。

### 3.1 P1（5 条）

| ID | 问题 | 证据 | 建议 | 量 |
|---|---|---|---|---|
| H1 | **键盘光标越界后键盘永久失效**：大棋盘玩过切小棋盘，`ui.cursor` 越界；`keydown` 在解析动作前 `return`，方向/F2/翻开/标旗全静默失效，鼠标也无法恢复（需切回更大棋盘） | `src/app.js:513-516`；`startGame` 不重置光标 `app.js:83-98`；实测（desktop-ui DU-1） | 开局 clamp/重置 `ui.cursor`；越界时 clamp 回棋盘而非 return；`render()` 里重新高亮 | S |
| H2 | **无猜“先插旗再首点”绕过异步生成**：`toggleFlag` 把 state 改成 playing 后，`canGenerateAsync()`/安卓异步分支要求 state=ready 不再成立 → 同步求解冻结 UI（桌面 p50 376ms / max 841ms；安卓 ≤800ms，无“生成中”提示） | 桌面 `src/ui.js:252-260` + `src/game.js:294-299`；安卓 `GameView.kt:586-591` + `GameEngine.kt:414`；DE-1/AC-1/AA-2 | 判定改为 `noGuess && !minesPlaced`（`applyPlanAndReveal` 已支持 playing 态）；给同步回退加小预算（如 50ms） | S |
| H3 | **KPA 手柄 `device.isVirtual` 一刀切过滤**（旧审查 P1-6 未修）：若掌机内置手柄上报为 virtual，方向/ABXY/Start/Select 全部被丢弃，只剩触摸与返回手势 | `android/app/.../MainActivity.kt:88-91`；读码 verified / 真机 unverified | 删除过滤，或改按 `SOURCE_GAMEPAD/JOYSTICK` + 键码白名单；加“从未收到物理手柄则不过滤”兜底；**必须真机验证** | S+真机 |
| H4 | **签名私钥 + 明文密码备份与安装包混放**：`dist/keystore-backup-v1.2.1.zip` 内含 `release.keystore`、含明文口令的 `keystore.properties`；`dist/` 虽 gitignore，但整目录分享/上传是常见操作，泄露即可被冒名发版 | `dist/keystore-backup-v1.2.1.zip`（3.7KB，3 个文件）；`android/keystore/` 同理（已 gitignore） | 备份移出仓库目录（密码管理器/加密盘）；`.gitignore` 增加 `dist/keystore*`、`*.keystore`、`*.jks`、`keystore.properties`；README 写明勿放 `dist/` | S |
| H5 | **发布准备缺失（对外分发前必须处理）**：① 无 `LICENSE`，`package.json` 无 `license/author`；② 双 tag 策略与历史冲突（历史 tag 全是 `v*`，桌面新流程要 `desktop-v*`，但仓库从未有 `desktop-v*` tag → 桌面发布流程从未经 CI 验证）；③ 版本号三处漂移（`package.json` 1.2.0 / 安卓 tag v1.2.1 / Gradle 默认 1.0.0）且 CI 不校验；④ Android release job 的 step `if` 引用**同 step 的 env**，按 GitHub Actions 语义疑似恒假（unverified，需一次 tag 实测）；⑤ macOS/Windows 产物未签名未公证 | `git tag -l`；`package.json:1-49`；`.github/workflows/build.yml:3-10,46-50`；`android.yml:61-66,81-86`；product P0-1/P0-3/P1-2/P1-3/AA-6 | 加 LICENSE（MIT/Apache-2.0/GPL 按需）；版本单一来源 + CI `check-version`；tag 语义定稿并跑一次验证；release job 加 `permissions: contents: write`、产物 checksum；README 标注“未签名” | M |

### 3.2 P2（23 条）

**正确性 / 数据 / 统计**

| ID | 问题 | 证据 | 建议 | 量 |
|---|---|---|---|---|
| M1 | 无猜策略在 `MIN_EXPECTED_ATTEMPTS=20` 阈值上抖动：同一 50×50/500 配置可能 45ms 就降级、也可能跑满 840ms；40×40/400 每局都白等 ~803ms；`config.js` 注释与实测相反（“32ms 会立即降级”，实际 800/32=25≥20 反而继续跑） | `src/generator.js:118-129`、`src/config.js:36-39`；DE-2 | 用确定性门控替代耗时外推：按尺寸/密度预判（>1600 格或密度>12% 只试 1–2 次）；成功优先（低密度给足预算）；修正注释；预算/尝试数参数化以便测试 | S |
| M2 | **求解器性能**：每步全盘 `collect()` + 每约束 `new Set`/排序，`16×30` ~0.9ms、`50×50` 27–38ms（安卓 30.9ms），占生成 95%+；直接压低大棋盘无猜成功率 | `src/solver.js:76-126`；`android/core/.../Solver.kt:83-134`；DE-3/AC-7 | ① 单点规则批量落地（一次 collect 应用全部结论）；② 约束/邻居增量维护；③ 位集替代 Set；④ 小规模边界枚举（提升可解率，兼做提示功能） | M |
| M3 | `MinePlan` 不携带几何/首点信息，`applyPlan(AndReveal)` 不校验：探针可把按 (4,4) 生成的计划应用到 (0,0) 且该格是雷 → 首点直接炸死、`首点 3×3 安全` 承诺被静默打破；畸形/短数组可越界崩溃 | `android/core/.../GameEngine.kt:17,251-267,355-360`；`src/game.js:132-159,241-251`；AC-2/DE-9 | Plan 带 `rows/cols/safeRow/safeCol`；apply 时校验几何、长度、雷数、安全区，不匹配返回 false 走既有回退；worker 回传同样校验 | S–M |
| M4 | `planMines` 输入校验缺口：`safeRow/safeCol` 越界会静默产出无安全区的雷局（普通模式）或 `ArrayIndexOutOfBoundsException`（无猜）；雷数超限抛 `IllegalStateException` 与 `initCustom` 的 `IllegalArgumentException` 契约不一；`rows*cols` Int 乘法溢出 → `NegativeArraySizeException` | `android/core/.../GameEngine.kt:98-100,44,214`；AC-3 | 入口统一 `require`（安全区坐标、雷数上限、`Long` 尺寸上限、最大格数）；异常类型统一；桌面 generator 同步补 | S |
| M5 | **存档类型损坏无防护**：键位存档 `key:123/null` → 每次 keydown 抛 TypeError，键盘静默全灭（端到端实测）；统计存档 `totalGames:"abc"` → 胜率 NaN、`history:[null]` → 效率曲线对话框抛错；`save()` 无 try/catch，Quota 异常会中断终局渲染 | `src/keybindings.js:41-51,84-89`；`src/stats.js:37-52,101-106,133-136`；DE-4/DE-5/DU-12 | `load()` 校验 `typeof key === 'string' && key.length`、数值 `Number.isFinite`、history 逐条校验并丢弃坏行；`save()` try/catch；补损坏类型单测 | S |
| M6 | 统计口径：history 是**全局** 50 条上限，换难度会把旧难度曲线挤掉（实测 30 初级+60 高级后初级曲线归零）；`custom` 所有盘面混用一个记录槽（9×9 与 50×50 的纪录互相污染）；`getHistory` 默认 20 条而存储 50 条 | `src/stats.js:70-90,133-136`；`src/config.js:45-46`；DE-6 | history 按难度**分桶**各 50 条；custom 桶 key 带盘面（`custom:16x16/40`）；默认 limit 与 UI 对齐；兼容迁移旧数组 | M |
| M7 | 计时/记录精度与上限：终局记账用的是 250ms 前轮询值（最多差 1s）；`bbbv/s` 用整秒；<1s 胜局写入 `bestTime=0` → 永远破不了的“0:00 纪录”（自定义 9×9/1 雷首点即胜实测 56%，真实可达）；>999s 长局统计也记 999 | 桌面 `src/app.js:23-30,55-58`、`src/stats.js:72-90`；安卓 `GameView.kt:281-285,541-549`；DE-11/AC-9/AA-8/DU-14 | 引擎/UI 增毫秒字段，终局补一次同步；`onGameWin` 用 ms 判定与算 3BV/s；`time<=0` 不更新最快时间；显示值与统计值分离 | M |
| M8 | 0 雷盘状态机漏洞：`checkWin()` 在 `ready` 即 true，插旗两次（旗→问号）未翻开任何格即判胜（两端同构；当前 UI 雷数下限 1，不可达） | `src/game.js:388-401`；`android/.../GameEngine.kt:506-518`；DE-8/AC-5 | 旗子判胜分支加 `totalMines > 0`；或 `!minesPlaced` 时 `checkWin` 直接 false；补回归测试 | S |

**桌面交互 / 无障碍**

| ID | 问题 | 证据 | 建议 | 量 |
|---|---|---|---|---|
| M9 | 对话框体系缺陷：无 `role=dialog/aria-modal/焦点陷阱`（打开无焦点，Tab 逃到背景按钮，实测 AX 树无 dialog 节点）；输入框内 Esc 不关闭（`isTyping` 早退在 Esc 处理前）；键位录制关闭对话框后未清理 → 下一次按键静默改键；小窗口统计框溢出、关闭按钮在视口外；对话框可叠加、Esc 关最旧层；嵌套重开会让旧 `shouldResume` 在新局 ready 态启动计时（实测新局 1.5s 显示 004） | `src/app.js:102-125,404-411,468-470,503-507`；`src/style.css:398-416`；DU-6/7/8/9/13 | 抽统一 `openDialog()`：role/aria-modal/labelledby、焦点陷阱与恢复、Esc 优先、小窗滚动、叠层栈；`close()` 恢复计时前判 `game.state==='playing'` | M |
| M10 | 原生菜单 radio 勾选被程序化同步破坏（macOS 实测）：对 radio 赋 `checked=false` 会反向勾选 → 启动主题勾成“macOS 深色”、点“中级”后“高级+自定义”双选；菜单动作被“取消”后不回写，菜单停留错误难度 | `main.js:91-102`；`src/app.js:96,642-645`；DU-2/DU-3 | 只对目标项赋 `true`（组内自动反选），其余不赋值；取消/关闭回调统一 `updateMenuDifficulty(currentLevel)`；补主进程菜单状态单测 | S |
| M11 | 统计/历史时间格式错误：秒被补成 3 位，实测显示“总游戏时间 62:005 / 初级 0:009 / 中级 1:005 / 高级 2:005” | `src/app.js:221-222,232-242`；DU-5 | 秒补 2 位、分补 2 位，总时长 >59min 用 `h:mm:ss`；抽纯函数单测 | S |
| M12 | 自定义雷区“确定”绕过重开确认，直接丢弃进行中对局（与难度按钮/F2/表情的保护不一致） | `src/app.js:186-213` vs `app.js:418-428,547`；DU-4 | 自定义确定也走 `requestRestart`；与 `startGame` 合并为单一开局入口 | S |
| M13 | 窗口/布局三连：手动缩小窗口后棋盘左侧被裁且滚不到（flex 居中 + 负坐标）；大棋盘键盘光标移出视口不滚动（50×50 实测 cursor top=961 > 视口 831）；`fit-window` 只放大不缩小、不按屏幕工作区兜底（50×50 请求 1050 被 macOS 压到 859） | `src/style.css:116-130`；`src/ui.js:221-229`；`main.js:139-149`；DU-10/11/15/16 | `#app` 改 `align-items:flex-start` + board `margin:auto`；`setCursor` 加 `scrollIntoView({block:'nearest'})`；fit-window 用 `workArea` 且允许回缩（或加“适应窗口”开关） | M |
| M14 | 无障碍：桌面对话框/网格/焦点问题见 M9，另无 `:focus-visible`、输入框 outline:none、无 `prefers-reduced-motion`（4 处 animation 照常播放）、HUD 无 aria-live、`role=grid` 下 gridcell 无 row 包裹、棋盘不可聚焦/无 `aria-activedescendant`；安卓零 `contentDescription`/`performClick`/虚拟节点，TalkBack 完全不可用 | `src/style.css:260,264,295,473,536`；`src/app.js:102-125`；`android/.../GameView.kt:2132`；DU-8/AA-9/product PF-18 | 桌面按 M9 统一修 + reduced-motion + focus-visible + HUD aria-live；安卓用 `AccessibilityDelegateCompat`/`ExploreByTouchHelper` 暴露虚拟节点与状态播报 | M |

**性能 / 平台 / 工程**

| ID | 问题 | 证据 | 建议 | 量 |
|---|---|---|---|---|
| M15 | 无猜生成生命周期缺口：worker/安卓后台线程无超时（卡死则 UI 永久“生成中”并忽略棋盘操作）；`cancelGeneration` 只作废结果不终止线程；生成在对话框/失焦期间完成会照常开场并启动计时 | `src/ui.js:279-316`；`main.js:184-204`；`src/app.js:102-125,566-577`；DE-10/DU-17 | 加 2–3s 超时回退；请求带 id 并 Map 管理 worker/可取消；showDialog/pause 时取消或延后落地 | M |
| M16 | 安卓 `dirty` 用 `Set<Pair<Int,Int>>`、`floodFill` 用 `ArrayDeque<Pair>`：50×50 `markAllDirty` 每次 2500 个装箱（56µs/次），首点/终局是分配峰值；且 `dirty` 公共可变，UI 直接 `clear()` | `android/core/.../GameEngine.kt:166,383`；`GameView.kt:439,523`；AC-6 | `dirty` 改 `MutableSet<Int>`（`r*cols+c`），`floodFill` 改 `ArrayDeque<Int>`；core 提供 `consumeDirty()` 收口 | S |
| M17 | `StatsManager` 多键非原子写：一次胜利最多 7 次独立 `apply()`，进程在两次写之间被杀会出现“总局数+1 但胜场未+1”式脏统计；`bestTime/bestBbbvPerSec` 是公开可变 Map | `android/core/.../StatsManager.kt:121-153,45-48`；`PrefsStorage.kt:9-15`；AC-8 | Storage 加批量写接口或整包单键（对齐桌面 JSON）；对外返回只读 Map；加统计难度集合一致性测试 | M |
| M18 | Electron 33.4.11 已 EOL（最新 45）、electron-builder 25→26；本地内容+沙箱下风险有限，但不再有安全补丁 | `package.json:45-48`；`npm outdated`；product P3 | 先补 e2e/打包冒烟，再升 1–2 个大版本；顺带 `engines`/`.nvmrc` 固定 Node | M |
| M19 | 安卓无状态保存：进程回收/字体/语言/外接键盘等配置变更直接丢局（`AndroidManifest.xml:12` 缺 `keyboard|density|fontScale|locale`；零 `onSaveInstanceState`）；丢局还污染胜率（已计总局无胜负） | `MainActivity.kt` 全文；`AndroidManifest.xml:12`；AA-5 | 轻量快照（关卡/雷位/翻开旗标/计时/noGuess）存 prefs/Bundle，`onCreate` 恢复；补 configChanges | M |
| M20 | i18n 架构缺失：README 以英文为默认，但桌面 UI/menu/config label 全中文，安卓 `strings.xml` 只有 1 条、`GameView` ~100 条硬编码；`String.format` 未指定 Locale（部分 locale `%.2f` 出逗号） | `src/index.html:2`；`main.js:20-89`；`config.js:14-25`；`android/.../GameView.kt:570,1954,2042,2074`；product P1-4/AA-13 | 桌面 `src/i18n.js`（UMD，主进程/渲染共用）+ 文案 key；安卓迁 `strings.xml`/`values-en`；数字格式化统一 `Locale.ROOT` | M |
| M21 | CI 质量门偏弱：PR 只跑 `npm test`，main 分支无 CI；无 lint/format/typecheck（无 ESLint/Prettier/ktlint/detekt）；安卓 `:app` 零测试、CI 不跑 `:app:lint`；桌面三平台打包只在 tag 验证且无产物冒烟 | `.github/workflows/build.yml:13-21`；`android.yml:25-36`；product P1-1/AC-13/DE-7 | PR 加 lint + `:app:lintDebug` + 桌面 `npm test`（已有）+ 打包冒烟；加 concurrency/cache/permissions；补 parity 常量测试 | S–M |
| M22 | 安卓底栏难度按钮漏设字号：继承 HUD 的 28f 画 30px 按钮（“自定义”宽 50.4px > 按钮 47.2px），只在无猜提示出现时才跳回 12f | `GameView.kt:1178-1181,1596-1623`；AA-3 | 循环前统一 `textPaint` 样式（14f），抽 `applyUiTextStyle()` 消除画笔残留依赖 | S |
| M23 | 安卓自定义雷区 ◀▶ 触摸热区被整行命中抢先：`overlayHitAt` 返回第一个包含点矩形，箭头矩形完全在 row 内 → 纯触摸玩家无法调数值 | `GameView.kt:1841-1848,753-758`；AA-4 | 子元素先登记（或命中时小面积优先/逆序）；箭头画成独立方块；注释写明优先级约定 | S |

### 3.3 P3（20 条，择机）

| ID | 问题 | 证据 | 量 |
|---|---|---|---|
| L1 | 壳层失败路径缺失：`loadFile()` 无 catch、无 `render-process-gone`/`unresponsive` 处理、`whenReady().then` 无 catch；worker 无超时（见 M15） | `main.js:129,184-204,223-226`（DU-17） | S–M |
| L2 | Electron 安全加固：CSP 缺 `object-src/base-uri/form-action/frame-src 'none'`；无 `setPermissionRequestHandler`；`generate-mines` 无 rows/cols 上界；`update-menu-*` 无白名单；IPC 无 sender 校验 | `src/index.html:6`；`main.js:151-154,171-182`（DU-18） | S |
| L3 | macOS 菜单集成缺失：无 App/Edit/View/Window 角色菜单 → 输入框 Cmd+A/C/V、Cmd+W/M、缩放/DevTools 均不可用；菜单项无 accelerator 提示 | `main.js:20-89`（DU-19） | M |
| L4 | 安卓 `GameView.kt` 2354 行 God Class（主题/绘制/7 覆盖层/输入/计时/统计/无猜线程全揽）+ app 模块零测试；死代码：`onStateChange` 0 引用、KeyCodes 5 常量 0 引用、`drawBindings` 无意义三元、`ACTION_OUTSIDE` 分支不生效 | `GameView.kt` 全文；`GameEngine.kt:168-184`；AA-15/AC-10 | L |
| L5 | 安卓长按已翻开数字格：误振动、吞掉松手和弦（短按行为不一致） | `GameView.kt:2122-2130,2317`（AA-7） | S |
| L6 | 安卓 About 页写死默认键位，改键后显示错误（应与底栏一样用 `bindings.getDisplayKey`） | `GameView.kt:2093-2100`（AA-11） | S |
| L7 | 音效：安卓冷启动 SoundPool 未加载完成前首次 play 静默、切后台不 autoPause、WAV 缓存文件名无版本（改参数后存量设备永不更新）；`Synth.noise` 缺指数包络，爆炸音后半段比桌面响约 10× | `SoundManager.kt:20-50`；`Synth.kt:36-48` vs `src/audio.js:31-48`（AA-12/AC-11） | S |
| L8 | 安卓常亮/insets：无条件 `FLAG_KEEP_SCREEN_ON`（菜单/暂停也亮）；沉浸式只在 onCreate/onResume，不处理 `onWindowFocusChanged`、刘海/挖孔 insets；未重写 `onDetachedFromWindow` | `MainActivity.kt:30,79-85,111-125`（AA-10） | S |
| L9 | 安卓图标/版本/工具链：单密度 `drawable/icon.png`、无自适应图标；本地构建默认 versionCode=1/versionName=1.0.0（覆盖安装会降级失败）；AGP 8.5.2/Kotlin 1.9.24/compileSdk 34 偏旧 | `app/build.gradle.kts:9-24`；`AndroidManifest.xml:5-6`；`libs.versions.toml:2-4`（AA-16/AC-13） | S–M |
| L10 | 桌面平台化缺口：无托盘/全局快捷键/窗口尺寸位置记忆/置顶/缩放/自动更新（`dist/latest-*.yml` 生成但无人消费）；macOS 未签名未公证、Windows 无签名 → 首次打开有 Gatekeeper/SmartScreen 摩擦 | `main.js:112-135`；`package.json:19-48`（product P1-2/PF-14） | M |
| L11 | 超大棋盘 HUD/窗口细节：剩余雷数 >999 时 LED 撑到 4 位（实测 93.2px vs 3 位 74.4px）；50×50 字号 10.8px 在 Retina 仍偏小且必须滚动 | `src/ui.js:465-472`；`src/style.css:149-160`（DU-15） | S |
| L12 | 统计诚信：对话框/失焦暂停不计时（可“暂停思考”）、插对全部旗即胜仍保留 → 最快时间/3BV/s 可比性被稀释 | `src/app.js:102-125`；`src/game.js:388-401`（DE-13） | 标注 S / 贯通 M |
| L13 | 桌面终局计时精度：250ms 轮询 + 终局不补同步，跨整秒边界最多差 1s（与 M7 同源，可一并修） | `src/app.js:23-30,55-58`（DE-11） | S |
| L14 | 安卓返回/退出：`onBackPressed` 已 deprecated、未接预测性返回；Back 恒被消费（游戏→菜单→关菜单），无“退出”入口 | `MainActivity.kt:103-108`（product P2/AA） | S |
| L15 | 可维护性：8 邻域遍历重复 5–6 处、UMD 与“CommonJS+全局类”三套导出、`formatKey` 隐式全局、`state/level` 裸字符串、`getBindings/getSummary` 浅拷贝暴露内部引用、`ui/app.js` 不可测 | `src/game.js`/`solver.js`/`generator.js`；`src/ui.js:91-93`；`src/stats.js:118-136`（DE-12/DU-20） | M |
| L16 | 跨端常量三份手工同步（难度/NO_GUESS/HISTORY_MAX/键重复节奏等）当前一致但无守护测试；无 lint/格式化配置；`src/*.js` 文件权限 600/644 不一致 | `src/config.js:13-46` vs `GameEngine.kt`/`StatsManager.kt`；DE-7/DE-12 | S–M |
| L17 | 文档准确性：README 10+ 处问题（Linux arm64 vs CI x64、无猜 “always solvable” 与降级自相矛盾、“Full keyboard control” 仅 7 个游戏动作、F2 归因错误、中英不一致等）；`docs/audit-2026-10-12.md` 版本过期且 §1/§11 自相矛盾；7 月设计文档已落后实现 | product R-1…R-15、P2-5 | S |
| L18 | 仓库卫生：`dist/` 273MB（10 个旧安装包 + 1.0.0 未签名 APK + keystore 备份）、`node_modules/` 539MB、4 处 `.DS_Store`；无 `.editorconfig`/`.nvmrc` | product P2-1 | S |
| L19 | 打包元数据：`appId: com.minesweeper.app` 过于通用、缺 `author/license/homepage/bugs/engines`、产物名含中文（`扫雷-1.2.0-…`）、无 Linux arch 声明；README 下载表版本状态不可核实 | `package.json:19-44`；product P2-4/P1-2 | S |
| L20 | 测试缺口：`applyPlanAndReveal` 失败分支/竞态、无猜降级路径（双端）、`ui.js`/`app.js`/`main.js` 零自动化、安卓子集规则无回归、`Synth` 零测试、`dirty` 内容准确性、`solver` 不改 board 的不变量 | DE-7/AC-12/AA-15 | M |

### 3.4 文档/README 准确性摘要（详见 product 报告 §一）

- 明确错误：Linux arm64（CI 在 x64 ubuntu 构建）、无猜 “always solvable”（同段又承认降级）、F2 归因（实为渲染进程可重绑键，菜单项无 accelerator）、“全键盘操作” 夸大（仅 7 个游戏动作可绑定）。
- 需补充：自定义棋盘 9–50 / 雷数 ≤85% 约束、桌面“暂停”仅为失焦自动暂停、非 ASCII 路径构建限制、未签名构建说明、数据存储位置与重置方法、截图/GIF。
- 中英两版结构漂移（18 行 vs 17 行）且示例版本号与实际版本不一致。
- **复核校正**：product 报告 R-5（“~170KB APK 与仓库 1.53MB 不符”）经复核部分不成立——本地 `:app:assembleRelease` 实测产物 **189,447B 且 V2 签名**，README “约 170KB” 基本准确；`dist/saolei-1.0.0-android-release.apk` 的 1.53MB/未签名是 **v1.0.0 旧产物**（归入 L18 仓库卫生，而非 README 错误）。

---

## 四、功能开发路线图

> 完整 18 条（PF-1…PF-18，含价值/实现要点/风险/依赖）见 [product-and-release.md](product-and-release.md) §三；引擎侧功能机会见 [desktop-engine.md](desktop-engine.md) 与 [android-core.md](android-core.md) 文末；桌面交互向见 [desktop-ui.md](desktop-ui.md) 文末。本节是合并去重后的 ROI 排序。

### 4.1 现状能力（双端已对齐）

三档难度 + 自定义（9–50，≤85% 雷）、首点 3×3 安全 + 空白洪泛、无猜模式（后台生成 + 降级提示）、和弦/问号三态、统计（总局/胜率/连胜/最快时间/3BV/s/最近 20 局曲线）、6 主题 + 跟随系统、6 合成音效、键位自定义、重开确认/错旗/新纪录横幅、暂停/失焦暂停、50×50 可读性、触摸（安卓全对话框）+ 键盘（桌面）。**引擎两端 8 组核心方法逐分支等价。**

### 4.2 路线图（按 ROI 排序）

**档位 A：快速见效（≤1 天/项，低风险）**

| # | 功能 | 价值 | 双端 | 量 | 关键实现点 |
|---|---|---|---|---|---|
| A1 | **种子化开局 + 重开同局 + 分享种子**（PF-1） | 每日挑战/回放/竞速的共同地基；可分享“这局我 12 秒” | 双端 | S | 桌面 `generator.generate()` 已支持注入 `random`，只需给引擎加 seed + PRNG、IPC 传 seed；安卓 `planMines` 加 `Random` 参数并让 `startAsyncFirstReveal` 传入 |
| A2 | **提示 / 安全点击 / 自动标雷**（PF-3） | 新手留存最大痛点；无猜局天然永远有提示；与 M2 求解器重构同源 | 双端 | S–M | 把 `solver.step()` 从“返回一条结论”改为“返回 move list”；提示次数/`assisted` 标记写统计，避免污染无辅助纪录 |
| A3 | **设置中心 + 显式暂停 + 动画/音量开关**（PF-5） | 收口散在菜单/键位对话框里的设置，解决“暂停可无限思考”的口径问题，为 i18n/无障碍提供入口 | 桌面优先 | S–M | 桌面复用 `showDialog`；安卓复用 `Overlay`；持久化键并入 `STORAGE_KEYS` |
| A4 | **棋盘缩放 + 跟随光标导航**（DU 功能 1） | 一次吃掉 M13 的窗口裁剪/不可见/不回缩；大棋盘真正可玩 | 桌面 | M | `Ctrl±`/`Ctrl+滚轮` 调 `--cell-size` 档位并记忆；`setCursor` 加 `scrollIntoView`；`fit-window` 按 `workArea` 重算 |
| A5 | **统计修复 + 按难度分桶历史 + 重置统计入口**（DE-6/DU-5） | 现有主打功能的正确性；修复成本低 | 双端 | S | 修 `fmtTime`；`history` 分桶；统计对话框加“清空统计（二次确认）” |

**档位 B：中期（2–5 天/项）**

| # | 功能 | 价值 | 双端 | 量 | 关键实现点 |
|---|---|---|---|---|---|
| B1 | **Web/PWA 版 + URL 分享**（PF-2） | 零安装、手机浏览器、链接传播；现有前端 Electron API 全部有守卫，改造成本远低于重写 | 桌面/Web | M | 复制 `src/` + `index.html` 出 `web/` 构建；manifest + sw 离线缓存；无猜改 Web Worker（复用生成算法）；GitHub Pages 部署 |
| B2 | **每日挑战（日期种子 + 连续天数 + 每日最佳）**（PF-7） | 低成本高留存，现代扫雷标配 | 双端 | S–M | 依赖 A1；`seed = hash(YYYY-MM-DD + difficulty)`；本地记录 `daily:{date,level,time,won,streak}`；不宣称竞技级 |
| B3 | **竞速模式：小数计时 + 点击数 + IOE/NF**（PF-4） | 把 3BV/s 升级为社区可比较指标；为回放/排行榜铺路 | 双端 | M | 内部记毫秒/点击计数（操作入口已集中）；stats schema 升版并兼容旧档；NF 开关；HUD 显示实时 3BV |
| B4 | **双语 UI / i18n 架构**（PF-6） | 英文 README 引流却打开全中文；上架国际商店前置 | 双端 | M–L | 桌面 `src/i18n.js`（UMD）+ 文案 key；安卓 `strings.xml`/`values-en`；长英文文案在 960×640 需实测 |
| B5 | **对局自动保存 / 崩溃恢复 / 显式暂停**（DE/AA 功能） | 直接消化 M15/M19 的丢局问题；桌面崩溃与掌机休眠场景价值高 | 双端 | M | 引擎 `serialize()/restore(snapshot)`；失焦/每步/定时写 localStorage/prefs；启动检测“继续上一局” |
| B6 | **成就系统（本地勋章 + 进度）**（PF-9） | 把现有统计转成长期目标（首胜、sub-10、无猜高级、50×50 通关、连胜 10…） | 双端 | M | 纯逻辑可单测；胜利/失败/新纪录事件后评估；复用 `record-banner` 样式 |
| B7 | **战绩/设置导出导入 + 成绩卡**（PF-11） | 换机/重装不丢；Web/桌面/安卓之间唯一可行的“同步”；社媒传播 | 双端 | M | 版本化 JSON schema + 导入校验/二次确认；桌面 `dialog.showSaveDialog`+IPC；安卓 SAF/FileProvider |
| B8 | **高级统计面板（趋势/分布）**（PF-10） | 现有统计只有汇总 + 一条曲线；玩家想看趋势/哪种盘最稳 | 双端 | M | history 增胜/负、ms、clicks、盘面；桌面现有 SVG 工具函数、安卓现有 `drawHistory` 原语 |
| B9 | **移动端手势增强 + 手机竖屏适配**（PF-12/13） | 若想上 Play 覆盖普通手机用户，竖屏是硬门槛；掌机手势也可提升 | 安卓 | M–L | 拖动连续翻开/双指标旗/触觉分级；Manifest 改 `fullSensor`、HUD 按宽高比重排、48dp 触摸目标 |
| B10 | **桌面平台集成包**（PF-14） | 托盘、全局快捷键、窗口记忆、置顶、缩放、更新入口；长期挂机体验 | 桌面 | M | `Tray`/`globalShortcut`/`screen`；窗口 bounds 存 userData；`setZoomFactor`；托盘模板图标 |
| B11 | **无障碍与新手引导**（PF-18） | 读屏/纯键盘可用；首次启动 3 步引导（规则/和弦/无猜）；商店审核加分 | 双端 | M | 桌面：aria-live、roving tabindex、焦点陷阱、reduced-motion、高对比；安卓：虚拟节点 + 状态播报；引导可重看 |

**档位 C：大投入/差异化（1–2 周+）**

| # | 功能 | 价值 | 双端 | 量 | 说明 |
|---|---|---|---|---|---|
| C1 | **历史回放（含失败局）**（PF-8） | Windows 7 扫雷内置能力；复盘/学习/反作弊/竞速提交的基础 | 双端 | L | 事件日志 `{t,type,r,c}` + seed 即可完全复现（引擎确定性）；播放器复用现有渲染；先桌面后安卓 |
| C2 | **变体棋盘 I：环绕 + 密度百分比 + 官方 30×24**（PF-15） | 低成本高新鲜感；环绕是成熟变体；官方密度图利于竞速 | 双端 | M | 先把 8 邻域抽成 `neighbors.js`（正是 L15 重构），再切边界语义；双端等价测试 |
| C3 | **变体棋盘 II：六边形/三角形/3D**（PF-16） | 差异化卖点 | 桌面先行 | L | 需 cell graph 抽象 + 多边形渲染 + 求解器改造；不建议近期做 |
| C4 | **在线竞技/排行榜**（PF-17） | 社区天花板；世界排名体系成熟 | 双端+后端 | L | 需账号/服务端/反作弊/隐私政策；建议等 A1/B3/C1 后再评估；过渡可用“成绩卡 + 社区榜” |

### 4.3 建议先做的前 5（调整后）

1. **先修 M2 求解器重构**（不算新功能，但它是 A2 提示、无猜成功率、C1 回放的共同前置；可把 16×30 单次 ~0.9ms 再降一个量级，并让 30×30/40×40 无猜从“必然降级”变成“有机会成功”）。
2. **A1 种子化 + 重开同局 + 分享**（1 天，双端；之后 B2/B3/C1 全部复用）。
3. **A2 提示/安全点击**（1–2 天，双端；直接复用 M2 的 move-list）。
4. **A3 设置中心 + 显式暂停 + A4 棋盘缩放**（桌面 2 天；吃掉 M13/DE-13）。
5. **B1 Web/PWA**（2–3 天；与 A1 联动做 URL 分享，是当前最大的用户触达杠杆）。

组合发布建议：**第一批** A1+A2+A3/A4（约 1 周，形成“可分享 + 有提示 + 更好用”的小版本）；**第二批** B1+B2+B3（Web 版 + 每日挑战 + 竞速，约 1–2 周）；**第三批** C1/C2 + B4/B11（回放/变体/国际化/无障碍）。

---

## 五、建议执行顺序

### 第 1 批：立即修（0.5–1 天，全部 S）

1. H1 键盘光标越界；H2 无猜异步判定（双端）；H3 视真机情况改 `isVirtual`。
2. M10 菜单 radio；M11 统计时间格式；M12 自定义确定确认；M9 的 Esc/键位录制清理（对话框统一层可后续做）。
3. M5 存档类型校验；M7 的 0 秒纪录守卫；M22/M23 安卓两处小块 UI 修复；M1 注释修正。
4. 顺手：H4 密钥备份移出 `dist/`；补对应单测（键位损坏/0 秒/光标重置/菜单状态）。

### 第 2 批：近期（3–5 天）

1. M2 求解器性能重构 + M1 无猜策略（先写基准，改后回归 76+69 项）。
2. M3/M4 Plan 契约与参数校验（双端）。
3. M9/M13/M14 桌面对话框统一层 + 键盘滚动 + 缩放（同时修 a11y 基线）。
4. M15/M19 生成生命周期 + 安卓状态保存。
5. H5 发布准备：LICENSE、版本单一来源、tag/CI 验证、Android release step 实测、产物 checksum。
6. M18 Electron 升级（先补打包冒烟）；M21 CI 质量门。
7. M16/M17 安卓分配与统计原子写；M20 i18n 起步（先桌面 title/menu/对话框）。

### 第 3 批：功能迭代（按 §4 路线图）

A1 → A2 → A3/A4 → B1/B2/B3 → C1/C2 + B4/B11。

### 验收标准（建议）

- 每批结束：桌面 `npm test`、安卓 `:core:test` 全绿；新增回归用例覆盖本批修复点。
- 发布前：`electron-builder --dir` 产物内 worker 冒烟；安卓 `assembleRelease` + `apksigner verify`；`aapt dump badging` 版本号与 tag 一致；打包产物启动冒烟（桌面 dev + 打包各一次）。

---

## 六、不建议做 / 避免浪费时间

- **不要为桌面 `dirty` 字符串键做优化**：50×50 实测 0.35ms，渲染 2500 格全量重建 ≈10ms，瓶颈不在这里（desktop-engine）。
- **不要重写 Electron → Tauri**：交换的是 ~100MB 安装包体积与内存，但 UI 代码几乎重写，收益/成本比不划算（product）。
- **不要为“零成本修掉所有 P3”排长队**：L1–L20 中大部分可以随功能迭代顺手做（尤其 L10/L11 与桌面平台化、L7/L8 与音效/沉浸式）。
- **不要在没做 A1/seed 的情况下做回放**：回放强依赖种子复现，顺序反了会返工。
- **不要未经真机就改 `isVirtual` 后收工**：改完必须在 KPA 上验证手柄、触摸、返回键三套输入。

---

## 附录 A：审查方法与局限

- 桌面：逐文件通读 + `npm test` + Node 探针（引擎不变量/3BV 独立复算/生成成功率/求解器计时）+ Electron 33 实机 CDP（渲染 9223/主进程 9229，`--user-data-dir=/tmp/ms-review-profile`）+ `electron-builder --dir` 产物测试。菜单原生点击无法用 CDP 触发（DU-3 为推断），DU-2 已在隔离菜单上实测复现。
- 安卓：逐文件通读 + `GRADLE_USER_HOME=<repo>/.gradle-user-home ./gradlew … --offline --rerun-tasks`（默认 `~/.gradle` 被另一项目占用且 wrapper 在慢速下载）+ JVM 只读探针 + `aapt`/`apksigner`。**无真机/模拟器**：输入、渲染、生命周期结论为读码推理，已在各报告标注；H3/AA-3/AA-14 需 KPA 实机确认。
- 网络：`web_search` 返回 401；`web_fetch` 仅 `minesweepergame.com`/`chiark.greenend.org.uk` 可用。product 报告的竞速/排名/变体资料已实际抓取，其余标注 unverified。GitHub Releases 无法联网核实，发布状态以本地 `git tag`/产物为准。
- 本轮**未修改任何源码/测试/配置**；仅新增本目录 6 个 Markdown 文件；所有临时探针在 `/tmp`，Electron/Gradle 进程已清理。

## 附录 B：已确认无问题 / 做对了的部分

- **引擎正确性**：75 局随机对局不变量（`revealedCount`/`flagCount`/终局雷全开/唯一爆炸/胜利条件）零违例；3BV 与独立实现 100 盘零差异；错旗和弦 → lost + wrongFlag；求解器三规则 sound（判 true 必无猜测步）；`planMines`/`Solver` 无静态可变状态，8 线程并发生成 0 错误；两端 8 组方法逐分支等价。
- **桌面渲染/安全**：dirty 增量 DOM 更新（单格 0.1ms 级、2500 格 ≈10ms）、事件委托、`renderAll` 仅尺寸变化时重建；`sandbox+contextIsolation+nodeIntegration:false`+CSP+双导航拦截；墙钟锚点计时；打包后 worker/IPC 正常。
- **安卓绘制**：静态位图 + `dirty` 局部重绘、动画格截断、Paint/RectF 复用、光标只失效两格、计时只失效 HUD、离线合成音效零资源；R8 后 release APK 189KB（签名）。
- **统计口径**：`ready→任意状态`才计一局（避免胜率 >100%）；历史最近 N 局曲线、最佳 3BV/s、连胜语义双端一致。
- **测试**：桌面 76 项覆盖引擎/生成/求解/统计/键位/移动；安卓 core 69 项并含 `planMines` 并发前提、`applyPlan` 幂等、问号清除、终局守卫等回归。
- **设计取舍（非缺陷，建议文档写明）**：插对全部旗即胜（与 WinXP 原版不同）；`revealedCount` 只计安全格；对话框/失焦暂停计时（防暂停研究局面，但影响统计口径）；`bbbv` 命名。

## 附录 C：报告索引与关键文件

| 报告 | 内容 | 条数 |
|---|---|---|
| [desktop-engine.md](desktop-engine.md) | 引擎/算法/统计/键位/生成器 | 13（P1×1、P2×6、P3×6） |
| [desktop-ui.md](desktop-ui.md) | UI/交互/菜单/对话框/Electron 安全/打包 | 20（P1×1、P2×11、P3×8） |
| [android-core.md](android-core.md) | 安卓 core 引擎/求解器/统计/并发/等价性 | 13（P1×1、P2×4、P3×8） |
| [android-app.md](android-app.md) | GameView/触摸/手柄/生命周期/无障碍/构建 | 16（P1×1、P2×5、P3×10） |
| [product-and-release.md](product-and-release.md) | 功能矩阵/18 条路线图/README/CI/发布/仓库 | 功能 18 + 工程 25 |

> 各分报告保留完整证据（`file:line`、探针输出、截图路径、命令耗时）与各自的“已核实项”，本总报告做了跨报告去重与优先级合并。
