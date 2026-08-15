package com.wpiaopiao.saolei.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
