<template>
    <main class="subtitle-shell" aria-label="悬浮字幕与纪要窗口">
        <section class="subtitle-panel">
            <header class="toolbar drag-region">
                <div class="status-group" aria-label="连接与生成状态">
                    <span class="status-pill" :class="connectionStatusClass">
                        <span class="status-dot" aria-hidden="true"></span>
                        {{ connectionStatusText }}
                    </span>
                    <span class="latency-pill">{{ latency }}ms</span>
                    <span class="latency-pill">{{ captureStatusText }}</span>
                </div>

                <div class="window-actions no-drag">
                    <div class="view-tabs" role="tablist" aria-label="内容视图">
                        <button
                            class="tab-button"
                            :class="{ active: activeView === 'subtitle' }"
                            type="button"
                            role="tab"
                            :aria-selected="activeView === 'subtitle'"
                            @click="activeView = 'subtitle'"
                        >
                            字幕
                        </button>
                        <button
                            class="tab-button"
                            :class="{ active: activeView === 'minutes' }"
                            type="button"
                            role="tab"
                            :aria-selected="activeView === 'minutes'"
                            @click="activeView = 'minutes'"
                        >
                            纪要
                        </button>
                    </div>
                    <button
                        class="control-button"
                        type="button"
                        @click="togglePlayback"
                    >
                        {{ isPlaying ? "暂停" : "开始" }}
                    </button>
                    <button
                        class="icon-button"
                        type="button"
                        title="退出程序"
                        aria-label="退出程序"
                        @mousedown.stop.prevent="closeWindow"
                        @pointerdown.stop.prevent="closeWindow"
                        @click="closeWindow"
                    >
                        x
                    </button>
                </div>
            </header>

            <div
                v-if="activeView === 'subtitle'"
                class="subtitle-content drag-region"
            >
                <article class="subtitle-block original" aria-label="原文字幕">
                    <p>{{ currentCaption.source }}</p>
                </article>

                <div class="divider" aria-hidden="true"></div>

                <article
                    class="subtitle-block translated"
                    aria-label="中文字幕"
                >
                    <p>{{ currentCaption.translation }}</p>
                </article>
            </div>

            <div v-else class="minutes-content no-drag" aria-live="polite">
                <div class="minutes-actions">
                    <div class="minutes-meta">
                        <strong>{{ minutesTitle }}</strong>
                        <span>{{ minutesStatusText }}</span>
                    </div>
                    <div class="minutes-buttons">
                        <button
                            class="control-button compact"
                            type="button"
                            :disabled="!canGenerateMinutes"
                            @click="generateMinutes"
                        >
                            {{ isMinutesGenerating ? "生成中" : "生成纪要" }}
                        </button>
                        <button
                            class="control-button compact secondary"
                            type="button"
                            :disabled="isSavingMinutes"
                            :title="
                                canSaveMinutes ? '保存当前纪要' : '请先生成纪要'
                            "
                            @click="saveMinutes"
                        >
                            {{ isSavingMinutes ? "保存中" : "保存纪要" }}
                        </button>
                    </div>
                </div>

                <section
                    v-if="minutesState.status === 'idle'"
                    class="minutes-empty"
                >
                    <p>完成几段字幕后，可以手动生成本次纪要。</p>
                    <span>已收集 {{ finalSegmentCount }} 段最终字幕</span>
                </section>

                <section
                    v-else-if="minutesState.status === 'error'"
                    class="minutes-empty error"
                >
                    <p>{{ minutesState.errorMessage }}</p>
                    <span>请稍后重试，字幕识别会继续运行。</span>
                </section>

                <section v-else class="minutes-result">
                    <p v-if="minutesState.summary" class="minutes-summary">
                        {{ minutesState.summary }}
                    </p>

                    <div class="minutes-grid">
                        <article class="minutes-section">
                            <h2>要点</h2>
                            <ul>
                                <li
                                    v-for="item in normalizedMinutes.keyPoints"
                                    :key="item"
                                >
                                    {{ item }}
                                </li>
                            </ul>
                        </article>

                        <article class="minutes-section">
                            <h2>决定</h2>
                            <ul>
                                <li
                                    v-for="item in normalizedMinutes.decisions"
                                    :key="item"
                                >
                                    {{ item }}
                                </li>
                            </ul>
                        </article>

                        <article class="minutes-section">
                            <h2>待办</h2>
                            <ul>
                                <li
                                    v-for="item in normalizedMinutes.actionItems"
                                    :key="item"
                                >
                                    {{ item }}
                                </li>
                            </ul>
                        </article>
                    </div>
                </section>
            </div>

            <footer class="shortcut-hint">
                <div class="capture-modes no-drag" aria-label="音频采集模式">
                    <button
                        class="mode-button"
                        :class="{ active: captureMode === 'system' }"
                        type="button"
                        @click="changeCaptureMode('system')"
                    >
                        系统音频
                    </button>
                    <button
                        class="mode-button"
                        :class="{ active: captureMode === 'microphone' }"
                        type="button"
                        @click="changeCaptureMode('microphone')"
                    >
                        麦克风
                    </button>
                </div>
                <span class="drag-region">RMS {{ currentRms.toFixed(3) }}</span>
            </footer>
        </section>
    </main>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import {
    AudioCaptureService,
    type AudioCaptureMode,
    type AudioCaptureState,
} from "./services/audioCaptureService";
import {
    SubtitleWebSocketClient,
    type MinutesUpdateMessage,
    type RealtimeStatusMessage,
    type SubtitleUpdateMessage,
    type WebSocketConnectionState,
} from "./services/websocketClient";

