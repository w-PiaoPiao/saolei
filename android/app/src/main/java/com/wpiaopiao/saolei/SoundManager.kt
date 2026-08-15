package com.wpiaopiao.saolei

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.wpiaopiao.saolei.core.Synth
import java.io.File

/**
 * 音效播放：首次启动用 core.Synth 合成 6 个 WAV 到缓存目录，SoundPool 播放。
 * 参数与桌面版 audio.js 一致。
 */
class SoundManager(context: Context) {

    private val appContext = context.applicationContext
    private var enabled = true
    private var pool: SoundPool? = null
    private val soundIds = mutableMapOf<String, Int>()

    fun init() {
        if (pool != null) return
        val p = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        pool = p
        val dir = File(appContext.cacheDir, "sfx").apply { mkdirs() }
        for ((name, gen) in Synth.SOUNDS) {
            val f = File(dir, "$name.wav")
            if (!f.exists() || f.length() == 0L) {
                f.writeBytes(Synth.wavBytes(gen()))
            }
            soundIds[name] = p.load(f.absolutePath, 1)
        }
    }

    fun setEnabled(b: Boolean) {
        enabled = b
    }

    fun play(name: String) {
        if (!enabled) return
        val p = pool ?: return
        soundIds[name]?.let { p.play(it, 1f, 1f, 1, 0, 1f) }
    }

    fun release() {
        pool?.release()
        pool = null
        soundIds.clear()
    }
}
