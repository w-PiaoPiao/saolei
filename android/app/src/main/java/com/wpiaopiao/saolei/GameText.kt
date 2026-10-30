package com.wpiaopiao.saolei

import java.util.Locale

/**
 * 纯格式化工具（无 Android 依赖，便于 JVM 单测）。
 * 全部数字格式化固定 [Locale.ROOT]，避免部分 locale（如德语/俄语）把小数点输出成逗号。
 */
object GameText {

    /**
     * 3 位 LED 计数器：非负补零到 3 位（007），负数保留负号并把绝对值补到 2 位（-05）。
     * 长度超过 3 位时原样返回（由调用方按可用宽度自适应字号，见 [GameView.drawHud]）。
     */
    fun formatCounter(n: Int): String {
        val digits = (if (n < 0) -n else n).toString()
        val target = if (n < 0) 2 else 3
        val sb = StringBuilder(if (n < 0) "-" else "")
        repeat(target - digits.length) { sb.append('0') }
        return sb.append(digits).toString()
    }

    /** 秒 → "m:ss"（分钟不封顶）。统计页总时长也用它。 */
    fun formatTime(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    /** 3BV/s 等小数：无值显示 "--"。 */
    fun formatRate(value: Double?): String =
        if (value == null) "--" else String.format(Locale.ROOT, "%.2f", value)

    /** 坐标轴刻度等一位小数。 */
    fun formatOneDecimal(value: Float): String = String.format(Locale.ROOT, "%.1f", value)
}
