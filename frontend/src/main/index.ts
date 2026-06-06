import { join } from 'node:path'
import { app, BrowserWindow, desktopCapturer, globalShortcut, ipcMain, session, shell } from 'electron'

const isDevelopment = Boolean(process.env.ELECTRON_RENDERER_URL)
const TOGGLE_WINDOW_SHORTCUT = 'CommandOrControl+Alt+S'

let mainWindow: BrowserWindow | null = null

function registerDisplayMediaHandler(): void {
  session.defaultSession.setDisplayMediaRequestHandler(async (_request, callback) => {
    const sources = await desktopCapturer.getSources({
      types: ['screen'],
      thumbnailSize: {
        width: 0,
        height: 0
      }
    })

    if (sources.length === 0) {
      callback({})
      return
    }

    callback({
      video: sources[0],
      audio: 'loopback'
    })
  })
}

function createMainWindow(): void {
  mainWindow = new BrowserWindow({
    width: 720,
    height: 280,
    minWidth: 520,
    minHeight: 220,
    show: false,
    frame: false,
    transparent: true,
    resizable: true,
    alwaysOnTop: true,
    hasShadow: false,
    skipTaskbar: true,
    autoHideMenuBar: true,
    backgroundColor: '#00000000',
    webPreferences: {
      preload: join(__dirname, '../preload/index.js'),
      sandbox: false,
      contextIsolation: true
    }
  })

  mainWindow.on('ready-to-show', () => {
    mainWindow?.show()
  })

  mainWindow.on('closed', () => {
    mainWindow = null
  })

  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url)
    return { action: 'deny' }
  })

  if (isDevelopment && process.env.ELECTRON_RENDERER_URL) {
    mainWindow.loadURL(process.env.ELECTRON_RENDERER_URL)
    return
  }

  mainWindow.loadFile(join(__dirname, '../renderer/index.html'))
}

function showMainWindow(): void {
  if (!mainWindow) {
    createMainWindow()
  }

  mainWindow?.show()
  mainWindow?.focus()
}

function toggleMainWindow(): void {
  if (!mainWindow) {
    createMainWindow()
    return
  }

  if (mainWindow.isVisible()) {
    mainWindow.hide()
    return
  }

  showMainWindow()
}

ipcMain.handle('subtitle-window:hide', () => {
  mainWindow?.hide()
})

ipcMain.handle('subtitle-window:show', () => {
  showMainWindow()
})

ipcMain.handle('subtitle-window:toggle', () => {
  toggleMainWindow()
})

app.whenReady().then(() => {
  app.setAppUserModelId('com.ai.translation.assistant')
  registerDisplayMediaHandler()
  createMainWindow()
  globalShortcut.register(TOGGLE_WINDOW_SHORTCUT, toggleMainWindow)

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createMainWindow()
    }
  })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit()
  }
})

app.on('will-quit', () => {
  globalShortcut.unregisterAll()
})
