import { contextBridge, ipcRenderer } from 'electron'

// 仅向渲染进程暴露受控 IPC 方法，避免直接开放 Node.js 能力。
contextBridge.exposeInMainWorld('subtitleWindow', {
  close: () => ipcRenderer.send('subtitle-window:close'),
  saveMinutes: (markdown: string) => ipcRenderer.invoke('minutes-file:save', markdown)
})

// 独立命名空间供纪要模块调用，同时保留 subtitleWindow 上的兼容入口。
contextBridge.exposeInMainWorld('minutesFile', {
  save: (markdown: string) => ipcRenderer.invoke('minutes-file:save', markdown)
})