type DisplayConnectionStatus =
    | "disconnected"
    | "listening"
    | "translating"
    | "paused"
    | "failed";
type ActiveView = "subtitle" | "minutes";

interface SubtitleSegment {
    segmentId: string;
    revision: number;
    source: string;
    translation: string;
    isFinal: boolean;
    updatedAt: number;
}

interface MinutesState {
    revision: number;
    status: MinutesUpdateMessage["status"] | "idle";
    title: string;
    summary: string;
    keyPoints: string[];
    decisions: string[];
    actionItems: string[];
    updatedAtMs: number;
    errorMessage: string;
}

const defaultCaption: SubtitleSegment = {
    segmentId: "waiting",
    revision: 0,
    source: "Waiting for the audio....",
    translation: "等待音频中...",
    isFinal: false,
    updatedAt: Date.now(),
};

const emptyMinutesState: MinutesState = {
    revision: 0,
    status: "idle",
    title: "",
    summary: "",
    keyPoints: [],
    decisions: [],
    actionItems: [],
    updatedAtMs: 0,
    errorMessage: "",
};

const websocketUrl =
    import.meta.env.VITE_SUBTITLE_WS_URL ?? "ws://localhost:8080/ws/audio";
// 单次窗口生命周期内复用会话 ID，关联音频、字幕与纪要消息。
const sessionId = window.crypto.randomUUID();
const segments = ref<SubtitleSegment[]>([defaultCaption]);
const activeView = ref<ActiveView>("subtitle");
const minutesState = ref<MinutesState>({ ...emptyMinutesState });
const isPlaying = ref(true);
const latency = ref(0);
const connectionState = ref<WebSocketConnectionState>("connecting");
const realtimeState = ref<RealtimeStatusMessage["status"]>("connecting");
const isTranslating = ref(false);
const captureMode = ref<AudioCaptureMode>("system");
const activeCaptureMode = ref<AudioCaptureMode>("system");
const captureState = ref<AudioCaptureState>("idle");
const currentRms = ref(0);
const saveStatusText = ref("");

let websocketClient: SubtitleWebSocketClient | undefined;
let audioCaptureService: AudioCaptureService | undefined;
let translatingResetTimer: number | undefined;

