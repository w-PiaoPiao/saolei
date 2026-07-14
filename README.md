# 扫雷 🎯

经典 Windows XP 风格扫雷游戏，Electron 跨平台桌面应用。

## 功能

- **三档难度**：初级 9×9/10雷、中级 16×16/40雷、高级 16×30/99雷
- **自定义模式**：自由设定行/列/雷数（菜单→难度→自定义）
- **原生菜单栏**：游戏(F2新游戏)、难度、主题、帮助
- **和弦操作**：双击或左右键同时按下已翻开数字格
- **首次点击安全**：3×3 区域不布雷，且确保点击格为空白大面积展开
- **音效**：Web Audio API 合成 6 种音效（零外部文件）
- **游戏统计**：总局、胜率、连胜、最快完成时间（localStorage 持久化）
- **深色模式**：菜单「主题」切换，偏好持久化
- **交互细节**：和弦失败 shake 动画、左右键邻居格凹陷效果、3D 边框

## 快速开始

```bash
npm install
npm start
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

## 技术栈

Electron + Vanilla JavaScript + CSS3（无框架依赖）
