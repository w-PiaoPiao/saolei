# android-core 修复记录（第二轮 · 2026-10-27）

> Owner：android-core · write scope：`android/core/**`、本文件。
> 依据：`docs/review-2026-10-27/FIX-PLAN.md` §1/§3；验证命令：
> `cd android && GRADLE_USER_HOME=<repo>/.gradle-user-home ./gradlew :core:test --offline --console=plain`

## 1. 逐项状态表

| ID | 状态 | 关键改动（file:line） | 验证 |
|---|---|---|---|
| AC-1 / H2（core 侧） | 已修 | `GameEngine.hasPendingMines()`（`GameEngine.kt:286`）；`planMines/placeMines/reveal` KDoc 标注"同步生成最长 800ms，UI 线程避免直接调用"（`:160-176,468-480,565-575`） | 读码；app 侧按新判定适配（android-app 已确认） |
| AC-2 / M3 | 已修 | `MinePlan` 增加 `rows/cols/safeRow/safeCol`（`GameEngine.kt:28-53`）；`applyPlan` 返回 Boolean 并校验几何/长度/雷数/3×3 安全区（`:470-503`）；`applyPlanAndReveal` 增加 `plan.safeRow==row && plan.safeCol==col` 与几何校验（`:608-625`） | 新增 9 条失败分支测试（`GameEngineTest.kt`） |
| AC-3 / M4 | 已修 | `planMines` 入口统一 `require`：尺寸上限 `MAX_CELLS=10_000`、`Long` 防溢出、首点坐标在界内、雷数 ≤ `rows*cols-9`、`budgetMs/maxAttempts` 为正；异常统一 `IllegalArgumentException`（`GameEngine.kt:130-148,178-200`）；`initCustom` 同样加 `MAX_CELLS` 守卫 | 参数矩阵测试（非法行/列/雷数/首点/超大棋盘/溢出/budget/maxAttempts） |
| AC-4 | 已修 | `planMines(..., now: () -> Long = System::nanoTime, random: Random = Random.Default, budgetMs = 800L, maxAttempts = 600)`（`GameEngine.kt:178-190`） | 同种子可复现、假时钟预算/门控测试 |
| AC-5 / M8 | 已修 | `checkWin`：`!minesPlaced` 恒 false；旗子判胜分支要求 `totalMines > 0`（`GameEngine.kt:775-789`） | 0 雷盘三态测试 + 未布雷恒 false 测试 |
| AC-6 / M16 | 已修 | `dirty: MutableSet<Int>`（`row*cols+col`，`GameEngine.kt:276`）；新增 `consumeDirty(): IntArray`（`:302`）；`floodFill`/`compute3BV` 改显式 `IntArray` 栈（不装箱，`:655,520`） | 编码/序号/清空测试；app 已适配（android-app 确认） |
| AC-7 / M2 | 已修 | `Solver.kt` 重写：一维 `IntArray known`、显式 Int 栈洪泛、可复用扁平约束缓冲、单点规则一次 collect 批量落地、子集规则「按未知数计数排序分桶 + 有序数组双指针」（无 `Set`/`sortedBy`/`filter`） | **与 HEAD 版桌面 `src/solver.js` 对 240 块随机盘的 `solve()` 布尔结果 240/240 完全一致**；单测全绿；见 §2 基准 |
| AC-8 / M17 | 已修 | 存储改单 JSON key `stats.v2`（`StatsManager.STORAGE_KEY`，`StatsManager.kt:48`），一次事件一次 `putString`；`load()` 自动迁移旧 `stats.*` 多键并清理（`:282-296,330-389`）；`bestTime/bestBbbvPerSec` 返回只读副本（`:109-118`）；难度集合一致性见 `ParityTest` | 单键写入次数断言、旧存档迁移、损坏 JSON 回退、只读副本测试 |
| AC-9 / M7 / M6 | 已修 | `onGameWin(time, bbbv, durationMs, pausedMs, wonByFlags)`（`StatsManager.kt:213`）；0 秒守卫（`time<=0 && durationMs<=0` 不更新最快）；`pausedMs>0` 写 history 标记 paused 且不更新最佳；`wonByFlags` 写 history 但不计 3BV/s 最佳；3BV/s 用 `durationMs`；history 按 bucket 分桶、每桶 50 条；`bucketOf`（`:66`）、`bestTimeOf`（`:145`）、`getBuckets`（`:148`）、`lastBucket`（`:103`） | 分桶隔离/上限、暂停/旗胜/0 秒、毫秒 3BV/s、迁移测试 |
| AC-10 / L15（core 侧） | 已修 | 删除死代码 `onStateChange`/`stateListeners`/`setState` 广播；`dirty` 收口为 `consumeDirty`（仍保留公开 Set 以兼容 app）；`KeyCodes` 未用常量加"预留"注释（`KeyCodes.kt:15-17`） | grep 确认零残留引用 |
| L15 八邻域 helper（补充） | 已修（2026-10-27 补） | 新增 `Neighbors.kt`：`NEIGHBOR_OFFSETS` + `inline fun forEachNeighbor(rows, cols, row, col, action)` + `neighborCount`，与桌面 `src/neighbors.js` 语义一致；在非极限热路径改用它：`GameEngine.countAdjacentMines`、`fillGrid` 数字计算、`compute3BV` 邻域展开、`chord` 两处邻域循环；`floodFill`/`Solver` 极限热点保留内联并在 KDoc 注明原因（`Neighbors.kt:16-61`；`GameEngine.kt` 的 `countAdjacentMines` / `fillGrid` / `compute3BV` / `chord`；`Solver.kt:23-24` 性能约定） | 新增 `NeighborsTest`（角落/边缘/中心邻居集合、邻居数、0 尺寸/越界不崩、与手写参考实现逐格一致） |
| AC-11 / L7（core 侧） | 已修 | `Synth.noise` 补指数增益包络（与 `tone` 共用 `envelope()`，对齐 audio.js 的 linear×exponential 双衰减，`Synth.kt:23,53-70`）；新增 `Synth.SFX_VERSION = 2`（`:15`） | `SynthTest`：前段 RMS 明显大于后段、WAV 头字段校验、mix 饱和 |
| AC-12 / L20 | 已修 | 新增/扩充测试：子集规则（移植桌面用例）、求解器只读不变量、参数校验、注入种子/假时钟/预算、降级门控、applyPlan 5 类拒绝、applyPlanAndReveal 5 条失败分支、0 雷盘、Snapshot 往返/非法/副本、0 秒/暂停/旗胜、统计迁移与分桶、Synth、跨端常量 parity | `:core:test` **114 项全绿**（原 69 项） |
| Snapshot（§3.4） | 已修 | `GameEngine.Snapshot`（`GameEngine.kt:41-57`）+ `snapshot(): Snapshot`（`:357`）+ `restore(snapshot)`（`:388`，几何/难度/数组长度/雷数校验，越界抛 IAE）；`noGuess` 不入快照（UI 偏好） | 往返字段逐格比对、恢复后继续操作一致、非法快照 IAE、快照数组副本 |
| M1（安卓策略对齐） | 已修（2026-10-27 二次对齐） | 确定性门控替换在线耗时外推，**与桌面 `src/config.js` `NO_GUESS.gate` 完全一致**：仅当 `cells >= HOPELESS_MIN_CELLS(900)` **且** `density >= HOPELESS_DENSITY(0.25)` 时最多尝试 `GATE_MAX_ATTEMPTS(2)` 次即降级；其余配置成功优先、受 `budgetMs/maxAttempts` 约束（`GameEngine.kt:85-105,213-232`）；`MIN_EXPECTED_ATTEMPTS` 保留仅为跨端常量对照 | 门控 probe（见 §2.3）：40×40/400（1600 格/25%）0ms 降级（nowCalls=2）；50×50/500（2500 格/20%）不门控，真实 800ms 预算下 **0/10 降级、avg 346ms / max 692ms**；高级局仍 0/10 降级 |
| 追加：`StatsManager.selectBucket` | 已修（android-app 请求） | 只设置 `lastLevel/lastBucket`、不动计数与存储（`StatsManager.kt:200`），供 `Snapshot` 恢复对局后对齐统计桶 | `selectBucket` 两测试（自定义/预设） |