const currentCaption = computed(() =>
    toReadableCaption(
        segments.value.reduce((latestSegment, segment) =>
            segment.updatedAt > latestSegment.updatedAt
                ? segment
                : latestSegment,
        ),
    ),
);

const finalSegmentCount = computed(
    () =>
        segments.value.filter(
            (segment) =>
                segment.segmentId !== defaultCaption.segmentId &&
                segment.isFinal,
        ).length,
);

const isMinutesGenerating = computed(
    () => minutesState.value.status === "generating",
);
const isSavingMinutes = ref(false);
const canGenerateMinutes = computed(
    () => finalSegmentCount.value > 0 && !isMinutesGenerating.value,
);
const canSaveMinutes = computed(() => minutesState.value.status === "ready");

const minutesTitle = computed(() => minutesState.value.title || "纪要");

const normalizedMinutes = computed(() => ({
    keyPoints: withEmptyText(minutesState.value.keyPoints),
    decisions: withEmptyText(minutesState.value.decisions),
    actionItems: withEmptyText(minutesState.value.actionItems),
}));

const minutesStatusText = computed(() => {
    if (saveStatusText.value) {
        return saveStatusText.value;
    }

    if (minutesState.value.status === "generating") {
        return "正在生成";
    }

    if (minutesState.value.status === "ready") {
        return `已更新 ${formatTime(minutesState.value.updatedAtMs)}`;
    }

    if (minutesState.value.status === "error") {
        return "生成失败";
    }

    return `已收集 ${finalSegmentCount.value} 段字幕`;
});

const connectionStatus = computed<DisplayConnectionStatus>(() => {
    if (!isPlaying.value) {
        return "paused";
    }

    if (connectionState.value === "error" || realtimeState.value === "error") {
        return "failed";
    }

    if (connectionState.value !== "connected") {
        return "disconnected";
    }

    return isTranslating.value ? "translating" : "listening";
});

const connectionStatusText = computed(() => {
    const statusText: Record<DisplayConnectionStatus, string> = {
        disconnected: "未连接",
        listening: "正在监听",
        translating: "正在翻译",
        paused: "已暂停",
        failed: "连接失败",
    };

    return statusText[connectionStatus.value];
});

const connectionStatusClass = computed(() => ({
    disconnected: connectionStatus.value === "disconnected",
    listening: connectionStatus.value === "listening",
    translating: connectionStatus.value === "translating",
    paused: connectionStatus.value === "paused",
    failed: connectionStatus.value === "failed",
}));

const captureStatusText = computed(() => {
    const modeText =
        activeCaptureMode.value === "system" ? "系统音频" : "麦克风";
    const stateText: Record<AudioCaptureState, string> = {
        idle: "未采集",
        requesting: "请求中",
        capturing: "采集中",
        fallback: "麦克风兜底",
        error: "采集异常",
    };

    return `${modeText} · ${stateText[captureState.value]}`;
});

function handleSubtitleUpdate(message: SubtitleUpdateMessage): void {
    realtimeState.value = "connected";
    markTranslating();

    const nextSegment: SubtitleSegment = {
        segmentId: message.segmentId,
        revision: message.revision,
        source: message.source,
        translation: message.translation,
        isFinal: message.isFinal,
        updatedAt: Date.now(),
    };

    const existingIndex = segments.value.findIndex(
        (segment) => segment.segmentId === message.segmentId,
    );

    if (existingIndex < 0) {
        segments.value = [
            ...segments.value.filter(
                (segment) => segment.segmentId !== defaultCaption.segmentId,
            ),
            nextSegment,
        ];
        latency.value = message.latencyMs;
        return;
    }

    const existingSegment = segments.value[existingIndex];

    // 服务端可能异步推送同一片段，只接受更高版本以避免界面回退。
    if (message.revision <= existingSegment.revision) {
        return;
    }

    segments.value = segments.value.map((segment, index) =>
        index === existingIndex ? nextSegment : segment,
    );
    latency.value = message.latencyMs;
}

