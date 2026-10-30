# 安卓 core 引擎审查

> 范围：`android/core` 全部 Kotlin 源码（main 10 个文件 / test 6 个文件）、`android/core/build.gradle.kts`、`android/build.gradle.kts`、`android/gradle/libs.versions.toml`、`android/settings.gradle.kts`、`.github/workflows/android.yml`；对照桌面 `src/game.js` / `solver.js` / `generator.js` / `stats.js` / `keybindings.js` / `movement.js` / `config.js` / `audio.js` 逐方法核对。UI 层（`android/app`）不在本报告结论范围内，仅在跨层调用链处引用。
> 基线：HEAD `9c92fda`（工作树干净）；`android/` 自 `af30620`（2026-10-12）后无源码改动，`android/core/build/` 产物与当前源码一致。
> 方法：全文通读 + 与桌面逐方法对照 + `:core:test` 复跑（69 项）+ `/tmp` 只读探针（对 `core/build/classes` 编译 Java 调用，未改动仓库任何文件）。旧审查 `docs/audit-2026-10-12.md` 中已修复的条目统一记入「已核实项」，不再重复计缺陷。

## 结论摘要（≤10 行）

1. **无 P0**：8 个核心方法与桌面逐分支等价，69 项单测在 2026-10-27 复跑（重编译）全绿；`planMines`/`Solver` 无静态可变状态，8 线程并发生成探针 0 错误——跨线程模型是安全的。
2. 遗留风险集中在「生成器 API 契约」与「无猜边界路径」，而非游戏逻辑：1 个 P1、4 个 P2、8 个 P3。
3. **P1（AC-1）**：无猜首点若先插旗再点格子，会绕过 app 的异步生成分支，`planMines` 回落到 UI 线程（最坏约 0.1–0.8s 冻结）。桌面 `ui.js` 用同一判定条件，属两端共有的遗留问题。
4. **P2×4**：① `MinePlan` 不带几何/首点信息、`applyPlan(AndReveal)` 不校验——探针可让"首点安全"承诺被静默打破（首点踩雷/越界崩溃）；② `planMines` 参数校验不完整、异常类型不一、`rows*cols` Int 溢出未防；③ 无猜时间/随机源不可注入（桌面 `generator.js` 可注入），降级路径无法回归；④ 0 雷盘 `checkWin()` 在 `ready` 即 true、插旗两次（旗→问号）即判胜（两端共有，UI 下限 1 雷不可达）。
5. **P3**：`dirty`/`floodFill` 的 `Pair` 装箱（实测 `markAllDirty` 50×50 56µs/2500 个 Pair）；Solver 每步全盘 `collect`（50×50 单次 solve 30.9ms，直接压低大棋盘无猜成功率）；`StatsManager` 多键非原子写 + 0 秒纪录 + 公共可变 Map + 难度表重复；`Synth.noise` 包络与 `audio.js` 偏差（约 10× 偏响）；`onStateChange` 等死代码；测试缺口；CI 只跑 `:core:test`、无 lint/app 测试、工具链 2024 版。
6. 引擎可继续开发的方向：无猜预算自适应、求解器枚举升级（顺带产出"提示"功能与更高无猜成功率）、3BV 效率曲线、种子回放/每日挑战、教学盘（0 雷）。

## 发现清单（P0→P3）

> 严重度口径：P0 = 稳定崩溃/死局/数据丢失；P1 = 可稳定触发的明显缺陷或冻结；P2 = 有明确影响但非阻塞，应修；P3 = 优化/加固/可维护性。每条给当前 `file:line` 证据与 verified/unverified。

### [AC-1] 无猜首点存在同步 UI 线程生成路径：先插旗再首点会冻结界面（P1）

- **类型**：缺陷 / 性能（UI 线程阻塞）
- **证据**：
  - `GameEngine.reveal` 在未布雷时同步执行 `placeMines` → `planMines`：`android/core/src/main/kotlin/com/wpiaopiao/saolei/core/GameEngine.kt:343-346`、`:246-248`；无猜上限 600 次尝试 / 800ms 预算（`:28-29`）。
  - app 的异步分支只认 `state == "ready"`：`android/app/src/main/java/com/wpiaopiao/saolei/GameView.kt:588-596`；但插旗会先把状态置为 `playing`（`GameEngine.kt:414`），此后首点必然走 `GameView.kt:595` 的同步 `engine.reveal`。
  - 桌面同条件：`src/ui.js:252-258`（`canGenerateAsync()` 要求 `state === 'ready'`）+ `ui.js:320-324`。**不是移植偏差，是两端共同的遗留路径**；旧审查 P1-1 的修复只覆盖了"首点即翻开"主路径。
  - 实测生成耗时（JVM，见「测试与实测记录」）：50×50/500 无猜首局 102–169ms、专家冷启动 79–154ms、预热后专家 p50 17–22ms/p99 149ms；预算耗尽路径理论上限 800ms，ART/低端机更慢。verified（代码路径 + 计时）；真机耗时 unverified。
- **影响**：用户"先插旗再点第一格"时，无猜雷局在 UI 线程同步生成：专家局约 0.02–0.15s 卡顿，50×50 自定义最长 0.1–0.8s；期间无"生成中"提示（`generating` 未置位）、输入事件被丢弃，与异步路径体验不一致。
- **建议**：`GameView.revealAt` 的异步判定改为 `engine.noGuess && !engine.minesPlaced`（不让 `state` 参与）；异步失败回退同步时保留兜底；core 侧在 `placeMines`/`reveal` KDoc 标注"可能执行最长 800ms 生成，UI 线程应避免直接调用"，或新增 `GameEngine.hasPendingMines()` 辅助判定。桌面 `ui.js::canGenerateAsync` 同步修（1 行）。
- **工作量**：S

### [AC-2] `MinePlan` 不携带几何/首点信息，`applyPlan`/`applyPlanAndReveal` 不校验，误用会静默错盘或首点踩雷（P2）

