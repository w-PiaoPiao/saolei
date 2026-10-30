# 安卓 app 层修复记录（第二轮）

- 日期：2026-10-27
- 范围：`android/app/**`（GameView / MainActivity / SoundManager / PrefsStorage / 资源 / manifest / build.gradle.kts）+ 按 §3 契约适配 `android/core` 新 API
- 结论：**分配任务全部完成**（H2、H3、AA-3/4/5/7/8/9/10/11/12/13/14/15/16、L5/L6/L7/L8/L9(图标+版本)/L11/L14、M7/M14/M19/M20/M22/M23 及 core API 适配 AC-2/AC-6/AC-9/AC-10）。唯一"部分"是 L9 的 AGP/Kotlin/compileSdk 大版本升级（FIX-PLAN §1 明确排除，已在 `build.gradle.kts` 注释记录为后续项）。
- 验证：`:core:test` **110 项全绿**；`:app:testDebugUnitTest` **15 项全绿**（新增 4 个测试类）；`:app:assembleDebug` / `:app:assembleRelease` 成功（R8 + lintVital 通过，release APK 289,999B，V2 签名，versionName **1.3.0** / versionCode **10300** 来自仓库根 `VERSION`）；`:app:lintDebug` 0 error（Warning 27 → 11，剩余为 targetSdk/图标形状/多元复数等既有 advisory）。
- 无真机/模拟器：H3（KPA 手柄）、无障碍 TalkBack、i18n 英文排版、视觉类改动（斜面/图标）为读码 + 构建验证，需 KPA 实机冒烟一次。

## 逐项状态

### 输入 / 引擎交互

| ID | 状态 | 关键改动 | 验证 |
|---|---|---|---|
| H2 | 已修 | `GameView.revealAt()` 判定改为 `engine.noGuess && engine.hasPendingMines()`（不再要求 `state=="ready"`）；先做无效操作守卫再走异步。`GameView.kt:733` | 读码：先标旗把 state 置 playing 后仍走后台 `startAsyncFirstReveal`；`applyPlanAndReveal` 自带首点/几何/竞态校验，失败回退同步 |
| H3 | 已修（真机待验） | `MainActivity.dispatchKeyEvent()` 去掉 `device.isVirtual` 一刀切：手柄来源（SOURCE_GAMEPAD/JOYSTICK）物理设备记录"见过手柄"；仅当见过物理手柄后才过滤虚拟设备上的非手柄事件，否则不过滤。`MainActivity.kt:154` | 读码 + 构建；**需 KPA 实机确认手柄上报类型** |
| AC-2 | 已适配 | 后台生成走 `planMines(...)` 新 6 字段 `MinePlan`；应用走 `applyPlanAndReveal(row,col,plan): Boolean`，失败自动回退同步。`GameView.kt:749` 起 | 编译 + `:core:test` 110 绿 |
| AC-6 | 已适配 | `consumeDirty()` 改用 `engine.consumeDirty(): IntArray`（`row*cols+col`），不再直接清内部集合。`GameView.kt:486` | 读码 + 编译；增量渲染路径保持 |

### 渲染 / 交互细节

| ID | 状态 | 关键改动 | 验证 |
|---|---|---|---|
| AA-3 / M22 | 已修 | 新增统一 `applyUiText(size,color,align,mono)`（`:1330`），底栏难度按钮循环前预设 14f（`:1775`），彻底消除"继承上一次绘制残留字号"；HUD/覆盖层文案全部改用该 helper | 读码；lint 0 error |
| AA-4 / M23 | 已修 | `OverlayHitRegistry`（新文件）改为**后登记者优先**；自定义雷区父行先登记、◀/▶ 子按钮后登记并画成独立方块（`GameView.kt:2004` / `drawArrowButton():2070`）。注释写明优先级约定 | 新增 `OverlayHitRegistryTest` 3 项（子元素优先/未命中/边界）全绿 |
| AA-11 / L6 | 已修 | About 页键位文案改为按 `bindings.getDisplayKey(...)` 动态拼接，不再写死默认键。`GameView.kt:2273` | 读码 |
| AA-14 | 已修 | `drawBevel()` 改为向内缩进 w/2 描边（`:1358`），已翻开格边框内缩 0.5px（`drawCell():1645`）→ 笔画完全不跨格，静态位图增量重绘不再擦/盖邻居 | 读码；视觉需真机确认 |
| L11（安卓 LED） | 已修 | `drawLedText()`（`:1340`）按 LED 可用宽度自动缩小字号，剩余雷数 ≥4 位（大棋盘 / 负计数）不再撑破 92px LED | 读码 |
| AA-15 | 已修（部分重构） | 抽出纯逻辑可测对象 `GameText`/`GameClock`/`OverlayHitRegistry`/`RoundSnapshotCodec`（新文件）+ `app/src/test` 4 个测试类共 15 项；删除死代码：`markRevealAnimFromDirty` 别名、重复 `consumeDirty`、无意义三元、`ACTION_OUTSIDE` 死分支；新增 `performClick()`（`:2417`）与 `@SuppressLint("ViewConstructor")` | `:app:testDebugUnitTest` 15/15；lint 0 error |
| AA-10 / L8 | 已修 | `KEEP_SCREEN_ON` 改为仅"对局中且无覆盖层"（`GameView.refreshKeepScreenOn()` + `MainActivity.kt:64` 回调）；`onWindowFocusChanged` 重设沉浸式（`MainActivity.kt:141`）；`displayCutout` insets 应用到 HUD/底栏/棋盘视口（`MainActivity.kt:123` + `GameView.setSafeInsets():459`）；`onDetachedFromWindow` 清理回调并作废后台生成（`GameView.kt:442`） | 读码 + 构建 |
| L14 | 已修 | 新增菜单「退出游戏」（`GameView.kt:984`，对局中先确认，`requestExit():924`）；API 33+ 注册 `OnBackInvokedCallback`（`MainActivity.kt:82`）+ manifest `enableOnBackInvokedCallback="true"` | 读码 + 构建 |

