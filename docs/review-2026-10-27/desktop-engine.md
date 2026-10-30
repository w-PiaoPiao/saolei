# 桌面前端引擎与算法审查

- 审查范围：`src/game.js`、`src/solver.js`、`src/generator.js`、`src/generator-worker.js`、`src/movement.js`、`src/keybindings.js`、`src/config.js`、`src/stats.js`、`test/*.test.js`；参考 `src/ui.js`/`src/app.js`/`main.js` 的调用方式（不评 UI 细节）。
- 基线：`9c92fda`，工作树干净；`npm test` 76/76 通过（180.5ms，Node v26.8.2，2026-10-27）。
- 上一轮报告：`docs/audit-2026-10-12.md`（基于 `461c395`），已修项只在“已核实项”简述，不重复展开。

## 结论摘要

1. 引擎核心正确性扎实：75 局随机对局不变量（revealedCount/flagCount/终局雷全开/单雷爆炸）零违例；3BV、洪泛、和弦、问号三态、终局守卫均与测试一致，未发现会让正常对局进入死局/崩溃的 P0。
2. 最值得先修的是 **无猜首点的一条可达同步回退路径**：先在棋盘上插旗再首点，`canGenerateAsync()` 因 `state==='playing'` 返回 false，生成退回渲染进程主线程，实测 50×50/500 冻结 p50 376ms、max 841ms（DE-1，P1，一行条件即可修）。
3. **无猜生成策略在阈值上抖动且注释与实测不符**：50×50/500 实测成功率 15–18%，同一配置可能 45ms 就降级、也可能跑满 ~840ms；40×40/400 必撞满 800ms 预算；30×30 空转 600 次（DE-2，P2）。
4. **求解器是 no-guess 的唯一主成本**：每步 `collect()` 全盘扫描 + 每约束 `new Set` + 排序，50×50 单次 35–62ms，占生成 95%+；有明确的算法优化空间（DE-3，P2）。
5. 两处**存档损坏防护缺口**会造成功能性故障：键位存档类型损坏 → 所有键盘输入抛 TypeError（DE-4）；统计存档类型损坏 → 胜率 NaN、效率曲线对话框抛错（DE-5）。
6. 统计口径有三处硬伤：自定义难度所有盘面混用一个记录池、历史全局 50 上限跨难度互相挤占（实测 30 初级 + 60 高级后初级曲线归零）、整秒计时导致 <1s 胜局记 0 秒（DE-6、DE-11）。
7. 其余为 P3：0 雷棋盘插旗可判胜、applyPlan 不校验 plan、异步生成缺超时/取消、8 邻域重复与隐式全局等可维护性问题。
8. 测试层面：76 项全绿但全部是纯模块测试；**异步生成/`applyPlanAndReveal`/ui/app/main.js 零自动化覆盖**，no-guess 大棋盘因预算不可注入而无法稳定测（DE-7）。

## 发现清单（按严重度 P0→P3 排序）

> 结论：无 P0。P1×1、P2×6、P3×6。

### [DE-1] 无猜模式「先插旗再首点」回退到同步生成，渲染进程主线程冻结（P1）

- 类型：性能 / UX（可达缺陷）
- 证据：
  - `src/ui.js:252-260`：`canGenerateAsync()` 要求 `this.engine.state === 'ready'`；
  - `src/game.js:294-299`：`toggleFlag` 在 `ready` 时把状态改为 `playing`（因此先插旗后 `canGenerateAsync()` 必为 false）；
  - `src/ui.js:318-325` → `src/game.js:224-234` → `src/game.js:118-126`：走 `placeMines()` 同步 `Generator.generate(...)`，预算 800ms（`src/config.js:40-43`）；worker 异常回退 `src/ui.js:296-301` 同样走这条路。
  - 临时探针（/tmp/probe10.js，复刻上述条件 12 次/配置，真实时钟）：`50×50/500` 首点耗时 **p50 376ms、max 841ms，7/12 降级**；`expert 16×30/99` p50 16ms、max 132ms；`beginner/intermediate` ≤2ms。verified（等价路径实测，未启动 Electron 本体）。
- 影响：no-guess 开启时，玩家习惯性先右击插旗（或先按 F）再左键首点，会出现最长约 0.84 秒的整窗卡死：计时器、动画、鼠标全部冻结，且无“正在生成”提示（`generating` 未置位）。50×50 自定义局最明显；500ms 以上卡顿已属可感知缺陷。
- 建议：把 `canGenerateAsync()` 的条件从 `state === 'ready'` 放宽为 `!minesPlaced && (state === 'ready' || state === 'playing')`（`applyPlanAndReveal` 已允许 `playing`，`src/game.js:241-251` 无需改）；这样插旗不再阻断异步路径。另给 `Generator.generate` 增加可选 `budgetMs` 形参，同步路径传小预算（如 50ms）保证回退也不会长时间冻结。注意安卓 `GameView.kt:588` 是同一条件，需同步修改以保持两端一致。
- 工作量：S（桌面 1 行 + 生成器 1 个可选参数 + 回归测试）

### [DE-2] 无猜生成策略在 `MIN_EXPECTED_ATTEMPTS` 阈值上抖动；`config.js` 注释与实测不符（P2）