- **类型**：缺陷（API 契约 / 正确性）
- **证据**：`MinePlan` 只有 `mines`/`noGuessFallback` 两个字段（`GameEngine.kt:17`）；`applyPlan` 只判 `minesPlaced`（`:251-258`），`applyMines` 用当前 `rows/cols` 直接索引 `mines[r*cols+c]`（`:261-267`）；`applyPlanAndReveal` 的 5 条竞态守卫（`:355-360`）**没有一条校验"计划是为哪个首点生成的"**。只读探针实测（verified）：
  - 按首点 (4,4) 生成的普通计划，应用到 (0,0) 且该格是雷 → `applied=true`、`state=lost`、`exploded=true`：**首次点击直接炸死，"首点 3×3 安全"承诺被静默打破**；
  - `applyPlan(MinePlan(BooleanArray(3)), ...)` → `ArrayIndexOutOfBoundsException`；
  - `applyPlan` 长度 100 的计划（棋盘 81）被静默接受（无长度校验）；
  - 跨尺寸误用（9×9 计划应用到 16×16）会越界读取或把不同几何的雷位错位写入——桌面版对短数组是"缺项按无雷"静默处理，**两端行为还不一致**（`src/game.js:142-159`）。
- **影响**：当前 app 靠 `generationSeq`（`GameView.kt:610-643`）与 `minesPlaced` 守卫规避，暂时不可达；但 core 是公共 API，任何新调用方（桌面/安卓复用、测试、未来异步导航）只要把"哪个计划对应哪次点击"做错，就会破坏引擎最核心的保证且无任何报错——属于"一旦新入口出现即 P0"的契约漏洞。旧审查同提醒过 C1 类问题，此条为生成器拆分（§11）后新引入的契约缺口。
- **建议**：把安全区与几何纳入计划：`class MinePlan(val rows: Int, val cols: Int, val safeRow: Int, val safeCol: Int, val mines: BooleanArray, val noGuessFallback: Boolean)`；`applyPlan` 里 `require(plan.rows == rows && plan.cols == cols && plan.mines.size == rows*cols)`；`applyPlanAndReveal` 增加 `plan.safeRow == row && plan.safeCol == col` 校验，不匹配返回 `false`（走既有回退路径）。桌面 `applyPlan` 对应增加 `plan.rows/cols` 与长度防御。
- **工作量**：S–M（core 约 20 行 + app 构造点同步 + 桌面 `generator-worker` 回传字段）

### [AC-3] `planMines` / `initCustom` 边界校验不完整：越界首点静默无安全区、异常类型不一、尺寸溢出未防（P2）

- **类型**：缺陷（输入校验 / 健壮性）
- **证据**：`planMines` 入口只校验 `rows/cols >= 1` 与 `totalMines >= 0`（`GameEngine.kt:98-99`），随后：
  - `safeRow/safeCol` **未校验**：`randomize` 用 `Math.abs(r-safeRow)>1` 判定安全区（`:44`），首点越界时所有格子都进候选，**普通模式静默产出一个没有 3×3 安全区的雷局**——与 KDoc「首点 3×3 安全区永不布雷」矛盾；`noGuess=true` 时 `Solver.solve` 的 `revealIdx(startRow*cols+startCol)` 直接抛 `ArrayIndexOutOfBoundsException`（探针实测：`Index 22 out of bounds for length 9`）。
  - 雷数超可用格由 `randomize` 抛 `IllegalStateException`（`:49-51`），与 `initCustom` 的 `require`→`IllegalArgumentException`（`:215`）契约不一致；`GameEngineTest.kt:355-358` 也只覆盖 `IllegalArgumentException`。
  - `BooleanArray(rows * cols)`（`:100`）用 Int 乘法：探针 `planMines(46341, 46341, 0, ...)` → `NegativeArraySizeException`。`initCustom` 已用 `Long` 防溢出（`:214`），`planMines` 没有等价防护。
  - `initCustom` 无棋盘规模上限（`:208-222`）：合法参数如 `rows*cols` 极大时 `resetRound()`→`createBoard()`（`:224-238`）直接 OOM。app UI 把行列限制在 9..50（`GameView.kt:1020-1034`）所以不可达，但 core 是公共 API。（verified：前三项探针实测；`initCustom` OOM 一项为代码推理，未实跑）
- **影响**：防御性缺失；当前 app 的调用点（`GameView.kt:610-622`）坐标均已在界内，所以不可达。异常类型不一致会让测试与上层错误处理不稳定；未来若 core 直接接受自定义/存档输入（例如"导入雷局编号"），这里就是崩溃面。
- **建议**：入口统一 `require`：`safeRow in 0 until rows && safeCol in 0 until cols`、`totalMines <= rows*cols-9`（与 `initCustom` 同口径，或用实际候选数校验）、`rows.toLong()*cols <= Int.MAX_VALUE`、`rows.toLong()*cols <= MAX_CELLS`（如 10_000，与 app 50×50 上限对齐）；超限统一抛 `IllegalArgumentException`。
- **工作量**：S

### [AC-4] 无猜生成的时间源/随机源不可注入，降级路径与提前降级不可测（P2）

