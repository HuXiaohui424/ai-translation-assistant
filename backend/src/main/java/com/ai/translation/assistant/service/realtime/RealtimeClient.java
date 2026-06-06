package com.ai.translation.assistant.service.realtime;

import com.ai.translation.assistant.config.RealtimeApiProperties;
import com.ai.translation.assistant.domain.subtitle.SubtitleSegment;
import com.ai.translation.assistant.domain.websocket.AudioChunkMessage;
import com.ai.translation.assistant.domain.websocket.RealtimeStatusMessage;
import com.ai.translation.assistant.domain.websocket.SubtitleUpdateMessage;
import com.alibaba.dashscope.audio.asr.translation.TranslationRecognizerParam;
import com.alibaba.dashscope.audio.asr.translation.TranslationRecognizerRealtime;
import com.alibaba.dashscope.audio.asr.translation.results.TranscriptionResult;
import com.alibaba.dashscope.audio.asr.translation.results.Translation;
import com.alibaba.dashscope.audio.asr.translation.results.TranslationRecognizerResult;
import com.alibaba.dashscope.common.ResultCallback;
import com.alibaba.dashscope.common.Status;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Base64;
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

    private static final int SILENCE_FRAME_BYTES = 3_200;
    private static final int SENTENCE_END_SILENCE_FRAME_COUNT = 6;

    private final String sessionId;
    private final RealtimeApiProperties properties;
    private final RealtimeSubtitleListener subtitleListener;
    private final RealtimeStatusListener statusListener;
    private final TranslationRecognizerRealtime recognizer;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicInteger segmentSequence = new AtomicInteger();
    private final Map<String, SubtitleSegment> sentenceSegments = new ConcurrentHashMap<>();

    private volatile SubtitleSegment activeSegment;
    private volatile long latestAudioTimestamp = System.currentTimeMillis();

    public RealtimeClient(
        String sessionId,
        RealtimeApiProperties properties,
        RealtimeSubtitleListener subtitleListener,
        RealtimeStatusListener statusListener
    ) {
        this.sessionId = sessionId;
        this.properties = properties;
        this.subtitleListener = subtitleListener;
        this.statusListener = statusListener;
        this.recognizer = new TranslationRecognizerRealtime();
    }

    public void sendAudioChunk(AudioChunkMessage message) {
        ensureStarted();
        latestAudioTimestamp = message.getTimestamp();
        byte[] audioBytes = Base64.getDecoder().decode(message.getData());
        recognizer.sendAudioFrame(ByteBuffer.wrap(audioBytes));
    }

    public void sendSentenceEndSilence() {
        if (!started.get()) {
            return;
        }

        for (int index = 0; index < SENTENCE_END_SILENCE_FRAME_COUNT; index += 1) {
            recognizer.sendAudioFrame(ByteBuffer.wrap(new byte[SILENCE_FRAME_BYTES]));
        }
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

        boolean sentenceEnd = result.isSentenceEnd()
            || Optional.ofNullable(transcriptionResult).map(TranscriptionResult::isSentenceEnd).orElse(false)
            || Optional.ofNullable(translation).map(Translation::isSentenceEnd).orElse(false);

        updateSegment(transcriptionResult, translation, sourceText, translationText, sentenceEnd)
            .ifPresent(subtitleListener::onSubtitleUpdate);
    }

    private synchronized Optional<SubtitleUpdateMessage> updateSegment(
        TranscriptionResult transcriptionResult,
        Translation translation,
        String sourceText,
        String translationText,
        boolean sentenceEnd
    ) {
        SubtitleSegment segment = resolveSegment(transcriptionResult, translation);

        if (Boolean.TRUE.equals(segment.getIsFinal())) {
            return Optional.empty();
        }

        segment.setSource(sourceText);
        segment.setTranslation(translationText);
        segment.setRevision(segment.getRevision() + 1);
        segment.setIsFinal(sentenceEnd);

        return Optional.of(SubtitleUpdateMessage.builder()
            .type("subtitle.update")
            .sessionId(sessionId)
            .segmentId(segment.getSegmentId())
            .revision(segment.getRevision())
            .source(segment.getSource())
            .translation(segment.getTranslation())
            .isFinal(segment.getIsFinal())
            .latencyMs(Duration.ofMillis(Math.max(0, System.currentTimeMillis() - latestAudioTimestamp)).toMillis())
            .build());
    }

    private SubtitleSegment resolveSegment(TranscriptionResult transcriptionResult, Translation translation) {
        String sentenceKey = buildSentenceKey(transcriptionResult, translation);

        if (sentenceKey != null) {
            return sentenceSegments.computeIfAbsent(sentenceKey, ignored -> {
                if (activeSegment != null && !Boolean.TRUE.equals(activeSegment.getIsFinal())) {
                    return activeSegment;
                }

                return createSegment();
            });
        }

        if (activeSegment == null || Boolean.TRUE.equals(activeSegment.getIsFinal())) {
            activeSegment = createSegment();
        }

        return activeSegment;
    }

    private SubtitleSegment createSegment() {
        return SubtitleSegment.builder()
            .segmentId("seg-" + segmentSequence.incrementAndGet())
            .source("")
            .translation("")
            .revision(0)
            .isFinal(false)
            .build();
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
