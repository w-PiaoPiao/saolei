# 桌面引擎层修复记录（desktop-engine）

任务：task-6（FIX-PLAN §1 desktop-engine 全部项）。只改 write scope：`src/game.js` `src/solver.js`
`src/generator.js` `src/generator-worker.js` `src/config.js` `src/movement.js`（未改）`src/neighbors.js`（新）
`src/stats.js` `src/keybindings.js` `test/`；未改 `src/ui.js`/`src/app.js`/`src/main.js`/`src/index.html`。

验证基线：`npm test` **148/148 通过**（2026-10-27，Node v26.8.2；其中 desktop-ui 的
`test/ui.test.js` 19 项、本层新增/改写约 52 项）。临时基准脚本在 `/tmp`，未入库。

## 1. 逐项状态

| ID | 状态 | 关键改动（file:line） | 验证 |
|---|---|---|---|
| **H2** 生成器/引擎预算参数 | 已修 | `generator.js:150-186` 新增 `options={budgetMs,maxAttempts}`（非法值回落 CONFIG）；返回 `{mines,noGuessFallback,rows,cols,safeRow,safeCol}`；`game.js:139-152` `placeMines(safeRow,safeCol,options)` 透传、`game.js:279-295` `reveal(row,col,options)`（arity=3，UI 可 `eng.reveal.length>=3` 探测）；`generator-worker.js:13-38` 支持 `workerData.budgetMs/maxAttempts` 并回传几何字段 | 先插旗再首点走同步路径 + `{budgetMs:50,maxAttempts:8}`：50×50/500 **p50 25ms / max 39ms、8/8 成功应用**（修复前同路径 p50 376ms / max 841ms）；expert p50 2ms。worker 实测回传 `rows/cols/safeRow/safeCol` 正确，`applyPlanAndReveal` 成功、过期 plan 被拒 |
| **M1** 无猜确定性门控 | 已修 | `generator.js:115-140` `deterministicAttemptCap()`：只按 `cells>=hopelessMinCells && density>=hopelessDensity` 决定 1–2 次上限，删除「按实测单次耗时外推尝试次数」的抖动启发式；保留硬预算 `elapsed>=budgetMs` 中断（`generator.js:173-182`）；`config.js:43-63` 更新实测口径注释 + `NO_GUESS.gate` 常量；`MIN_EXPECTED_ATTEMPTS` 仅保留导出兼容（`generator.js:22-25`） | 真实时钟 N=10：9×9/10、16×16/40、16×30/99、50×50/100、**50×50/500 均 10/10 成功**（50×50/500 avg 163.6ms / max 479.5ms，修复前 Lead 实测 3/20=15%、avg 12.6 次/355ms）；门控配置 40×40/400 **0/10 但 avg 1.3ms**（修复前 0/20 且每局撞满 ~803ms）、50×50/1000 0/10 0.3ms、30×30/500 0/10 0.1ms（修复前 0/30 / 29–54ms）。`test/generator.test.js` 新增门控/预算/几何/校验 8 项 |
| **M2** 求解器性能重构 | 已修 | `solver.js` 整体重写（规则不变）：摊平 TypedArray、`Neighbors` CSR 邻域、同一快照内**批量落地**单点+子集结论（`:112-220`）、子集判定用时间戳替代每约束 Set、约束对象池、循环上限 20000 保留（`:33`）；`game.js:203/231/265/329/383/388`、`generator.js:108-135` 改用 CSR | **同批确定性盘面**（LCG 注入 random）：16×30/99 1.20→**0.36ms**（3.3×）、50×50/500 26.36→**2.60ms**（10.1×）、50×50/160 16.36→**2.41ms**（6.8×）；**差分对比旧实现 207 盘（14 组尺寸/密度，含 1×1/2×2 退化）0 mismatch**，且确认 `solve()` 不修改传入 board（`test/solver.test.js` 新增）。引擎热路径未回退：initCustom 50×50 0.38ms、首点 reveal 0.90ms、compute3BV 0.81ms（±0.2ms 噪声） |
| **M3** applyPlan/applyPlanAndReveal 校验 | 已修 | `game.js:162-200` `applyPlan()` 校验 `mines.length===rows*cols`、雷数===`totalMines`、`plan.rows/cols` 一致、`safeRow/safeCol` 的 3×3 无雷（缺几何字段时向后兼容）；失败返回 false 且不改状态。`game.js:297-311` `applyPlanAndReveal()` 额外要求 `plan.safeRow/safeCol` 与点击格一致，`applyPlan` 失败即返回 false（不 setState/不翻开）。generator/worker 回传几何见 H2 | `test/game.test.js` 新增 4 项：合法 plan 生效、重复/终局/已插旗/坐标不匹配/长度错/雷数错/行列错/安全区有雷全部拒绝且不开局、旧格式（无几何）兼容 |
| **M4** generator 输入校验 | 已修 | `generator.js:44-67` `assertInputs()`：rows/cols 正整数、totalMines 非负整数且 ≤`rows*cols-9`、safeRow/safeCol 整数在界内，非法立即抛错 | `test/generator.test.js` 新增：0/负数/小数行列、负雷数、NaN、越界安全区 9 种非法输入；1×1/0 雷合法；上限 72（9×9）报错 |
| **M5** keybindings/stats 损坏防护 | 已修 | `keybindings.js:18-25` `isValidKey()`（非字符串/空串/纯空白/超长拒绝，保留单空格默认键）；`:55-82` load 校验存档形状与每项 key；`:88-95` setKey 校验；`findConflict/getActionForKey/getDisplayKey` 防非字符串。`stats.js:50-78` load 逐字段 `Number.isFinite` 校验/旧版迁移、`:81-118` history 逐条清洗、`:120-131` save try/catch（失败降级为仅内存统计） | `test/keybindings.test.js` 新增 8 项（数字/null/对象 key、数组/字符串存档、空串/超长、setKey 拒绝、深拷贝、formatKey 导出）；`test/stats.test.js` 新增 3 项（类型损坏→0/丢弃、save 抛错不中断、history null 不再抛错） |
| **M6** stats 分桶 + 旧存档迁移 | 已修 | `stats.js:9-19/50-78/81-118` schema v2：`bucket`=预设 level 或 `custom:<rows>x<cols>/<mines>`；`bucketOf/currentBucket`（`:133-146`）、`getBuckets()`（`:250-273`）、`getHistory(bucket,limit)`（`:275-287`）逐桶限长 HISTORY_MAX，旧数组按 level 迁移（custom→`custom` 桶），旧 bestTime 秒→毫秒；`getSummary()` 深拷贝并保留 beginner/intermediate/expert/custom 键（无记录 null）兼容旧 UI | `test/stats.test.js` 20→30 项：自定义两盘面互不覆盖、切预设清空 currentCustom、60+1 局后每桶各自保留 50（不再互相挤空）、旧存档迁移/类型损坏、limit≤0 返回空、深拷贝 |
| **M7** stats 毫秒/0 秒/暂停/插旗标记 | 已修 | `stats.js:156-206`：`onGameWin(time, bbbv=0, meta={durationMs,pausedMs,wonByFlags})`、`onGameLose(time, meta)`；bestTime 存毫秒、3BV/s = `bbbv/(durationMs/1000)`；`time<=0 && !(durationMs>0)` 不更新 best；`pausedMs>0` 写 history 标 paused 且不更新 best；`wonByFlags` 写 history 标记但不更新 3BV/s best（bestTime 仍记录，按 FIX-PLAN §2.4 原文） | `test/stats.test.js`：0 秒无 meta 不写记录、0 秒+durationMs=350 记录 350ms 且 3BV/s=30/0.35、暂停局不更新 best、flag-win 只跳 3BV/s、毫秒优先于整秒 time |
| **M8** 0 雷盘插旗判胜 | 已修 | `game.js:443-459` `checkWin()` 第二分支加 `this.totalMines > 0` 前置；逻辑与安卓 `GameEngine.kt:checkWin` 对齐（android-core 负责安卓侧） | `test/game.test.js`：0 雷盘插旗→问号仍 playing、真正翻开才 won |
| **L12/DE-13** flag-win 数据 | 已修 | `game.js:34/78/369-373` `wonByFlags`：`toggleFlag` 触发胜利时置 true；`init/initCustom` 重置 false；另新增 `elapsedMs` 字段（UI 计时写入口，重置 0） | `test/game.test.js`：插旗胜 true / 翻格胜 false / 新局重置 |
| **L15** neighbors/常量/导出/深拷贝 | 已修 | 新增 `src/neighbors.js`（UMD：`NEIGHBOR_OFFSETS`、`forEachNeighbor(rows,cols,r,c,fn)`、`get(rows,cols)` CSR 缓存、`clearCache()`）；`config.js:20-24` `STATES/LEVEL_IDS/CUSTOM_LEVEL_ID`（值不变，纯引用）；`keybindings.js:18-25/125-132` `formatKey` 保留全局并挂 `KeyBindings.formatKey`、`KeyBindings.isValidKey`；`keybindings.getBindings()`/`stats.getSummary()`/`getHistory()` 深拷贝 | `test/neighbors.test.js` 5 项（偏移/角边中心/CSR 与暴力一致/缓存/clearCache）；`test/keybindings.test.js` 深拷贝与静态导出；`index.html` 已由 desktop-ui 在 `solver.js` 之前加载 `neighbors.js`（已核对 `src/index.html:35-37`） |
| **L16** 跨端 parity 测试 | 已修 | 新增 `test/parity.test.js`：正则读取 `android/core/.../GameEngine.kt`、`StatsManager.kt`，对比难度表（rows/cols/mines）、`NO_GUESS_MAX_ATTEMPTS/BUDGET_MS`、**无猜确定性门控 `HOPELESS_MIN_CELLS/HOPELESS_DENSITY/GATE_MAX_ATTEMPTS` ↔ `CONFIG.NO_GUESS.gate`（并校验「同时满足」语义）**、`HISTORY_MAX`、自定义雷数上限 `rows*cols-9`；对可能重构移除的旧常量 `MIN_EXPECTED_ATTEMPTS` 做「存在则必一致」容错 | `npm test` 内 **7 项**通过（当前两端常量一致：9×9/10、16×16/40、16×30/99、600/800、门控 900/0.25/2、HISTORY_MAX=50） |
| **L20** 测试补齐 | 已修 | 见各条；新增/改写：`test/parity.test.js`、`test/neighbors.test.js`；`game.test.js` +9、`generator.test.js` +8/-、`stats.test.js` +13/-、`keybindings.test.js` +8、`solver.test.js` +3 | `npm test` 148/148（本层新增约 53 项） |

