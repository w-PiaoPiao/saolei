package com.wpiaopiao.saolei.core

/**
 * 游戏统计：总局、胜率、连胜、最快时间、总时长、3BV 效率与历史战绩。
 * 由桌面版 src/stats.js 移植。
 *
 * v1.3（M6/M7/M17）起：
 * - 存储改为**单个 JSON key**（[STORAGE_KEY] = `stats.v2`），一次事件只写一次，避免
 *   "总局数 +1 但胜场未 +1"式的多键半写脏数据；首次启动自动迁移旧 `stats.*` 多键存档。
 * - history 按 **bucket** 分桶（预设难度 = level；自定义 = `custom:<rows>x<cols>/<mines>`），
 *   每桶独立保留 [HISTORY_MAX] 条，换难度不再挤掉其它难度的曲线。
 * - 规则：`time <= 0` 且没有 `durationMs` 时不更新最佳时间（0 秒纪录守卫）；`pausedMs > 0`
 *   的局写入 history 但标记 `paused` 且不更新最佳；`wonByFlags` 写入 history 但不算 3BV/s 最佳。
 * - 对外只读 Map（内部可变副本），避免外部绕过持久化直接改内存统计。
 *
 * 线程模型：与桌面版一致，只在 UI 线程使用。
 */
class StatsManager(private val storage: Storage) {

    /**
     * 一局胜利记录（效率曲线数据源）。
     * [level] 为 bucket key（见 [bucketOf]）；[time] 为整秒；[ts] 为毫秒时间戳。
     */
    data class HistoryEntry(
        val level: String,
        val time: Int,
        val bbbv: Int,
        val ts: Long,
        val paused: Boolean = false,
        val wonByFlags: Boolean = false
    )

    data class Summary(
        val totalGames: Int,
        val gamesWon: Int,
        val gamesLost: Int,
        val winRate: Int,
        val currentStreak: Int,
        val bestStreak: Int,
        val bestTime: Map<String, Int?>,
        val bestBbbvPerSec: Map<String, Double?>,
        val totalPlayTime: Int,
        val buckets: List<String> = emptyList()
    )

    companion object {
        /** 单键存储（JSON） */
        const val STORAGE_KEY = "stats.v2"

        /** 旧版多键前缀（只用于迁移读取/清理） */
        const val LEGACY_PREFIX = "stats."

        /** 每个 bucket 的 history 上限（与桌面版 CONFIG.HISTORY_MAX 对齐） */
        const val HISTORY_MAX = 50

        /** getHistory 默认返回条数（与 UI 曲线采样数对齐） */
        const val DEFAULT_HISTORY_LIMIT = 20

        /** 旧存档里所有自定义盘共用的 bucket key */
        const val LEGACY_CUSTOM_BUCKET = "custom"

        /**
         * 计算统计分桶 key：预设难度直接用 level；自定义盘用 `custom:<rows>x<cols>/<mines>`，
         * 避免 9×9 与 50×50 的纪录互相污染。
         */
        fun bucketOf(level: String, custom: GameEngine.CustomConfig? = null): String =
            if (level == "custom" && custom != null) {
                "custom:${custom.rows}x${custom.cols}/${custom.mines}"
            } else {
                level
            }

        private val PRESET_LEVELS: List<String> = GameEngine.LEVELS.keys.toList()

        /** 兼容旧存档的固定 key 集合（预设 + 旧 custom 桶） */
        private fun legacyLevels(): List<String> = PRESET_LEVELS + LEGACY_CUSTOM_BUCKET

        private fun legacyKey(name: String): String = LEGACY_PREFIX + name
    }

    private class Bucket {
        var bestTime: Int? = null
        var bestRate: Double? = null
        val history = ArrayList<HistoryEntry>()
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

    /** 最近一次开局的难度 level（字符串，兼容旧 API；bucket 见 [lastBucket]） */
    var lastLevel: String? = null
        private set

