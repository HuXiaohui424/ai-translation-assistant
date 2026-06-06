import { contextBridge, ipcRenderer } from 'electron'

contextBridge.exposeInMainWorld('subtitleWindow', {
  hide: () => ipcRenderer.invoke('subtitle-window:hide'),
  show: () => ipcRenderer.invoke('subtitle-window:show'),
  toggle: () => ipcRenderer.invoke('subtitle-window:toggle')
})
