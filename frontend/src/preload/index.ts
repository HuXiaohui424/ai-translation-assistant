import { contextBridge, ipcRenderer } from 'electron'

contextBridge.exposeInMainWorld('subtitleWindow', {
  close: () => ipcRenderer.send('subtitle-window:close'),
  saveMinutes: (markdown: string) => ipcRenderer.invoke('minutes-file:save', markdown)
})

contextBridge.exposeInMainWorld('minutesFile', {
  save: (markdown: string) => ipcRenderer.invoke('minutes-file:save', markdown)
})
