package com.ai.translation.assistant.service.realtime;

import com.ai.translation.assistant.config.RealtimeApiProperties;
import com.ai.translation.assistant.domain.subtitle.SubtitleSegment;
import com.ai.translation.assistant.domain.websocket.AudioChunkMessage;
import com.ai.translation.assistant.domain.websocket.AudioSilenceMessage;
import com.ai.translation.assistant.domain.websocket.RealtimeConnectionStatus;
import com.ai.translation.assistant.domain.websocket.RealtimeStatusMessage;
import com.ai.translation.assistant.domain.websocket.SubtitleUpdateMessage;
import com.ai.translation.assistant.service.subtitle.SentenceBoundaryDetector;
import com.alibaba.dashscope.audio.asr.translation.TranslationRecognizerParam;
import com.alibaba.dashscope.audio.asr.translation.TranslationRecognizerRealtime;
import com.alibaba.dashscope.audio.asr.translation.results.TranscriptionResult;
import com.alibaba.dashscope.audio.asr.translation.results.Translation;
import com.alibaba.dashscope.audio.asr.translation.results.TranslationRecognizerResult;
import com.alibaba.dashscope.common.ResultCallback;
import com.alibaba.dashscope.common.Status;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

/**
 * 封装单个业务会话的实时识别连接、字幕片段状态和自动重连流程。
 */
@Slf4j
public class RealtimeClient implements AutoCloseable {

    private final String sessionId;
    private final RealtimeApiProperties properties;
    private final RealtimeSubtitleListener subtitleListener;
    private final RealtimeStatusListener statusListener;
    private final SentenceBoundaryDetector sentenceBoundaryDetector;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private final AtomicInteger segmentSequence = new AtomicInteger();
    private final AtomicInteger reconnectAttempts = new AtomicInteger();
    private final Map<String, SubtitleSegment> sentenceSegments = new ConcurrentHashMap<>();
    private final ScheduledExecutorService reconnectExecutor;

    private volatile TranslationRecognizerRealtime recognizer;
    private volatile SubtitleSegment activeSegment;
    private volatile boolean nextResultStartsNewSegment = false;
    private volatile long latestAudioTimestamp = System.currentTimeMillis();

