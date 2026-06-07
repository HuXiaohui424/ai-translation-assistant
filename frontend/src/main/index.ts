import { writeFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { join } from "node:path";
import {
    app,
    BrowserWindow,
    desktopCapturer,
    dialog,
    ipcMain,
    session,
    shell,
} from "electron";

const isDevelopment = Boolean(process.env.ELECTRON_RENDERER_URL);

let mainWindow: BrowserWindow | null = null;

/**
 * 兼容开发构建与生产构建生成的不同预加载脚本扩展名。
 */
function resolvePreloadPath(): string {
    const preloadModulePath = join(__dirname, "../preload/index.mjs");

    if (existsSync(preloadModulePath)) {
        return preloadModulePath;
    }

    return join(__dirname, "../preload/index.js");
}

function registerDisplayMediaHandler(): void {
    session.defaultSession.setDisplayMediaRequestHandler(
        async (_request, callback) => {
            const sources = await desktopCapturer.getSources({
                types: ["screen"],
                thumbnailSize: {
                    width: 0,
                    height: 0,
                },
            });

            if (sources.length === 0) {
                callback({});
                return;
            }

            // Electron 在 Windows 上通过 loopback 捕获系统输出音频。
            callback({
                video: sources[0],
                audio: "loopback",
            });
        },
    );
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
        backgroundColor: "#00000000",
        webPreferences: {
            preload: resolvePreloadPath(),
            sandbox: false,
            contextIsolation: true,
        },
    });

    mainWindow.on("ready-to-show", () => {
        mainWindow?.show();
    });

    mainWindow.on("closed", () => {
        mainWindow = null;
    });

    // 外部链接交给系统浏览器打开，禁止在悬浮窗内创建新窗口。
    mainWindow.webContents.setWindowOpenHandler(({ url }) => {
        shell.openExternal(url);
        return { action: "deny" };
    });

    if (isDevelopment && process.env.ELECTRON_RENDERER_URL) {
        mainWindow.loadURL(process.env.ELECTRON_RENDERER_URL);
        return;
    }

    mainWindow.loadFile(join(__dirname, "../renderer/index.html"));
}

function quitApplication(): void {
    app.quit();
}

ipcMain.on("subtitle-window:close", () => {
    quitApplication();
});

// 文件写入仅在主进程执行，渲染进程通过受控 IPC 请求保存。
ipcMain.handle("minutes-file:save", async (event, markdown: string) => {
    if (typeof markdown !== "string" || markdown.trim().length === 0) {
        return {
            saved: false,
            errorMessage: "纪要内容为空",
        };
    }

    const targetWindow =
        BrowserWindow.fromWebContents(event.sender) ?? mainWindow ?? undefined;
    const wasAlwaysOnTop = targetWindow?.isAlwaysOnTop() ?? false;

    try {
        // 保存对话框打开期间临时取消置顶，避免系统窗口被悬浮窗遮挡。
        targetWindow?.focus();
        targetWindow?.setAlwaysOnTop(false);

        const result = await dialog.showSaveDialog(targetWindow, {
            title: "保存纪要",
            defaultPath: join(
                app.getPath("documents"),
                `纪要-${new Date().toISOString().slice(0, 10)}.md`,
            ),
            filters: [
                {
                    name: "Markdown",
                    extensions: ["md"],
                },
                {
                    name: "Text",
                    extensions: ["txt"],
                },
            ],
        });

        if (result.canceled || !result.filePath) {
            return {
                saved: false,
            };
        }

        await writeFile(result.filePath, markdown, "utf-8");

        return {
            saved: true,
            filePath: result.filePath,
        };
    } catch (error) {
        console.error("Failed to save minutes file.", error);

        return {
            saved: false,
            errorMessage: "保存失败，请检查文件路径或权限",
        };
    } finally {
        targetWindow?.setAlwaysOnTop(wasAlwaysOnTop);
    }
});

app.whenReady().then(() => {
    app.setAppUserModelId("com.ai.translation.assistant");
    registerDisplayMediaHandler();
    createMainWindow();

    app.on("activate", () => {
        if (BrowserWindow.getAllWindows().length === 0) {
            createMainWindow();
        }
    });
});

app.on("window-all-closed", () => {
    if (process.platform !== "darwin") {
        app.quit();
    }
});
