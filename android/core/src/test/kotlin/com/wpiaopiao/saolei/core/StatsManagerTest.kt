package com.wpiaopiao.saolei.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 统计测试：对齐桌面版 stats.js 行为。 */
class StatsManagerTest {

    @Test
    fun `默认值全零`() {
        val s = StatsManager(FakeStorage())
        val summary = s.getSummary()
        assertEquals(0, summary.totalGames)
        assertEquals(0, summary.gamesWon)
        assertEquals(0, summary.winRate)
        assertEquals(0, summary.currentStreak)
        assertEquals(0, summary.totalPlayTime)
    }

    @Test
    fun `开局计数`() {
        val s = StatsManager(FakeStorage())
        s.onGameStart("beginner")
        s.onGameStart("expert")
        assertEquals(2, s.getSummary().totalGames)
        assertEquals("expert", s.lastLevel)
    }

    @Test
    fun `胜利更新连胜与最快时间`() {
        val storage = FakeStorage()
        val s = StatsManager(storage)
        s.onGameStart("beginner")
        s.onGameWin(45)
        s.onGameStart("beginner")
        s.onGameWin(60)

        val summary = s.getSummary()
        assertEquals(2, summary.gamesWon)
        assertEquals(2, summary.currentStreak)
        assertEquals(2, summary.bestStreak)
        assertEquals(45, summary.bestTime["beginner"])
        assertEquals(105, summary.totalPlayTime)
        assertEquals(100, summary.winRate)

        // 持久化：重建后一致
        val s2 = StatsManager(storage)
        val sum2 = s2.getSummary()
        assertEquals(2, sum2.gamesWon)
        assertEquals(45, sum2.bestTime["beginner"])
    }

    @Test
    fun `失败重置连胜但保留最佳连胜`() {
        val s = StatsManager(FakeStorage())
        s.onGameStart("intermediate")
        s.onGameWin(100)
        s.onGameStart("intermediate")
        s.onGameWin(80)
        s.onGameStart("intermediate")
        s.onGameLose(50)

        val summary = s.getSummary()
        assertEquals(0, summary.currentStreak)
        assertEquals(2, summary.bestStreak)
        assertEquals(80, summary.bestTime["intermediate"])
        assertEquals(230, summary.totalPlayTime)
    }

    @Test
    fun `自定义难度记录最快时间`() {
        val s = StatsManager(FakeStorage())
        s.onGameStart("custom")
        s.onGameWin(200)
        assertEquals(200, s.getSummary().bestTime["custom"])
    }

    @Test
    fun `重置清空全部`() {
        val storage = FakeStorage()
        val s = StatsManager(storage)
        s.onGameStart("beginner")
        s.onGameWin(30)
        s.reset()
        val summary = s.getSummary()
        assertEquals(0, summary.totalGames)
        assertEquals(0, summary.gamesWon)
        assertEquals(0, summary.bestStreak)
        assertNull(summary.bestTime["beginner"])
        assertEquals(0, summary.totalPlayTime)

        val s2 = StatsManager(storage)
        assertEquals(0, s2.getSummary().totalGames)
    }

    @Test
    fun `胜利记录 3BV 效率并写入历史`() {
        val storage = FakeStorage()
        val s = StatsManager(storage)
        s.onGameStart("beginner")
        s.onGameWin(30, 45) // 45 3BV / 30s = 1.5 3BV/s
        assertEquals(1.5, s.getSummary().bestBbbvPerSec["beginner"]!!, 1e-9)
        val h = s.getHistory("beginner")
        assertEquals(1, h.size)
        assertEquals(45, h[0].bbbv)
        assertEquals(30, h[0].time)
        assertTrue("时间戳应被记录", h[0].ts > 0)

        // 持久化：重建后历史与效率都还在
        val s2 = StatsManager(storage)
        assertEquals(1.5, s2.getSummary().bestBbbvPerSec["beginner"]!!, 1e-9)
        assertEquals(1, s2.getHistory("beginner").size)
    }

    @Test
    fun `最佳效率只在更快的一局时刷新`() {
        val s = StatsManager(FakeStorage())
        s.onGameStart("beginner")
        s.onGameWin(30, 45) // 1.5
        s.onGameStart("beginner")
        s.onGameWin(60, 45) // 0.75，不刷新
        assertEquals(1.5, s.getSummary().bestBbbvPerSec["beginner"]!!, 1e-9)
        s.onGameStart("beginner")
        s.onGameWin(20, 50) // 2.5，刷新
        assertEquals(2.5, s.getSummary().bestBbbvPerSec["beginner"]!!, 1e-9)
    }

    @Test
    fun `历史按难度过滤且总数受上限约束`() {
        val s = StatsManager(FakeStorage())
        repeat(60) { i ->
            s.onGameStart("beginner")
            s.onGameWin(30 + i, 40)
        }
        s.onGameStart("expert")
        s.onGameWin(100, 120)
        assertEquals(20, s.getHistory("beginner").size) // 默认取最近 20 局
        assertEquals(1, s.getHistory("expert").size)
        // 总上限 50：60+1 条被截断为 50，最旧的 11 条（都是 beginner）被丢弃
        assertEquals(49, s.getHistory("beginner", 100).size)
        assertEquals(1, s.getHistory("expert", 100).size)
    }

    @Test
    fun `未带 3BV 的胜利不写历史（兼容旧调用）`() {
        val s = StatsManager(FakeStorage())
        s.onGameStart("beginner")
        s.onGameWin(30)
        assertNull(s.getSummary().bestBbbvPerSec["beginner"])
        assertEquals(0, s.getHistory("beginner").size)
    }

    @Test
    fun `损坏的历史记录行被跳过`() {
        val storage = FakeStorage()
        storage.putString("stats.history", "beginner|30|45|100;坏行;expert|x|y|z;expert|60|80|200")
        val s = StatsManager(storage)
        val h = s.getHistory("expert")
        assertEquals(1, h.size)
        assertEquals(80, h[0].bbbv)
        assertEquals(1, s.getHistory("beginner").size)
    }
}