function handleMinutesUpdate(message: MinutesUpdateMessage): void {
    // 忽略晚到的旧任务结果，确保较新的生成状态不会被覆盖。
    if (message.revision < minutesState.value.revision) {
        return;
    }

    saveStatusText.value = "";
    minutesState.value = {
        revision: message.revision,
        status: message.status,
        title: message.title ?? minutesState.value.title,
        summary: message.summary ?? minutesState.value.summary,
        keyPoints: message.keyPoints ?? [],
        decisions: message.decisions ?? [],
        actionItems: message.actionItems ?? [],
        updatedAtMs: message.updatedAtMs,
        errorMessage: message.errorMessage ?? "",
    };
}

function handleConnectionStateChange(state: WebSocketConnectionState): void {
    connectionState.value = state;
}

function handleRealtimeStatus(message: RealtimeStatusMessage): void {
    realtimeState.value = message.status;

    if (message.status === "error") {
        segments.value = [
            {
                segmentId: "realtime-error",
                revision: Date.now(),
                source: message.message,
                translation:
                    "实时识别服务暂不可用，请检查后端 API Key、网络或模型配置。",
                isFinal: true,
                updatedAt: Date.now(),
            },
        ];
    }
}

function generateMinutes(): void {
    if (!canGenerateMinutes.value) {
        return;
    }

    activeView.value = "minutes";
    websocketClient?.generateMinutes();
}

async function saveMinutes(): Promise<void> {
    if (!canSaveMinutes.value) {
        saveStatusText.value = "请先生成纪要";
        return;
    }

    const saveHandler =
        window.minutesFile?.save ?? window.subtitleWindow?.saveMinutes;

    if (!saveHandler) {
        saveStatusText.value = "保存能力未就绪";
        return;
    }

    isSavingMinutes.value = true;
    saveStatusText.value = "正在打开保存窗口";

    try {
        const result = await saveHandler(buildMinutesMarkdown());

        if (result.saved) {
            saveStatusText.value = "已保存";
            return;
        }

        saveStatusText.value = result.errorMessage ?? "已取消保存";
    } catch (error) {
        console.error("Failed to save minutes.", error);
        saveStatusText.value = "保存失败";
    } finally {
        isSavingMinutes.value = false;
    }
}

function buildMinutesMarkdown(): string {
    const lines = [
        `# ${minutesTitle.value}`,
        "",
        `生成时间：${formatDateTime(minutesState.value.updatedAtMs)}`,
        "",
        "## 摘要",
        minutesState.value.summary || "暂无摘要",
        "",
        "## 要点",
        ...toMarkdownList(minutesState.value.keyPoints),
        "",
        "## 决定",
        ...toMarkdownList(minutesState.value.decisions),
        "",
        "## 待办",
        ...toMarkdownList(minutesState.value.actionItems),
        "",
    ];

    return lines.join("\n");
}

function toMarkdownList(items: string[]): string[] {
    const values = items.filter((item) => item.trim().length > 0);
    return values.length > 0 ? values.map((item) => `- ${item}`) : ["- 暂无"];
}

function withEmptyText(items: string[]): string[] {
    return items.length > 0 ? items : ["暂无"];
}

function formatTime(timestamp: number): string {
    if (!timestamp) {
        return "";
    }

    return new Intl.DateTimeFormat("zh-CN", {
        hour: "2-digit",
        minute: "2-digit",
    }).format(timestamp);
}

function formatDateTime(timestamp: number): string {
    return new Intl.DateTimeFormat("zh-CN", {
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
    }).format(timestamp || Date.now());
}

function toReadableCaption(segment: SubtitleSegment): SubtitleSegment {
    return {
        ...segment,
        source: takeReadableTail(segment.source, 150),
        translation: takeReadableTail(segment.translation, 84),
    };
}

