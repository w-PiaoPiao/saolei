package com.wpiaopiao.saolei

import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.wpiaopiao.saolei.core.KeyBindings
import com.wpiaopiao.saolei.core.KeyCodes
import com.wpiaopiao.saolei.core.Storage
import com.wpiaopiao.saolei.core.StatsManager

/**
 * 主 Activity：沉浸全屏横屏、物理按键分发、生命周期（暂停/恢复计时）与对局存档恢复。
 * 所有绘制与手柄交互都在 GameView 中完成。
 */
class MainActivity : Activity() {

    companion object {
        /** 配置变更/进程回收时把进行中对局快照放进 Bundle 的 key */
        private const val STATE_ROUND = "round.snapshot"
        /** onPause 时把进行中对局落到 prefs 的 key（进程被杀后仍可恢复，M19/AA-5） */
        private const val PREF_ROUND = "round.snapshot"
    }

    private lateinit var gameView: GameView
    private lateinit var sound: SoundManager
    private lateinit var storage: Storage
    private lateinit var statusWatcher: StatusWatcher

    /** 是否已经确认收到过"物理手柄"按键（H3 兜底过滤用） */
    private var sawPhysicalGamepadKey = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 注意：KEEP_SCREEN_ON 不在这里常开——只在对局进行中由 GameView 回调开启（L8）
        setVolumeControlStream(AudioManager.STREAM_MUSIC)

        storage = PrefsStorage(getSharedPreferences("saolei", MODE_PRIVATE))
        val bindings = KeyBindings(storage)
        val stats = StatsManager(storage)
        sound = SoundManager(this)
        sound.init()
        val muted = storage.getString("muted") == "true"

