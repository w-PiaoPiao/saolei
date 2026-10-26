# 扫雷 🎯

**中文** | [English](README.md)

经典 Windows XP 风格扫雷游戏。

基于 Electron 的跨平台桌面应用，支持 macOS · Windows · Linux。

---

## 功能

| 功能 | 说明 |
|---|---|
| 三档难度 | 初级 9×9/10 雷、中级 16×16/40 雷、高级 16×30/99 雷 |
| 自定义模式 | 自由设定行/列/雷数，最大 50×50 |
| 超大棋盘 | 自动缩放格子尺寸（带可读性下限，放不下时滚动浏览）；窗口只放大不缩小 |
| 无猜模式 | 生成保证可纯逻辑推理通关的雷局；预算内未找到可解局时降级，并在界面明示 |
| 无猜生成不阻塞 | 生成在后台线程执行，首点不冻结界面并显示「生成中」 |
| 原生菜单栏 | F2 新游戏 |
| 单实例运行 | 重复启动会聚焦已有窗口，避免双开互相覆盖统计 |
| 键盘全操作 | 所有操作可用键盘完成，快捷键可自定义 |
| 和弦操作 | 双击、中键或左右键同时按下 |
| 首次点击安全 | 3×3 无雷 + 空白格大面积展开 |
| 音效 | Web Audio API 合成 6 种音效（零外部文件） |
| 游戏统计 | 总局、胜率、连胜、最快时间 |
| 3BV 效率统计 | 各难度最佳 3BV/s + 最近 20 局效率曲线 |
| 主题 | 6 套主题切换（偏好持久化），未手动选过时跟随系统深浅色 |
| 动画与反馈 | 和弦失败 shake 动画、左右键邻居格凹陷效果、破纪录横幅 |
| 对局保护 | 重开需确认、失败错旗打叉 |
| 单元测试 | 内置 node:test 测试，运行 `npm test` |

---

## 快速开始

```bash
npm install
npm start
```

## 测试

```bash
npm test
```

## 打包

```bash
# macOS
npm run dist:mac

# Windows
npm run dist:win

# Linux
npm run dist:linux
```

## 下载