    public RealtimeClient(
        String sessionId,
        RealtimeApiProperties properties,
        RealtimeSubtitleListener subtitleListener,
        RealtimeStatusListener statusListener,
        SentenceBoundaryDetector sentenceBoundaryDetector
    ) {
        this.sessionId = sessionId;
        this.properties = properties;
        this.subtitleListener = subtitleListener;
        this.statusListener = statusListener;
        this.sentenceBoundaryDetector = sentenceBoundaryDetector;
        this.reconnectExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "realtime-reconnect-" + sessionId);
            thread.setDaemon(true);
            return thread;
        });
    }

    public void sendAudioChunk(AudioChunkMessage message) {
        if (!ensureStarted()) {
            return;
        }

        latestAudioTimestamp = message.getTimestamp();
        byte[] audioBytes = Base64.getDecoder().decode(message.getData());
        TranslationRecognizerRealtime currentRecognizer = recognizer;

        if (currentRecognizer == null) {
            return;
        }

        try {
            currentRecognizer.sendAudioFrame(ByteBuffer.wrap(audioBytes));
        } catch (RuntimeException exception) {
            handleConnectionFailure(currentRecognizer, exception, "发送音频帧失败");
        }
    }

    public synchronized Optional<SubtitleUpdateMessage> handleSilence(AudioSilenceMessage message) {
        if (!sentenceBoundaryDetector.shouldFinishBySilence(message.getDurationMs(), getSilenceBoundaryMs())) {
            return Optional.empty();
        }

        if (!hasDisplayableText(activeSegment)) {
            nextResultStartsNewSegment = false;
            return Optional.empty();
        }

        // 静音边界先标记切段，避免后续模型修订被并入上一片段。
        nextResultStartsNewSegment = true;

        if (Boolean.TRUE.equals(activeSegment.getIsFinal())) {
            return Optional.empty();
        }

        finalizeSegment(activeSegment, System.currentTimeMillis());
        return Optional.of(buildSubtitleUpdate(activeSegment));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        reconnectExecutor.shutdownNow();
        closeRecognizer(recognizer);
        recognizer = null;
        started.set(false);
    }

    private boolean ensureStarted() {
        if (closed.get()) {
            return false;
        }

        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new IllegalStateException("DashScope api key is not configured");
        }

        if (started.get()) {
            return true;
        }

        if (reconnecting.get() || hasReconnectAttemptsExhausted()) {
            return false;
        }

        // 双重检查保证并发音频块只会启动一个识别器实例。
        synchronized (this) {
            if (started.get()) {
                return true;
            }

            if (reconnecting.get() || hasReconnectAttemptsExhausted()) {
                return false;
            }

            return startRecognizer();
        }
    }

    private boolean startRecognizer() {
        if (closed.get()) {
            return false;
        }

        TranslationRecognizerRealtime nextRecognizer = new TranslationRecognizerRealtime();
        TranslationRecognizerParam param = TranslationRecognizerParam.builder()
            .apiKey(properties.getApiKey())
            .model(properties.getModel())
            .format(properties.getFormat())
            .sampleRate(properties.getSampleRate())
            .transcriptionEnabled(true)
            .sourceLanguage(properties.getSourceLanguage())
            .translationEnabled(true)
            .translationLanguages(new String[] { properties.getTargetLanguage() })
            .maxEndSilence(properties.getMaxEndSilence())
            .build();

        sendStatus(RealtimeConnectionStatus.CONNECTING, "正在连接实时识别服务");

        try {
            recognizer = nextRecognizer;
            started.set(true);
            nextRecognizer.call(param, createCallback(nextRecognizer));
            return true;
        } catch (RuntimeException exception) {
            log.warn("Failed to start realtime recognizer, sessionId={}", sessionId, exception);
            resetRecognizer(nextRecognizer);
            closeRecognizer(nextRecognizer);
            sendStatus(RealtimeConnectionStatus.CONNECTING, "实时识别服务连接失败，正在尝试重连");
            scheduleReconnect();
            return false;
        }
    }

    private ResultCallback<TranslationRecognizerResult> createCallback(TranslationRecognizerRealtime callbackRecognizer) {
        return new ResultCallback<>() {
            @Override
            public void onOpen(Status status) {
                log.info("Realtime recognizer connected, sessionId={}, status={}", sessionId, status);
                reconnectAttempts.set(0);
                reconnecting.set(false);
                sendStatus(RealtimeConnectionStatus.CONNECTED, "实时识别服务已连接");
            }

            @Override
            public void onEvent(TranslationRecognizerResult result) {
                handleResult(result);
            }

            @Override
            public void onComplete() {
                if (closed.get()) {
                    return;
                }

                resetRecognizer(callbackRecognizer);
                log.info("Realtime recognizer completed, sessionId={}", sessionId);
                sendStatus(RealtimeConnectionStatus.CONNECTING, "实时识别服务已结束，正在尝试重连");
                scheduleReconnect();
            }

            @Override
            public void onError(Exception exception) {
                if (closed.get()) {
                    return;
                }

                log.warn("Realtime recognizer error, sessionId={}", sessionId, exception);
                handleConnectionFailure(callbackRecognizer, exception, "实时识别服务异常");
            }
        };
    }

    private void handleConnectionFailure(
        TranslationRecognizerRealtime failedRecognizer,
        Exception exception,
        String failureMessage
    ) {
        resetRecognizer(failedRecognizer);
        closeRecognizer(failedRecognizer);
        log.warn("{}, sessionId={}", failureMessage, sessionId, exception);
        sendStatus(RealtimeConnectionStatus.CONNECTING, "实时识别服务异常，正在尝试重连");
        scheduleReconnect();
    }

    private void resetRecognizer(TranslationRecognizerRealtime targetRecognizer) {
        synchronized (this) {
            // 旧连接的延迟回调不得清空已经替换成功的新识别器。
            if (recognizer == targetRecognizer) {
                recognizer = null;
                started.set(false);
            }
        }
    }

    private void closeRecognizer(TranslationRecognizerRealtime targetRecognizer) {
        if (targetRecognizer == null) {
            return;
        }

        try {
            targetRecognizer.stop();
        } catch (RuntimeException exception) {
            log.warn("Failed to stop realtime recognizer, sessionId={}", sessionId, exception);
        }
    }

    private void scheduleReconnect() {
        // 多个失败回调可能同时到达，原子标记保证只安排一个重连任务。
        if (closed.get() || !reconnecting.compareAndSet(false, true)) {
            return;
        }

        int attempt = reconnectAttempts.incrementAndGet();

        if (attempt > getReconnectMaxAttempts()) {
            reconnecting.set(false);
            sendStatus(RealtimeConnectionStatus.ERROR, "实时识别服务连接失败，请检查 API Key、网络或模型配置");
            return;
        }

        long delayMs = calculateReconnectDelayMs(attempt);
        reconnectExecutor.schedule(() -> {
            reconnecting.set(false);

            if (closed.get()) {
                return;
            }

            if (!ensureStarted()) {
                return;
            }

            log.info("Realtime recognizer reconnect attempt started, sessionId={}, attempt={}", sessionId, attempt);
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private boolean hasReconnectAttemptsExhausted() {
        return reconnectAttempts.get() > getReconnectMaxAttempts();
    }

    private int getReconnectMaxAttempts() {
        return Optional.ofNullable(properties.getReconnectMaxAttempts()).orElse(5);
    }

    private long calculateReconnectDelayMs(int attempt) {
        long initialDelayMs = Optional.ofNullable(properties.getReconnectInitialDelayMs()).orElse(1000L);
        long maxDelayMs = Optional.ofNullable(properties.getReconnectMaxDelayMs()).orElse(10000L);

        // 限制移位次数和最大等待时间，避免高重试次数导致延迟溢出。
        long exponentialDelayMs = initialDelayMs * (1L << Math.min(attempt - 1, 10));

        return Math.min(exponentialDelayMs, maxDelayMs);
    }

    private void sendStatus(RealtimeConnectionStatus status, String message) {
        statusListener.onRealtimeStatus(RealtimeStatusMessage.builder()
            .type("realtime.status")
            .sessionId(sessionId)
            .status(status)
            .message(message)
            .build());
    }

    private void handleResult(TranslationRecognizerResult result) {
        TranscriptionResult transcriptionResult = result.getTranscriptionResult();
        Translation translation = Optional.ofNullable(result.getTranslationResult())
            .map(translationResult -> translationResult.getTranslation(properties.getTargetLanguage()))
            .orElse(null);

        String sourceText = Optional.ofNullable(transcriptionResult)
            .map(TranscriptionResult::getText)
            .orElse("");
        String translationText = Optional.ofNullable(translation)
            .map(Translation::getText)
            .orElse("");

        if (!StringUtils.hasText(sourceText) && !StringUtils.hasText(translationText)) {
            return;
        }

        boolean modelFinal = result.isSentenceEnd()
            || Optional.ofNullable(transcriptionResult).map(TranscriptionResult::isSentenceEnd).orElse(false)
            || Optional.ofNullable(translation).map(Translation::isSentenceEnd).orElse(false);
        boolean sentenceEnd = sentenceBoundaryDetector.shouldFinishByModelFinal(modelFinal);

        updateSegment(transcriptionResult, translation, sourceText, translationText, sentenceEnd)
            .forEach(subtitleListener::onSubtitleUpdate);
    }

    private synchronized List<SubtitleUpdateMessage> updateSegment(
        TranscriptionResult transcriptionResult,
        Translation translation,
        String sourceText,
        String translationText,
        boolean sentenceEnd
    ) {
        // SDK 回调可能跨线程到达，串行维护活动片段及句子绑定关系。
        String sentenceKey = buildSentenceKey(transcriptionResult, translation);
        SubtitleSegment segment = resolveSegment(sentenceKey);
        long currentTimeMs = System.currentTimeMillis();
        List<SubtitleUpdateMessage> updates = new ArrayList<>();

        // 长时间未结束的片段先强制定稿，再用新片段承接当前识别结果。
        if (shouldForceNewSegment(segment, currentTimeMs)) {
            finalizeSegment(segment, currentTimeMs);
            removeSentenceBindings(segment);
            updates.add(buildSubtitleUpdate(segment));
            segment = createAndActivateSegment();
            bindSentenceKey(sentenceKey, segment);
        }

        if (Boolean.TRUE.equals(segment.getIsFinal())) {
            if (nextResultStartsNewSegment && !sentenceEnd) {
                segment = createAndActivateSegment();
                bindSentenceKey(sentenceKey, segment);
            } else if (sentenceBoundaryDetector.canReviseFinalSegment(
                segment,
                sourceText,
                translationText,
                currentTimeMs,
                getFinalSegmentRevisionGraceMs()
            )) {
                // 模型可能在最终结果后短暂修正文本，宽限期内沿用原片段版本。
                sentenceEnd = true;
            } else if (!sentenceEnd) {
                segment = createAndActivateSegment();
                bindSentenceKey(sentenceKey, segment);
            } else {
                return updates;
            }
        }

        segment.setSource(sourceText);
        segment.setTranslation(translationText);
        segment.setRevision(segment.getRevision() + 1);
        segment.setIsFinal(sentenceEnd);

        if (sentenceEnd && segment.getFinalizedAtMs() == null) {
            segment.setFinalizedAtMs(currentTimeMs);
        }

        updates.add(buildSubtitleUpdate(segment));
        return updates;
    }

    private SubtitleUpdateMessage buildSubtitleUpdate(SubtitleSegment segment) {
        return SubtitleUpdateMessage.builder()
            .type("subtitle.update")
            .sessionId(sessionId)
            .segmentId(segment.getSegmentId())
            .revision(segment.getRevision())
            .source(segment.getSource())
            .translation(segment.getTranslation())
            .isFinal(segment.getIsFinal())
            .latencyMs(Duration.ofMillis(Math.max(0, System.currentTimeMillis() - latestAudioTimestamp)).toMillis())
            .build();
    }

    private SubtitleSegment resolveSegment(String sentenceKey) {
        if (sentenceKey != null) {
            // 按模型句子 ID 绑定片段，兼容原文和译文分批到达。
            return sentenceSegments.computeIfAbsent(sentenceKey, ignored -> {
                if (activeSegment != null && !Boolean.TRUE.equals(activeSegment.getIsFinal())) {
                    return activeSegment;
                }

                return createAndActivateSegment();
            });
        }

        if (activeSegment == null || nextResultStartsNewSegment) {
            nextResultStartsNewSegment = false;
            activeSegment = createSegment();
        }

        return activeSegment;
    }

    private boolean shouldForceNewSegment(SubtitleSegment segment, long currentTimeMs) {
        return hasDisplayableText(segment)
            && !Boolean.TRUE.equals(segment.getIsFinal())
            && sentenceBoundaryDetector.shouldFinishByDuration(
                segment,
                currentTimeMs,
                getMaxSegmentDurationMs()
            );
    }

    private long getMaxSegmentDurationMs() {
        return Optional.ofNullable(properties.getMaxSegmentDurationMs()).orElse(3000L);
    }

    private long getSilenceBoundaryMs() {
        return Optional.ofNullable(properties.getSilenceBoundaryMs()).orElse(700L);
    }

    private long getFinalSegmentRevisionGraceMs() {
        return Optional.ofNullable(properties.getFinalSegmentRevisionGraceMs()).orElse(1000L);
    }

    private void bindSentenceKey(String sentenceKey, SubtitleSegment segment) {
        if (sentenceKey != null) {
            sentenceSegments.put(sentenceKey, segment);
        }
    }

    private void removeSentenceBindings(SubtitleSegment segment) {
        sentenceSegments.entrySet().removeIf(entry -> entry.getValue() == segment);
    }

    private void finalizeSegment(SubtitleSegment segment, long finalizedAtMs) {
        segment.setIsFinal(true);
        segment.setFinalizedAtMs(finalizedAtMs);
        segment.setRevision(segment.getRevision() + 1);
    }

    private SubtitleSegment createAndActivateSegment() {
        nextResultStartsNewSegment = false;
        activeSegment = createSegment();
        return activeSegment;
    }

    private SubtitleSegment createSegment() {
        return SubtitleSegment.builder()
            .segmentId("seg-" + segmentSequence.incrementAndGet())
            .source("")
            .translation("")
            .revision(0)
            .isFinal(false)
            .startedAtMs(System.currentTimeMillis())
            .finalizedAtMs(null)
            .build();
    }

    private boolean hasDisplayableText(SubtitleSegment segment) {
        return segment != null
            && (StringUtils.hasText(segment.getSource()) || StringUtils.hasText(segment.getTranslation()));
    }

    private String buildSentenceKey(TranscriptionResult transcriptionResult, Translation translation) {
        Long sentenceId = Optional.ofNullable(transcriptionResult)
            .map(TranscriptionResult::getSentenceId)
            .filter(Objects::nonNull)
            .orElseGet(() -> Optional.ofNullable(translation).map(Translation::getSentenceId).orElse(null));

        if (sentenceId == null) {
            return null;
        }

        return "sentence-" + sentenceId;
    }
}
