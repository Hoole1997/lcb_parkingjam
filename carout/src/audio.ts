// WebAudio 程序化音效（无需素材文件）
export class Sound {
  private ctx: AudioContext | null = null
  enabled = true

  // 必须在用户手势里调用以解锁音频
  unlock() {
    if (!this.ctx) {
      const AC = window.AudioContext || (window as any).webkitAudioContext
      if (AC) this.ctx = new AC()
    }
    if (this.ctx?.state === 'suspended') this.ctx.resume()
  }

  private get ac(): AudioContext | null {
    return this.enabled ? this.ctx : null
  }

  private tone(
    freq: number,
    dur: number,
    type: OscillatorType = 'sine',
    vol = 0.2,
    when = 0,
    slideTo?: number,
  ) {
    const ac = this.ac
    if (!ac) return
    const t = ac.currentTime + when
    const osc = ac.createOscillator()
    const gain = ac.createGain()
    osc.type = type
    osc.frequency.setValueAtTime(freq, t)
    if (slideTo) osc.frequency.exponentialRampToValueAtTime(slideTo, t + dur)
    gain.gain.setValueAtTime(vol, t)
    gain.gain.exponentialRampToValueAtTime(0.001, t + dur)
    osc.connect(gain).connect(ac.destination)
    osc.start(t)
    osc.stop(t + dur)
  }

  private noise(dur: number, vol = 0.3, freq = 800) {
    const ac = this.ac
    if (!ac) return
    const len = Math.floor(ac.sampleRate * dur)
    const buf = ac.createBuffer(1, len, ac.sampleRate)
    const data = buf.getChannelData(0)
    for (let i = 0; i < len; i++) data[i] = (Math.random() * 2 - 1) * (1 - i / len)
    const src = ac.createBufferSource()
    src.buffer = buf
    const filter = ac.createBiquadFilter()
    filter.type = 'lowpass'
    filter.frequency.value = freq
    const gain = ac.createGain()
    gain.gain.value = vol
    src.connect(filter).connect(gain).connect(ac.destination)
    src.start()
  }

  click() {
    this.tone(600, 0.06, 'square', 0.08)
  }

  // 车辆驶出：引擎加速声
  drive() {
    this.tone(90, 0.45, 'sawtooth', 0.15, 0, 320)
    this.noise(0.35, 0.1, 500)
  }

  // 停入车位
  park() {
    this.tone(220, 0.12, 'sine', 0.15, 0, 140)
    this.noise(0.08, 0.1, 400)
  }

  // 乘客上车（音高随人数递增更带感）
  board(step = 0) {
    this.tone(500 + step * 60, 0.09, 'triangle', 0.16, 0, 700 + step * 60)
  }

  // 坐满驶离
  depart() {
    this.tone(130, 0.5, 'sawtooth', 0.16, 0, 420)
    this.tone(784, 0.18, 'triangle', 0.14, 0.05)
    this.tone(1047, 0.22, 'triangle', 0.14, 0.16)
  }

  coin() {
    this.tone(988, 0.08, 'square', 0.1)
    this.tone(1319, 0.16, 'square', 0.1, 0.07)
  }

  // 撞车
  crash() {
    this.tone(120, 0.2, 'square', 0.2, 0, 50)
    this.noise(0.2, 0.35, 300)
    if (navigator.vibrate) navigator.vibrate(60)
  }

  win() {
    const notes = [523, 659, 784, 1047]
    notes.forEach((f, i) => this.tone(f, 0.3, 'triangle', 0.2, i * 0.12))
  }

  lose() {
    this.tone(392, 0.25, 'sawtooth', 0.16)
    this.tone(311, 0.25, 'sawtooth', 0.16, 0.22)
    this.tone(233, 0.5, 'sawtooth', 0.18, 0.44, 180)
  }
}

export const sound = new Sound()
