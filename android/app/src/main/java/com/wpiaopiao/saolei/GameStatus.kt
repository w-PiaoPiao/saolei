package com.wpiaopiao.saolei

/**
 * 底栏状态区（时钟 + 电量）的纯逻辑：无 Android 依赖，便于 JVM 单测。
 *
 * 电池广播的 level/scale/status/plugged 解析、百分比文案、图标填充比例与配色规则
 * 全部集中在这里；[GameView] 只负责把结果画到底栏，[StatusWatcher] 只负责收广播。
 */
object GameStatus {

    /** 低电量（含）阈值：≤ 此值电池图标填红色 */
    const val LOW_BATTERY_PERCENT = 20

    /** 充电中图标填充色（绿） */
    val CHARGING_FILL_COLOR = 0xFF2E7D32.toInt()

    /** 低电量图标填充色（红） */
    val LOW_FILL_COLOR = 0xFFC62828.toInt()

    // BatteryManager.BATTERY_STATUS_CHARGING / BATTERY_STATUS_FULL 的数值，
    // 这里内联成常量，避免把 android.os 依赖带进纯逻辑单元测试。
    private const val BATTERY_STATUS_CHARGING = 2
    private const val BATTERY_STATUS_FULL = 5

    /**
     * 系统电池广播的 level/scale → 0..100 百分比；未知（level < 0 或 scale ≤ 0）返回 null。
     * 四舍五入，避免 57/100 之类的显示误差。
     */
    fun batteryPercent(level: Int, scale: Int): Int? {
        if (level < 0 || scale <= 0) return null
        return ((level.toLong() * 100 + scale / 2) / scale).toInt().coerceIn(0, 100)
    }

    /** 电量文案：未知显示 "--"，正常显示百分比（如 "86%"）。 */
    fun batteryLabel(percent: Int?): String = if (percent == null) "--" else "$percent%"

    /** 电池图标内部填充比例（0f..1f）；未知按 0 处理。 */
    fun batteryFillRatio(percent: Int?): Float = ((percent ?: 0).coerceIn(0, 100)) / 100f

    /**
     * 电池图标填充颜色：充电中绿色 > 低电量红色 > 主题正常色 [normal]（通常取主题文字色）。
     * 电量未知时不按低电量处理，回落到 [normal]。
     */
    fun batteryFillColor(percent: Int?, charging: Boolean, normal: Int): Int = when {
        charging -> CHARGING_FILL_COLOR
        percent == null -> normal
        percent <= LOW_BATTERY_PERCENT -> LOW_FILL_COLOR
        else -> normal
    }

    /**
     * 是否处于「已接电源」（充电中或已充满）状态。
     *
     * @param status BatteryManager.EXTRA_STATUS（-1 = 未知）
     * @param plugged BatteryManager.EXTRA_PLUGGED（0 = 未接电源）
     */
    fun isCharging(status: Int, plugged: Int): Boolean =
        status == BATTERY_STATUS_CHARGING || status == BATTERY_STATUS_FULL || plugged != 0
}
