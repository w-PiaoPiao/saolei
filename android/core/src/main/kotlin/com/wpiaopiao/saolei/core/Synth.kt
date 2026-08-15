package com.wpiaopiao.saolei.core

/**
 * 音效合成：用与桌面版 src/audio.js 相同的参数（频率/波形/时长/音量/指数衰减）
 * 在运行时生成 6 个 PCM WAV，零外部资源文件。
 */
object Synth {

    const val SAMPLE_RATE = 44100

    enum class Wave { SINE, SQUARE, TRIANGLE, SAWTOOTH }

    /** 单音：波形 × 指数衰减包络（对齐 Web Audio exponentialRampToValueAtTime(0.001)） */
    fun tone(freq: Double, duration: Double, wave: Wave, volume: Double): ShortArray {
        val n = (SAMPLE_RATE * duration).toInt()
        val out = ShortArray(n)
        val twoPi = 2.0 * Math.PI
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val phase = freq * t * twoPi
            val raw = when (wave) {
                Wave.SINE -> Math.sin(phase)
                Wave.SQUARE -> if (Math.sin(phase) >= 0) 1.0 else -1.0
                Wave.TRIANGLE -> 2.0 / Math.PI * Math.asin(Math.sin(phase))
                Wave.SAWTOOTH -> 2.0 * (t * freq - Math.floor(0.5 + t * freq))
            }
            val env = volume * Math.exp(Math.log(0.001 / volume) * t / duration)
            out[i] = (raw * env * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /** 白噪声：随机 × 线性衰减（对齐 audio.js playNoise） */
    fun noise(duration: Double, volume: Double): ShortArray {
        val n = (SAMPLE_RATE * duration).toInt()
        val out = ShortArray(n)
        val rnd = java.util.Random()
        for (i in 0 until n) {
            val t = i.toDouble() / SAMPLE_RATE
            val env = volume * (1.0 - t / duration)
            out[i] = ((rnd.nextDouble() * 2 - 1) * env * Short.MAX_VALUE)
                .toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /** 混音：按起始秒偏移叠加多条音轨（超出则截断并饱和钳制） */
    fun mix(vararg parts: Pair<Double, ShortArray>): ShortArray {
        val total = parts.maxOfOrNull { (offset, data) ->
            (offset * SAMPLE_RATE).toInt() + data.size
        } ?: 0
        val out = ShortArray(total)
        for ((offset, data) in parts) {
            val start = (offset * SAMPLE_RATE).toInt()
            for (i in data.indices) {
                val idx = start + i
                if (idx >= out.size) break
                out[idx] = (out[idx].toInt() + data[i].toInt())
                    .coerceIn(-32768, 32767).toShort()
            }
        }
        return out
    }

    /** 16bit/44100Hz 单声道 PCM → WAV 文件字节 */
    fun wavBytes(pcm: ShortArray): ByteArray {
        val dataSize = pcm.size * 2
        val buf = java.nio.ByteBuffer.allocate(44 + dataSize)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataSize)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(1)          // PCM
        buf.putShort(1)          // mono
        buf.putInt(SAMPLE_RATE)
        buf.putInt(SAMPLE_RATE * 2) // byte rate
        buf.putShort(2)          // block align
        buf.putShort(16)         // bits per sample
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataSize)
        for (s in pcm) buf.putShort(s)
        return buf.array()
    }

    // ---------- 6 种音效（参数对齐 audio.js） ----------

    fun click(): ShortArray = tone(600.0, 0.05, Wave.SQUARE, 0.15)
    fun flag(): ShortArray = tone(400.0, 0.08, Wave.SQUARE, 0.12)
    fun chordSuccess(): ShortArray = tone(800.0, 0.04, Wave.SINE, 0.10)
    fun chordFail(): ShortArray = tone(150.0, 0.15, Wave.TRIANGLE, 0.25)
    fun explosion(): ShortArray = mix(
        0.0 to noise(0.4, 0.4),
        0.0 to tone(60.0, 0.3, Wave.SAWTOOTH, 0.3)
    )
    fun win(): ShortArray = mix(
        0.0 to tone(523.0, 0.12, Wave.SINE, 0.2),
        0.12 to tone(659.0, 0.12, Wave.SINE, 0.2),
        0.24 to tone(784.0, 0.12, Wave.SINE, 0.2),
        0.36 to tone(1047.0, 0.30, Wave.SINE, 0.25)
    )

    val SOUNDS: Map<String, () -> ShortArray> = mapOf(
        "click" to ::click,
        "flag" to ::flag,
        "chordSuccess" to ::chordSuccess,
        "chordFail" to ::chordFail,
        "explosion" to ::explosion,
        "win" to ::win
    )
}