- 类型：性能 / 产品行为 / 文档漂移
- 证据：
  - `src/generator.js:118-129`：预算检查在整次 `solve()` 之后；`budgetMs / (elapsed/(attempt+1)) < 20` 决定是否提前降级——当单次成本 ≈ 800/20 = 40ms 时，条件在“刚好 20”两侧抖动；
  - `src/config.js:36-39` 注释称“50×50 单次约 32ms，800ms 内约 25 次尝试；…50×50 会立即降级”（`src/generator.js:18-22` 同）；
  - 实测（/tmp/probe1/3/9.js + Lead 交叉数据）：`50×50/500` 单次 solve 35–62ms → 两种行为并存：8 次直测中 2 次 **43–45ms 返回 1 次尝试**、6 次 **815–840ms 跑 28–37 次尝试**（其中 1 次成功）；总成功率 2/11≈18%，与 Lead 的 3/20=15%、平均 12.6 次/355ms 一致。`40×40/400`：0/20，平均约 440 次、**每局都撞满 ~803ms**（Lead 数据）。`30×30/500`：0/30，跑满 600 次上限，仅 29–54ms（`maxAttempts` 兜底，不撞时间预算）。
  - `50×50/500` 独立可解率实测仅 1/60（probe3），而 `50×50/160`（6.4% 密度）可解 19/20、`50×50/100` 100%（Lead）——成败几乎完全由密度决定。
- 影响：(a) 同一配置在同一台机器上，是否白等 0.8s 由计时噪声决定，用户体感随机；(b) `config.js` 的调参依据（“单次约 32ms 会立即降级”）与实际相反（32ms 时 800/32=25 ≥ 20，不会降级，反而会继续跑到预算耗尽）；(c) 高密度自定义局（UI 允许到 85% 密度）开 no-guess 是纯粹浪费 800ms 后降级。
- 建议：用确定性门控替代“以实测耗时外推”：(1) 按 `rows*cols` 与密度直接预判——密度 > 约 10–12% 或 `rows*cols > 1600` 时只做 1–2 次尝试即声明降级（与机器速度无关）；(2) 或给 `generate` 增加 `budgetMs`/`maxAttempts` 形参，异步 worker 用完整 800ms、同步路径用 50ms（与 DE-1 联动）；(3) 成功优先：低密度（<8%）直接给足预算，实测这种盘面几乎必成；(4) 修正 `config.js:36-39` 注释为实测口径。测试可注入 `now`/`random`（`src/generator.js:97-103` 已支持），建议补“大棋盘必定提前降级”的稳定用例。
- 工作量：S（策略 10–20 行 + 注释 + 2 条单测）

### [DE-3] 求解器每步全盘重建约束：O(steps×n×8) + 每约束 `Set`/数组/排序，是 no-guess 主成本（P2）

- 类型：性能 / 算法
- 证据：
  - `src/solver.js:76-106`：`collect()` 每次 `step()` 都遍历整盘 `rows×cols`、对每个已翻开数字再扫 8 邻居，并为每个约束 `push({unknown, need, set: new Set(unknown)})`；
  - `src/solver.js:109-155`：`step()` 每轮只落地**一条**结论就返回（`return 1`），下一轮重新 `collect()`；子集规则每轮还 `sort` 一次 `order`（`src/solver.js:126`）；
  - `src/solver.js:160-189`：`guard 20000` 只是循环上限，不限制时间；
  - 实测（/tmp/probe2/5/9.js）：`50×50/20%` 单次 solve **p50 35–38ms、p95/最好样本 44–62ms**（160 个随机盘无 >100ms，但也没有时间上限）；`50×50/10%` avg 30ms；`expert 16×30/99` 0.8–1.35ms；Lead 实测 50×50 约 27ms/16×30 约 0.87ms——与 no-guess 每局总耗时（高级 18–21ms ≈ 20 次 × 0.9ms）吻合，即 **>95% 的生成时间在 solve 里**。
- 影响：no-guess 的帧/等待成本、以及预算内可尝试次数（直接决定成功率）几乎全由 solver 常数决定。当前大棋盘下“单次 30–60ms × 十几到几十次”是 DE-2 抖动与成功率低的根因。50×50 同步回退（DE-1）冻结 0.84s 也是它。
- 建议（按收益/改动比排序，均需改后用现有 76 项 + 新增基准回归）：
  1. **预计算邻居索引**：`neighborIdx = Array(rows*cols)`（每个格子 4/6/8 个下标数组，一次性构建，生成器 scratch 与 solver 共用）——去掉 collect/flood/3BV 里的 8 次边界裁剪，预计小幅提速（unverified）。
  2. **单点规则批量落地**：一次 `collect()` 后，把所有 `need===unknown.length`（全雷）与 `need===0`（全安全）结论**全部**应用，而不是一条就重扫；这些结论是 sound 的（矛盾在合法雷局不会出现），可把 `collect()` 调用次数降低一个量级（unverified 估计 2–4×）。
  3. **约束缓存**：`collect()` 只重建与本次新翻开/新标出格相邻的约束（维护 `cell -> constraint` 反向索引），避免全盘扫描；`Set` 可用每条约束的 `Uint8Array` 时间戳或排序数组替代。
  4. 若做无猜“提示”功能（见功能机会），把 `step()` 改为返回 move list，顺带实现上面前两条。
- 工作量：M（第 1+2 条约半天，含基准/回归）

### [DE-4] 键位存档缺类型校验：损坏的 `key` 会让**所有键盘输入**抛 TypeError（P2）