## 2. 桌面端最终接口（已与 desktop-ui 对齐，可直接消费）

```js
// ---- generator.js（UMD MineGenerator） ----
Generator.generate(rows, cols, totalMines, safeRow, safeCol, noGuess,
                   now = Date.now, random = Math.random, options = {})
// options?: { budgetMs?: number, maxAttempts?: number }（非法值回落 CONFIG.NO_GUESS）
// => { mines: Uint8Array(rows*cols), noGuessFallback: boolean,
//      rows, cols, safeRow, safeCol }                      // 后 4 个字段为新增（M3）
Generator.MIN_EXPECTED_ATTEMPTS // 20，legacy 导出（M1 后策略不再使用）
// 输入非法抛 Error：行列/雷数/安全区坐标（M4）

// ---- game.js（UMD GameEngine） ----
new GameEngine(level?)                 // 'beginner' | 'intermediate' | 'expert'
game.reveal(row, col, options?)        // arity 3，options 仅在首次点击同步布雷时生效（H2）
game.placeMines(safeRow, safeCol, options?)   // arity 3 → boolean（applyPlan 结果）
game.applyPlan(plan)                   // → boolean；校验长度/雷数/rows/cols/3×3 安全区（M3）
game.applyPlanAndReveal(row, col, plan) // → boolean；还要求 plan.safeRow/safeCol 与点击格一致
game.wonByFlags                        // boolean，toggleFlag 触发胜利为 true（L12/DE-13）
game.elapsedMs                         // number，UI 计时器写入（终局 durationMs 来源）
game.bbbv / game.noGuessFallback / game.state / game.dirty ...
GameEngine.STATES                      // { READY:'ready', PLAYING:'playing', WON:'won', LOST:'lost' }
// 状态字符串值未变，STATES 仅提供引用

// ---- stats.js（StatsManager） ----
new StatsManager(storage?, config?)
stats.onGameStart(level, custom = null)          // custom = {rows,cols,mines} | null
stats.currentBucket                              // getter → string|null（预设 level / custom:<r>x<c>/<m>）
stats.bucketOf(level, custom)                    // → string|null
stats.onGameWin(time, bbbv = 0, meta = null)     // meta = {durationMs?, pausedMs?, wonByFlags?}
stats.onGameLose(time, meta = null)
stats.bestTimeOf(bucket)                         // → 毫秒|null（单位是毫秒！）
stats.bestBbbvPerSecOf(bucket)                   // → number|null
stats.getHistory(bucket, limit = 20)             // → [{bucket,level,time(秒),durationMs,bbbv,ts,paused,wonByFlags}]
stats.getBuckets()                               // → string[]（预设 3 个恒在 + custom 桶）
stats.getSummary()                               // → {..., bestTime:{bucket→毫秒}, bestBbbvPerSec,
                                                 //      buckets:string[], currentBucket}
// 规则：time<=0 且无 durationMs → 不更新 bestTime；paused 局不更新 best；wonByFlags 不更新 3BV/s best
// 旧存档（无 bucket / bestTime 以秒）自动迁移，无需 UI 处理

// ---- keybindings.js ----
KeyBindings.formatKey(key) / KeyBindings.isValidKey(key)   // 静态导出（浏览器全局 formatKey 保留）
bindings.setKey(action, key)     // 非字符串/空串/纯空白/超长 → false（M5）
bindings.getBindings()           // 深拷贝
```

