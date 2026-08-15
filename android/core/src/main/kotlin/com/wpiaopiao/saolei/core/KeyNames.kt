package com.wpiaopiao.saolei.core

/** 键码 → 物理键显示名（设置界面与按键测试屏使用）。 */
object KeyNames {
    fun nameOf(code: Int): String = when (code) {
        KeyCodes.BACK -> "Back"
        KeyCodes.DPAD_UP -> "上"
        KeyCodes.DPAD_DOWN -> "下"
        KeyCodes.DPAD_LEFT -> "左"
        KeyCodes.DPAD_RIGHT -> "右"
        KeyCodes.DPAD_CENTER -> "确认"
        KeyCodes.BUTTON_A -> "A"
        KeyCodes.BUTTON_B -> "B"
        KeyCodes.BUTTON_X -> "X"
        KeyCodes.BUTTON_Y -> "Y"
        KeyCodes.BUTTON_L1 -> "L1"
        KeyCodes.BUTTON_R1 -> "R1"
        KeyCodes.BUTTON_L2 -> "L2"
        KeyCodes.BUTTON_R2 -> "R2"
        KeyCodes.BUTTON_START -> "Start"
        KeyCodes.BUTTON_SELECT -> "Select"
        else -> "键码$code"
    }
}
