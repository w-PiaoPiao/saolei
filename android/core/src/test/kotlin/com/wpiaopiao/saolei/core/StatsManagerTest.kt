package com.wpiaopiao.saolei.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 统计测试：对齐桌面版 stats.js 行为，并覆盖 v1.3 的单键 JSON 存储、
 * 旧存档迁移、分桶 history、0 秒/暂停/旗胜守卫。
 */
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
        assertEquals(0.0, summary.bestBbbvPerSec["beginner"] ?: 0.0, 1e-9)
        assertNull(s.bestTimeOf("beginner"))
    }

    @Test
    fun `开局计数与 bucket`() {
        val s = StatsManager(FakeStorage())
        s.onGameStart("beginner")
        s.onGameStart("expert")
        assertEquals(2, s.getSummary().totalGames)
        assertEquals("expert", s.lastLevel)
        assertEquals("expert", s.lastBucket)
        assertEquals(listOf("beginner", "expert"), s.getBuckets())
    }

    @Test
    fun `自定义开局使用几何 bucket key`() {
        val s = StatsManager(FakeStorage())
        s.onGameStart("custom", GameEngine.CustomConfig(9, 9, 10))
        assertEquals("custom:9x9/10", s.lastBucket)
        s.onGameStart("custom", GameEngine.CustomConfig(50, 50, 500))
        assertEquals("custom:50x50/500", s.lastBucket)
        assertEquals(
            listOf("custom:50x50/500", "custom:9x9/10"),
            s.getBuckets()
        )
        assertEquals("custom:9x9/10", StatsManager.bucketOf("custom", GameEngine.CustomConfig(9, 9, 10)))
        assertEquals("beginner", StatsManager.bucketOf("beginner", null))
    }

    @Test
    fun `胜利更新连胜与最快时间`() {
        val storage = FakeStorage()
        val s = StatsManager(storage)
        s.onGameStart("beginner")
        s.onGameWin(45, durationMs = 45_000L)
        s.onGameStart("beginner")
        s.onGameWin(60, durationMs = 60_000L)

        val summary = s.getSummary()
        assertEquals(2, summary.gamesWon)
        assertEquals(2, summary.currentStreak)
        assertEquals(2, summary.bestStreak)
        assertEquals(45, summary.bestTime["beginner"])
        assertEquals(105, summary.totalPlayTime)
        assertEquals(100, summary.winRate)

        // 持久化：重建后一致
        val s2 = StatsManager(storage)
        assertEquals(2, s2.getSummary().gamesWon)
        assertEquals(45, s2.bestTimeOf("beginner"))
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
    fun `0 秒守卫：无毫秒计时的 0 秒胜利不更新最快时间`() {
        val s = StatsManager(FakeStorage())
        s.onGameStart("custom", GameEngine.CustomConfig(9, 9, 1))
        s.onGameWin(0) // 旧调用：time=0 且没有 durationMs
        assertNull("0 秒且无精确计时不应写最快时间", s.bestTimeOf("custom:9x9/1"))
        assertEquals(0, s.getHistory("custom:9x9/1").size)

        // 有毫秒计时时允许记录（真实 <1s 通关）
        s.onGameStart("custom", GameEngine.CustomConfig(9, 9, 1))
        s.onGameWin(0, bbbv = 1, durationMs = 800L)
        assertEquals(0, s.bestTimeOf("custom:9x9/1"))
        assertEquals(1, s.getHistory("custom:9x9/1").size)
    }

    @Test
    fun `暂停局写 history 但标记 paused 且不更新最佳`() {
        val s = StatsManager(FakeStorage())
        s.onGameStart("beginner")
        s.onGameWin(20, bbbv = 40, durationMs = 20_000L, pausedMs = 5_000L)
        assertEquals("暂停局不应写最快时间", null, s.bestTimeOf("beginner"))
        assertNull(s.getSummary().bestBbbvPerSec["beginner"])
        val h = s.getHistory("beginner")
        assertEquals(1, h.size)
        assertTrue(h[0].paused)
        assertFalse(h[0].wonByFlags)
    }

    @Test
    fun `旗胜局写 history 标记 but 不更新 3BV-s 最佳`() {
        val s = StatsManager(FakeStorage())
        s.onGameStart("custom", GameEngine.CustomConfig(16, 16, 40))
        s.onGameWin(30, bbbv = 50, durationMs = 30_000L, wonByFlags = true)
        val key = "custom:16x16/40"
        assertEquals("旗胜仍可记录最快时间", 30, s.bestTimeOf(key))
        assertNull("旗胜不算 3BV/s 最佳", s.getSummary().bestBbbvPerSec[key])
        val h = s.getHistory(key)
        assertEquals(1, h.size)
        assertTrue(h[0].wonByFlags)
    }

    @Test
    fun `3BV-s 用毫秒计时计算`() {
        val s = StatsManager(FakeStorage())
        s.onGameStart("beginner")
        s.onGameWin(30, bbbv = 45, durationMs = 30_000L)
        assertEquals(1.5, s.getSummary().bestBbbvPerSec["beginner"]!!, 1e-9)
    }

    @Test
    fun `history 按 bucket 分桶各自限长 50`() {
        val s = StatsManager(FakeStorage())
        repeat(60) { i ->
            s.onGameStart("beginner")
            s.onGameWin(30 + i, bbbv = 40, durationMs = (30 + i) * 1000L)
            s.onGameStart("expert")
            s.onGameWin(100 + i, bbbv = 120, durationMs = (100 + i) * 1000L)
        }
        assertEquals("初级只保留最近 50 局", 50, s.getHistory("beginner", 100).size)
        assertEquals("高级不受初级局数影响", 50, s.getHistory("expert", 100).size)
        assertEquals(20, s.getHistory("beginner").size) // 默认 limit=20
        assertEquals(0, s.getHistory("beginner", 0).size)
        assertEquals(0, s.getHistory("beginner", -5).size)
        // 最新的在最后（时间升序）
        val h = s.getHistory("expert")
        assertTrue(h.last().time >= h.first().time)
    }

    @Test
    fun `不同几何的自定义盘 history 互相独立`() {
        val s = StatsManager(FakeStorage())
        s.onGameStart("custom", GameEngine.CustomConfig(9, 9, 10))
        s.onGameWin(10, bbbv = 15, durationMs = 10_000L)
        s.onGameStart("custom", GameEngine.CustomConfig(50, 50, 500))
        s.onGameWin(200, bbbv = 900, durationMs = 200_000L)
        assertEquals(1, s.getHistory("custom:9x9/10").size)
        assertEquals(1, s.getHistory("custom:50x50/500").size)
        assertEquals(10, s.bestTimeOf("custom:9x9/10"))
        assertEquals(200, s.bestTimeOf("custom:50x50/500"))
    }

    @Test
    fun `单事件只写一次 stats_v2 键`() {
        val storage = FakeStorage()
        val s = StatsManager(storage)
        assertEquals("初次加载空存档不应写盘", 0, storage.putCount)
        s.onGameStart("beginner")
        assertEquals(1, storage.putCount)
        s.onGameWin(30, bbbv = 45, durationMs = 30_000L)
        assertEquals(2, storage.putCount)
        s.onGameLose(10)
        assertEquals(3, storage.putCount)
        assertEquals(setOf(StatsManager.STORAGE_KEY), storage.map.keys.toSet())
        assertNotNull(storage.getString(StatsManager.STORAGE_KEY))
    }

    @Test
    fun `旧多键存档自动迁移为单键并清理旧键`() {
        val storage = FakeStorage()
        storage.putString("stats.totalGames", "2")
        storage.putString("stats.gamesWon", "1")
        storage.putString("stats.currentStreak", "1")
        storage.putString("stats.bestStreak", "1")
        storage.putString("stats.totalPlayTime", "100")
        storage.putString("stats.lastLevel", "beginner")
        storage.putString("stats.bestTime.beginner", "30")
        storage.putString("stats.bestBbbvPerSec.beginner", "1.5")
        storage.putString("stats.history", "beginner|30|45|1000;expert|60|80|2000")

        val s = StatsManager(storage)
        assertEquals(2, s.totalGames)
        assertEquals(1, s.gamesWon)
        assertEquals(100, s.totalPlayTime)
        assertEquals(30, s.bestTimeOf("beginner"))
        assertEquals(1.5, s.getSummary().bestBbbvPerSec["beginner"]!!, 1e-9)
        assertEquals(1, s.getHistory("beginner").size)
        assertEquals(45, s.getHistory("beginner")[0].bbbv)
        assertEquals(1, s.getHistory("expert", 10).size)

        // 迁移后：v2 存在、旧键被清理
        assertNotNull(storage.getString(StatsManager.STORAGE_KEY))
        assertNull(storage.getString("stats.totalGames"))
        assertNull(storage.getString("stats.history"))
        assertNull(storage.getString("stats.bestTime.beginner"))

        // 重建后仍是迁移后的数据
        val s2 = StatsManager(storage)
        assertEquals(2, s2.totalGames)
        assertEquals(30, s2.bestTimeOf("beginner"))
        assertEquals(1, s2.getHistory("expert", 10).size)
    }

    @Test
    fun `损坏的 v2 JSON 回退默认值且不抛异常`() {
        val storage = FakeStorage()
        storage.putString(StatsManager.STORAGE_KEY, "{这不是合法 JSON")
        val s = StatsManager(storage)
        assertEquals(0, s.totalGames)
        assertNull(s.bestTimeOf("beginner"))
        // 仍可正常记账并覆盖损坏存档
        s.onGameStart("beginner")
        s.onGameWin(30, durationMs = 30_000L)
        val s2 = StatsManager(storage)
        assertEquals(1, s2.gamesWon)
    }

    @Test
    fun `损坏的 v2 存在时仍可迁移旧存档`() {
        val storage = FakeStorage()
        storage.putString(StatsManager.STORAGE_KEY, "garbage")
        storage.putString("stats.totalGames", "7")
        storage.putString("stats.gamesWon", "3")
        val s = StatsManager(storage)
        assertEquals(7, s.totalGames)
        assertEquals(3, s.gamesWon)
        assertNotNull(storage.getString(StatsManager.STORAGE_KEY))
    }

    @Test
    fun `reset 清空内存与存储`() {
        val storage = FakeStorage()
        val s = StatsManager(storage)
        s.onGameStart("beginner")
        s.onGameWin(30, bbbv = 45, durationMs = 30_000L)
        s.reset()
        assertEquals(0, s.totalGames)
        assertEquals(0, s.gamesWon)
        assertEquals(0, s.bestStreak)
        assertNull(s.bestTimeOf("beginner"))
        assertNull(s.lastLevel)
        assertNull("reset 应删除 v2 键", storage.getString(StatsManager.STORAGE_KEY))
        assertNull(storage.getString("stats.bestTime.beginner"))
        assertEquals(0, StatsManager(storage).totalGames)
    }

    @Test
    fun `旧存档空串 lastLevel 归一为 null`() {
        val storage = FakeStorage()
        storage.putString("stats.lastLevel", "")
        storage.putString("stats.totalGames", "1")
        val s = StatsManager(storage)
        assertNull(s.lastLevel)
        assertEquals(1, s.totalGames)
    }

    @Test
    fun `bestTime 与 bestBbbvPerSec 对外只读副本`() {
        val s = StatsManager(FakeStorage())
        s.onGameStart("beginner")
        s.onGameWin(30, bbbv = 45, durationMs = 30_000L)
        @Suppress("UNCHECKED_CAST")
        val times = s.bestTime as MutableMap<String, Int?>
        times["beginner"] = 1
        assertEquals("外部修改不得影响内部统计", 30, s.bestTimeOf("beginner"))
        @Suppress("UNCHECKED_CAST")
        val rates = s.bestBbbvPerSec as MutableMap<String, Double?>
        rates["beginner"] = 999.0
        assertEquals(1.5, s.getSummary().bestBbbvPerSec["beginner"]!!, 1e-9)
    }

    @Test
    fun `selectBucket 用于恢复存档对局：只对齐桶、不重复计数`() {
        val storage = FakeStorage()
        val s = StatsManager(storage)
        s.selectBucket("custom", GameEngine.CustomConfig(16, 16, 40))
        assertEquals("custom", s.lastLevel)
        assertEquals("custom:16x16/40", s.lastBucket)
        assertEquals("selectBucket 不应增加总局数", 0, s.totalGames)
        s.onGameWin(30, bbbv = 45, durationMs = 30_000L)
        assertEquals(1, s.gamesWon)
        assertEquals(30, s.bestTimeOf("custom:16x16/40"))
        assertEquals(1, s.getHistory("custom:16x16/40").size)
    }

    @Test
    fun `selectBucket 预设难度同样生效`() {
        val s = StatsManager(FakeStorage())
        s.selectBucket("beginner")
        s.onGameWin(20, durationMs = 20_000L)
        assertEquals(20, s.bestTimeOf("beginner"))
    }

    @Test
    fun `getSummary 统计口径`() {
        val s = StatsManager(FakeStorage())
        s.onGameStart("beginner")
        s.onGameLose(10)
        s.onGameStart("beginner")
        s.onGameWin(20, bbbv = 30, durationMs = 20_000L)
        val summary = s.getSummary()
        assertEquals(2, summary.totalGames)
        assertEquals(1, summary.gamesWon)
        assertEquals(1, summary.gamesLost)
        assertEquals(50, summary.winRate)
        assertEquals(listOf("beginner"), summary.buckets)
        assertTrue(summary.bestTime.containsKey("beginner"))
        assertTrue(summary.bestTime.containsKey("custom"))
        assertTrue(summary.bestTime.containsKey("expert"))
    }
}