## 3. 验证与实测

| 命令 / 基准 | 结果 |
|---|---|
| `npm test` | **148/148 通过**（game 31、stats 30、generator 16、keybindings 17、solver 15、movement 8、neighbors 5、parity 7、ui 19） |
| 求解器差分（/tmp/diff-solver.js） | 旧 vs 新 207 盘、14 组尺寸/密度：**0 mismatch**；验证不修改输入 board |
| 求解器基准（/tmp/bench-solver.js，同一确定性盘面） | 16×30/99 **1.20→0.36ms/盘**；50×50/500 **26.36→2.60ms/盘**；50×50/160 **16.36→2.41ms/盘** |
| no-guess 生成（真实时钟 N=10） | 9×9/10 10/10 0.3ms；16×16/40 10/10 0.3ms；16×30/99 10/10 9.3ms（max 97 次尝试）；50×50/100 10/10 1.1ms；**50×50/500 10/10，avg 163.6ms / max 479.5ms**；门控：40×40/400 0/10 avg 1.3ms、50×50/1000 0/10 0.3ms、30×30/500 0/10 0.1ms |
| H2 同步回退（/tmp/probe-final.js） | 插旗→首点 + `reveal(row,col,{budgetMs:50,maxAttempts:8})`：50×50/500 **p50 25ms / max 39ms**（8/8 应用）；expert p50 2ms（修复前同路径 p50 376ms / max 841ms） |
| worker 端到端 | 16×30/99 worker 47ms，回传几何 `16,30,8,15`、Uint8Array 无损；`applyPlanAndReveal` 成功（bbbv=169），过期 plan 被拒；`{budgetMs:30,maxAttempts:2}` 23ms 降级 |
| 随机对局不变量 | 75 局（3 难度 × 25）随机操作，`revealedCount/flagCount/lost 雷全开/唯一爆炸/won 条件` 零违例；错旗和弦仍 lost+wrongFlag |
| 引擎热路径 | initCustom 50×50 0.38ms；首点 reveal 0.90ms；compute3BV 0.81ms（与改前同量级） |

