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
          <p>{{ currentCaption.original }}</p>
        </article>

        <div class="divider" aria-hidden="true"></div>

        <article class="subtitle-block translated" aria-label="中文字幕">
          <p>{{ currentCaption.translated }}</p>
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

type ConnectionStatus = 'connected' | 'paused'

interface Caption {
  original: string
  translated: string
}

const captions: Caption[] = [
  {
    original: 'Welcome to the AI translation assistant. The floating subtitle window is ready.',
    translated: '欢迎使用 AI 翻译助手，悬浮字幕窗已准备就绪。'
  },
  {
    original: 'The original sentence appears above, and the Chinese translation appears below.',
    translated: '上半部分显示原文，下半部分显示中文翻译。'
  },
  {
    original: 'You can drag this window anywhere on the desktop while it stays above other windows.',
    translated: '你可以把窗口拖到桌面任意位置，它会保持置顶显示。'
  },
  {
    original: 'Use the play button to pause or resume simulated subtitle updates.',
    translated: '使用开始或暂停按钮控制模拟字幕更新。'
  }
]

const currentIndex = ref(0)
const isPlaying = ref(true)
const latency = ref(38)
const connectionStatus = ref<ConnectionStatus>('connected')

let captionTimer: number | undefined
let latencyTimer: number | undefined

const currentCaption = computed(() => captions[currentIndex.value])

const connectionStatusText = computed(() =>
  connectionStatus.value === 'connected' ? '已连接' : '已暂停'
)

const connectionStatusClass = computed(() => ({
  connected: connectionStatus.value === 'connected',
  paused: connectionStatus.value === 'paused'
}))

function updateCaption(): void {
  if (!isPlaying.value) {
    return
  }

  currentIndex.value = (currentIndex.value + 1) % captions.length
}

function updateLatency(): void {
  latency.value = isPlaying.value ? 28 + Math.floor(Math.random() * 56) : 0
}

function togglePlayback(): void {
  isPlaying.value = !isPlaying.value
  connectionStatus.value = isPlaying.value ? 'connected' : 'paused'
  updateLatency()
}

function hideWindow(): void {
  void window.subtitleWindow?.hide()
}

onMounted(() => {
  captionTimer = window.setInterval(updateCaption, 2800)
  latencyTimer = window.setInterval(updateLatency, 1200)
})

onBeforeUnmount(() => {
  window.clearInterval(captionTimer)
  window.clearInterval(latencyTimer)
})
</script>
