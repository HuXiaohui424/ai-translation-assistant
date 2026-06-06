export type WebSocketConnectionState = 'connecting' | 'connected' | 'disconnected' | 'error'

export interface AudioChunkMessage {
  type: 'audio.chunk'
  sessionId: string
  timestamp: number
  sampleRate: number
  format: 'pcm16'
  data: string
}

export interface AudioSentenceEndMessage {
  type: 'audio.sentence_end'
  sessionId: string
  timestamp: number
}

export interface SubtitleUpdateMessage {
  type: 'subtitle.update'
  sessionId: string
  segmentId: string
  revision: number
  source: string
  translation: string
  isFinal: boolean
  latencyMs: number
}

interface WebSocketClientOptions {
  url: string
  sessionId: string
  onSubtitleUpdate: (message: SubtitleUpdateMessage) => void
  onStateChange: (state: WebSocketConnectionState) => void
}

const RECONNECT_DELAY_MS = 2000

export class SubtitleWebSocketClient {
  private socket?: WebSocket
  private reconnectTimer?: number
  private shouldReconnect = false

  constructor(private readonly options: WebSocketClientOptions) {}

  connect(): void {
    if (this.socket?.readyState === WebSocket.OPEN || this.socket?.readyState === WebSocket.CONNECTING) {
      return
    }

    this.shouldReconnect = true
    this.options.onStateChange('connecting')
    this.socket = new WebSocket(this.options.url)

    this.socket.addEventListener('open', () => {
      this.clearReconnectTimer()
      this.options.onStateChange('connected')
    })

    this.socket.addEventListener('message', (event) => {
      this.handleMessage(event.data)
    })

    this.socket.addEventListener('close', () => {
      this.socket = undefined
      this.options.onStateChange('disconnected')
      this.scheduleReconnect()
    })

    this.socket.addEventListener('error', () => {
      this.options.onStateChange('error')
    })
  }

  disconnect(): void {
    this.shouldReconnect = false
    this.clearReconnectTimer()
    this.socket?.close()
    this.socket = undefined
  }

  isConnected(): boolean {
    return this.socket?.readyState === WebSocket.OPEN
  }

  sendAudioChunk(data: string): void {
    if (this.socket?.readyState !== WebSocket.OPEN) {
      return
    }

    const message: AudioChunkMessage = {
      type: 'audio.chunk',
      sessionId: this.options.sessionId,
      timestamp: Date.now(),
      sampleRate: 16000,
      format: 'pcm16',
      data
    }

    this.socket.send(JSON.stringify(message))
  }

  sendSentenceEnd(): void {
    if (this.socket?.readyState !== WebSocket.OPEN) {
      return
    }

    const message: AudioSentenceEndMessage = {
      type: 'audio.sentence_end',
      sessionId: this.options.sessionId,
      timestamp: Date.now()
    }

    this.socket.send(JSON.stringify(message))
  }

  private handleMessage(data: unknown): void {
    if (typeof data !== 'string') {
      return
    }

    let message: SubtitleUpdateMessage

    try {
      message = JSON.parse(data) as SubtitleUpdateMessage
    } catch {
      return
    }

    if (message.type !== 'subtitle.update') {
      return
    }

    this.options.onSubtitleUpdate(message)
  }

  private scheduleReconnect(): void {
    if (!this.shouldReconnect || this.reconnectTimer) {
      return
    }

    this.reconnectTimer = window.setTimeout(() => {
      this.reconnectTimer = undefined
      this.connect()
    }, RECONNECT_DELAY_MS)
  }

  private clearReconnectTimer(): void {
    window.clearTimeout(this.reconnectTimer)
    this.reconnectTimer = undefined
  }
}
