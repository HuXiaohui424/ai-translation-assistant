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

type OutgoingAudioMessage = AudioChunkMessage | AudioSilenceMessage

const INITIAL_RECONNECT_DELAY_MS = 1000
const MAX_RECONNECT_DELAY_MS = 10000
const MAX_RECONNECT_ATTEMPTS = 8
const MAX_PENDING_MESSAGES = 120

export class SubtitleWebSocketClient {
  private socket?: WebSocket
  private reconnectTimer?: number
  private shouldReconnect = false
  private reconnectAttempts = 0
  private pendingMessages: OutgoingAudioMessage[] = []

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
      this.reconnectAttempts = 0
      this.options.onStateChange('connected')
      this.flushPendingMessages()
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
      if (!this.shouldReconnect) {
        this.options.onStateChange('error')
      }
    })
  }

  disconnect(): void {
    this.shouldReconnect = false
    this.clearReconnectTimer()
    this.pendingMessages = []
    this.reconnectAttempts = 0
    this.socket?.close()
    this.socket = undefined
  }

  isConnected(): boolean {
    return this.socket?.readyState === WebSocket.OPEN
  }

  sendAudioChunk(data: string): void {
    const message: AudioChunkMessage = {
      type: 'audio.chunk',
      sessionId: this.options.sessionId,
      timestamp: Date.now(),
      sampleRate: 16000,
      format: 'pcm16',
      data
    }

    this.sendOrQueue(message)
  }

  sendSilence(durationMs: number): void {
    const message: AudioSilenceMessage = {
      type: 'audio.silence',
      sessionId: this.options.sessionId,
      timestamp: Date.now(),
      durationMs
    }

    this.sendOrQueue(message)
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

    if (this.reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
      this.options.onStateChange('error')
      return
    }

    this.reconnectAttempts += 1
    const reconnectDelayMs = Math.min(
      INITIAL_RECONNECT_DELAY_MS * 2 ** (this.reconnectAttempts - 1),
      MAX_RECONNECT_DELAY_MS
    )

    this.reconnectTimer = window.setTimeout(() => {
      this.reconnectTimer = undefined
      this.connect()
    }, reconnectDelayMs)
  }

  private clearReconnectTimer(): void {
    window.clearTimeout(this.reconnectTimer)
    this.reconnectTimer = undefined
  }

  private sendOrQueue(message: OutgoingAudioMessage): void {
    if (this.socket?.readyState === WebSocket.OPEN) {
      this.socket.send(JSON.stringify(message))
      return
    }

    this.pendingMessages.push(message)

    if (this.pendingMessages.length > MAX_PENDING_MESSAGES) {
      this.pendingMessages.splice(0, this.pendingMessages.length - MAX_PENDING_MESSAGES)
    }
  }

  private flushPendingMessages(): void {
    if (this.socket?.readyState !== WebSocket.OPEN || this.pendingMessages.length === 0) {
      return
    }

    const messages = this.pendingMessages
    this.pendingMessages = []
    messages.forEach((message) => this.socket?.send(JSON.stringify(message)))
  }
}
