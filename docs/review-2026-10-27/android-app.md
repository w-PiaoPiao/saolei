# 安卓 app 层审查

范围：`android/app` 全部源码/资源、`AndroidManifest.xml`、`app/build.gradle.kts`、`proguard-rules.pro`（`core/` 仅在与 UI 交互处引用，不重复审）。
基线：HEAD `9c92fda`，工作树干净；`git diff af30620 -- android/` 为空（2026-10-12 之后 android 源码无改动）。
方法：逐行通读 `GameView.kt`（2354 行）+ 其余 4 个源文件与构建配置；用 `./gradlew :core:test :app:assembleDebug :app:assembleRelease --offline`（仓库本地 Gradle 缓存）复跑；`aapt dump badging`/`apksigner verify` 检查产物。**无真机/模拟器，输入与渲染的运行时行为均为读码推理**，已在对应条目注明。

## 结论摘要

- app 层整体质量好于旧审查基线：af30620 的静态位图 + `dirty` 增量渲染、无猜后台生成、六个覆盖层触摸、拖动重锚、多点触控等均已落地且读码验证正确，**未发现可稳定触发的主线程崩溃或死锁**。
- 最高优先级是 1 条未验证的运行风险：`MainActivity.kt:88-91` 仍按 `device.isVirtual` 一刀切丢弃手柄事件（旧审查 P1-6，未修）——若 KPA 手柄上报为 virtual，游戏主输入完全失效。
- 4 条 P2 中等问题：无猜模式「先标旗再翻开」会绕过异步生成、在主线程同步生成（≤800ms 冻结）；底栏难度按钮漏设字号、继承 HUD 的 28f；自定义雷区 ◀▶ 触摸热区被整行抢先导致无法调数值；对局状态零保存，进程回收/部分配置变更直接丢局。
- 另有 1 条构建/CI 疑似问题（release job 的 `if` 引用同 step env，疑似恒假 → 签名版可能从未自动发布，需 GitHub 实测）与 10 条 P3（无障碍、计时封顶、音效缓存、窗口/insets、本地化、渲染边角、可维护性等）。
- 构建实测：`:core:test` 69 项全绿；`:app:assembleDebug` 成功；`:app:assembleRelease` 成功（R8 + `lintVitalRelease` 通过，APK 189,447B，V2 签名），非 ASCII 路径构建正常。

## 发现清单（P0→P3）

> 严重度按"修复收益 × 触达概率"评级；每条均给出当前 HEAD 的行号。**P0：无。**

### [AA-1] KPA 手柄事件先按 `device.isVirtual` 一刀切过滤（P1）

- 类型：缺陷（输入）
- 证据：`android/app/src/main/java/com/wpiaopiao/saolei/MainActivity.kt:88-91`：
  ```kotlin
  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
      if (event.device != null && event.device.isVirtual) {
          return super.dispatchKeyEvent(event)
      }
  ```
  该过滤自 2026-10-12 审查（旧 P1-6）后未改。**verified（读码）/ 运行时 unverified（无真机）**。
- 影响：游戏的唯一主输入是手柄。若 KPA 内置手柄/按键被系统标记为 virtual device（部分掌机、USB/蓝牙 HID 转接、以及某些模拟层会这样上报），所有手柄键在进入 `GameView.handleKeyDown` 前被丢弃 → 方向/ABXY/Start/Select 全部失效，只剩触摸与系统返回手势可玩（菜单/切难度/键位设置基本不可达）。
- 建议：删除该过滤（本项目无文本输入，软键盘不会产生游戏键码；音量键等仍走 `super`），或改按 `event.source and InputDevice.SOURCE_GAMEPAD/JOYSTICK`、键码白名单判定；再加兜底"从未收到物理手柄键则不过滤"。修复后必须在 KPA 实机验证。
- 工作量：S

### [AA-2] 无猜模式「先标旗再翻开」绕过异步生成，主线程同步冻结最长 800ms（P2）