- **类型**：测试 / 可维护性（可测试性偏差）
- **证据**：`planMines` 硬编码 `System.nanoTime()` 与 `kotlin.random.Random`（`GameEngine.kt:107,114,53`）；桌面 `generator.js:96-104` 明确提供 `now = Date.now, random = Math.random` 注入参数，且 `test/generator.test.js` 用假时钟验证"提前降级不空等"。安卓侧因此无法确定性测试：`noGuessFallback=true` 路径、`MIN_EXPECTED_ATTEMPTS` 提前降级、600 次尝试上限全部零覆盖（`SolverTest.kt`/`GameEngineTest.kt` 只断言成功路径，`SolverTest.kt:58,69,84`）。（verified：读码对照桌面注入参数）
- **影响**：无猜降级是本项目对外承诺的关键行为（README/旧审查 P1-7）；现在只能靠"跑一次高级局恰好成功"这种随机断言间接相信它。实测 30×30/500 与 50×50/500 各 100% 降级（JVM），但没有任何回归测试锁住"预算/尝试上限/降级标记"的语义，后续调参（AC-7 建议会动它）没有安全网。这也是两端可调参数无法自动对齐的根因（桌面 `test/generator.test.js:80-84` 只断言 JS 侧常量）。
- **建议**：增加测试可见的注入重载：`internal fun planMines(rows, cols, totalMines, safeRow, safeCol, noGuess, now: () -> Long = System::nanoTime, random: Random = Random.Default)`（公开签名保留委托）。补三类用例：假时钟验证提前降级；`noGuess=true` 且随机源固定时降级；尝试上限 × 单次耗时预算的行为。顺带可在降级时通过返回值/回调暴露已尝试次数，供 UI 显示"生成中（第 N 次）"。
- **工作量**：S

### [AC-5] 0 雷盘状态机漏洞：`checkWin()` 在 `ready` 即返回 true，插旗两次未翻开任何格即判胜（P2）

- **类型**：缺陷（状态机 / 边界）
- **证据**：`checkWin`（`GameEngine.kt:506-518`）先算 `totalSafe = rows*cols - totalMines`，再在 `flagCount == totalMines` 时逐格比较 `flagged == mine`。当 `totalMines == 0`：`flagCount` 恒为 0、所有格 `flagged == mine == false` → **直接返回 true**；`toggleFlag` 在第二次切换（旗→问号、`flagCount` 归 0）后调用 `checkWin()` 即置 `won`（`:409-429`）。探针实测（verified，`initCustom(9,9,0)`）：
  - `reveal` 之前直接 `checkWin()` = true；
  - 插旗两次：`state=won`、`revealedCount=0`、`flagCount=0`；
  - 桌面 `game.js:388-401` 同一实现 → 两端共有，非移植偏差。
- **影响**：Android/桌面 UI 的自定义下限都是 1 雷（`GameView.kt:1022,1034`；`src/app.js:200`），当前不可达；但 core 公共 API 允许 `mines=0` 且已有测试覆盖"退化但合法"的 `initCustom(1,1,0)`（`GameEngineTest.kt:213-221`），任何"0 雷教学盘/练习盘"新玩法一接就会命中"不动即胜"。同时它让 `checkWin()` 的语义在 `ready` 状态失去意义（公共方法）。
- **建议**：旗子判胜分支加非空前提：`if (totalMines > 0 && flagCount == totalMines)`；`checkWin` 在 `!minesPlaced` 或 `revealedCount == 0` 时直接返回 false（更贴合"胜利 = 至少完成一次有效操作"）。补一条 0 雷盘回归测试（reveal 前 `checkWin()==false`、插旗三态不判胜、首次翻开即胜）。两端同步修。
- **工作量**：S

### [AC-6] `dirty` 用 `Set<Pair<Int,Int>>`、`floodFill` 用 `ArrayDeque<Pair>`：仍在热路径分配装箱对象（P3）

- **类型**：性能（分配）
- **证据**：`GameEngine.kt:166`（`val dirty = mutableSetOf<Pair<Int, Int>>()`）、`:188-190`、`:192-194`、`:383`（`ArrayDeque<Pair<Int, Int>>`）；app 每次操作都会遍历 `engine.dirty` 两遍并清空（`GameView.kt:423-440`），不是只读缓存。实测（50×50）：`markAllDirty()` 最优 56µs/2500 个 Pair；`applyPlan` 与 `revealAllMines/Safe` 每次全量 2500 Pair；首点洪泛按翻开格每格 1 个 Pair（最坏 2500 个）+ 栈上邻居 Pair。小棋盘/普通对局可忽略（单格点击通常 <10 Pair），但大棋盘首点/终局是短命分配峰值。（verified：计时探针）
- **影响**：GC 压力的量级不大（2500×~24B ≈ 60KB/次），但旧审查 §4.2 已点名"安卓 `GameView` 刻意用 `r*cols+c` 避免 Pair，core 反而没做"，这次复核**仍然存在**且 app 已经消费 dirty（不再是"被局部重绘遮蔽"）。另外 `dirty` 是公共可变 `MutableSet`，语义上应由 core 提供 `consumeDirty(): IntArray`，减少 UI 对内部结构的耦合。
- **建议**：`dirty` 改 `MutableSet<Int>`（`r*cols+c`）或位图+计数；`floodFill` 改 `ArrayDeque<Int>`（同 `compute3BV` 的写法，`:282`）；给 core 增加 `consumeDirty()`/`dirtyIndices()` 返回副本并清空。需 app `consumeDirty` 同步改（UI 成员配合，一处循环）。
- **工作量**：S

### [AC-7] 求解器每步全盘 `collect()` + 每约束新建集合，单次 solve 30ms 级，直接限制大棋盘无猜成功率（P3）

- **类型**：性能
- **证据**：`Solver.kt:83-114` 每步重新扫描全盘已翻开格；每个约束分配 `ArrayList<Int>`、`IntArray` 和 `HashSet<Int>`（`:110`）；子集规则 `sortedBy`（`:134`）与 `filter`（`:148`）继续分配；`solve` 每次尝试新建 `known` 二维数组（`:40`）。实测（JVM，200 局平均）：16×30/99 单次 1.25ms；50×50/500 单次 **30.86ms**。而 50×50/500 单块盘可解率仅 4/200（2%，期望 50 次尝试）→ 800ms 预算内只能尝试 ~26 次，**结构上必然降级**（实测 5/5 降级）；30×30/500 可解率 0/200，也必然降级（10/10，~57ms 内由尝试上限/提前降级结束）。（verified：实测 solve 计时与降级率）
- **影响**：当前无猜生成在后台线程（旧审查 §11 已修），所以不冻结 UI；但"大棋盘无猜 = 必然降级"意味着招牌功能在自定义大盘上不可用（只剩 HUD 警告）。这是算法成本 + 预算共同决定的结果，属可优化空间而非缺陷。
- **建议**（两项可独立）：① 低风险：复用 `known`/`unknown`/`constraint` 缓冲、用 `LongArray` 位集替代 `HashSet<Int>`、子集判定改为按位与、按"边界格增量更新"而不是每步全盘 `collect`（预计单次 solve 2–5×）；② 高收益：对边界未知集做小规模枚举（≤2^20 组合）判定可解性，把高级局单次可解率从 6.5% 提升到接近理论上限，可同时缩短"生成中"时间。若做 ②，AC-4 的注入时钟就更有必要（调参会改变降级率）。
- **工作量**：M

