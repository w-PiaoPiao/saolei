package com.wpiaopiao.saolei

/**
 * 对局计时状态机（纯逻辑，可 JVM 单测）：
 * - [activeMs]：实际对局时间，覆盖层打开 / 切后台期间不计；
 * - [durationMs]：从开局起的墙钟总时长（active + paused），用于统计与 3BV/s；
 * - [seconds] / [displaySeconds]：秒数（统计不封顶，LED 显示封顶 999 保持 3 位）。
 *
 * 时间源由调用方传入（`SystemClock.elapsedRealtime()`），本类不持有 Android 依赖。
 */
class GameClock {

    private var bankedMs = 0L
    private var pausedMsTotal = 0L
    private var anchorMs = 0L
    private var pauseStartMs = 0L
    private var roundStarted = false

    /** 当前是否在正常计时（覆盖层/后台暂停时为 false）。 */
    var running = false
        private set

    /** 开局（state ready→playing）：清零并开始计时。 */
    fun startRound(nowMs: Long) {
        bankedMs = 0L
        pausedMsTotal = 0L
        anchorMs = nowMs
        pauseStartMs = 0L
        running = true
        roundStarted = true
    }

    /** 暂停（打开覆盖层/切后台）：把本段计时入库。可重复调用。 */
    fun pause(nowMs: Long) {
        if (!running) return
        bankedMs += (nowMs - anchorMs).coerceAtLeast(0L)
        running = false
        pauseStartMs = nowMs
    }

    /** 恢复计时。未开局时忽略。 */
    fun resume(nowMs: Long) {
        if (running || !roundStarted) return
        if (pauseStartMs > 0L) pausedMsTotal += (nowMs - pauseStartMs).coerceAtLeast(0L)
        pauseStartMs = 0L
        anchorMs = nowMs
        running = true
    }

    fun activeMs(nowMs: Long): Long =
        bankedMs + if (running) (nowMs - anchorMs).coerceAtLeast(0L) else 0L

    /** 暂停累计时长（含当前尚未恢复的暂停段）。 */
    fun pausedMs(nowMs: Long): Long =
        pausedMsTotal + if (!running && pauseStartMs > 0L) (nowMs - pauseStartMs).coerceAtLeast(0L) else 0L

    fun durationMs(nowMs: Long): Long = activeMs(nowMs) + pausedMs(nowMs)

    /** 统计用秒数：不封顶、向下取整（与桌面 floor 口径一致）。 */
    fun seconds(nowMs: Long): Int = (activeMs(nowMs) / 1000L).toInt()

    /** LED 显示秒数：封顶 999（3 位）。 */
    fun displaySeconds(nowMs: Long): Int = seconds(nowMs).coerceAtMost(999)

    /**
     * 从存档恢复：只恢复已进行的对局时间，不继续计时（随后的 [resume] 会开始计新时间）。
     * 存档只保存秒级 timer，故毫秒精度会丢失（可接受）。
     */
    fun restoreActive(activeMs: Long) {
        bankedMs = activeMs.coerceAtLeast(0L)
        pausedMsTotal = 0L
        anchorMs = 0L
        pauseStartMs = 0L
        running = false
        roundStarted = true
    }

    fun reset() {
        bankedMs = 0L
        pausedMsTotal = 0L
        anchorMs = 0L
        pauseStartMs = 0L
        running = false
        roundStarted = false
    }
}