- 类型：缺陷 / 健壮性（存档损坏）
- 证据：`src/keybindings.js:41-51` 的 `load()` 只判断 `this.bindings[action]` 存在就把 `data.key` 原样写入；`src/keybindings.js:84-89`/`77-82` 直接 `data.key.toLowerCase()`。探针（/tmp/probe4/7.js）：存档 `{"flag":{"key":123}}` 或 `{"flag":{"key":null}}` 时构造成功（`getKey` 返回 123/null），之后 `getActionForKey('f')` 抛 `TypeError: data.key.toLowerCase is not a function`；`app.js:515` 每次 keydown 都调用它，于是方向键/翻格/插旗/新游戏全部失效（仅剩鼠标；可通过菜单“键盘快捷键→恢复默认”或清 localStorage 自救）。
- 影响：一旦 localStorage 中该键被外部写入/旧版本字段变更/手工修改，键盘输入整体不可用；错误发生时不在启动阶段，而在按键阶段，用户无提示。
- 建议：`load()` 中校验 `typeof data.key === 'string' && data.key.length > 0`，不合法则保留默认并记为待修复；`setKey` 增加同样校验（并拒绝空串、`'Escape'` 等保留键——`Escape` 会被对话框关闭逻辑先消费，绑定后表现为“无效”）；`getActionForKey`/`findConflict` 防御性读 `String(data.key)`。安卓 `KeyBindings.kt:44-55` 用 `toIntOrNull` + `code >= 0` 过滤（`setKey` 同样拒绝负码 `KeyBindings.kt:69-75`），桌面应补齐同等强度校验（跨端口径见“已核实项”）。
- 工作量：S（10 行 + 2 条测试）

### [DE-5] 统计存档缺类型校验：胜率 NaN / 效率曲线对话框抛错（P2）

- 类型：缺陷 / 健壮性（存档损坏）
- 证据：`src/stats.js:37-52` 仅做 `{...defaults(), ...JSON.parse(raw)}` 的浅合并，数值字段与 `history` 条目均不校验；`src/stats.js:101-104` 的胜率计算、`src/stats.js:133-136` 的 `getHistory` 直接信任数据。探针（/tmp/probe6/7.js）：
  - 存档 `{"totalGames":"abc","gamesWon":2}` → `onGameStart` 后 `totalGames` 变 NaN（`JSON.stringify` 落盘成 `null`），`winRate=NaN`、`gamesLost=NaN`；
  - 存档 `{"history":[null]}` → `getHistory` 抛 `TypeError: Cannot read properties of null (reading 'level')`，`app.js:329-339` 的“效率曲线”渲染链路随之中断（对话框已创建但内容不渲染）；
  - 存档 `{"bestTime":{"beginner":"20"}}` → 记录变成字符串，比较/显示依赖隐式转换；
  - `save()`（`src/stats.js:54-56`）无 try/catch，`localStorage.setItem` 抛错会从 `ui.onStateChange`（`src/ui.js:96`）冒泡到点击处理器，终局渲染被跳过（探针复现 QuotaExceededError 传播）。
- 影响：损坏/旧版存档会让统计面板出现 NaN、效率曲线打不开；写入失败时终局表现异常（棋盘已胜但未重绘）。安卓 `StatsManager.kt:55-60/88-92/102-115` 逐字段 `toIntOrNull`/格式校验、跳坏行，桌面侧防护明显更弱（跨端不一致）。
- 建议：`load()` 对数值做 `Number.isFinite` 校验/回退默认，`history` 用 `Array.isArray` + 逐条校验 `{level:string, time:positive int, bbbv:nonneg int, ts:number}`，无效条目丢弃并在下次 `save()` 时自愈；`getHistory` 内部再滤一遍；`save()` 用 try/catch 包住（失败只 console.warn，不影响对局）。补“类型损坏”单测（现有测试只覆盖非法 JSON，`test/stats.test.js:111-116`）。
- 工作量：S（20 行 + 3 条测试）

### [DE-6] 统计口径缺陷：自定义难度混池 + 历史全局 50 上限跨难度互相挤占（P2）

- 类型：缺陷 / 统计口径（数据保留与展示）
- 证据：
  - `src/stats.js:70-90`：`bestTime.custom`、`bestBbbvPerSec.custom`、`history` 只按难度名归档；`custom` 下的 9×9/10 与 50×50/500 共用一个记录槽——9×9 的 5 秒纪录会让 50×50 永远刷不出“新纪录”，两种盘面的 3BV/s 混在一条曲线里；
  - `src/stats.js:86-89`：`history` 是**全局**队列，`HISTORY_MAX=50`（`src/config.js:45-46`）裁剪时按时间戳丢最旧；而 `getHistory(level)`（`src/stats.js:133-136`）按难度过滤 → 换难度玩会把旧难度曲线整段挤掉。探针：30 局初级 + 60 局高级后 `getHistory('beginner')` **返回 0 条**，`history` 里只剩 50 条高级；
  - `src/stats.js:134`：默认 `limit=20`，而 `HISTORY_MAX=50`——即使单难度存满 50 条，曲线也只显示 20 条（探针：50 局同难度，存 50、默认返回 20）；桌面 `getHistory(level, 0)` 返回全部 5 条、`-1` 返回 4 条（`slice` 语义），安卓 `StatsManager.kt:191-192` 已修为 `limit<=0 → 空`（跨端行为漂移）。
- 影响：效率曲线是桌面主打的统计功能，跨难度切换会静默丢历史；自定义难度记录实际不可比较，新纪录横幅会误导。
- 建议：`bestTime/bestBbbvPerSec/history` 改为按“难度桶”存（每难度各自 `HISTORY_MAX`，共 4×50=200 条小体量），`custom` 桶 key 带盘面（如 `custom:16x16/40`，`getHistory` API 相应改 `levelKey`）；默认 `limit` 与 `HISTORY_MAX` 对齐或明确 UI 传入；`getHistory` 加 `limit<=0 → []`（与安卓一致）。
- 工作量：M（存储 schema 变更，需容错迁移旧格式：旧 `history` 数组按 `level` 分桶即可）