### [AC-8] `StatsManager` 多键非原子写、公开可变 Map、难度表三处重复（P3）

- **类型**：架构 / 可维护性 / 健壮性
- **证据**：
  - `onGameWin` 最多拆成 7 次独立存储写：`StatsManager.kt:134`（bestTime）、`:143`（bestRate）、`:147`（history）、`:150-153`（4 个计数）；`onGameStart` 2 次（`:121-122`）。`Storage` 实现 `PrefsStorage.kt:9-15` 每次 `putString/remove` 都是独立 `edit().apply()`。桌面版是**单 JSON 一次写入**（`src/stats.js:54-56`），一次事件只有一次落盘。探针：手动删除 `stats.gamesWon` 后重建，得到 `totalGames=2 / gamesWon=0`，无任何一致性校验或修复（只有读默认值兜底）。（verified：探针 + 代码）
  - `bestTime`/`bestBbbvPerSec` 是 `public val mutableMapOf`（`StatsManager.kt:45,48`），外部可绕过持久化直接改内存值（app 目前只读，`GameView.kt:561`）。
  - `TIME_LEVELS = listOf("beginner","intermediate","expert","custom")`（`:27`）与 `GameEngine.LEVELS`（`GameEngine.kt:20-24`）重复；新增/改名难度会静默漏统计，且无守护测试（桌面侧有 `test/generator.test.js:80-84` 的常量断言，安卓侧没有）。
- **影响**：进程在两次写之间被杀（低概率）会产生"总局数 +1 但胜场未 +1"式的脏统计；SharedPreferences 的 `apply()` 内存态一致、落盘异步，所以只在崩溃/强杀窗口可见，属数据完整性的边缘风险。公开可变 Map 属封装漏洞。难度表重复是已知跨端手工同步风险的同类问题在 core 内部的重演。
- **建议**：给 `Storage` 加批量写接口（如 `edit { put(...); remove(...) }`，SharedPreferences 侧一次 `edit().apply()`），或把统计整体编码成单个 key（与桌面 JSON 对齐，换来迁移/导出能力）；`bestTime`/`bestBbbvPerSec` 对外返回 `Map`（只读），内部保留可变；`TIME_LEVELS` 改为 `GameEngine.LEVELS.keys + "custom"` 或共享常量，并在 `core` 加一条"统计难度集合 == 引擎难度集合 + custom"的单测。
- **工作量**：M（迁移需读旧键，建议保留一次"旧键→新键"兼容读取）

### [AC-9] 「0 秒纪录」：`bestTime` 更新缺少 `time > 0` 守卫，与历史记录条件不一致（P3）

- **类型**：缺陷（统计口径 / 数据）
- **证据**：`onGameWin` 里最佳时间更新没有正数检查（`StatsManager.kt:131-136`），而紧接着的历史记录却要求 `time > 0`（`:138-148`）——同一方法内两套口径。探针：`onGameStart("custom")` + `onGameWin(0, 45)` → `bestTime.custom = 0`、`history=0`；随后 30s 胜利仍保持 0（"0:00 纪录"永久不可破）。桌面 `stats.js:71-79` 同样未守卫 → 两端共有。该路径**当前可达**：自定义 9×9/1 雷首点即胜率实测 112/200（56%），`engine.timer` 为整秒，首秒内通关即写 0。（verified：探针）
- **影响**：低雷密度自定义盘（以及未来 0 雷教学盘）很容易把该难度"最快时间"钉死在 0，统计面板与"新纪录"横幅失去意义（`GameView.kt:559-567` 会提示"新纪录！0:00"）；同时说明"胜利时间"口径没有统一守卫。
- **建议**：最佳时间更新加 `time > 0`（或与历史记录共用同一资格判定）；`engine.timer` 为 0 的胜利在统计层可记为"未计时"（不参与最快时间但计入胜场/连胜）。两端同步修，并加一条 `onGameWin(0, …)` 回归测试。
- **工作量**：S

### [AC-10] 死代码与过宽公共面：`onStateChange` 零引用、`KeyCodes` 五个常量零引用、`dirty` 公共可变（P3）

- **类型**：可维护性
- **证据**（verified，全仓 grep 计数）：
  - `GameEngine.onStateChange` + `stateListeners`（`GameEngine.kt:168,176-184`）**0 处调用**（app 中只有同义注释 `GameView.kt:532`）；回调无注销 API、`MutableList` 非线程安全——万一将来从后台线程接入就是数据竞争面。
  - `KeyCodes.DPAD_CENTER/BUTTON_L1/BUTTON_R1/BUTTON_L2/BUTTON_R2`（`KeyCodes.kt:13,18-21`）与 `KeyNames`（`KeyNames.kt:11,16-19`）对应分支 **0 处引用**（默认键位只用 8 个动作）。
  - `markDirty`/`countAdjacentMines` 是 public 但 app 0 引用（仅内部/测试用）。
  - `dirty` 为 public `MutableSet`（`GameEngine.kt:166`），app 直接 `engine.dirty.clear()`（`GameView.kt:439,523`）——引擎内部记账结构被 UI 直接修改。
