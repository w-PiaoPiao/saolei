package com.wpiaopiao.saolei

import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.wpiaopiao.saolei.core.KeyBindings
import com.wpiaopiao.saolei.core.KeyCodes
import com.wpiaopiao.saolei.core.Storage
import com.wpiaopiao.saolei.core.StatsManager

/**
 * 主 Activity：沉浸全屏横屏、物理按键分发、生命周期（暂停/恢复计时）。
 * 所有绘制与手柄交互都在 GameView 中完成。
 */
class MainActivity : Activity() {

    private lateinit var gameView: GameView
    private lateinit var sound: SoundManager
    private lateinit var storage: Storage

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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
        // About 页显示运行时版本号（Gradle/CI 注入），避免与 build.gradle 双处硬编码漂移
        gameView.setAppVersion(versionName())
        setContentView(gameView)
        immersive()
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
    }

    private fun immersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** 物理按键（掌机手柄）→ GameView；虚拟设备（软键盘等）不拦截。 */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.device != null && event.device.isVirtual) {
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

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 手势导航（Android 10+ 常见）不产生 KEYCODE_BACK：
        // 把返回手势转成游戏的 Back 键（关对话框 / 打开菜单），避免误滑直接退出游戏
        if (!gameView.handleKeyDown(KeyCodes.BACK)) {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        gameView.onAppPaused()
    }

    override fun onResume() {
        super.onResume()
        gameView.onAppResumed()
        immersive()
    }

    override fun onDestroy() {
        gameView.onDestroy()
        sound.release()
        super.onDestroy()
    }
}
