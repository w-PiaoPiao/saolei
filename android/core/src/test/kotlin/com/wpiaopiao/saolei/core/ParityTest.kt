package com.wpiaopiao.saolei.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨端常量守护（L16/L20）：安卓侧对与桌面 src/config.js、src/stats.js、src/audio.js
 * 手工同步的常量做 golden 断言。桌面 `test/generator.test.js` 有对应断言，本测试补上安卓侧。
 */
class ParityTest {

    @Test
    fun `难度表与桌面 config js 一致`() {
        assertEquals(3, GameEngine.LEVELS.size)
        assertEquals(GameEngine.Level("初级", 9, 9, 10), GameEngine.LEVELS["beginner"])
        assertEquals(GameEngine.Level("中级", 16, 16, 40), GameEngine.LEVELS["intermediate"])
        assertEquals(GameEngine.Level("高级", 16, 30, 99), GameEngine.LEVELS["expert"])
    }

    @Test
    fun `NO_GUESS 预算与尝试上限与桌面一致`() {
        assertEquals(600, GameEngine.NO_GUESS_MAX_ATTEMPTS)
        assertEquals(800L, GameEngine.NO_GUESS_BUDGET_MS)
        assertEquals(20, GameEngine.MIN_EXPECTED_ATTEMPTS)
    }

    @Test
    fun `M1 确定性门控常量与桌面 config js 一致`() {
        assertEquals(10_000, GameEngine.MAX_CELLS)
        // 桌面 src/config.js NO_GUESS.gate：hopelessMinCells=900 / hopelessDensity=0.25 / gateAttempts=2
        assertEquals(900, GameEngine.HOPELESS_MIN_CELLS)
        assertEquals(0.25, GameEngine.HOPELESS_DENSITY, 1e-12)
        assertEquals(2, GameEngine.GATE_MAX_ATTEMPTS)
    }

    @Test
    fun `统计上限与桌面 HISTORY_MAX 一致`() {
        assertEquals(50, StatsManager.HISTORY_MAX)
        assertEquals(20, StatsManager.DEFAULT_HISTORY_LIMIT)
    }

    @Test
    fun `统计难度集合与引擎难度集合一致`() {
        val s = StatsManager(FakeStorage())
        val bestTimeKeys = s.getSummary().bestTime.keys
        for (level in GameEngine.LEVELS.keys) {
            assertTrue("统计应包含难度 $level", level in bestTimeKeys)
            assertEquals(level, StatsManager.bucketOf(level, null))
        }
        assertTrue("统计应包含 custom 桶", "custom" in bestTimeKeys)
        // 自定义桶按几何区分，且与预设 key 不冲突
        val custom = GameEngine.CustomConfig(16, 16, 40)
        assertEquals("custom:16x16/40", StatsManager.bucketOf("custom", custom))
        assertEquals("16x16/40", StatsManager.bucketOf("custom", custom).removePrefix("custom:"))
    }

    @Test
    fun `音效版本常量`() {
        assertEquals(2, Synth.SFX_VERSION)
        assertEquals(44100, Synth.SAMPLE_RATE)
        assertEquals(6, Synth.SOUNDS.size)
    }
}
