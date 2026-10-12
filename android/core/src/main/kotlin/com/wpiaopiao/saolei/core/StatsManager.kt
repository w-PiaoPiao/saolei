package com.wpiaopiao.saolei.core

/**
 * 游戏统计：总局、胜率、连胜、最快时间、总时长、3BV 效率与历史战绩。
 * 由桌面版 src/stats.js 移植（localStorage JSON 换成 Storage 的按字段键值存储）。
 */
class StatsManager(private val storage: Storage) {

    /** 一局胜利记录（效率曲线数据源） */
    data class HistoryEntry(val level: String, val time: Int, val bbbv: Int, val ts: Long)

    data class Summary(
        val totalGames: Int,
        val gamesWon: Int,
        val gamesLost: Int,
        val winRate: Int,
        val currentStreak: Int,
        val bestStreak: Int,
        val bestTime: Map<String, Int?>,
        val bestBbbvPerSec: Map<String, Double?>,
        val totalPlayTime: Int
    )

    companion object {
        private const val PREFIX = "stats."
        private fun key(name: String) = PREFIX + name
        private val TIME_LEVELS = listOf("beginner", "intermediate", "expert", "custom")

        /** 历史战绩上限（与桌面版 CONFIG.HISTORY_MAX 对齐） */
        const val HISTORY_MAX = 50
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

    /** 各难度最佳 3BV/s */
    val bestBbbvPerSec = mutableMapOf<String, Double?>()
    private val history = mutableListOf<HistoryEntry>()

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
        if (value == null) storage.remove(key("bestTime.$level"))
        else storage.putString(key("bestTime.$level"), value.toString())
    }

    private fun readBestRate(level: String): Double? =
        storage.getString(key("bestBbbvPerSec.$level"))?.toDoubleOrNull()

    private fun writeBestRate(level: String, value: Double?) {
        if (value == null) storage.remove(key("bestBbbvPerSec.$level"))
        else storage.putString(key("bestBbbvPerSec.$level"), value.toString())
    }

    /** 历史记录编码：`level|time|bbbv|ts` 以 `;` 分隔（core 不引入 JSON 库） */
    private fun loadHistory() {
        history.clear()
        val raw = storage.getString(key("history")) ?: return
        for (part in raw.split(";")) {
            if (part.isBlank()) continue
            val f = part.split("|")
            if (f.size != 4) continue
            val time = f[1].toIntOrNull() ?: continue
            val bbbv = f[2].toIntOrNull() ?: continue
            val ts = f[3].toLongOrNull() ?: continue
            history.add(HistoryEntry(f[0], time, bbbv, ts))
        }
    }

    private fun saveHistory() {
        storage.putString(
            key("history"),
            history.joinToString(";") { "${it.level}|${it.time}|${it.bbbv}|${it.ts}" }
        )
    }

    fun load() {
        totalGames = readInt("totalGames")
        gamesWon = readInt("gamesWon")
        currentStreak = readInt("currentStreak")
        bestStreak = readInt("bestStreak")
        totalPlayTime = readInt("totalPlayTime")
        // 空串不是合法难度名（旧版 reset 曾写入空串当哨兵）：归一为 null，
        // 否则会被当成"有值但不存在"的难度而误报新纪录
        lastLevel = storage.getString(key("lastLevel"))?.takeIf { it.isNotBlank() }
        bestTime.clear()
        for (level in TIME_LEVELS) bestTime[level] = readBestTime(level)
        bestBbbvPerSec.clear()
        for (level in TIME_LEVELS) bestBbbvPerSec[level] = readBestRate(level)
        loadHistory()
    }

    fun onGameStart(level: String) {
        totalGames++
        lastLevel = level
        writeInt("totalGames", totalGames)
        storage.putString(key("lastLevel"), level)
    }

    fun onGameWin(time: Int, bbbv: Int = 0) {
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
        // 3BV 效率：刷新该难度最佳 3BV/s，并把本局写入历史（曲线数据源）
        if (level != null && bbbv > 0 && time > 0) {
            val rate = bbbv.toDouble() / time
            val currentRate = bestBbbvPerSec[level] ?: readBestRate(level)
            if (currentRate == null || rate > currentRate) {
                bestBbbvPerSec[level] = rate
                writeBestRate(level, rate)
            }
            history.add(HistoryEntry(level, time, bbbv, System.currentTimeMillis()))
            while (history.size > HISTORY_MAX) history.removeAt(0)
            saveHistory()
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
        val rates = mutableMapOf<String, Double?>()
        for (level in TIME_LEVELS) {
            rates[level] = bestBbbvPerSec[level] ?: readBestRate(level)
        }
        return Summary(
            totalGames = totalGames,
            gamesWon = gamesWon,
            gamesLost = totalGames - gamesWon,
            winRate = getWinRate(),
            currentStreak = currentStreak,
            bestStreak = bestStreak,
            bestTime = times,
            bestBbbvPerSec = rates,
            totalPlayTime = totalPlayTime
        )
    }

    /** 某难度最近的胜利记录（时间升序，供效率曲线绘制） */
    fun getHistory(level: String, limit: Int = 20): List<HistoryEntry> =
        history.filter { it.level == level }.takeLast(limit.coerceAtLeast(0))

    fun reset() {
        totalGames = 0
        gamesWon = 0
        currentStreak = 0
        bestStreak = 0
        totalPlayTime = 0
        lastLevel = null
        bestTime.clear()
        bestBbbvPerSec.clear()
        history.clear()
        writeInt("totalGames", 0)
        writeInt("gamesWon", 0)
        writeInt("currentStreak", 0)
        writeInt("bestStreak", 0)
        writeInt("totalPlayTime", 0)
        storage.remove(key("lastLevel"))
        for (level in TIME_LEVELS) {
            writeBestTime(level, null)
            writeBestRate(level, null)
            bestTime[level] = null
            bestBbbvPerSec[level] = null
        }
        saveHistory()
    }
}
