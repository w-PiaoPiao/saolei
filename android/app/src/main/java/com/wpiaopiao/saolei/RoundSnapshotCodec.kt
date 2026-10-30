package com.wpiaopiao.saolei

import com.wpiaopiao.saolei.core.GameEngine
import java.util.Base64

/**
 * 对局快照的紧凑文本编解码（M19/AA-5）。
 *
 * 用途：`onSaveInstanceState` 的 Bundle 与 onPause 落盘的 prefs 都只放一个字符串；
 * 进程被回收 / 字体·语言·键盘等配置变化后，启动时可恢复进行中的对局（不重跑首点布雷）。
 *
 * 格式：`v1|rows|cols|totalMines|currentLevel|custom|-|state|minesPlaced|timer|flagCount|
 * revealedCount|bbbv|noGuessFallback|wonByFlags|<b64 mines>|<b64 revealed>|<b64 flagged>|
 * <b64 questioned>|<b64 exploded>|<b64 wrongFlag>`，其中 custom 为 `r,c,m` 或 `-`。
 */
object RoundSnapshotCodec {

    private const val VERSION = "v1"
    private const val SEP = '|'
    private const val NONE = "-"
    private const val PART_COUNT = 20

    fun encode(snapshot: GameEngine.Snapshot): String {
        val custom = snapshot.currentCustom?.let { "${it.rows},${it.cols},${it.mines}" } ?: NONE
        return buildString(256) {
            append(VERSION).append(SEP)
            append(snapshot.rows).append(SEP)
            append(snapshot.cols).append(SEP)
            append(snapshot.totalMines).append(SEP)
            append(snapshot.currentLevel).append(SEP)
            append(custom).append(SEP)
            append(snapshot.state).append(SEP)
            append(if (snapshot.minesPlaced) 1 else 0).append(SEP)
            append(snapshot.timer).append(SEP)
            append(snapshot.flagCount).append(SEP)
            append(snapshot.revealedCount).append(SEP)
            append(snapshot.bbbv).append(SEP)
            append(if (snapshot.noGuessFallback) 1 else 0).append(SEP)
            append(if (snapshot.wonByFlags) 1 else 0).append(SEP)
            append(encodeBooleans(snapshot.mines)).append(SEP)
            append(encodeBooleans(snapshot.revealed)).append(SEP)
            append(encodeBooleans(snapshot.flagged)).append(SEP)
            append(encodeBooleans(snapshot.questioned)).append(SEP)
            append(encodeBooleans(snapshot.exploded)).append(SEP)
            append(encodeBooleans(snapshot.wrongFlag))
        }
    }

    fun decode(encoded: String?): GameEngine.Snapshot? = try {
        if (encoded.isNullOrEmpty()) {
            null
        } else {
            val parts = encoded.split(SEP)
            require(parts.size == PART_COUNT) { "快照字段数不符: ${parts.size}" }
            require(parts[0] == VERSION) { "未知快照版本: ${parts[0]}" }
            val rows = parts[1].toInt()
            val cols = parts[2].toInt()
            val totalMines = parts[3].toInt()
            val level = parts[4]
            val custom = if (parts[5] == NONE) {
                null
            } else {
                val c = parts[5].split(',')
                require(c.size == 3)
                GameEngine.CustomConfig(c[0].toInt(), c[1].toInt(), c[2].toInt())
            }
            val size = rows * cols
            require(rows > 0 && cols > 0 && size > 0) { "非法棋盘尺寸" }
            GameEngine.Snapshot(
                rows = rows,
                cols = cols,
                totalMines = totalMines,
                currentLevel = level,
                currentCustom = custom,
                state = parts[6],
                minesPlaced = parts[7] == "1",
                timer = parts[8].toInt(),
                flagCount = parts[9].toInt(),
                revealedCount = parts[10].toInt(),
                bbbv = parts[11].toInt(),
                noGuessFallback = parts[12] == "1",
                wonByFlags = parts[13] == "1",
                mines = decodeBooleans(parts[14], size),
                revealed = decodeBooleans(parts[15], size),
                flagged = decodeBooleans(parts[16], size),
                questioned = decodeBooleans(parts[17], size),
                exploded = decodeBooleans(parts[18], size),
                wrongFlag = decodeBooleans(parts[19], size),
            )
        }
    } catch (e: Exception) {
        // 存档损坏/版本不兼容：丢弃并开新局，绝不能因存档崩溃
        null
    }

    private fun encodeBooleans(values: BooleanArray): String {
        val bytes = ByteArray(values.size)
        for (i in values.indices) bytes[i] = if (values[i]) 1 else 0
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun decodeBooleans(encoded: String, expectedSize: Int): BooleanArray {
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size == expectedSize) { "数组长度不符: ${bytes.size} != $expectedSize" }
        return BooleanArray(bytes.size) { bytes[it].toInt() != 0 }
    }
}
