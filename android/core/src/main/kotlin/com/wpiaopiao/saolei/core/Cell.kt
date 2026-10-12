package com.wpiaopiao.saolei.core

/** 单个格子的状态。与桌面版 src/game.js createBoard 的格子结构一致。 */
class Cell {
    var mine = false
    var revealed = false
    var flagged = false
    var questioned = false
    var adjacentMines = 0
    var exploded = false

    /** 失败终局时置位：插在非雷格上的旗（UI 绘制红叉提示） */
    var wrongFlag = false
}
