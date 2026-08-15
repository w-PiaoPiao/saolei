package com.wpiaopiao.saolei.core

/**
 * 游戏统计：总局、胜率、连胜、最快时间、总时长。
 * 由桌面版 src/stats.js 移植（localStorage JSON 换成 Storage 的按字段键值存储）。
 */
class StatsManager(private val storage: Storage) {

    data class Summary(
        val totalGames: Int,
        val gamesWon: Int,
        val gamesLost: Int,
        val winRate: Int,
        val currentStreak: Int,
        val bestStreak: Int,
        val bestTime: Map<String, Int?>,
        val totalPlayTime: Int
    )

    companion object {
        private const val PREFIX = "stats."
        private fun key(name: String) = PREFIX + name
        private val TIME_LEVELS = listOf("beginner", "intermediate", "expert", "custom")
    }

    var totalGames: Int = 0
        private set
    var gamesWon: Int = 0
        private set
    var currentStreak: Int = 0
        private set
    var bestStreak: Int = 0
        private set
    var totalPlayTime: Int = 0
        private set
    var lastLevel: String? = null
        private set
    val bestTime = mutableMapOf<String, Int?>()

    init {
        load()
    }

    private fun readInt(name: String): Int =
        storage.getString(key(name))?.toIntOrNull() ?: 0

    private fun writeInt(name: String, value: Int) {
        storage.putString(key(name), value.toString())
    }

    private fun readBestTime(level: String): Int? {
        val raw = storage.getString(key("bestTime.$level")) ?: return null
        return raw.toIntOrNull()
    }

    private fun writeBestTime(level: String, value: Int?) {
        storage.putString(key("bestTime.$level"), value?.toString() ?: "")
    }

    fun load() {
        totalGames = readInt("totalGames")
        gamesWon = readInt("gamesWon")
        currentStreak = readInt("currentStreak")
        bestStreak = readInt("bestStreak")
        totalPlayTime = readInt("totalPlayTime")
        lastLevel = storage.getString(key("lastLevel"))
        bestTime.clear()
        for (level in TIME_LEVELS) bestTime[level] = readBestTime(level)
    }

    fun onGameStart(level: String) {
        totalGames++
        lastLevel = level
        writeInt("totalGames", totalGames)
        storage.putString(key("lastLevel"), level)
    }

    fun onGameWin(time: Int) {
        gamesWon++
        currentStreak++
        if (currentStreak > bestStreak) bestStreak = currentStreak
        val level = lastLevel
        if (level != null) {
            val current = bestTime[level] ?: readBestTime(level)
            if (current == null || time < current) {
                bestTime[level] = time
                writeBestTime(level, time)
            }
        }
        totalPlayTime += time
        writeInt("gamesWon", gamesWon)
        writeInt("currentStreak", currentStreak)
        writeInt("bestStreak", bestStreak)
        writeInt("totalPlayTime", totalPlayTime)
    }

    fun onGameLose(time: Int) {
        currentStreak = 0
        totalPlayTime += time
        writeInt("currentStreak", 0)
        writeInt("totalPlayTime", totalPlayTime)
    }

    fun getWinRate(): Int {
        if (totalGames == 0) return 0
        return Math.round(gamesWon.toDouble() / totalGames * 100).toInt()
    }

    fun getSummary(): Summary {
        val times = mutableMapOf<String, Int?>()
        for (level in TIME_LEVELS) {
            times[level] = bestTime[level] ?: readBestTime(level)
        }
        return Summary(
            totalGames = totalGames,
            gamesWon = gamesWon,
            gamesLost = totalGames - gamesWon,
            winRate = getWinRate(),
            currentStreak = currentStreak,
            bestStreak = bestStreak,
            bestTime = times,
            totalPlayTime = totalPlayTime
        )
    }

    fun reset() {
        totalGames = 0
        gamesWon = 0
        currentStreak = 0
        bestStreak = 0
        totalPlayTime = 0
        lastLevel = null
        bestTime.clear()
        writeInt("totalGames", 0)
        writeInt("gamesWon", 0)
        writeInt("currentStreak", 0)
        writeInt("bestStreak", 0)
        writeInt("totalPlayTime", 0)
        storage.putString(key("lastLevel"), "")
        for (level in TIME_LEVELS) writeBestTime(level, null)
    }
}
