package com.wpiaopiao.saolei

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
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
@SuppressLint("ViewConstructor") // 纯代码创建（Activity setContentView），不需要 XML inflate 构造器
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

    data class Theme(val value: String, val labelRes: Int, val colors: ThemeColors)

    private fun hex(h: String): Int = Color.parseColor(h)

    private fun rgba(a: Int, rgb: String): Int = (a shl 24) or (Color.parseColor(rgb) and 0xFFFFFF)

    private fun mkTheme(
        value: String, labelRes: Int,
        page: String, panel: String, cell: String, revealed: String, active: String,
        text: String, led: String, bLight: String, bDark: String, bRevealed: String,
        overlayA: Int, dialog: String, input: String
    ) = Theme(
        value, labelRes,
        ThemeColors(
            hex(page), hex(panel), hex(cell), hex(revealed), hex(active),
            hex(text), hex(led), hex(bLight), hex(bDark), hex(bRevealed),
            rgba(overlayA, "#000000"), hex(dialog), hex(input)
        )
    )

    // 主题标签走资源（AA-13/M20 i18n），显示时 str(labelRes)
    private val themes = listOf(
        mkTheme("light", R.string.theme_light, "#008080", "#C0C0C0", "#C0C0C0", "#D0D0D0", "#A0A0A0",
            "#333333", "#FF0000", "#FFFFFF", "#808080", "#B0B0B0", 0x80, "#C0C0C0", "#FFFFFF"),
        mkTheme("dark", R.string.theme_dark, "#1A1A2E", "#2D2D2D", "#3A3A3A", "#505050", "#555555",
            "#CCCCCC", "#00FF00", "#555555", "#222222", "#444444", 0xB3, "#2D2D2D", "#444444"),
        mkTheme("xp-silver", R.string.theme_xp_silver, "#ECE9D8", "#ECE9D8", "#ECE9D8", "#F5F3ED", "#D4D0C8",
            "#222222", "#CC0000", "#FFFFFF", "#ACA899", "#D4D0C8", 0x80, "#ECE9D8", "#FFFFFF"),
        mkTheme("vista", R.string.theme_vista, "#005A8C", "#E8EFF7", "#D9E5F2", "#F0F5FB", "#C5D9EB",
            "#1E3B5C", "#CC0000", "#FFFFFF", "#8BA0B8", "#B8CCE0", 0x80, "#E8EFF7", "#FFFFFF"),
        mkTheme("macos", R.string.theme_macos, "#C6C6C8", "#E8E8ED", "#D1D1D6", "#F2F2F7", "#B8B8C0",
            "#1C1C1E", "#FF3B30", "#FFFFFF", "#8E8E93", "#C7C7CC", 0x80, "#E8E8ED", "#FFFFFF"),
        mkTheme("macos-dark", R.string.theme_macos_dark, "#1C1C1E", "#2C2C2E", "#3A3A3C", "#48484A", "#555557",
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
        private const val MIN_CELL = 20f
        /** 仅防御超大屏/超大棋盘的位图开销：KPA 960×640 初级由高度决定（≈56px），不会被此上限截断 */
        private const val MAX_CELL = 96f
        // 底栏状态区（当前时间 + 电量）：与难度按钮同高，居中放在按钮与键位提示之间的空闲区
        private const val STATUS_PANE_H = 30f
        private const val STATUS_PAD_H = 12f
        private const val STATUS_CLOCK_SIZE = 14f
        private const val STATUS_PCT_SIZE = 13f
        private const val STATUS_CONTENT_GAP = 12f
        private const val STATUS_ICON_GAP = 6f
        private const val BATTERY_BODY_W = 22f
        private const val BATTERY_NUB_W = 3f
        private const val BATTERY_ICON_H = 13f
        /** 表情按钮半径：绘制与触摸命中必须用同一个值（避免不可见热区） */
        private const val FACE_RADIUS = 22f
        /** 数字与符号占格子边长比例（小格子可读性） */
        private const val NUMBER_TEXT_RATIO = 0.60f
        private const val REVEAL_EMOJI_RATIO = 0.62f
        /** 单次操作新翻开的格子数超过此值时不做逐格缩放动画（大洪泛最坏帧） */
        private const val MAX_REVEAL_ANIM = 256
        // 和弦失败 shake：时长 350ms，双轴错位抖动（仅周围未翻开的格子）
        // 与桌面 `@keyframes shake` 0.3s、ShakeAnimation.DURATION_MS 对齐（用户反馈原 350ms/高频抖动像抽搐）
        private const val SHAKE_MS = 300L
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
        // 底部难度按钮：对应难度（null = 自定义）
        private val DIFF_BUTTON_LEVELS = listOf<String?>("beginner", "intermediate", "expert", null)
        private val DIFF_BUTTON_LABEL_RES = intArrayOf(
            R.string.level_beginner, R.string.level_intermediate,
            R.string.level_expert, R.string.level_custom
        )
        // 无障碍自定义动作 ID（避免与系统 ACTION_* 冲突）
        private const val A11Y_ACTION_NEW_GAME = 0x0101
        private const val A11Y_ACTION_MENU = 0x0102
        private const val A11Y_ACTION_FLAG = 0x0103
        private const val A11Y_ACTION_ACTIVATE = 0x0104

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
    // 棋盘格内容专用画笔：常量属性（typeface/对齐/抗锯齿）在这里配置一次，
    // 绘制时只改 textSize 与 color —— 整盘重建时每格 reset() 曾是明显开销
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = MONO_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val cellEmojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    // 绘制热路径复用对象：避免每帧每格分配（50×50 自定义局每帧可达数千次临时对象）
    private val dirtyRect = android.graphics.Rect()
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
    private var prevState = "ready"

    // 无猜首点的后台生成：generating 期间忽略棋盘操作，generationSeq 用于作废过期结果
    private var generating = false
    private var generationSeq = 0

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
    /** true = 当前确认框是「退出游戏」，false = 「重新开始」（L14） */
    private var confirmExit = false
    private var touchConfirmButton: Int? = null

    // 运行时读到的应用版本号（About 显示，避免硬编码）
    private var appVersion = ""

    // 窗口安全区（刘海/挖孔/系统栏 insets，像素）：由 MainActivity 同步（L8）
    private var safeLeft = 0
    private var safeTop = 0
    private var safeRight = 0
    private var safeBottom = 0

    // 屏幕常亮回调（仅对局中常亮）与退出游戏回调（L14 菜单退出入口）
    private var keepScreenOnCallback: ((Boolean) -> Unit)? = null
    var onExitRequested: (() -> Unit)? = null

    // 无障碍：最近一次播报内容（避免重复播报）
    private var lastA11yAnnouncement = ""

    // 覆盖层可点击区域：绘制时登记（后登记者优先，见 OverlayHitRegistry）
    private val overlayHits = OverlayHitRegistry()
    private var overlayPressedIndex: Int? = null

    // 胜利"新纪录"横幅
    private var recordBanner = ""
    private var recordBannerStart = 0L

    // 底栏状态区（当前时间 + 电量）：数据由 MainActivity 的 StatusWatcher 推送（见 setStatusInfo）
    private var statusClock = ""
    private var statusBatteryPercent: Int? = null
    private var statusCharging = false

    // 效率曲线：当前查看的难度下标（HISTORY_LEVELS 顺序）
    private var historyLevelIndex = 0

    // 底部难度按钮：矩形布局复用（绘制与触摸命中共用），touchDiffButton 为触摸按下态
    private val diffBtnRects = Array(4) { RectF() }
    private var touchDiffButton: Int? = null
    // 难度按钮标签宽度（含内边距）：只与常量文本/字号有关，首次布局后缓存
    private val diffBtnLabelWidths: FloatArray by lazy {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = MONO_BOLD
            textSize = 14f
        }
        FloatArray(DIFF_BUTTON_LABEL_RES.size) { i -> p.measureText(str(DIFF_BUTTON_LABEL_RES[i])) + 22f }
    }
    // 状态区宽度测量复用（绘制热路径禁止每帧分配 Paint）
    private val statusMeasurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = MONO_BOLD }

    // 触摸按下已翻开数字格时的邻居预览（和弦示意，与桌面版按下高亮等价）
    private var touchPreviewCell: Int? = null

    // 大棋盘视口滚动偏移（像素，0 = 不滚动）；触摸拖动可浏览
    private var viewOffsetX = 0
    private var viewOffsetY = 0
    private var draggingBoard = false
    private var dragOffsetX0 = 0
    private var dragOffsetY0 = 0
    // 拖动锚点：进入拖动那一刻的触点（越过阈值后重锚，避免内容跳变）
    private var dragAnchorX = 0f
    private var dragAnchorY = 0f

    // 棋盘静态层位图缓存：非动画帧只贴图 + 重绘动态格
    private var boardCache: Bitmap? = null
    /** 静态层需要整层重建（尺寸/主题/新局） */
    private var cacheFullRebuild = true
    /** 静态层待局部重绘的格子（r*cols+c），内容为不透明整格，直接重绘即可 */
    private val cacheDirty = mutableSetOf<Int>()
    private var boardCacheLeft = 0f
    private var boardCacheTop = 0f

    // 触摸
    private var touchCell: Pair<Int, Int>? = null
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchFlagged = false

    // ---------- 计时器（AA-8/M7：activeMs 不封顶进统计，LED 显示封顶 999） ----------

    private val clock = GameClock()
    private var timerScheduled = false

    private fun shouldCountTime(): Boolean =
        appActive && engine.state == "playing" && overlay == Overlay.None

    /** 把真实用时同步进 engine.timer（LED 显示值，封顶 999）。 */
    private fun syncTimerValue() {
        engine.timer = clock.displaySeconds(SystemClock.elapsedRealtime())
    }

    private val timerRunnable = object : Runnable {
        override fun run() {
            timerScheduled = false
            if (!shouldCountTime()) {
                clock.pause(SystemClock.elapsedRealtime())
                return
            }
            syncTimerValue()
            // 计时 LED 与表情都在 HUD 内：只失效 HUD 区域，避免每秒整屏重绘
            dirtyRect.set(0, 0, width, safeTop + HUD_H.toInt() + 4)
            invalidate(dirtyRect)
            // 对齐下一个整秒边界再排程，单次 postDelayed 延迟不会累积
            scheduleTimerTick()
        }
    }

    private fun scheduleTimerTick() {
        if (timerScheduled || !shouldCountTime()) return
        val phase = clock.activeMs(SystemClock.elapsedRealtime()) % 1000L
        postDelayed(timerRunnable, 1000L - phase)
        timerScheduled = true
    }

    private fun manageTimer() {
        val now = SystemClock.elapsedRealtime()
        if (shouldCountTime()) {
            if (!clock.running) clock.resume(now)
            scheduleTimerTick()
        } else {
            clock.pause(now)
            removeCallbacks(timerRunnable)
            timerScheduled = false
        }
        refreshKeepScreenOn()
    }

    /** 状态 ready→playing 时调用：重置并开始计时。 */
    private fun startRoundClock() {
        clock.startRound(SystemClock.elapsedRealtime())
    }

    /** 屏幕常亮只在「对局进行中且无覆盖层」时开启（L8/AA-10）。 */
    fun setKeepScreenOnCallback(cb: (Boolean) -> Unit) {
        keepScreenOnCallback = cb
    }

    fun refreshKeepScreenOn() {
        keepScreenOnCallback?.invoke(appActive && engine.state == "playing" && overlay == Overlay.None)
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
        val old = cursor
        cursor = r to c
        cursorVisible = true
        val offX = viewOffsetX
        val offY = viewOffsetY
        ensureCursorVisible(metrics())
        if (offX != viewOffsetX || offY != viewOffsetY) {
            invalidate() // 视口滚动了：整屏内容都变了
            return
        }
        // 只失效新旧两个光标格：长按方向键 25Hz 移动时不再整屏重绘
        if (old != cursor) invalidateCell(old.first, old.second)
        invalidateCell(r, c)
    }

    /** 只失效单个格子所在区域（外扩 2px，覆盖光标描边） */
    private fun invalidateCell(row: Int, col: Int) {
        if (row !in 0 until engine.rows || col !in 0 until engine.cols) return
        val m = metrics()
        val left = (m.x + col * m.cell - viewOffsetX).toInt() - 2
        val top = (m.y + row * m.cell - viewOffsetY).toInt() - 2
        val size = m.cell.toInt() + 4
        dirtyRect.set(left, top, left + size, top + size)
        invalidate(dirtyRect)
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

    override fun onDetachedFromWindow() {
        // L8：View 意外 detach 时清理全部回调并作废进行中的后台生成，避免泄漏/迟到结果
        removeCallbacks(timerRunnable)
        removeCallbacks(moveDelay)
        removeCallbacks(moveRepeat)
        removeCallbacks(longPressRunnable)
        cancelGeneration()
        super.onDetachedFromWindow()
    }

    /** 尺寸变化（含分屏/旋转）后棋盘布局与位图缓存必须重建 */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        invalidateBoardCache()
    }

    /** 刘海/挖孔安全区（像素）：影响 HUD、底栏、棋盘视口与对话框布局（L8）。 */
    fun setSafeInsets(left: Int, top: Int, right: Int, bottom: Int) {
        if (left == safeLeft && top == safeTop && right == safeRight && bottom == safeBottom) return
        safeLeft = left
        safeTop = top
        safeRight = right
        safeBottom = bottom
        metricsCache = null
        invalidateBoardCache()
        invalidate()
    }

    // ---------- 棋盘位图缓存 ----------

    /**
     * 整层重建：尺寸/主题/难度变化时调用（下次绘制重绘全部格子）。
     * 日常操作走 [consumeDirty] 的局部重绘，避免每次点击都光栅化整盘
     * （50×50 一整盘约 2500 格 × 数条 Skia 指令）。
     */
    private fun invalidateBoardCache() {
        cacheFullRebuild = true
        cacheDirty.clear()
    }

    /**
     * 消费引擎的脏格（core 现以 Int 编码 row*cols+col）：新翻开的格子加入翻开动画，
     * 其余交给静态层局部重绘。整层重建待办时（新局/尺寸变化后的首帧）只需登记动画。
     */
    private fun consumeDirty() {
        val dirty = engine.consumeDirty()
        if (dirty.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        val cols = engine.cols
        // 首点洪泛可能一次翻开上千格：逐格缩放动画会给后续每帧塞入上千格重绘，
        // 超过阈值时跳过动画（格子内容仍正确显示），避免大棋盘最坏帧
        var opened = 0
        for (idx in dirty) {
            val cell = engine.board[idx / cols][idx % cols]
            if (cell.revealed && !cell.mine) opened++
        }
        val animateOpen = opened <= MAX_REVEAL_ANIM
        for (idx in dirty) {
            val r = idx / cols
            val c = idx % cols
            val cell = engine.board[r][c]
            if (animateOpen && cell.revealed && !cell.mine) revealedAnim[idx] = now
            if (!cacheFullRebuild) cacheDirty.add(idx)
        }
    }

    // ---------- 主题 / 静音 / 文案映射 ----------

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

    /**
     * 底栏状态区数据（当前时间 + 电量），由 MainActivity 的 StatusWatcher 在
     * 系统时间（分钟级）/电池变化时推送。只重绘底栏，不触发棋盘位图缓存重建。
     */
    fun setStatusInfo(clock: String, batteryPercent: Int?, charging: Boolean) {
        if (clock == statusClock && batteryPercent == statusBatteryPercent && charging == statusCharging) return
        statusClock = clock
        statusBatteryPercent = batteryPercent
        statusCharging = charging
        if (width > 0 && height > 0) {
            dirtyRect.set(0, (height - safeBottom - BOTTOM_H).toInt(), width, height)
            invalidate(dirtyRect)
        } else {
            invalidate()
        }
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

    /** 难度 id → 本地化文案（core LEVELS.label 保留，显示层走资源；AA-13/M20）。 */
    private fun levelLabel(level: String): String = str(
        when (level) {
            "beginner" -> R.string.level_beginner
            "intermediate" -> R.string.level_intermediate
            "expert" -> R.string.level_expert
            else -> R.string.level_custom
        }
    )

    /** 键位动作 id → 本地化文案。 */
    private fun actionLabel(action: String): String = str(
        when (action) {
            "moveUp" -> R.string.action_move_up
            "moveDown" -> R.string.action_move_down
            "moveLeft" -> R.string.action_move_left
            "moveRight" -> R.string.action_move_right
            "activate" -> R.string.action_activate
            "flag" -> R.string.action_flag
            "newGame" -> R.string.action_new_game
            else -> R.string.action_menu
        }
    )

    // ---------- 新游戏 / 状态同步 ----------

    private fun canAct(): Boolean = (engine.state == "ready" || engine.state == "playing") && !generating

    private fun startGame(level: String) {
        // 对齐桌面版 app.js：custom 难度恢复上次的自定义配置；
        // 尚无配置时直接忽略（防御，避免 engine.init("custom") 抛未知难度异常）
        if (level == "custom") {
            val cfg = engine.currentCustom ?: return
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
        cancelGeneration()
        cursor = 0 to 0
        cursorVisible = false
        clearHeldMoves()
        overlay = Overlay.None
        pendingRestart = null
        confirmExit = false
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
        clock.reset()
        engine.timer = 0
        invalidateBoardCache()
        manageTimer()
        invalidate()
        updateAccessibilityDescription("ready")
    }

    /** 状态迁移：统计、胜利自动补旗/展开、计时（对齐 ui.js onStateChange） */
    private fun syncState() {
        val s = engine.state
        if (s != prevState) {
            // ready → 任意非 ready 状态都计入总局数：
            // 覆盖「一次操作直接 ready→won」的路径，避免胜率统计出现 >100% 的口径洞
            if (s == "playing" && prevState == "ready") {
                stats.onGameStart(engine.currentLevel, engine.currentCustom)
                startRoundClock()
            }
            if (s == "won") {
                engine.autoFlagRemainingMines()
                engine.revealAllSafe()
                finishRoundStats(won = true)
            }
            if (s == "lost") {
                finishRoundStats(won = false)
            }
            prevState = s
            updateAccessibilityDescription(s)
            announceStateChange(s)
        }
        // 只重绘本次变化的格子（引擎 dirty），不再每次操作都光栅化整盘
        consumeDirty()
        manageTimer()
        invalidate()
    }

    /** 终局：暂停计时器并用真实时长（不封顶）记账（AA-8/M7）。 */
    private fun finishRoundStats(won: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!clock.running && clock.durationMs(now) == 0L) {
            // ready→won 瞬胜（0 毫秒）：补一次开局重置，保证统计口径一致
            clock.startRound(now)
        }
        clock.pause(now)
        val durationMs = clock.durationMs(now)
        val pausedMs = clock.pausedMs(now)
        // AA-8/M7：engine.timer 是 LED 显示口径（封顶 999），真实耗时走 durationMs；
        // 统计规则（0 秒守卫/3BV/s）由 core 按 durationMs 判定（与桌面端同规则）。
        engine.timer = clock.displaySeconds(now)
        if (won) {
            maybeShowRecordBanner(engine.timer)
            stats.onGameWin(engine.timer, engine.bbbv, durationMs, pausedMs, engine.wonByFlags)
        } else {
            stats.onGameLose(engine.timer, durationMs, pausedMs)
        }
        removeCallbacks(timerRunnable)
        timerScheduled = false
    }

    /** 胜利时若刷新了该桶的最快时间，弹「新纪录」横幅（必须在 onGameWin 之前比较）。 */
    private fun maybeShowRecordBanner(seconds: Int) {
        if (seconds <= 0) return // 0 秒纪录无意义（M7）
        val bucket = stats.lastBucket ?: StatsManager.bucketOf(engine.currentLevel, engine.currentCustom)
        val prevBest = stats.bestTimeOf(bucket)
        if (prevBest != null && seconds >= prevBest) return
        recordBanner = str(
            R.string.record_banner, levelLabel(engine.currentLevel), GameText.formatTime(seconds)
        )
        recordBannerStart = SystemClock.uptimeMillis()
    }

    // ---------- 状态快照（M19/AA-5：进程回收/配置变更不丢局） ----------

    /** 当前是否值得存档（仅进行中的对局）。 */
    fun currentSnapshot(): GameEngine.Snapshot? =
        if (engine.state == "playing") engine.snapshot() else null

    /** 从存档恢复（不重跑首点布雷）。成功返回 true。 */
    fun restoreSnapshot(snapshot: GameEngine.Snapshot): Boolean = try {
        engine.restore(snapshot)
        // 恢复的对局没有经过 onGameStart（避免重复计总局数），这里只把统计桶指向当前局面，
        // 否则终局 onGameWin 找不到 bucket，最快时间/3BV/s/history 会漏记
        stats.selectBucket(snapshot.currentLevel, snapshot.currentCustom)
        cancelGeneration()
        cursor = 0 to 0
        cursorVisible = false
        overlay = Overlay.None
        pendingRestart = null
        confirmExit = false
        revealedAnim.clear()
        shakeCells.clear()
        shakeStart = 0L
        viewOffsetX = 0
        viewOffsetY = 0
        touchCell = null
        touchPreviewCell = null
        draggingBoard = false
        facePressed = false
        prevState = engine.state
        clock.restoreActive(snapshot.timer.toLong() * 1000L)
        invalidateBoardCache()
        manageTimer()
        invalidate()
        updateAccessibilityDescription(engine.state)
        true
    } catch (e: IllegalArgumentException) {
        false
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
        // 无效翻开（已翻开/已插旗）不播音效，避免"点了没反应却有声音"
        val target = engine.board[r][c]
        if (target.revealed || target.flagged) return
        // H2：只要雷位还没生成就走后台，不再要求 state=="ready"
        //（先标旗会把 state 改成 playing，旧判定会让 50×50 无猜首点冻结 ≤800ms）
        if (engine.noGuess && engine.hasPendingMines()) {
            startAsyncFirstReveal(r, c)
            return
        }
        engine.reveal(r, c)
        syncState()
        when {
            engine.state == "lost" -> sound.play("explosion")
            engine.state == "won" -> sound.play("win")
            else -> sound.play("click")
        }
    }

    /**
     * 后台生成无猜雷局：worker 线程只调用无状态的 [GameEngine.planMines]，
     * 结果回到 UI 线程经 [GameEngine.applyPlanAndReveal] 应用（含首点/几何校验）。
     * 生成期间忽略棋盘操作；新游戏/切难度会作废进行中的生成（generationSeq）。
     */
    private fun startAsyncFirstReveal(r: Int, c: Int) {
        val seq = ++generationSeq
        generating = true
        invalidate()
        val rows = engine.rows
        val cols = engine.cols
        val mines = engine.totalMines
        Thread {
            val plan = try {
                GameEngine.planMines(rows, cols, mines, r, c, true)
            } catch (e: Throwable) {
                null
            }
            post {
                if (seq != generationSeq) return@post // 期间已重开/切难度：丢弃结果
                generating = false
                val applied = plan != null && engine.applyPlanAndReveal(r, c, plan)
                if (!applied) {
                    // 后台生成失败（异常/校验失败/竞态）：回退到同步路径，保证功能可用
                    engine.reveal(r, c)
                }
                syncState()
                when {
                    engine.state == "lost" -> sound.play("explosion")
                    engine.state == "won" -> sound.play("win")
                    else -> sound.play("click")
                }
                invalidate()
            }
        }.start()
    }

    /** 作废进行中的后台生成（新游戏/切难度时调用） */
    private fun cancelGeneration() {
        if (!generating) return
        generationSeq++
        generating = false
    }

    private fun flagAt(r: Int, c: Int) {
        // 已翻开的格子不能插旗：无效操作不播音效
        if (engine.board[r][c].revealed) return
        engine.toggleFlag(r, c)
        syncState()
        if (engine.state == "won") sound.play("win") else sound.play("flag")
    }

    private fun doChord(r: Int, c: Int) {
        val did = engine.chord(r, c)
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
        // 尊重系统「动画时长缩放 = 关闭」/无障碍减少动态效果：不播抖动
        if (!ValueAnimator.areAnimatorsEnabled()) return
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
        confirmExit = false
        touchConfirmButton = null
        resetOverlayHits()
        manageTimer()
        invalidate()
    }

    // ---------- 覆盖层触摸命中 ----------

    /** 覆盖层绘制前调用：清空上一帧登记的命中区域与按下态 */
    private fun resetOverlayHits() {
        overlayHits.reset()
        overlayPressedIndex = null
    }

    /**
     * 登记一个可点击区域（在绘制该区域的同一处调用，保证触摸命中与绘制几何一致）。
     * 约定：**后登记者优先**——子元素（如自定义雷区的 ◀ ▶ 按钮）必须在父行之后登记，
     * 否则会先命中父矩形（AA-4/M23）。见 [OverlayHitRegistry]。
     */
    private fun addOverlayHit(rect: RectF, action: (() -> Unit)?) {
        overlayHits.add(rect.left, rect.top, rect.right, rect.bottom, action)
    }

    private fun overlayHitAt(x: Float, y: Float): Int? = overlayHits.hitAt(x, y)

    // ---------- 重开/退出保护 ----------

    /**
     * 对局进行中（已有进展）时先弹确认框再执行，避免掌机 Start 键 / 表情按钮误触丢局；
     * 未开局（ready）或已终局（won/lost）时直接执行，不打扰。
     */
    private fun requestRestart(action: () -> Unit) {
        if (engine.state == "playing") {
            pendingRestart = action
            confirmExit = false
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

    /** L14：菜单「退出游戏」——进行中先确认，用户主动退出不丢局。 */
    private fun requestExit() {
        if (engine.state == "playing") {
            pendingRestart = { onExitRequested?.invoke() }
            confirmExit = true
            confirmSelected = 1
            touchConfirmButton = null
            overlay = Overlay.ConfirmNewGame
            clearHeldMoves()
            manageTimer()
            invalidate()
        } else {
            onExitRequested?.invoke()
        }
    }

    private fun runPendingRestart(confirmed: Boolean) {
        val act = pendingRestart
        pendingRestart = null
        val wasExit = confirmExit
        confirmExit = false
        closeOverlay()
        if (confirmed && act != null) act()
        // 取消退出时 confirmExit 已复位；取消重开同理（不需要额外动作）
        if (!confirmed && wasExit) invalidate()
    }

    private fun menuItems(): List<MenuItem> {
        val items = mutableListOf<MenuItem>()
        items += MenuItem(str(R.string.menu_new_game)) {
            requestRestart { startGame(engine.currentLevel) }
        }
        for (lvl in listOf("beginner", "intermediate", "expert")) {
            val cfg = GameEngine.LEVELS.getValue(lvl)
            items += MenuItem(
                str(R.string.menu_difficulty, levelLabel(lvl), cfg.rows, cfg.cols, cfg.mines),
                checked = engine.currentLevel == lvl && engine.currentCustom == null
            ) { requestRestart { startGame(lvl) } }
        }
        items += MenuItem(str(R.string.menu_custom)) { openCustom() }
        for (t in themes) {
            items += MenuItem(
                str(R.string.menu_theme, str(t.labelRes)),
                checked = theme.value == t.value
            ) { setThemeValue(t.value) }
        }
        items += MenuItem(str(if (muted) R.string.menu_mute_on else R.string.menu_mute_off)) { toggleMute() }
        items += MenuItem(
            str(if (engine.noGuess) R.string.menu_no_guess_on else R.string.menu_no_guess_off)
        ) { toggleNoGuess() }
        items += MenuItem(str(R.string.menu_bindings)) { openBindings() }
        items += MenuItem(str(R.string.menu_stats)) {
            overlay = Overlay.Stats
            manageTimer()
        }
        items += MenuItem(str(R.string.menu_history)) { openHistory() }
        items += MenuItem(str(R.string.menu_about)) {
            overlay = Overlay.About
            manageTimer()
        }
        // L14：显式退出入口（掌机上 Back 恒被消费，没有它就退不出去）
        items += MenuItem(str(R.string.menu_exit)) { requestExit() }
        return items
    }

    private fun bindRows(): List<BindRow> {
        val rows = KeyBindings.ACTIONS.map { action ->
            BindRow(BindRowKind.ACTION, action, actionLabel(action))
        }
        return rows + BindRow(BindRowKind.RESET, null, str(R.string.bind_reset)) +
            BindRow(BindRowKind.CLOSE, null, str(R.string.bind_close))
    }

    /** 激活键位页某一行（手柄 A/B 与触摸点按共用同一逻辑） */
    private fun activateBindRow(row: BindRow) {
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

    /** 效率曲线切换难度（手柄左右键与触摸左右箭头共用） */
    private fun shiftHistoryLevel(delta: Int) {
        historyLevelIndex = (historyLevelIndex + HISTORY_LEVELS.size + delta) % HISTORY_LEVELS.size
    }

    // ---------- 手柄按键（MainActivity 转发） ----------

    fun handleKeyDown(code: Int, isRepeat: Boolean = false): Boolean {
        lastKeyInfo = "${KeyNames.nameOf(code)} ($code)"
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
                    bindError = str(R.string.bind_conflict, KeyNames.nameOf(code), actionLabel(conflict))
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
                    action == "activate" || action == "flag" -> activateBindRow(rows[bindSelected])
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
                    action == "moveLeft" || action == "moveUp" -> shiftHistoryLevel(-1)
                    action == "moveRight" || action == "moveDown" -> shiftHistoryLevel(1)
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
            customRows !in 9..50 -> str(R.string.custom_err_rows)
            customCols !in 9..50 -> str(R.string.custom_err_cols)
            customMines !in 1..maxMines -> str(R.string.custom_err_mines, maxMines)
            else -> ""
        }
        if (customError.isEmpty()) {
            // M12 同源（桌面）：自定义「确定」也走重开确认，避免直接丢弃进行中对局
            requestRestart { startCustom() }
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
        val view = boardViewport()
        val cell = min(
            (view.width() - 2 * PAD) / engine.cols,
            (view.height() - 3 * PAD) / engine.rows
        ).coerceIn(MIN_CELL, MAX_CELL)
        val boardW = cell * engine.cols
        val boardH = cell * engine.rows
        // 装得下就居中；装不下则从视口左上留白处开始，由视口滚动浏览
        val boardX = if (boardW + 2 * PAD <= view.width()) {
            view.left + (view.width() - boardW) / 2f
        } else {
            view.left + PAD
        }
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

    /** 棋盘可视区（HUD 下沿到底栏上沿，扣除刘海/系统栏安全区），滚动裁剪用 */
    private val viewportRect = RectF()

    /** 棋盘可用视口；复用同一 RectF，避免每帧分配 */
    private fun boardViewport(): RectF {
        viewportRect.set(
            safeLeft.toFloat(),
            safeTop + HUD_H,
            width.toFloat() - safeRight,
            height.toFloat() - safeBottom - BOTTOM_H
        )
        return viewportRect
    }

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
        // 整屏画"游戏窗口"面板色：铺满屏幕，不再露出与主题无关的大面积底色（原 bgPage 绿色一圈）
        canvas.drawColor(theme.colors.bgPanel)
        drawHud(canvas)
        drawBoard(canvas, m, now)
        drawBottomBar(canvas)
        drawRecordBanner(canvas, now)
        if (overlay != Overlay.None) resetOverlayHits()
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
        } else if (bindListening != null) {
            // 键位录制行的闪烁需要时钟驱动（低频即可）；覆盖层打开时计时器已暂停
            postInvalidateDelayed(200L)
        }
    }

    /** View 本身没有 getString：统一走 Context，便于 i18n（AA-13/M20）。 */
    private fun str(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun textBaseline(cy: Float, p: Paint): Float = cy - (p.ascent() + p.descent()) / 2f

    /**
     * 统一设置 UI 文本画笔：每次都用完整样式，避免继承上一次绘制残留的 textSize/typeface
     * （AA-3/M22 的根因就是底栏沿用了 HUD 的 28f）。
     */
    private fun applyUiText(size: Float, color: Int, align: Paint.Align = Paint.Align.LEFT, mono: Boolean = false) {
        textPaint.reset()
        textPaint.isAntiAlias = true
        if (mono) textPaint.typeface = MONO_BOLD
        textPaint.textSize = size
        textPaint.color = color
        textPaint.textAlign = align
    }

    /** LED 文本：超过 3 位（如 -1000/2125）时按可用宽度自动缩小字号，避免撑破 92px LED（L11）。 */
    private fun drawLedText(canvas: Canvas, text: String, led: RectF, color: Int) {
        applyUiText(28f, color, Paint.Align.CENTER, mono = true)
        val maxW = led.width() - 10f
        val textW = textPaint.measureText(text)
        if (textW > maxW && textW > 0f) {
            textPaint.textSize = (28f * maxW / textW).coerceAtLeast(12f)
        }
        canvas.drawText(text, led.centerX(), textBaseline(led.centerY(), textPaint), textPaint)
    }

    /** 斜面宽度随格子缩放：小格子（50×50）用细边，避免吃掉过多视觉面积 */
    private fun bevelWidth(cell: Float): Float = (cell * 0.08f).coerceIn(1f, 3f)

    /**
     * 画 3D 斜面：全部描边**向内缩进 w/2**，保证笔画完全落在本矩形内（不越界到相邻格）。
     * 这样静态位图的增量重绘不会擦/盖邻居的边线（AA-14/M16 渲染正确性）；
     * 相邻格各自向内画上下/左右边线，视觉上仍是经典双色斜面。
     */
    private fun drawBevel(canvas: Canvas, rect: RectF, w: Float, light: Int, dark: Int) {
        val h = w / 2f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = w
        paint.color = light
        canvas.drawLine(rect.left + h, rect.top + h, rect.right - h, rect.top + h, paint)
        canvas.drawLine(rect.left + h, rect.top + h, rect.left + h, rect.bottom - h, paint)
        paint.color = dark
        canvas.drawLine(rect.right - h, rect.top + h, rect.right - h, rect.bottom - h, paint)
        canvas.drawLine(rect.left + h, rect.bottom - h, rect.right - h, rect.bottom - h, paint)
    }

    private fun drawHud(canvas: Canvas) {
        val w = width.toFloat()
        val rect = tmpRect
        rect.set(0f, 0f, w, safeTop + HUD_H)
        paint.style = Paint.Style.FILL
        paint.color = theme.colors.bgPanel
        canvas.drawRect(rect, paint)
        drawBevel(canvas, rect, 2f, theme.colors.borderDark, theme.colors.borderLight)

        val ledW = 92f
        val ledH = 40f
        val ledTop = safeTop + (HUD_H - ledH) / 2f
        // 雷数 LED
        val mineLed = tmpRect2
        mineLed.set(safeLeft + PAD + 2f, ledTop, safeLeft + PAD + 2f + ledW, ledTop + ledH)
        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK
        canvas.drawRect(mineLed, paint)
        drawLedText(canvas, GameText.formatCounter(engine.totalMines - engine.flagCount), mineLed, theme.colors.led)

        // 表情（新游戏按钮）：半径与 faceHit 命中半径共用常量
        val hudCenterX = (safeLeft + (w - safeRight)) / 2f
        val hudCenterY = safeTop + HUD_H / 2f
        drawFace(canvas, hudCenterX, hudCenterY, FACE_RADIUS)

        // 计时 LED（显示值封顶 999，真实耗时另走统计，AA-8/M7）
        val timerLed = tmpRect2
        timerLed.set(w - safeRight - PAD - 2f - ledW, ledTop, w - safeRight - PAD - 2f, ledTop + ledH)
        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK
        canvas.drawRect(timerLed, paint)
        drawLedText(canvas, GameText.formatCounter(engine.timer), timerLed, theme.colors.led)

        // 生成中 / 无猜降级提示（互斥，生成中优先）：让玩家知道后台正在做什么
        val hint = when {
            generating -> str(R.string.hud_generating)
            engine.noGuessFallback -> str(R.string.hud_no_guess_warning)
            else -> null
        }
        if (hint != null) {
            val dark = theme.value == "dark" || theme.value == "macos-dark"
            val hintColor = if (generating) {
                if (dark) 0xFF9CC7EC.toInt() else 0xFF1F4E79.toInt()
            } else {
                if (dark) 0xFFFFC46B.toInt() else 0xFF8A4B00.toInt()
            }
            applyUiText(12f, hintColor, Paint.Align.LEFT, mono = true)
            canvas.drawText(
                hint,
                mineLed.right + 14f,
                textBaseline(hudCenterY, textPaint), textPaint
            )
        }
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
        drawBoardFrame(canvas, m)

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

    /**
     * 棋盘外框：2px 凹陷斜面（上/左暗、下/右亮），对齐桌面版 `#board` 的 border。
     * 整屏改为面板色后，这圈外框让棋盘与窗口底之间仍有经典"雷区"层次。
     */
    private fun drawBoardFrame(canvas: Canvas, m: Metrics) {
        val frame = tmpRect
        frame.set(m.x - 3f, m.y - 3f, m.x + m.w + 3f, m.y + m.h + 3f)
        drawBevel(canvas, frame, 2f, theme.colors.borderDark, theme.colors.borderLight)
    }

    /** 垂直滚动指示条：仅内容超出视口时显示，提示视口外还有内容 */
    private fun drawScrollIndicator(canvas: Canvas, m: Metrics, view: RectF) {
        val contentH = m.h + 2 * PAD
        if (contentH <= view.height()) return
        val trackX = width - safeRight - 7f
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
            // 尺寸变化必须整层重建。旧位图不显式 recycle：它可能刚被上一帧的硬件画布
            // 上传为纹理，立即回收会抛 "trying to use a recycled bitmap"（API 26+ 交给 GC 更稳）
            cacheFullRebuild = true
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { boardCache = it }
        }
        boardCacheLeft = left.toFloat()
        boardCacheTop = top.toFloat()
        if (cacheFullRebuild) {
            val c = Canvas(bmp)
            c.translate(-left.toFloat(), -top.toFloat())
            for (r in 0 until engine.rows) {
                for (col in 0 until engine.cols) drawCell(c, m, r, col, now, animate = false)
            }
            cacheFullRebuild = false
            cacheDirty.clear()
        } else if (cacheDirty.isNotEmpty()) {
            // 只重绘变化格：格子内容不透明且不重叠，直接覆盖绘制即可
            val c = Canvas(bmp)
            c.translate(-left.toFloat(), -top.toFloat())
            val cols = engine.cols
            for (idx in cacheDirty) {
                drawCell(c, m, idx / cols, idx % cols, now, animate = false)
            }
            cacheDirty.clear()
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

        // 和弦失败抖动：水平轻晃（0.3s / ±3px 衰减），与桌面 @keyframes shake 对齐。
        // 旧实现是 25Hz/18Hz 的双轴正弦 + 7px 位移，真机上看起来像抽搐；现改为关键帧插值。
        var shakeDx = 0f
        if (animate && shakeStart != 0L && shakeCells.contains(idx)) {
            val elapsed = now - shakeStart
            if (elapsed < SHAKE_MS) {
                shakeDx = ShakeAnimation.horizontalOffset(elapsed / SHAKE_MS.toFloat())
            }
        }
        var animElapsed: Long? = null
        if (animate && data.revealed) {
            animElapsed = revealedAnim[idx]?.let { now - it }
        }

        val shaking = shakeDx != 0f
        if (shaking) {
            // 先擦掉静态层原位置的格子（外扩 1px 覆盖 bevel 描边），再画偏移后的格子；
            // 否则静态层会残留一个"鬼影"
            paint.style = Paint.Style.FILL
            paint.color = theme.colors.bgPanel
            tmpRect2.set(rect.left - 1f, rect.top - 1f, rect.right + 1f, rect.bottom + 1f)
            canvas.drawRect(tmpRect2, paint)
            canvas.save()
            canvas.translate(shakeDx, 0f)
        }

        if (!data.revealed) {
            paint.style = Paint.Style.FILL
            paint.color = theme.colors.bgCell
            canvas.drawRect(rect, paint)
            drawBevel(
                canvas, rect, bevelWidth(m.cell),
                theme.colors.borderLight, theme.colors.borderDark
            )
            when {
                data.flagged -> {
                    drawEmoji(canvas, "🚩", rect, m.cell * REVEAL_EMOJI_RATIO)
                    if (data.wrongFlag) drawWrongFlagCross(canvas, rect, m.cell)
                }
                data.questioned -> drawEmoji(canvas, "❓", rect, m.cell * REVEAL_EMOJI_RATIO)
            }
        } else {
            paint.style = Paint.Style.FILL
            paint.color = if (data.exploded) Color.RED else theme.colors.bgRevealed
            canvas.drawRect(rect, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            paint.color = theme.colors.borderRevealed
            // 内缩 0.5px：描边完全落在本格内，增量重绘不会覆盖邻格（AA-14）
            tmpRect2.set(rect.left + 0.5f, rect.top + 0.5f, rect.right - 0.5f, rect.bottom - 0.5f)
            canvas.drawRect(tmpRect2, paint)

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
            data.mine -> drawEmoji(canvas, "💣", rect, cell * REVEAL_EMOJI_RATIO)
            data.adjacentMines > 0 -> {
                // 字号占格子 60%：小格子（50×50 自定义）下也保持可读
                numberPaint.textSize = cell * NUMBER_TEXT_RATIO
                numberPaint.color = NUMBER_COLORS[data.adjacentMines]
                canvas.drawText(
                    data.adjacentMines.toString(),
                    rect.centerX(), textBaseline(rect.centerY(), numberPaint), numberPaint
                )
            }
        }
    }

    private fun drawEmoji(canvas: Canvas, emoji: String, rect: RectF, size: Float) {
        cellEmojiPaint.textSize = size
        canvas.drawText(emoji, rect.centerX(), textBaseline(rect.centerY(), cellEmojiPaint), cellEmojiPaint)
    }

    private fun drawBottomBar(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val rect = tmpRect
        rect.set(0f, h - safeBottom - BOTTOM_H, w, h)
        paint.style = Paint.Style.FILL
        paint.color = theme.colors.bgPanel
        canvas.drawRect(rect, paint)
        drawBevel(canvas, rect, 2f, theme.colors.borderDark, theme.colors.borderLight)

        // 难度按钮（触摸可直接点按，与手柄菜单切换等价）
        layoutDiffButtons()
        // 循环前统一预设 14f 等宽样式：旧代码漏设 textSize，标签继承了 HUD 的 28f（AA-3/M22）
        applyUiText(14f, theme.colors.text, Paint.Align.CENTER, mono = true)
        for (i in DIFF_BUTTON_LEVELS.indices) {
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
                str(DIFF_BUTTON_LABEL_RES[i]), btn.centerX(),
                textBaseline(btn.centerY(), textPaint), textPaint
            )
        }

        // 键位提示（动态读取当前绑定，改键后自动更新）
        applyUiText(12f, theme.colors.text, Paint.Align.RIGHT)
        val hint = str(
            R.string.bottom_hint,
            bindings.getDisplayKey("activate"),
            bindings.getDisplayKey("flag"),
            bindings.getDisplayKey("menu")
        )
        val hintRight = w - safeRight - PAD - 4f
        val hintWidth = textPaint.measureText(hint)
        val barCenterY = rect.centerY()

        // 状态区（当前时间 + 电量）：居中放在难度按钮与键位提示之间的空闲区，
        // 不额外占用纵向空间；窄屏放不下时优先保留状态区（时间/电量优先级高于键位提示）。
        val paneWidth = statusPaneWidth()
        if (paneWidth > 0f) {
            val freeLeft = diffBtnRects[DIFF_BUTTON_LEVELS.size - 1].right + 12f
            val freeRight = hintRight - hintWidth - 16f
            val showHint = freeRight - freeLeft >= paneWidth
            val boxLeft = if (showHint) {
                freeLeft + (freeRight - freeLeft - paneWidth) / 2f
            } else {
                (w - safeRight - PAD - paneWidth).coerceAtLeast(freeLeft)
            }
            drawStatusBar(canvas, boxLeft, barCenterY - STATUS_PANE_H / 2f)
            if (showHint) {
                applyUiText(12f, theme.colors.text, Paint.Align.RIGHT)
                canvas.drawText(hint, hintRight, textBaseline(barCenterY, textPaint), textPaint)
            }
        } else {
            canvas.drawText(hint, hintRight, textBaseline(barCenterY, textPaint), textPaint)
        }
    }

    /** 状态区总宽（时钟 + 电池图标 + 百分比 + 内边距）；首帧尚无数据时返回 0 表示不画。 */
    private fun statusPaneWidth(): Float {
        if (statusClock.isEmpty()) return 0f
        statusMeasurePaint.textSize = STATUS_CLOCK_SIZE
        val clockW = statusMeasurePaint.measureText(statusClock)
        statusMeasurePaint.textSize = STATUS_PCT_SIZE
        val pctW = statusMeasurePaint.measureText(GameStatus.batteryLabel(statusBatteryPercent))
        return clockW + STATUS_CONTENT_GAP + BATTERY_BODY_W + BATTERY_NUB_W + STATUS_ICON_GAP +
            pctW + 2 * STATUS_PAD_H
    }

    /**
     * 底栏状态区：凹陷面板内显示当前时间 + 电池图标 + 电量百分比。
     * 视觉与 HUD/难度按钮一致（凹陷斜面、等宽字体、面板色填充），融入经典 WinXP 窗口风格。
     */
    private fun drawStatusBar(canvas: Canvas, left: Float, top: Float) {
        val pane = tmpRect
        pane.set(left, top, left + statusPaneWidth(), top + STATUS_PANE_H)
        paint.style = Paint.Style.FILL
        paint.color = theme.colors.bgPanel
        canvas.drawRect(pane, paint)
        // 凹陷斜面：上/左暗、下/右亮
        drawBevel(canvas, pane, 2f, theme.colors.borderDark, theme.colors.borderLight)

        val cy = pane.centerY()
        var x = pane.left + STATUS_PAD_H
        applyUiText(STATUS_CLOCK_SIZE, theme.colors.text, Paint.Align.LEFT, mono = true)
        canvas.drawText(statusClock, x, textBaseline(cy, textPaint), textPaint)
        x += textPaint.measureText(statusClock) + STATUS_CONTENT_GAP
        drawBatteryIcon(canvas, x, cy)
        x += BATTERY_BODY_W + BATTERY_NUB_W + STATUS_ICON_GAP
        applyUiText(STATUS_PCT_SIZE, theme.colors.text, Paint.Align.LEFT, mono = true)
        canvas.drawText(
            GameStatus.batteryLabel(statusBatteryPercent), x,
            textBaseline(cy, textPaint), textPaint
        )
    }

    /** 电池图标：外壳 + 正极触点 + 按电量比例填充（充电绿 / ≤20% 红 / 正常为主题文字色） */
    private fun drawBatteryIcon(canvas: Canvas, left: Float, cy: Float) {
        val body = tmpRect2
        val top = cy - BATTERY_ICON_H / 2f
        body.set(left, top, left + BATTERY_BODY_W, top + BATTERY_ICON_H)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.4f
        paint.color = theme.colors.text
        paint.alpha = 200
        canvas.drawRoundRect(body, 2.4f, 2.4f, paint)
        // 正极触点
        paint.style = Paint.Style.FILL
        canvas.drawRect(body.right + 0.6f, cy - 2.5f, body.right + BATTERY_NUB_W, cy + 2.5f, paint)
        paint.alpha = 255
        // 电量填充：内缩 2.2px，避免盖住外壳描边
        val ratio = GameStatus.batteryFillRatio(statusBatteryPercent)
        if (ratio > 0f) {
            val pad = 2.2f
            paint.color = GameStatus.batteryFillColor(
                statusBatteryPercent, statusCharging, theme.colors.text
            )
            paint.alpha = 215
            canvas.drawRect(
                body.left + pad, body.top + pad,
                body.left + pad + (body.width() - 2 * pad) * ratio,
                body.bottom - pad, paint
            )
            paint.alpha = 255
        }
        if (statusCharging) drawChargeBolt(canvas, body)
    }

    /** 充电闪电：小尺寸图标上用高对比白色多边形叠加在绿色填充上（不依赖 emoji 字体）。 */
    private fun drawChargeBolt(canvas: Canvas, body: RectF) {
        val cx = body.centerX()
        val cy = body.centerY()
        val h = body.height() * 0.42f
        tmpPath.reset()
        tmpPath.moveTo(cx + h * 0.28f, cy - h)
        tmpPath.lineTo(cx - h * 0.55f, cy + h * 0.10f)
        tmpPath.lineTo(cx - h * 0.06f, cy + h * 0.10f)
        tmpPath.lineTo(cx - h * 0.28f, cy + h)
        tmpPath.lineTo(cx + h * 0.55f, cy - h * 0.10f)
        tmpPath.lineTo(cx + h * 0.06f, cy - h * 0.10f)
        tmpPath.close()
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.alpha = 235
        canvas.drawPath(tmpPath, paint)
        paint.alpha = 255
    }

    /** 布局底部难度按钮：绘制与触摸命中共用同一套计算，避免两处漂移 */
    private fun layoutDiffButtons() {
        val btnH = BOTTOM_H - 14f
        val btnY = height - safeBottom - BOTTOM_H + 7f
        // 标签宽度按固定字号缓存一次（14f 等宽粗体，与绘制一致）
        val widths = diffBtnLabelWidths
        var x = safeLeft + PAD + 4f
        for (i in DIFF_BUTTON_LEVELS.indices) {
            diffBtnRects[i].set(x, btnY, x + widths[i], btnY + btnH)
            x += widths[i] + 6f
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
        // 不透明遮盖（对齐桌面版 style.css 的暂停改造）：暂停期间看不清棋盘。
        // 遮罩用窗口面板色（原 bgPage 会露出一整屏与主题无关的绿色）。
        canvas.drawColor(theme.colors.bgPanel)
        val cx = (safeLeft + width.toFloat() - safeRight) / 2f
        applyUiText(30f, theme.colors.text, Paint.Align.CENTER)
        canvas.drawText(
            str(R.string.pause_title), cx,
            textBaseline(height / 2f - 12f, textPaint), textPaint
        )
        applyUiText(14f, theme.colors.text, Paint.Align.CENTER)
        canvas.drawText(
            str(R.string.pause_sub), cx,
            textBaseline(height / 2f + 26f, textPaint), textPaint
        )
    }

    // ---------- 覆盖层通用绘制 ----------

    /** 对话框矩形：绘制与触摸命中共用同一套计算，保证按钮位置一致（扣除安全区）。 */
    private fun dialogRect(contentHeight: Float): RectF {
        val availW = width.toFloat() - safeLeft - safeRight
        val availH = height.toFloat() - safeTop - safeBottom
        val w = (availW * 0.86f).coerceAtMost(560f)
        val left = safeLeft + (availW - w) / 2f
        val top = safeTop + ((availH - contentHeight) / 2f).coerceAtLeast(0f)
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
        applyUiText(17f, theme.colors.text, Paint.Align.CENTER, mono = true)
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

        applyUiText(20f, theme.colors.text, Paint.Align.CENTER, mono = true)
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

    // ---------- 重开/退出确认 ----------

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

    /** 触摸命中：0=确认 1=取消，未命中返回 null */
    private fun confirmButtonIndex(x: Float, y: Float): Int? {
        val (yes, no) = confirmButtonRects()
        return when {
            yes.contains(x, y) -> 0
            no.contains(x, y) -> 1
            else -> null
        }
    }

    private fun drawConfirmNewGame(canvas: Canvas) {
        val title = str(if (confirmExit) R.string.confirm_exit_title else R.string.confirm_restart_title)
        val message = str(if (confirmExit) R.string.confirm_exit_msg else R.string.confirm_restart_msg)
        val yesLabel = str(if (confirmExit) R.string.confirm_exit_yes else R.string.confirm_restart_yes)
        drawDialog(canvas, title, CONFIRM_DIALOG_H) { c, rect ->
            applyUiText(14f, theme.colors.text, Paint.Align.CENTER)
            c.drawText(message, rect.centerX(), rect.top + 52f, textPaint)
            val (yes, no) = confirmButtonRects()
            drawConfirmButton(c, yes, yesLabel, confirmSelected == 0)
            drawConfirmButton(c, no, str(R.string.confirm_cancel), confirmSelected == 1)
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
        applyUiText(15f, theme.colors.text, Paint.Align.CENTER)
        canvas.drawText(label, rect.centerX(), textBaseline(rect.centerY(), textPaint), textPaint)
    }

    private fun drawMenu(canvas: Canvas) {
        val items = menuItems()
        // 项高按可用高度自适应：矮屏上近二十项菜单不再溢出屏幕
        val maxContentH = height - safeTop - safeBottom - 2 * PAD - 24f
        val itemH = min(32f, ((maxContentH - 56f) / items.size).coerceAtLeast(18f))
        val contentH = 46f + items.size * itemH + 10f
        drawDialog(canvas, str(R.string.menu_title), contentH) { _, rect ->
            applyUiText(14f, theme.colors.text, Paint.Align.LEFT)
            for (i in items.indices) {
                val row = RectF(
                    rect.left + 10f, rect.top + 42f + i * itemH,
                    rect.right - 10f, rect.top + 42f + (i + 1) * itemH - 4f
                )
                // 触摸命中：点按执行（与手柄 A/B 等价）
                addOverlayHit(row) { items[i].action() }
                val selected = i == menuSelected || i == overlayPressedIndex
                if (selected) {
                    paint.style = Paint.Style.FILL
                    paint.color = theme.colors.bgActive
                    canvas.drawRect(row, paint)
                    drawBevel(canvas, row, 1.5f, theme.colors.borderDark, theme.colors.borderLight)
                }
                val prefix = if (items[i].checked) "✓ " else ""
                canvas.drawText(
                    prefix + items[i].text,
                    row.left + 10f, textBaseline(row.centerY(), textPaint), textPaint
                )
            }
        }
    }

    private fun drawCustom(canvas: Canvas) {
        val contentH = 246f
        drawDialog(canvas, str(R.string.custom_title), contentH) { _, rect ->
            applyUiText(13f, 0xFFC00000.toInt(), Paint.Align.LEFT)
            canvas.drawText(customError, rect.left + 16f, rect.top + 40f, textPaint)

            val fields = listOf(
                str(R.string.custom_row),
                str(R.string.custom_col),
                str(R.string.custom_mines)
            )
            val values = listOf(customRows, customCols, customMines)
            for (i in 0..2) {
                val row = RectF(
                    rect.left + 16f, rect.top + 50f + i * 36f,
                    rect.right - 16f, rect.top + 82f + i * 36f
                )
                // 命中约定（AA-4/M23）：父行先登记，子按钮（◀/▶）后登记 → 后登记者优先命中
                addOverlayHit(row) { customField = i }
                val arrowW = 30f
                val arrowH = row.height() - 8f
                val arrowTop = row.centerY() - arrowH / 2f
                val minus = RectF(
                    row.right - 8f - 2 * arrowW - 6f, arrowTop,
                    row.right - 8f - arrowW - 6f, arrowTop + arrowH
                )
                val plus = RectF(
                    row.right - 8f - arrowW, arrowTop,
                    row.right - 8f, arrowTop + arrowH
                )
                addOverlayHit(minus) { customField = i; adjustCustom(-1) }
                addOverlayHit(plus) { customField = i; adjustCustom(1) }

                val selected = i == customField
                if (selected) {
                    paint.style = Paint.Style.FILL
                    paint.color = theme.colors.bgActive
                    canvas.drawRect(row, paint)
                    drawBevel(canvas, row, 1.5f, theme.colors.borderDark, theme.colors.borderLight)
                }
                applyUiText(15f, theme.colors.text, Paint.Align.LEFT)
                canvas.drawText(fields[i], row.left + 12f, textBaseline(row.centerY(), textPaint), textPaint)
                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(values[i].toString(), row.centerX(), textBaseline(row.centerY(), textPaint), textPaint)
                // 独立方块箭头按钮（与命中矩形同一几何）
                drawArrowButton(canvas, minus, "◀")
                drawArrowButton(canvas, plus, "▶")
            }

            // 确定 / 取消按钮：触摸可用（手柄仍可直接按 A/B 确定、Back 取消）
            val gap = 14f
            val bw = (rect.width() - 3 * gap) / 2f
            val bh = 38f
            val by = rect.bottom - gap - bh
            val ok = RectF(rect.left + gap, by, rect.left + gap + bw, by + bh)
            val cancel = RectF(rect.right - gap - bw, by, rect.right - gap, by + bh)
            addOverlayHit(ok) { confirmCustom() }
            addOverlayHit(cancel) { closeOverlay() }
            drawConfirmButton(canvas, ok, str(R.string.custom_ok), false)
            drawConfirmButton(canvas, cancel, str(R.string.custom_cancel), false)

            applyUiText(11f, theme.colors.text, Paint.Align.CENTER)
            canvas.drawText(str(R.string.custom_hint), rect.centerX(), by - 8f, textPaint)
        }
    }

    private fun drawArrowButton(canvas: Canvas, rect: RectF, glyph: String) {
        paint.style = Paint.Style.FILL
        paint.color = theme.colors.bgCell
        canvas.drawRect(rect, paint)
        drawBevel(canvas, rect, 1.5f, theme.colors.borderLight, theme.colors.borderDark)
        applyUiText(14f, theme.colors.text, Paint.Align.CENTER)
        canvas.drawText(glyph, rect.centerX(), textBaseline(rect.centerY(), textPaint), textPaint)
    }

    private fun drawBindings(canvas: Canvas) {
        val rows = bindRows()
        val itemH = 30f
        val contentH = 46f + rows.size * itemH + 46f
        drawDialog(canvas, str(R.string.bindings_title), contentH) { _, rect ->
            applyUiText(12f, 0xFFC00000.toInt(), Paint.Align.LEFT)
            canvas.drawText(bindError, rect.left + 14f, rect.top + 38f, textPaint)

            for (i in rows.indices) {
                val row = RectF(
                    rect.left + 10f, rect.top + 44f + i * itemH,
                    rect.right - 10f, rect.top + 44f + (i + 1) * itemH - 3f
                )
                addOverlayHit(row) { bindSelected = i; activateBindRow(rows[i]) }
                val selected = i == bindSelected || i == overlayPressedIndex
                val listening = bindListening == rows[i].action
                val blinkOn = (SystemClock.uptimeMillis() % 800) < 400
                if (selected || (listening && blinkOn)) {
                    paint.style = Paint.Style.FILL
                    paint.color = theme.colors.bgActive
                    canvas.drawRect(row, paint)
                    drawBevel(canvas, row, 1.5f, theme.colors.borderDark, theme.colors.borderLight)
                }
                applyUiText(13f, theme.colors.text, Paint.Align.LEFT)
                canvas.drawText(rows[i].label, row.left + 12f, textBaseline(row.centerY(), textPaint), textPaint)

                textPaint.textAlign = Paint.Align.RIGHT
                textPaint.color = if (listening) theme.colors.led else theme.colors.text
                val value = when {
                    listening -> str(R.string.bind_listening)
                    rows[i].kind == BindRowKind.ACTION -> bindings.getDisplayKey(rows[i].action!!)
                    else -> ""
                }
                canvas.drawText(value, row.right - 12f, textBaseline(row.centerY(), textPaint), textPaint)
            }

            // 按键测试屏
            applyUiText(12f, theme.colors.text, Paint.Align.CENTER)
            canvas.drawText(
                str(R.string.bind_current_key, lastKeyInfo),
                rect.centerX(), rect.bottom - 34f, textPaint
            )
            canvas.drawText(
                str(R.string.bind_help),
                rect.centerX(), rect.bottom - 14f, textPaint
            )
        }
    }

    private fun customBuckets(): List<String> =
        stats.getBuckets().filter { it.startsWith("custom:") }

    private fun bestCustomTime(): Int? =
        customBuckets().mapNotNull { stats.bestTimeOf(it) }.minOrNull()

    private fun bestCustomRate(): Double? =
        customBuckets().mapNotNull { stats.bestBbbvPerSec[it] }.maxOrNull()

    private fun drawStats(canvas: Canvas) {
        val s = stats.getSummary()
        fun fmtTime(t: Int?): String = if (t == null) "--:--" else GameText.formatTime(t)
        val lines = listOf(
            str(R.string.stats_total, s.totalGames),
            str(R.string.stats_win_lose, s.gamesWon, s.gamesLost),
            str(R.string.stats_win_rate, s.winRate),
            str(R.string.stats_current_streak, s.currentStreak),
            str(R.string.stats_best_streak, s.bestStreak),
            str(R.string.stats_total_time, fmtTime(s.totalPlayTime)),
            str(R.string.stats_fastest),
            str(R.string.stats_level_line, levelLabel("beginner"), fmtTime(s.bestTime["beginner"])),
            str(R.string.stats_level_line, levelLabel("intermediate"), fmtTime(s.bestTime["intermediate"])),
            str(R.string.stats_level_line, levelLabel("expert"), fmtTime(s.bestTime["expert"])),
            str(R.string.stats_level_line, levelLabel("custom"), fmtTime(bestCustomTime())),
            str(R.string.stats_best_rate),
            str(R.string.stats_level_line, levelLabel("beginner"), GameText.formatRate(s.bestBbbvPerSec["beginner"])),
            str(R.string.stats_level_line, levelLabel("intermediate"), GameText.formatRate(s.bestBbbvPerSec["intermediate"])),
            str(R.string.stats_level_line, levelLabel("expert"), GameText.formatRate(s.bestBbbvPerSec["expert"])),
            str(R.string.stats_level_line, levelLabel("custom"), GameText.formatRate(bestCustomRate()))
        )
        val contentH = 46f + lines.size * 24f + 10f
        drawDialog(canvas, str(R.string.stats_title), contentH) { _, rect ->
            applyUiText(14f, theme.colors.text, Paint.Align.LEFT)
            for (i in lines.indices) {
                canvas.drawText(lines[i], rect.left + 22f, rect.top + 42f + i * 24f, textPaint)
            }
            // 触摸：统计页无交互项，点按对话框内任意处关闭
            addOverlayHit(rect) { closeOverlay() }
        }
    }

    // ---------- 效率曲线 ----------

    /** 最近 N 局 3BV/s 折线（左右切换难度）；空数据显示占位文案 */
    private fun drawHistory(canvas: Canvas) {
        val level = HISTORY_LEVELS[historyLevelIndex]
        val bucket = if (level == "custom") {
            customBuckets().lastOrNull() ?: "custom"
        } else {
            level
        }
        val entries = stats.getHistory(bucket, HISTORY_SAMPLE)
        drawDialog(canvas, str(R.string.history_title), 300f) { c, rect ->
            applyUiText(15f, theme.colors.text, Paint.Align.CENTER)
            c.drawText(str(R.string.history_label, levelLabel(level)), rect.centerX(), rect.top + 52f, textPaint)
            // 触摸命中：左右两个箭头区域切换难度（与手柄左右键等价，非重叠无需担心优先级）
            addOverlayHit(RectF(rect.left + 10f, rect.top + 36f, rect.centerX(), rect.top + 68f)) { shiftHistoryLevel(-1) }
            addOverlayHit(RectF(rect.centerX(), rect.top + 36f, rect.right - 10f, rect.top + 68f)) { shiftHistoryLevel(1) }

            val left = rect.left + 46f
            val top = rect.top + 72f
            val right = rect.right - 18f
            val bottom = rect.bottom - 56f

            if (entries.isEmpty()) {
                applyUiText(14f, theme.colors.text, Paint.Align.CENTER)
                c.drawText(str(R.string.history_empty), rect.centerX(), (top + bottom) / 2f, textPaint)
                drawHistoryHint(c, rect)
                return@drawDialog
            }

            // 0 秒记录（durationMs>0 的瞬胜）没有可比性，也不能作为 3BV/s 分母
            val chartEntries = entries.filter { it.time > 0 }
            if (chartEntries.isEmpty()) {
                applyUiText(14f, theme.colors.text, Paint.Align.CENTER)
                c.drawText(str(R.string.history_empty), rect.centerX(), (top + bottom) / 2f, textPaint)
                drawHistoryHint(c, rect)
                return@drawDialog
            }
            val rates = chartEntries.map { it.bbbv.toFloat() / it.time }
            val maxRate = rates.maxOrNull() ?: 1f
            val yMax = (ceil(maxRate * 1.15f * 10f) / 10f).coerceAtLeast(0.5f)

            fun xAt(i: Int): Float =
                if (chartEntries.size == 1) (left + right) / 2f
                else left + (right - left) * i / (chartEntries.size - 1).toFloat()

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
                c.drawText(GameText.formatOneDecimal(v), left - 6f, y + 4f, textPaint)
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
            applyUiText(13f, theme.colors.text, Paint.Align.CENTER)
            c.drawText(
                str(
                    R.string.history_summary,
                    chartEntries.size,
                    GameText.formatRate(avg.toDouble()),
                    GameText.formatRate(maxRate.toDouble())
                ),
                rect.centerX(), rect.bottom - 32f, textPaint
            )
            drawHistoryHint(c, rect)
        }
    }

    private fun drawHistoryHint(canvas: Canvas, rect: RectF) {
        applyUiText(12f, theme.colors.text, Paint.Align.CENTER)
        canvas.drawText(str(R.string.history_hint), rect.centerX(), rect.bottom - 12f, textPaint)
    }

    private fun drawAbout(canvas: Canvas) {
        val versionLine = if (appVersion.isEmpty()) {
            str(R.string.about_version)
        } else {
            str(R.string.about_version_with_v, appVersion)
        }
        val lines = listOf(
            str(R.string.about_line1),
            versionLine,
            str(R.string.about_line3),
            "",
            str(R.string.about_device),
            // L6/AA-11：键位文案动态读取当前绑定，不再写死默认键
            str(
                R.string.about_controls,
                bindings.getDisplayKey("moveUp"),
                bindings.getDisplayKey("moveDown"),
                bindings.getDisplayKey("moveLeft"),
                bindings.getDisplayKey("moveRight"),
                bindings.getDisplayKey("activate"),
                bindings.getDisplayKey("flag"),
                bindings.getDisplayKey("newGame"),
                bindings.getDisplayKey("menu")
            )
        )
        val contentH = 46f + lines.size * 26f + 10f
        drawDialog(canvas, str(R.string.about_title), contentH) { _, rect ->
            applyUiText(14f, theme.colors.text, Paint.Align.CENTER)
            for (i in lines.indices) {
                canvas.drawText(
                    lines[i], rect.centerX(),
                    rect.top + 42f + i * 26f, textPaint
                )
            }
            // 触摸：关于页无交互项，点按任意处关闭
            addOverlayHit(rect) { closeOverlay() }
        }
    }

    // ---------- 无障碍（M14/AA-9）：虚拟动作 + 状态播报 + performClick ----------

    init {
        setupAccessibility()
    }

    private fun setupAccessibility() {
        contentDescription = str(R.string.a11y_game_view)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.contentDescription = accessibilitySummary()
                info.className = "android.widget.Button"
                info.addAction(
                    AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        A11Y_ACTION_ACTIVATE, str(R.string.a11y_action_activate)
                    )
                )
                info.addAction(
                    AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        A11Y_ACTION_FLAG, str(R.string.a11y_action_flag)
                    )
                )
                info.addAction(
                    AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        A11Y_ACTION_NEW_GAME, str(R.string.a11y_action_new_game)
                    )
                )
                info.addAction(
                    AccessibilityNodeInfoCompat.AccessibilityActionCompat(
                        A11Y_ACTION_MENU, str(R.string.a11y_action_menu)
                    )
                )
            }

            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean =
                when (action) {
                    A11Y_ACTION_ACTIVATE -> {
                        activateAtCursor()
                        true
                    }
                    A11Y_ACTION_FLAG -> {
                        flagAtCursor()
                        true
                    }
                    A11Y_ACTION_NEW_GAME -> {
                        requestRestart { startGame(engine.currentLevel) }
                        true
                    }
                    A11Y_ACTION_MENU -> {
                        openMenu()
                        true
                    }
                    else -> super.performAccessibilityAction(host, action, args)
                }
        })
    }

    /** 当前局面的读屏摘要（HUD/棋盘状态可播报）；有状态数据时补上当前时间与电量。 */
    private fun accessibilitySummary(): String {
        val elapsed = GameText.formatTime(clock.seconds(SystemClock.elapsedRealtime()))
        val gameState = when {
            generating -> str(R.string.a11y_generating)
            engine.state == "playing" -> str(
                R.string.a11y_state_playing, engine.totalMines - engine.flagCount, elapsed
            )
            engine.state == "won" -> str(R.string.a11y_state_won, elapsed)
            engine.state == "lost" -> str(R.string.a11y_state_lost, elapsed)
            engine.noGuessFallback -> str(R.string.a11y_no_guess_fallback)
            else -> str(R.string.a11y_state_ready)
        }
        if (statusClock.isEmpty()) return gameState
        val status = str(
            R.string.a11y_status, statusClock, GameStatus.batteryLabel(statusBatteryPercent)
        )
        return "$gameState · $status"
    }

    private fun updateAccessibilityDescription(@Suppress("UNUSED_PARAMETER") state: String) {
        contentDescription = accessibilitySummary()
    }

    /** 状态变化时主动播报（胜利/失败/开始/生成），避免读屏用户错过关键事件。 */
    private fun announceStateChange(state: String) {
        val message = when (state) {
            "playing" -> str(R.string.a11y_state_playing, engine.totalMines - engine.flagCount, "0:00")
            "won" -> str(R.string.a11y_state_won, GameText.formatTime(engine.timer))
            "lost" -> str(R.string.a11y_state_lost, GameText.formatTime(engine.timer))
            else -> accessibilitySummary()
        }
        if (message != lastA11yAnnouncement) {
            lastA11yAnnouncement = message
            announceForAccessibility(message)
        }
    }

    // ---------- 触摸（点按=翻开/和弦，长按=标雷，表情=新游戏） ----------

    private val longPressRunnable = Runnable {
        val cell = touchCell ?: return@Runnable
        if (!canAct()) return@Runnable
        // L5/AA-7：已翻开格长按不标旗、不误振动，也不吞掉松手和弦
        if (engine.board[cell.first][cell.second].revealed) return@Runnable
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        touchPreviewCell = null
        flagAt(cell.first, cell.second)
        touchFlagged = true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
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
                        performClick()
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
        // 其余覆盖层（菜单/自定义/键位/统计/关于/曲线）：统一走「绘制时登记的命中区域」。
        // 命中即执行；点空白（无命中且未按在区域上）等价 Back 关闭。
        if (overlay != Overlay.None) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    overlayPressedIndex = overlayHitAt(event.x, event.y)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val pressed = overlayPressedIndex
                    overlayPressedIndex = null
                    val hit = overlayHitAt(event.x, event.y)
                    if (hit != null && hit == pressed) {
                        performClick()
                        overlayHits.actionAt(hit)?.invoke()
                    } else if (pressed == null && hit == null) {
                        closeOverlay()
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    overlayPressedIndex = null
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
            MotionEvent.ACTION_POINTER_UP -> {
                // 非主指针抬起：同样取消手势（坐标恒取 pointer 0，主指变了会跳变）
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
                    // 手势转为拖动浏览（超大棋盘的视口滚动）。
                    // 以「越过阈值时的触点」为锚，避免进入拖动瞬间内容跳 24px
                    draggingBoard = true
                    dragAnchorX = event.x
                    dragAnchorY = event.y
                    dragOffsetX0 = viewOffsetX
                    dragOffsetY0 = viewOffsetY
                    invalidate()
                }
                if (draggingBoard) {
                    val m = metrics()
                    viewOffsetX = dragOffsetX0 - (event.x - dragAnchorX).toInt()
                    viewOffsetY = dragOffsetY0 - (event.y - dragAnchorY).toInt()
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
                        performClick()
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
                        performClick()
                        requestRestart { startGame(engine.currentLevel) }
                    }
                    invalidate()
                    return true
                }
                removeCallbacks(longPressRunnable)
                val cell = touchCell
                touchCell = null
                if (cell != null && !touchFlagged && canAct()) {
                    performClick()
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
        val cx = (safeLeft + width.toFloat() - safeRight) / 2f
        val dx = x - cx
        val dy = y - (safeTop + HUD_H / 2f)
        return dx * dx + dy * dy < FACE_RADIUS * FACE_RADIUS
    }
}
