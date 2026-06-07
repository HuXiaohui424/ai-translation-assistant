package com.ai.translation.assistant.service.minutes;

import com.ai.translation.assistant.config.MinutesProperties;
import com.ai.translation.assistant.domain.minutes.CachedSubtitleSegment;
import com.ai.translation.assistant.domain.minutes.MinutesDraft;
import com.ai.translation.assistant.domain.websocket.MinutesGenerationStatus;
import com.ai.translation.assistant.domain.websocket.MinutesUpdateMessage;
import com.ai.translation.assistant.domain.websocket.SubtitleUpdateMessage;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 缓存会话内最终字幕，并异步生成带版本号的结构化会议纪要。
 */
@Service
@RequiredArgsConstructor
public class MinutesSessionService {

    private static final String MINUTES_UPDATE_TYPE = "minutes.update";

    private final MinutesGenerator minutesGenerator;
    private final MinutesProperties properties;
    private final ConcurrentMap<String, MinutesSessionState> sessions = new ConcurrentHashMap<>();
    private final ExecutorService minutesExecutor = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "minutes-generator");
        thread.setDaemon(true);
        return thread;
    });

    public void cacheFinalSubtitle(SubtitleUpdateMessage message) {
        if (!Boolean.TRUE.equals(message.getIsFinal()) || !hasDisplayableText(message)) {
            return;
        }

        MinutesSessionState state = sessions.computeIfAbsent(message.getSessionId(), ignored -> new MinutesSessionState());

        synchronized (state) {
            CachedSubtitleSegment existingSegment = state.segments.get(message.getSegmentId());

            // 忽略乱序或重复推送，确保缓存始终保留片段的最新修订。
            if (existingSegment != null && message.getRevision() <= existingSegment.getRevision()) {
                return;
            }

            state.segments.put(message.getSegmentId(), CachedSubtitleSegment.builder()
                .segmentId(message.getSegmentId())
                .revision(message.getRevision())
                .source(message.getSource())
                .translation(message.getTranslation())
                .updatedAtMs(System.currentTimeMillis())
                .build());
        }
    }

    public void generateMinutes(String sessionId, Consumer<MinutesUpdateMessage> minutesSender) {
        MinutesSessionState state = sessions.computeIfAbsent(sessionId, ignored -> new MinutesSessionState());
        List<CachedSubtitleSegment> snapshot = snapshotSegments(state);

        if (snapshot.isEmpty()) {
            minutesSender.accept(buildErrorUpdate(sessionId, state.revision.get(), "暂无可用于生成纪要的最终字幕"));
            return;
        }

        // 同一会话只允许一个生成任务运行，防止重复调用模型。
        if (!state.generating.compareAndSet(false, true)) {
            minutesSender.accept(buildGeneratingUpdate(sessionId, state.revision.get()));
            return;
        }

        // 仅被实际受理的生成请求递增版本，便于前端丢弃过期结果。
        int nextRevision = state.revision.incrementAndGet();
        minutesSender.accept(buildGeneratingUpdate(sessionId, nextRevision));

        CompletableFuture.runAsync(() -> {
            try {
                MinutesDraft draft = minutesGenerator.generate(buildTranscript(snapshot));
                minutesSender.accept(buildReadyUpdate(sessionId, nextRevision, draft));
            } catch (RuntimeException exception) {
                minutesSender.accept(buildErrorUpdate(sessionId, nextRevision, "纪要生成失败，请检查 API Key、网络或模型配置"));
            } finally {
                state.generating.set(false);
            }
        }, minutesExecutor);
    }

    public void closeSession(String sessionId) {
        sessions.remove(sessionId);
    }

    @PreDestroy
    public void shutdown() {
        minutesExecutor.shutdownNow();
    }

    private List<CachedSubtitleSegment> snapshotSegments(MinutesSessionState state) {
        // 生成过程使用稳定快照，不阻塞后续字幕继续写入会话缓存。
        synchronized (state) {
            return state.segments.values().stream()
                .sorted(Comparator.comparing(CachedSubtitleSegment::getUpdatedAtMs))
                .toList();
        }
    }

    private String buildTranscript(List<CachedSubtitleSegment> segments) {
        StringBuilder transcript = new StringBuilder();

        for (CachedSubtitleSegment segment : segments) {
            String text = StringUtils.hasText(segment.getTranslation())
                ? segment.getTranslation().trim()
                : cleanText(segment.getSource());

            if (StringUtils.hasText(text)) {
                transcript.append(text).append(System.lineSeparator());
            }
        }

        String value = transcript.toString().trim();
        int maxLength = properties.getMaxTranscriptChars();

        if (value.length() <= maxLength) {
            return value;
        }

        // 超限时保留最近内容，使纪要更贴近会议当前阶段。
        return value.substring(value.length() - maxLength);
    }

    private String cleanText(String text) {
        return StringUtils.hasText(text) ? text.trim() : "";
    }

    private MinutesUpdateMessage buildGeneratingUpdate(String sessionId, int revision) {
        return MinutesUpdateMessage.builder()
            .type(MINUTES_UPDATE_TYPE)
            .sessionId(sessionId)
            .revision(revision)
            .status(MinutesGenerationStatus.GENERATING)
            .updatedAtMs(System.currentTimeMillis())
            .build();
    }

    private MinutesUpdateMessage buildReadyUpdate(String sessionId, int revision, MinutesDraft draft) {
        return MinutesUpdateMessage.builder()
            .type(MINUTES_UPDATE_TYPE)
            .sessionId(sessionId)
            .revision(revision)
            .status(MinutesGenerationStatus.READY)
            .title(draft.getTitle())
            .summary(draft.getSummary())
            .keyPoints(defaultList(draft.getKeyPoints()))
            .decisions(defaultList(draft.getDecisions()))
            .actionItems(defaultList(draft.getActionItems()))
            .updatedAtMs(System.currentTimeMillis())
            .build();
    }

    private MinutesUpdateMessage buildErrorUpdate(String sessionId, int revision, String errorMessage) {
        return MinutesUpdateMessage.builder()
            .type(MINUTES_UPDATE_TYPE)
            .sessionId(sessionId)
            .revision(revision)
            .status(MinutesGenerationStatus.ERROR)
            .errorMessage(errorMessage)
            .updatedAtMs(System.currentTimeMillis())
            .build();
    }

    private List<String> defaultList(List<String> values) {
        if (values == null) {
            return new ArrayList<>();
        }

        return values.stream()
            .filter(StringUtils::hasText)
            .map(String::trim)
            .toList();
    }

    private boolean hasDisplayableText(SubtitleUpdateMessage message) {
        return StringUtils.hasText(message.getSource()) || StringUtils.hasText(message.getTranslation());
    }

    private static class MinutesSessionState {

        private final ConcurrentMap<String, CachedSubtitleSegment> segments = new ConcurrentHashMap<>();

        private final AtomicInteger revision = new AtomicInteger();

        private final AtomicBoolean generating = new AtomicBoolean(false);
    }
}