- **影响**：误导新读者（回调看似成对的订阅模型，实际被 app 的 `syncState` 轮询替代）；`KeyCodes` 常量被 `KeyNames` 映射但永远打不出来，属"文档性常量"；`dirty` 的公共可变性让 core 无法演进（AC-6 想改 Int 编码就要动 app）。
- **建议**：删除 `onStateChange`/`stateListeners`（或真的接入 app 的 `syncState` 并补 `removeOnStateChange`）；`KeyCodes`/`KeyNames` 按实际可绑定键收窄或标注"预留"；把 `dirty` 改为 `private val` + `fun consumeDirty(): List<Int>`/`dirtyIndices()`（与 AC-6 合并做）。
- **工作量**：S

### [AC-11] `Synth.noise()` 缺少 `audio.js` 播放路径的指数增益包络：爆炸音约 10× 偏响（P3）

- **类型**：缺陷（音效保真）/ 文档
- **证据**：桌面 `playNoise`（`src/audio.js:31-48`）是 白噪声 × 线性衰减 `(1 - i/bufferSize)` 作为 **buffer**，再经 Web Audio `gain` 从 `volume` 指数 ramp 到 `0.001`（`:42-44`）——实际是"线性 × 指数"双衰减。`Synth.noise`（`Synth.kt:36-48`）只有 `volume * (1 - t/duration)`，没有指数因子；KDoc 却写"对齐 audio.js playNoise"。按参数（`explosion`: duration=0.4, volume=0.4）算：t=0.2s 处 JS 增益 = 0.4·(0.001/0.4)^0.5 = **0.02**，Kotlin = 0.4·0.5 = **0.2**，相差 10 倍；`Synth.tone`（`:14-33`）的指数包络则与 Web Audio 语义一致（verified by code + 算术，未做听感/频谱验证）。
- **影响**：爆炸音后半段明显更响、更"糊"，与其余 5 个音效（tone 类）的听感比例失衡；`Synth` 零测试，参数回归无保护。改合成参数时还要注意 app `SoundManager` 以固定文件名缓存 WAV（旧审查 §6.2），已安装设备不会自动更新——需要同步 bump 缓存文件名/版本（app 侧）。
- **建议**：`noise` 的包络补 `Math.exp(Math.log(0.001 / volume) * t / duration)` 因子（与 `tone` 共用同一条 `envelope()` 私有函数）；补 `SynthTest`：非空/样本范围/峰值单调/包络端点 ≈ volume 与 0.001；改音效时 bump 缓存版本号。
- **工作量**：S

### [AC-12] 测试覆盖缺口：子集规则、降级路径、失败分支、跨端常量守护均无 Android 用例（P3）

- **类型**：测试
- **证据**（verified：读码 + 探针）：
  - **Solver 子集规则**：桌面有专测 `test/solver.test.js:55-65`（3×4、雷在 (0,0)/(0,2)、首点 (2,1)）；`SolverTest.kt` 9 项里没有。探针用同一棋局跑 Android `Solver.solve` → `true`，说明**规则实现正确，仅缺回归测试**（该规则是无猜承诺的核心）。
  - **降级路径**：`SolverTest.kt:58,69,84` 与 `GameEngineTest.kt:307,323,351` 只断言 `noGuessFallback == false`；`fallback == true`、`MIN_EXPECTED_ATTEMPTS` 提前降级、600 次上限全无（根因见 AC-4）。
  - **失败分支**：`applyPlanAndReveal` 的 5 条前置守卫（`GameEngine.kt:356-360`）只测了成功路径（`GameEngineTest.kt:327-339`）；`applyPlan` 畸形/错尺寸 plan、`initCustom` 的 `rows*cols` 溢出边界无测试（AC-2/AC-3 的探针就是证据）。
  - **零测试模块**：`Synth`（116 行，无 `SynthTest.kt`）；`StatsManager` 无坏键/部分写入容错用例（AC-8/AC-9 只能靠探针）；`KeyBindings` 无重复键码持久化用例。
  - **跨端常量守护**：桌面 `test/generator.test.js:80-84` 断言了 `NO_GUESS` 常量，Android 侧没有对应断言（`GameEngineTest` 的 9/9/10 是硬编码断言，不构成跨端对照）。
  - **墙钟/随机**：`SolverTest.kt:50-77` 依赖随机首点 + 800ms 预算。实测专家无猜 0/120 降级、预热后 p50 22ms，**实践中稳定**，但慢 CI/冷 JVM 仍是理论 flaky 点；配合 AC-4 注入时钟可根治。
- **影响**：无猜是项目主打的"不猜"承诺，其核心推理规则与降级路径都没有 Android 回归测试；后续任何求解器/生成器优化（AC-7）都缺少安全网。
- **建议**（按性价比排序）：① 移植桌面子集规则用例（10 行）；② 加降级/上限用例（依赖 AC-4 的注入）；③ 补 `applyPlanAndReveal` 5 条失败分支 + 0 雷盘（AC-5）用例；④ 加 `SynthTest` 基本样本断言；⑤ 在 `core` 测试里断言"统计难度集合 = `GameEngine.LEVELS.keys + custom`"（AC-8）。
- **工作量**：S–M

### [AC-13] 工程与 CI：只跑 `:core:test` + `assembleDebug`，无 lint/app 测试，工具链停在 2024 版本（P3）