    /** 最近一次开局的统计分桶 key（预设=level；自定义=custom:rowsxcols/mines） */
    var lastBucket: String? = null
        private set

    private val bucketMap = LinkedHashMap<String, Bucket>()

    /** bucket key → 该桶最佳时间（只读快照；预设难度恒有键，无纪录为 null） */
    val bestTime: Map<String, Int?>
        get() = bestTimeSnapshot()

    /** bucket key → 该桶最佳 3BV/s（只读快照；预设难度恒有键，无纪录为 null） */
    val bestBbbvPerSec: Map<String, Double?>
        get() = bestRateSnapshot()

    init {
        load()
    }

    // ---------- 只读视图 ----------

    private fun bestTimeSnapshot(): Map<String, Int?> {
        val out = LinkedHashMap<String, Int?>()
        for (level in PRESET_LEVELS) out[level] = bucketMap[level]?.bestTime
        out[LEGACY_CUSTOM_BUCKET] = bucketMap[LEGACY_CUSTOM_BUCKET]?.bestTime
        for (key in bucketMap.keys.sorted()) {
            if (!out.containsKey(key)) out[key] = bucketMap[key]?.bestTime
        }
        return out
    }

    private fun bestRateSnapshot(): Map<String, Double?> {
        val out = LinkedHashMap<String, Double?>()
        for (level in PRESET_LEVELS) out[level] = bucketMap[level]?.bestRate
        out[LEGACY_CUSTOM_BUCKET] = bucketMap[LEGACY_CUSTOM_BUCKET]?.bestRate
        for (key in bucketMap.keys.sorted()) {
            if (!out.containsKey(key)) out[key] = bucketMap[key]?.bestRate
        }
        return out
    }

    // ---------- 查询 ----------

    /** 某 bucket 的当前最佳时间；null 表示尚无纪录。 */
    fun bestTimeOf(bucketKey: String): Int? = bucketMap[bucketKey]?.bestTime

    /** 所有出现过的 bucket key（排序后返回）。 */
    fun getBuckets(): List<String> = bucketMap.keys.sorted()

    fun getWinRate(): Int {
        if (totalGames == 0) return 0
        return Math.round(gamesWon.toDouble() / totalGames * 100).toInt()
    }

    fun getSummary(): Summary = Summary(
        totalGames = totalGames,
        gamesWon = gamesWon,
        gamesLost = totalGames - gamesWon,
        winRate = getWinRate(),
        currentStreak = currentStreak,
        bestStreak = bestStreak,
        bestTime = bestTimeSnapshot(),
        bestBbbvPerSec = bestRateSnapshot(),
        totalPlayTime = totalPlayTime,
        buckets = getBuckets()
    )

    /**
     * 某 bucket 最近的胜利记录（时间升序，供效率曲线绘制）。
     * @param bucketKey 见 [bucketOf]；旧调用可传 "beginner"/"custom"
     */
    fun getHistory(bucketKey: String, limit: Int = DEFAULT_HISTORY_LIMIT): List<HistoryEntry> {
        if (limit <= 0) return emptyList()
        val h = bucketMap[bucketKey]?.history ?: return emptyList()
        val start = maxOf(0, h.size - limit)
        return h.subList(start, h.size).toList()
    }

    // ---------- 事件 ----------

    /**
     * 开局计数。
     * @param level 难度 level；@param custom 自定义配置（仅 level=="custom" 时用于分桶）
     */
    fun onGameStart(level: String, custom: GameEngine.CustomConfig? = null) {
        totalGames++
        lastLevel = level
        lastBucket = bucketOf(level, custom)
        bucketMap.getOrPut(lastBucket!!) { Bucket() }
        save()
    }