**二次对齐记录（2026-10-27，Lead 指出的跨端口径漂移）**
- 旧实现：`cells >= 1600 || density > 0.30` → 2 次尝试（会把 50×50/500 误门控，吃掉求解器提速收益）。
- 现状：`cells >= 900 && density >= 0.25` → 2 次尝试，常量 `HOPELESS_MIN_CELLS=900 / HOPELESS_DENSITY=0.25 / GATE_MAX_ATTEMPTS=2`，与桌面 `src/config.js:51-58`、`src/generator.js:116-123` 一致；`ParityTest` 增加对应断言。
- 回归测试：`GameEngineTest` 新增两条（40×40/400 被门控且 nowCalls=2；50×50/500 不被门控、固定种子下成功）。

**未做/说明**
- L15 已补：8 邻域遍历抽为 `Neighbors` 公共 helper，非极限热路径全部改用它；`GameEngine.floodFill` 与 `Solver` 的内部展开按性能约定保留内联，KDoc 已注明原因与语义一致性。
- `MIN_EXPECTED_ATTEMPTS` 常量保留但不再参与安卓判定（KDoc 已说明），供桌面 parity 断言。
- 真机（KPA/ART）耗时仍 unverified；本文件所有耗时均为 macOS JVM 17 探针数据。

## 2. 实测记录（JVM 探针，`/tmp/coreprobe`，未改动仓库）

### 2.1 Solver 性能（M2，改前 = 旧实现，改后 = 本实现，各 200 块随机盘）