        gameView = GameView(this, bindings, stats, sound, storage)
        // 主题：用户选过就用选过的；没选过则跟随系统深浅色且不落盘，保持继续跟随
        val savedTheme = storage.getString("theme")
        gameView.setThemeValue(savedTheme ?: systemThemeValue(), persist = savedTheme != null)
        // 把持久化的静音状态同步进 GameView：否则菜单显示与首次静音切换都会错乱
        gameView.setMutedValue(muted)
        // 无猜模式（生成可纯逻辑通关的雷局）：与静音同模式同步
        gameView.setNoGuessValue(storage.getString("noGuess") == "true")
        // About 页显示运行时版本号（Gradle/CI 注入），避免与 build.gradle 双处硬编码漂移
        gameView.setAppVersion(versionName())
        // 屏幕常亮：仅对局进行中（菜单/暂停/统计时不常亮）
        gameView.setKeepScreenOnCallback { active ->
            if (active) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        // L14：菜单里的「退出游戏」
        gameView.onExitRequested = { finish() }

        setContentView(gameView)
        // 底栏状态区（当前时间 + 电量）数据源：广播变化时推给 GameView 绘制
        statusWatcher = StatusWatcher(this) { clock, percent, charging ->
            gameView.setStatusInfo(clock, percent, charging)
        }
        setupInsets()
        immersive()
        restoreRound(savedInstanceState)
        gameView.refreshKeepScreenOn()

        // API 33+ 预测性返回：把系统返回手势交给游戏（与 onBackPressed 同语义）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) {
                if (!gameView.handleKeyDown(KeyCodes.BACK)) finish()
            }
        }
    }

    /** 系统当前深浅色 → 默认主题值（跟随系统时首次启动与运行中切换都用它） */
    private fun systemThemeValue(): String =
        if ((resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        ) "dark" else "light"

    private fun versionName(): String = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
                .versionName ?: ""
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        }
    } catch (ignored: Exception) {
        ""
    }

    /** configChanges 含 uiMode，系统深浅色切换不会重建 Activity：未显式选过主题时实时跟随 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (storage.getString("theme") == null) {
            gameView.setThemeValue(systemThemeValue(), persist = false)
        }
        // 字体/语言/密度变化也在 configChanges 内，无需重建；这里补一次沉浸式与重绘
        immersive()
        gameView.invalidate()
        // 语言 / 12-24 小时制可能随配置变化：重建时钟格式并重推底栏状态
        if (::statusWatcher.isInitialized) statusWatcher.refresh()
    }

    /**
     * 刘海/挖孔安全区：沉浸式下系统栏不可见，只处理 displayCutout（L8）。
     * 由 GameView 自身把安全区应用到 HUD/底栏/棋盘视口。
     */
    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(gameView) { _, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            gameView.setSafeInsets(cutout.left, cutout.top, cutout.right, cutout.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(gameView)
    }

    private fun immersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** 焦点回到本窗口时补设沉浸式（从通知栏/系统弹层返回后系统栏可能重现，L8） */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    /**
     * 物理按键（掌机手柄）→ GameView。
     * H3：不再按 `device.isVirtual` 一刀切——部分掌机/转接器会把内置手柄上报为 virtual，
     * 那会导致游戏主输入完全失效。策略：
     *  1) 手柄来源（SOURCE_GAMEPAD/JOYSTICK）且非虚拟 → 记录"见过物理手柄"；
     *  2) 只有「已见过物理手柄」后，才过滤虚拟设备上非手柄来源的事件（软键盘/远程输入）；
     *  3) 从未见过物理手柄时不过滤（兜底，保证掌机可用）。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isVirtual = event.device?.isVirtual == true
        val fromGamepad = event.source and
            (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK) != 0
        if (fromGamepad && !isVirtual) sawPhysicalGamepadKey = true
        if (isVirtual && !fromGamepad && sawPhysicalGamepadKey) {
            return super.dispatchKeyEvent(event)
        }
        val consumed = when (event.action) {
            // repeatCount > 0 标记系统自动重复，由 GameView 决定哪些动作忽略重复
            KeyEvent.ACTION_DOWN -> gameView.handleKeyDown(event.keyCode, event.repeatCount > 0)
            KeyEvent.ACTION_UP -> gameView.handleKeyUp(event.keyCode)
            else -> false
        }
        if (consumed) return true
        return super.dispatchKeyEvent(event)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // 手势导航（Android 10+ 常见）不产生 KEYCODE_BACK：
        // 把返回手势转成游戏的 Back 键（关对话框 / 打开菜单），避免误滑直接退出游戏。
        // API 33+ 已改为 OnBackInvokedCallback（见 onCreate），这里覆盖旧设备。
        if (!gameView.handleKeyDown(KeyCodes.BACK)) {
            super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // M19/AA-5：配置变化（含 configChanges 未覆盖的）时通过 Bundle 兜底保存进行中对局
        gameView.currentSnapshot()?.let {
            outState.putString(STATE_ROUND, RoundSnapshotCodec.encode(it))
        }
    }

    /** 启动时恢复进行中的对局：优先 Bundle，其次 onPause 落盘的 prefs 快照。 */
    private fun restoreRound(savedInstanceState: Bundle?) {
        val encoded = savedInstanceState?.getString(STATE_ROUND)
            ?: storage.getString(PREF_ROUND)
        storage.remove(PREF_ROUND)
        if (encoded.isNullOrEmpty()) return
        RoundSnapshotCodec.decode(encoded)?.let { gameView.restoreSnapshot(it) }
    }

    /** 把当前对局写入/清除 prefs（进程被杀后仍能"继续上局"）。 */
    private fun persistRound() {
        val snapshot = gameView.currentSnapshot()
        if (snapshot == null) {
            storage.remove(PREF_ROUND)
        } else {
            storage.putString(PREF_ROUND, RoundSnapshotCodec.encode(snapshot))
        }
    }

    override fun onPause() {
        super.onPause()
        // 退到后台先停状态监听：避免向不在前台的 GameView 推更新，也省掉广播开销
        statusWatcher.stop()
        gameView.onAppPaused()
        sound.autoPause()
        persistRound()
    }

    override fun onResume() {
        super.onResume()
        sound.autoResume()
        gameView.onAppResumed()
        // 回到前台才注册广播：注册即拿到 sticky 电量，并立即推一次「时钟 + 电量」
        statusWatcher.start()
        immersive()
    }

    override fun onDestroy() {
        // 先注销状态监听，避免广播回调打到正在销毁的 GameView
        statusWatcher.stop()
        gameView.onDestroy()
        sound.release()
        super.onDestroy()
    }
}