    /**
     * 只设置"当前对局所属统计桶"，不增加总局数、不改任何计数、不写存储。
     *
     * 使用场景：进程被系统回收后，app 用 [GameEngine.Snapshot] 恢复一局（该局开局时已经
     * onGameStart 计过总局数，不能重复计数），但新进程里的 [lastBucket] 可能为 null 或
     * 与恢复的对局不一致；恢复后调用本方法把桶对齐，终局记账才会落到正确的 bucket。
     */
    fun selectBucket(level: String, custom: GameEngine.CustomConfig? = null) {
        lastLevel = level
        lastBucket = bucketOf(level, custom)
    }

    /**
     * 胜利记账。
     * @param time 整秒耗时（LED 显示口径）
     * @param bbbv 本局 3BV
     * @param durationMs 毫秒耗时（-1 表示调用方没有更精确的值；>=0 时参与 3BV/s 计算与 0 秒判定）
     * @param pausedMs 本局暂停时长（>0 时标记 history 为 paused 且不更新最佳）
     * @param wonByFlags 是否靠"插对全部旗"取胜（不计 3BV/s 最佳）
     */
    fun onGameWin(
        time: Int,
        bbbv: Int = 0,
        durationMs: Long = -1L,
        pausedMs: Long = 0L,
        wonByFlags: Boolean = false
    ) {
        gamesWon++
        currentStreak++
        if (currentStreak > bestStreak) bestStreak = currentStreak

        val bucketKey = lastBucket
        val hasTiming = time > 0 || durationMs > 0
        val seconds = if (time > 0) time else 0
        if (bucketKey != null) {
            val bucket = bucketMap.getOrPut(bucketKey) { Bucket() }
            // 最快时间：需要有真实计时、且没有暂停（暂停局可比性差）
            if (hasTiming && pausedMs <= 0L) {
                val current = bucket.bestTime
                if (current == null || time < current) bucket.bestTime = time
            }
            // 3BV/s 最佳：需要真实计时、有效 3BV、未暂停、且不是靠插旗取胜
            if (hasTiming && pausedMs <= 0L && bbbv > 0 && !wonByFlags) {
                val rate = if (durationMs > 0) {
                    bbbv * 1000.0 / durationMs
                } else if (time > 0) {
                    bbbv.toDouble() / time
                } else {
                    0.0
                }
                if (rate > 0.0) {
                    val current = bucket.bestRate
                    if (current == null || rate > current) bucket.bestRate = rate
                }
            }
            if (hasTiming) {
                bucket.history.add(
                    HistoryEntry(
                        level = bucketKey,
                        time = seconds,
                        bbbv = bbbv,
                        ts = System.currentTimeMillis(),
                        paused = pausedMs > 0L,
                        wonByFlags = wonByFlags
                    )
                )
                while (bucket.history.size > HISTORY_MAX) bucket.history.removeAt(0)
            }
        }
        totalPlayTime += playedSeconds(time, durationMs)
        save()
    }

    /** 失败记账：清空连胜、累计时长（[pausedMs] 保留参数以对齐桌面/未来口径，当前不计入）。 */
    @Suppress("UNUSED_PARAMETER")
    fun onGameLose(time: Int, durationMs: Long = -1L, pausedMs: Long = 0L) {
        currentStreak = 0
        totalPlayTime += playedSeconds(time, durationMs)
        save()
    }

    private fun playedSeconds(time: Int, durationMs: Long): Int = when {
        time > 0 -> time
        durationMs > 0 -> ((durationMs + 999) / 1000).toInt()
        else -> 0
    }

    // ---------- 持久化 ----------

    fun load() {
        resetMemory()
        val raw = runCatching { storage.getString(STORAGE_KEY) }.getOrNull()
        if (raw != null) {
            if (decode(raw)) return
            // v2 存档损坏：退回旧格式/默认值（下一次 save 会用合法 JSON 覆盖）
        }
        if (loadLegacy()) {
            clearLegacyKeys()
            save()
        }
    }

    fun save() {
        runCatching { storage.putString(STORAGE_KEY, encode()) }
    }