- **类型**：工程 / 可维护性
- **证据**：`.github/workflows/android.yml:26`（仅 `./gradlew :core:test`）、`:29`（仅 `:app:assembleDebug`），无 `:app:lintDebug`、无 app 单测/仪器测试（`android/app/src` 只有 `main`，无 `test`/`androidTest`）；仓库无 ktlint/detekt/kover 配置。工具链：Kotlin 1.9.24 / AGP 8.5.2（`android/gradle/libs.versions.toml:2-4`）、compileSdk/targetSdk 34（`android/app/build.gradle.kts:11,20-21`）、JUnit 4.13.2（verified：配置读码）。`android/settings.gradle.kts:10-15` 的 `FAIL_ON_PROJECT_REPOS` 与 `core/build.gradle.kts:1-11`（纯 JVM、JUnit 唯一依赖、jvmToolchain 17）是健康配置。
- **影响**：core 69 项测试覆盖的是引擎；占代码量约一半的 `app` 层（绘制/触摸/状态同步/统计接线）完全在自动化之外——旧审查发现的 `isVirtual`、触摸、渲染类问题都只能靠人工。工具链落后两代意味着新 Android 版本兼容性验证与安全补丁缺失（KPA 侧载可接受，但升级成本会随时间上升）。
- **建议**：CI 增加 `:app:lintDebug`（至少作为非阻塞产物）与 `:core:test` 之外的最小 app 逻辑测试（可先把纯函数抽到 core）；加 ktlint/detekt（增量方式）；规划 targetSdk 35+ / Kotlin 2.x / AGP 8.7+ 升级（一次性小改造，避免拖成大迁移）。
- **工作量**：S（CI）/ M（工具链升级）

## 与桌面的等价性核对表（核心方法 × 一致/偏差）

| 方法/常量 | 桌面实现 | 安卓实现 | 结论 |
|---|---|---|---|
| 布雷 `randomize` | `generator.js:41-60` Fisher-Yates、安全区 3×3、候选不足抛错 | `GameEngine.kt:35-59` 同算法/同守卫 | **一致**；偏差：安卓随机源不可注入（AC-4） |
| 无猜生成 `planMines` | `generator.js:101-131`；`now/random` 可注入、scratch 复用 | `GameEngine.kt:94-121`；`System.nanoTime()`/`Random.Default`、每次调用新建临时盘 | **逻辑/常量一致**（600/800/20 三值与 `config.js:40-43` 相同）；测试性偏差（AC-4）、线程安全优于桌面（无静态 scratch） |
| 3BV `compute3BV` | `game.js:165-207` | `GameEngine.kt:280-321` | **一致**（0 区域计 1 + 未覆盖数字格各 1）；两侧均有针对性用例 |
| 翻开 `reveal` | `game.js:224-234` | `GameEngine.kt:338-348` | **一致**（终局/越界/已翻开/已插旗守卫 → 首次布雷 → playing → finishReveal） |
| 洪泛 `floodFill` | `game.js:268-292` 显式栈、清 questioned、跳过旗/雷 | `GameEngine.kt:382-407` 同遍历（LIFO 顺序一致） | **一致**；安卓多 `Pair` 分配（AC-6） |
| 插旗 `toggleFlag` | `game.js:294-312` 三态循环 + checkWin | `GameEngine.kt:409-429` | **一致**（含 ready→playing）；0 雷边界漏洞两端共有（AC-5） |
| 和弦 `chord` | `game.js:314-343` 旗数匹配才展开、lost 早退、win | `GameEngine.kt:432-461` | **一致** |
| 胜负 `checkWin` / 终局辅助 | `game.js:347-401`（含 revealAllMines/Safe、autoFlag、旗子判胜） | `GameEngine.kt:465-518` | **一致**（含"插对全部旗即胜"的桌面设计取舍）；0 雷边界两端共有（AC-5）；`revealedCount` 语义同为"翻开的安全格数" |
| 求解器 `Solver` | `solver.js:32-198` 单点/子集/全局三规则 + 20000 步守卫 + 终局全盘校验 | `Solver.kt:32-202` 逐句对应（UNKNOWN/SAFE/MINE、need 矛盾处理、子集差集判定、全局规则） | **一致**；子集规则实测通过（探针），仅缺 Android 回归测试（AC-12） |
| 难度表/标签 | `config.js:13-17`（9/9/10、16/16/40、16/30/99，标签初级/中级/高级） | `GameEngine.kt:20-24` 同值同标签 | **一致**（人工核对，无自动化守护 → AC-12） |
| `NO_GUESS` / `MIN_EXPECTED_ATTEMPTS` / `HISTORY_MAX` | `config.js:40-43`、`generator.js:22`、`config.js:46` | `GameEngine.kt:28-32`、`StatsManager.kt:30` | **一致**（600/800/20/50）；桌面有常量断言，安卓无（AC-12） |
| 方向合成 `Movement` | `movement.js:28-43` | `Movement.kt:21-42` | **一致**（斜向/抵消/边界裁剪/0 尺寸退化） |
| 键位 `KeyBindings` | `keybindings.js` 字符串键、大小写不敏感冲突检测 | `KeyBindings.kt` 数值键码（物理键）、`findConflict` 数值相等 | **语义一致**（平台默认键与键码表示属合理差异）；`reset()` 均不落盘、由调用方 `save()`（app 已如此，`GameView.kt:835-836`） |
| 统计 `StatsManager` | `stats.js` 单 JSON、HISTORY_MAX=50、胜率四舍五入、3BV/s、getHistory 取最近 N | `StatsManager.kt` 同口径，存储改为前缀多键 | **业务口径一致**；存储原子性/迁移能力偏差（AC-8）；0 秒纪录两端共有（AC-9） |
| 音效 `Synth` | `audio.js` Web Audio 振荡器/噪声 | `Synth.kt` 离线 PCM+WAV；频率/波形/时长/音量参数一致，tone 包络一致 | **tone 一致**；`noise` 包络偏差（AC-11） |

## 已核实项（已修/无问题）

