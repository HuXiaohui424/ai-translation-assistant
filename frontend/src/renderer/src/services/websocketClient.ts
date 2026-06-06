export type WebSocketConnectionState = 'connecting' | 'connected' | 'disconnected' | 'error'

export interface AudioChunkMessage {
  type: 'audio.chunk'
  sessionId: string
  timestamp: number
  sampleRate: number
  format: 'pcm16'
  data: string
}

export interface AudioSilenceMessage {
  type: 'audio.silence'
  sessionId: string
  timestamp: number
  durationMs: number
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

export interface RealtimeStatusMessage {
  type: 'realtime.status'
  sessionId: string
  status: 'connecting' | 'connected' | 'completed' | 'error'
  message: string
}

interface WebSocketClientOptions {
  url: string
  sessionId: string
  onSubtitleUpdate: (message: SubtitleUpdateMessage) => void
  onRealtimeStatus: (message: RealtimeStatusMessage) => void
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

  sendSilence(durationMs: number): void {
    if (this.socket?.readyState !== WebSocket.OPEN) {
      return
    }

    const message: AudioSilenceMessage = {
      type: 'audio.silence',
      sessionId: this.options.sessionId,
      timestamp: Date.now(),
      durationMs
    }

    this.socket.send(JSON.stringify(message))
  }

  private handleMessage(data: unknown): void {
    if (typeof data !== 'string') {
      return
    }

    let message: SubtitleUpdateMessage | RealtimeStatusMessage

    try {
      message = JSON.parse(data) as SubtitleUpdateMessage | RealtimeStatusMessage
    } catch {
      return
    }

    if (message.type === 'subtitle.update') {
      this.options.onSubtitleUpdate(message)
      return
    }

    if (message.type === 'realtime.status') {
      this.options.onRealtimeStatus(message)
    }
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
