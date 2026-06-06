export type AudioCaptureMode = 'system' | 'microphone'
export type AudioCaptureState = 'idle' | 'requesting' | 'capturing' | 'fallback' | 'error'

interface AudioCaptureServiceOptions {
  mode: AudioCaptureMode
  onAudioChunk: (base64Pcm16: string, rms: number) => void
  onSilence: (durationMs: number) => void
  onStateChange: (state: AudioCaptureState, activeMode: AudioCaptureMode) => void
}

const TARGET_SAMPLE_RATE = 16000
const CHUNK_DURATION_MS = 100
const TARGET_CHUNK_SAMPLES = TARGET_SAMPLE_RATE / (1000 / CHUNK_DURATION_MS)
const SILENCE_RMS_THRESHOLD = 0.012
const SENTENCE_END_SILENCE_MS = 600

export class AudioCaptureService {
  private stream?: MediaStream
  private audioContext?: AudioContext
  private sourceNode?: MediaStreamAudioSourceNode
  private processorNode?: ScriptProcessorNode
  private outputGainNode?: GainNode
  private outputSamples: number[] = []
  private silentDurationMs = 0
  private sentenceEndSent = false
  private activeMode: AudioCaptureMode

  constructor(private readonly options: AudioCaptureServiceOptions) {
    this.activeMode = options.mode
  }

  async start(): Promise<void> {
    await this.stop()
    this.options.onStateChange('requesting', this.activeMode)

    try {
      this.stream = await this.createStream(this.options.mode)
      this.activeMode = this.options.mode
    } catch {
      if (this.options.mode !== 'system') {
        this.options.onStateChange('error', this.options.mode)
        throw new Error('Microphone audio capture failed')
      }

      this.options.onStateChange('fallback', 'microphone')
      this.stream = await this.createStream('microphone')
      this.activeMode = 'microphone'
    }

    await this.startAudioGraph(this.stream)
    this.options.onStateChange('capturing', this.activeMode)
  }

  async stop(): Promise<void> {
    this.processorNode?.disconnect()
    this.sourceNode?.disconnect()
    this.outputGainNode?.disconnect()
    this.stream?.getTracks().forEach((track) => track.stop())

    if (this.audioContext && this.audioContext.state !== 'closed') {
      await this.audioContext.close()
    }

    this.stream = undefined
    this.audioContext = undefined
    this.sourceNode = undefined
    this.processorNode = undefined
    this.outputGainNode = undefined
    this.outputSamples = []
    this.silentDurationMs = 0
    this.sentenceEndSent = false
    this.options.onStateChange('idle', this.activeMode)
  }

  private async createStream(mode: AudioCaptureMode): Promise<MediaStream> {
    if (mode === 'system') {
      const displayStream = await navigator.mediaDevices.getDisplayMedia({
        audio: true,
        video: true
      })
      displayStream.getVideoTracks().forEach((track) => track.stop())

      const audioTracks = displayStream.getAudioTracks()
      if (audioTracks.length === 0) {
        displayStream.getTracks().forEach((track) => track.stop())
        throw new Error('System audio track is unavailable')
      }

      return new MediaStream(audioTracks)
    }

    return navigator.mediaDevices.getUserMedia({
      audio: {
        echoCancellation: true,
        noiseSuppression: true,
        autoGainControl: true
      },
      video: false
    })
  }

  private async startAudioGraph(stream: MediaStream): Promise<void> {
    this.audioContext = new AudioContext()
    await this.audioContext.resume()

    this.sourceNode = this.audioContext.createMediaStreamSource(stream)
    this.processorNode = this.audioContext.createScriptProcessor(4096, 1, 1)
    this.outputGainNode = this.audioContext.createGain()
    this.outputGainNode.gain.value = 0

    this.processorNode.onaudioprocess = (event) => {
      const inputSamples = event.inputBuffer.getChannelData(0)
      this.appendResampledSamples(inputSamples, this.audioContext?.sampleRate ?? TARGET_SAMPLE_RATE)
      this.flushAudioChunks()
    }

    this.sourceNode.connect(this.processorNode)
    this.processorNode.connect(this.outputGainNode)
    this.outputGainNode.connect(this.audioContext.destination)
  }

  private appendResampledSamples(samples: Float32Array, sourceSampleRate: number): void {
    if (sourceSampleRate === TARGET_SAMPLE_RATE) {
      this.outputSamples.push(...samples)
      return
    }

    const ratio = sourceSampleRate / TARGET_SAMPLE_RATE
    const targetLength = Math.floor(samples.length / ratio)

    for (let index = 0; index < targetLength; index += 1) {
      const sourceIndex = index * ratio
      const lowerIndex = Math.floor(sourceIndex)
      const upperIndex = Math.min(lowerIndex + 1, samples.length - 1)
      const weight = sourceIndex - lowerIndex
      this.outputSamples.push(samples[lowerIndex] * (1 - weight) + samples[upperIndex] * weight)
    }
  }

  private flushAudioChunks(): void {
    while (this.outputSamples.length >= TARGET_CHUNK_SAMPLES) {
      const chunk = this.outputSamples.splice(0, TARGET_CHUNK_SAMPLES)
      const rms = this.calculateRms(chunk)

      if (rms < SILENCE_RMS_THRESHOLD) {
        this.handleSilentChunk()
        continue
      }

      this.silentDurationMs = 0
      this.sentenceEndSent = false
      this.options.onAudioChunk(this.toPcm16Base64(chunk), rms)
    }
  }

  private handleSilentChunk(): void {
    this.silentDurationMs += CHUNK_DURATION_MS

    if (this.silentDurationMs < SENTENCE_END_SILENCE_MS || this.sentenceEndSent) {
      return
    }

    this.sentenceEndSent = true
    this.options.onSilence(this.silentDurationMs)
  }

  private calculateRms(samples: number[]): number {
    const squareSum = samples.reduce((sum, sample) => sum + sample * sample, 0)
    return Math.sqrt(squareSum / samples.length)
  }

  private toPcm16Base64(samples: number[]): string {
    const pcmBuffer = new ArrayBuffer(samples.length * Int16Array.BYTES_PER_ELEMENT)
    const pcmView = new DataView(pcmBuffer)

    samples.forEach((sample, index) => {
      const clampedSample = Math.max(-1, Math.min(1, sample))
      const int16Sample = clampedSample < 0 ? clampedSample * 0x8000 : clampedSample * 0x7fff
      pcmView.setInt16(index * Int16Array.BYTES_PER_ELEMENT, int16Sample, true)
    })

    const bytes = new Uint8Array(pcmBuffer)
    let binary = ''

    for (let index = 0; index < bytes.length; index += 1) {
      binary += String.fromCharCode(bytes[index])
    }

    return window.btoa(binary)
  }
}
