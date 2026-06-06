/// <reference types="vite/client" />

interface SubtitleWindowApi {
  hide: () => Promise<void>
  show: () => Promise<void>
  toggle: () => Promise<void>
}

interface Window {
  subtitleWindow?: SubtitleWindowApi
}