- 类型：性能/缺陷（回归洞）
- 证据：
  - `GameView.kt:586-591` —— 仅当 `engine.state == "ready"` 才走 `startAsyncFirstReveal`：
    ```kotlin
    if (engine.noGuess && !engine.minesPlaced && engine.state == "ready") { startAsyncFirstReveal(r, c); return }
    ```
  - `android/core/src/main/kotlin/com/wpiaopiao/saolei/core/GameEngine.kt:414` —— 首手标旗即可把 state 从 `ready` 改为 `playing`；
  - `GameEngine.kt:338-348`（`:343-345` 首次翻开即 `placeMines`）+ `:246-248` —— 此时再翻开走同步 `reveal()` → `placeMines()` → `planMines(..., noGuess=true)`，预算 `GameEngine.kt:29` `NO_GUESS_BUDGET_MS = 800`。
  - **verified（读码，链路完整）。**
- 影响：无猜开启时，玩家第一手用 A 键/长按触摸标旗（常见操作），随后第一次翻开就会在 UI 线程跑最多 800ms 的无猜求解重试——正是 af30620 要消除的"点一下卡半秒"，只是换了个入口；50×50/高密度自定义局最明显。附带问题：该同步分支下 `generating` 提示不会出现，玩家只会看到界面无响应。
- 建议：条件改为 `if (engine.noGuess && !engine.minesPlaced)`（`applyPlanAndReveal` 已允许 `state == "playing"`且有完整的竞态守卫），让"首手标旗后再翻"也走后台线程；补一条"先标旗再首点"的回归测试（core 层即可覆盖 `applyPlanAndReveal` 在 playing 态可用，app 层逻辑可抽出判定函数）。
- 工作量：S

### [AA-3] 底栏难度按钮漏设字号，继承 HUD LED 的 28f（P2）

- 类型：缺陷（视觉/UX）
- 证据：
  - `GameView.kt:1178-1181`：`drawHud()` 里 `textPaint.reset()` 后设 `textSize = 28f`（雷数/计时 LED 用）；
  - `GameView.kt:1596-1617`：`drawBottomBar()` 画 4 个难度按钮时只设 `color`/`textAlign`，**未设 `textSize`/`typeface`**；
  - `GameView.kt:229-235`：按钮宽度按 **14f** 测量缓存；`:1621-1623`：直到画完按钮后才 `textPaint.reset()` + `textSize = 12f` 画键位提示；
  - 唯一会改回 12f 的路径是 HUD 提示条（`:1217`），即只在"无解保护不可用/正在生成"时生效。
  - **verified（读码；静态推算可确认，真机截图 unverified）。**
- 影响：常态下底栏「初级/中级/高级/自定义」以 28px 等宽粗体画在 30px 高的按钮里（"自定义"宽 50.4px > 按钮 47.2px），文字压边/溢出、与全局 12–14px 风格明显不一致；一但 HUD 出现无解提示，字号又跳成 12px，产生可见跳变。桌面版无此问题。
- 建议：按钮循环前统一设置 `textPaint.reset(); isAntiAlias=true; typeface=MONO_BOLD; textSize=14f`（建议提取 `applyUiTextStyle(size)` helper，消灭"依赖上一次绘制残留状态"的写法），并加一条 UI 截图/像素断言回归（Robolectric 或设备截图）。
- 工作量：S

### [AA-4] 自定义雷区 ◀▶ 的触摸热区被整行命中抢先，触摸无法调数值（P2）

- 类型：缺陷（触摸功能）
- 证据：
  - `GameView.kt:1841-1848`：`row` 先登记，`minus`/`plus` 后登记，而两个箭头矩形**完全位于 row 内**；
  - `GameView.kt:753-758`：`overlayHitAt()` 按登记顺序返回**第一个**包含点的矩形 → 命中箭头时返回的永远是 row 的索引，只执行 `customField = i`；
  - `GameView.kt:1866`：绘制侧「◀ ▶」文本右对齐到 `row.right - 12`，与 minus `[…-70, -40]`/plus `[…-40, -6]` 热区也不对齐。
  - **verified（读码，几何可确定）。**
