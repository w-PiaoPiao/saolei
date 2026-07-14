class AudioManager {
  constructor() {
    this.ctx = null
    this.enabled = true
  }

  ensureCtx() {
    if (!this.ctx) {
      this.ctx = new (window.AudioContext || window.webkitAudioContext)()
    }
    if (this.ctx.state === 'suspended') {
      this.ctx.resume()
    }
  }

  playTone(freq, duration, type = 'sine', volume = 0.3) {
    if (!this.enabled) return
    this.ensureCtx()
    const osc = this.ctx.createOscillator()
    const gain = this.ctx.createGain()
    osc.type = type
    osc.frequency.value = freq
    gain.gain.setValueAtTime(volume, this.ctx.currentTime)
    gain.gain.exponentialRampToValueAtTime(0.001, this.ctx.currentTime + duration)
    osc.connect(gain)
    gain.connect(this.ctx.destination)
    osc.start()
    osc.stop(this.ctx.currentTime + duration)
  }

  playNoise(duration, volume = 0.3) {
    if (!this.enabled) return
    this.ensureCtx()
    const bufferSize = this.ctx.sampleRate * duration
    const buffer = this.ctx.createBuffer(1, bufferSize, this.ctx.sampleRate)
    const data = buffer.getChannelData(0)
    for (let i = 0; i < bufferSize; i++) {
      data[i] = (Math.random() * 2 - 1) * (1 - i / bufferSize)
    }
    const source = this.ctx.createBufferSource()
    source.buffer = buffer
    const gain = this.ctx.createGain()
    gain.gain.setValueAtTime(volume, this.ctx.currentTime)
    gain.gain.exponentialRampToValueAtTime(0.001, this.ctx.currentTime + duration)
    source.connect(gain)
    gain.connect(this.ctx.destination)
    source.start()
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
    this.playTone(523, 0.12, 'sine', 0.2)
    setTimeout(() => this.playTone(659, 0.12, 'sine', 0.2), 120)
    setTimeout(() => this.playTone(784, 0.12, 'sine', 0.2), 240)
    setTimeout(() => this.playTone(1047, 0.3, 'sine', 0.25), 360)
  }
}
