# 经典复刻扫雷 — 设计文档

## 目标
跨平台桌面扫雷游戏（Windows/macOS/Linux），Electron + Web 技术，经典 Windows XP 风格。

## 技术栈
- Electron 主进程窗口管理 + 原生菜单
- Vanilla JS (ES6+) 游戏引擎 + UI 渲染
- Web Audio API 音效合成（零外部文件）
- CSS 变量实现深色/浅色主题切换
- localStorage 持久化统计数据与用户偏好

## 架构
```
扫雷/
├── package.json           # Electron + electron-builder 配置
├── main.js                # 主进程（窗口、菜单、IPC）
├── preload.js             # 安全桥接（IPC 通道）
└── src/
    ├── index.html         # 主页面（入口、对话框、菜单事件）
    ├── style.css          # 经典像素风 + 深色模式 + 对话框样式
    ├── game.js            # GameEngine 类
    ├── ui.js              # UIRenderer 类
    ├── audio.js           # AudioManager 类（Web Audio 音效）
    └── stats.js           # StatsManager 类（游戏统计持久化）
```

## GameEngine (`src/game.js`)
- `init(level)` — 按预设难度初始化（首次点击后布雷）
- `initCustom(rows, cols, mines)` — 自定义模式
- `reveal(row, col)` — 翻开，递归展开空白
- `toggleFlag(row, col)` — 循环：无 → 🚩 → ❓ → 无
- `chord(row, col)` → `boolean` — 双击/和弦展开，返回是否执行
- `placeMines(safeRow, safeCol)` — 布雷，3×3 安全区 + 确保点击格为空白(0)
- 状态机：`ready → playing → won | lost`

### 难度
| 级别 | 行×列 | 雷数 |
|---|---|---|
| 初级 | 9×9 | 10 |
| 中级 | 16×16 | 40 |
| 高级 | 16×30 | 99 |
| 自定义 | 9~50 × 9~50 | 1 ~ 85%格子数 |

## UI (`src/ui.js`)
- 3D 凸起/凹陷边框（经典灰色）
- 数字颜色：1蓝 2绿 3红 4紫 5栗 6青 7黑 8灰
- 笑脸状态切换（🙂正常/😎胜利/😵失败）
- 难度按钮（标题栏右侧）
- 和弦失败 shake 动画反馈
- 左右键同时按下 chord（原版行为，含邻居格压下高亮）
- 深色/浅色主题切换（CSS 变量）

## 菜单栏（Electron 原生, `main.js`）
```
游戏(G)    难度(D)     主题      帮助(H)
├─ 新游戏   ├─ 初级     ├─ 浅色    ├─ 游戏统计
│  F2       ├─ 中级     ├─ 深色    ├─ ───
├─ ───      ├─ 高级                └─ 关于扫雷
└─ 退出     ├─ ───
            └─ 自定义...
```

- 菜单项通过 IPC `menu-action` 通知渲染进程
- 菜单状态同步：难度/主题选择与界面状态保持一致

## 自定义模式
- 对话框：行、列、雷数输入框
- 约束：行/列 9~50，雷数 ≤ 格子数 × 85%
- 校验失败时弹窗提示

## 音效 (`src/audio.js`)

| 音效 | 合成方式 |
|---|---|
| 点击 (click) | 600Hz 方波 50ms |
| 标旗 (flag) | 400Hz 方波 80ms |
| 和弦成功 (chordSuccess) | 800Hz 正弦 40ms |
| 和弦失败 (chordFail) | 150Hz 三角波 150ms |
| 爆炸 (explosion) | 噪声 400ms + 60Hz 锯齿波 300ms |
| 胜利 (win) | 上升音阶 C5→E5→G5→C6 |

- AudioContext 延迟创建（首次用户交互后）
- `enabled` 标志控制开关

## 游戏统计 (`src/stats.js`)
- 持久化方式：localStorage
- 追踪数据：总局数、胜/败、胜率、当前连胜、最长连胜、各难度最快完成时间、总游戏时间
- 通过菜单「游戏统计」查看

## 深色模式
- CSS 自定义属性 (`:root` / `[data-theme="dark"]`)
- 覆盖：面板色、格子色、LED 数字色、边框色、页面底色
- 偏好存储于 localStorage
- 菜单栏「主题」同步切换

## 交互细节
- 首次点击不会踩雷 + 3×3 安全区 + 确保点击格为空白(0)
- 右键循环：无标记 → 🚩 → ❓ → 无标记
- 踩雷时所有雷显示，踩到的标红底
- 左右键同时按下：相邻未翻开格凹陷，松开时执行 chord
- chord 失败时邻居格 shake 动画
- 窗口自动适应棋盘尺寸（IPC resize）

## 打包
electron-builder → `.dmg` / `.exe` / `.AppImage`