- 影响：纯触摸玩家在自定义雷区里点 ◀▶ 只会选中行，无法增减行/列/雷数——af30620 新增的"触摸可玩覆盖层"在自定义页实际失效（手柄方向键不受影响）。这是本轮最容易复现的功能性缺陷。
- 建议：子元素优先登记（先 minus/plus 再 row），或让 `overlayHitAt()` 逆序/小面积优先；把两个 30px 箭头按钮按字形位置画成独立方块；在 `addOverlayHit` 注释里写明"后登记者优先/不得被父矩形覆盖"的约定。
- 工作量：S

### [AA-5] 对局状态零保存：进程回收或部分配置变更即丢局（P2）

- 类型：架构/生命周期
- 证据：
  - `MainActivity.kt` 全文无 `onSaveInstanceState`/`onRestoreInstanceState`（grep 零命中），`GameView` 也没有状态快照/恢复入口；
  - `AndroidManifest.xml:12`：`configChanges="orientation|screenSize|keyboardHidden|screenLayout|smallestScreenSize|uiMode"`，仍缺 `keyboard|density|fontScale|locale`（旧审查 6.3 未修）；
  - `GameView.kt:394-399` `onDestroy()` 只 `removeCallbacks`，不保存任何局面。
  - **verified（读码）。**
- 影响：掌机切后台被系统回收（50×50 位图缓存约 4MB，KPA 内存有限）→ 回来变新局；改系统字号/语言/插拔外接键盘同样重建 Activity 丢局。丢局前该局已计入 `totalGames` 但无胜负，胜率口径也被污染。
- 建议：做轻量快照——`onPause` 把 `currentLevel/currentCustom`、雷位（`BooleanArray`→Base64/ByteArray）、`revealed/flagged/questioned`、`timer`、`noGuess` 存 prefs/Bundle；`onCreate` 检测到未终局快照则恢复（不重跑布雷）。同时把 `keyboard|density|fontScale|locale` 加入 `configChanges`（`onConfigurationChanged` 已处理 uiMode 主题，其余布局是裸像素，无需重排）。
- 工作量：M

### [AA-6] CI release job 的 `if` 引用同 step 的 `env`，疑似恒为假 → 签名版可能从未自动发布（P2，构建/CI）

- 类型：构建/发布
- 证据：`.github/workflows/android.yml:61-66`（以及发布 step `:81-86`）：
  ```yaml
  - name: 构建签名 Release APK
    if: ${{ env.ANDROID_KEYSTORE_BASE64 != '' }}
    env:
      ANDROID_KEYSTORE_BASE64: ${{ secrets.ANDROID_KEYSTORE_BASE64 }}
  ```
  git 历史 `ba55e99`（"secrets 判断改经 step env 中转（secrets 不能直接用于 if）"）表明这是为绕开 job 级 `if` 不支持 secrets 而做的中转；但 GitHub Actions 的 step `if` 在应用该 step 的 `env:` **之前**求值，`env.ANDROID_KEYSTORE_BASE64` 在该处通常为空 → 两个 step 恒跳过。**unverified**：本机无 GitHub 网络（web 工具不可用），未能用 tag 实测；且该文件不在 app 目录内，供 Lead 决定是否转给构建/CI 负责人。
- 影响（若成立）：即使仓库配好 4 个 signing secrets，tag 也只产出 debug APK artifact，`README.md:125` 承诺的"自动构建签名 release 并发布到 GitHub Releases"不成立；且不会有任何报错。
- 建议：step `if` 直接引用 `secrets.ANDROID_KEYSTORE_BASE64`（step 级 if 支持 secrets；job 级不支持），或把 secrets 提到 job 级 `env` 再在 step `if` 中用 `env.*`；改完用一个临时 tag 跑一遍确认 release 产出。
- 工作量：S

### [AA-7] 长按已翻开的数字格：误振动且吞掉松手和弦（P3）

