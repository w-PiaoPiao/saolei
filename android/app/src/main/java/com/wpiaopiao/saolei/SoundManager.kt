package com.wpiaopiao.saolei

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.wpiaopiao.saolei.core.Synth
import java.io.File

/**
 * 音效播放：首次启动用 core.Synth 合成 6 个 WAV 到缓存目录，SoundPool 播放。
 * 参数与桌面版 audio.js 一致。
 *
 * L7/AA-12 修复：
 * - 缓存文件名带 [Synth.SFX_VERSION]，合成参数更新后存量设备也能拿到新音效，并清理旧缓存；
 * - 注册 OnLoadCompleteListener：冷启动加载完成前的播放请求暂存，加载完成后补播（不再静默）；
 * - 提供 autoPause/autoResume，切后台时暂停 SoundPool。
 */
class SoundManager(context: Context) {

    private companion object {
        const val CACHE_DIR = "sfx"
        /** 冷启动加载未完成时最多暂存的播放请求数 */
        const val MAX_PENDING = 2
    }

    private val appContext = context.applicationContext
    private var enabled = true
    private var pool: SoundPool? = null
    private val soundIds = mutableMapOf<String, Int>()
    private val loadedIds = mutableSetOf<Int>()
    private val pending = ArrayDeque<String>()

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
        p.setOnLoadCompleteListener { _, sampleId, status ->
            if (status != 0) return@setOnLoadCompleteListener
            loadedIds.add(sampleId)
            // 冷启动首音：该音效加载完成时若有暂存请求，补播最近一次
            val index = pending.indexOfFirst { soundIds[it] == sampleId }
            if (index >= 0) {
                pending.removeAt(index)
                if (enabled) p.play(sampleId, 1f, 1f, 1, 0, 1f)
            }
        }
        pool = p

        val dir = File(appContext.cacheDir, CACHE_DIR).apply { mkdirs() }
        val expected = Synth.SOUNDS.keys.mapTo(mutableSetOf()) { "$it-v${Synth.SFX_VERSION}.wav" }
        // 清理旧版本/无版本号的缓存音效（改合成参数后能立即生效）
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name !in expected) f.delete()
        }
        for ((name, gen) in Synth.SOUNDS) {
            val f = File(dir, "$name-v${Synth.SFX_VERSION}.wav")
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
        val id = soundIds[name] ?: return
        if (id !in loadedIds) {
            // 冷启动加载未完成：暂存最近请求，加载完成后补播（不再静默丢音）
            if (pending.size >= MAX_PENDING) pending.removeFirst()
            pending.addLast(name)
            return
        }
        p.play(id, 1f, 1f, 1, 0, 1f)
    }

    /** 切后台暂停所有正在播放的流（L7）。 */
    fun autoPause() {
        pool?.autoPause()
    }

    /** 回到前台恢复播放（L7）。 */
    fun autoResume() {
        pool?.autoResume()
    }

    fun release() {
        pool?.release()
        pool = null
        soundIds.clear()
        loadedIds.clear()
        pending.clear()
    }
}
