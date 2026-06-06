package com.ai.translation.assistant.service.realtime;

import com.ai.translation.assistant.config.RealtimeApiProperties;
import com.ai.translation.assistant.domain.subtitle.SubtitleSegment;
import com.ai.translation.assistant.domain.websocket.AudioChunkMessage;
import com.ai.translation.assistant.domain.websocket.AudioSilenceMessage;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

@Slf4j
public class RealtimeClient implements AutoCloseable {

    private final String sessionId;
    private final RealtimeApiProperties properties;
    private final RealtimeSubtitleListener subtitleListener;
    private final RealtimeStatusListener statusListener;
    private final SentenceBoundaryDetector sentenceBoundaryDetector;
    private final TranslationRecognizerRealtime recognizer;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicInteger segmentSequence = new AtomicInteger();
    private final Map<String, SubtitleSegment> sentenceSegments = new ConcurrentHashMap<>();

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
        this.recognizer = new TranslationRecognizerRealtime();
    }

    public void sendAudioChunk(AudioChunkMessage message) {
        ensureStarted();
        latestAudioTimestamp = message.getTimestamp();
        byte[] audioBytes = Base64.getDecoder().decode(message.getData());
        recognizer.sendAudioFrame(ByteBuffer.wrap(audioBytes));
    }

    public Optional<SubtitleUpdateMessage> handleSilence(AudioSilenceMessage message) {
        if (!sentenceBoundaryDetector.shouldFinishBySilence(message.getDurationMs())) {
            return Optional.empty();
        }

        nextResultStartsNewSegment = hasDisplayableText(activeSegment);
        return Optional.empty();
    }

    @Override
    public void close() {
        if (!started.getAndSet(false)) {
            return;
        }

        try {
            recognizer.stop();
        } catch (RuntimeException exception) {
            log.warn("Failed to stop realtime recognizer, sessionId={}", sessionId, exception);
        }
    }

    private void ensureStarted() {
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new IllegalStateException("DashScope api key is not configured");
        }

        if (!started.compareAndSet(false, true)) {
            return;
        }

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

        recognizer.call(param, createCallback());
        sendStatus("connecting", "正在连接实时识别服务");
    }

    private ResultCallback<TranslationRecognizerResult> createCallback() {
        return new ResultCallback<>() {
            @Override
            public void onOpen(Status status) {
                log.info("Realtime recognizer connected, sessionId={}, status={}", sessionId, status);
                sendStatus("connected", "实时识别服务已连接");
            }

            @Override
            public void onEvent(TranslationRecognizerResult result) {
                handleResult(result);
            }

            @Override
            public void onComplete() {
                started.set(false);
                log.info("Realtime recognizer completed, sessionId={}", sessionId);
                sendStatus("completed", "实时识别服务已结束");
            }

            @Override
            public void onError(Exception exception) {
                started.set(false);
                log.warn("Realtime recognizer error, sessionId={}", sessionId, exception);
                sendStatus("error", "实时识别服务异常，请检查 API Key、网络或模型配置");
            }
        };
    }

    private void sendStatus(String status, String message) {
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
        String sentenceKey = buildSentenceKey(transcriptionResult, translation);
        SubtitleSegment segment = resolveSegment(sentenceKey);
        long currentTimeMs = System.currentTimeMillis();
        List<SubtitleUpdateMessage> updates = new ArrayList<>();

        if (shouldForceNewSegment(segment, currentTimeMs)) {
            segment.setIsFinal(true);
            segment.setFinalizedAtMs(currentTimeMs);
            updates.add(buildSubtitleUpdate(segment));
            segment = createAndActivateSegment();
            bindSentenceKey(sentenceKey, segment);
        }

        if (Boolean.TRUE.equals(segment.getIsFinal())) {
            if (sentenceBoundaryDetector.canReviseFinalSegment(segment, sourceText, translationText)) {
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

    private void bindSentenceKey(String sentenceKey, SubtitleSegment segment) {
        if (sentenceKey != null) {
            sentenceSegments.put(sentenceKey, segment);
        }
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