- 类型：缺陷（触摸 UX）
- 证据：`GameView.kt:2122-2130` 长按回调无条件 `performHapticFeedback()` 并置 `touchFlagged = true`；`GameView.kt:650-657` `flagAt()` 对 `cell.revealed` 静默 return；`GameView.kt:2317` 松手时 `!touchFlagged` 才会执行和弦。
- 影响：对已翻开数字格长按会振动但无任何变化，且松手不再执行和弦（与短按行为不一致），此前按下的和弦预览（`:2217-2220`）也被清掉；玩家会认为操作丢失。
- 建议：长按回调先判断 `engine.board[r][c].revealed` → 直接 return（不振动、不置 `touchFlagged`）；或把"长按已翻开数字格"定义为和弦。
- 工作量：S

### [AA-8] 计时在 999 秒封顶，长局成绩与统计失真（P3）

- 类型：缺陷（统计口径；与桌面同源）
- 证据：`GameView.kt:281-285` `syncTimerValue()` 用 `min(999, …)` 写入 `engine.timer`；`GameView.kt:541-549` 终局时直接把这个值传给 `stats.onGameWin/onGameLose`（最快时间、总游戏时间、3BV/s 历史）；LED 显示 `:1199-1202`。
- 影响：高级局或 50×50 局超过 16:39 后显示冻结 999，且最快时间/总时长/新纪录横幅都按 999 记录，长局数据永久失真。
- 建议：显示可维持 3 位封顶，但终局统计用真实耗时（独立 `elapsedSec`，或终局时从 `timerBaseMs + 锚点差` 精确取整），LED 可显示 `999+`；若放开位数则同步调 LED 宽度（当前 92px 只能容 3 位）。
- 工作量：S

### [AA-9] 无障碍（TalkBack）完全不可用（P3）

- 类型：无障碍
- 证据：`android/app/src` 内 `contentDescription`/`AccessibilityDelegate`/`announceForAccessibility` 零命中；`GameView.kt:2132` 手写 `onTouchEvent` 处理点击但从不调用 `performClick()`（lint `ClickableViewAccessibility`），也没有虚拟节点；HUD（雷数/计时）与棋盘状态无任何可读信息。
- 影响：读屏用户拿到的是一块无信息的画布，无法操作菜单/棋盘；键盘焦点用户也无法 Tab 到控件。掌机场景影响有限，但属平台基本盘。
- 建议：给 `GameView` 设 `AccessibilityDelegateCompat`，至少暴露"新游戏（表情）/四个难度/菜单"虚拟节点；状态变化时 `announceForAccessibility("剩余雷数 10，用时 3 秒")`；触摸分发里补 `performClick()`。
- 工作量：M

### [AA-10] 窗口/生命周期细节：常亮、insets、焦点、detach（P3）

- 类型：UX/平台健壮性
- 证据：
  - `MainActivity.kt:30` 无条件 `FLAG_KEEP_SCREEN_ON`（菜单/暂停/统计页也常亮，旧 U9 未修）；
  - `MainActivity.kt:79-85` `immersive()` 只在 `onCreate`/`onResume` 调用，未实现 `onWindowFocusChanged` 补设，无 `WindowInsetsCompat`/刘海安全区处理（旧 6.3 未修）；
  - `GameView` 未重写 `onDetachedFromWindow`；`MainActivity.kt:122-125` 的 `onDestroy` 是唯一清理点（当前无其他 detach 路径，属健壮性缺口）。
- 影响：暂停/看菜单时也常亮耗电；有挖孔/刘海的设备 HUD 顶部可能被遮挡；从通知栏/系统弹层回焦后系统栏可能短暂重现；后台生成线程的 `post` 在极端场景可能滞留到下次 attach。
- 建议：暂停/覆盖层打开时清 `KEEP_SCREEN_ON`、恢复时加；`onWindowFocusChanged(true)` 重设沉浸式；用 `WindowInsetsCompat.Type.displayCutout()|systemBars()` 给 HUD 加上内边距；`onDetachedFromWindow` 统一 removeCallbacks + 作废 `generationSeq`。
- 工作量：S

### [AA-11] About 页写死默认键位，用户改键后显示错误（P3）

