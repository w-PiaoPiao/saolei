package com.wpiaopiao.saolei.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 音效合成测试（L20）：覆盖 v1.3 的指数包络修正、SFX_VERSION 与 WAV 头。
 */
class SynthTest {

    @Test
    fun `SFX_VERSION 为 2（缓存文件名版本）`() {
        assertEquals(2, Synth.SFX_VERSION)
    }

    @Test
    fun `tone 样本数、取值范围与指数包络端点`() {
        val pcm = Synth.tone(600.0, 0.05, Synth.Wave.SQUARE, 0.15)
        assertEquals((Synth.SAMPLE_RATE * 0.05).toInt(), pcm.size)
        val peak = pcm.maxOf { Math.abs(it.toInt()) }
        assertTrue("峰值不应超过音量×满幅太多", peak <= (0.15 * Short.MAX_VALUE).toInt() + 2)
        val tail = pcm.takeLast(pcm.size / 10).maxOf { Math.abs(it.toInt()) }
        assertTrue("指数包络末段应接近静音（尾值=$tail）", tail <= 400)
        assertTrue("首段应有明显幅度", pcm.take(pcm.size / 10).maxOf { Math.abs(it.toInt()) } > tail)
    }

    @Test
    fun `noise 补上指数包络：前段能量显著高于后段`() {
        val pcm = Synth.noise(0.4, 0.4)
        assertEquals((Synth.SAMPLE_RATE * 0.4).toInt(), pcm.size)
        fun rms(from: Int, to: Int): Double {
            var sum = 0.0
            for (i in from until to) sum += pcm[i].toDouble() * pcm[i]
            return Math.sqrt(sum / (to - from))
        }
        val first = rms(0, pcm.size / 4)
        val last = rms(pcm.size * 3 / 4, pcm.size)
        assertTrue("前段 RMS($first) 应明显大于后段 RMS($last)（线性×指数双衰减）", first > last * 2)
        assertTrue(last > 0.0)
        assertTrue(pcm.all { it in Short.MIN_VALUE..Short.MAX_VALUE })
    }

    @Test
    fun `mix 按偏移叠加并饱和钳制`() {
        // 超过满幅的叠加应饱和到 32767 / -32768，而不是溢出回绕
        val loud = ShortArray(5) { 30_000 }
        val positive = Synth.mix(0.0 to loud, 0.0 to loud)
        assertTrue(positive.all { it.toInt() == Short.MAX_VALUE.toInt() })
        val negative = Synth.mix(0.0 to ShortArray(5) { -30_000 }, 0.0 to ShortArray(5) { -30_000 })
        assertTrue(negative.all { it.toInt() == Short.MIN_VALUE.toInt() })
        // 偏移混音长度 = offset 样本数 + 数据长度；前面补零
        val a = Synth.tone(440.0, 0.02, Synth.Wave.SINE, 0.5)
        val offset = Synth.mix(0.01 to a)
        assertEquals((Synth.SAMPLE_RATE * 0.01).toInt() + a.size, offset.size)
        assertTrue(offset.take((Synth.SAMPLE_RATE * 0.01).toInt()).all { it.toInt() == 0 })
    }

    @Test
    fun `wavBytes 生成合法 16bit 单声道 WAV 头`() {
        val pcm = Synth.tone(500.0, 0.01, Synth.Wave.SINE, 0.2)
        val bytes = Synth.wavBytes(pcm)
        assertEquals(44 + pcm.size * 2, bytes.size)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(bytes.size - 8, buf.getInt(4))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals(1, buf.getShort(20).toInt()) // PCM
        assertEquals(1, buf.getShort(22).toInt()) // mono
        assertEquals(Synth.SAMPLE_RATE, buf.getInt(24))
        assertEquals(Synth.SAMPLE_RATE * 2, buf.getInt(28)) // byte rate
        assertEquals(2, buf.getShort(32).toInt()) // block align
        assertEquals(16, buf.getShort(34).toInt()) // bits per sample
        assertEquals(pcm.size * 2, buf.getInt(40))
        assertFalse("样本不应全为 0", pcm.all { it.toInt() == 0 })
    }

    @Test
    fun `6 种音效都能生成非空 PCM`() {
        assertEquals(6, Synth.SOUNDS.size)
        for ((name, factory) in Synth.SOUNDS) {
            val pcm = factory()
            assertTrue("$name 应有样本", pcm.isNotEmpty())
            assertFalse("$name 不应全静音", pcm.all { it.toInt() == 0 })
        }
    }
}