## 4. 未完成 / 风险 / 注意事项

- **未做**：`src/movement.js` 无需改动（方向逻辑与测试一致）；未改 UI/主进程（desktop-ui 范围）。
- **跨端一致性**：`test/parity.test.js` 依赖 android/core 目录存在；android-core 若最终移除
  `MIN_EXPECTED_ATTEMPTS` 或改造 `Level(...)` 行格式，测试已做「缺失跳过」容错，但**难度表/预算/HISTORY_MAX
  若改名/改值会 fail 提示漂移**（这是设计目的，不是 flake）。
- **stats 单位契约**：`bestTime`/`bestTimeOf()` 均为**毫秒**；`history[].time` 仍为秒、`durationMs` 为毫秒
  （桌面-ui 已按毫秒比较/显示）。旧存档秒值在首次 load 时 ×1000 迁移，落盘即 schema v2。
- **无猜策略调参**：门控阈值在 `config.js NO_GUESS.gate`；实测数据见注释。`MIN_EXPECTED_ATTEMPTS` 仅为
  兼容导出，若后续确认无调用方可删除（需同步 `test/generator.test.js` 与安卓 parity 测试）。
- **`wonByFlags` 的 bestTime**：按 FIX-PLAN §2.4 原文只限制 3BV/s best；若产品希望插旗胜也不计入最快时间，
  改 `stats.js:onGameWin` 的 `canUpdateBest` 加 `!wonByFlags` 即可（1 行，需同步测试）。
- 未执行 git 写操作；工作区保留所有改动供 Lead 汇总。

## 5. 改动文件

- `src/config.js`、`src/game.js`、`src/generator.js`、`src/generator-worker.js`、`src/solver.js`、
  `src/stats.js`、`src/keybindings.js`、`src/neighbors.js`（新）
- `test/game.test.js`、`test/generator.test.js`、`test/solver.test.js`、`test/stats.test.js`、
  `test/keybindings.test.js`、`test/neighbors.test.js`（新）、`test/parity.test.js`（新）
