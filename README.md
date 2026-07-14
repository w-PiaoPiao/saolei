# 扫雷 / Minesweeper 🎯

经典 Windows XP 风格扫雷游戏 · Classic Windows XP-style Minesweeper

Electron 跨平台桌面应用 / Cross-platform desktop app for macOS · Windows · Linux

---

## 功能 / Features

| 中文 | English |
|---|---|
| 三档难度：初级 9×9/10雷、中级 16×16/40雷、高级 16×30/99雷 | 3 preset difficulties: Beginner, Intermediate, Expert |
| 自定义模式：自由设定行/列/雷数 | Custom mode: configurable rows, cols & mines |
| 原生菜单栏（F2 新游戏） | Native menu bar (F2 for new game) |
| 和弦操作：双击或左右键同时按下 | Chord: double-click or left+right click on numbers |
| 首次点击安全：3×3 无雷 + 空白格大面积展开 | Safe first click: 3×3 safe zone + guaranteed zero-cell |
| Web Audio API 合成 6 种音效（零外部文件） | 6 Web Audio API sound effects (no external files) |
| 游戏统计：总局、胜率、连胜、最快时间 | Game stats: games, win rate, streaks, best times |
| 深色/浅色主题切换（偏好持久化） | Dark / Light theme toggle (persistent) |
| 和弦失败 shake 动画、左右键邻居格凹陷效果 | Shake animation on chord fail, press-down neighbor highlight |

---

## 快速开始 / Quick Start

```bash
npm install
npm start
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

Electron + Vanilla JavaScript + CSS3 (no framework dependencies)
