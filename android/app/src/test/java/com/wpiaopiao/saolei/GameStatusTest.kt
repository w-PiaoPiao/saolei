package com.wpiaopiao.saolei

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GameStatus] 纯逻辑单测：电池广播解析、电量文案、图标填充比例/配色与充电判定边界。
 * 这些方法不持有 Android 依赖，直接在 JVM 上跑。
 */
class GameStatusTest {

    private val eps = 0.0001f

    /** 主题正常色（任意值只用于区分）：实际由 GameView 传主题文字色。 */
    private val normal = 0xFFE0E0E0.toInt()

    @Test
    fun `电量百分比按 0 50 100 解析`() {
        assertEquals(0, GameStatus.batteryPercent(0, 100))
        assertEquals(50, GameStatus.batteryPercent(50, 100))
        assertEquals(100, GameStatus.batteryPercent(100, 100))
        // Android 电池广播常见 scale=100，也兼容非 100 刻度
        assertEquals(86, GameStatus.batteryPercent(86, 100))
        assertEquals(50, GameStatus.batteryPercent(128, 255))
    }

    @Test
    fun `电量百分比四舍五入`() {
        // 56.5% → 57%，57.4% → 57%，57.5% → 58%
        assertEquals(57, GameStatus.batteryPercent(565, 1000))
        assertEquals(57, GameStatus.batteryPercent(574, 1000))
        assertEquals(58, GameStatus.batteryPercent(575, 1000))
        // 0.5% → 1%，0.4% → 0%
        assertEquals(1, GameStatus.batteryPercent(1, 200))
        assertEquals(0, GameStatus.batteryPercent(4, 1000))
    }

    @Test
    fun `电量百分比上限 100`() {
        // level 超过 scale（个别设备充电瞬间电平虚高）夹到 100
        assertEquals(100, GameStatus.batteryPercent(101, 100))
        assertEquals(100, GameStatus.batteryPercent(150, 100))
    }

    @Test
    fun `电量百分比未知返回 null`() {
        assertNull(GameStatus.batteryPercent(-1, 100))
        assertNull(GameStatus.batteryPercent(50, 0))
        assertNull(GameStatus.batteryPercent(50, -1))
    }

    @Test
    fun `电量文案未知显示两个横线`() {
        assertEquals("--", GameStatus.batteryLabel(null))
        assertEquals("0%", GameStatus.batteryLabel(0))
        assertEquals("57%", GameStatus.batteryLabel(57))
        assertEquals("100%", GameStatus.batteryLabel(100))
    }

    @Test
    fun `电池填充比例映射到 0 到 1 并夹紧`() {
        assertEquals(0f, GameStatus.batteryFillRatio(null), eps)
        assertEquals(0f, GameStatus.batteryFillRatio(0), eps)
        assertEquals(0.5f, GameStatus.batteryFillRatio(50), eps)
        assertEquals(1f, GameStatus.batteryFillRatio(100), eps)
        // 越界输入夹紧，避免图标画出电池框外
        assertEquals(0f, GameStatus.batteryFillRatio(-20), eps)
        assertEquals(1f, GameStatus.batteryFillRatio(120), eps)
    }

    @Test
    fun `电池配色充电中优先于低电量`() {
        assertEquals(
            GameStatus.CHARGING_FILL_COLOR,
            GameStatus.batteryFillColor(5, charging = true, normal = normal),
        )
        assertEquals(
            GameStatus.CHARGING_FILL_COLOR,
            GameStatus.batteryFillColor(null, charging = true, normal = normal),
        )
        // 低电量阈值内充电，仍显示充电绿
        assertEquals(
            GameStatus.CHARGING_FILL_COLOR,
            GameStatus.batteryFillColor(GameStatus.LOW_BATTERY_PERCENT, charging = true, normal = normal),
        )
    }

    @Test
    fun `电池配色低电量阈值 20`() {
        assertEquals(20, GameStatus.LOW_BATTERY_PERCENT)
        assertEquals(GameStatus.LOW_FILL_COLOR, GameStatus.batteryFillColor(0, false, normal))
        assertEquals(
            GameStatus.LOW_FILL_COLOR,
            GameStatus.batteryFillColor(GameStatus.LOW_BATTERY_PERCENT, false, normal),
        )
        assertEquals(normal, GameStatus.batteryFillColor(GameStatus.LOW_BATTERY_PERCENT + 1, false, normal))
        assertEquals(normal, GameStatus.batteryFillColor(80, false, normal))
    }

    @Test
    fun `电池配色电量未知按正常色`() {
        // 未知电量不误判为低电量
        assertEquals(normal, GameStatus.batteryFillColor(null, charging = false, normal = normal))
    }

    @Test
    fun `充电判定状态或已接电源均为真`() {
        // BatteryManager.BATTERY_STATUS_CHARGING = 2
        assertTrue(GameStatus.isCharging(2, 0))
        // BatteryManager.BATTERY_STATUS_FULL = 5
        assertTrue(GameStatus.isCharging(5, 0))
        // status 未报充电，但 plugged != 0（USB / AC / 无线充电）
        assertTrue(GameStatus.isCharging(3, 1))
        assertTrue(GameStatus.isCharging(-1, 2))
    }

    @Test
    fun `非充电判定未知状态且未接电源为假`() {
        assertFalse(GameStatus.isCharging(-1, 0))
        // BATTERY_STATUS_DISCHARGING = 3、BATTERY_STATUS_NOT_CHARGING = 4 且未接电源
        assertFalse(GameStatus.isCharging(3, 0))
        assertFalse(GameStatus.isCharging(4, 0))
    }
}