    fun reset() {
        resetMemory()
        runCatching {
            storage.remove(STORAGE_KEY)
            clearLegacyKeysUnsafe()
        }
    }

    private fun resetMemory() {
        totalGames = 0
        gamesWon = 0
        currentStreak = 0
        bestStreak = 0
        totalPlayTime = 0
        lastLevel = null
        lastBucket = null
        bucketMap.clear()
    }

    // ---------- 旧存档迁移 ----------

    private fun readLegacyInt(name: String): Int =
        storage.getString(legacyKey(name))?.toIntOrNull() ?: 0

    private fun readLegacyIntOrNull(name: String): Int? =
        storage.getString(legacyKey(name))?.toIntOrNull()

    private fun readLegacyDoubleOrNull(name: String): Double? =
        storage.getString(legacyKey(name))?.toDoubleOrNull()

    /** 读取旧版 `stats.*` 多键存档；没有任何旧键时返回 false。 */
    private fun loadLegacy(): Boolean {
        val marker = storage.getString(legacyKey("totalGames"))
        val lastLevelRaw = storage.getString(legacyKey("lastLevel"))
        val historyRaw = storage.getString(legacyKey("history"))
        val hasAnyBest = legacyLevels().any {
            storage.getString(legacyKey("bestTime.$it")) != null ||
                storage.getString(legacyKey("bestBbbvPerSec.$it")) != null
        }
        if (marker == null && lastLevelRaw == null && historyRaw == null && !hasAnyBest) return false

        totalGames = readLegacyInt("totalGames").coerceAtLeast(0)
        gamesWon = readLegacyInt("gamesWon").coerceAtLeast(0)
        currentStreak = readLegacyInt("currentStreak").coerceAtLeast(0)
        bestStreak = readLegacyInt("bestStreak").coerceAtLeast(0)
        totalPlayTime = readLegacyInt("totalPlayTime").coerceAtLeast(0)
        lastLevel = lastLevelRaw?.takeIf { it.isNotBlank() }
        lastBucket = lastLevel
        for (level in legacyLevels()) {
            val best = readLegacyIntOrNull("bestTime.$level")
            val rate = readLegacyDoubleOrNull("bestBbbvPerSec.$level")
            if (best != null || rate != null) {
                val bucket = bucketMap.getOrPut(level) { Bucket() }
                bucket.bestTime = best
                bucket.bestRate = rate
            }
        }
        if (historyRaw != null) {
            for (part in historyRaw.split(";")) {
                if (part.isBlank()) continue
                val f = part.split("|")
                if (f.size != 4) continue
                val time = f[1].toIntOrNull() ?: continue
                val bbbv = f[2].toIntOrNull() ?: continue
                val ts = f[3].toLongOrNull() ?: continue
                val level = f[0]
                if (level.isBlank()) continue
                val bucket = bucketMap.getOrPut(level) { Bucket() }
                bucket.history.add(HistoryEntry(level, time, bbbv, ts))
                while (bucket.history.size > HISTORY_MAX) bucket.history.removeAt(0)
            }
        }
        return true
    }

    private fun clearLegacyKeys() {
        runCatching { clearLegacyKeysUnsafe() }
    }

    private fun clearLegacyKeysUnsafe() {
        for (name in listOf("totalGames", "gamesWon", "currentStreak", "bestStreak", "totalPlayTime", "lastLevel", "history")) {
            storage.remove(legacyKey(name))
        }
        for (level in legacyLevels()) {
            storage.remove(legacyKey("bestTime.$level"))
            storage.remove(legacyKey("bestBbbvPerSec.$level"))
        }
    }

    // ---------- JSON 编解码（core 不引入 JSON 依赖，自带最小实现） ----------

