<template>
  <main class="subtitle-shell" aria-label="悬浮字幕窗">
    <section class="subtitle-panel">
      <header class="toolbar drag-region">
        <div class="status-group" aria-label="连接与延迟状态">
          <span class="status-pill" :class="connectionStatusClass">
            <span class="status-dot" aria-hidden="true"></span>
            {{ connectionStatusText }}
          </span>
          <span class="latency-pill">{{ latency }}ms</span>
          <span class="latency-pill">{{ captureStatusText }}</span>
        </div>

        <div class="window-actions no-drag">
          <button class="control-button" type="button" @click="togglePlayback">
            {{ isPlaying ? '暂停' : '开始' }}
          </button>
          <button
            class="icon-button"
            type="button"
            title="隐藏窗口，快捷键 Ctrl/Command + Alt + S 可显示"
            aria-label="隐藏窗口"
            @click="hideWindow"
          >
            ×
          </button>
        </div>
      </header>

      <div class="subtitle-content drag-region">
        <article class="subtitle-block original" aria-label="原文字幕">
          <p>{{ currentCaption.source }}</p>
        </article>

        <div class="divider" aria-hidden="true"></div>

        <article class="subtitle-block translated" aria-label="中文字幕">
          <p>{{ currentCaption.translation }}</p>
        </article>
      </div>

      <footer class="shortcut-hint drag-region">
        <div class="capture-modes no-drag" aria-label="音频采集模式">
          <button
            class="mode-button"
            :class="{ active: captureMode === 'system' }"
            type="button"
            @click="changeCaptureMode('system')"
          >
            系统音频
          </button>
          <button
            class="mode-button"
            :class="{ active: captureMode === 'microphone' }"
            type="button"
            @click="changeCaptureMode('microphone')"
          >
            麦克风
          </button>
        </div>
        <span>RMS {{ currentRms.toFixed(3) }} · Ctrl/Command + Alt + S 隐藏或显示</span>
      </footer>
    </section>
  </main>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  AudioCaptureService,
  type AudioCaptureMode,
  type AudioCaptureState
} from './services/audioCaptureService'
import {
  SubtitleWebSocketClient,
  type RealtimeStatusMessage,
  type SubtitleUpdateMessage,
  type WebSocketConnectionState
} from './services/websocketClient'

type DisplayConnectionStatus = 'disconnected' | 'listening' | 'translating' | 'paused' | 'failed'

interface SubtitleSegment {
  segmentId: string
  revision: number
  source: string
  translation: string
  isFinal: boolean
  updatedAt: number
}

const defaultCaption: SubtitleSegment = {
  segmentId: 'waiting',
  revision: 0,
  source: 'Waiting for mock audio to reach the backend...',
  translation: '等待模拟音频发送到后端...',
  isFinal: false,
  updatedAt: Date.now()
}

const sessionId = 'session-001'
const segments = ref<SubtitleSegment[]>([defaultCaption])
const isPlaying = ref(true)
const latency = ref(0)
const connectionState = ref<WebSocketConnectionState>('connecting')
const realtimeState = ref<RealtimeStatusMessage['status']>('connecting')
const isTranslating = ref(false)
const captureMode = ref<AudioCaptureMode>('system')
const activeCaptureMode = ref<AudioCaptureMode>('system')
const captureState = ref<AudioCaptureState>('idle')
const currentRms = ref(0)

let websocketClient: SubtitleWebSocketClient | undefined
let audioCaptureService: AudioCaptureService | undefined
let translatingResetTimer: number | undefined

const currentCaption = computed(() =>
  toReadableCaption(segments.value.reduce((latestSegment, segment) =>
    segment.updatedAt > latestSegment.updatedAt ? segment : latestSegment
  ))
)

const connectionStatus = computed<DisplayConnectionStatus>(() => {
  if (!isPlaying.value) {
    return 'paused'
  }

  if (connectionState.value === 'error' || realtimeState.value === 'error') {
    return 'failed'
  }

  if (connectionState.value !== 'connected' || realtimeState.value === 'connecting') {
    return 'disconnected'
  }

  return isTranslating.value ? 'translating' : 'listening'
})

const connectionStatusText = computed(() => {
  const statusText: Record<DisplayConnectionStatus, string> = {
    disconnected: '未连接',
    listening: '正在监听',
    translating: '正在翻译',
    paused: '已暂停',
    failed: '连接失败'
  }

  return statusText[connectionStatus.value]
})

const connectionStatusClass = computed(() => ({
  disconnected: connectionStatus.value === 'disconnected',
  listening: connectionStatus.value === 'listening',
  translating: connectionStatus.value === 'translating',
  paused: connectionStatus.value === 'paused',
  failed: connectionStatus.value === 'failed'
}))

const captureStatusText = computed(() => {
  const modeText = activeCaptureMode.value === 'system' ? '系统音频' : '麦克风'
  const stateText: Record<AudioCaptureState, string> = {
    idle: '未采集',
    requesting: '请求中',
    capturing: '采集中',
    fallback: '麦克风兜底',
    error: '采集异常'
  }

  return `${modeText} · ${stateText[captureState.value]}`
})