### [DE-7] 测试缺口：异步生成/UI/主进程零自动化覆盖，关键分支无稳定用例（P2）

- 类型：测试
- 证据与具体缺口（逐条，均可落成测试）：
  1. `GameEngine.applyPlanAndReveal` 在 `test/` 中**零直接用例**（全局 grep 只有实现与 UI 调用）：无“过期 plan 被拒（`minesPlaced` 后再 apply 无效）”“`won/lost` 后拒绝”“点击格已插旗/已翻开时返回 false”用例。
  2. 无 `ui.js`/`app.js`/`main.js` 任何自动化测试（76 项全是纯模块），`generationSeq` 作废、worker 失败回退、菜单动作、计时/暂停、统计接线均靠人工。
  3. no-guess 大棋盘无法稳定测：`Generator.generate` 只支持注入 `now`/`random`，**预算/尝试上限不可注入**；现有 `test/generator.test.js:51-78` 用假时钟绕过，但“50×50 同步回退耗时”这类真实路径无回归（建议配合 DE-1/DE-2 增加 `budgetMs` 形参后补测）。
  4. 降级路径只断言 `noGuessFallback===true`（`test/generator.test.js:51-60`），未断言降级时返回的 `mines` 仍合法（雷数正确、3×3 安全区无雷）——探针已验证合法（5/5、12/12），但没有测试守。
  5. 0 雷/1×1 等退化棋盘只测“构造成功”（`test/game.test.js:189-195`），未测对局（漏掉 DE-8）。
  6. 键位存档类型损坏（DE-4）、统计存档类型损坏（DE-5）无测试；现有只测非法 JSON。
  7. 没有“插旗→首点”状态机路径测试（DE-1 的触发条件），也没有 `toggleFlag` 在 `ready` 触发 `playing`/计时开始的断言。
  8. 和弦覆盖只测成功/旗数不匹配（`test/game.test.js:118-136`），缺“错旗和弦→踩雷+wrongFlag 标记”（探针已验证行为正确）、问号邻居被和弦翻开。
  9. `dirty` 只断言 `size>0`（`test/game.test.js:138-144`），未断言内容准确性（如 chord/洪泛后脏集合恰为变化格）。
  10. `movement.js` 缺 `rows/cols=0` 边界（安卓 `MovementTest` 已修并测；桌面 `movement.js:40-41` 返回 `{0,0}` 与安卓一致，但无测试）。
  11. `solver.solve` 未测“不修改传入 board”（生成器 scratch 复用依赖此性质）、全局规则分支、`guard` 耗尽行为。
  12. 跨端无 parity 测试：旧审查建议的 `PortParityTest`（桌面 `config.js` vs 安卓 `GameEngine.kt`/`StatsManager.kt` 常量）仍未落地；`test/generator.test.js:80-84` 只覆盖 `NO_GUESS` 与 `MIN_EXPECTED_ATTEMPTS`。
- 建议：优先补 1/3/4/6/8/11（纯模块，成本低）；UI 侧最小可行方案是把 `startAsyncReveal`/`generationSeq`/计时与统计接线抽成可注入 `electronAPI` 与时钟的纯逻辑模块后用 node:test 覆盖；parity 测试可用正则从 Kotlin 源码读取常量比对（防漂移）。
- 工作量：M（列表 1/3/4/6/8/11 约半天；UI 抽取 1–2 天）

### [DE-8] 0 雷自定义棋盘可“插旗→取消”直接判胜（P3）

- 类型：缺陷 / 状态机（不可从 UI 触发）
- 证据：`src/game.js:388-401` 的 `checkWin` 第二个分支只要求 `flagCount === totalMines` 且所有格 `flagged === mine`；`totalMines===0` 时条件恒真。`src/game.js:294-312` 的 `toggleFlag` 在旗→问号（`flagCount` 减到 0）后调用 `checkWin`。探针（/tmp/probe4.js）：`initCustom(9,9,0)` 后 `toggleFlag(5,5)` 两次 → `state='won'` 而 `revealedCount=0`，未翻开任何格；`1×1/0 雷` 走正常 `reveal` 也能正确 1 次翻开判胜（bbbv=1）。安卓 `GameEngine.kt:506-518` 逻辑完全相同（跨端同构，非桌面独有）。
- 影响：引擎公共 API 允许 `mines=0`（`initCustom` 校验只要求 `mines>=0`，`test/game.test.js:189-195` 明确允许），将来若 UI/自定义入口放开 0 雷（或安卓端入口不同），会出现“不翻格即胜”的荒谬判定并污染统计。当前桌面 UI 雷数下限为 1（`app.js:200-203`），不可达。
- 建议：`checkWin` 第二分支加 `this.totalMines > 0` 前置条件；更彻底的做法是“胜利只由安全格全开判定”，插旗分支仅用于 UI 提示（会影响现有 `test/game.test.js:104-108` 的“正确插旗判定胜利”用例，需同步改。若保留该规则，至少排除 0 雷）。
- 工作量：S（1 行 + 1 条测试）

### [DE-9] `applyPlan`/`applyPlanAndReveal` 不校验 plan 内容，被截断的雷位会静默产生错误对局（P3）