    private fun encode(): String {
        val root = LinkedHashMap<String, Any?>()
        root["version"] = 2
        root["totalGames"] = totalGames
        root["gamesWon"] = gamesWon
        root["currentStreak"] = currentStreak
        root["bestStreak"] = bestStreak
        root["totalPlayTime"] = totalPlayTime
        root["lastLevel"] = lastLevel
        root["lastBucket"] = lastBucket
        val buckets = LinkedHashMap<String, Any?>()
        for ((key, bucket) in bucketMap) {
            val obj = LinkedHashMap<String, Any?>()
            obj["bestTime"] = bucket.bestTime
            obj["bestRate"] = bucket.bestRate
            val history = ArrayList<Any?>(bucket.history.size)
            for (entry in bucket.history) {
                val e = LinkedHashMap<String, Any?>()
                e["level"] = entry.level
                e["time"] = entry.time
                e["bbbv"] = entry.bbbv
                e["ts"] = entry.ts
                e["paused"] = entry.paused
                e["wonByFlags"] = entry.wonByFlags
                history.add(e)
            }
            obj["history"] = history
            buckets[key] = obj
        }
        root["buckets"] = buckets
        return StatsJson.write(root)
    }

    /** 解析 v2 JSON；失败返回 false（不抛异常，由 load 退回旧存档/默认值）。 */
    private fun decode(raw: String): Boolean {
        return try {
            val root = StatsJson.read(raw) as? Map<*, *> ?: return false
            val newBuckets = LinkedHashMap<String, Bucket>()
            val bs = root["buckets"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
            for ((rawKey, rawBucket) in bs) {
                val key = rawKey as? String ?: continue
                val obj = rawBucket as? Map<*, *> ?: continue
                val bucket = Bucket()
                bucket.bestTime = StatsJson.asInt(obj["bestTime"])
                bucket.bestRate = StatsJson.asDouble(obj["bestRate"])
                val hist = obj["history"] as? List<*> ?: emptyList<Any?>()
                for (rawEntry in hist) {
                    val e = rawEntry as? Map<*, *> ?: continue
                    val level = e["level"] as? String ?: continue
                    val time = StatsJson.asInt(e["time"]) ?: continue
                    val bbbv = StatsJson.asInt(e["bbbv"]) ?: 0
                    val ts = StatsJson.asLong(e["ts"]) ?: 0L
                    val paused = e["paused"] as? Boolean ?: false
                    val wonByFlags = e["wonByFlags"] as? Boolean ?: false
                    bucket.history.add(HistoryEntry(level, time, bbbv, ts, paused, wonByFlags))
                }
                while (bucket.history.size > HISTORY_MAX) bucket.history.removeAt(0)
                newBuckets[key] = bucket
            }
            resetMemory()
            totalGames = (StatsJson.asInt(root["totalGames"]) ?: 0).coerceAtLeast(0)
            gamesWon = (StatsJson.asInt(root["gamesWon"]) ?: 0).coerceAtLeast(0)
            currentStreak = (StatsJson.asInt(root["currentStreak"]) ?: 0).coerceAtLeast(0)
            bestStreak = (StatsJson.asInt(root["bestStreak"]) ?: 0).coerceAtLeast(0)
            totalPlayTime = (StatsJson.asInt(root["totalPlayTime"]) ?: 0).coerceAtLeast(0)
            lastLevel = (root["lastLevel"] as? String)?.takeIf { it.isNotBlank() }
            lastBucket = (root["lastBucket"] as? String)?.takeIf { it.isNotBlank() }
            if (lastBucket == null) lastBucket = lastLevel
            bucketMap.putAll(newBuckets)
            true
        } catch (e: Exception) {
            false
        }
    }
}

/** 最小 JSON 读写实现（只覆盖本文件用到的对象/数组/字符串/数字/布尔/null）。 */
private object StatsJson {

    fun write(value: Any?): String {
        val sb = StringBuilder(256)
        writeValue(sb, value)
        return sb.toString()
    }

