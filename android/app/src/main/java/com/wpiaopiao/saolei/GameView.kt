package com.wpiaopiao.saolei

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.wpiaopiao.saolei.core.GameEngine
import com.wpiaopiao.saolei.core.KeyBindings
import com.wpiaopiao.saolei.core.KeyCodes
import com.wpiaopiao.saolei.core.KeyNames
import com.wpiaopiao.saolei.core.Movement
import com.wpiaopiao.saolei.core.Storage
import com.wpiaopiao.saolei.core.StatsManager
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * 游戏主视图：960×640 掌机屏幕上的全部绘制（HUD/棋盘/底部栏/覆盖层对话框）
 * 与手柄/触摸交互。视觉与交互对齐桌面版（WinXP 风格、6 主题、光标、动画）。
 */
class GameView(
    context: Context,
    private val bindings: KeyBindings,
    private val stats: StatsManager,
    private val sound: SoundManager,
    private val storage: Storage,
) : View(context) {

    // ---------- 主题（颜色对齐桌面版 style.css 的 6 套主题） ----------

    data class ThemeColors(
        val bgPage: Int, val bgPanel: Int, val bgCell: Int, val bgRevealed: Int,
        val bgActive: Int, val text: Int, val led: Int,
        val borderLight: Int, val borderDark: Int, val borderRevealed: Int,
        val overlay: Int, val dialogBg: Int, val inputBg: Int,
    )

    data class Theme(val value: String, val label: String, val colors: ThemeColors)

    private fun hex(h: String): Int = Color.parseColor(h)

    private fun rgba(a: Int, rgb: String): Int = (a shl 24) or (Color.parseColor(rgb) and 0xFFFFFF)

    private fun mkTheme(
        value: String, label: String,
        page: String, panel: String, cell: String, revealed: String, active: String,
        text: String, led: String, bLight: String, bDark: String, bRevealed: String,
        overlayA: Int, dialog: String, input: String
    ) = Theme(
        value, label,
        ThemeColors(
            hex(page), hex(panel), hex(cell), hex(revealed), hex(active),
            hex(text), hex(led), hex(bLight), hex(bDark), hex(bRevealed),
            rgba(overlayA, "#000000"), hex(dialog), hex(input)
        )
    )

    private val themes = listOf(
        mkTheme("light", "浅色", "#008080", "#C0C0C0", "#C0C0C0", "#D0D0D0", "#A0A0A0",
            "#333333", "#FF0000", "#FFFFFF", "#808080", "#B0B0B0", 0x80, "#C0C0C0", "#FFFFFF"),
        mkTheme("dark", "深色", "#1A1A2E", "#2D2D2D", "#3A3A3A", "#505050", "#555555",
            "#CCCCCC", "#00FF00", "#555555", "#222222", "#444444", 0xB3, "#2D2D2D", "#444444"),
        mkTheme("xp-silver", "WinXP 银灰", "#ECE9D8", "#ECE9D8", "#ECE9D8", "#F5F3ED", "#D4D0C8",
            "#222222", "#CC0000", "#FFFFFF", "#ACA899", "#D4D0C8", 0x80, "#ECE9D8", "#FFFFFF"),
        mkTheme("vista", "Vista 蓝", "#005A8C", "#E8EFF7", "#D9E5F2", "#F0F5FB", "#C5D9EB",
            "#1E3B5C", "#CC0000", "#FFFFFF", "#8BA0B8", "#B8CCE0", 0x80, "#E8EFF7", "#FFFFFF"),
        mkTheme("macos", "macOS", "#C6C6C8", "#E8E8ED", "#D1D1D6", "#F2F2F7", "#B8B8C0",
            "#1C1C1E", "#FF3B30", "#FFFFFF", "#8E8E93", "#C7C7CC", 0x80, "#E8E8ED", "#FFFFFF"),
        mkTheme("macos-dark", "macOS 深色", "#1C1C1E", "#2C2C2E", "#3A3A3C", "#48484A", "#555557",
            "#F2F2F7", "#FF453A", "#636366", "#1C1C1E", "#48484A", 0xB3, "#2C2C2E", "#3A3A3C")
    )

    // ---------- 常量 ----------

    companion object {
        private val NUMBER_COLORS = listOf(
            0x00000000,
            0xFF0000FF.toInt(), 0xFF008000.toInt(), 0xFFFF0000.toInt(),
            0xFF000080.toInt(), 0xFF800000.toInt(), 0xFF008080.toInt(),
            0xFF000000.toInt(), 0xFF808080.toInt()
        )
        private val MOVE_ACTIONS = setOf("moveUp", "moveDown", "moveLeft", "moveRight")
        private const val REPEAT_DELAY_MS = 500L   // 对齐桌面版 CONFIG.KEYBOARD
        private const val REPEAT_INTERVAL_MS = 40L
        private const val LONG_PRESS_MS = 600L
        private const val HUD_H = 64f
        private const val BOTTOM_H = 44f
        private const val PAD = 10f
        private const val MIN_CELL = 16f
        private const val MAX_CELL = 48f
        // 和弦失败 shake：时长 350ms，双轴错位抖动（仅周围未翻开的格子）
        private const val SHAKE_MS = 350L
        // 翻开动画：缩放回弹时长；超过后从动画集合移除
        private const val REVEAL_ANIM_MS = 150L
        private const val REVEAL_ANIM_SCALE_MS = 120f
        // 新纪录横幅：显示总时长与末尾淡出时长
        private const val RECORD_BANNER_MS = 2200L
        private const val RECORD_BANNER_FADE_MS = 500L
        // 棋盘位图缓存四周留白：抖动最大位移 7px，留 10px 保证溢出落在缓存内
        private const val CACHE_PAD = 10
        private const val CONFIRM_DIALOG_H = 150f
        // 效率曲线：难度顺序与取样局数
        private val HISTORY_LEVELS = listOf("beginner", "intermediate", "expert", "custom")
        private const val HISTORY_SAMPLE = 20
        // 底部难度按钮：标签与对应难度（null = 自定义）
        private val DIFF_BUTTON_LABELS = listOf("初级", "中级", "高级", "自定义")
        private val DIFF_BUTTON_LEVELS = listOf<String?>("beginner", "intermediate", "expert", null)

        // 等宽粗体 Typeface 全局缓存：draw 路径禁止每帧 Typeface.create
        private val MONO_BOLD: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private enum class Overlay { None, Menu, Custom, Bindings, Stats, About, ConfirmNewGame, History }
    private enum class BindRowKind { ACTION, RESET, CLOSE }

    private data class BindRow(val kind: BindRowKind, val action: String? = null, val label: String = "")
    private data class MenuItem(val text: String, val checked: Boolean = false, val action: () -> Unit)
    private data class Metrics(val cell: Float, val x: Float, val y: Float, val w: Float, val h: Float)

    // ---------- 状态 ----------

    private val engine = GameEngine()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // 绘制热路径复用对象：避免每帧每格分配（50×50 自定义局每帧可达数千次临时对象）
    private val tmpRect = RectF()
    private val tmpRect2 = RectF()
    private val tmpRect3 = RectF()
    private val tmpPath = Path()

    // 棋盘布局缓存：尺寸/难度不变时直接复用
    private var metricsCache: Metrics? = null
    private var metricsW = 0
    private var metricsH = 0
    private var metricsRows = 0
    private var metricsCols = 0

    private var theme = themes[0]
    private var muted = false
    private var cursor = 0 to 0
    private var cursorVisible = false
    private var facePressed = false
    private var appActive = true
    private var timerRunning = false
    private var prevState = "ready"

    private val heldMoves = mutableSetOf<String>()
    // 以 r*cols+c 整数为键，避免每帧每格分配 Pair 装箱对象
    private val revealedAnim = mutableMapOf<Int, Long>()
    private var shakeStart = 0L
    private val shakeCells = mutableSetOf<Int>()

    private var overlay = Overlay.None
    private var menuSelected = 0
    private var customRows = 16
    private var customCols = 16
    private var customMines = 40
    private var customField = 0
    private var customError = ""
    private var bindSelected = 0
    private var bindListening: String? = null
    private var bindError = ""
    private var lastKeyInfo = "—"

    // 重开确认：待执行的重开动作 + 按钮选择（0=重开 1=取消，默认停在取消防误触）
    private var pendingRestart: (() -> Unit)? = null
    private var confirmSelected = 1
    private var touchConfirmButton: Int? = null

    // 运行时读到的应用版本号（About 显示，避免硬编码）
    private var appVersion = ""

    // 胜利"新纪录"横幅
    private var recordBanner = ""
    private var recordBannerStart = 0L

    // 效率曲线：当前查看的难度下标（HISTORY_LEVELS 顺序）
    private var historyLevelIndex = 0

    // 底部难度按钮：矩形布局复用（绘制与触摸命中共用），touchDiffButton 为触摸按下态
    private val diffBtnRects = Array(4) { RectF() }
    private var touchDiffButton: Int? = null

    // 触摸按下已翻开数字格时的邻居预览（和弦示意，与桌面版按下高亮等价）
    private var touchPreviewCell: Int? = null

    // 大棋盘视口滚动偏移（像素，0 = 不滚动）；触摸拖动可浏览
    private var viewOffsetX = 0
    private var viewOffsetY = 0
    private var draggingBoard = false
    private var dragOffsetX0 = 0
    private var dragOffsetY0 = 0

    // 棋盘静态层位图缓存：非动画帧只贴图 + 重绘动态格
    private var boardCache: Bitmap? = null
    private var boardCacheValid = false
    private var boardCacheLeft = 0f
    private var boardCacheTop = 0f

    // 触摸
    private var touchCell: Pair<Int, Int>? = null
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchFlagged = false

    // ---------- 定时器 ----------

    // 墙钟锚点计时（对齐桌面版 app.js 的改造）：postDelayed 只负责驱动刷新，
    // 秒数由 elapsedRealtime 差值计算，消除逐拍累加漂移。
    // 覆盖层打开或切后台时暂停并把已走毫秒存入 timerBaseMs，恢复后从锚点续走。
    private var timerBaseMs = 0L
    private var timerAnchorMs = 0L

    private fun bankTimer() {
        if (timerRunning) {
            timerBaseMs += SystemClock.elapsedRealtime() - timerAnchorMs
            removeCallbacks(timerRunnable)
            timerRunning = false
        }
    }

    private fun syncTimerValue() {
        if (!timerRunning) return
        val t = min(999, ((timerBaseMs + SystemClock.elapsedRealtime() - timerAnchorMs) / 1000L).toInt())
        if (t != engine.timer) engine.timer = t
    }

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!(appActive && engine.state == "playing" && overlay == Overlay.None)) {
                bankTimer()
                return
            }
            syncTimerValue()
            invalidate()
            // 对齐下一个整秒边界再排程，单次 postDelayed 延迟不会累积
            val phase = (timerBaseMs + SystemClock.elapsedRealtime() - timerAnchorMs) % 1000L
            postDelayed(this, 1000L - phase)
        }
    }

    private fun manageTimer() {
        val shouldRun = appActive && engine.state == "playing" && overlay == Overlay.None
        if (shouldRun && !timerRunning) {
            timerAnchorMs = SystemClock.elapsedRealtime()
            timerRunning = true
            postDelayed(timerRunnable, 1000L)
        } else if (!shouldRun && timerRunning) {
            bankTimer()
        }
    }

    // ---------- 移动重复（对齐桌面版 app.js 的定时器驱动长按） ----------

    private val moveDelay = object : Runnable {
        override fun run() {
            postDelayed(moveRepeat, REPEAT_INTERVAL_MS)
        }
    }

    private val moveRepeat = object : Runnable {
        override fun run() {
            stepMove()
            postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    private fun stopMoveTimers() {
        removeCallbacks(moveDelay)
        removeCallbacks(moveRepeat)
    }

    private fun startMoveRepeat() {
        stopMoveTimers()
        postDelayed(moveDelay, REPEAT_DELAY_MS)
    }

    private fun clearHeldMoves() {
        heldMoves.clear()
        stopMoveTimers()
    }

    private fun stepMove() {
        val next = Movement.combineDirections(
            heldMoves, engine.rows, engine.cols, cursor.first, cursor.second
        ) ?: return
        setCursor(next.first, next.second)
    }

    private fun setCursor(r: Int, c: Int) {
        cursor = r to c
        cursorVisible = true
        ensureCursorVisible(metrics())
        invalidate()
    }

    // ---------- 生命周期（MainActivity 调用） ----------

    fun onAppPaused() {
        appActive = false
        clearHeldMoves()
        removeCallbacks(longPressRunnable)
        touchCell = null
        facePressed = false
        manageTimer()
        invalidate()
    }

    fun onAppResumed() {
        appActive = true
        manageTimer()
        invalidate()
    }

    fun onDestroy() {
        removeCallbacks(timerRunnable)
        removeCallbacks(moveDelay)
        removeCallbacks(moveRepeat)
        removeCallbacks(longPressRunnable)
    }

    /** 尺寸变化（含分屏/旋转）后棋盘布局与位图缓存必须重建 */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        invalidateBoardCache()
    }

    // ---------- 棋盘位图缓存 ----------

    /** 棋盘静态层失效：尺寸/主题/难度/任意一步操作后调用，下次绘制时重绘内容 */
    private fun invalidateBoardCache() {
        boardCacheValid = false
    }

    // ---------- 主题 / 静音 ----------

    /**
     * @param persist false 时不写入偏好：用于"用户未选过主题"时的系统深浅色跟随，
     *                这样系统切换深浅色可以继续生效；用户显式选过主题后固定不变。
     */
    fun setThemeValue(value: String, persist: Boolean = true) {
        theme = themes.find { it.value == value } ?: themes[0]
        if (persist) storage.putString("theme", theme.value)
        invalidateBoardCache()
        invalidate()
    }

    /** MainActivity 启动时同步运行时版本号（About 页显示，替代硬编码） */
    fun setAppVersion(v: String) {
        appVersion = v
    }

    /** MainActivity 启动时同步持久化的静音状态（与 setThemeValue 同模式） */
    fun setMutedValue(b: Boolean) {
        muted = b
        sound.setEnabled(!muted)
    }

    /** MainActivity 启动时同步持久化的无猜模式（与 setMutedValue 同模式） */
    fun setNoGuessValue(b: Boolean) {
        engine.noGuess = b
    }

    private fun toggleMute() {
        muted = !muted
        storage.putString("muted", muted.toString())
        sound.setEnabled(!muted)
    }

    private fun toggleNoGuess() {
        engine.noGuess = !engine.noGuess
        storage.putString("noGuess", engine.noGuess.toString())
    }

    // ---------- 新游戏 / 状态同步 ----------

    private fun canAct(): Boolean = engine.state == "ready" || engine.state == "playing"

    private fun startGame(level: String) {
        // 对齐桌面版 app.js：custom 难度需要恢复上次的自定义配置
        if (level == "custom" && engine.currentCustom != null) {
            val cfg = engine.currentCustom!!
            engine.initCustom(cfg.rows, cfg.cols, cfg.mines)
        } else {
            engine.init(level)
        }
        afterNewGame()
    }

    private fun startCustom() {
        engine.initCustom(customRows, customCols, customMines)
        afterNewGame()
    }

    private fun afterNewGame() {
        cursor = 0 to 0
        cursorVisible = false
        clearHeldMoves()
        overlay = Overlay.None
        pendingRestart = null
        revealedAnim.clear()
        shakeCells.clear()
        shakeStart = 0L
        recordBanner = ""
        recordBannerStart = 0L
        // 新棋盘尺寸可能不同：复位视口滚动与触摸按下态
        viewOffsetX = 0
        viewOffsetY = 0
        touchDiffButton = null
        touchPreviewCell = null
        draggingBoard = false
        facePressed = false
        prevState = "ready"
        engine.dirty.clear()
        bankTimer()
        timerBaseMs = 0L
        engine.timer = 0
        invalidateBoardCache()
        manageTimer()
        invalidate()
    }

    /** 状态迁移：统计、胜利自动补旗/展开、计时（对齐 ui.js onStateChange） */
    private fun syncState() {
        val s = engine.state
        if (s != prevState) {
            // ready → 任意非 ready 状态都计入总局数：
            // 覆盖「一次操作直接 ready→won」的路径，避免胜率统计出现 >100% 的口径洞
            if (s != "ready" && prevState == "ready") {
                stats.onGameStart(engine.currentLevel)
            }
            if (s == "won") {
                engine.autoFlagRemainingMines()
                engine.revealAllSafe()
                maybeShowRecordBanner()
                stats.onGameWin(engine.timer, engine.bbbv)
            }
            if (s == "lost") {
                stats.onGameLose(engine.timer)
            }
            prevState = s
        }
        // 任何一次操作都可能改变格子外观（翻开/插旗/终局布置），静态层需要重建
        invalidateBoardCache()
        manageTimer()
        invalidate()
    }

    /** 胜利时若刷新了该难度的最快时间，弹「新纪录」横幅（必须在 onGameWin 之前比较） */
    private fun maybeShowRecordBanner() {
        val level = stats.lastLevel ?: engine.currentLevel
        val prevBest = stats.bestTime[level]
        // 首次通关（无旧纪录）同样算新纪录
        if (prevBest != null && engine.timer >= prevBest) return
        val label = GameEngine.LEVELS[engine.currentLevel]?.label ?: "自定义"
        recordBanner = "新纪录！$label ${formatTime(engine.timer)}"
        recordBannerStart = SystemClock.uptimeMillis()
    }

    private fun formatTime(t: Int): String =
        String.format("%d:%02d", t / 60, t % 60)

    private fun markRevealAnimFromDirty() {
        val now = SystemClock.uptimeMillis()
        for ((r, c) in engine.dirty) {
            val cell = engine.board[r][c]
            if (cell.revealed && !cell.mine) revealedAnim[r * engine.cols + c] = now
        }
        engine.dirty.clear()
    }

    // ---------- 动作（对齐 ui.js 的操作入口与音效） ----------

    private fun activateAtCursor() {
        if (!canAct()) return
        val (r, c) = cursor
        if (r !in 0 until engine.rows || c !in 0 until engine.cols) return
        val cell = engine.board[r][c]
        if (cell.revealed && cell.adjacentMines > 0) doChord(r, c) else revealAt(r, c)
    }

    private fun revealAt(r: Int, c: Int) {
        engine.reveal(r, c)
        markRevealAnimFromDirty()
        syncState()
        when {
            engine.state == "lost" -> sound.play("explosion")
            engine.state == "won" -> sound.play("win")
            else -> sound.play("click")
        }
    }

    private fun flagAt(r: Int, c: Int) {
        engine.toggleFlag(r, c)
        engine.dirty.clear()
        syncState()
        if (engine.state == "won") sound.play("win") else sound.play("flag")
    }

    private fun doChord(r: Int, c: Int) {
        val did = engine.chord(r, c)
        markRevealAnimFromDirty()
        syncState()
        when {
            engine.state == "won" -> sound.play("win")
            engine.state == "lost" -> sound.play("explosion")
            did -> sound.play("chordSuccess")
            else -> {
                sound.play("chordFail")
                startShake(r, c)
            }
        }
    }

    /**
     * 和弦失败抖动：只让数字格周围"未翻开"的格子抖动（与桌面版范围一致），双轴错位。
     */
    private fun startShake(r: Int, c: Int) {
        shakeCells.clear()
        for (dr in -1..1) {
            for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                val nr = r + dr
                val nc = c + dc
                if (nr in 0 until engine.rows && nc in 0 until engine.cols) {
                    val cell = engine.board[nr][nc]
                    if (!cell.revealed) shakeCells.add(nr * engine.cols + nc)
                }
            }
        }
        shakeStart = SystemClock.uptimeMillis()
    }

    // ---------- 覆盖层打开/关闭 ----------

    private fun openMenu() {
        clearHeldMoves()
        overlay = Overlay.Menu
        menuSelected = 0
        lastKeyInfo = "—"
        manageTimer()
        invalidate()
    }

    private fun openCustom() {
        overlay = Overlay.Custom
        customField = 0
        customError = ""
        manageTimer()
        invalidate()
    }

    private fun openBindings() {
        overlay = Overlay.Bindings
        bindSelected = 0
        bindListening = null
        bindError = ""
        lastKeyInfo = "—"
        manageTimer()
        invalidate()
    }

    private fun openHistory() {
        historyLevelIndex = HISTORY_LEVELS.indexOf(engine.currentLevel).coerceAtLeast(0)
        overlay = Overlay.History
        manageTimer()
        invalidate()
    }

    private fun closeOverlay() {
        overlay = Overlay.None
        pendingRestart = null
        touchConfirmButton = null
        manageTimer()
        invalidate()
    }

    // ---------- 重开保护 ----------

    /**
     * 对局进行中（已有进展）时先弹确认框再执行，避免掌机 Start 键 / 表情按钮误触丢局；
     * 未开局（ready）或已终局（won/lost）时直接执行，不打扰。
     */
    private fun requestRestart(action: () -> Unit) {
        if (engine.state == "playing") {
            pendingRestart = action
            confirmSelected = 1 // 默认停在「取消」：连按两下不会误重开
            touchConfirmButton = null
            overlay = Overlay.ConfirmNewGame
            clearHeldMoves()
            manageTimer()
            invalidate()
        } else {
            action()
        }
    }

    private fun runPendingRestart(confirmed: Boolean) {
        val act = pendingRestart
        pendingRestart = null
        closeOverlay()
        if (confirmed && act != null) act()
    }

    private fun menuItems(): List<MenuItem> {
        val items = mutableListOf<MenuItem>()
        items += MenuItem("新游戏") { requestRestart { startGame(engine.currentLevel) } }
        for (lvl in listOf("beginner", "intermediate", "expert")) {
            val cfg = GameEngine.LEVELS.getValue(lvl)
            items += MenuItem(
                "难度：${cfg.label} ${cfg.rows}×${cfg.cols}/${cfg.mines}雷",
                checked = engine.currentLevel == lvl && engine.currentCustom == null
            ) { requestRestart { startGame(lvl) } }
        }
        items += MenuItem("自定义雷区...") { openCustom() }
        for (t in themes) {
            items += MenuItem(
                "主题：${t.label}",
                checked = theme.value == t.value
            ) { setThemeValue(t.value) }
        }
        items += MenuItem(if (muted) "静音：开" else "静音：关") { toggleMute() }
        items += MenuItem(if (engine.noGuess) "无猜模式：开" else "无猜模式：关") { toggleNoGuess() }
        items += MenuItem("键位设置...") { openBindings() }
        items += MenuItem("游戏统计...") {
            overlay = Overlay.Stats
            manageTimer()
        }
        items += MenuItem("效率曲线...") { openHistory() }
        items += MenuItem("关于...") {
            overlay = Overlay.About
            manageTimer()
        }
        return items
    }

    private fun bindRows(): List<BindRow> {
        val rows = KeyBindings.ACTIONS.map { action ->
            BindRow(BindRowKind.ACTION, action, bindings.getBindings()[action]!!.label)
        }
        return rows + BindRow(BindRowKind.RESET, null, "恢复默认") + BindRow(BindRowKind.CLOSE, null, "关闭")
    }

    // ---------- 手柄按键（MainActivity 转发） ----------

    fun handleKeyDown(code: Int, isRepeat: Boolean = false): Boolean {
        lastKeyInfo = "${KeyNames.nameOf(code)} (${code})"
        val action = bindings.getActionForKey(code)

        // 非方向动作忽略硬件键自动重复（对齐桌面版 app.js 的 e.repeat 守卫）：
        // 避免按住 B 连续翻开、按住 Start 连续重开。
        // 方向键的重复放行——菜单滚动受益于长按连续滚动，棋盘移动由 heldMoves 自行去重。
        if (isRepeat && action != null && action !in MOVE_ACTIONS) return true

        // 键位捕获模式：任意键（Back 除外）直接绑定
        if (overlay == Overlay.Bindings && bindListening != null) {
            if (code == KeyCodes.BACK) {
                bindListening = null
                bindError = ""
            } else {
                val newAction = bindListening!!
                val conflict = bindings.findConflict(newAction, code)
                if (conflict != null) {
                    bindError = "「${KeyNames.nameOf(code)}」已被「${bindings.getBindings()[conflict]!!.label}」占用"
                    bindListening = null
                } else {
                    bindings.setKey(newAction, code)
                    bindListening = null
                    bindError = ""
                }
            }
            invalidate()
            return true
        }

        when (overlay) {
            Overlay.None -> {
                if (action != null) {
                    when {
                        action in MOVE_ACTIONS -> {
                            if (!heldMoves.contains(action)) {
                                heldMoves.add(action)
                                stepMove()
                                startMoveRepeat()
                            }
                            return true
                        }
                        action == "activate" -> {
                            if (canAct()) facePressed = true
                            activateAtCursor()
                            return true
                        }
                        action == "flag" -> {
                            flagAtCursor()
                            return true
                        }
                        action == "newGame" -> {
                            requestRestart { startGame(engine.currentLevel) }
                            return true
                        }
                        action == "menu" -> {
                            openMenu()
                            return true
                        }
                    }
                }
                if (code == KeyCodes.BACK) {
                    openMenu()
                    return true
                }
                return false
            }

            Overlay.Menu -> {
                val items = menuItems()
                when {
                    action == "moveUp" -> menuSelected = (menuSelected + items.size - 1) % items.size
                    action == "moveDown" -> menuSelected = (menuSelected + 1) % items.size
                    action == "activate" || action == "flag" -> items[menuSelected].action()
                    code == KeyCodes.BACK || action == "menu" -> closeOverlay()
                    else -> return false
                }
                invalidate()
                return true
            }

            Overlay.Custom -> {
                when {
                    action == "moveUp" -> customField = (customField + 2) % 3
                    action == "moveDown" -> customField = (customField + 1) % 3
                    action == "moveLeft" -> adjustCustom(-1)
                    action == "moveRight" -> adjustCustom(1)
                    action == "activate" || action == "flag" -> confirmCustom()
                    code == KeyCodes.BACK || action == "menu" -> closeOverlay()
                    else -> return false
                }
                invalidate()
                return true
            }

            Overlay.Bindings -> {
                val rows = bindRows()
                when {
                    action == "moveUp" -> bindSelected = (bindSelected + rows.size - 1) % rows.size
                    action == "moveDown" -> bindSelected = (bindSelected + 1) % rows.size
                    action == "activate" || action == "flag" -> {
                        val row = rows[bindSelected]
                        when (row.kind) {
                            BindRowKind.ACTION -> {
                                bindListening = row.action
                                bindError = ""
                            }
                            BindRowKind.RESET -> {
                                bindings.reset()
                                bindings.save()
                                bindError = ""
                            }
                            BindRowKind.CLOSE -> closeOverlay()
                        }
                    }
                    code == KeyCodes.BACK || action == "menu" -> closeOverlay()
                    else -> return false
                }
                invalidate()
                return true
            }

            Overlay.ConfirmNewGame -> {
                when {
                    action == "moveLeft" || action == "moveUp" -> confirmSelected = 0
                    action == "moveRight" || action == "moveDown" -> confirmSelected = 1
                    action == "activate" || action == "flag" -> runPendingRestart(confirmSelected == 0)
                    code == KeyCodes.BACK || action == "menu" -> runPendingRestart(false)
                    else -> return false
                }
                invalidate()
                return true
            }

            Overlay.History -> {
                when {
                    action == "moveLeft" || action == "moveUp" ->
                        historyLevelIndex = (historyLevelIndex + HISTORY_LEVELS.size - 1) % HISTORY_LEVELS.size
                    action == "moveRight" || action == "moveDown" ->
                        historyLevelIndex = (historyLevelIndex + 1) % HISTORY_LEVELS.size
                    action == "activate" || action == "flag" ||
                        code == KeyCodes.BACK || action == "menu" -> closeOverlay()
                    else -> return false
                }
                invalidate()
                return true
            }

            Overlay.Stats, Overlay.About -> {
                if (action != null || code == KeyCodes.BACK) {
                    closeOverlay()
                    return true
                }
                return false
            }
        }
    }

    fun handleKeyUp(code: Int): Boolean {
        val action = bindings.getActionForKey(code) ?: return false
        if (action == "activate") {
            facePressed = false
            invalidate()
            return true
        }
        if (action in MOVE_ACTIONS) {
            heldMoves.remove(action)
            if (heldMoves.isEmpty()) {
                stopMoveTimers()
            } else {
                stepMove()
            }
            return true
        }
        return false
    }

    private fun flagAtCursor() {
        if (!canAct()) return
        val (r, c) = cursor
        if (r !in 0 until engine.rows || c !in 0 until engine.cols) return
        flagAt(r, c)
    }

    private fun adjustCustom(delta: Int) {
        when (customField) {
            0 -> customRows = (customRows + delta).coerceIn(9, 50)
            1 -> customCols = (customCols + delta).coerceIn(9, 50)
            2 -> customMines = (customMines + delta).coerceIn(1, maxMinesFor())
        }
        customError = ""
    }

    private fun maxMinesFor(): Int = (customRows * customCols * 0.85).toInt()

    private fun confirmCustom() {
        val maxMines = maxMinesFor()
        customError = when {
            customRows !in 9..50 -> "行必须是 9 到 50 之间的整数"
            customCols !in 9..50 -> "列必须是 9 到 50 之间的整数"
            customMines !in 1..maxMines -> "雷数必须是 1 到 $maxMines 之间的整数"
            else -> ""
        }
        if (customError.isEmpty()) {
            startCustom()
        } else {
            invalidate()
        }
    }

    // ---------- 布局 ----------

    private fun metrics(): Metrics {
        // 视图尺寸与棋盘规模都不变时直接复用上次结果，
        // 避免 onDraw/触摸事件每帧分配 Metrics 对象
        metricsCache?.let { cached ->
            if (metricsW == width && metricsH == height &&
                metricsRows == engine.rows && metricsCols == engine.cols
            ) return cached
        }
        val w = width.toFloat()
        val h = height.toFloat()
        val cell = min(
            (w - 2 * PAD) / engine.cols,
            (h - HUD_H - BOTTOM_H - 3 * PAD) / engine.rows
        ).coerceIn(MIN_CELL, MAX_CELL)
        val boardW = cell * engine.cols
        val boardH = cell * engine.rows
        val view = boardViewport()
        // 装得下就居中；装不下则从左上留白处开始，由视口滚动浏览
        val boardX = if (boardW + 2 * PAD <= view.width()) (w - boardW) / 2f else PAD
        val boardY = if (boardH + 2 * PAD <= view.height()) {
            view.top + ((view.height() - boardH) / 2f).coerceAtLeast(0f)
        } else {
            view.top + PAD
        }
        val m = Metrics(cell, boardX, boardY, boardW, boardH)
        metricsCache = m
        metricsW = width
        metricsH = height
        metricsRows = engine.rows
        metricsCols = engine.cols
        return m
    }

    /** 棋盘可视区（HUD 下沿到底栏上沿），滚动裁剪用 */
    private fun boardViewport(): RectF =
        RectF(0f, HUD_H, width.toFloat(), height - BOTTOM_H)

    /** 滚动偏移夹紧到有效范围（内容比视口小时恒为 0） */
    private fun clampOffsets(m: Metrics, view: RectF) {
        val maxX = (m.w + 2 * PAD - view.width()).toInt().coerceAtLeast(0)
        val maxY = (m.h + 2 * PAD - view.height()).toInt().coerceAtLeast(0)
        viewOffsetX = viewOffsetX.coerceIn(0, maxX)
        viewOffsetY = viewOffsetY.coerceIn(0, maxY)
    }

    /** 保证光标格在视口内（大棋盘时手柄移动光标会自动滚动跟随） */
    private fun ensureCursorVisible(m: Metrics) {
        val view = boardViewport()
        val (r, c) = cursor
        val left = m.x + c * m.cell
        val top = m.y + r * m.cell
        val right = left + m.cell
        val bottom = top + m.cell
        if (left - viewOffsetX < view.left) viewOffsetX = floor(left - view.left).toInt()
        if (right - viewOffsetX > view.right) viewOffsetX = ceil(right - view.right).toInt()
        if (top - viewOffsetY < view.top) viewOffsetY = floor(top - view.top).toInt()
        if (bottom - viewOffsetY > view.bottom) viewOffsetY = ceil(bottom - view.bottom).toInt()
        clampOffsets(m, view)
    }

    // ---------- 绘制 ----------

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val m = metrics()
        canvas.drawColor(theme.colors.bgPage)
        drawHud(canvas)
        drawBoard(canvas, m, now)
        drawBottomBar(canvas)
        drawRecordBanner(canvas, now)
        when (overlay) {
            Overlay.Menu -> drawMenu(canvas)
            Overlay.Custom -> drawCustom(canvas)
            Overlay.Bindings -> drawBindings(canvas)
            Overlay.Stats -> drawStats(canvas)
            Overlay.About -> drawAbout(canvas)
            Overlay.ConfirmNewGame -> drawConfirmNewGame(canvas)
            Overlay.History -> drawHistory(canvas)
            Overlay.None -> {}
        }
        if (!appActive && engine.state == "playing") drawPause(canvas)

        // 抖动/翻开动画/新纪录横幅淡出都按时间插值，必须持续请求新帧才会动起来
        val shaking = shakeStart != 0L && now - shakeStart < SHAKE_MS
        if (shaking || revealedAnim.isNotEmpty() || recordBannerStart != 0L) {
            postInvalidateOnAnimation()
        }
    }

    private fun textBaseline(cy: Float, p: Paint): Float = cy - (p.ascent() + p.descent()) / 2f

    private fun drawBevel(canvas: Canvas, rect: RectF, w: Float, light: Int, dark: Int) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = w
        paint.color = light
        canvas.drawLine(rect.left, rect.top, rect.right, rect.top, paint)
        canvas.drawLine(rect.left, rect.top, rect.left, rect.bottom, paint)
        paint.color = dark
        canvas.drawLine(rect.right, rect.top, rect.right, rect.bottom, paint)
        canvas.drawLine(rect.left, rect.bottom, rect.right, rect.bottom, paint)
    }

    private fun drawHud(canvas: Canvas) {
        val w = width.toFloat()
        val rect = tmpRect
        rect.set(0f, 0f, w, HUD_H)
        paint.style = Paint.Style.FILL
        paint.color = theme.colors.bgPanel
        canvas.drawRect(rect, paint)
        drawBevel(canvas, rect, 2f, theme.colors.borderDark, theme.colors.borderLight)

        // 雷数 LED
        val ledW = 92f
        val ledH = 40f
        val ledY = (HUD_H - ledH) / 2f
        val mineLed = tmpRect2
        mineLed.set(PAD + 2f, ledY, PAD + 2f + ledW, ledY + ledH)
        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK
        canvas.drawRect(mineLed, paint)
        textPaint.reset()
        textPaint.isAntiAlias = true
        textPaint.typeface = MONO_BOLD
        textPaint.textSize = 28f
        textPaint.color = theme.colors.led
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(
            formatCounter(engine.totalMines - engine.flagCount),
            mineLed.centerX(), textBaseline(mineLed.centerY(), textPaint), textPaint
        )

        // 表情（新游戏按钮）
        drawFace(canvas, w / 2f, HUD_H / 2f, 22f)

        // 计时 LED
        val timerLed = tmpRect2
        timerLed.set(w - PAD - 2f - ledW, ledY, w - PAD - 2f, ledY + ledH)
        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK
        canvas.drawRect(timerLed, paint)
        textPaint.color = theme.colors.led
        canvas.drawText(
            formatCounter(engine.timer),
            timerLed.centerX(), textBaseline(timerLed.centerY(), textPaint), textPaint
        )
    }

    private fun formatCounter(n: Int): String {
        // 手写补零替代 String.format（draw 路径每帧调用两次）
        val digits = (if (n < 0) -n else n).toString()
        val target = if (n < 0) 2 else 3
        val sb = StringBuilder(if (n < 0) "-" else "")
        repeat(target - digits.length) { sb.append('0') }
        return sb.append(digits).toString()
    }

    /** 移植桌面版 FACE_SVGS：用 Canvas 路径绘制 4 种表情 */
    private fun drawFace(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val s = r / 12f
        fun px(x: Float) = cx + (x - 12f) * s
        fun py(y: Float) = cy + (y - 12f) * s

        paint.style = Paint.Style.FILL
        paint.color = 0xFFFFD23F.toInt()
        canvas.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * s
        paint.color = 0xFF8A6D1A.toInt()
        canvas.drawCircle(cx, cy, r, paint)

        val eye = 0xFF3A2C00.toInt()
        paint.style = Paint.Style.FILL
        paint.color = eye
        when (faceState()) {
            "ready" -> {
                canvas.drawCircle(px(8.8f), py(9.4f), 1.6f * s, paint)
                canvas.drawCircle(px(15.2f), py(9.4f), 1.6f * s, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.7f * s
                paint.strokeCap = Paint.Cap.ROUND
                tmpPath.reset()
                tmpPath.moveTo(px(8.2f), py(14.2f))
                tmpPath.quadTo(px(12f), py(18.2f), px(15.8f), py(14.2f))
                canvas.drawPath(tmpPath, paint)
            }
            "pressed" -> {
                canvas.drawCircle(px(8.8f), py(9.4f), 1.6f * s, paint)
                canvas.drawCircle(px(15.2f), py(9.4f), 1.6f * s, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(px(12f), py(14.8f), 2.4f * s, paint)
            }
            "won" -> {
                paint.style = Paint.Style.FILL
                paint.color = 0xFF20242B.toInt()
                tmpRect3.set(px(3.6f), py(7.8f), px(20.4f), py(12.8f))
                canvas.drawRoundRect(tmpRect3, 2.4f * s, 2.4f * s, paint)
                paint.color = 0xFF0B0D10.toInt()
                tmpRect3.set(px(3.6f), py(7.8f), px(9.2f), py(12.8f))
                canvas.drawRect(tmpRect3, paint)
                tmpRect3.set(px(14.8f), py(7.8f), px(20.4f), py(12.8f))
                canvas.drawRect(tmpRect3, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.7f * s
                paint.color = 0xFF3A2C00.toInt()
                paint.strokeCap = Paint.Cap.ROUND
                tmpPath.reset()
                tmpPath.moveTo(px(8.2f), py(15.6f))
                tmpPath.quadTo(px(12f), py(19f), px(15.8f), py(15.6f))
                canvas.drawPath(tmpPath, paint)
            }
            else -> { // lost
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.7f * s
                paint.strokeCap = Paint.Cap.ROUND
                for (x in floatArrayOf(7.2f, 13.6f)) {
                    canvas.drawLine(px(x), py(7.6f), px(x + 3.2f), py(10.8f), paint)
                    canvas.drawLine(px(x + 3.2f), py(7.6f), px(x), py(10.8f), paint)
                }
                tmpPath.reset()
                tmpPath.moveTo(px(8.6f), py(16.8f))
                tmpPath.quadTo(px(12f), py(14f), px(15.4f), py(16.8f))
                canvas.drawPath(tmpPath, paint)
            }
        }
    }

    private fun faceState(): String = when {
        engine.state == "lost" -> "lost"
        engine.state == "won" -> "won"
        facePressed -> "pressed"
        else -> "ready"
    }

    private fun drawBoard(canvas: Canvas, m: Metrics, now: Long) {
        // 抖动已结束：整体收尾，避免 onDraw 的动画调度空转
        if (shakeStart != 0L && now - shakeStart >= SHAKE_MS) {
            shakeStart = 0L
            shakeCells.clear()
        }
        val view = boardViewport()
        clampOffsets(m, view)

        // 超大棋盘启用视口滚动：只绘制可见区域（棋盘层位图坐标不受滚动影响）
        val save = canvas.save()
        canvas.clipRect(view)
        if (viewOffsetX != 0 || viewOffsetY != 0) {
            canvas.translate(-viewOffsetX.toFloat(), -viewOffsetY.toFloat())
        }
        canvas.drawBitmap(ensureBoardCache(m, now), boardCacheLeft, boardCacheTop, null)

        // 动态格（翻开动画中的格 + 抖动格及其邻居）覆盖重绘到静态层之上
        val dynamic = dynamicCells()
        if (dynamic.isNotEmpty()) {
            val cols = engine.cols
            for (idx in dynamic) drawCell(canvas, m, idx / cols, idx % cols, now, animate = true)
        }
        drawCursor(canvas, m)
        drawPressedPreview(canvas, m)
        canvas.restoreToCount(save)

        drawScrollIndicator(canvas, m, view)
    }

    /** 垂直滚动指示条：仅内容超出视口时显示，提示视口外还有内容 */
    private fun drawScrollIndicator(canvas: Canvas, m: Metrics, view: RectF) {
        val contentH = m.h + 2 * PAD
        if (contentH <= view.height()) return
        val trackX = width - 7f
        val trackTop = view.top + 6f
        val trackBottom = view.bottom - 6f
        val trackH = trackBottom - trackTop
        paint.style = Paint.Style.FILL
        paint.color = theme.colors.borderDark
        paint.alpha = 110
        canvas.drawRect(trackX, trackTop, trackX + 4f, trackBottom, paint)
        val maxOff = (contentH - view.height()).coerceAtLeast(1f)
        val barH = (trackH * (view.height() / contentH)).coerceAtLeast(28f)
        val barY = trackTop + (trackH - barH) * (viewOffsetY / maxOff)
        paint.color = theme.colors.text
        paint.alpha = 190
        canvas.drawRect(trackX, barY, trackX + 4f, barY + barH, paint)
        paint.alpha = 255
    }

    /** 触摸按下已翻开数字格时预览和弦范围（与桌面版按下高亮等价） */
    private fun drawPressedPreview(canvas: Canvas, m: Metrics) {
        val idx = touchPreviewCell ?: return
        val cols = engine.cols
        val r0 = idx / cols
        val c0 = idx % cols
        paint.style = Paint.Style.FILL
        paint.color = theme.colors.bgActive
        paint.alpha = 150
        for (dr in -1..1) {
            for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                val r = r0 + dr
                val c = c0 + dc
                if (r !in 0 until engine.rows || c !in 0 until engine.cols) continue
                tmpRect.set(
                    m.x + c * m.cell + 1f, m.y + r * m.cell + 1f,
                    m.x + (c + 1) * m.cell - 1f, m.y + (r + 1) * m.cell - 1f
                )
                canvas.drawRect(tmpRect, paint)
            }
        }
        paint.alpha = 255
    }

    /**
     * 取得棋盘静态层位图（内容是所有格子的"最终外观"，不含光标与动画）：
     * - 位图按需新建并复用，避免每次操作都分配新对象；
     * - 原点向下取整、四周留 CACHE_PAD 边距，贴图坐标全为整数 → 零重采样，
     *   抖动位移（≤7px）也不会画出缓存范围。
     */
    private fun ensureBoardCache(m: Metrics, now: Long): Bitmap {
        val left = floor(m.x).toInt() - CACHE_PAD
        val top = floor(m.y).toInt() - CACHE_PAD
        val w = (ceil(m.x + m.w).toInt() + CACHE_PAD - left).coerceAtLeast(1)
        val h = (ceil(m.y + m.h).toInt() + CACHE_PAD - top).coerceAtLeast(1)
        val bmp = boardCache?.takeIf { it.width == w && it.height == h } ?: run {
            boardCacheValid = false
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { boardCache = it }
        }
        boardCacheLeft = left.toFloat()
        boardCacheTop = top.toFloat()
        if (!boardCacheValid) {
            val c = Canvas(bmp)
            c.translate(-left.toFloat(), -top.toFloat())
            for (r in 0 until engine.rows) {
                for (col in 0 until engine.cols) drawCell(c, m, r, col, now, animate = false)
            }
            boardCacheValid = true
        }
        return bmp
    }

    /**
     * 需要每帧实时重绘的格子（升序，保持与全量绘制相同的覆盖顺序）：
     * 翻开动画中的格 + 抖动格及其 8 邻居（抖动位移 ≤7px 不会越过一格，邻居参与重绘
     * 是为了补回抖动格擦除时外扩 1px 吃掉的邻格描边）。
     */
    private fun dynamicCells(): List<Int> {
        if (shakeStart == 0L && revealedAnim.isEmpty()) return emptyList()
        val set = HashSet<Int>(revealedAnim.size * 2 + 16)
        set.addAll(revealedAnim.keys)
        if (shakeStart != 0L && shakeCells.isNotEmpty()) {
            val cols = engine.cols
            for (idx in shakeCells) {
                val r0 = idx / cols
                val c0 = idx % cols
                for (dr in -1..1) {
                    for (dc in -1..1) {
                        val r = r0 + dr
                        val c = c0 + dc
                        if (r in 0 until engine.rows && c in 0 until cols) set.add(r * cols + c)
                    }
                }
            }
        }
        return set.sorted()
    }

    /** 绘制单个格子：静态层构建与动态帧覆盖共用同一条路径（animate 控制抖动/翻开动画） */
    private fun drawCell(canvas: Canvas, m: Metrics, r: Int, c: Int, now: Long, animate: Boolean) {
        val left = m.x + c * m.cell
        val top = m.y + r * m.cell
        val rect = tmpRect
        rect.set(left, top, left + m.cell, top + m.cell)
        val data = engine.board[r][c]
        val idx = r * engine.cols + c

        // 和弦失败抖动：双轴错位（水平 7px + 垂直 5px 衰减），仅周围未翻开的格子
        var shakeDx = 0f
        var shakeDy = 0f
        if (animate && shakeStart != 0L && shakeCells.contains(idx)) {
            val elapsed = now - shakeStart
            if (elapsed < SHAKE_MS) {
                val t = elapsed / SHAKE_MS.toFloat()
                shakeDx = sin(t * 55.0).toFloat() * 7f * (1f - t)
                shakeDy = cos(t * 40.0).toFloat() * 5f * (1f - t)
            }
        }
        var animElapsed: Long? = null
        if (animate && data.revealed) {
            animElapsed = revealedAnim[idx]?.let { now - it }
        }

        val shaking = shakeDx != 0f || shakeDy != 0f
        if (shaking) {
            // 先擦掉静态层原位置的格子（外扩 1px 覆盖 bevel 描边），再画偏移后的格子；
            // 否则静态层会残留一个"鬼影"
            paint.style = Paint.Style.FILL
            paint.color = theme.colors.bgPage
            tmpRect2.set(rect.left - 1f, rect.top - 1f, rect.right + 1f, rect.bottom + 1f)
            canvas.drawRect(tmpRect2, paint)
            canvas.save()
            canvas.translate(shakeDx, shakeDy)
        }

        if (!data.revealed) {
            paint.style = Paint.Style.FILL
            paint.color = theme.colors.bgCell
            canvas.drawRect(rect, paint)
            drawBevel(
                canvas, rect, 2f,
                theme.colors.borderLight, theme.colors.borderDark
            )
            when {
                data.flagged -> {
                    drawEmoji(canvas, "🚩", rect, m.cell * 0.5f)
                    if (data.wrongFlag) drawWrongFlagCross(canvas, rect, m.cell)
                }
                data.questioned -> drawEmoji(canvas, "❓", rect, m.cell * 0.5f)
            }
        } else {
            paint.style = Paint.Style.FILL
            paint.color = if (data.exploded) Color.RED else theme.colors.bgRevealed
            canvas.drawRect(rect, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            paint.color = theme.colors.borderRevealed
            canvas.drawRect(rect, paint)

            if (animElapsed != null && animElapsed < REVEAL_ANIM_MS) {
                val scale = 0.85f + 0.15f * (animElapsed / REVEAL_ANIM_SCALE_MS).coerceAtMost(1f)
                canvas.save()
                canvas.scale(scale, scale, rect.centerX(), rect.centerY())
                drawCellContent(canvas, rect, data, m.cell)
                canvas.restore()
            } else {
                if (animElapsed != null) revealedAnim.remove(idx)
                drawCellContent(canvas, rect, data, m.cell)
            }
        }

        if (shaking) canvas.restore()
    }

    /** 失败终局：插错的旗打红叉（经典 Windows 扫雷样式） */
    private fun drawWrongFlagCross(canvas: Canvas, rect: RectF, cell: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (cell * 0.09f).coerceAtLeast(2f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = 0xFFE00000.toInt()
        val inset = cell * 0.18f
        canvas.drawLine(
            rect.left + inset, rect.top + inset,
            rect.right - inset, rect.bottom - inset, paint
        )
        canvas.drawLine(
            rect.right - inset, rect.top + inset,
            rect.left + inset, rect.bottom - inset, paint
        )
        paint.strokeCap = Paint.Cap.BUTT
    }

    /** 光标单独绘制（不进静态层，移动时光标只需重画一帧） */
    private fun drawCursor(canvas: Canvas, m: Metrics) {
        if (!cursorVisible) return
        val (r, c) = cursor
        if (r !in 0 until engine.rows || c !in 0 until engine.cols) return
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = theme.colors.led
        tmpRect2.set(
            m.x + c * m.cell + 1f, m.y + r * m.cell + 1f,
            m.x + (c + 1) * m.cell - 1f, m.y + (r + 1) * m.cell - 1f
        )
        canvas.drawRect(tmpRect2, paint)
    }

    private fun drawCellContent(canvas: Canvas, rect: RectF, data: com.wpiaopiao.saolei.core.Cell, cell: Float) {
        when {
            data.mine -> drawEmoji(canvas, "💣", rect, cell * 0.57f)
            data.adjacentMines > 0 -> {
                textPaint.reset()
                textPaint.isAntiAlias = true
                textPaint.typeface = MONO_BOLD
                textPaint.textSize = cell * 0.5f
                textPaint.color = NUMBER_COLORS[data.adjacentMines]
                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(
                    data.adjacentMines.toString(),
                    rect.centerX(), textBaseline(rect.centerY(), textPaint), textPaint
                )
            }
        }
    }

    private fun drawEmoji(canvas: Canvas, emoji: String, rect: RectF, size: Float) {
        emojiPaint.reset()
        emojiPaint.isAntiAlias = true
        emojiPaint.textSize = size
        emojiPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(emoji, rect.centerX(), textBaseline(rect.centerY(), emojiPaint), emojiPaint)
    }

    private fun drawBottomBar(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val rect = tmpRect
        rect.set(0f, h - BOTTOM_H, w, h)
        paint.style = Paint.Style.FILL
        paint.color = theme.colors.bgPanel
        canvas.drawRect(rect, paint)
        drawBevel(canvas, rect, 2f, theme.colors.borderDark, theme.colors.borderLight)

        // 难度按钮（触摸可直接点按，与手柄菜单切换等价）
        layoutDiffButtons()
        for (i in DIFF_BUTTON_LABELS.indices) {
            val level = DIFF_BUTTON_LEVELS[i]
            val btn = diffBtnRects[i]
            val isActive = when (level) {
                null -> engine.currentLevel == "custom"
                else -> engine.currentLevel == level && engine.currentCustom == null
            }
            val highlighted = isActive || touchDiffButton == i
            paint.style = Paint.Style.FILL
            paint.color = if (highlighted) theme.colors.bgActive else theme.colors.bgCell
            canvas.drawRect(btn, paint)
            drawBevel(
                canvas, btn, 2f,
                if (highlighted) theme.colors.borderDark else theme.colors.borderLight,
                if (highlighted) theme.colors.borderLight else theme.colors.borderDark
            )
            textPaint.color = theme.colors.text
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(
                DIFF_BUTTON_LABELS[i], btn.centerX(),
                textBaseline(btn.centerY(), textPaint), textPaint
            )
        }

        // 键位提示
        textPaint.reset()
        textPaint.isAntiAlias = true
        textPaint.textSize = 12f
        textPaint.color = theme.colors.text
        textPaint.textAlign = Paint.Align.RIGHT
        val hint = "${bindings.getDisplayKey("activate")}翻开/和弦  " +
            "${bindings.getDisplayKey("flag")}标雷  " +
            "${bindings.getDisplayKey("menu")}菜单"
        canvas.drawText(hint, w - PAD - 4f, textBaseline(rect.centerY(), textPaint), textPaint)
    }

    /** 布局底部难度按钮：绘制与触摸命中共用同一套计算，避免两处漂移 */
    private fun layoutDiffButtons() {
        val btnH = BOTTOM_H - 14f
        val btnY = height - BOTTOM_H + 7f
        textPaint.reset()
        textPaint.isAntiAlias = true
        textPaint.typeface = MONO_BOLD
        textPaint.textSize = 14f
        var x = PAD + 4f
        for (i in DIFF_BUTTON_LABELS.indices) {
            val btnW = textPaint.measureText(DIFF_BUTTON_LABELS[i]) + 22f
            diffBtnRects[i].set(x, btnY, x + btnW, btnY + btnH)
            x += btnW + 6f
        }
    }

    /** 触摸命中的难度按钮下标；未命中返回 null */
    private fun diffButtonIndex(x: Float, y: Float): Int? {
        layoutDiffButtons()
        for (i in diffBtnRects.indices) {
            if (diffBtnRects[i].contains(x, y)) return i
        }
        return null
    }

    private fun drawPause(canvas: Canvas) {
        // 不透明遮盖（对齐桌面版 style.css 的暂停改造）：暂停期间看不清棋盘
        canvas.drawColor(theme.colors.bgPage)
        textPaint.reset()
        textPaint.isAntiAlias = true
        textPaint.textSize = 30f
        textPaint.color = Color.WHITE
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(
            "已暂停", width / 2f,
            textBaseline(height / 2f - 12f, textPaint), textPaint
        )
        textPaint.textSize = 14f
        canvas.drawText(
            "回到游戏后自动继续", width / 2f,
            textBaseline(height / 2f + 26f, textPaint), textPaint
        )
    }

    // ---------- 覆盖层通用绘制 ----------

    /** 对话框矩形：绘制与触摸命中共用同一套计算，保证按钮位置一致 */
    private fun dialogRect(contentHeight: Float): RectF {
        val w = (width * 0.86f).coerceAtMost(560f)
        val left = (width - w) / 2f
        val top = (height - contentHeight) / 2f
        return RectF(left, top, left + w, top + contentHeight)
    }

    private fun drawDialog(
        canvas: Canvas, title: String, contentHeight: Float,
        content: (Canvas, RectF) -> Unit
    ) {
        canvas.drawColor(theme.colors.overlay)
        val rect = dialogRect(contentHeight)
        paint.style = Paint.Style.FILL
        paint.color = theme.colors.dialogBg
        canvas.drawRect(rect, paint)
        drawBevel(canvas, rect, 3f, theme.colors.borderLight, theme.colors.borderDark)
        textPaint.reset()
        textPaint.isAntiAlias = true
        textPaint.typeface = MONO_BOLD
        textPaint.textSize = 17f
        textPaint.color = theme.colors.text
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(title, rect.centerX(), rect.top + 26f, textPaint)
        content(canvas, rect)
    }

    // ---------- 新纪录横幅 ----------

    /** 胜利破纪录横幅：居中显示，末尾淡出（saveLayerAlpha 整体透明，避免逐元素改 alpha） */
    private fun drawRecordBanner(canvas: Canvas, now: Long) {
        if (recordBannerStart == 0L) return
        val elapsed = now - recordBannerStart
        if (elapsed >= RECORD_BANNER_MS) {
            recordBanner = ""
            recordBannerStart = 0L
            return
        }
        val fade = if (elapsed > RECORD_BANNER_MS - RECORD_BANNER_FADE_MS) {
            (RECORD_BANNER_MS - elapsed) / RECORD_BANNER_FADE_MS.toFloat()
        } else 1f

        textPaint.reset()
        textPaint.isAntiAlias = true
        textPaint.typeface = MONO_BOLD
        textPaint.textSize = 20f
        textPaint.textAlign = Paint.Align.CENTER
        val w = textPaint.measureText(recordBanner) + 52f
        val h = 52f
        val rect = RectF(
            (width - w) / 2f, (height - h) / 2f,
            (width + w) / 2f, (height + h) / 2f
        )
        val alpha = (255 * fade).toInt().coerceIn(0, 255)
        val sc = canvas.saveLayerAlpha(rect, alpha)
        paint.style = Paint.Style.FILL
        paint.color = theme.colors.dialogBg
        canvas.drawRect(rect, paint)
        drawBevel(canvas, rect, 3f, theme.colors.borderLight, theme.colors.borderDark)
        textPaint.color = theme.colors.text
        canvas.drawText(
            recordBanner, rect.centerX(), textBaseline(rect.centerY(), textPaint), textPaint
        )
        canvas.restoreToCount(sc)
    }

    // ---------- 重开确认 ----------

    private fun confirmButtonRects(): Pair<RectF, RectF> {
        val rect = dialogRect(CONFIRM_DIALOG_H)
        val gap = 14f
        val bw = (rect.width() - 3 * gap) / 2f
        val bh = 40f
        val by = rect.bottom - gap - bh
        val yes = RectF(rect.left + gap, by, rect.left + gap + bw, by + bh)
        val no = RectF(rect.right - gap - bw, by, rect.right - gap, by + bh)
        return yes to no
    }

    /** 触摸命中：0=重开 1=取消，未命中返回 null */
    private fun confirmButtonIndex(x: Float, y: Float): Int? {
        val (yes, no) = confirmButtonRects()
        return when {
            yes.contains(x, y) -> 0
            no.contains(x, y) -> 1
            else -> null
        }
    }

    private fun drawConfirmNewGame(canvas: Canvas) {
        drawDialog(canvas, "重新开始？", CONFIRM_DIALOG_H) { c, rect ->
            textPaint.reset()
            textPaint.isAntiAlias = true
            textPaint.textSize = 14f
            textPaint.color = theme.colors.text
            textPaint.textAlign = Paint.Align.CENTER
            c.drawText("当前对局将丢失", rect.centerX(), rect.top + 52f, textPaint)
            val (yes, no) = confirmButtonRects()
            drawConfirmButton(c, yes, "重开", confirmSelected == 0)
            drawConfirmButton(c, no, "取消", confirmSelected == 1)
        }
    }

    private fun drawConfirmButton(canvas: Canvas, rect: RectF, label: String, selected: Boolean) {
        paint.style = Paint.Style.FILL
        paint.color = if (selected) theme.colors.bgActive else theme.colors.bgCell
        canvas.drawRect(rect, paint)
        drawBevel(
            canvas, rect, 2f,
            if (selected) theme.colors.borderDark else theme.colors.borderLight,
            if (selected) theme.colors.borderLight else theme.colors.borderDark
        )
        textPaint.reset()
        textPaint.isAntiAlias = true
        textPaint.textSize = 15f
        textPaint.color = theme.colors.text
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(label, rect.centerX(), textBaseline(rect.centerY(), textPaint), textPaint)
    }

    private fun drawMenu(canvas: Canvas) {
        val items = menuItems()
        val itemH = 32f
        val contentH = 46f + items.size * itemH + 10f
        drawDialog(canvas, "菜单", contentH) { _, rect ->
            for (i in items.indices) {
                val row = RectF(rect.left + 10f, rect.top + 42f + i * itemH, rect.right - 10f, rect.top + 42f + (i + 1) * itemH - 4f)
                val selected = i == menuSelected
                if (selected) {
                    paint.style = Paint.Style.FILL
                    paint.color = theme.colors.bgActive
                    canvas.drawRect(row, paint)
                    drawBevel(canvas, row, 1.5f, theme.colors.borderDark, theme.colors.borderLight)
                }
                textPaint.reset()
                textPaint.isAntiAlias = true
                textPaint.textSize = 14f
                textPaint.color = theme.colors.text
                textPaint.textAlign = Paint.Align.LEFT
                val prefix = if (items[i].checked) "✓ " else ""
                canvas.drawText(
                    prefix + items[i].text,
                    row.left + 10f, textBaseline(row.centerY(), textPaint), textPaint
                )
            }
        }
    }

    private fun drawCustom(canvas: Canvas) {
        val contentH = 190f
        drawDialog(canvas, "自定义雷区", contentH) { _, rect ->
            textPaint.reset()
            textPaint.isAntiAlias = true
            textPaint.textSize = 13f
            textPaint.color = 0xFFC00000.toInt()
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(customError, rect.left + 16f, rect.top + 40f, textPaint)

            val fields = listOf("行", "列", "雷数")
            val values = listOf(customRows, customCols, customMines)
            for (i in 0..2) {
                val row = RectF(rect.left + 16f, rect.top + 50f + i * 36f, rect.right - 16f, rect.top + 82f + i * 36f)
                val selected = i == customField
                if (selected) {
                    paint.style = Paint.Style.FILL
                    paint.color = theme.colors.bgActive
                    canvas.drawRect(row, paint)
                    drawBevel(canvas, row, 1.5f, theme.colors.borderDark, theme.colors.borderLight)
                }
                textPaint.reset()
                textPaint.isAntiAlias = true
                textPaint.textSize = 15f
                textPaint.color = theme.colors.text
                textPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(fields[i], row.left + 12f, textBaseline(row.centerY(), textPaint), textPaint)
                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(values[i].toString(), row.centerX(), textBaseline(row.centerY(), textPaint), textPaint)
                textPaint.textSize = 12f
                textPaint.textAlign = Paint.Align.RIGHT
                canvas.drawText("◀ ▶", row.right - 12f, textBaseline(row.centerY(), textPaint), textPaint)
            }
            textPaint.reset()
            textPaint.isAntiAlias = true
            textPaint.textSize = 12f
            textPaint.color = theme.colors.text
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("左右调整值 · 上下选择 · A/B 确定 · Back 取消", rect.centerX(), rect.bottom - 12f, textPaint)
        }
    }

    private fun drawBindings(canvas: Canvas) {
        val rows = bindRows()
        val itemH = 30f
        val contentH = 46f + rows.size * itemH + 46f
        drawDialog(canvas, "键位设置", contentH) { _, rect ->
            // 错误行
            textPaint.reset()
            textPaint.isAntiAlias = true
            textPaint.textSize = 12f
            textPaint.color = 0xFFC00000.toInt()
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(bindError, rect.left + 14f, rect.top + 38f, textPaint)

            for (i in rows.indices) {
                val row = RectF(rect.left + 10f, rect.top + 44f + i * itemH, rect.right - 10f, rect.top + 44f + (i + 1) * itemH - 3f)
                val selected = i == bindSelected
                val listening = bindListening == rows[i].action
                val blinkOn = (SystemClock.uptimeMillis() % 800) < 400
                if (selected || (listening && blinkOn)) {
                    paint.style = Paint.Style.FILL
                    paint.color = theme.colors.bgActive
                    canvas.drawRect(row, paint)
                    drawBevel(canvas, row, 1.5f, theme.colors.borderDark, theme.colors.borderLight)
                }
                textPaint.reset()
                textPaint.isAntiAlias = true
                textPaint.textSize = 13f
                textPaint.color = theme.colors.text
                textPaint.textAlign = Paint.Align.LEFT
                val label = if (rows[i].kind == BindRowKind.ACTION) rows[i].label else rows[i].label
                canvas.drawText(label, row.left + 12f, textBaseline(row.centerY(), textPaint), textPaint)

                textPaint.textAlign = Paint.Align.RIGHT
                textPaint.color = if (listening) theme.colors.led else theme.colors.text
                val value = when {
                    listening -> "按下新键..."
                    rows[i].kind == BindRowKind.ACTION -> bindings.getDisplayKey(rows[i].action!!)
                    else -> ""
                }
                canvas.drawText(value, row.right - 12f, textBaseline(row.centerY(), textPaint), textPaint)
            }

            // 按键测试屏
            textPaint.reset()
            textPaint.isAntiAlias = true
            textPaint.textSize = 12f
            textPaint.color = theme.colors.text
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(
                "当前按键: $lastKeyInfo",
                rect.centerX(), rect.bottom - 34f, textPaint
            )
            canvas.drawText(
                "选择动作后按 A/B 开始录制，再按目标键 · Back 取消",
                rect.centerX(), rect.bottom - 14f, textPaint
            )
        }
    }

    private fun drawStats(canvas: Canvas) {
        val s = stats.getSummary()
        val fmtTime = { t: Int? -> if (t == null) "--:--" else formatTime(t) }
        val fmtRate = { r: Double? -> if (r == null) "--" else String.format("%.2f", r) }
        val lines = listOf(
            "总局数: ${s.totalGames}",
            "胜利: ${s.gamesWon} / 失败: ${s.gamesLost}",
            "胜率: ${s.winRate}%",
            "当前连胜: ${s.currentStreak}",
            "最长连胜: ${s.bestStreak}",
            "总游戏时间: ${fmtTime(s.totalPlayTime)}",
            "最快完成:",
            "　初级: ${fmtTime(s.bestTime["beginner"])}",
            "　中级: ${fmtTime(s.bestTime["intermediate"])}",
            "　高级: ${fmtTime(s.bestTime["expert"])}",
            "　自定义: ${fmtTime(s.bestTime["custom"])}",
            "最佳效率(3BV/s):",
            "　初级: ${fmtRate(s.bestBbbvPerSec["beginner"])}",
            "　中级: ${fmtRate(s.bestBbbvPerSec["intermediate"])}",
            "　高级: ${fmtRate(s.bestBbbvPerSec["expert"])}",
            "　自定义: ${fmtRate(s.bestBbbvPerSec["custom"])}"
        )
        val contentH = 46f + lines.size * 24f + 10f
        drawDialog(canvas, "游戏统计", contentH) { _, rect ->
            textPaint.reset()
            textPaint.isAntiAlias = true
            textPaint.textSize = 14f
            textPaint.color = theme.colors.text
            textPaint.textAlign = Paint.Align.LEFT
            for (i in lines.indices) {
                canvas.drawText(
                    lines[i], rect.left + 22f,
                    rect.top + 42f + i * 24f, textPaint
                )
            }
        }
    }

    // ---------- 效率曲线 ----------

    /** 最近 N 局 3BV/s 折线（左右切换难度）；空数据显示占位文案 */
    private fun drawHistory(canvas: Canvas) {
        val level = HISTORY_LEVELS[historyLevelIndex]
        val entries = stats.getHistory(level, HISTORY_SAMPLE)
        drawDialog(canvas, "效率曲线（3BV/s）", 300f) { c, rect ->
            textPaint.reset()
            textPaint.isAntiAlias = true
            textPaint.textSize = 15f
            textPaint.color = theme.colors.text
            textPaint.textAlign = Paint.Align.CENTER
            val label = GameEngine.LEVELS[level]?.label ?: "自定义"
            c.drawText("◀ $label ▶", rect.centerX(), rect.top + 52f, textPaint)

            val left = rect.left + 46f
            val top = rect.top + 72f
            val right = rect.right - 18f
            val bottom = rect.bottom - 56f

            if (entries.isEmpty()) {
                textPaint.textSize = 14f
                c.drawText("该难度暂无胜局记录", rect.centerX(), (top + bottom) / 2f, textPaint)
                drawHistoryHint(c, rect)
                return@drawDialog
            }

            val rates = entries.map { it.bbbv.toFloat() / it.time }
            val maxRate = rates.maxOrNull() ?: 1f
            val yMax = (ceil(maxRate * 1.15f * 10f) / 10f).coerceAtLeast(0.5f)

            fun xAt(i: Int): Float =
                if (entries.size == 1) (left + right) / 2f
                else left + (right - left) * i / (entries.size - 1).toFloat()

            fun yAt(v: Float): Float = bottom - (bottom - top) * (v / yMax)

            // 网格线与刻度
            textPaint.textSize = 11f
            textPaint.textAlign = Paint.Align.RIGHT
            for (f in floatArrayOf(0f, 0.5f, 1f)) {
                val v = yMax * f
                val y = yAt(v)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1f
                paint.color = theme.colors.borderRevealed
                c.drawLine(left, y, right, y, paint)
                textPaint.color = theme.colors.text
                c.drawText(String.format("%.1f", v), left - 6f, y + 4f, textPaint)
            }

            // 平均线（虚线）
            val avg = rates.average().toFloat()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f
            paint.color = theme.colors.led
            paint.pathEffect = DashPathEffect(floatArrayOf(6f, 5f), 0f)
            c.drawLine(left, yAt(avg), right, yAt(avg), paint)
            paint.pathEffect = null

            // 折线
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = theme.colors.text
            for (i in 1 until rates.size) {
                c.drawLine(xAt(i - 1), yAt(rates[i - 1]), xAt(i), yAt(rates[i]), paint)
            }

            // 数据点
            paint.style = Paint.Style.FILL
            paint.color = theme.colors.led
            for (i in rates.indices) c.drawCircle(xAt(i), yAt(rates[i]), 3f, paint)

            // 统计行
            textPaint.reset()
            textPaint.isAntiAlias = true
            textPaint.textSize = 13f
            textPaint.color = theme.colors.text
            textPaint.textAlign = Paint.Align.CENTER
            c.drawText(
                "样本 ${entries.size} 局 · 平均 ${String.format("%.2f", avg)} · " +
                    "最佳 ${String.format("%.2f", maxRate)} 3BV/s",
                rect.centerX(), rect.bottom - 32f, textPaint
            )
            drawHistoryHint(c, rect)
        }
    }

    private fun drawHistoryHint(canvas: Canvas, rect: RectF) {
        textPaint.reset()
        textPaint.isAntiAlias = true
        textPaint.textSize = 12f
        textPaint.color = theme.colors.text
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("左右切换难度 · 任意键 / Back 关闭", rect.centerX(), rect.bottom - 12f, textPaint)
    }

    private fun drawAbout(canvas: Canvas) {
        val versionLine = if (appVersion.isEmpty()) "安卓版" else "安卓版 v$appVersion"
        val lines = listOf(
            "经典扫雷游戏",
            versionLine,
            "引擎移植自桌面版",
            "",
            "攻氪 KPA 手柄适配",
            "方向键移动 · B 翻开/和弦 · A 标雷",
            "Start 新游戏 · Select 菜单"
        )
        val contentH = 46f + lines.size * 26f + 10f
        drawDialog(canvas, "关于扫雷", contentH) { _, rect ->
            textPaint.reset()
            textPaint.isAntiAlias = true
            textPaint.textSize = 14f
            textPaint.color = theme.colors.text
            textPaint.textAlign = Paint.Align.CENTER
            for (i in lines.indices) {
                canvas.drawText(
                    lines[i], rect.centerX(),
                    rect.top + 42f + i * 26f, textPaint
                )
            }
        }
    }

    // ---------- 触摸（可选增强：点按=翻开/和弦，长按=标雷，表情=新游戏） ----------

    private val longPressRunnable = Runnable {
        val cell = touchCell
        if (cell != null && canAct()) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            touchPreviewCell = null
            flagAt(cell.first, cell.second)
            touchFlagged = true
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 重开确认框：触摸设备也必须能操作（否则弹窗后只能等手柄/返回键）
        if (overlay == Overlay.ConfirmNewGame) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchConfirmButton = confirmButtonIndex(event.x, event.y)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val pressed = touchConfirmButton
                    touchConfirmButton = null
                    val hit = confirmButtonIndex(event.x, event.y)
                    if (hit != null && hit == pressed) {
                        runPendingRestart(hit == 0)
                    } else {
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    touchConfirmButton = null
                    invalidate()
                    return true
                }
                else -> return true
            }
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                touchFlagged = false
                touchPreviewCell = null
                draggingBoard = false
                // 难度按钮（无覆盖层时；触摸可直接切难度，走与手柄一致的确认保护）
                if (overlay == Overlay.None) {
                    val diffIdx = diffButtonIndex(event.x, event.y)
                    if (diffIdx != null) {
                        touchDiffButton = diffIdx
                        invalidate()
                        return true
                    }
                }
                touchCell = cellAt(event.x, event.y)
                val cell = touchCell
                if (cell == null) {
                    // 表情按钮仅在无覆盖层时可点（否则对话框打开时点顶部会误重开）
                    if (overlay == Overlay.None && faceHit(event.x, event.y)) {
                        facePressed = true
                        invalidate()
                        return true
                    }
                    return false
                }
                // 按下已翻开数字格：预览和弦将影响的周围格
                val data = engine.board[cell.first][cell.second]
                if (data.revealed && data.adjacentMines > 0 && canAct()) {
                    touchPreviewCell = cell.first * engine.cols + cell.second
                    invalidate()
                }
                cursorVisible = false
                postDelayed(longPressRunnable, LONG_PRESS_MS)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // 第二根手指落下：取消当前待定手势，避免多指误触发翻开/标雷/重开
                removeCallbacks(longPressRunnable)
                touchCell = null
                touchPreviewCell = null
                touchDiffButton = null
                draggingBoard = false
                facePressed = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (touchDiffButton != null) {
                    // 手指在按钮排上滑动：实时高亮所在按钮
                    val idx = diffButtonIndex(event.x, event.y)
                    if (idx != touchDiffButton) {
                        touchDiffButton = idx
                        invalidate()
                    }
                    return true
                }
                if (touchCell != null &&
                    (abs(event.x - touchDownX) > 24f || abs(event.y - touchDownY) > 24f)
                ) {
                    removeCallbacks(longPressRunnable)
                    touchCell = null
                    touchPreviewCell = null
                    // 手势转为拖动浏览（超大棋盘的视口滚动）
                    draggingBoard = true
                    dragOffsetX0 = viewOffsetX
                    dragOffsetY0 = viewOffsetY
                    invalidate()
                }
                if (draggingBoard) {
                    val m = metrics()
                    viewOffsetX = dragOffsetX0 - (event.x - touchDownX).toInt()
                    viewOffsetY = dragOffsetY0 - (event.y - touchDownY).toInt()
                    clampOffsets(m, boardViewport())
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (touchDiffButton != null) {
                    val pressed = touchDiffButton
                    touchDiffButton = null
                    if (pressed != null && pressed == diffButtonIndex(event.x, event.y)) {
                        val level = DIFF_BUTTON_LEVELS[pressed]
                        if (level == null) openCustom() else requestRestart { startGame(level) }
                    } else {
                        invalidate()
                    }
                    return true
                }
                if (draggingBoard) {
                    draggingBoard = false
                    return true
                }
                touchPreviewCell = null
                if (facePressed) {
                    facePressed = false
                    // 必须在表情按钮上松开才重开（按住拖走再松开不应触发）；进行中先确认
                    if (overlay == Overlay.None && faceHit(event.x, event.y)) {
                        requestRestart { startGame(engine.currentLevel) }
                    }
                    invalidate()
                    return true
                }
                removeCallbacks(longPressRunnable)
                val cell = touchCell
                touchCell = null
                if (cell != null && !touchFlagged && canAct()) {
                    val (r, c) = cell
                    val d = engine.board[r][c]
                    if (d.revealed && d.adjacentMines > 0) doChord(r, c) else revealAt(r, c)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                touchCell = null
                touchPreviewCell = null
                touchDiffButton = null
                draggingBoard = false
                facePressed = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun cellAt(x: Float, y: Float): Pair<Int, Int>? {
        if (overlay != Overlay.None) return null
        val m = metrics()
        val px = x + viewOffsetX
        val py = y + viewOffsetY
        if (px < m.x || px >= m.x + m.w || py < m.y || py >= m.y + m.h) return null
        val r = ((py - m.y) / m.cell).toInt()
        val c = ((px - m.x) / m.cell).toInt()
        if (r !in 0 until engine.rows || c !in 0 until engine.cols) return null
        return r to c
    }

    private fun faceHit(x: Float, y: Float): Boolean {
        val dx = x - width / 2f
        val dy = y - HUD_H / 2f
        return dx * dx + dy * dy < 26f * 26f
    }
}