**旧审查已修项（本次逐条验证通过，不重复计缺陷）**
- `initCustom` 参数校验（行列≥1、雷数≥0、≤`rows*cols-9`，Long 防溢出）：`GameEngine.kt:208-222`；用例 `GameEngineTest.kt:201-221`。verified
- `reveal`/`toggleFlag`/`chord` 越界坐标与终局守卫：`GameEngine.kt:339-340,410-411,434`；用例 `GameEngineTest.kt:189-196,224-237,267-293`。verified
- `floodFill` 内 `cell.flagged = false` 死代码已删除：`GameEngine.kt:382-407`（跳过条件仍保留 `flagged`）。verified
- `Movement` 0 尺寸棋盘不再抛空区间异常：`Movement.kt:38-41`；用例 `MovementTest.kt:61-66`。verified
- `Storage.remove` + `StatsManager.reset()` 不再用空串哨兵、`bestTime`/`bestBbbvPerSec` 键集合对称、`lastLevel` 空白归一：`Storage.kt:10-11`、`StatsManager.kt:102-116,194-217`；用例 `StatsManagerTest.kt:166-192`。verified
- `KeyBindings.setKey` 拒绝负键码：`KeyBindings.kt:69-76`；用例 `KeyBindingsTest.kt:95-104`。verified
- `Synth.tone/noise/mix` 参数校验：`Synth.kt:16,37,52`。verified（代码）
- `getHistory(limit<=0)` 返回空列表：`StatsManager.kt:191-192`；用例 `StatsManagerTest.kt:194-202`。verified
- 无猜生成从 UI 线程拆出为纯函数 + 应用分离：`GameEngine.kt:87-121,250-258,355-365`；用例 `GameEngineTest.kt:295-358`；app 侧 seq 作废与回退：`GameView.kt:610-642`。verified（主路径；边界路径见 AC-1）
- `noGuessFallback` 接到 UI：`GameView.kt:1207`（core 标志本身 `GameEngine.kt:153`）。verified（app 侧）
- 静态层接入 `dirty` 局部重绘、动画格数截断：`GameView.kt:423-440`（core `dirty` 语义未变）。verified（app 侧；core 分配问题见 AC-6）

**无问题模块**
- `Cell.kt`（纯数据类）、`Storage.kt`（最小接口 + 文档）、`KeyCodes.kt`/`KeyNames.kt`（数值与 Android `KeyEvent` 一致，`KeyNames` 未知码回退合理）、`Movement.kt`（与桌面逐分支一致）、`KeyBindings.kt`（默认/加载/保存/冲突/恢复默认语义完整，损坏数据容错）。
- `GameEngine` 的游戏逻辑本体（8 个核心方法）与 `Solver` 的三条推理规则：与桌面逐句等价，未发现逻辑缺陷。
- 并发模型：`planMines`/`Solver` 只用局部变量（Kotlin 端特意不复用静态 scratch，`GameEngine.kt:105`），8 线程 × 25 次混合难度并发生成探针 0 错误；app 的 worker→`post` 回 UI 线程模型正确（`GameView.kt:610-642`）。`StatsManager`/`KeyBindings` 目前只在 UI 线程使用，无共享可变状态问题（core 未加同步，属文档缺失而非缺陷）。
- `core` 保持纯 JVM 无 Android 依赖（`core/build.gradle.kts:1-11`，唯一依赖 JUnit）；`settings.gradle.kts:10-15` 的 `FAIL_ON_PROJECT_REPOS`、release R8、签名材料未入库（`keystore/` 已被 `.gitignore` 忽略）均为健康实践。
- 统计业务口径：总局/胜率/连胜/最佳时间/3BV/s/历史最近 20 局语义与桌面一致（`StatsManager.kt:118-192` 对照 `stats.js:58-136`）。

**已确认为"设计取舍"而非缺陷（两端一致，建议仅在 README/KDoc 写明）**
- "插对全部旗即胜"（`GameEngine.kt:509-516` / `game.js:391-399`）与 WinXP 原版"必须翻开所有安全格"不同——两端一致，属既有设计。
- `revealedCount` 语义是"翻开的**安全**格数"（`revealAllMines` 不增加它），命名可改 `revealedSafeCount`。
- `bbbv` 命名（3BV → bbbv）与裸 `String` 状态（`"ready"/"playing"/"won"/"lost"`）为两端共有的风格问题；若要改枚举需两端一起动，收益中等。
- 桌面 `applyPlan` 接受跨 IPC 的 0/1 数组、安卓接受 `BooleanArray`，属平台差异；但短数组行为不一致见 AC-2。

## 测试与实测记录

**1) Gradle 复跑（当前源码）**
- 首次直接 `./gradlew :core:test` 失败：共享的 `~/.gradle` 正被另一个项目（`balanceguard`）构建占用，Gradle 8.7 wrapper 在慢速下载并持锁（`Timeout waiting for exclusive access to gradle-8.7-bin.zip`）。
- 改用仓库本地缓存（Lead 提供的方案）：`cd android && GRADLE_USER_HOME=<repo>/.gradle-user-home ./gradlew :core:test --rerun-tasks --offline --console=plain` → **BUILD SUCCESSFUL in 4s**（`compileKotlin`/`compileTestKotlin`/`:core:test` 均实际执行）。
- 结果 XML（`android/core/build/test-results/test/`，时间戳 2026-10-27T12:37Z）：`GameEngineTest 28 + KeyBindingsTest 9 + MovementTest 9 + SolverTest 9 + StatsManagerTest 14 = **69 项，0 失败 0 错误 0 跳过**`。
- 交叉验证：直接以 `java org.junit.runner.JUnitCore` 跑 `core/build/classes`（5 个测试类）→ `OK (69 tests), Time: 0.266`。

**2) 只读探针（`/tmp/coreprobe`，对 `core/build/classes` 编译 Java 调用，未改动仓库）**