- 类型：缺陷 / 健壮性（IPC 信任边界）
- 证据：`src/game.js:132-159` 的 `applyPlan`/`applyMines` 只按 `mines[r*cols+c]` 取值（非 1/true 一律当非雷），不校验数组长度、雷数是否等于 `totalMines`、首点 3×3 是否安全；`src/game.js:241-251` 的 `applyPlanAndReveal` 不做 plan 前置校验。探针（/tmp/probe4.js）：`applyPlanAndReveal(4,4,{mines:new Uint8Array(0)})` 返回 true，棋盘实际 0 雷、`totalMines=10`，首点洪泛后 `revealedCount` 达到 81 ≥ totalSafe=71 → **立刻判胜**（state=won、bbbv=1）。
- 影响：`main.js:171-205` 只校验请求参数、不校验 worker 响应；一旦 worker 版本不匹配/结构化克隆异常/被篡改，渲染进程会把错误雷局当真实局开打，且表现为“秒赢”而非报错。正常路径不会触发。
- 建议：`applyPlan` 入口校验 `plan.mines.length === rows*cols`、雷数 === `totalMines`、`mines[safeRow*cols+safeCol]===0`（`applyPlanAndReveal` 侧校验整块 3×3），任一不满足则 `return false` 由调用方回退同步路径（`ui.js:285-301` 已有 fallback 通道）；`main.js` 响应侧同样校验雷数与长度。
- 工作量：S（10 行 + 2 条测试；`test/game.test.js` 需 `setupEngine` 之外增加 plan 校验用例）

### [DE-10] 异步生成生命周期缺口：无超时、作废不取消线程、对话框/失焦期间落地会开场（P3）

- 类型：缺陷 / 竞态 / UX
- 证据：
  - 无超时：`src/ui.js:279-316` 的 `startAsyncReveal` 只处理 resolve/reject；`main.js:184-204` 的 Promise 没有超时。若 worker 永不返回（如被系统挂起/生成异常慢），`this.generating` 永久为 true，棋盘所有点击/插旗被静默丢弃（`src/ui.js:319,341,387`），只能通过新游戏/切难度逃出。
  - 作废不取消：`src/ui.js:263-268` 的 `cancelGeneration` 只 `generationSeq++`，`main.js:184-204` 每请求新建 worker 且只在收到结果后 `terminate`；连点“新游戏+首点”会叠加多个最长 800ms 的 worker，旧结果被丢弃但 CPU 继续烧（资源浪费，非正确性问题）。
  - 对话框/失焦期间落地：`src/app.js:102-125` 的 `showDialog` 在 `state!=='playing'` 时不暂停计时，也不调用 `cancelGeneration`；生成在对话框打开期间完成 → `applyPlanAndReveal` 开场并 `setState('playing')`，引擎监听（`src/app.js:55-58`）启动计时器，棋盘在对话框背后开始计时；同理 `pauseGame`（`src/app.js:566-571`）在 blur 时因 `state==='ready'` 直接 return，之后生成完成仍会开场计时（窗口在后台时计时照走）。读码确认路径，未在 Electron 内实测（unverified 运行时表现）。
- 建议：`startAsyncReveal` 加 2–3s 超时（超时→`fallbackSync` 或提示重试）；`cancelGeneration` 通过 IPC 取消/终止对应 worker（给请求加 `requestId`，或在 `main.js` 维护 `Map<requestId, worker>` 并监听 `webContents.destroyed`），并把 `cancelGeneration` 挂到 `showDialog` 与 `pauseGame`；`applyPlanAndReveal` 前后各检查一次 `document.querySelector('.dialog-overlay')`/`document.hasFocus()`，不满足则丢弃并提示。
- 工作量：M（超时+取消 S，对话框/失焦接线 M）

### [DE-11] 计时/统计精度：终局用的是 250ms 前的轮询值，3BV/s 以整秒计算，<1s 胜局记 0 秒（P3）

- 类型：统计口径 / 精度
- 证据：
  - `src/app.js:23-30` 的 `syncTimer` 每 250ms 轮询一次；`src/app.js:55-58` 收到 `won/lost` 直接 `stopTimer()`，**不在终局补一次同步**，`game.timer` 停留在最后一次轮询值；`src/ui.js:96` 的 `stats.onGameWin(this.engine.timer, ...)` 与 `src/ui.js:92-93` 的破纪录判断都用这个可能陈旧的值（最多差 ~250ms，跨整秒边界时差 1 秒）。
  - `src/stats.js:81-90` 用整秒 `time` 计算 `bbbv/s`（`rate = bbbv / time`），无毫秒字段；`src/stats.js:81` 要求 `time > 0`，因此 **<1 秒的胜局**（首点洪泛直接通关等）会：`time=0` → 不写历史、不记效率（`test` 未覆盖），但 `bestTime` 会被写成 `0`（`src/stats.js:72-78`，因为 `0 < null` 分支取 0），成为永远无法打破的“0:00 纪录”，破纪录横幅还会播报。探针（/tmp/probe6.js）确认 `onGameWin(0,30)` 后 `bestTime.beginner=0`、`history=[]`、`bestBbbvPerSec=null`。
