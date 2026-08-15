package com.wpiaopiao.saolei

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
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
import kotlin.math.cos
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
        private const val MIN_CELL = 8f
        private const val MAX_CELL = 48f
        // 和弦失败 shake：时长 350ms，双轴错位抖动（仅周围未翻开的格子）
        private const val SHAKE_MS = 350L
    }

    private enum class Overlay { None, Menu, Custom, Bindings, Stats, About }
    private enum class BindRowKind { ACTION, RESET, CLOSE }

    private data class BindRow(val kind: BindRowKind, val action: String? = null, val label: String = "")
    private data class MenuItem(val text: String, val checked: Boolean = false, val action: () -> Unit)
    private data class Metrics(val cell: Float, val x: Float, val y: Float, val w: Float, val h: Float)

    // ---------- 状态 ----------

    private val engine = GameEngine()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var theme = themes[0]
    private var muted = false
    private var cursor = 0 to 0
    private var cursorVisible = false
    private var facePressed = false
    private var appActive = true
    private var timerRunning = false
    private var prevState = "ready"

    private val heldMoves = mutableSetOf<String>()
    private val revealedAnim = mutableMapOf<Pair<Int, Int>, Long>()
    private var shakeStart = 0L
    private val shakeCells = mutableSetOf<Pair<Int, Int>>()

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

    // 触摸
    private var touchCell: Pair<Int, Int>? = null
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchFlagged = false

    // ---------- 定时器 ----------

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (appActive && engine.state == "playing") {
                engine.timer = min(999, engine.timer + 1)
                invalidate()
                postDelayed(this, 1000)
            } else {
                timerRunning = false
            }
        }
    }

    private fun manageTimer() {
        if (appActive && engine.state == "playing" && !timerRunning) {
            timerRunning = true
            postDelayed(timerRunnable, 1000)
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
        invalidate()
    }

    // ---------- 生命周期（MainActivity 调用） ----------

    fun onAppPaused() {
        appActive = false
        clearHeldMoves()
        facePressed = false
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

    // ---------- 主题 / 静音 ----------

    fun setThemeValue(value: String) {
        theme = themes.find { it.value == value } ?: themes[0]
        storage.putString("theme", theme.value)
        invalidate()
    }

    private fun toggleMute() {
        muted = !muted
        storage.putString("muted", muted.toString())
        sound.setEnabled(!muted)
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
        revealedAnim.clear()
        shakeCells.clear()
        facePressed = false
        prevState = "ready"
        engine.dirty.clear()
        manageTimer()
        invalidate()
    }

    /** 状态迁移：统计、胜利自动补旗/展开、计时（对齐 ui.js onStateChange） */
    private fun syncState() {
        val s = engine.state
        if (s != prevState) {
            if (s == "playing" && prevState == "ready") {
                stats.onGameStart(engine.currentLevel)
            }
            if (s == "won") {
                engine.autoFlagRemainingMines()
                engine.revealAllSafe()
                stats.onGameWin(engine.timer)
            }
            if (s == "lost") {
                stats.onGameLose(engine.timer)
            }
            prevState = s
        }
        manageTimer()
        invalidate()
    }

    private fun markRevealAnimFromDirty() {
        val now = SystemClock.uptimeMillis()
        for ((r, c) in engine.dirty) {
            val cell = engine.board[r][c]
            if (cell.revealed && !cell.mine) revealedAnim[r to c] = now
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
                    if (!cell.revealed) shakeCells.add(nr to nc)
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
        invalidate()
    }

    private fun openCustom() {
        overlay = Overlay.Custom
        customField = 0
        customError = ""
        invalidate()
    }

    private fun openBindings() {
        overlay = Overlay.Bindings
        bindSelected = 0
        bindListening = null
        bindError = ""
        lastKeyInfo = "—"
        invalidate()
    }

    private fun closeOverlay() {
        overlay = Overlay.None
        invalidate()
    }

    private fun menuItems(): List<MenuItem> {
        val items = mutableListOf<MenuItem>()
        items += MenuItem("新游戏") { startGame(engine.currentLevel) }
        for (lvl in listOf("beginner", "intermediate", "expert")) {
            val cfg = GameEngine.LEVELS.getValue(lvl)
            items += MenuItem(
                "难度：${cfg.label} ${cfg.rows}×${cfg.cols}/${cfg.mines}雷",
                checked = engine.currentLevel == lvl && engine.currentCustom == null
            ) { startGame(lvl) }
        }
        items += MenuItem("自定义雷区...") { openCustom() }
        for (t in themes) {
            items += MenuItem(
                "主题：${t.label}",
                checked = theme.value == t.value
            ) { setThemeValue(t.value) }
        }
        items += MenuItem(if (muted) "静音：开" else "静音：关") { toggleMute() }
        items += MenuItem("键位设置...") { openBindings() }
        items += MenuItem("游戏统计...") { overlay = Overlay.Stats }
        items += MenuItem("关于...") { overlay = Overlay.About }
        return items
    }

    private fun bindRows(): List<BindRow> {
        val rows = KeyBindings.ACTIONS.map { action ->
            BindRow(BindRowKind.ACTION, action, bindings.getBindings()[action]!!.label)
        }
        return rows + BindRow(BindRowKind.RESET, null, "恢复默认") + BindRow(BindRowKind.CLOSE, null, "关闭")
    }

    // ---------- 手柄按键（MainActivity 转发） ----------

    fun handleKeyDown(code: Int): Boolean {
        lastKeyInfo = "${KeyNames.nameOf(code)} (${code})"
        val action = bindings.getActionForKey(code)

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
                            startGame(engine.currentLevel)
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
        val w = width.toFloat()
        val h = height.toFloat()
        val cell = min(
            (w - 2 * PAD) / engine.cols,
            (h - HUD_H - BOTTOM_H - 3 * PAD) / engine.rows
        ).coerceIn(MIN_CELL, MAX_CELL)
        val boardW = cell * engine.cols
        val boardH = cell * engine.rows
        val boardX = (w - boardW) / 2f
        val middleH = h - HUD_H - BOTTOM_H - 2 * PAD
        val boardY = HUD_H + PAD + ((middleH - boardH) / 2f).coerceAtLeast(0f)
        return Metrics(cell, boardX, boardY, boardW, boardH)
    }

    // ---------- 绘制 ----------

    override fun onDraw(canvas: Canvas) {
        val m = metrics()
        canvas.drawColor(theme.colors.bgPage)
        drawHud(canvas)
        drawBoard(canvas, m)
        drawBottomBar(canvas, m)
        when (overlay) {
            Overlay.Menu -> drawMenu(canvas)
            Overlay.Custom -> drawCustom(canvas)
            Overlay.Bindings -> drawBindings(canvas)
            Overlay.Stats -> drawStats(canvas)
            Overlay.About -> drawAbout(canvas)
            Overlay.None -> {}
        }
        if (!appActive && engine.state == "playing") drawPause(canvas)
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
        val rect = RectF(0f, 0f, w, HUD_H)
        paint.style = Paint.Style.FILL
        paint.color = theme.colors.bgPanel
        canvas.drawRect(rect, paint)
        drawBevel(canvas, rect, 2f, theme.colors.borderDark, theme.colors.borderLight)

        // 雷数 LED
        val ledW = 92f
        val ledH = 40f
        val ledY = (HUD_H - ledH) / 2f
        val mineLed = RectF(PAD + 2f, ledY, PAD + 2f + ledW, ledY + ledH)
        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK
        canvas.drawRect(mineLed, paint)
        textPaint.reset()
        textPaint.isAntiAlias = true
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
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
        val timerLed = RectF(w - PAD - 2f - ledW, ledY, w - PAD - 2f, ledY + ledH)
        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK
        canvas.drawRect(timerLed, paint)
        textPaint.color = theme.colors.led
        canvas.drawText(
            formatCounter(engine.timer),
            timerLed.centerX(), textBaseline(timerLed.centerY(), textPaint), textPaint
        )
    }

    private fun formatCounter(n: Int): String =
        if (n < 0) "-" + String.format("%02d", -n) else String.format("%03d", n)

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
                val path = android.graphics.Path()
                path.moveTo(px(8.2f), py(14.2f))
                path.quadTo(px(12f), py(18.2f), px(15.8f), py(14.2f))
                canvas.drawPath(path, paint)
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
                canvas.drawRoundRect(
                    RectF(px(3.6f), py(7.8f), px(20.4f), py(12.8f)), 2.4f * s, 2.4f * s, paint
                )
                paint.color = 0xFF0B0D10.toInt()
                canvas.drawRect(RectF(px(3.6f), py(7.8f), px(9.2f), py(12.8f)), paint)
                canvas.drawRect(RectF(px(14.8f), py(7.8f), px(20.4f), py(12.8f)), paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.7f * s
                paint.color = 0xFF3A2C00.toInt()
                paint.strokeCap = Paint.Cap.ROUND
                val path = android.graphics.Path()
                path.moveTo(px(8.2f), py(15.6f))
                path.quadTo(px(12f), py(19f), px(15.8f), py(15.6f))
                canvas.drawPath(path, paint)
            }
            else -> { // lost
                paint.strokeWidth = 1.7f * s
                paint.strokeCap = Paint.Cap.ROUND
                for (x in floatArrayOf(7.2f, 13.6f)) {
                    canvas.drawLine(px(x), py(7.6f), px(x + 3.2f), py(10.8f), paint)
                    canvas.drawLine(px(x + 3.2f), py(7.6f), px(x), py(10.8f), paint)
                }
                val path = android.graphics.Path()
                path.moveTo(px(8.6f), py(16.8f))
                path.quadTo(px(12f), py(14f), px(15.4f), py(16.8f))
                canvas.drawPath(path, paint)
            }
        }
    }

    private fun faceState(): String = when {
        engine.state == "lost" -> "lost"
        engine.state == "won" -> "won"
        facePressed -> "pressed"
        else -> "ready"
    }

    private fun drawBoard(canvas: Canvas, m: Metrics) {
        val now = SystemClock.uptimeMillis()
        for (r in 0 until engine.rows) {
            for (c in 0 until engine.cols) {
                val left = m.x + c * m.cell
                val top = m.y + r * m.cell
                val rect = RectF(left, top, left + m.cell, top + m.cell)
                val data = engine.board[r][c]

                // 和弦失败抖动：双轴错位（水平 7px + 垂直 5px 衰减），仅周围未翻开的格子
                var shakeDx = 0f
                var shakeDy = 0f
                if (shakeCells.contains(r to c)) {
                    val elapsed = now - shakeStart
                    if (elapsed < SHAKE_MS) {
                        val t = elapsed / SHAKE_MS.toFloat()
                        shakeDx = sin(t * 55.0).toFloat() * 7f * (1f - t)
                        shakeDy = cos(t * 40.0).toFloat() * 5f * (1f - t)
                    }
                }
                canvas.save()
                if (shakeDx != 0f || shakeDy != 0f) canvas.translate(shakeDx, shakeDy)

                if (!data.revealed) {
                    paint.style = Paint.Style.FILL
                    paint.color = theme.colors.bgCell
                    canvas.drawRect(rect, paint)
                    drawBevel(
                        canvas, rect, 2f,
                        theme.colors.borderLight, theme.colors.borderDark
                    )
                    when {
                        data.flagged -> drawEmoji(canvas, "🚩", rect, m.cell * 0.5f)
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

                    val animElapsed = revealedAnim[r to c]?.let { now - it }
                    if (animElapsed != null) {
                        if (animElapsed >= 150) {
                            revealedAnim.remove(r to c)
                        } else {
                            val scale = 0.85f + 0.15f * (animElapsed / 120f).coerceAtMost(1f)
                            canvas.save()
                            canvas.scale(scale, scale, rect.centerX(), rect.centerY())
                            drawCellContent(canvas, rect, data, m.cell)
                            canvas.restore()
                        }
                    }
                    if (animElapsed == null || animElapsed >= 150) {
                        drawCellContent(canvas, rect, data, m.cell)
                    }
                }

                if (cursorVisible && cursor == (r to c)) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 2f
                    paint.color = theme.colors.led
                    canvas.drawRect(
                        RectF(rect.left + 1f, rect.top + 1f, rect.right - 1f, rect.bottom - 1f),
                        paint
                    )
                }
                canvas.restore()
            }
        }
    }

    private fun drawCellContent(canvas: Canvas, rect: RectF, data: com.wpiaopiao.saolei.core.Cell, cell: Float) {
        when {
            data.mine -> drawEmoji(canvas, "💣", rect, cell * 0.57f)
            data.adjacentMines > 0 -> {
                textPaint.reset()
                textPaint.isAntiAlias = true
                textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
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

    private fun drawBottomBar(canvas: Canvas, m: Metrics) {
        val w = width.toFloat()
        val h = height.toFloat()
        val rect = RectF(0f, h - BOTTOM_H, w, h)
        paint.style = Paint.Style.FILL
        paint.color = theme.colors.bgPanel
        canvas.drawRect(rect, paint)
        drawBevel(canvas, rect, 2f, theme.colors.borderDark, theme.colors.borderLight)

        // 难度按钮
        val labels = listOf("初级", "中级", "高级", "自定义")
        val levels = listOf("beginner", "intermediate", "expert", null)
        val btnH = BOTTOM_H - 14f
        var x = PAD + 4f
        val btnY = h - BOTTOM_H + 7f
        textPaint.reset()
        textPaint.isAntiAlias = true
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textPaint.textSize = 14f
        for (i in labels.indices) {
            val label = labels[i]
            val level = levels[i]
            val btnW = textPaint.measureText(label) + 22f
            val btn = RectF(x, btnY, x + btnW, btnY + btnH)
            paint.style = Paint.Style.FILL
            val isActive = when (level) {
                null -> engine.currentLevel == "custom"
                else -> engine.currentLevel == level && engine.currentCustom == null
            }
            paint.color = if (isActive) theme.colors.bgActive else theme.colors.bgCell
            canvas.drawRect(btn, paint)
            drawBevel(
                canvas, btn, 2f,
                if (isActive) theme.colors.borderDark else theme.colors.borderLight,
                if (isActive) theme.colors.borderLight else theme.colors.borderDark
            )
            textPaint.color = theme.colors.text
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(label, btn.centerX(), textBaseline(btn.centerY(), textPaint), textPaint)
            x += btnW + 6f
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

    private fun drawPause(canvas: Canvas) {
        canvas.drawColor(0x8C000000.toInt())
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

    private fun drawDialog(
        canvas: Canvas, title: String, contentHeight: Float,
        content: (Canvas, RectF) -> Unit
    ) {
        canvas.drawColor(theme.colors.overlay)
        val w = (width * 0.86f).coerceAtMost(560f)
        val left = (width - w) / 2f
        val top = (height - contentHeight) / 2f
        val rect = RectF(left, top, left + w, top + contentHeight)
        paint.style = Paint.Style.FILL
        paint.color = theme.colors.dialogBg
        canvas.drawRect(rect, paint)
        drawBevel(canvas, rect, 3f, theme.colors.borderLight, theme.colors.borderDark)
        textPaint.reset()
        textPaint.isAntiAlias = true
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textPaint.textSize = 17f
        textPaint.color = theme.colors.text
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(title, rect.centerX(), rect.top + 26f, textPaint)
        content(canvas, rect)
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
        val fmtTime = { t: Int? -> if (t == null) "--:--" else String.format("%d:%02d", t / 60, t % 60) }
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
            "　自定义: ${fmtTime(s.bestTime["custom"])}"
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

    private fun drawAbout(canvas: Canvas) {
        val lines = listOf(
            "经典扫雷游戏",
            "安卓掌机版 v1.0.0",
            "移植自桌面版 v1.2.0",
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
            flagAt(cell.first, cell.second)
            touchFlagged = true
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                touchCell = cellAt(event.x, event.y)
                touchFlagged = false
                if (touchCell == null) {
                    if (faceHit(event.x, event.y)) {
                        facePressed = true
                        invalidate()
                        return true
                    }
                    return false
                }
                cursorVisible = false
                postDelayed(longPressRunnable, LONG_PRESS_MS)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (touchCell != null &&
                    (abs(event.x - touchDownX) > 24f || abs(event.y - touchDownY) > 24f)
                ) {
                    removeCallbacks(longPressRunnable)
                    touchCell = null
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (facePressed) {
                    facePressed = false
                    startGame(engine.currentLevel)
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
                facePressed = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun cellAt(x: Float, y: Float): Pair<Int, Int>? {
        if (overlay != Overlay.None) return null
        val m = metrics()
        if (x < m.x || x >= m.x + m.w || y < m.y || y >= m.y + m.h) return null
        val r = ((y - m.y) / m.cell).toInt()
        val c = ((x - m.x) / m.cell).toInt()
        if (r !in 0 until engine.rows || c !in 0 until engine.cols) return null
        return r to c
    }

    private fun faceHit(x: Float, y: Float): Boolean {
        val dx = x - width / 2f
        val dy = y - HUD_H / 2f
        return dx * dx + dy * dy < 26f * 26f
    }
}