- 类型：文档/UX
- 证据：`GameView.kt:2093-2100` 硬编码「方向键移动 · B 翻开/和弦 · A 标雷 · Start 新游戏 · Select 菜单」；而键位可自定义（`bindings.getDisplayKey`），底栏提示 `:1626-1629` 已正确使用动态键名。
- 影响：玩家改键后 About 与实际不符（误导），且与底栏提示互相矛盾；违反单一数据源。
- 建议：About 用 `bindings.getDisplayKey("activate")` 等动态拼接（与底栏共用 helper）。
- 工作量：S

### [AA-12] 音效：冷启动加载期可能静默、切后台不暂停、WAV 缓存无版本号（P3）

- 类型：资源/UX
- 证据：`SoundManager.kt:20-40` 在 `onCreate` 主线程直接合成 6 个 WAV（`Synth` 采样合计约 7 万，成本几 ms，可接受）并 `p.load()` 异步加载，未注册 `OnLoadCompleteListener`；`:46-50` 加载完成前的首次 `play()` 会静默；无 `autoPause/autoResume`（旧 6.3 未修）；`:34` 缓存文件名固定 `$name.wav`，`Synth` 参数变更后已安装设备永远用旧音效（旧 6.2 未修）。
- 影响：冷启动前几百毫秒的无点击音（"点了没声"）；后台短音效可能与系统音叠加；音效迭代无法到达存量设备。
- 建议：缓存文件名带合成版本号（`click-v2.wav`）；`setOnLoadCompleteListener` 记录就绪集合（未就绪时丢弃/延迟一次）；`onPause/onResume` 调 `autoPause()/autoResume()`。
- 工作量：S

### [AA-13] 硬编码中文 + `String.format` 未指定 Locale（P3，本地化）

- 类型：可维护性/本地化
- 证据：`res/values/strings.xml:3` 仅有 `app_name`；全部 ~100 条文案硬编码在 `GameView.kt`（菜单 `:789-816`、统计 `:1955-1972`、曲线 `:1997-2088`、关于 `:2093-2101` 等）；`String.format` 无 Locale：`:570`（`%d:%02d`）、`:1954`、`:2042`、`:2074`（`%.2f`）。`aapt dump badging` 显示 `application-label-af` 等各语言均为「扫雷」。
- 影响：英文 README 面向国际用户但 app 完全无法本地化；部分 locale 的 `%.2f` 会输出逗号小数点，与中文硬编码文案混排。
- 建议：文案迁移 `strings.xml`（至少 About/菜单/统计），代码 `context.getString`；数字/时间格式化统一 `String.format(Locale.ROOT, …)`。
- 工作量：M

### [AA-14] 静态位图增量重绘的斜面描边跨格，可能留下 1–2px 色差（P3，视觉 unverified）

- 类型：缺陷（渲染正确性，低影响）
- 证据：`GameView.kt:1416-1425` 只重绘 `cacheDirty` 中的格子；`drawCell():1456-1529` 用 `drawBevel()`（`:1149-1158`）在格子**边界线上居中描边**，一半描边落在相邻格内。整盘重建时，右/下邻居随后绘制会用浅色上/左描边覆盖前一格的深色下/右描边；增量重绘时邻居不会被重画，脏格的下/右边界会保留自己的深色描边。`bevelWidth(cell)` = `cell*0.08` 限幅 1–3px（`:1147`）。
- 影响：本次操作变化的格子（插旗/翻开）下/右边缘可能比周边多 1–2px 深色线；50×50（描边 1px）几乎不可见，9×9（描边 3px）可能可辨。无真机，标记 unverified。
- 建议：脏格重绘时向外扩 `bevelWidth` 并把相邻格一起画（`clipRect` + 邻居集合），或把斜面改成全部落在本格内的内描边，从机制上消除跨格覆盖。
- 工作量：S

### [AA-15] 可维护性：2354 行 God Class + app 层零测试 + 残留死代码（P3）

