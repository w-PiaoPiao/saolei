package com.wpiaopiao.saolei.core

/**
 * 物理按键码常量（与 Android KeyEvent 数值一致，但保持纯 JVM 便于单元测试）。
 * KPA 掌机：十字键 + ABXY + Start/Select。
 */
object KeyCodes {
    const val BACK = 4
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val DPAD_CENTER = 23
    const val BUTTON_A = 96
    const val BUTTON_B = 97
    const val BUTTON_X = 99
    const val BUTTON_Y = 100
    const val BUTTON_L1 = 102
    const val BUTTON_R1 = 103
    const val BUTTON_L2 = 104
    const val BUTTON_R2 = 105
    const val BUTTON_START = 108
    const val BUTTON_SELECT = 109
}
