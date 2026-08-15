# 扫雷 / Minesweeper 🎯

经典 Windows XP 风格扫雷游戏 · Classic Windows XP-style Minesweeper

Electron 跨平台桌面应用 / Cross-platform desktop app for macOS · Windows · Linux

---

## 功能 / Features

| 中文 | English |
|---|---|
| 三档难度：初级 9×9/10雷、中级 16×16/40雷、高级 16×30/99雷 | 3 preset difficulties: Beginner, Intermediate, Expert |
| 自定义模式：自由设定行/列/雷数（最大 50×50） | Custom mode: configurable rows, cols & mines (up to 50×50) |
| 超大棋盘自动缩放格子尺寸，窗口只放大不缩小 | Auto cell scaling for huge boards; window only grows, never shrinks |
| 原生菜单栏（F2 新游戏） | Native menu bar (F2 for new game) |
| 键盘全操作 + 可自定义快捷键 | Full keyboard control with rebindable keys |
| 和弦操作：双击或左右键同时按下 | Chord: double-click or left+right click on numbers |
| 首次点击安全：3×3 无雷 + 空白格大面积展开 | Safe first click: 3×3 safe zone + guaranteed zero-cell |
| Web Audio API 合成 6 种音效（零外部文件） | 6 Web Audio API sound effects (no external files) |
| 游戏统计：总局、胜率、连胜、最快时间 | Game stats: games, win rate, streaks, best times |
| 6 套主题切换（偏好持久化） | 6 themes (persistent preference) |
| 和弦失败 shake 动画、左右键邻居格凹陷效果 | Shake animation on chord fail, press-down neighbor highlight |
| 内置单元测试（node:test，`npm test`） | Built-in unit tests (node:test, `npm test`) |

---

## 快速开始 / Quick Start

```bash
npm install
npm start
```

## 测试 / Tests

```bash
npm test
```

## 打包 / Build

```bash
# macOS
npm run dist:mac

# Windows
npm run dist:win

# Linux
npm run dist:linux
```

## 下载 / Download

发布页：[github.com/w-PiaoPiao/saolei/releases](https://github.com/w-PiaoPiao/saolei/releases)

| 平台 / Platform | 格式 | 说明 |
|---|---|---|
| macOS (Apple Silicon) | .dmg | 安装包 |
| Windows (x64) | .exe | 便携版，双击即运行 |
| Linux (arm64) | .AppImage | chmod +x 后运行 |

## 技术栈 / Tech Stack

Electron + Vanilla JavaScript + CSS3 + node:test (no framework dependencies)

## 安卓掌机版（攻氪 KPA）/ Android (Konkr KPA)

面向 960×640 横屏安卓掌机（攻氪 KPA：十字键 + ABXY）的原生移植版，代码在 `android/` 目录：

- **原生 Kotlin**：自定义 View (Canvas) 绘制，沉浸全屏，无浏览器层
- **引擎移植**：`android/core` 纯 JVM 模块逐方法移植桌面版 `game.js` / `movement.js` / `keybindings.js` / `stats.js`，JUnit 单元测试覆盖
- **手柄操作**：方向键移动（斜向 + 长按重复）、B=翻开/和弦、A=标雷（旗→问号→取消）、Start=新游戏、Select=菜单
- **键位自定义**：菜单 → 键位设置，任意键可重绑（含按键测试屏），持久化保存
- **其余对齐桌面版**：三档难度 + 自定义雷区、6 套主题、6 种运行时合成音效、统计、暂停恢复、首次点击安全、和弦失败 shake

### 构建与安装

```bash
cd android
./gradlew :app:assembleDebug          # 产出 app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> 故障排查：若本机项目路径含非 ASCII 字符（如中文目录）导致 Kotlin 编译报错或路径被转义为 `uXXXX`，把 `android/` 复制到纯 ASCII 路径下构建即可（GitHub CI 不受影响）。

KPA 需先在系统设置中开启「开发者选项 → USB 调试」。调试包直接侧载安装；正式发布用 `assembleRelease` 并配置签名。

### 操作说明

| 按键 | 功能 |
|---|---|
| 方向键 | 移动光标（可斜向，长按连续移动） |
| B | 翻开格子；在已翻开数字格上按 = 和弦 |
| A | 标雷（插旗 → 问号 → 取消） |
| Start | 新游戏 |
| Select | 菜单（难度/主题/键位/统计等） |
| Back | 关闭对话框 / 打开菜单 |

触摸（可选）：点按=翻开/和弦，长按=标雷，点表情=新游戏。

## CI / 自动化发布

- **桌面版**：推 `desktop-v*` 标签（如 `desktop-v1.3.0`）触发 [GitHub Actions](.github/workflows/build.yml)：先跑单元测试，再构建 macOS / Windows / Linux 三平台安装包并自动发布到 GitHub Releases。
- **安卓版**：推 `v*` 标签（如 `v1.2.1`）触发 [android.yml](.github/workflows/android.yml)：跑单测、构建 Debug APK；若仓库配置了签名 secrets（`ANDROID_KEYSTORE_BASE64` / `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD`），还会构建**签名正式版**并自动发布到 GitHub Releases。

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