发布页：[github.com/w-PiaoPiao/saolei/releases](https://github.com/w-PiaoPiao/saolei/releases)

| 平台 | 格式 | 说明 |
|---|---|---|
| macOS（Apple Silicon） | .dmg | 安装包 |
| Windows（x64） | .exe | 便携版，双击即运行 |
| Linux（arm64） | .AppImage | `chmod +x` 后运行 |

## 技术栈

Electron + 原生 JavaScript + CSS3 + node:test（无框架依赖）

## 安卓掌机版（攻氪 KPA）

面向 960×640 横屏安卓掌机（攻氪 KPA：十字键 + ABXY）的原生移植版，代码在 `android/` 目录：

- **原生 Kotlin**：自定义 View（Canvas）绘制，沉浸全屏，无浏览器层
- **引擎移植**：`android/core` 纯 JVM 模块逐方法移植桌面版 `game.js` / `movement.js` / `keybindings.js` / `stats.js`，JUnit 单元测试覆盖
- **手柄操作**：方向键移动（斜向 + 长按重复）、B=翻开/和弦、A=标雷（旗→问号→取消）、Start=新游戏、Select=菜单
- **键位自定义**：菜单 → 键位设置，任意键可重绑（含按键测试屏），持久化保存
- **无猜模式**：菜单开关；开启后布雷会反复重生成，直到求解器判定可纯逻辑通关（单点 + 子集 + 全局规则，保守判定）。生成在**后台线程**执行（界面不冻结，HUD 显示「正在生成无猜雷局…」）；大棋盘（如 50×50）或高雷密度下若在尝试次数与时间预算内未找到可解局，会在预估尝试次数不足时提前降级，沿用最后一次雷局并在 HUD 显示「⚠ 无解保护不可用」，不会静默降级
- **3BV 效率统计与曲线**：统计页显示各难度最佳 3BV/s；菜单「效率曲线」查看最近 20 局折线（含平均线，左右键切换难度）
- **防误触与终局反馈**：对局进行中重开（Start / 表情 / 菜单换难度）先弹确认框（默认停在「取消」）；失败时插错的旗打红叉，胜利破纪录弹「新纪录」横幅
- **跟随系统深浅色**：未手动选过主题时跟随系统深/浅色（含运行中切换），在菜单里选过主题后固定
- **性能**：棋盘静态层位图缓存（缓冲区复用）并接入引擎脏标记——一次操作只重绘变化格，非动画帧只贴图；光标移动与计时秒跳改为局部失效；大洪泛（首点翻开上千格）超过阈值时跳过逐格动画。50×50 大棋盘不再每帧或每次操作重绘全部格子
- **触摸增强**：**全部对话框支持触摸**（菜单项点按执行、自定义雷区的 ◀▶ 调整与确定/取消按钮、统计/关于/曲线点按关闭、键位行点按）；底部难度按钮可直接点按（走与手柄一致的重开确认保护）；按下已翻开数字格预览和弦范围；超大棋盘（如 50×50）启用视口滚动，触摸拖动（越阈值后重锚，不再跳变）或手柄光标移动自动跟随浏览
- **超大棋盘可读性**：格子边长下限 20px、数字占格子 60%、斜面宽度随格子缩放，50×50 不再压到 8px 字号
- **其余对齐桌面版**：三档难度 + 自定义雷区、6 套主题、6 种运行时合成音效、统计、暂停恢复、首次点击安全、和弦失败 shake

### 构建与安装

```bash
cd android
./gradlew :app:assembleDebug          # 产出 app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> 故障排查：若本机项目路径含非 ASCII 字符（如中文目录）导致 Kotlin 编译报错或路径被转义为 `uXXXX`，把 `android/` 复制到纯 ASCII 路径下构建即可（GitHub CI 不受影响）。

KPA 需先在系统设置中开启「开发者选项 → USB 调试」。调试包直接侧载安装；正式发布用 `assembleRelease` 并配置签名（Release 已开启 R8 压缩混淆，APK 从约 2MB 降至约 170KB）。

### 操作说明

| 按键 | 功能 |
|---|---|
| 方向键 | 移动光标（可斜向，长按连续移动） |
| B | 翻开格子；在已翻开数字格上按 = 和弦 |
| A | 标雷（插旗 → 问号 → 取消） |
| Start | 新游戏（对局进行中需二次确认） |
| Select | 菜单（难度/主题/键位/统计等） |
| Back | 关闭对话框 / 打开菜单 |

触摸（可选）：点按=翻开/和弦，长按=标雷，点表情=新游戏；超大棋盘可拖动浏览。**全部对话框都支持触摸**——菜单项点按执行，自定义雷区点行选中、点 ◀▶ 调整数值、点「确定/取消」提交，统计/关于点按关闭，效率曲线左右箭头切换难度，键位设置点行进入录制；点对话框外空白等价 Back 关闭。

## CI 与自动化发布

- **桌面版**：推 `desktop-v*` 标签（如 `desktop-v1.3.0`）触发 [GitHub Actions](.github/workflows/build.yml)：先跑单元测试，再构建 macOS / Windows / Linux 三平台安装包并自动发布到 GitHub Releases。
- **安卓版**：推 `v*` 标签（如 `v1.2.1`）触发 [android.yml](.github/workflows/android.yml)：跑单测、构建 Debug APK；若仓库配置了签名 secrets（`ANDROID_KEYSTORE_BASE64` / `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD`），还会构建**签名正式版**并自动发布到 GitHub Releases。发布版的版本号由标签注入（`v1.2.3` → `versionName=1.2.3`、`versionCode=10203`），游戏内「关于」页显示运行时版本号，不再硬编码。

### 安卓正式版签名

```bash
cd android
keytool -genkeypair -v -keystore keystore/release.keystore -alias saolei \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass 你的密码 -keypass 你的密码 -dname "CN=Saolei, OU=Dev, O=wpiaopiao, C=CN"
# 然后创建 keystore/keystore.properties：
#   storeFile=keystore/release.keystore
#   storePassword=你的密码
#   keyAlias=saolei
#   keyPassword=你的密码
./gradlew :app:assembleRelease   # 产出签名 app-release.apk
```

`keystore/` 目录已被 gitignore，密钥与密码不会入库；**请自行备份 keystore 与密码**（丢失后将无法用同一密钥续发版本）。CI 发布时把 keystore 的 Base64 与密码配置为仓库 secrets 即可全自动发布。
