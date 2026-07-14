# 经典复刻扫雷 — 设计文档

## 目标
跨平台桌面扫雷游戏（Windows/macOS/Linux），Electron + Web 技术。

## 技术栈
- Electron 主进程窗口管理
- Vanilla JS (ES6+) 游戏引擎 + UI 渲染
- 经典灰色像素风 CSS

## 架构
```
扫雷/
├── package.json
├── main.js            # Electron 主进程
├── preload.js         # 预加载（安全桥接）
└── src/
    ├── index.html     # 主页面
    ├── style.css      # 经典像素风样式
    ├── game.js        # GameEngine 类
    └── ui.js          # UI 渲染器
```

## GameEngine
- `init(rows, cols, mines)` — 初始化（首次点击后布雷）
- `reveal(row, col)` — 翻开，递归展开空白
- `toggleFlag(row, col)` — 循环：无 → 🚩 → ❓ → 无
- `chord(row, col)` — 双击数字展开
- 状态机：`ready → playing → won | lost`

## UI
- 3D 凸起/凹陷边框（经典灰色）
- 数字颜色：1蓝 2绿 3红 4紫 5栗 6青 7黑 8灰
- 笑脸状态切换
- 难度切换（初级 9×9/10雷，中级 16×16/40雷，高级 30×16/99雷）

## 打包
electron-builder → .dmg / .exe / .AppImage