- 类型：架构/测试/可维护性
- 证据：`GameView.kt` 2354 行，一个 View 同时承担 6 套主题、HUD/棋盘/底栏、7 个覆盖层绘制、手柄+触摸输入分派、计时器、统计联动、无猜后台线程与音效触发（构造注入 5 个依赖 `:34-40`）；`android/app/src` 下**没有** `test/`、`androidTest/`（CI 的测试步骤只有 `:core:test`）；残留：`drawBindings():1921` 三元两侧相同（旧 U13 未修）、`markRevealAnimFromDirty():572-574` 只是 `consumeDirty()` 别名、`consumeDirty` 在操作路径与 `syncState` 中重复调用（`:596/654/661` 与 `:553`）、`ACTION_OUTSIDE` 分支 `:2245-2253` 不生效（未设 `FLAG_WATCH_OUTSIDE_TOUCH`）。
- 影响：本轮 AA-3（依赖上一个绘制函数留下的画笔状态）、AA-4（命中数据由绘制顺序决定）都是"绘制/状态/输入耦合且无测试兜底"的直接产物；后续加存档/提示/缩放等功能成本递增。
- 建议：拆出 `BoardRenderer`（纯绘制，传入 Canvas + 只读快照）、`OverlayHost`（命中登记与派发）、`InputController`；把 `formatCounter`、命中顺序、计时状态机提成纯函数并给 `app` 加 JVM 单元测试（`testImplementation(kotlin("test"))`，或只测抽出的纯逻辑）；顺手清理死代码。
- 工作量：L

### [AA-16] 构建/打包细节：本机 release 版本号回退 1、单密度图标、工具链偏旧（P3）

- 类型：构建
- 证据：
  - `app/build.gradle.kts:22-23` 未注入时 `versionCode = 1 / versionName = "1.0.0"`；本次 `assembleRelease` 产物经 `aapt dump badging` 确认为 `versionCode='1' versionName='1.0.0'`。已安装 CI 版 v1.2.1（versionCode 10201）时，本机签名包 `adb install` 会 `INSTALL_FAILED_VERSION_DOWNGRADE`；
  - `res/drawable/icon.png` 单张 3988B，无 `mipmap-*`/自适应图标；`AndroidManifest.xml:6` 只有 `android:icon`，无 `roundIcon`；
  - `gradle/libs.versions.toml:2-4`：AGP 8.5.2 / Kotlin 1.9.24（2026 年已偏旧）；`isShrinkResources=false`（资源极少，非问题）。
  - **verified（构建产物实测）。**
- 影响：本机 release 包覆盖安装需先卸载（丢统计）；启动器图标在多数设备被拉伸/模糊；工具链长期不升级会积累迁移成本。
- 建议：本地默认 versionCode 抬高（如 `1_000_000 + 时间戳`）或文档注明"本机构建须 `-PversionCode=…`"；补多密度或矢量自适应图标；规划 AGP/Kotlin 升级（单独 PR，`assembleRelease` + 真机冒烟验证）。
- 工作量：S

## 已核实项（已修/无问题）

### 旧审查条目：已验证修复（不再重复报）