| 探针 | 结果 |
|---|---|
| `planMines` 无猜 20 次/难度（首点居中） | beginner：0 降级、中位 1ms、最大 29ms；intermediate：0 降级、4ms、17ms；expert：0 降级、17ms、**最大 219ms**；均雷数正确、3×3 安全区无雷 |
| expert 无猜 100 次 | **0 降级**；p50 22ms / p90 61ms / p99 149ms |
| 冷启动（新 JVM 第一条命令）专家无猜 ×5 | 79 / 104 / 107 / 131 / 154ms，0 降级 |
| 单次随机盘可解率（200 次 `Solver.solve` 直接判定） | 9×9 167/200（83.5%，期望 1.2 次）；16×16 110/200（55%）；16×30 13/200（**6.5%，期望 15.4 次**）；30×30/500 0/200；50×50/500 4/200（2%） |
| 大棋盘无猜（每配置 5–10 次） | 30×30/500：10/10 降级，均 57ms；50×50/500：5/5 降级，均 123ms；50×50/2125：5/5 降级；9×9/68：7/10 降级；普通模式 50×50/500：<1ms |
| 并发：8 线程 × 25 次 `planMines`（beginner/expert 混合，含 noGuess） | **0 错误**（雷数与安全区全部校验通过），共 200 次 |
| 计划/首点绑定 | 按首点 (4,4) 生成的计划应用到 (0,0)（该格为雷）→ `applied=true`、`state=lost`、`exploded=true` |
| 畸形计划 | 长度 3 的 plan → `ArrayIndexOutOfBoundsException`；长度 100（>81）→ 静默接受 |
| `planMines` 非法参数 | 雷数超可用格 → `IllegalStateException`；`safeRow/safeCol` 越界：普通模式静默无安全区、noGuess → `ArrayIndexOutOfBoundsException(Index 22 …)`；`46341×46341` → `NegativeArraySizeException` |
| 0 雷盘状态机 | `initCustom(9,9,0)`：`checkWin()` 在 ready = true；插旗两次（旗→问号）→ `state=won`、`revealedCount=0` |
| 0 秒纪录可达性 | 9×9/1 雷自定义盘随机首点即胜 112/200（56%）→ `engine.timer=0` 的胜利真实可达；`onGameWin(0,45)` → `bestTime.custom=0`、history 不记录；后续 30s 胜利仍为 0 |
| 统计部分写入 | 删除 `stats.gamesWon` 键重建 → `totalGames=2 / gamesWon=0`（无一致性校验） |
| 求解器子集规则（桌面 `solver.test.js:55-65` 同一棋局） | Android `Solver.solve` → **true**（规则正确，测试缺失） |
| 50×50/500 首点洪泛 + 3BV（20 次） | 洪泛+3BV 中位 1992µs / 最大 3699µs；dirty=2500、bbbv=874（单局） |
| 50×50 `markAllDirty`（200 次取最优） | 56µs / 2500 个 Pair |
| 求解器单次 solve（200 局均值） | 16×30/99 1.25ms；50×50/500 30.86ms |
| 极小盘 | 1×1/0、3×3/0 无猜均直接 `won`、`bbbv=1`，无异常 |

**3) 局限与未验证项**
- 探针运行在 macOS JVM 17（JIT）上，**不是 KPA/ART 真机数据**；AC-1 的冻结时长按 JVM 实测外推，真机（尤其低端 SoC）应更慢，需真机确认。
- 旧审查 §11 之后 `android/` 无源码改动（`git log af30620..HEAD -- android/` 为空），所以 2026-10-12 的构建产物与当前源码一一对应；本次已用 `--rerun-tasks` 重新编译并重跑，排除"旧产物"干扰。
- 未做 UI 层验证（覆盖层触摸、渲染、`isVirtual` 等由 UI 成员负责）；`SoundManager` 的 WAV 缓存版本问题只作为 AC-11 的连带提醒。

## 功能机会（引擎/玩法角度，3–6 条）

1. **无猜预算自适应 + 可注入时钟**（对应 AC-4/AC-7）：按棋盘尺寸与雷密度动态给定 `maxAttempts/budgetMs`（低密度小盘直接 800ms，大盘/高密度在首试后即可声明"本局不支持无解保护"并显示原因），同时把提前降级判据从"含冷启动首试的均值"改为"剔除首试后的中位数"。既提高无猜成功率，也让参数可测、可调。
2. **求解器升级为"枚举可解性判定 + 提示"**：现有三规则（单点/子集/全局）是保守判定；在边界未知集 ≤20 时做组合枚举即可把高级局单次可解率从 6.5% 提到接近理论值（无猜生成成功率大幅上升），同一套逻辑可直接暴露为**提示功能**——"标一个安全格/一个雷"（返回 `Hint(cell, kind)`），无需新增棋盘状态，是玩家最常请求的辅助玩法。
3. **3BV 效率曲线与难度榜**：引擎已算 `bbbv`（`GameEngine.kt:149-150,280-321`），统计侧已有 `bestBbbvPerSec` 与历史（`StatsManager.kt:45-49,125-154`）；可扩展为"每局 3BV/s 折线 + 该难度最佳曲线 + 无猜/普通局分榜"。需要引擎暴露每局 `bbbv`（已有）与 `noGuess/fallback` 标记（已有），统计存储加字段并做一次迁移（与 AC-8 的单键存储改造一起做成本最低）。
4. **种子回放 / 每日挑战**：`planMines` 一旦支持注入随机源（AC-4），雷局可由"种子 + 尺寸/雷数/首点"完全复现；把 `MinePlan` + 操作序列（reveal/flag/chord）序列化成轻量记录，即可做本地回放、分享挑战、每日固定盘（无需服务器，纯引擎能力）。
5. **教学/练习盘模式**：core 已允许 0 雷与退化棋盘（`initCustom` 校验），修 AC-5 后可做"教学关（0 雷引导）/无限安全练习（踩雷不死）/慢速提示"等新手模式；这类模式还能顺带复用求解器做"每一步都保证有逻辑解"的练习盘生成。
6. **引擎级可观测性**：给 `planMines` 增加"已尝试次数/耗时"上报（返回 `MinePlan` 附带 `attempts/elapsedMs`），UI 可显示"生成中（第 12 次尝试）"；也给统计增加"无猜平均生成耗时/降级率"埋点，为后续调参提供数据（当前只能靠 `/tmp` 探针）。