- 影响：最快时间/3BV/s 是主玩法统计，整秒粒度让 beginner 局（10–30s）误差可达 3–10%；0 秒纪录会让该难度的“新纪录”永远不再触发（`ui.js:92-93` 比较 `timer < 0` 恒 false）。
- 建议：引擎/UI 增加毫秒字段（如 `game.elapsedMs`，在 `won/lost` 时用同一锚点公式同步一次并停表），`stats.onGameWin(ms, bbbv)` 内部存 ms、展示时取整秒，`3BV/s = bbbv / (ms/1000)`；`bestTime` 以 ms 比较（展示转秒）；`time=0` 时用 `Math.max(1, ...)` 兜底或改为按 ms 判断“有效局”。安卓侧 `GameView.kt:283` 同样整秒，可一并统一。
- 工作量：M（桌面引擎/UI/stats 联动 + 迁移旧存档 seconds→ms 的兼容读取）

### [DE-12] 可维护性：8 邻域遍历重复 5 处、UMD 与隐式全局混用、`state` 裸字符串、浅拷贝暴露内部状态（P3）

- 类型：架构 / 可维护性
- 证据：
  - 8 邻域双循环 + 边界裁剪重复：`src/game.js:186-196`（3BV）、`src/game.js:209-220`（countAdjacentMines）、`src/game.js:280-290`（floodFill）、`src/game.js:321-340`（chord 两处）、`src/solver.js:86-95`、`src/generator.js:75-81`；改边界语义（如功能机会里的环形/六边形棋盘）需要同步改 6 处。
  - 模块导出风格三套并存：`game.js/solver.js/generator.js/movement.js` 是 UMD，`stats.js/keybindings.js` 是“CommonJS + 浏览器全局类”，`keybindings.js:11-13` 的 `formatKey` 是顶格函数声明、仅供 `app.js:486` 以隐式全局使用，**未被导出、无法单测**；`keybindings.js:96-99` 只导出类。
  - `state` 是裸字符串，`src/game.js` 内 20+ 处字面量比较；`level` 同理；`bbbv` 命名应为 `threeBV`/`threeBv`（不能在标识符开头用数字），`revealedCount`（只计安全格，`src/game.js:277`）建议 `revealedSafeCount`（旧审查已提，未改）。
  - `keybindings.getBindings()`（`src/keybindings.js:91-93`）与 `stats.getSummary()`（`src/stats.js:118-131`）只做浅拷贝，返回对象/内层 `bestTime` 仍是内部引用，调用方误改会污染内存态并随 `save()` 落盘（当前 UI 未改，属潜在隐患）。
  - 自定义棋盘上限（9–50、雷数 ≤ `floor(rows*cols*0.85)`）散落在 `app.js:165-203` 与安卓 `GameView.kt:1020-1034`，不在 `config.js`；引擎校验用的是另一套上限 `rows*cols-9`（`src/game.js:81-83`）。
  - 死代码/无用分支：`src/stats.js:70-79` 的 `custom` 与 `非 custom` 两个分支行为相同（可合并）；`src/generator.js:24-38` 的模块级 `scratch` 会在最后一次大棋盘后常驻内存（50×50 约 2500 个对象，可忽略，但注释未说明非重入前提）。
- 影响：不是功能缺陷，但直接抬高后续功能（回放/变体/提示）的改造成本，也让“跨端逐句对齐”的维护成本继续存在。
- 建议：新增 `src/neighbors.js`（导出 `NEIGHBOR_OFFSETS`、`forEachNeighbor(rows, cols, r, c, fn)` 或预计算索引表），game/solver/generator 统一引用；把 `formatKey` 挂到 `KeyBindings` 的静态方法/导出并补测试；用 `Object.freeze({READY:'ready',...})` 常量替代裸字符串；`getBindings/getSummary` 改深拷贝或 `Object.freeze` 快照；自定义范围移入 `CONFIG.CUSTOM` 供桌面 UI/引擎与 parity 测试共用。可与 DE-3 的邻居预计算一起做。
- 工作量：M（纯重构，需 76 项测试兜底）

### [DE-13] 统计诚信：对话框/失焦暂停不计时 + “插对全部旗即胜”仍可被利用（P3，沿用旧审查未修）

- 类型：统计口径 / UX（已知设计取舍）
- 证据：`src/app.js:102-125` 的 `showDialog` 在对局中 `stopTimer()`，`src/app.js:219-249` 的统计/曲线/关于对话框都通过它打开 → 玩家可随时开对话框“暂停思考”，暂停时间不进入最快时间；`src/game.js:388-401` 允许插对全部雷即胜（`test/game.test.js:104-108` 已固化），配合 no-guess 之外的运气局可做出“不翻开安全格”的短时胜利。旧审查 §5.1/§6.2 已记录，本轮代码仍在。
- 影响：最快时间/3BV/s 的可比性被稀释；单机游戏影响有限，但效率曲线和“新纪录”是主玩法，长期看数据不可信。
- 建议：记录累计暂停时长（`pausedMs`）并在统计里标注该局含暂停，或对“含暂停局”不计入最佳纪录；插旗胜利若要保留，统计中标记 `wonByFlags` 并在 3BV/s 中标注（3BV 理论最少点击数与本局实际点击不符）。
- 工作量：S（标注）/ M（暂停时长贯通 UI/引擎/统计）
- 备注：UI 侧实现细节属 UI 负责人范围，本条只从统计口径提出。

## 已核实项（上一轮已修 / 确认无问题）