| 配置 | 改前 | 改后 | 提升 |
|---|---|---|---|
| 16×30/99 全部盘 avg / p50 / max | 2.52ms / 2.18ms / 10.34ms | **0.40ms / 0.32ms / 2.43ms** | ≈6.3× / 6.8× |
| 16×30/99 可解盘 avg | 5.65ms | **1.03ms** | ≈5.5× |
| 50×50/500 全部盘 avg / p50 / max | 27.95ms / 39.50ms / 72.90ms | **4.35ms / 5.21ms / 10.34ms** | ≈6.4× / 7.6× |
| 50×50/500 可解盘 avg | 56.89ms | **7.84ms** | ≈7.3× |

### 2.2 Solver 语义不变性（与 HEAD 版 `src/solver.js` 逐盘对比）

- 4 种配置 × 60 块随机盘（9×9/10、12×20/45、16×30/99、50×50/500，各取棋盘中心为首点）：
  **240/240 `solve()` 布尔结果一致，diff=0**（参考实现为 `git show HEAD:src/solver.js`，避免桌面端同轮改动干扰）。

### 2.3 无猜生成（M1 门控 + 预算修复后）

门控判定（与桌面一致）：`cells >= 900 && density >= 0.25` → 最多 2 次尝试。

| 配置 | 结果 |
|---|---|
| 9×9/10、16×16/40、16×30/99 | 0/10 降级（高级局 avg≈0–2ms，最慢 51ms） |
| 9×9/68（密度 84% 但 81 格 < 900） | 10/10 降级（不受 M1 门控影响；600 次尝试内未命中可解局） |
| 30×30/500（≥900 格且密度 56%）、50×50/2125（密度 85%） | 10/10、5/5 降级，均 <3ms（门控，不再白等） |
| 40×40/400（1600 格、密度恰 25%） | 5/5 降级，**0ms**（改前需白等 ~800ms；now 调用=2 证明只试 2 次） |
| 50×50/500（2500 格、密度 20% < 25%） | **不门控**：真实预算 800ms 下 10/10 成功（fallback=0），avg **346ms** / max 692ms；桌面同配置 10/10 avg 164ms（求解器实现差异，均远低于预算） |

> 预算比较修复：`now()` 返回纳秒，旧实现在 M1 重写草稿中曾误用 `elapsed >= budgetMs`（把毫秒当纳秒）导致高级局提前降级；已改为 `>= budgetMs * 1_000_000L`，测试 `planMines 假时钟预算耗尽后提前降级` 锁定。

### 2.4 测试结果

- `:core:test`：**114 项，0 失败 0 错误 0 跳过**（GameEngineTest 48 + SolverTest 12 + StatsManagerTest 21 + KeyBindingsTest 9 + MovementTest 9 + SynthTest 6 + ParityTest 6 + NeighborsTest 3）。
- 构建产物：`core/build/test-results/test/*.xml` 时间戳 2026-10-27；`BUILD SUCCESSFUL`。
- android-app 已按最终签名适配，`:app:assembleDebug` 与 `:app:testDebugUnitTest`（15 项）由其确认通过。

## 3. 接口变更（已同步 android-app）

```kotlin
// GameEngine
class MinePlan(val rows: Int, val cols: Int, val safeRow: Int, val safeCol: Int,
               val mines: BooleanArray, val noGuessFallback: Boolean)
fun planMines(rows, cols, totalMines, safeRow, safeCol, noGuess,
              now: () -> Long = System::nanoTime, random: Random = Random.Default,
              budgetMs: Long = 800L, maxAttempts: Int = 600): MinePlan
fun applyPlan(plan: MinePlan): Boolean            // ← 原 Unit
fun applyPlanAndReveal(row, col, plan): Boolean   // 失败 false 走同步回退
fun hasPendingMines(): Boolean
val dirty: MutableSet<Int>                        // row*cols+col
fun consumeDirty(): IntArray
var wonByFlags: Boolean                           // private set
fun snapshot(): Snapshot; fun restore(snapshot: Snapshot)

// StatsManager
const val STORAGE_KEY = "stats.v2"
fun bucketOf(level, custom): String               // companion
fun selectBucket(level, custom = null)            // 恢复存档对局时对齐桶
fun onGameStart(level, custom = null)
fun onGameWin(time, bbbv = 0, durationMs = -1L, pausedMs = 0L, wonByFlags = false)
fun onGameLose(time, durationMs = -1L, pausedMs = 0L)
fun bestTimeOf(bucket); fun getBuckets(); val bestTime/bestBbbvPerSec: Map（只读副本）
var lastBucket: String?（private set）；HistoryEntry + paused/wonByFlags

// Synth
const val SFX_VERSION = 2
```

**口径说明**：`onGameWin` 的 `time` 传引擎整秒（`engine.timer`），`durationMs` 传真实毫秒。core 按 FIX-PLAN §2.4/§3.5 规则：`time<=0 && durationMs<=0` 才不更新最快；`time=0 && durationMs>0` 属真实 <1s 通关，允许记录（0:00）。