function handleSubtitleUpdate(message: SubtitleUpdateMessage): void {
  markTranslating()

  const nextSegment: SubtitleSegment = {
    segmentId: message.segmentId,
    revision: message.revision,
    source: message.source,
    translation: message.translation,
    isFinal: message.isFinal,
    updatedAt: Date.now()
  }

  const existingIndex = segments.value.findIndex((segment) => segment.segmentId === message.segmentId)

  if (existingIndex < 0) {
    segments.value = [
      ...segments.value.filter((segment) => segment.segmentId !== defaultCaption.segmentId),
      nextSegment
    ]
    latency.value = message.latencyMs
    return
  }

  const existingSegment = segments.value[existingIndex]

  if (message.revision <= existingSegment.revision) {
    return
  }

  segments.value = segments.value.map((segment, index) =>
    index === existingIndex ? nextSegment : segment
  )
  latency.value = message.latencyMs
}

function handleConnectionStateChange(state: WebSocketConnectionState): void {
  connectionState.value = state
}

function handleRealtimeStatus(message: RealtimeStatusMessage): void {
  realtimeState.value = message.status

  if (message.status === 'error') {
    segments.value = [{
      segmentId: 'realtime-error',
      revision: Date.now(),
      source: message.message,
      translation: '实时识别服务暂不可用，请检查后端 API Key、网络或模型配置。',
      isFinal: true,
      updatedAt: Date.now()
    }]
  }
}

function toReadableCaption(segment: SubtitleSegment): SubtitleSegment {
  return {
    ...segment,
    source: takeReadableTail(segment.source, 150),
    translation: takeReadableTail(segment.translation, 84)
  }
}

function takeReadableTail(text: string, maxLength: number): string {
  const normalizedText = text.trim()

  if (normalizedText.length <= maxLength) {
    return normalizedText
  }

  const boundaryIndex = Math.max(
    normalizedText.lastIndexOf('.'),
    normalizedText.lastIndexOf('?'),
    normalizedText.lastIndexOf('!'),
    normalizedText.lastIndexOf('。'),
    normalizedText.lastIndexOf('？'),
    normalizedText.lastIndexOf('！'),
    normalizedText.lastIndexOf('，'),
    normalizedText.lastIndexOf(','),
    normalizedText.lastIndexOf(';'),
    normalizedText.lastIndexOf('；')
  )

  if (boundaryIndex >= normalizedText.length - maxLength && boundaryIndex < normalizedText.length - 1) {
    return normalizedText.slice(boundaryIndex + 1).trim()
  }

  const tail = normalizedText.slice(-maxLength)
  const firstSpaceIndex = tail.search(/\s/)

  if (firstSpaceIndex > 0 && firstSpaceIndex < 24) {
    return tail.slice(firstSpaceIndex + 1).trim()
  }

  return tail.trim()
}

async function togglePlayback(): Promise<void> {
  isPlaying.value = !isPlaying.value

  if (isPlaying.value) {
    websocketClient?.connect()
    connectionState.value = websocketClient?.isConnected() ? 'connected' : 'connecting'
    await startAudioCapture()
    return
  }

  isTranslating.value = false
  latency.value = 0
  currentRms.value = 0
  await audioCaptureService?.stop()
}

async function changeCaptureMode(mode: AudioCaptureMode): Promise<void> {
  captureMode.value = mode

  if (!isPlaying.value) {
    return
  }

  await startAudioCapture()
}

async function startAudioCapture(): Promise<void> {
  await audioCaptureService?.stop()

  audioCaptureService = new AudioCaptureService({
    mode: captureMode.value,
    onAudioChunk: (base64Pcm16, rms) => {
      currentRms.value = rms
      websocketClient?.sendAudioChunk(base64Pcm16)
    },
    onSilence: (durationMs) => {
      websocketClient?.sendSilence(durationMs)
    },
    onStateChange: (state, mode) => {
      captureState.value = state
      activeCaptureMode.value = mode
    }
  })

  try {
    await audioCaptureService.start()
  } catch {
    captureState.value = 'error'
  }
}

function hideWindow(): void {
  void window.subtitleWindow?.hide()
}

function markTranslating(): void {
  isTranslating.value = true
  window.clearTimeout(translatingResetTimer)
  translatingResetTimer = window.setTimeout(() => {
    isTranslating.value = false
  }, 1500)
}

onMounted(() => {
  websocketClient = new SubtitleWebSocketClient({
    url: 'ws://localhost:8080/ws/audio',
    sessionId,
    onSubtitleUpdate: handleSubtitleUpdate,
    onRealtimeStatus: handleRealtimeStatus,
    onStateChange: handleConnectionStateChange
  })

  websocketClient.connect()
  void startAudioCapture()
})

onBeforeUnmount(() => {
  window.clearTimeout(translatingResetTimer)
  void audioCaptureService?.stop()
  websocketClient?.disconnect()
})
</script>
