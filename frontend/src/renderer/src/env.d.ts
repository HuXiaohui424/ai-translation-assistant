/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_SUBTITLE_WS_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

interface SubtitleWindowApi {
  close: () => void
  saveMinutes?: (markdown: string) => Promise<MinutesFileSaveResult>
}

interface MinutesFileSaveResult {
  saved: boolean
  filePath?: string
  errorMessage?: string
}

interface MinutesFileApi {
  save: (markdown: string) => Promise<MinutesFileSaveResult>
}

interface Window {
  subtitleWindow?: SubtitleWindowApi
  minutesFile?: MinutesFileApi
}
