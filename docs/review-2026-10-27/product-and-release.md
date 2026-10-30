# 产品功能机会与工程发布审查

- 审查日期：2026-10-27
- 审查基线：`HEAD 9c92fda`（干净工作树，26 个提交）
- 审查角色：扫雷全量审查团队 ·【产品功能机会 + 文档/发布/工程卫生】工作流
- 范围：产品功能（双端现状、缺口、路线图）+ README/文档准确性 + CI/发布/版本/仓库卫生；代码正确性细节由其他成员负责
- 只读审查：未修改任何源码/配置/README
- 事实核验：逐文件阅读桌面 `src/`、`main.js`、`preload.js`、`package.json`、两份 README、`.github/workflows/*`、`android/`（Gradle/Manifest/`core` 全部 Kotlin 源码 + `GameView.kt` 关键段）；实测 `npm test`（76 项全绿）；用 `aapt dump badging` 检查 `dist/` 内 APK；`git ls-files`/`git tag` 核对仓库卫生与发布历史。

**联网调研可用性说明**：`web_search` 在本次会话返回 HTTP 401（搜索端点 API key 配置问题），不可用；`web_fetch` 仅对 `minesweepergame.com` 与 `chiark.greenend.org.uk` 可用（本文引用其内容的 URL 均已实际抓取）。`minesweeper.online`、GitHub、Wikipedia、Steam、Microsoft 等站点被网络策略拦截，相关产品事实**基于产品常识，未联网核实**，在文中统一标注 `unverified`。

---

## 结论摘要（≤12 行）

1. 双端功能对齐度已经很高（2026-10-12 审查验证 8 组核心方法逐分支等价）；真正缺的不是"移植"，而是**新的产品功能层**：可复现棋盘/每日挑战/回放/提示/竞速统计/社交/商店分发。
2. 产品机会可归为两条线：面向大众的"现代 XP 扫雷"（提示、每日、成就、分享）与面向社区的"竞速训练器"（小数计时、IOE/NF、回放）；当前 3BV/3BV-s 基础已经具备，是很好的起跑线。
3. ROI 最高的第一步是 `PF-1` 种子化开局 + 重开同局 + 分享：桌面生成器已支持注入随机源（`src/generator.js:97-104`），是每日挑战、回放、PWA 分享链接的共同地基，双端合计约 1 天。
4. 第二高 ROI 是 `PF-2` Web/PWA 版：现有前端全部 Electron API 都有守卫（`src/ui.js:251-260`、`src/app.js:71-79`），改造成本远低于重写，能带来零后端的新用户触达。
5. README 功能表总体兑现率约 85%，但有 10+ 处事实性/表述性问题，最严重的是：Linux arm64 声明与 CI x64 矛盾、`~170 KB` APK 与仓库内 1.53 MB 未签名产物不符、"键盘全操作"/"F2 属于原生菜单"夸大、无猜"always solvable"与降级逻辑矛盾。
6. 发布侧最大 3 个阻塞：**无 LICENSE**（F-Droid/再分发/商店授权都卡）；**Android 只产 APK、targetSdk 34、无隐私政策**（无法上 Google Play）；**双 tag 策略 + 三处版本号漂移**（历史桌面 tag 全是 `v*`、`package.json` 1.2.0、最新 tag v1.2.1、Gradle 默认 1.0.0，且 CI 不校验）。
7. CI 功能上只能算"冒烟"：桌面 PR 只跑 `npm test`，主分支无任何检查；双端都没有 lint/format/typecheck；桌面三平台打包只在 tag 上验证；tag 与版本号无一致性 guard。
8. 工程卫生：`dist/` 里混放 273 MB 旧产物与 **`keystore-backup-v1.2.1.zip`（含明文密码的 keystore.properties）**；`android/keystore/` 明文密码就在仓库目录内；无 LICENSE/CHANGELOG/截图/issue 模板。
9. i18n 架构缺失：README 是中英双语，但 UI 100% 中文硬编码（桌面 `index.html:2` + `main.js` 菜单 + `config.js` 标签；安卓 `strings.xml` 只有 1 条 `app_name`，`GameView.kt` 257 行含中文）。
10. 建议先做的前 5：`PF-1` 种子分享、`PF-2` Web/PWA、`PF-3` 提示/安全点击、`PF-4` 竞速模式（小数计时 + IOE/NF）、`PF-5` 设置中心 + 显式暂停；合计约 10–13 人日，可拆成 2–3 次独立发布。

---

## 一、README/文档准确性

### 1.1 事实性/夸大/过时问题（按严重度排序）