### 计时 / 统计

| ID | 状态 | 关键改动 | 验证 |
|---|---|---|---|
| AA-8 / M7 | 已修 | 新增 `GameClock`（activeMs 不封顶 / durationMs 含暂停 / displaySeconds 封顶 999）；终局先 `pause` 再统计，**向 `StatsManager` 传 `engine.timer`（LED 口径）+ 真实 `durationMs` + `pausedMs` + `engine.wonByFlags`**（按 core 最终口径，未把 time 改成 1）；恢复局调用 `stats.selectBucket(...)` 保证终局计入正确 bucket（`GameView.kt:651/692`） | `GameClockTest` 5 项；编译；`stats` 新签名适配 `:core:test` 通过 |
| AA-12 / L7 | 已修 | 缓存名 `"$name-v${Synth.SFX_VERSION}.wav"` 并清理旧缓存；`setOnLoadCompleteListener` + 冷启动暂存补播；新增 `autoPause()/autoResume()`，MainActivity onPause/onResume 调用。`SoundManager.kt:33/44/75/89/94` | 读码 + 编译 |

### 生命周期 / 状态保存

| ID | 状态 | 关键改动 | 验证 |
|---|---|---|---|
| AA-5 / M19 | 已修 | `RoundSnapshotCodec`（Base64 紧凑编码 core `Snapshot`，含 6 个布尔数组与全部标量）；`MainActivity.onSaveInstanceState` 存 Bundle、`onPause` 存 prefs（`MainActivity.kt:182/191/200/209`）；`GameView.currentSnapshot()/restoreSnapshot()`（`:688/692`），恢复时重建棋盘缓存、重置视图状态、`stats.selectBucket` | `RoundSnapshotCodecTest` 4 项（roundtrip/预设难度/损坏存档返回 null）；编译 |
| configChanges | 已修 | manifest 追加 `keyboard|density|fontScale|locale`；`onConfigurationChanged` 补沉浸式与重绘。`AndroidManifest.xml:14` | 构建 + lint |

### 音效 / i18n / 无障碍

| ID | 状态 | 关键改动 | 验证 |
|---|---|---|---|
| AA-13 / M20 | 已修 | 全部 UI 文案迁 `res/values/strings.xml`（99 条）+ 新增 `values-en/strings.xml`（键完全对齐）；`GameView` 用 `str()`（`:1322`）/`levelLabel()`（`:549`）/`actionLabel()`（`:559`）做资源映射，core LEVELS/KeyBindings label 仅存 id 语义；`String.format` 全改 `Locale.ROOT`（`GameText`）；`app_name` 随 values-en 变 Minesweeper；整型/时间格式化抽 `GameText` | zh/en 99 键 diff 一致、占位符脚本校验通过；`GameTextTest` 4 项；英文 About 文案已缩短避免 560px 对话框溢出 |
| AA-9 / M14 | 已修 | `AccessibilityDelegateCompat` 虚拟动作（翻开/标雷/新游戏/菜单，`GameView.kt:2318`）、动态 `contentDescription`、`announceForAccessibility` 状态播报（`:2391`）、`performClick()`（`:2417`）、`importantForAccessibility` | 读码 + lint（ClickableViewAccessibility 已消）；TalkBack 需真机 |

### 构建 / 打包