- **无猜生成移出 UI 线程（旧 P1-1）已修并验证**：`main.js:171-205` worker_threads + `src/generator-worker.js`，打包 `asarUnpack`（`package.json:29-34`）与 `app.asar→app.asar.unpacked` 路径替换（`main.js:167-169`）；探针实跑 worker：16×30/99 23–52ms、50×50/500 57–230ms，`Uint8Array` 跨越结构化克隆无损、`noGuessFallback` 正确回传。
- **无猜降级提示（旧 P1-7）已修**：`src/index.html:23` `#noguess-warning` + `src/ui.js:270-273,478-484`；探针确认 `noGuessFallback=true` 时 HUD 会切换提示（UI 逻辑读码确认）。
- **50×50 可读性（旧 P1-5）已修**：`src/config.js:48-53` `CELL_SIZE.min=18`；`app.js:62-69` 对 50×50 算出 `min(1150/50,760/50)=15 → clamp 18px`（旧版 15px/7.5px 字号）。
- **旧 P1 一批正确性修复仍在位**：`initCustom` 参数校验（`game.js:74-99`）、`reveal/toggleFlag/chord` 越界与终局守卫（`game.js:226,296,316`）、`floodFill` 死代码已删（`game.js:268-292`）、`stats` 空串 `lastLevel` 归一（`stats.js:47`）。
- **主进程侧旧修**：单实例锁（`main.js:207-219`）、菜单按 `type` 抓取不依赖中文 label（`main.js:80-88`）、`fit-window` 有限性校验（`main.js:143`）。
- **引擎核心正确性**：75 局随机对局（beginner/intermediate/expert，随机翻开/插旗/和弦/终局辅助）不变量全过：`revealedCount`=实际已开安全格、`flagCount`=实际旗数、lost 时所有雷已翻开、exploded 唯一、won 时安全格全开或旗位全对（/tmp/probe8.js）。错旗和弦 → lost 且 `wrongFlag=true`（探针实测，行为符合设计）。
- **3BV 实现正确**：与 `test/solver.test.js:107-151` 的已知构造一致；50×50/500 计算 0.97ms/次，分配（`opened` 数组 + stack）可接受。
- **`movement.js` 无问题**：方向合成/相反抵消/越界裁剪与 8 条测试完全一致；`rows/cols=0` 时返回 `{0,0}`（与安卓修复后行为一致，仅缺一条测试）。
- **性能实测（50×50 引擎热路径）**：`initCustom`+全量脏标记（2500 个字符串键）0.35ms、普通布雷 0.056ms、首点洪泛 0.68ms、`compute3BV` 0.97ms——**旧审查“dirty 字符串键可优化”在 50×50 规模下无实际收益**，不建议为此改动。
- **普通模式生成器**：`Generator.generate(..., false)` 50×50 仅 0.056ms，候选数组/洗牌无性能问题；`generator.js:24-38` 的 scratch 复用正确（`gridFromMines` 每次全量写入 mine/adjacentMines，无脏数据残留）。
- **降级返回的雷局合法**：`noGuessFallback=true` 时返回的仍是最后一个合法随机局——探针验证雷数准确、首点 3×3 无雷（50×50/500 12/12、50×50/160 等配置全过）。
- **求解器规则 sound**：单点/子集/全局三条规则逐条审阅未发现假 true；求解器只读 board、不持有引擎状态（生成器 scratch 复用前提成立）；随机对局与“无猜生成后独立复算 `Solver.solve`”结果一致。
- **跨端常量当前一致（逐项核对）**：难度表（桌面 `config.js:13-17` ↔ `GameEngine.kt:20-24`）、`NO_GUESS 600/800`（`config.js:40-43` ↔ `GameEngine.kt:28-29`）、`MIN_EXPECTED_ATTEMPTS=20`（`generator.js:22` ↔ `GameEngine.kt:32`）、`HISTORY_MAX=50`（`config.js:45-46` ↔ `StatsManager.kt:30`）、键盘重复 500/40（`config.js:55-59` ↔ `GameView.kt:96-97`）、自定义范围 9–50/0.85（`app.js:165-203` ↔ `GameView.kt:1020-1034`）、计时上限 999（`app.js:25` ↔ `GameView.kt:283`）。**目前无漂移，但均无 parity 测试守护，且默认值分散在 3+ 处**（见 DE-7 第 12 条、DE-12）。
- **设计选择（非缺陷，已确认并建议保留现状/文档说明）**：插对全部雷即胜（`game.js:388-401`，测试已固化）；`revealedCount` 只计安全格；墙钟锚点计时（`app.js:16-52`，消除 setInterval 漂移）；对话框/失焦暂停计时（`app.js:102-125,566-577`，防“暂停研究局面”，但统计口径见 DE-13）。
- **旧审查中仍存在但本次不重复展开的项**：`ui.js/app.js` 零测试、无 lint/格式化配置、`src/*.js` 文件权限 600/644 不一致、Electron 依赖未升级——均非引擎/算法范围。

## 测试与实测记录

环境：macOS，Node v26.8.2，HEAD `9c92fda`，2026-10-27。仓库文件零修改（仅新增本文档），临时脚本全部在 `/tmp`。