| 旧条目 | 当前证据 | 结论 |
|---|---|---|
| P1-2 每次操作整盘光栅化，`dirty` 被丢弃 | `GameView.kt:1395-1427`（静态位图 + `cacheDirty` 局部重绘）、`:423-440`（消费 `engine.dirty`） | 已验证修复 |
| P1-3 洪泛时上千格逐格动画同帧 | `GameView.kt:110`、`:433`（>256 格跳过缩放动画） | 已验证修复 |
| P1-1 无猜生成阻塞 UI 线程 | `GameView.kt:610-641` + `GameEngine.planMines`（纯函数、后台线程、`generationSeq` 作废过期结果） | 除 AA-2 残留分支外已验证修复 |
| P1-4 六个覆盖层零触摸 | `GameView.kt:738-758`（绘制时登记命中区）、`:2161-2187`（统一派发） | 已验证修复（自定义页箭头见 AA-4） |
| P1-5 50×50 不可读 | `MIN_CELL 20f`（`:102`）、数字 0.60/符号 0.62（`:107-108`）、斜面随格缩放（`:1147`）、视口滚动（`:1322-1341`） | 已验证改善；960×640 下 50×50 仍需平移浏览（20px 格/12px 数字），可接受 |
| P1-7 无猜降级无提示 | `GameView.kt:1204-1224`（「⚠ 无解保护不可用」/「正在生成无猜雷局…」互斥显示） | 已验证修复 |
| A1 `invalidate` 粒度粗 | 光标只失效新旧两格（`:350-374`）、计时只失效 HUD（`:294-299`） | 已验证修复 |
| A4 每帧 4 次 `measureText` | `:229-235`（lazy 缓存宽度） | 已验证修复 |
| U2 拖动起跳/无重锚 | `:2264-2286`（越过 24px 阈值时以当前触点重锚） | 已验证修复 |
| U6 键位录制闪烁无时钟驱动 | `:1136-1141`（`postInvalidateDelayed(200)`） | 已验证修复 |
| U7 表情热区与绘制不一致 | `FACE_RADIUS`（`:105`）绘制/命中复用（`:1190`、`:2349-2353`） | 已验证修复 |
| U8 无效操作也播音效 | `:592-594`（已翻开/已插旗不播）、`:651-652`（已翻开不插旗） | 已验证修复 |
| U10 多点触控不完整 | `:2225-2253`（POINTER_DOWN/POINTER_UP/OUTSIDE 取消手势、坐标恒用 pointer 0 的手势被作废） | 已验证修复 |
| U11 抖动格被邻居遮掉外溢 | `:1434-1453`（动态集合含 8 邻居并按 r*cols+c 升序绘制） | 已验证修复（AA-14 是另一处静态层边角） |
| C1/C4 自定义难度防御分支 | `:486-496`（`currentCustom == null` 直接忽略） | 已验证修复 |
| 6.2 主题跟随系统 | `MainActivity.kt:72-77` + `GameView.kt:444-453`（未显式选主题时不落盘、uiMode 变化实时跟随） | 已验证修复 |

### 本轮确认无问题的模块

- `PrefsStorage.kt:6-13`：薄适配、`apply()` 异步写，无泄漏/阻塞问题。
- `AndroidManifest.xml`：launcher intent-filter、`exported`、横屏锁定、`configChanges`（除 AA-5 缺项）正确；无多余权限（`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` 为 AGP 自动加，正常）。
- `res/values/themes.xml:3-6`：平台全屏无标题栏主题，与自绘 View 匹配。
- `proguard-rules.pro` + R8：纯 Kotlin 无反射，无需 keep 规则；`assembleRelease` 实测成功（R8 + `lintVitalRelease` 通过，189,447B）。
- 无猜后台线程安全：`planMines` 不触碰实例状态、线程只读取启动时捕获的 `rows/cols/mines/首点`、结果 `post` 回 UI 并再次校验（`GameView.kt:610-648`），未发现共享可变状态竞争；`generationSeq` 作废过期结果正确。
- 静态位图坐标：原点 floor + 四周 `CACHE_PAD=10`、整数贴图，抖动最大位移 7px 不越界（`:1395-1407`）——设计正确。
- 主题切换/尺寸变化走整层重建（`:414-417`、`:448-453`、`:401-405`）——正确。
- 触摸坐标与滚动偏移（`:2337-2347`）、`invalidateCell` 越界防护（`:367-374`）——正确。
- `onBackPressed` 手势返回映射为游戏 Back（`MainActivity.kt:103-108`）——在未启用预测性返回前行为正确。

### 旧审查仍未修、但本轮未单列编号的低优先项

- A2 静态位图仍按整盘尺寸分配（50×50 ≈4MB，与视口无关）——日常已增量重绘，可接受。
- A3 位图不显式 `recycle`——有意为之（避免硬件纹理使用中崩溃），可接受。
- A5–A7 覆盖层每帧重建 `MenuItem`/`RectF`、`dynamicCells` 每帧分配、横幅 `saveLayerAlpha`——只在按键/动画窗口发生，影响低，可作后续优化。
- 5.1 覆盖层打开即暂停计时（可"暂停思考"），影响最快时间/3BV/s 可信度——桌面版同样语义，属产品口径选择，未改。
- U4 无退出入口（Back 恒被消费：游戏内→菜单，菜单内→关菜单，只能 Home 退出）——掌机场景可接受。
- U12 `Stats/About` 任意绑定键即关闭（方向键也会关，`GameView.kt:982-988`）——低影响。
- U5 全部尺寸裸像素（`HUD_H=64f`、字号 11–30f），未按 density 缩放——目标机固定 960×640，暂可接受。