| ID | 状态 | 关键改动 | 验证 |
|---|---|---|---|
| AA-16 / L9（图标+版本） | 已修 | 由原 512px `icon.png` 生成 `mipmap-mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi` 的 `ic_launcher.png` + 圆形 `ic_launcher_round.png`，manifest 改 `@mipmap/ic_launcher` / `@mipmap/ic_launcher_round`，删除单密度 `drawable/icon.png`。版本默认值从仓库根 `VERSION` 读取：`1.3.0 → versionCode 10300`，`-PversionName/-PversionCode` 仍优先（`app/build.gradle.kts:11-18/33`） | `aapt dump badging`：versionCode 10300 / versionName 1.3.0 / 7 个 application-icon 密度条目；`apksigner verify` 通过 |
| L9（工具链） | 部分（后续项） | AGP 8.5.2 / Kotlin 1.9.24 / compileSdk 34 未动——FIX-PLAN §1 明确本轮不做大版本升级，已在 build.gradle.kts 注释保留说明 | — |

### 附：为通过 lint/质量门做的清理

- 覆盖层点击后补 `performClick()`，消除 `ClickableViewAccessibility`；类上加 `@SuppressLint("ViewConstructor")`（纯代码创建）。
- 删除 3 条未使用 a11y 资源；中文/英文文案中 `...` 统一改 `…`（TypographyEllipsis 12 → 0）。
- 剩余 11 条 lint Warning 均为既有 advisory：`OldTargetApi`（targetSdk 34）、`IconLauncherShape`（方形启动图标形状）、`PluralsCandidate`、`DiscouragedApi`（横屏锁定）。

## 测试与构建记录

```
cd android
GRADLE_USER_HOME=<repo>/.gradle-user-home ./gradlew :core:test \
  :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug \
  --offline --console=plain
```

| 任务 | 结果 |
|---|---|
| `:core:test` | BUILD SUCCESSFUL；**110/110** 测试通过（android-core 本轮修复后重跑） |
| `:app:testDebugUnitTest` | BUILD SUCCESSFUL；**15/15**（GameText 4 / GameClock 5 / OverlayHitRegistry 3 / RoundSnapshotCodec 4） |
| `:app:assembleDebug` | BUILD SUCCESSFUL；`app-debug.apk` 2,395,998B |
| `:app:assembleRelease` | BUILD SUCCESSFUL；R8 + `lintVitalRelease` 通过；`app-release.apk` 289,999B，V2 签名（本地 keystore `CN=Saolei`） |
| `:app:lintDebug` | BUILD SUCCESSFUL；0 error / 11 warning |
| `aapt dump badging` | minSdk 26 / targetSdk 34 / compileSdk 34；versionCode 10300 / versionName 1.3.0；图标 5 密度 + round |
| `git diff --stat -- android/app` | 7 个已跟踪文件修改（+~960/−395），新增 4 个纯逻辑文件、4 个测试文件、values-en、5 个 mipmap 目录，删除 `drawable/icon.png` |

## 风险与未验证项（需 KPA 实机冒烟）

1. **H3**：KPA 手柄若上报为 virtual 且 source 非 GAMEPAD/JOYSTICK，"从未见过物理手柄→不过滤"兜底可保证可用；但若手柄同时上报 virtual+GAMEPAD source 也直接放行。建议真机各按键（方向/ABXY/Start/Select）走查一次。
2. **无障碍**：虚拟动作/播报为读码 + 构建验证，TalkBack 实际焦点顺序/播报内容需真机确认。
3. **i18n 英文排版**：英文文案较长处（底栏提示/About/键位帮助）已按 960×640 与 560px 对话框估算，真机确认无溢出。
4. **视觉改动**：斜面内描边（AA-14）、圆形图标、HUD 安全区（无刘海时为 0）需截图确认。
5. **状态恢复**：进程回收恢复走 Bundle/prefs 双通道，建议真机"游戏中杀进程→冷启动→继续上局"验证一次（无模拟器）。
6. **L9 工具链升级**未做（FIX-PLAN 排除）。

## 主要文件

- 修改：`android/app/src/main/java/com/wpiaopiao/saolei/{GameView,MainActivity,SoundManager}.kt`、`AndroidManifest.xml`、`app/build.gradle.kts`、`res/values/strings.xml`
- 新增：`GameText.kt`、`GameClock.kt`、`OverlayHitRegistry.kt`、`RoundSnapshotCodec.kt`、`res/values-en/strings.xml`、`res/mipmap-*/ic_launcher{,_round}.png`、`src/test/java/com/wpiaopiao/saolei/*Test.kt`（4 个）
- 删除：`res/drawable/icon.png`（被 mipmap 多密度图标取代）
- 无跨 owner 越权改动（`android/core/**` 由 android-core 负责）。
