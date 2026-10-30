/**
 * 音效：WebAudio 合成，无音频资源文件。
 * 所有创建/播放路径都做了失败兜底：音频不可用（无声卡/上下文被禁）时静默降级，
 * 绝不把异常抛回 UI 调用链（否则会跳过 render()，棋盘停在旧状态）。
 */
class AudioManager {
  constructor() {
    this.ctx = null
    this.enabled = true
    this.unavailable = false
  }

  ensureCtx() {
    if (this.unavailable) return null
    if (!this.ctx) {
      try {
        const Ctor = window.AudioContext || window.webkitAudioContext
        if (!Ctor) {
          this.unavailable = true
          return null
        }
        this.ctx = new Ctor()
      } catch {
        this.unavailable = true
        return null
      }
    }
    if (this.ctx.state === 'suspended') {
      try {
        this.ctx.resume()
      } catch {
        return null
      }
    }
    return this.ctx
  }

  playTone(freq, duration, type = 'sine', volume = 0.3) {
    if (!this.enabled) return
    const ctx = this.ensureCtx()
    if (!ctx) return
    try {
      const osc = ctx.createOscillator()
      const gain = ctx.createGain()
      osc.type = type
      osc.frequency.value = freq
      gain.gain.setValueAtTime(volume, ctx.currentTime)
      gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + duration)
      osc.connect(gain)
      gain.connect(ctx.destination)
      osc.start()
      osc.stop(ctx.currentTime + duration)
    } catch {
      // 音频节点失败不影响游戏逻辑
    }
  }

  playNoise(duration, volume = 0.3) {
    if (!this.enabled) return
    const ctx = this.ensureCtx()
    if (!ctx) return
    try {
      const bufferSize = Math.floor(ctx.sampleRate * duration)
      if (!(bufferSize > 0)) return
      const buffer = ctx.createBuffer(1, bufferSize, ctx.sampleRate)
      const data = buffer.getChannelData(0)
      for (let i = 0; i < bufferSize; i++) {
        data[i] = (Math.random() * 2 - 1) * (1 - i / bufferSize)
      }
      const source = ctx.createBufferSource()
      source.buffer = buffer
      const gain = ctx.createGain()
      gain.gain.setValueAtTime(volume, ctx.currentTime)
      gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + duration)
      source.connect(gain)
      gain.connect(ctx.destination)
      source.start()
    } catch {
      // 静默降级
    }
  }

  click() {
    this.playTone(600, 0.05, 'square', 0.15)
  }

  flag() {
    this.playTone(400, 0.08, 'square', 0.12)
  }

  chordSuccess() {
    this.playTone(800, 0.04, 'sine', 0.1)
  }

  chordFail() {
    this.playTone(150, 0.15, 'triangle', 0.25)
  }

  explosion() {
    this.playNoise(0.4, 0.4)
    this.playTone(60, 0.3, 'sawtooth', 0.3)
  }

  win() {
    // 与旧实现相同的四音上行，但每个音独立兜底
    this.playTone(523, 0.12, 'sine', 0.2)
    setTimeout(() => this.playTone(659, 0.12, 'sine', 0.2), 120)
    setTimeout(() => this.playTone(784, 0.12, 'sine', 0.2), 240)
    setTimeout(() => this.playTone(1047, 0.3, 'sine', 0.25), 360)
  }
}

if (typeof module === 'object' && module.exports) {
  module.exports = AudioManager
}