| 命令 / 探针 | 结果 |
|---|---|
| `npm test` | **76/76 通过**，`duration_ms 180.53`（game 22、solver 12、stats 17、generator 8、keybindings 9、movement 8） |
| `/tmp/probe1.js` 求解器/生成器基准 | `solve 50×50/500` 48.8ms/次（同一盘 20 次）；`generate noGuess`：expert 21.1ms（0/5 降级）、50×50/500 avg 201ms/max 816ms（5/5 降级）、30×30/500 29.7ms（5/5 降级）、beginner 0.1ms |
| `/tmp/probe2.js` solver 时间分布 | 50×50/500 p50 37.7ms p95 44.6ms，可解 2/20；50×50/160 p50 16ms 可解 19/20；30×30/500 0.0ms 可解 0/30；expert p50 0.8ms 可解 2/50 |
| `/tmp/probe3.js` 生成循环插桩 | 50×50/500 8 次：2 次 `solveCalls=1`（43–45ms 降级）、6 次 28–37 次调用/815–840ms（1 次成功）；独立盘面可解率 1/60 |
| `/tmp/probe4/7.js` 边界与存档 | 0 雷棋盘两次 `toggleFlag` → `state=won, revealedCount=0`；1×1/0 雷正常 `reveal` 判胜；键位存档 `{flag:{key:123}}`/`{key:null}` → `getActionForKey('f')` 抛 `TypeError`；`stats` 存档 `{"totalGames":"abc"}` → winRate NaN；`{"history":[null]}` → `getHistory` 抛 `TypeError`；`{"bestTime":{"beginner":"20"}}` → 记录为字符串 |
| `/tmp/probe5.js` 引擎热路径 | `initCustom` 50×50 + 全量脏标记 0.354ms；普通布雷 0.056ms；`compute3BV` 0.968ms；首点洪泛 0.679ms；solver 密度扫描：4% max 19.7ms、10% avg 29.9ms、20% max 58.6ms、30%+ ~0ms（开局快失败） |
| `/tmp/probe6.js` 统计口径 | `getHistory(limit=0)` 返回 5 条、`-1` 返回 4 条（安卓返回空）；`onGameWin(0,30)` → `bestTime=0`、history 空、bestRate null；`getSummary().gamesLost = totalGames - gamesWon`（中途重开计入失败） |
| `/tmp/probe7.js` 额外存档 | `save()` 抛 QuotaExceededError 会从 `onGameWin` 冒出；`getItem` 抛错被 `load()` catch；键位存档为 `[]`/`"abc"`/`5`/`null` 均静默保留默认（JSON.parse 结果非对象时走空循环） |
| `/tmp/probe8.js` 随机对局不变量 | 75 局（3 难度 × 25）随机操作，`revealedCount/flagCount/lost 雷全开/唯一爆炸/won 条件` 全部无违例；错旗和弦 → lost + wrongFlag |
| `/tmp/probe9.js` worker + solver 最坏 | worker 16×30 23–52ms；50×50 57/137/230ms（1/3 成功），`Uint8Array` 保持；solver 50×50 160 个随机盘 max 62ms（无 >100ms） |
| `/tmp/probe10.js` DE-1 同步回退 | 先插旗再首点（复刻 `canGenerateAsync()===false`）：beginner 0–2ms、intermediate 1–2ms、expert p50 16ms/max 132ms、**50×50/500 p50 376ms/max 841ms（7/12 降级）**、30×30/500 28–29ms（12/12 降级） |
| 历史保留探针（node -e） | 30 局初级 + 60 局高级后 `getHistory('beginner')` = **0**，expert=50，总 50；同难度 50 局存满后默认 `getHistory` 只返回 20 |
| Lead 交叉实测（引用） | 50×50/500 3/20 成功（15%，avg 12.6 次/355ms）；40×40/400 0/20 且平均 440 次/约 803ms 撞满预算；30×30/300、30×30/500 0/30（600 次、29–54ms）；16×30/99 100%（19.9 次/18.2ms）；9×9/10、16×16/40、50×50/100 100% |

## 功能机会（引擎/玩法角度）

1. **对局回放 / 复盘（M）**：生成器已支持注入 `random`/`now`（`generator.js:97-103`），引擎是纯操作 API——扩展为 `generate(..., seed)` 并记录 `{seed, safeRow, safeCol, actions:[{t, type, row, col}], noGuessFallback}` 即可 100% 重放；可做“破纪录局回放”“与自我最佳点击序列对比”。顺带解决“最快时间无法验证真伪”。
2. **每日挑战 / 种子分享（S–M）**：`seed = hash(YYYY-MM-DD)` + 固定难度/密度，复用上一条的 seed 生成；本地存每日成绩、生成可分享文本（含 3BV/s）。无需后端即可让冷启动有目标感。
3. **提示 / 自动标雷（S–M）**：把 `solver.js` 的 `step()` 从“应用一条结论”改为“返回 move 列表”，即可提供“高亮一个必安全格 / 自动标一个必是雷的格”；no-guess 局天然可用，提示次数计入统计（`hintCount`）避免破坏公平。与 DE-3 的批量落地改造同源。
4. **对局自动保存 / 崩溃恢复（M）**：给引擎加 `serialize()/restore(snapshot)`（board、mines、flags、state、timer、noGuessFallback），新局/失焦/定时写入 localStorage，Electron 崩溃或重启后恢复；Electron 桌面端当前无“退出保护”，这比移动端更有价值。
5. **变体棋盘（M–L）**：环形边界（首尾相连）、六边形/三角形格、盲扫模式（不显示数字，仅靠和弦与旗数推理）。环形棋盘只需把 8 邻域与洪泛/3BV/求解器的边界判断抽象成 `neighbors.js`（DE-12 的重构正好是前置），即可在配置内切换。
6. **效率分析增强（S）**：引擎记录 `clickCount/revealCount/chordCount`（每次操作 +1），在统计里给出“实际点击/3BV = 点击效率”“浪费点击”和每局 3BV 对比曲线；数据已有一半（3BV、history），增量小、直接提升可玩性。

---

*本报告为只读审查产出；所有临时探针位于 `/tmp`，未修改仓库源码、测试或配置。*
