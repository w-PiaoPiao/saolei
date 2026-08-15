package com.wpiaopiao.saolei

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.wpiaopiao.saolei.core.KeyBindings
import com.wpiaopiao.saolei.core.Storage
import com.wpiaopiao.saolei.core.StatsManager

/**
 * 主 Activity：沉浸全屏横屏、物理按键分发、生命周期（暂停/恢复计时）。
 * 所有绘制与手柄交互都在 GameView 中完成。
 */
class MainActivity : Activity() {

    private lateinit var gameView: GameView
    private lateinit var sound: SoundManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val storage: Storage = PrefsStorage(getSharedPreferences("saolei", MODE_PRIVATE))
        val bindings = KeyBindings(storage)
        val stats = StatsManager(storage)
        sound = SoundManager(this)
        sound.init()
        val muted = storage.getString("muted") == "true"
        sound.setEnabled(!muted)

        gameView = GameView(this, bindings, stats, sound, storage)
        gameView.setThemeValue(storage.getString("theme") ?: "light")
        setContentView(gameView)
        immersive()
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
            KeyEvent.ACTION_DOWN -> gameView.handleKeyDown(event.keyCode)
            KeyEvent.ACTION_UP -> gameView.handleKeyUp(event.keyCode)
            else -> false
        }
        if (consumed) return true
        return super.dispatchKeyEvent(event)
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
