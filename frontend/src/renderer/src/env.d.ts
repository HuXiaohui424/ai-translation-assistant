/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_SUBTITLE_WS_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

interface SubtitleWindowApi {
  close: () => void
}

interface Window {
  subtitleWindow?: SubtitleWindowApi
}