    private fun writeValue(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(sb, value)
            is Boolean -> sb.append(if (value) "true" else "false")
            is Int, is Long -> sb.append(value.toString())
            is Double -> if (value.isFinite()) sb.append(value.toString()) else sb.append("null")
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString())
                    sb.append(':')
                    writeValue(sb, v)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (v in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeValue(sb, v)
                }
                sb.append(']')
            }
            else -> writeString(sb, value.toString())
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append("\\u").append(ch.code.toString(16).padStart(4, '0')) else sb.append(ch)
            }
        }
        sb.append('"')
    }

    fun read(text: String): Any? = Parser(text).parseDocument()

    fun asInt(value: Any?): Int? = when (value) {
        is Double -> value.toInt()
        is Long -> value.toInt()
        is Int -> value
        else -> null
    }

    fun asLong(value: Any?): Long? = when (value) {
        is Double -> value.toLong()
        is Long -> value
        is Int -> value.toLong()
        else -> null
    }

    fun asDouble(value: Any?): Double? = when (value) {
        is Double -> value
        is Long -> value.toDouble()
        is Int -> value.toDouble()
        else -> null
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun parseDocument(): Any? {
            val value = parseValue()
            skipWs()
            if (i != s.length) throw IllegalArgumentException("JSON: 尾部有多余字符")
            return value
        }

        private fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun parseValue(): Any? {
            skipWs()
            if (i >= s.length) throw IllegalArgumentException("JSON: 意外结尾")
            return when (s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                else -> parseNumber()
            }
        }

        private fun parseLiteral(literal: String, value: Any?): Any? {
            if (!s.startsWith(literal, i)) throw IllegalArgumentException("JSON: 非法字面量")
            i += literal.length
            return value
        }

        private fun parseObject(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++ // {
            skipWs()
            if (i < s.length && s[i] == '}') {
                i++
                return out
            }
            while (true) {
                skipWs()
                if (i >= s.length || s[i] != '"') throw IllegalArgumentException("JSON: 对象键不是字符串")
                val key = parseString()
                skipWs()
                if (i >= s.length || s[i] != ':') throw IllegalArgumentException("JSON: 缺少冒号")
                i++
                out[key] = parseValue()
                skipWs()
                if (i >= s.length) throw IllegalArgumentException("JSON: 对象未闭合")
                when (s[i]) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return out
                    }
                    else -> throw IllegalArgumentException("JSON: 对象分隔符非法")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            val out = ArrayList<Any?>()
            i++ // [
            skipWs()
            if (i < s.length && s[i] == ']') {
                i++
                return out
            }
            while (true) {
                out.add(parseValue())
                skipWs()
                if (i >= s.length) throw IllegalArgumentException("JSON: 数组未闭合")
                when (s[i]) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return out
                    }
                    else -> throw IllegalArgumentException("JSON: 数组分隔符非法")
                }
            }
        }

        private fun parseString(): String {
            i++ // "
            val sb = StringBuilder()
            while (i < s.length) {
                val ch = s[i]
                when {
                    ch == '"' -> {
                        i++
                        return sb.toString()
                    }
                    ch == '\\' -> {
                        i++
                        if (i >= s.length) throw IllegalArgumentException("JSON: 转义不完整")
                        when (val esc = s[i]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 >= s.length) throw IllegalArgumentException("JSON: \\u 转义不完整")
                                val hex = s.substring(i + 1, i + 5)
                                sb.append(hex.toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("JSON: 非法转义 \\$esc")
                        }
                        i++
                    }
                    else -> {
                        sb.append(ch)
                        i++
                    }
                }
            }
            throw IllegalArgumentException("JSON: 字符串未闭合")
        }

        private fun parseNumber(): Double {
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '-' || s[i] == '+')) i++
            if (i == start) throw IllegalArgumentException("JSON: 非法数字")
            val text = s.substring(start, i)
            return text.toDoubleOrNull() ?: throw IllegalArgumentException("JSON: 非法数字 $text")
        }
    }
}