function takeReadableTail(text: string, maxLength: number): string {
    const normalizedText = text.trim();

    if (normalizedText.length <= maxLength) {
        return normalizedText;
    }

    const boundaryIndex = Math.max(
        normalizedText.lastIndexOf("."),
        normalizedText.lastIndexOf("?"),
        normalizedText.lastIndexOf("!"),
        normalizedText.lastIndexOf("。"),
        normalizedText.lastIndexOf("？"),
        normalizedText.lastIndexOf("！"),
        normalizedText.lastIndexOf("，"),
        normalizedText.lastIndexOf(","),
        normalizedText.lastIndexOf(";"),
        normalizedText.lastIndexOf("；"),
    );

    // 优先从末尾最近的标点切分，减少字幕从词语中间截断。
    if (
        boundaryIndex >= normalizedText.length - maxLength &&
        boundaryIndex < normalizedText.length - 1
    ) {
        return normalizedText.slice(boundaryIndex + 1).trim();
    }

    const tail = normalizedText.slice(-maxLength);
    const firstSpaceIndex = tail.search(/\s/);

    if (firstSpaceIndex > 0 && firstSpaceIndex < 24) {
        return tail.slice(firstSpaceIndex + 1).trim();
    }

    return tail.trim();
}

async function togglePlayback(): Promise<void> {
    isPlaying.value = !isPlaying.value;

    if (isPlaying.value) {
        websocketClient?.connect();
        connectionState.value = websocketClient?.isConnected()
            ? "connected"
            : "connecting";
        await startAudioCapture();
        return;
    }

    isTranslating.value = false;
    latency.value = 0;
    currentRms.value = 0;
    // 暂停时同时终止采集和连接，防止后台继续缓存音频。
    await audioCaptureService?.stop();
    websocketClient?.disconnect();
}

async function changeCaptureMode(mode: AudioCaptureMode): Promise<void> {
    captureMode.value = mode;

    if (!isPlaying.value) {
        return;
    }

    await startAudioCapture();
}

async function startAudioCapture(): Promise<void> {
    // 切换采集模式时重建完整音频图，避免旧设备轨道继续占用。
    await audioCaptureService?.stop();

    audioCaptureService = new AudioCaptureService({
        mode: captureMode.value,
        onAudioChunk: (base64Pcm16, rms) => {
            currentRms.value = rms;
            websocketClient?.sendAudioChunk(base64Pcm16);
        },
        onSilence: (durationMs) => {
            websocketClient?.sendSilence(durationMs);
        },
        onStateChange: (state, mode) => {
            captureState.value = state;
            activeCaptureMode.value = mode;
        },
    });

    try {
        await audioCaptureService.start();
    } catch {
        captureState.value = "error";
    }
}

function closeWindow(): void {
    try {
        window.subtitleWindow?.close();
    } catch (error) {
        console.error("Failed to close subtitle window.", error);
    }

    window.close();
}

function markTranslating(): void {
    // 每次字幕更新都会延长提示时间，避免翻译状态频繁闪烁。
    isTranslating.value = true;
    window.clearTimeout(translatingResetTimer);
    translatingResetTimer = window.setTimeout(() => {
        isTranslating.value = false;
    }, 1500);
}

onMounted(() => {
    websocketClient = new SubtitleWebSocketClient({
        url: websocketUrl,
        sessionId,
        onSubtitleUpdate: handleSubtitleUpdate,
        onRealtimeStatus: handleRealtimeStatus,
        onMinutesUpdate: handleMinutesUpdate,
        onStateChange: handleConnectionStateChange,
    });

    websocketClient.connect();
    void startAudioCapture();
});

onBeforeUnmount(() => {
    // 释放定时器、媒体轨道和连接，避免窗口重建后残留资源。
    window.clearTimeout(translatingResetTimer);
    void audioCaptureService?.stop();
    websocketClient?.disconnect();
});
</script>
