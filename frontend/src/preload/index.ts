import { contextBridge, ipcRenderer } from 'electron'

contextBridge.exposeInMainWorld('subtitleWindow', {
  close: () => ipcRenderer.send('subtitle-window:close')
})