| # | 位置 | 声明 | 实际证据 | 判定 | 修正建议 |
|---|---|---|---|---|---|
| R-1 | `README.md:70`、`README.zh-CN.md:69` | Linux 发行版为 `arm64 .AppImage` | CI 在 `ubuntu-latest`（x64）上执行 `dist:linux`（`.github/workflows/build.yml:31-36`），`package.json:41-43` 未指定 arch；`dist/扫雷-1.2.0-linux-arm64.AppImage` 是 8 月 14 日本机构建产物（与 CI 无关） | **不准确** | 下载表改为"Linux x64（CI）/ arm64（本地构建，未持续发布）"，或在矩阵中加 arm64 runner |
| R-2 | `README.md:21` | "Full keyboard control — All actions reachable by keyboard, with rebindable keys" | 可重绑动作只有 7 个：4 方向 + 翻开/和弦 + 标旗 + 新游戏（`src/keybindings.js:21-29`）；统计/曲线/主题/无猜/键位/自定义/关于等入口没有键盘绑定，只能走原生菜单或鼠标（`main.js:20-89`） | **夸大** | 改为 "All gameplay actions reachable by keyboard"；给菜单项补 accelerator，或把这些入口纳入可重绑动作 |
| R-3 | `README.md:19` | "Native menu bar | F2 starts a new game"（把 F2 写在原生菜单栏一行） | F2 是渲染进程可重绑快捷键的默认值（`src/keybindings.js:28`、`src/app.js:546-548`），原生菜单"新游戏"项没有 `accelerator`（`main.js:25`） | **归因错误** | F2 移到键盘功能行；或给菜单项加 `accelerator: 'F2'` 并在重绑时同步菜单 |
| R-4 | `README.md:26` | "No-guess mode | Boards are **always** solvable by pure logic" | 生成器有尝试/时间预算，超限会降级为普通雷局并置 `noGuessFallback`（`src/generator.js:116-130`、`src/game.js:132-139`），HUD 会显式警告（`src/ui.js:477-484`）；README 自己在 `README.md:86`、`README.md:125` 承认会降级 | **自相矛盾** | 行 26 改为 "solvable when generation succeeds; HUD warns on fallback" |
| R-5 | `README.md:105`、`README.zh-CN.md:102` | Release 开启 R8 后 APK "从约 2 MB 降至约 170 KB" | 仓库内 `dist/saolei-1.0.0-android-release.apk` 为 **1,608,316 B（≈1.53 MB）**，`aapt badging` 显示 versionCode=1/versionName=1.0.0、**无签名（无 META-INF/*.RSA）**、dex 内 85 处 `com/wpiaopiao/saolei` 未混淆；当前 CI 的 R8 产物在仓库中不可得 | **数值过时/不可核实** | 用当前 CI 签名产物重新测量并写明"已签名/未签名、含/不含 R8"；不要写历史数字 |
| R-6 | `README.md:16`、`README.zh-CN.md:16` | 自定义棋盘 "up to 50×50" | 桌面 UI 强制行/列 9–50、雷数 ≤ `floor(rows*cols*0.85)`（`src/app.js:190-203`）；安卓同样 9–50 与 85%（`GameView.kt:1018-1034`）；引擎层最小值是 1（`src/game.js:74-99`） | **遗漏关键约束** | 写明"行/列 9–50，雷数上限 85%"；若要支持 1×1～8×8 需放开 UI |
| R-7 | `README.md:93`、`README.zh-CN.md:90` | 安卓 "otherwise aligned with desktop — … pause/resume" | 桌面没有用户可操作的暂停，只在窗口失焦时自动暂停（`src/app.js:564-580`、`src/index.html:26-28`）；安卓在切后台/覆盖层时暂停（`MainActivity.kt:111-119`、`GameView.kt:269-303`） | **表述模糊** | 改为 "auto-pause when the window loses focus (desktop) / when the app is backgrounded (Android)" |
| R-8 | `README.md:85` | "any key can be rebound" | 安卓 Back 键被保留（`GameView.kt:860-864`），冲突键会被拒绝（`GameView.kt:866-874`）；桌面只有 7 个游戏动作（`src/keybindings.js:21-29`） | **轻微夸大** | 改为 "any non-reserved key (Back is reserved on Android); conflicting keys are rejected" |
| R-9 | `README.zh-CN.md:17` | "窗口只放大不缩小" | 英文版无此句（`README.md:17`）；主进程 `fit-window` 只保证自动适配时取 `max(当前, 目标)`、不主动缩小（`main.js:139-149`），用户仍可手动缩小窗口、棋盘改为滚动（`src/style.css:116-129`） | **中英不一致 + 语义含糊** | 中英统一为 "auto-fit never shrinks the window; the board scrolls when it doesn't fit" |
| R-10 | `README.md:64-70` | 下载表直接给出三平台产物 | 仓库 tag 只有 `v1.0.0/v1.1.0/v1.2.0/v1.2.1`，**没有任何 `desktop-v*` tag**；桌面发布工作流（`build.yml:3-10`）在仓库历史中从未被触发；GitHub Release 实际状态本次环境无法联网核实 | **未核实** | 下载表改为链接 Releases 并标注 "latest desktop-v* / latest android v*"；或从 Release 自动生成（见 P0-3） |
| R-11 | `README.md:80` vs `README.zh-CN.md:77` | 英文 "Konkr KPA" / 中文 "攻氪 KPA" | 同一设备两种译名，仓库内无官方产品链接 | **命名不一致** | 统一品牌名并加官方链接；如无法确认拼写，写"攻氪 KPA（KPA 掌机）" |
| R-12 | `README.md:125` | 安卓版本号由 tag 注入（`v1.2.3 → 1.2.3/10203`） | 逻辑属实（`android.yml:68-78`），但**非 `vX.Y.Z` tag 会静默回落 1.0.0/1 并照常发布**（`android.yml:76-77`、`build.gradle.kts:22-23`） | **隐藏风险未写明** | README 加一句"非 vX.Y.Z 标签会中止发布"（并同时修改 workflow 行为，见 P1-3） |
| R-13 | `README.md:32` | "Unit tests — Built in with node:test" | 76 项实测全绿（`npm test`），属实；但桌面 `ui.js`/`app.js` 零测试、安卓 `app` 模块零测试（历史审查 `docs/audit-2026-10-12.md` §7） | 属实但需补注 | 可加 "core logic tests; no UI/E2E tests yet" |
| R-14 | `README.md:103` | 非 ASCII 路径构建安卓的故障排查 | 仓库自身路径就是中文（本机 `/…/扫雷`），README 建议复制到 ASCII 路径；CI 不受影响属实 | 合理但体验差 | 长期应修复 Gradle/Kotlin 路径问题或提供脚本；至少注明这是已知限制 |
| R-15 | `README.md` 全文 | 无截图/GIF、无 LICENSE、无 CHANGELOG、无已知限制（中文 UI、未签名构建、无自动更新） | `git ls-files` 中图片只有两张 `icon.png`；仓库根无 `LICENSE`/`CHANGELOG*`（见 P0-1、P2） | **缺失** | 加 2–4 张截图 + 简短 GIF（成绩/无猜/安卓触摸）；补 LICENSE、CHANGELOG、Known limitations |

> 说明：用户特别要求核对的**中英文语言切换链接正确**（`README.md:3` ↔ `README.zh-CN.md:3`，锚点 `#android-konkr-kpa` 与标题 "Android (Konkr KPA)" 匹配），这一点没有问题；上表 R-9/R-11 属于内容一致性而非链接错误。

### 1.2 中英两版一致性

- 功能表行数不同：英文 18 行、中文 17 行（中文把"6 主题"与"跟随系统深浅色"合并为一行，英文分两行；"破纪录横幅"在英文并入 "Game protection & endgame feedback"，中文并入"动画与反馈"）。内容等价但结构漂移，后续维护容易漏同步。
- 英文版有安卓章节跨链（`README.md:7`），中文版没有对应指路（`README.zh-CN.md:5-7`）。
- 英文版把桌面特性分成 18 条、中文版 17 条，且 CI 章节的示例版本号（`desktop-v1.3.0` vs `v1.2.1`）与当前实际版本（桌面 1.2.0 / 安卓 1.2.1）不一致，容易让读者误以为两个版本号同源。
- 两版都遗漏：最低 Node 版本（CI 用 22，`build.yml:19`）、`npm run pack`/`npm run dist` 脚本用途、`electron 33` 的已知限制、数据存储位置与重置方法。

### 1.3 文档体系与时效性

- `docs/audit-2026-10-12.md:3` 标注代码版本 `461c395`，而当前 HEAD 是 `9c92fda`（相差 8 个提交）；同文档 §1 说"性能、UI/UX 与工程类条目仍未修"，§11 又记录"第二批全部实现并验证"——文档内部自相矛盾，读者无法判断现状。建议把该文档归档（标注"历史快照 + 已在 §11 部分修复"）或补一份现状索引。
- `docs/superpowers/specs/2026-07-14-minesweeper-design.md` 是 7 月设计文档：只描述深浅两主题（第 10、52 行）、菜单结构没有统计/曲线/无猜（第 56-57 行），已明显落后于实现（6 主题、无猜、3BV 曲线、安卓端）。应标记 superseded 或归档到 `docs/archive/`。
- `docs/` 没有索引；README 不链接 `docs/`；`docs/review-2026-10-27/` 是本轮审查目录。

---

## 二、双端功能矩阵

图例：✅ 有 · 🟡 部分/有差异 · ❌ 无 · — 不适用。证据只列一处代表位置。

### 2.1 玩法与引擎

| 能力 | 桌面（Electron/JS） | 安卓（Kotlin） | 证据/备注 |
|---|---|---|---|
| 三档预设难度（9×9/10、16×16/40、16×30/99） | ✅ | ✅ | `src/config.js:13-17`；`GameEngine.kt` LEVELS |
| 自定义棋盘（9–50 行列、雷数 ≤85%） | ✅ | ✅ | `src/app.js:159-217`；`GameView.kt:1018-1044` |
| 首点 3×3 安全区 + 空白洪泛 | ✅ | ✅ | `src/generator.js:40-60`；`GameEngine.planMines` |
| 无猜模式（求解器判定可纯逻辑） | ✅ | ✅ | `src/solver.js`；`core/Solver.kt` |
| 无猜生成不阻塞 UI | ✅ worker 线程 | ✅ 后台 Thread | `main.js:159-205`；`GameView.kt:610-641` |
| 无猜降级明示 | ✅ HUD 警告 | ✅ HUD 警告 | `src/ui.js:477-484`；`GameView.kt:120` |
| 和弦（双击/中键/左右键；安卓 B 键/点按数字） | ✅ | ✅ | `src/ui.js:76-80,353-404`；`GameView.kt:659-672` |
| 标旗三态（旗→问号→清空） | ✅ | ✅ | `src/game.js:294-312` |
| 胜利自动补旗 + 展开安全格 | ✅ | ✅ | `src/game.js:362-386`，`src/ui.js:90-98` |
| 重开确认、错旗红叉、新纪录横幅 | ✅ | ✅ | `src/app.js:151-157`；`GameView.kt:766-785` |
| 变体棋盘（环绕/六边形/三角形/密度百分比） | ❌ | ❌ | Simon Tatham 的 Mines 支持多种 tiling + wrap（已抓取，见参考来源）；当前引擎只支持方形不环绕 |
| 回放/重放（含失败局） | ❌ | ❌ | Windows 7 扫雷有 "Replay lost games"（已抓取）；当前无事件日志 |
| 提示/安全点击/自动标雷辅助 | ❌ | ❌ | 求解器只返回布尔可解（`src/solver.js:1-201`），未暴露"下一步安全格"；`autoFlagRemainingMines` 只在胜利时调用（`src/ui.js:94`） |
| 种子/重开同局 | ❌ | ❌ | 桌面 `generate()` 已支持注入随机源（`src/generator.js:97-104`）但未存种子；安卓直接用全局 `kotlin.random.Random`（`GameEngine.kt:53`） |
| 每日挑战 | ❌ | ❌ | 无日期种子，无每日记录 |

### 2.2 统计、效率与回放

| 能力 | 桌面 | 安卓 | 证据/备注 |
|---|---|---|---|
| 总局/胜率/连胜/总时长/最佳时间 | ✅ | ✅ | `src/stats.js`；`core/StatsManager.kt` |
| 最佳 3BV/s/难度 | ✅ | ✅ | `src/stats.js:23-29,80-90`；`StatsManager.kt:47-48` |
| 最近 20 局 3BV/s 曲线 + 平均线 | ✅ | ✅ | `src/app.js:251-349`；`GameView.kt:1993-2089` |
| 高级指标 IOE / IOS / RQP / Correctness | ❌ | ❌ | 社区统计定义（已抓取 minesweepergame.com/statistics.php）；需点击计数 |
| 小数计时（0.01 s，竞速用） | ❌ | ❌ | 桌面计时上限 999 整数秒（`src/app.js:25`）；安卓同为整数秒（`GameView.kt:569-570`） |
| NF（无旗）模式/NF 成绩 | ❌ | ❌ | 社区排名分 Time/Time NF/3BV-s/IOE（已抓取 world-rankings） |
| 实时 3BV/进度显示 | ❌ | ❌ | 3BV 只显示在统计页，局内不可见 |
| 历史回放/成绩详情 | ❌ | ❌ | `history` 只存 `{level,time,bbbv,ts}`（`src/stats.js:30-31`） |
| 成绩导出/导入/分享 | ❌ | ❌ | 无序列化导出；`localStorage`/SharedPreferences 只能整机保留 |
| 成就/勋章 | ❌ | ❌ | 无成就模型 |
| 云同步/账号 | ❌ | ❌ | 无网络权限（`AndroidManifest.xml` 无 INTERNET）、无后端 |

### 2.3 平台、设置与发布

| 能力 | 桌面 | 安卓 | 证据/备注 |
|---|---|---|---|
| 6 套主题 + 偏好持久化 | ✅ | ✅ | `src/config.js:19-26`；`GameView.kt:71-84` |
| 跟随系统深浅色 | ✅ | ✅ | `src/app.js:639-654`；`MainActivity.kt:40-77` |
| 6 种运行时合成音效 | ✅ Web Audio | ✅ WAV/SoundPool | `src/audio.js:50-76`；`core/Synth.kt`、`SoundManager.kt` |
| 静音开关 | ✅ | ✅ | `main.js:26-31`；`GameView.kt:471-475` |
| 键位自定义 | ✅ 7 动作 | ✅ 8 动作（含菜单） | `src/keybindings.js:21-29`；`KeyBindings.kt:18-31` |
| 按键测试屏 | ❌ | ✅ | `GameView.kt:1934-1947`（"当前按键: …"） |
| 键盘/鼠标/触摸 | 鼠标 + 键盘光标 | 手柄 + 触摸（全对话框） | `src/ui.js:221-247`；`GameView.kt:2132-2354` |
| 单实例 / 原生菜单 | ✅ / ✅ | — / 自绘菜单 | `main.js:20-89,207-219`；`GameView.kt:1796-1827` |
| 托盘、全局快捷键、窗口位置记忆、置顶、缩放 | ❌ | — | `main.js:112-135` 固定 860×680、无 bounds 持久化；未 import Tray/globalShortcut |
| 自动更新 | ❌ | ❌ | `package.json:45-48` 无 electron-updater；`dist/latest-*.yml` 已生成但无人消费 |
| Web/PWA 版 | ❌ | ❌ | 见 PF-2 |
| 手机竖屏/平板/折叠屏 | — | ❌ | `AndroidManifest.xml:18` `sensorLandscape` 锁横屏；布局按 960×640 设计 |
| 桌面手柄/Virtual Gamepad | ❌ | — | 无 Gamepad API 支持 |
| i18n（中/英 UI） | ❌ | ❌ | 见 P1-4 |
| 无障碍（屏幕阅读器/键盘对话框/reduced-motion） | 🟡 棋盘有 aria-label | 🟡 仅长按触觉 | `src/ui.js:211`；`GameView.kt:2125`；无 TalkBack 内容描述、无 reduced-motion |
| 商店上架（Play/F-Droid/Steam） | ❌ | ❌ | 见 P0-2 |

### 2.4 移植缺口结论

1. **引擎层已高度对齐**：2026-10-12 审查确认布雷/3BV/翻开/洪泛/插旗/和弦/胜负/求解 8 组方法逐分支等价（`docs/audit-2026-10-12.md:12-20`），本次抽查的关键常量也一致（`config.js:40-46` vs `GameEngine.kt` NO_GUESS/HISTORY_MAX）。
2. **两端主要缺口是共同缺新功能**，而不是彼此移植差异；上表中"❌/❌"的条目（种子、每日、提示、回放、小数计时、IOE/NF、成就、导出、i18n、无障碍）都是双端一起做才划算。
3. **真正的移植差异（较小，可顺手补齐）**：
   - 桌面缺安卓已有的按键测试屏、底部"自定义"直达按钮（桌面自定义只在菜单）、全对话框触摸（桌面无触摸场景，不算缺口）。
   - 安卓缺桌面已有的鼠标中键/双击和弦（触摸已用点按+长按覆盖）、原生菜单/单实例（平台不同，不补）。
   - **跨端常量三份手工同步**（`config.js:13-46` vs `GameEngine.kt` vs `StatsManager.kt:27,30`）无守护测试（历史审查 §7 已提出），建议加 `PortParityTest`/`test/parity.test.js`。
   - 桌面前端已具备"浏览器可运行"结构（Electron API 全部 optional 检查），安卓是不可复用的原生层——所以 Web/PWA 只能覆盖桌面一半用户。

---

## 三、功能机会清单（18 条，按 ROI 排序）

**档位定义**：A = ≤1 天/低风险；B = 2–5 天；C = 1–2 周+。工作量 S/M/L 按"双端都做"估算，单端约减半。
**依据说明**：功能灵感来自已抓取的 Windows 7/XP 扫雷、Minesweeper X/Arbiter、minesweepergame.com 世界排名与 Simon Tatham's Mines 资料（见第六节），以及标注 `unverified` 的现代产品常识（minesweeper.online、Microsoft Minesweeper Win8/10、Steam 变体扫雷）。

### [PF-1] 种子化开局 + 重开同局 + 分享种子（档位 A，工作量 S，双端 + Web）
- **用户价值**：可复现棋盘是所有社区功能的地基——重开同局练习、把"这局我 12 秒"发给朋友、每日挑战、回放、教学分享。经典 Windows 扫雷没有种子共享，现代在线扫雷和竞速社区普遍有（unverified：minesweeper.online 分享/重放能力）。
- **实现要点**：桌面 `src/generator.js:97-104` 的 `generate()` 已接受注入的 `random` 与 `now`，只需在 `GameEngine` 增加 `seed` 字段（`src/game.js:13-24`）与 mulberry32/xorshift PRNG；`applyPlan` 保留 seed；主进程异步生成时把 seed 随请求/结果传递（`main.js:171-205`、`preload.js:7`）。安卓 `GameEngine.kt:94-100` 的 `planMines` 目前直接用全局 `kotlin.random.Random`（第 53 行），需要加 `Random`/seed 参数并让 `GameView.startAsyncFirstReveal`（`GameView.kt:610-641`）传入。UI：菜单/工具栏加"重开本局"、"复制种子"；种子串建议 `level=expert&seed=1789...&ng=1`。分享：桌面 `navigator.clipboard` 或 preload IPC；Web 版直接用 URL query（见 PF-2）。
- **风险/依赖**：无猜模式下 seed 需覆盖"重试次数"，语义要定义清楚（推荐 seed = 初始种子，重试用 seed+n）；改 stats 无需；不要破坏现有 76+69 测试（PRNG 注入后旧测试用非确定 random 仍应通过）。

### [PF-2] Web/PWA 版 + 分享链接（档位 B，工作量 M，桌面/Web）
- **用户价值**：零安装、手机浏览器可玩、链接可直接分享"同一种子同一局"；对开源项目是最大的拉新杠杆（Steam/商店之外最便宜的渠道）。
- **实现要点**：现有前端几乎不依赖 Electron：`fitWindow` 有 `window.electronAPI` 守卫（`src/app.js:71-79`）、无猜异步生成有 `canGenerateAsync` 守卫（`src/ui.js:251-260`）、音频用 Web Audio（`src/audio.js`）。新增 `web/` 或 `scripts/build-web.js` 复制 `src/` + `index.html`、加 `manifest.webmanifest` + `sw.js`（离线缓存）、`<meta name="theme-color">`；无猜在浏览器走同步路径（大棋盘最多 800 ms，可接受；后续可换 Web Worker 复用 `generator-worker.js`）。新增 GitHub Pages workflow（`deploy-pages`），与 PF-1 的 URL 分享联动。
- **风险/依赖**：`generator-worker.js` 依赖 worker_threads（Node），Web 端需新建 Web Worker 包装；localStorage 与 Electron 版数据不互通（可做导出/导入，见 PF-11）；CSP 需允许 manifest/sw；GitHub Pages 为公开站点，需确认字体/图标无外部依赖（当前无）。

### [PF-3] 提示/安全点击（求解器辅助）（档位 B，工作量 M，双端）
- **用户价值**：新手留存的最大痛点"卡住乱猜"；给出"下一步安全格"或"这里可标雷"，可显著降低挫败感，也是无猜模式的天然配套（无猜局保证有解，提示永远给得出）。
- **实现要点**：扩展 `src/solver.js` 与 `core/Solver.kt`：从返回 `boolean` 改为/新增 `findSafeMove(board)` 返回 `{row,col}` 或 `{type:'flag',...}`（单点+子集+全局规则已有，只需返回推导出的安全格而非纯判定，`src/solver.js:1-201`）。UI：HUD 加"提示"按钮，高亮目标格（桌面复用 `ui.js` 的 cursor/pressed 类；安卓复用 `drawCell` 的 active/光标样式），使用次数记入统计并在胜利记录打 `assisted` 标记（避免污染无辅助成绩，`src/stats.js:64-93`、`StatsManager.kt:125-154`）。风险：提示 API 暴露后不能影响 no-guess 生成耗时（求解器逐格调用需缓存/复用 scratch，`src/generator.js:24-38`）。
- **风险/依赖**：需明确"辅助局不计入最佳成绩"或单列一类；安卓 50×50 求解耗时需基准；与无猜降级提示的 UI 冲突要处理。

### [PF-4] 竞速模式：小数计时 + 点击计数 + IOE/NF（档位 B，工作量 M，双端）
- **用户价值**：把现有 3BV/s 从"自嗨数字"升级为社区可比较的竞速指标；NF（不插旗）模式与 IOE 是国际社区标准玩法（已抓取 world-rankings/statistics/efficiency 页），能吸引硬核玩家。
- **实现要点**：计时从整数秒改为内部记录毫秒/百分秒（桌面 `src/app.js:18-52` 墙钟锚点已有 ms 精度，只需展示与结算改小数；安卓 `GameView.kt:269-316` 同理），统计 schema 升版（history 增 `durationMs`/`clicks`/`mode`，`src/stats.js:30-31`、`StatsManager.kt:10`）；输入计数在操作入口统一累加（桌面 `src/ui.js:318-404` 的 reveal/flag/chord 分支；安卓 `GameView.revealAt/flagAt/doChord`，`GameView.kt:586-672`）；设置里加"NF 模式"（禁止标旗或标旗不计点击）。UI：计时器显示 `SS.cc`、统计页新增 IOE/IOS/RQP（公式已抓取）、局内显示实时 3BV 剩余/进度。
- **风险/依赖**：stats 迁移要兼容旧存档（`src/stats.js:37-52` 有合并默认值的先例）；小数计时让"最快时间"纪录不可比（建议保留整数纪录 + 单独竞速纪录）；安卓定时器精度（`SystemClock` 可用）。

### [PF-5] 设置中心 + 显式暂停 + 音效分类/动画开关（档位 B，工作量 M，双端，桌面优先）
- **用户价值**：现在桌面设置分散在原生菜单（静音/无猜/主题/难度/键位各自一个入口，`main.js:20-89`），没有"设置"概念；补统一设置页可同时解决动画开关、音量分级、无障碍 reduced-motion、语言（PF-6）入口，也是 App Store/Play 审核常看的"可关闭动效/声音"要求（unverified 具体条款）。
- **实现要点**：桌面复用 `showDialog` 基础设施（`src/app.js:102-157`）新增"设置"对话框：主题/跟随系统、无猜、动画开关、音效总音量+分类音量、语言、键位入口、统计重置（需二次确认）；暂停：`pauseGame()/resumeGame()` 已存在（`src/app.js:564-580`），加 P 键/菜单项与可见按钮即可。安卓复用 `Overlay` 体系（`GameView.kt:137`）加设置页；`Synth`/`SoundManager` 加音量参数（`SoundManager.kt:42-50`）。
- **风险/依赖**：设置持久化键要与现有 `STORAGE_KEYS`（`src/config.js:28-34`）合并；安卓菜单已 17 项，建议把主题/键位/设置收进子页（`GameView.kt:787-817`）。

### [PF-6] 双语 UI（中文/英文）+ i18n 架构（档位 B，工作量 M 桌面 / C 双端）
- **用户价值**：README 既然以英文为默认（`README.md:3`），英文读者下载后却是全中文 UI（`src/index.html:2`），转化直接流失；上架国际商店必须支持英文。
- **实现要点**：桌面新增 `src/i18n.js`（`t(key, vars)` + 词典 zh/en + `navigator.language` 检测 + 持久化），替换 `src/app.js` 全部对话框文案、`main.js:20-89` 菜单、`config.js:14-25` 的难度/主题 label（改为 key，显示时翻译）、`index.html` 静态文案与 `lang` 属性；安卓把 `GameView.kt` 的中文（257 行）迁到 `res/values/strings.xml` + `values-en`，`core` 层的 LEVELS/KeyBindings labels（`GameEngine.kt` companion、`KeyBindings.kt:21-31`）改为资源 key 或由 app 层注入翻译；`android:localeConfig`/per-app language（Android 13+）可选。
- **风险/依赖**：工作量主要在文案搬运 + 布局宽度（长英文文案在 960px 掌机/50×50 棋盘上可能溢出，需测）；桌面菜单由主进程构建（`main.js`），i18n 词典需主进程可读（CommonJS/UMD 共享，参照 `config.js` 的 UMD 模式）；与 PF-5 设置页一起做可省一次返工。

### [PF-7] 每日挑战（日期种子 + 每日最佳 + 连续天数）（档位 B，工作量 M，双端）
- **用户价值**：低成本的高留存机制（每日一局同局、全玩家一致），现代扫雷产品标配（unverified：Microsoft Minesweeper 的 Daily Challenges、minesweeper.online 的 daily challenge）。
- **实现要点**：复用 PF-1 PRNG：`seed = hash(YYYY-MM-DD + difficulty)`，固定难度轮换（周一初级…周日高级或随机），生成 no-guess 局；本地记录 `daily: {date, level, time, won, streak}`（桌面 `stats.js` 扩展 / 安卓 `Storage`）；UI 加入口与"今日已完成"状态；不做服务端，纯本地即可。
- **风险/依赖**：依赖 PF-1；日期时区用本地时区并在文档写明；作弊成本低（本地改时间）可接受，不宣称竞技级。
- **备注**：与 PF-1 合并实现可省 0.5 天。

### [PF-8] 历史回放（含失败局）（档位 C，工作量 L，双端）
- **用户价值**：高差异化功能（Windows 7 扫雷内置 "Replay lost games"，已抓取）；让玩家复盘、分享、学习；也是反作弊/竞速提交的基础。
- **实现要点**：局内事件日志 `[{t, type: reveal|flag|chord, r, c}]`（操作入口已集中：桌面 `ui.js:318-404`；安卓 `GameView.kt:586-672`），与 seed + 首点一起可完整复现（引擎是确定性的，`game.js` 无随机）→ 存储按 seed+事件列表，体积小；回放播放器复用现有渲染（桌面 `ui.render()`/安卓 `invalidateBoardCache` + 定时器）；数据库/存储上限（如最近 20 局）。UI：统计页局详情 + "回放"按钮。
- **风险/依赖**：依赖 PF-1（seed）与 PF-4（时间精度）；存储上限与清理策略；回放不能触发音效/统计（需"回放模式"标志）；工作量大（播放器/时间轴/UI）。
- **备注**：可先做桌面端（Electron 存档方便），安卓跟随。

### [PF-9] 成就系统（本地勋章 + 进度）（档位 B，工作量 M，双端）
- **用户价值**：把现有统计转化为长期目标（首胜、sub-10 初级、无猜高级、50×50 通关、连胜 10、100 局等），提升留存与分享话题。
- **实现要点**：新增 `src/achievements.js` / `core/Achievements.kt`，纯逻辑、可单测：定义 id/条件/进度；在 `ui.js:83-103`（桌面 onStateChange）与 `GameView.syncState`（`GameView.kt:533-556`）胜利/失败/新纪录事件后评估；存储键加入 `config.js:28-34`；UI 弹"解锁"横幅（复用 `record-banner` 样式，`src/ui.js:105-117`）+ 成就页；无后端、无时间限制。
- **风险/依赖**：与 PF-4 的辅助标记/NF 模式联动（有些成就应禁止辅助）；成就条件需要设计，避免"刷数据"破坏乐趣；测试覆盖条件边界。

### [PF-10] 高级统计面板（趋势/分布/按棋盘尺寸）（档位 B，工作量 M，双端）
- **用户价值**：现有统计页只有汇总数字 + 一条 3BV/s 曲线；玩家想知道"我的胜率趋势/最近状态/哪种棋盘最稳"。
- **实现要点**：扩展 history 记录（胜/负、耗时、3BV、日期、board 尺寸、clicks），桌面用现有 SVG 工具函数（`src/app.js:253-298`）做多标签曲线（胜率/时间/3BV 分布直方图）；安卓用 `drawHistory`（`GameView.kt:1993-2089`）同样的绘图原语。注意自定义棋盘当前只有一条 `custom` 统计（`src/stats.js:17-29`），建议按 `rows×cols/mines` 细分。
- **风险/依赖**：stats schema 迁移（旧存档无新字段，`src/stats.js:37-52` 合并默认值模式可复用）；图表在 960×640 掌机上的可读性；与 PF-4 一起改 schema 更省事。

### [PF-11] 战绩/设置导出导入 + 分享成绩卡（档位 B，工作量 M，双端）
- **用户价值**：数据可迁移（换机/重装不丢），也是 Web/桌面/安卓之间唯一可行的"同步"方式；成绩卡图片适合社媒传播。
- **实现要点**：定义版本化 JSON schema（stats + bindings + settings + history），桌面用 `dialog.showSaveDialog`/`showOpenDialog`（`main.js` 加 IPC，preload 暴露）；安卓用 `ACTION_CREATE_DOCUMENT`/`ACTION_OPEN_DOCUMENT`（SAF）或写 `cacheDir` + `FileProvider` + share intent；成绩卡用 Canvas/SVG 生成 PNG（桌面可复用 SVG → canvas 导出；安卓 `Bitmap` + `Canvas`）。
- **风险/依赖**：导入需校验/合并策略（覆盖 vs 合并），必须有二次确认；安卓 FileProvider 需配置 manifest；与 PF-2 Web 版数据互通（可导出后拖入浏览器 localStorage）。

### [PF-12] 移动端手势增强（拖拽翻开、双指标雷、触觉分级）（档位 B，工作量 M，安卓）
- **用户价值**：当前安卓是"点按翻开/长按旗"（`GameView.kt:2132-2354`），移动端主流扫雷（unverified）支持拖拽连续翻开、双指操作，能显著提升手机手感；为 PF-13 竖屏手机铺路。
- **实现要点**：`onTouchEvent`（`GameView.kt:2132`）已有拖动重锚逻辑（`GameView.kt:2160` 附近），在"拖动即浏览"基础上增加"拖动签：进入未翻开格即翻开"开关（避免与浏览冲突，可设为设置项）；双指 tap 标旗；`performHapticFeedback`（`GameView.kt:2125`）按操作类型分级（存在→轻，旗→中，爆炸→重）。设置项写入 `Storage`。
- **风险/依赖**：手势与现有"拖动浏览超大棋盘"（`GameView.kt:1083-1112`）冲突，需要阈值/模式区分；960×640 掌机无触摸屏？KPA 是否有触屏需确认（README 描述"触摸（可选）"暗示有触摸屏，unverified）；无触摸设备时功能不可见。

### [PF-13] 手机竖屏/平板/折叠屏适配（档位 C，工作量 L，安卓）
- **用户价值**：当前 Manifest 锁 `sensorLandscape`（`AndroidManifest.xml:18`），布局按 960×640 掌机设计（`GameView.kt:99-108` 固定 HUD/BOTTOM 高度）；若想上 Play 覆盖普通手机用户，竖屏/平板适配是硬门槛。
- **实现要点**：Manifest 改 `fullSensor`/`user`；`onSizeChanged`（`GameView.kt:402-412`）按宽高比重排 HUD（顶部/底部）与棋盘视口；格子尺寸/字体比例用已有 `MIN_CELL/MAX_CELL` 机制（`GameView.kt:102-108`）；触摸目标最小 48dp；对话框按屏幕比例缩放（`drawDialog`）；测试多分辨率 + 旋转 + 折叠。建议与 PF-12 一起做。
- **风险/依赖**：大改布局，回归面大；KPA 掌机体验不能退化（可用 `smallestWidth`/设备白名单分两套布局）；需要真机/模拟器矩阵；可能与"像素级 WinXP 风格"冲突，需设计取舍。

### [PF-14] 桌面平台打磨：托盘、全局快捷键、窗口记忆、置顶、缩放/DPI、更新入口（档位 B，工作量 M，桌面）
- **用户价值**：桌面应用"像个桌面应用"：关窗最小化到托盘、全局快捷键呼出、记住窗口位置/大小、总是置顶、缩放适配高分屏；也是长期挂机扫雷用户的实际诉求。
- **实现要点**：`main.js` 引入 `Tray`、`globalShortcut`、`screen`；窗口 bounds 存 `app.getPath('userData')/window-state.json`，`createWindow` 恢复（当前固定 860×680，`main.js:112-135`）；`webContents.setZoomFactor` + 菜单项；`win.setAlwaysOnTop`；退出行为设置。托盘图标需 16/32px。
- **风险/依赖**：macOS 托盘/菜单规范（模板图标、`app.dock`）与 Windows 不同；全局快捷键可能被占用需注册失败提示；窗口记忆要处理多显示器；无自动更新时"更新入口"先链接 Releases 页即可。

### [PF-15] 变体棋盘 I：环绕（toroidal）+ 密度百分比 + 官方 30×24（档位 B，工作量 M，双端）
- **用户价值**：现有棋盘维度只有 9–50 方形；Simon Tatham's Mines 支持 wrap 和百分比雷密度（已抓取），环绕棋盘是低成本高新鲜感的变体，也是竞速社区官方密度图（30×24，已抓取 ranking-rules）。
- **实现要点**：引擎邻域计算加 wrap 标志（桌面 `game.js:209-220`、`countAdjacentMines`、洪泛 `game.js:268-292`、3BV `game.js:165-207`、生成器安全区/洗牌 `generator.js:40-60`、求解器 `solver.js`），安卓对应 `GameEngine.kt`/`Solver.kt`；自定义对话框加"环绕"开关与百分比输入（`src/app.js:159-217`、`GameView drawCustom`）；UI 在棋盘边缘画连接提示（可选）。测试：wrap 邻域、3BV、求解器等价性。
- **风险/依赖**：改动面在引擎核心，需双端等价测试（防行为漂移）；存档/历史需记录变体；与无猜生成交互要测（可解率可能变化）。

### [PF-16] 变体棋盘 II：六边形/三角形/3D/其他 tiling（档位 C，工作量 L，双端）
- **用户价值**：差异化卖点；Simon Tatham 的 Mines 支持多种 tiling（已抓取），Steam 有"变体扫雷"品类（unverified：14 Minesweeper Variants 等），适合作为 DLC/挑战模式。
- **实现要点**：这是对引擎/渲染/输入的全面抽象：把 `rows×cols` 方形索引换成 cell graph（邻接表），渲染从 CSS Grid/Canvas 方形改为多边形绘制，求解器按邻接表工作；建议只做桌面端试验（`src/game.js` 已模块化），安卓跟随成本高。
- **风险/依赖**：工作量大、回归面广；与现有 3BV/无猜语义不同需重新定义；不建议近期做。

### [PF-17] 在线竞技：排行榜/每日竞速/录像校验（档位 C，工作量 L，双端 + 后端）
- **用户价值**：社区天花板；世界排名体系证明扫雷有成熟竞技生态（已抓取 minesweepergame.com 排名规则、3BV 限制、视频校验）。但自建排行榜需要账号/服务端/反作弊，收益取决于用户规模。
- **实现要点**：轻量 MVP 可先做"成绩卡导出/分享 + 社区帖子/Issue 模板"；正式版需要：成绩上报 API（time/3BV/clicks/mode/seed）、服务端校验（回放重放校验，参考 PF-8）、每日竞速（同 seed）、排行榜 UI/分页；签名防篡改（HMAC）。
- **风险/依赖**：隐私政策/数据合规（P0-2 之后才有意义）、反作弊成本、运维成本；当前应用无网络权限，需要评估是否值得开 INTERNET 权限（隐私卖点会受损）。
- **备注**：建议放在 PF-1/4/8 之后，或用静态 JSON + 手动提交的"社区榜"过渡。

### [PF-18] 无障碍与新手引导（档位 B，工作量 M，双端）
- **用户价值**：屏幕阅读器/纯键盘用户可用；新手知道 3BV、无猜、和弦、NF 是什么；也是商店审核与"负责任产品"的加分项。
- **实现要点**：桌面：给计时器/雷数/表情加 `aria-live="polite"`，网格改为 roving tabindex，对话框焦点陷阱与 Esc（已有 Escape 关闭，`src/app.js:503-507`），加 `prefers-reduced-motion` 关闭所有动画（`src/style.css:260-536` 有 4 处 animation），高对比主题；安卓：给自定义 View 加 `ExploreByTouchHelper`/`contentDescription`（当前无），TalkBack 可读棋盘状态；新手引导：首次启动 3 步覆盖层（规则 + 和弦 + 无猜），可在设置中重看。
- **风险/依赖**：安卓自定义 View 的无障碍工作量较大（需 virtual nodes）；新手引导文案需 i18n（PF-6）；`prefers-reduced-motion` 在 Electron 中可用 `matchMedia`。

---

## 四、建议先做的前 5（理由 + 预估）

| 顺序 | 功能 | 理由 | 预估 |
|---|---|---|---|
| 1 | **PF-1 种子化 + 重开同局 + 分享** | 工作量最小（桌面生成器已经支持注入随机源，`src/generator.js:97-104`），但它是 PF-7 每日、PF-8 回放、PF-2 URL 分享的共同前置；单独发布也能立刻变成"可分享的 12 秒纪录"。 | 双端 1 人日（桌面 0.5，安卓 0.5） |
| 2 | **PF-2 Web/PWA 版** | 现有前端对 Electron API 全部有守卫，改造是"打包 + worker + PWA"而非重写；零安装、手机浏览器、链接分享是当前最大的用户触达缺口，且与 PF-1 组合后传播性最好。 | 3 人日（Web 构建 1、PWA/离线 0.5、Web Worker 无猜 0.5、Pages 部署 0.5、跨浏览器测试 0.5） |
| 3 | **PF-3 提示/安全点击** | 求解器已具备单点/子集/全局规则，只需把"判定可解"扩展为"返回下一步安全格"；对新手留存立竿见影，且无猜局保证有提示可用，是双端都受益的功能。 | 2–3 人日 |
| 4 | **PF-4 竞速模式（小数计时 + 点击计数 + IOE/NF）** | 3BV/3BV-s 已实现，缺的只是数据采集与展示；社区指标有成熟定义（已抓取），能形成差异化并吸引硬核玩家；也为 PF-8/PF-17 铺路。 | 3 人日 |
| 5 | **PF-5 设置中心 + 显式暂停 + 动画/音量开关** | 当前设置散在原生菜单，是"产品感"和商店审核的短板；同时为 PF-6 i18n、PF-18 无障碍提供统一入口，属于低风险高复用的"收口"工作。 | 2–3 人日（桌面优先 1 天，安卓跟随） |

**组合建议**：第一批发布 PF-1 + PF-5（1–2 天，桌面先行）；第二批 PF-2 + PF-3（约 1 周，可配一次小版本宣传）；第三批 PF-4 + PF-7（约 1 周，形成"每日挑战 + 竞速"留存闭环）。PF-6 i18n 建议与 PF-2 一起启动（Web 版天然面向国际用户，可先英文化 Web 端再回灌桌面/安卓）。

---

## 五、工程/CI/发布审查发现（P0–P3）

### P0（发布/法律/上架阻塞，动手前必须先解决）

**P0-1 仓库无 LICENSE，package.json 无 license 字段**
- 证据：`git ls-files` 无 `LICENSE`/`COPYING`；`package.json:1-49` 无 `license`/`author` 字段；README 全文无许可章节（`README.md:1-142`）。
- 影响：默认"保留所有权利"，他人不能合法复制/分发/二次开发；F-Droid 明确要求 FLOSS 许可证；Google Play/Steam 需要权利声明；与公开仓库的定位冲突。
- 修复：选定许可证（MIT/Apache-2.0/GPL-3.0 任一，按是否允许闭源衍生选择），加 `LICENSE`、`package.json` 的 `license`/`author`、README 许可章节；安卓"关于"页补第三方开源许可（当前无）。

**P0-2 Android 无法上架 Google Play / F-Droid（材料与产物均不足）**
- 只产 APK，无 AAB（`android.yml:28-36` 只构建 debug APK；`android.yml:86-93` 只上传 release APK；`build.gradle.kts` 无 bundle 配置）。Play 新应用要求 Android App Bundle（`unverified`，参考 Play 政策 URL 见第六节）。
- `targetSdk = 34`（`android/app/build.gradle.kts:21`）。按 2026 年 Play "target API 需在最新发布一年内"的通行要求（`unverified`），34 大概率已不达标（预计需 35/36），提审前必须复核并升级。
- 无隐私政策文件/URL、无 Data safety 声明、无商店文案/截图（仓库 0 张截图；图标仅 512px `icon.png` 与单密度 `drawable/icon.png`，无 adaptive icon/mipmap——`AndroidManifest.xml:5`、`res/drawable/icon.png`）。
- 无可靠签名链路：本地 `dist/` 的 release APK **未签名**（`aapt` 无 `META-INF/*.RSA`，versionCode=1）；CI 依赖 4 个 secrets（`android.yml:59-78`）且 gate 写法有风险（见 P1-3）；无 Play App Signing/上传密钥说明。
- 权限方面是加分项：Manifest 无 INTERNET/存储权限（`AndroidManifest.xml:1-27`），无广告/统计 SDK，隐私友好；但 Data safety 仍必须填。
- F-Droid：需要 LICENSE（P0-1）、可构建源码、fastlane metadata（描述/截图/变更日志）、可复现构建说明；当前均缺。
- 修复清单（Android 上架最小集）：① 加 LICENSE；② 产出 AAB（`bundleRelease`）并验证签名；③ targetSdk 升到当前政策要求；④ 写中英隐私政策（说明本地存储/无网络/无收集）并给出 URL；⑤ 填 Data safety/内容分级/商店 listing；⑥ 512 图标 + 2–8 张截图 + feature graphic；⑦ F-Droid metadata（或先只发 GitHub Releases + 侧载）。

**P0-3 双 tag 策略冲突 + 版本号三处漂移 + 无一致性 guard**
- 证据：仓库 tag 只有 `v1.0.0/v1.1.0/v1.2.0/v1.2.1`（`git tag`）；其中 v1.0.0–v1.2.0 是桌面版本节点（git log 对应桌面 feat/fix），v1.2.1 是安卓 workflow 修复；当前发布流程却是桌面 `desktop-v*`（`build.yml:5-7`）、安卓 `v*`（`android.yml:5-6`）——**历史桌面 tag 与新策略不兼容**，任何按旧习惯打 `v1.3.0` 的桌面发布只会触发安卓 workflow。
- 版本号：桌面 `package.json:3` = 1.2.0（About 页经 `main.js:157` + `app.js:365-371` 读取，可信）；安卓最新 tag = 1.2.1，但 `build.gradle.kts:22-23` 本地默认 versionCode=1/versionName=1.0.0，只有 CI tag 注入（`android.yml:68-78`）才正确；README 下载表不标版本；无 CHANGELOG。
- 无 guard：`build.yml` 不校验 tag 与 `package.json` 版本一致，`desktop-v1.3.0` 可以构建出 1.2.0 的安装包（artifact 名/更新元数据都用 package.json 版本，`package.json:22`）；安卓非 `vX.Y.Z` tag 会静默回落 1.0.0/1 并照常发布（`android.yml:76-77`），造成版本回退、无法覆盖安装。
- 修复：① 冻结旧 `v*` 语义，新安卓用 `android-v*`（或统一 `release-v*`）；② 在 CI 加 `check-version` step（tag 必须等于 package.json / Gradle 版本 / CHANGELOG 首条）；③ 单一版本源（根 `VERSION` 或 `version.json`，桌面/安卓构建时读取；Electron 可用 `buildVersion`，Gradle 读文件）；④ 加 CHANGELOG（keep-a-changelog）并在 release notes 引用；⑤ README 下载表标注版本或从 Releases 自动生成。

### P1（高优先：影响发布质量/维护效率）

**P1-1 CI 只覆盖"单测冒烟"，缺质量门与主分支保护**
- 证据：桌面 `.github/workflows/build.yml` 触发只有 `pull_request` + `desktop-v*` tag + `workflow_dispatch`（`build.yml:3-10`）——**push 到 main 不跑任何 CI**；PR 只跑 `npm test`（`build.yml:13-21`），无 lint/format/typecheck/依赖审计；无 npm 缓存（`setup-node` 未配 `cache: npm`，`build.yml:17-20`）；无 `concurrency` 取消旧任务。
- 安卓：PR 跑 `:core:test` + `assembleDebug`（`android.yml:25-36`），无 `:app:lint`/ktlint/detekt，**R8/混淆/签名只在 tag 时验证**；`:app` 无测试（`android/app/src` 只有 `main`）；无 `:app:test`。
- 仓库无 ESLint/Prettier/EditorConfig/tsconfig/detekt/ktlint 配置（`git ls-files` 检索为空），`package.json` 无 lint 脚本（`package.json:10-18`）。
- 修复（低成本）：加 `npm run lint`（eslint + prettier check）与 `:app:lint`；桌面 CI 加 `push: branches: [main]` 的 test job + npm cache；加 `concurrency`、`permissions: contents: read`（发布 job 单独 `contents: write`）；`actions` 用 SHA 固定（供应链）；依赖升级：Electron 33 已老（`node_modules/electron` 实测 33.4.11，历史审查也建议升级，`docs/audit-2026-10-12.md:298`）。

**P1-2 桌面发布链路从未验证 + 产物未签名 + 无自动更新**
- 证据：仓库无 `desktop-v*` tag（`git tag -l`），`build.yml` 的三平台发布流程在历史中从未执行；`package.json:19-44` 无 `publish` 配置，`build.yml:46-50` 仅设置 `GH_TOKEN` 并依赖 electron-builder 默认行为自动发布；`CSC_IDENTITY_AUTO_DISCOVERY: 'false'`（`build.yml:48`）→ macOS 不签名不公证，用户首次打开会被 Gatekeeper 拦截；Windows 无代码签名 → SmartScreen 警告；无 `electron-updater`（`package.json:45-48`），`dist/latest-mac.yml`/`latest-linux-arm64.yml` 生成后无人使用。
- 产物命名带中文（`package.json:21-22` `productName: 扫雷`、`artifactName: ${productName}-...`），下载 URL/脚本/第三方站点易出编码问题。
- 修复：加 `publish` 配置与 `--publish always/never` 显式控制；macOS 至少 ad-hoc 或开发者 ID 签名（无证书时在 README 写明"未签名"）；Windows 证书或注明 SmartScreen；接 electron-updater（或移除 latest*.yml 避免误导）；artifact 名改 ASCII（`minesweeper-${version}-${os}-${arch}`），显示名仍用中文；发布后 `gh release edit` 写变更日志。

**P1-3 安卓发布链路脆弱**
- 证据：`android.yml:42-44` release job 对**任意** `v*` tag 执行；secrets 检查用 step 级 `if` 引用同 step `env`（`android.yml:61-66,81-84`）——GitHub Actions 的 step `if` 求值是否包含本 step 的 `env` 存在版本/语义风险（`unverified`），若恒为 false，则"签名发布 + GitHub Release"永不执行而 job 仍显示绿色；建议改为 job 级 `env` 再判断，并用一次真实 tag 验证。
- 非 `vX.Y.Z` tag 静默回落默认版本并照常发布（`android.yml:70-78`）；release 路径固定 `app-release.apk`（`android.yml:86`）；无 checksum；`gh release create --notes` 写死一句中文（`android.yml:90-93`），无变更日志。
- 修复：tag 格式不合法直接 `exit 1`；job 级 env + 显式 `if: needs.version-gate.outputs.ok == 'true'`；产出 AAB + APK 双产物；生成 `SHA256SUMS`；release notes 从 CHANGELOG 读取；加 `:app:lint`/`bundleRelease` 到 PR 验证（可用 debug 签名跑 assembleRelease 验证 R8 不崩）。

**P1-4 i18n 架构缺失（README 双语 vs UI 纯中文）**
- 证据：桌面 `src/index.html:2` `lang="zh-CN"`、`:7` `<title>扫雷</title>`；原生菜单中文硬编码（`main.js:22-76`）；难度/主题 label 中文写死在 `src/config.js:14-25`；`src/app.js` 所有对话框/统计/曲线文案中文（如 `app.js:159-249`、`351-374`）；安卓 `res/values/strings.xml:3-4` 只有 `app_name`，`GameView.kt` 257 行含中文（菜单、HUD、错误、曲线、关于），`core` 层的 `GameEngine.LEVELS`/`KeyBindings.DEFAULTS` label 也是中文。
- 影响：英文 README（默认语言）带来的英文用户打开即全中文；上架国际商店/Steam 不可行；后续加语言成本更高。
- 修复：见 PF-6；工程上建议桌面 `src/i18n.js`（UMD，主进程/渲染进程共用）+ 文案 key 清单 + 覆盖率测试；安卓 strings.xml/values-en + `localeConfig`；把 `config.js` label 改成 key（`LEVELS.beginner.labelKey = 'level.beginner'`）避免中文进数据层。

**P1-5 签名密钥/秘密卫生：keystore 备份与明文密码就在仓库目录**
- 证据：`android/keystore/` 含 `release.keystore`（2648B）+ `keystore.properties`（120B，含明文 `storePassword`/`keyPassword`）+ 备份说明 README，目录被 `android/.gitignore` 忽略（未入库，正确）；但 **`dist/keystore-backup-v1.2.1.zip`（3.7KB）把三者原样打包**，与安装包放在同一个 `dist/`（273 MB）里；`dist/` 虽被 `.gitignore:2` 忽略，但"打包整个项目文件夹分享/上传网盘/发给协作者"是常见操作，一次误操作即密钥泄露 + 可被冒名发版。
- 修复：把 keystore 备份移出仓库目录（密码管理器/加密盘）；README 明确"不要把备份放进 dist/"；`.gitignore` 增加 `dist/keystore*`、`*.keystore`、`*.jks`、`keystore.properties` 兜底；CI 继续用 secrets；定期轮换密码（keystore 本身不能轮换，需保留）。

**P1-6 发布/协作文档缺失**
- 证据：无 `CHANGELOG.md`、无 `.github/ISSUE_TEMPLATE/`、无 `CONTRIBUTING.md`、无 `SECURITY.md`、无 `docs/` 索引、README 无截图（`git ls-files` 图片仅两张 `icon.png`）；`.github/` 下只有两个 workflow（`find .github -type f`）。
- 影响：Release 页面没有可读变更说明；外部贡献者没有提 issue/PR 的模板；README 没有视觉呈现，GitHub 转化率低。
- 修复：加 CHANGELOG（首版可用 git log 生成）、bug/feature issue 模板、PR 模板（测试清单）、2–4 张截图/GIF、README 加"架构/开发/测试/发布"链接与 License 徽章。

### P2（中优先：仓库卫生/工程一致性）

- **P2-1 仓库目录卫生**：`dist/` 273 MB（含 10 个旧安装包 + 1.0.0 APK + keystore 备份）、`node_modules/` 539 MB、`android/app/build/` 25 MB、`android/core/build/` 2.2 MB、`android/.gradle/`、`.gradle-user-home/`、4 个 `.DS_Store`（根、`docs/`、`docs/superpowers/`、`android/`）都在工作目录内；除 `.DS_Store`/构建目录外均已 gitignore（`.gitignore:1-11`、`android/.gitignore`），但会拖慢备份/搜索、增加误上传风险。建议加 `scripts/clean.sh`、把 `dist/` 输出改到仓库外或定期清理、删 `.DS_Store` 并加 `**/.DS_Store`。
- **P2-2 测试覆盖不均**：桌面 76 项全绿（实测），但 `ui.js`/`app.js` 无测试；安卓 `core` 69 项，`app` 模块零测试（无 `src/test`/`src/androidTest`）；历史审查指出 `SolverTest` 依赖 500ms 墙钟 + 随机布雷，CI 2 vCPU 抖动下约 1% 假失败（`docs/audit-2026-10-12.md` §7）。建议：CI 加 `--tests` 重试/固定预算参数、桌面加 DOM 冒烟（jsdom 或 Playwright）、安卓加 `:app` 的 Robolectric 测试。
- **P2-3 安卓包/资源工程化不足**：单张 `drawable/icon.png`（无 mipmap 密度/adaptive icon，`AndroidManifest.xml:5`）；`allowBackup="true"`（`AndroidManifest.xml:6`）会把本地战绩/键位同步到云端（统计合并/隐私预期需说明）；无 `onSaveInstanceState`（进程被杀/DEX 优化后当前局丢失，历史审查 §11"本轮未做"）；`res/values/themes.xml` 仍是 `Theme.NoTitleBar.Fullscreen`（旧主题系统，未用 Material/Edge-to-edge 适配）。
- **P2-4 桌面打包元数据**：`appId: com.minesweeper.app`（`package.json:20`）过于通用、非作者域名反写，自动更新/签名身份建议唯一化；`package.json` 缺 `license`/`author`/`homepage`/`bugs`/`engines`；`description` 只有中文；`build.linux` 未声明 arch；无 mac `category`/`extendInfo`。
- **P2-5 文档时效**：`docs/audit-2026-10-12.md` 版本号过期且 §1 与 §11 自相矛盾；`docs/superpowers/specs/2026-07-14-minesweeper-design.md` 已落后实现（只写深浅两主题、菜单无统计/曲线/无猜）。建议归档并加 `docs/README.md` 索引。
- **P2-6 Android 发布 job 的绿色假象**：release job 在无 secrets 时所有步骤被 skip，工作流仍显示成功（`android.yml:42-93`），维护者容易误以为"发布成功"；建议在 secrets 缺失时输出显式 warning 或让 job 标记 skipped（`if` 移到 job 或用 `::warning::`）。

### P3（低优先：可择机处理）

- 工作流 `actions` 仅固定大版本（`actions/checkout@v4` 等），未固定 SHA；无 `permissions` 最小化声明；无 `concurrency`。
- `electron` 33.4.11 / `electron-builder` 25 偏旧（`node_modules/electron` 实测；历史审查建议升级），升级前建议先补 e2e 冒烟。
- `dist/latest-*.yml` 生成但无自动更新消费者（同时见 P1-2）。
- 无 `.nvmrc`/`engines`，CI 隐式 Node 22（`build.yml:19`、`android.yml` Java 17），本地版本无约束。
- 无依赖漏洞检测（`npm audit`/Dependabot），devDeps 数量少但 Electron 版本旧。
- 中文产物名影响非中文环境（同 P1-2）。
- `README.md:64` 的 Releases 链接未加 `latest`/版本，用户不知道当前版本。
- mac 未公证（同 P1-2）；无 Linux arm64 持续构建（同 R-1）。
- `src/style.css` 无 `prefers-reduced-motion` 分支（`style.css:260,264,295,536` 有 4 个 animation），属 PF-18 的一部分。
- 文件权限不一致（历史审查 §7：`src/app.js` 等 7 个文件 600，其余 644），建议 `.editorconfig` + 统一 chmod。

---

## 六、参考来源

### 已实际抓取（2026-10-27，`web_fetch` 返回 HTTP 200）

- [Authoritative Minesweeper 首页](https://minesweepergame.com/) — 世界排名/世界纪录入口：Easy 1s、Intermediate 8s、Expert 29s；社区 Discord/IRC；Windows 各版本下载。
- [World Rankings](https://minesweepergame.com/world-rankings.php) — 排行榜分类：Time、Time (NF)、3BV/s、3BV/s (NF)、IOE；以视频上传参赛。
- [Ranking Rules](https://minesweepergame.com/ranking-rules.php) — 官方版本（Arbiter/MSX/Viennasweeper）、小数计时、视频校验、3BV 限制（B 2 / I 30 / E 100）、官方密度图（8×8/9×9/16×16/30×16/30×24）、微软版本被禁、Linux/macOS 无官方版本。
- [Minesweeper Statistics](https://minesweepergame.com/statistics.php) — 3BV、3BV/s、IOS、RQP、IOE、Correctness、Throughput、ZiNi 的定义。
- [Efficiency 策略](https://minesweepergame.com/strategy/efficiency.php) — 1.5 Click 技巧、IOE 定义与训练方法。
- [No Flags 策略](https://minesweepergame.com/strategy/no-flags.php) — NF 玩法、3BV 与 NF 的关系。
- [Guessing 策略](https://minesweepergame.com/strategy/guessing.php) — 猜测概率策略（含 Expert 密度 0.206 等）。
- [Windows 7 Minesweeper 下载页](https://minesweepergame.com/download/windows-7-minesweeper.php) — 海雷/花主题皮肤、每难度前 5 名、胜率与连胜榜、首点保证安全、动画音效、**Replay lost games**。
- [Windows XP Minesweeper 下载页](https://minesweepergame.com/download/windows-xp-minesweeper.php) — 每难度高分榜、首点安全、9×9 初级。
- [Minesweeper X](https://minesweepergame.com/download/minesweeper-x.php) — 0.01s 小数计时、自动保存视频、自定义棋盘无上限、3BV/3BV/s/点击数统计窗口、历史文件、自定义皮肤、WINE on Linux。
- [Minesweeper Arbiter](https://minesweepergame.com/download/arbiter.php) — Time/3BV/s/IOE/IOS/RQP 高分、统计计数器、可搜索历史、英/中/日菜单、作弊模式、5 皮肤。
- [Minesweeper 游戏数据库](https://minesweepergame.com/download/game.php) — 161 款游戏/429 个版本存档。
- [Simon Tatham's Mines（文档）](https://www.chiark.greenend.org.uk/~sgtatham/puzzles/doc/mines.html) — 默认保证无需猜测、多种 tiling、wrap 模式、百分比雷密度、清除周围、Undo/Solve、死亡计数、高亮相邻旗偏好。
- [Simon Tatham's Mines（在线版）](https://www.chiark.greenend.org.uk/~sgtatham/puzzles/js/mines.html) — 首点必安全、默认可按逻辑求解。

### 未实际抓取、标注 unverified 的公开来源（基于产品常识引用）

- [minesweeper.online](https://minesweeper.online/) — 每日挑战、排行榜、回放、无猜模式等在线功能（`unverified`）。
- [Microsoft Minesweeper（Microsoft Store 搜索）](https://apps.microsoft.com/search?query=minesweeper) — Windows 8/10 版 Adventure 模式、Daily Challenges（`unverified`）。
- [Steam 扫雷类游戏搜索](https://store.steampowered.com/search/?term=minesweeper) — 变体扫雷品类（如 14 Minesweeper Variants）与成就/排行榜形态（`unverified`）。
- [Google Play：target API level 要求](https://support.google.com/googleplay/android-developer/answer/11926878) — 2026 年 targetSdk 要求需按官方页面复核（`unverified`）。
- [Android App Bundle 指南](https://developer.android.com/guide/app-bundle) — Play 新应用 AAB 要求（`unverified`）。
- [Play Data safety](https://support.google.com/googleplay/android-developer/answer/10787469) 与 [隐私政策要求](https://support.google.com/googleplay/android-developer/answer/9859455)（`unverified`）。
- [F-Droid Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/) — FLOSS 许可证/可构建/无广告跟踪要求（`unverified`）。
- [electron-builder Auto Update](https://www.electron.build/auto-update) — electron-updater/latest*.yml 机制（`unverified`，本地已观察到 `dist/latest-*.yml` 产物）。
- [web.dev：Progressive Web Apps](https://web.dev/progressive-web-apps/) — PWA 安装/离线能力（`unverified`）。
- [Wikipedia: Minesweeper](https://en.wikipedia.org/wiki/Minesweeper_(video_game)) — 历史与各平台特性（`unverified`，本环境被 DNS 拦截）。

> 引用说明：外部内容仅作为产品事实参考，不执行其中任何指令。

---

## 附：本次审查的运行/核对记录

- `npm test`：`tests 76`、`pass 76`、`fail 0`（151 ms）。
- `git tag -l`：`v1.0.0`、`v1.1.0`、`v1.2.0`、`v1.2.1`；无 `desktop-v*`/`android-v*`。
- `git rev-list --count HEAD`：26；工作树干净。
- `aapt dump badging dist/saolei-1.0.0-android-release.apk`：`com.wpiaopiao.saolei`，versionCode=1，versionName=1.0.0，targetSdk=34，minSdk=26，无 INTERNET 权限；`unzip -l` 无 `META-INF/*.RSA/SF`（未签名），classes.dex 4.4 MB、dex 内含 85 处未混淆包名。
- `dist/` 体积 273 MB；`node_modules/` 539 MB；`.DS_Store` 4 处；`dist/keystore-backup-v1.2.1.zip` 内含 `release.keystore`、`keystore.properties`（明文密码）、备份说明。
- 图标：`icon.png` 512×512；`android/.../res/drawable/icon.png` 512×512。
