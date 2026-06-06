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
        Ctrl/Command + Alt + S 隐藏或显示
      </footer>
    </section>
  </main>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  SubtitleWebSocketClient,
  type SubtitleUpdateMessage,
  type WebSocketConnectionState
} from './services/websocketClient'

type DisplayConnectionStatus = WebSocketConnectionState | 'paused'

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
const connectionStatus = ref<DisplayConnectionStatus>('connecting')

let websocketClient: SubtitleWebSocketClient | undefined

const currentCaption = computed(() =>
  segments.value.reduce((latestSegment, segment) =>
    segment.updatedAt > latestSegment.updatedAt ? segment : latestSegment
  )
)

const connectionStatusText = computed(() => {
  const statusText: Record<DisplayConnectionStatus, string> = {
    connecting: '连接中',
    connected: '已连接',
    disconnected: '未连接',
    error: '连接异常',
    paused: '已暂停'
  }

  return statusText[connectionStatus.value]
})

const connectionStatusClass = computed(() => ({
  connecting: connectionStatus.value === 'connecting',
  connected: connectionStatus.value === 'connected',
  disconnected: connectionStatus.value === 'disconnected',
  error: connectionStatus.value === 'error',
  paused: connectionStatus.value === 'paused'
}))

function handleSubtitleUpdate(message: SubtitleUpdateMessage): void {
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

  if (message.revision < existingSegment.revision) {
    return
  }

  segments.value = segments.value.map((segment, index) =>
    index === existingIndex ? nextSegment : segment
  )
  latency.value = message.latencyMs
}

function handleConnectionStateChange(state: WebSocketConnectionState): void {
  if (isPlaying.value) {
    connectionStatus.value = state
  }
}

function togglePlayback(): void {
  isPlaying.value = !isPlaying.value

  if (isPlaying.value) {
    websocketClient?.connect()
    websocketClient?.startMockAudio()
    connectionStatus.value = websocketClient?.isConnected() ? 'connected' : 'connecting'
    return
  }

  connectionStatus.value = 'paused'
  latency.value = 0
  websocketClient?.stopMockAudio()
}

function hideWindow(): void {
  void window.subtitleWindow?.hide()
}

onMounted(() => {
  websocketClient = new SubtitleWebSocketClient({
    url: 'ws://localhost:8080/ws/audio',
    sessionId,
    onSubtitleUpdate: handleSubtitleUpdate,
    onStateChange: handleConnectionStateChange
  })

  websocketClient.connect()
  websocketClient.startMockAudio()
})

onBeforeUnmount(() => {
  websocketClient?.disconnect()
})
</script>