## 构建与实测记录

环境：macOS，JDK 17，仓库 `android/`（路径含非 ASCII「扫雷」），`GRADLE_USER_HOME=<repo>/.gradle-user-home`（默认 `~/.gradle` 被其他项目并发构建占用；首次用默认缓存执行时因 wrapper 分发 zip 排他锁超时失败，改用仓库本地缓存 + `--offline` 后全部通过）。

| 命令 | 结果 |
|---|---|
| `./gradlew :core:test --offline --console=plain` | **BUILD SUCCESSFUL in 819ms**；5 个测试类 XML 汇总 **69 项**，全绿（与本轮 Lead 复跑一致） |
| `./gradlew :app:assembleDebug --offline --console=plain` | **BUILD SUCCESSFUL in 1s**（全部 UP-TO-DATE，说明当前源码与 `af30620` 构建输入一致）；`app-debug.apk` 2,163,241B |
| `./gradlew :app:assembleRelease --offline --console=plain` | **BUILD SUCCESSFUL in 18s**；执行 `minifyReleaseWithR8`、`lintVitalRelease` 均通过；`app-release.apk` **189,447B** |
| `apksigner verify --print-certs app-release.apk` | 通过（V2 签名，证书 `CN=Saolei, OU=Dev, O=wpiaopiao, C=CN`，使用本机 gitignored `keystore/`） |
| `aapt dump badging app-release.apk` | `package com.wpiaopiao.saolei`，minSdk **26** / targetSdk **34** / compileSdk **34**，`versionCode='1' versionName='1.0.0'`，label「扫雷」 |
| `git diff af30620 -- android/` | 空（android 源码/构建文件与旧审查修复提交逐字节一致） |

未执行：`:app:lintDebug`（按 Lead 建议跳过；`lintVitalRelease` 已覆盖 fatal 级 lint）。**无设备/模拟器**：输入、渲染、生命周期结论均为读码推理，已在相应条目标注 verified/unverified；AA-1、AA-3、AA-14 建议在 KPA 实机确认。

## 功能机会（从掌机/触摸/安卓平台角度）

1. **提示/安全格（Hint）**：`core` 已有完整 `Solver`，可直接复用实现"提示一个可确定安全格/雷"（按次限量、消耗时间或影响 3BV/s 统计），把无猜求解器从"只用于生成"变成玩法功能；对触摸/掌机玩家价值最高，且与既有效率统计天然配套。
2. **对局存档/快速恢复**：与 AA-5 同源，做成"继续上局/自动存档"，匹配掌机随时休眠的使用方式；顺带解决进程回收丢局。
3. **触摸可玩性增强**：双击和弦（桌面已有）、长按后拖拽连续标旗、双指缩放/一键适配棋盘（50×50 在 960×640 上需平移浏览，缩放比滚动更直观）、底栏补「菜单/返回」触摸入口（现在菜单只能靠手柄 Select 或系统返回手势）。
4. **掌机键位利用率**：`core/KeyCodes` 已定义但键位表未用的 L1/R1/L2/R2，可映射为"快速标旗模式/和弦/提示"；补 `setVolumeControlStream(AudioAttributes.USAGE_GAME)`、音量与振动设置项（现在音效音量固定 1f、振动只在长按）。
5. **本地排行榜与目标**：已有 `StatsManager` 历史（3BV/s 曲线、连胜、最快时间），可加"每日挑战/同难度前三名/成就"，并把统计页做成触摸可滚动列表。
6. **平台现代化**：`AccessibilityDelegate` + `announceForAccessibility` 补读屏；API 33+ 的 `OnBackInvokedDispatcher`（预测性返回）替代 deprecated `onBackPressed`；自适应图标 + `values-en` 英文（README 已双语，app 仍是纯中文）。
