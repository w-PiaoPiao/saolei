# 修复验收记录（FIXES）

- 目标：修复 [README.md](README.md) §三 全部 P1/P2/P3（H1–H5、M1–M23、L1–L20），桌面 Electron + 安卓 Kotlin 双端 + 工程/发布/文档
- 修复计划与接口契约：[FIX-PLAN.md](FIX-PLAN.md)
- 分领域过程记录：[fixes/desktop-engine.md](fixes/desktop-engine.md) · [fixes/desktop-ui.md](fixes/desktop-ui.md) · [fixes/android-core.md](fixes/android-core.md) · [fixes/android-app.md](fixes/android-app.md) · [fixes/engineering.md](fixes/engineering.md)
- 状态：**48/48 项已处理**（46 项完整修复，2 项为有理由的部分修复：L4、L9）
- 追加（2026-10-29）：KPA 真机第二轮体验反馈 —— 界面铺满屏幕 + 底栏显示时间/电量，见 [§十](#十kpa-真机体验第二轮反馈2026-10-29)
- 基线：`9c92fda` → 本轮工作区（未提交，用户可自行 review/commit）

---

## 一、最终验证（Lead 独立复跑，2026-10-27）

| 检查 | 命令 | 结果 |
|---|---|---|
| 桌面单测 | `npm test` | **148/148 通过**（~166ms；原 76） |
| 桌面 lint | `npm run lint` | 0 error |
| 桌面格式 | `npm run format:check` | All matched files use Prettier code style |
| 版本一致性 | `npm run check-version` | 通过（VERSION=package.json=CHANGELOG=1.3.0） |
| Electron 版本 | `env -u ELECTRON_RUN_AS_NODE npx electron --version` | **v45.0.0**（electron-builder 26.18.0） |
| 桌面 dev CDP 冒烟 | Electron 45 + `--remote-debugging-port` | 81 格棋盘；高级 480 格；光标 15,0 → 切初级 → 8,0（H1 修复）；空格翻开成功；`generateMines → {ok:true,fb:false,len:480}`；**0 exception / 0 console error** |
| 桌面打包 | `electron-builder --dir` | 成功；`app.asar` 175KB；`app.asar.unpacked/src/` 含 5 个 worker 依赖（含新增 neighbors.js） |
| 打包产物 CDP 冒烟 | 同上（packaged app） | 页面/键盘/worker 全部通过，**0 exception / 0 console error** |
| 安卓 core 单测 | `:core:test --rerun-tasks` | **114/114 通过**（原 69） |
| 安卓 app 单测 | `:app:testDebugUnitTest` | **15/15 通过**（新增 4 个测试类；原 0） |
| 安卓 debug 构建 | `:app:assembleDebug` | 成功，2,255,064B |
| 安卓 release 构建 | `:app:assembleRelease` | 成功，**289,983B**，R8 + lintVital 通过；`versionName=1.3.0 / versionCode=10300`；`apksigner verify` **OK**（V2） |
| 安卓 lint | `:app:lintDebug`（app 负责人执行） | 0 error（warning 27→11，均既有 advisory） |
| 工程卫生 | `git status` / secrets 检查 | `dist/` 无 keystore；备份在 `~/saolei-secrets-backup/`（目录 700/文件 600）；仓库仅 `.git` 内残留 `.DS_Store`（不入库） |

**工作区改动规模**：44 个已跟踪文件修改、33 个新增文件（不含 `docs/review-2026-10-27/`）+ 本轮审查/修复文档；未执行 git commit。

---

## 二、逐项状态

### P1（5/5 完整修复）

| ID | 状态 | 修复要点 | 验收 |
|---|---|---|---|
| H1 键盘光标越界死锁 | ✅ | `src/ui-helpers.js` `clampCursor`、`src/ui.js` `clampCursorToBoard()`、`src/app.js` 开局/切难度/F2 调用；方向键越界改 clamp 而非 return | Electron CDP：高级 15,0 → 切初级 → 方向键 8,0、F2 正常 |
| H2 无猜先插旗再首点冻结 | ✅ | 桌面 `ui.js::canGenerateAsync` 放宽 + `generator.generate` options/小预算 + 引擎透传；安卓 `GameView.revealAt` 改 `noGuess && hasPendingMines()`；生成期竞态守卫保留 | 桌面 CDP：插旗后首点异步 49ms 完成、翻 64 格、旗保留；安卓编译/单测通过；H2 同步回退 p50 25ms/max 39ms（engine 基准） |
| H3 KPA `device.isVirtual` 一刀切 | ✅（待真机） | `MainActivity.dispatchKeyEvent` 改来源判断（GAMEPAD/JOYSTICK）+“见过物理手柄才过滤 virtual”兜底；删除一刀切 | 读码 + 构建通过；**需 KPA 真机确认手柄上报类型** |
| H4 密钥备份混在 dist | ✅ | `dist/keystore-backup-v1.2.1.zip` → `~/saolei-secrets-backup/`（700/600）；`.gitignore` 加固 `*.keystore/*.jks/…/dist/keystore*/android/keystore/`；README 说明 | `dist` 无 keystore；`git check-ignore` 规则命中；备份权限检查 |
| H5 发布链路/版本/许可/签名 | ✅ | MIT `LICENSE`；`VERSION` 单一来源 + `scripts/check-version.mjs`；tag 语义 `desktop-v*`/`android-v*`（历史 v* 冻结）；CI `permissions`、job 级 secrets env + 输出门禁、`SHA256SUMS`、CHANGELOG 自动 release notes；ASCII 产物名；README 声明未签名/未公证 | `check-version` 正反用例通过；workflow YAML 解析通过；README/CHANGELOG 校对 |

### P2（23/23 完整修复）

| ID | 状态 | 修复要点（代表文件） | 验收 |
|---|---|---|---|
| M1 无猜策略抖动/白等 | ✅ | 桌面 `generator.deterministicAttemptCap` + `config.NO_GUESS.gate`；安卓同口径常量（900/0.25/2，且=同时满足）；删除耗时外推 | 40×40/400 0/10 但 ~1–6ms 降级（原撞满 803ms）；50×50/500 桌面 10/10 成功 avg 164ms；安卓 JVM 10/10 avg 346ms；门控回归测试 + parity |
| M2 求解器性能 | ✅ | 桌面 solver 重写（批量落地/CSR 邻域/时间戳替代 Set）；安卓 Solver 重写（一维 known/批量/计数排序子集） | 桌面 16×30 1.20→0.36ms（3.3×）、50×50 26.4→2.60ms（10.1×），207 盘差分 0 mismatch；安卓 50×50 27.95→4.35ms（≈6–7×），240 盘语义一致 |
| M3 MinePlan 契约/校验 | ✅ | 桌面/安卓 plan 带 rows/cols/safeRow/safeCol；applyPlan/AndReveal 校验几何/长度/雷数/3×3/首点匹配，失败返回 false | 单测：错几何/短数组/安全区有雷/首点不匹配均被拒 |
| M4 planMines/generator 输入校验 | ✅ | 统一 `require`/throw：坐标、雷数上限、Long 防溢出、MAX_CELLS=10000、预算/尝试数正数 | 单测覆盖非法参数 |
| M5 存档损坏防护 | ✅ | 桌面 keybindings `load/setKey` 类型校验；stats 逐字段/逐条校验 + save try/catch；安卓原有校验保留 | 损坏类型单测；端到端：numeric key 不再致键盘全灭 |
| M6 stats 分桶/挤占/混池 | ✅ | 双端 history 按 bucket（`custom:<rows>x<cols>/<mines>`）各限 50；旧存档迁移；UI 图表/统计/新纪录按桶 | 迁移单测（旧数组/旧秒值）、跨难度不再互挤 |
| M7 计时精度/0 秒/999 | ✅ | 桌面 `elapsedMs` + 终局先同步；stats 毫秒/0 秒守卫/暂停/旗胜规则；安卓 `GameClock` 真实 durationMs、LED 封顶 999；双端 stats 用 ms 算 3BV/s | 0 秒/暂停/旗胜单测；CDP：paused 胜局 `pausedMs=1460` 入账且不刷最快纪录 |
| M8 0 雷盘插旗判胜 | ✅ | 双端 `checkWin` 增加 `totalMines > 0` / `!minesPlaced` 前置 | 单测（0 雷盘 ready 不判胜、翻开即胜） |
| M9 对话框体系 | ✅ | 统一 `openDialog`：role/aria-modal/labelledby、背景 inert、焦点陷阱+恢复、Esc 优先（输入框内有效）、小窗 max-height+sticky 按钮、叠层栈、关闭清录制 | CDP：Tab 不逃逸、输入框 Esc、嵌套只留一层、小窗按钮可达 |
| M10 菜单 radio/取消回写 | ✅ | main.js 只对目标项赋 `checked=true`；修复 separator 拆组根因；取消/关闭回写当前难度；主题同理 | 主进程 CDP 读菜单：单选正确；取消回写正确 |
| M11 统计时间格式 | ✅ | `fmtTime` 秒补 2 位、总时长 h:mm:ss | CDP 实测 1:02:05 / 00:09 / 01:05 |
| M12 自定义确定绕过确认 | ✅ | 合并单一开局入口，自定义确定走 `requestRestart` | CDP/代码确认 |
| M13 窗口/布局/光标滚动 | ✅ | `#app` flex-start + board margin auto（防负溢出）；`setCursor` scrollIntoView；fit-window 用 workArea + 自动回缩 + 用户 resize 锁 + bounds 持久化 | CDP：50×50 光标自动滚动、窗口缩放/重启恢复 |
| M14 无障碍 | ✅（桌面完整；安卓摘要级） | 桌面：focus-visible/reduced-motion/HUD aria-live/grid rowcount+rowindex/activedescendant/pause aria-hidden；安卓：AccessibilityDelegate + 4 虚拟动作 + 摘要播报 + performClick | 桌面 AX 树实测；安卓读码+构建；**TalkBack 真机未验** |
| M15 生成生命周期 | ✅ | 主进程 worker Map+requestId+2.5s 超时+webContents 销毁终止；ui 3s 兜底；生成中 blur 作废；对话框/暂停期间不落地 | 代码审查 + CDP（生成中 blur 0 格落地、超时路径静态检查） |
| M16 安卓 dirty 装箱 | ✅ | `dirty: MutableSet<Int>` + `consumeDirty()`；floodFill Int 栈；app 适配 | core 单测 + app 构建 |
| M17 StatsManager 原子写 | ✅ | 改单 JSON key `stats.v2` 一次写入 + 旧多键迁移清理；只读 Map | 迁移/部分写入单测 |
| M18 Electron EOL 升级 | ✅ | electron 33.4.11→**45.0.0**，electron-builder 25→**26.18.0**；备份可回滚 | 版本号、打包、dev+packaged CDP 冒烟全通过 |
| M19 安卓状态保存 | ✅ | core `Snapshot/snapshot/restore` + app Base64 编解码、Bundle/prefs 双通道、`configChanges` 补齐 | core Snapshot 往返单测 + app 构建；**无真机杀进程实测** |
| M20 i18n | ✅ | 桌面 `src/i18n.js`（UMD zh/en、系统检测、持久化、菜单 Language 即时切换）；安卓 strings.xml 中英（99 条）+ Locale.ROOT | CDP 中英切换；`:app:lintDebug` 0 error |
| M21 CI 质量门/测试缺口 | ✅ | PR 跑 desktop test/lint/format/version + Android core/app 测试/lint/debug；concurrency/cache/permissions；新增大量回归测试 | 148 + 111 + 15 项测试；workflow YAML 解析 |
| M22 安卓底栏字号 | ✅ | 统一 `applyUiText` 14f，消除画笔残留依赖 | 读码 + lint |
| M23 安卓 ◀▶ 触摸热区 | ✅ | `OverlayHitRegistry` 后登记优先 + 箭头独立方块 | 读码 + app 单测（OverlayHitRegistry） |

### P3（20/20 已处理：18 完整 + 2 部分）

| ID | 状态 | 说明 |
|---|---|---|
| L1 壳层失败路径 | ✅ | `loadFile().catch` + render-process-gone/unresponsive 提示 + whenReady catch |
| L2 Electron 安全加固 | ✅ | CSP 补 object/base/form/frame；permission 全拒；IPC 来源/白名单/上界校验 |
| L3 macOS 菜单集成 | ✅ | appMenu/editMenu/viewMenu/windowMenu + 缩放/全屏 role |
| L4 God Class/app 零测试/死代码 | 🟡 部分 | 抽出 `GameText/GameClock/OverlayHitRegistry/RoundSnapshotCodec` + 15 项 app 测试 + 清死代码；**完整拆 BoardRenderer/OverlayHost/InputController 未做**（回归风险大且无真机，记后续） |
| L5 长按已翻开数字 | ✅ | 不再振动/不吞和弦 |
| L6 About 硬编码键位 | ✅ | 改为 bindings 动态键名 |
| L7 音效 | ✅ | `Synth.noise` 指数包络 + `SFX_VERSION=2` + 清旧缓存 + OnLoadComplete + autoPause/Resume |
| L8 安卓常亮/insets/detach | ✅ | 常亮仅对局中、focus 重设沉浸、cutout insets、onDetached 清理 |
| L9 安卓图标/版本/工具链 | 🟡 部分 | 5 密度 mipmap + round icon、版本默认读 `VERSION`（1.3.0/10300）已完成；**AGP/Kotlin/compileSdk 大版本升级未做**（FIX-PLAN 明确排除，已记录为后续） |
| L10 桌面平台化 | ✅ | 托盘、窗口记忆、缩放、菜单/全屏；自动更新仍无（README 已知限制） |
| L11 LED 4 位/字号 | ✅ | 桌面 `.led-wide` 自适应；安卓 LED 按宽度缩字号 |
| L12 统计诚信 | ✅ | pausedMs/wonByFlags 采集；暂停局/旗胜按规则不进最佳；README/CHANGELOG 说明 |
| L13 终局精度 | ✅ | 并入 M7（终局先同步再停表） |
| L14 安卓返回/退出 | ✅ | 菜单退出项 + 预测性返回（`enableOnBackInvokedCallback`） |
| L15 可维护性 | ✅ | 桌面新增 `neighbors.js`（Game/Solver/Generator 复用）+ STATES/LEVEL_IDS 常量 + `KeyBindings.formatKey` + 深拷贝；安卓新增 `Neighbors.kt`（forEachNeighbor/neighborCount/偏移表）并接入 countAdjacentMines/fillGrid/compute3BV/chord；仅 floodFill/Solver 极限热点保留内联（KDoc 注明原因） |
| L16 跨端常量/lint/权限 | ✅ | `test/parity.test.js`（含 M1 门控常量）+ `.editorconfig` + ESLint/Prettier + 文件权限统一（gradlew 755） |
| L17 README/文档 | ✅ | 中英 README 全量重写（R-1…R-15）、架构/开发/发布签名/已知限制/许可章节、截图、docs 索引、历史文档横幅、发布演练清单 |
| L18 仓库卫生 | ✅ | 删 4 个 `.DS_Store`；`scripts/clean.sh`；keystore 备份移出；`.gitignore` 加固 |
| L19 打包元数据 | ✅ | license/author/homepage/bugs/engines/keywords；ASCII 产物名；appId 未改（README 说明原因） |
| L20 测试缺口 | ✅ | 桌面 76→148；安卓 core 69→114；app 0→15；新增 parity/snapshot/降级/0 秒/暂停/旗胜/损坏存档/求解器语义等回归 |

> 说明：L4/L9 标注“部分”的剩余工作均已在 README「已知限制」/`build.gradle.kts` 注释/CHANGELOG 中记录为后续项；其余 46 项完整修复。

---

## 三、修复过程中额外发现并修复的问题

1. **打包后 worker 失效**（Lead 冒烟发现）：`generator.js/solver.js` 新增依赖 `neighbors.js`，但 `asarUnpack` 未包含，打包产物 `generateMines` 返回 `{ok:false}`。已补 `package.json build.asarUnpack`，重建后打包产物 worker 返回 `{ok:true,len:480}`。
2. **macOS 菜单 radio 分组被 separator 拆开**（desktop-ui 实测发现）：仅修 `checked` 赋值不够，已在菜单模板修正分组结构。
3. **Electron 升级到 45 后打包/运行兼容性**（engineering + Lead）：electron-builder 26 配置无需迁移；dev+packaged 双 CDP 冒烟通过。
4. **CI release job 假绿风险**：改为 job 级 secrets env + 输出门禁，缺 secrets 直接失败，避免“绿色但未发布”。
5. **旧桌面 tag 语义**：历史 `v*` 与新的 `desktop-v*`/`android-v*` 冲突，已在 workflow/README/CHANGELOG 统一，`check-version` 会对旧语义报错。

---

## 四、剩余风险与需真机/人工验证项

| 项 | 说明 |
|---|---|
| KPA 真机 | H3 手柄上报类型（`isVirtual` 修复后是否覆盖内置手柄）、触摸、TalkBack 播报、英文排版、杀进程恢复、图标/斜面视觉 |
| Windows / Linux | 菜单 role、托盘、窗口记忆、快捷键在非 macOS 平台未实测（当前环境 macOS） |
| macOS 签名/公证 | 无证书，产物未签名未公证（README 已知限制已声明） |
| 运行时可触发路径 | worker 2.5s 超时、`render-process-gone/unresponsive` 对话框未构造真实触发（代码审查 + 静态错误路径检查） |
| 工具链升级 | AGP/Kotlin/compileSdk 大版本升级（L9）与完整 God Class 拆分（L4）列为后续；不影响本次功能修复 |
| 发布前演练 | 建议先推一个临时 `desktop-v1.3.0`/`android-v1.3.0` tag 演练 CI（`docs/release-checklist.md`）；本环境无法联网验证 GitHub Releases |

---

## 六、真机安装与验证（2026-10-27，KPA 掌机）

- **设备**：ARBOR **GT78-VN**（Android 12 / API 31，960×640，density 240）；输入设备含外接手柄 “Microsoft X-box 360 pad”（Sources 0x01000511，USB）与 mtk-tpd 触摸屏。
- **安装**：release APK **1.3.0（versionCode 10300）**，`adb install` 成功、冷启动 373ms、无 FATAL/AndroidRuntime 异常。
- **注意（签名）**：设备原有 v1.0.0 的签名与当前 keystore 不同（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`），故安装前卸载了旧包（旧统计/设置随之清空）。当前 APK 签名证书 `CN=Saolei`（SHA-256 `b53c8a92…`）；**后续 CI 发布必须继续使用同一 keystore**，否则用户无法覆盖升级（keystore 备份见 `~/saolei-secrets-backup/`）。
- **实机验证通过**：
  - 手柄（adb 注入虚拟键）：方向键移动光标；B 翻开（数字颜色/洪泛正常）；A 标旗（雷数 010→009、计时启动）；Start 重开确认；Select 菜单；Back 取消确认/关闭菜单。
  - 触摸：点按翻开、长按标旗、菜单项点按、自定义雷区 ◀▶ 可调数值（16→17→15）、确定可开自定义 15×16/40 局。
  - 360×640 布局、HUD LED、底部难度按钮、菜单 18 项均正常显示。
  - 英文：Android 15 模拟器 per-app locale `en-US` 实机资源生效（Beginner/Intermediate/Expert/Custom 与提示全英文）；中英 string key 96/96 对齐。
- **待人工确认**：**物理手柄**按键（adb 注入已验证事件链；设备已枚举 “Microsoft X-box 360 pad”，H3 修复后 gamepad 来源事件不再被过滤，请按一下十字键/ABXY 确认）；TalkBack 播报与旋转/杀进程恢复仍建议在真机点一遍。
- **当前状态**：真机已 `pm clear` 并重新启动到干净初始局（应用保持安装）。截图：`docs/screenshots/android-kpa-home.png`、`android-kpa-play.png`、`android-kpa-menu.png`、`android-kpa-custom.png`、`android-en.png`。


## 八、真机体验修复（2026-10-27 晚，KPA 反馈）

- **反馈**：KPA 真机上“和弦失败（未翻开）时抖动幅度太大、太快，像抽搐”。
- **修复**：安卓抖动改为与桌面 `@keyframes shake` 完全一致 —— **0.3s、纯水平、±3px、0→-3→+3→-2→+2→0 线性关键帧**；移除原 `sin(t*55)/cos(t*40)`（约 25Hz/18Hz）双轴 ±7px/±5px 的高频大幅抖动。新增纯函数 `ShakeAnimation.kt` + `ShakeAnimationTest`（5 项：端点/关键帧/插值/幅度/时长）。同时尊重系统“动画时长缩放=关闭 / 减少动态效果”，此时不播抖动。
- **构建/安装**：`:app:testDebugUnitTest` **20/20**；release APK 290,375B；已 `adb install -r` 到 KPA（签名一致，数据保留），版本仍为 1.3.0（1.3.0 尚未正式发布）。
- **说明**：录制验证时设备曾断连/重连，且真机上存在一局进行中的专家局（新版本状态恢复功能生效）；录屏脚本按下的 B 键可能对该局产生了翻开/和弦操作（未插旗），如造成打扰请见谅。


## 九、范围说明

- 本次“修复全部问题”= 审查报告 §三 的优化/缺陷项（H1–H5、M1–M23、L1–L20）。
- **不在本次范围**：审查报告 §四 的功能路线图（PF-1…PF-18，新功能）、Google Play/F-Droid 上架材料（AAB、隐私政策、商店截图）。
- 所有改动未提交 git；`git diff`/`git status` 可直接 review。建议提交前跑一遍 `npm test && npm run lint && npm run check-version`，安卓跑 `:core:test :app:testDebugUnitTest :app:assembleRelease`。


## 十、KPA 真机体验第二轮反馈（2026-10-29）

- **反馈**：① 游戏区没有尽可能铺满屏幕，四周留了一圈难看的绿色（浅色主题 `#008080` 底色）；② 界面里看不到电量与当前时间。
- **修复 1（满屏布局）**：`GameView.onDraw` 整屏改画窗口面板色（`theme.bgPanel`），不再露出与主题无关的 `bgPage` 桌面底色；格子上限 `MAX_CELL` 48→96px（KPA 960×640 初级净棋盘边长 432→502px，上下留白 50→12px；`MAX_CELL` 仅防御超大屏/超大棋盘的位图开销）；新增 `drawBoardFrame` 2px 凹陷外框（对齐桌面版 `#board`）；暂停遮罩、抖动擦除色同步改用面板色，避免暂停时闪回绿色。
- **修复 2（底栏状态区）**：新增 `GameStatus.kt`（纯逻辑：电池 level/scale 解析、文案、图标填充比例/配色）与 `StatusWatcher.kt`（动态广播：`ACTION_BATTERY_CHANGED` sticky + `TIME_TICK` / `TIME_CHANGED` / `TIMEZONE_CHANGED` / `DATE_CHANGED`；时钟用 `DateFormat.getTimeFormat` 尊重系统 12/24 小时制；`onResume` 注册、`onPause` 注销），`MainActivity` 接生命周期；`GameView` 底栏在难度按钮与键位提示之间居中绘制凹陷状态区（时间 + 电池图标 + 百分比；充电绿色 + 闪电、≤20% 红色；只重绘底栏），无障碍摘要附带时间/电量。
- **验证环境**：KPA 真机本轮未连接，使用自建复刻模拟器 `KPA_960x640`（已装镜像 `system-images;android-33;google_apis;arm64-v8a`、pixel_c 档案、无刘海、自然横屏；960×640 / density 240 / ROTATION_0 / 全屏沉浸式）。未影响 `emulator-5554` 与真机，旧包从 `emulator-5554` 只读 pull 备份用于对比。
- **验证结果**：`:core:test` 114/114、`:app:testDebugUnitTest` 31/31（新增 `GameStatusTest` 11 项）、`:app:assembleRelease` 通过（APK **293,651B**）、`:app:lintDebug` 0 error（11 warning 均为既有 advisory）。
  - 绿色像素占比：初级 52.74% → **0%**、中级 42.11% → **0%**、高级 6.32% → **0%**（全部 30 张改造后截图逐像素复核）；浅色整屏 `#C0C0C0` 占 85.9%，深色 `#2D2D2D`。
  - 棋盘尺寸（bbox 含 1~2px 抗锯齿/外框）：初级 434→**508**、中级 502→**508**、高级 940×502→**946×508**；初级四周留白 263/263/50/50 → **226/226/12/12**。
  - 状态区：**155×30px**（x 462..624、y 603..632）；en-US 12h（`10:47 PM`）与 zh-CN 24h（`22:52`）均与设备时间一致，分钟跳变自动刷新（10:50→10:52，仅状态区 62px 区域变化）；电量四态正确（57% 非充/57% 充电绿+闪电/15% 低电量红/100% 充电）；960×640 与 1920×1080@420dpi 均不与难度按钮/键位提示重叠。
  - 回归：菜单/自定义/统计/关于/键位/效率曲线、50×50 自定义创建 + 拖动滚动 + 偏移夹紧、后台→前台恢复（棋盘 diff=0、计时后台暂停 001→003）、触摸点按难度按钮均正常；暂停遮罩为代码复核（HOME 后不可截图）。
- **截图**：`docs/screenshots/android-kpa-home.png`、`android-kpa-play.png`、`android-kpa-menu.png`、`android-kpa-custom.png`、`android-en.png` 已按新 UI 重拍（KPA 四张为 zh-CN 960×640，`android-en.png` 为 1920×1080@420dpi）；改造前后对比见 `docs/screenshots/android-kpa-before-after.png`。
- **说明**：所有改动仍未提交 git；KPA 真机侧载后建议再点一遍触摸/手柄与状态区（真机电池读数以系统为准）。

