package com.ai.translation.assistant.service.minutes;

import com.ai.translation.assistant.config.MinutesProperties;
import com.ai.translation.assistant.domain.minutes.MinutesDraft;
import com.ai.translation.assistant.domain.websocket.MinutesGenerationStatus;
import com.ai.translation.assistant.domain.websocket.MinutesUpdateMessage;
import com.ai.translation.assistant.domain.websocket.SubtitleUpdateMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MinutesSessionServiceTests {

    @Test
    void generatesMinutesFromCachedFinalSubtitles() throws Exception {
        RecordingMinutesGenerator generator = new RecordingMinutesGenerator(MinutesDraft.builder()
            .title("项目例会")
            .summary("团队同步了项目进展。")
            .keyPoints(List.of("确认开发计划"))
            .decisions(List.of("本周完成纪要功能"))
            .actionItems(List.of("完善测试"))
            .build());
        MinutesSessionService service = new MinutesSessionService(generator, properties());
        service.cacheFinalSubtitle(subtitle("seg-1", 1, "hello", "你好"));
        service.cacheFinalSubtitle(subtitle("seg-1", 1, "older", "旧内容"));
        CountDownLatch latch = new CountDownLatch(2);
        List<MinutesUpdateMessage> updates = new ArrayList<>();

        service.generateMinutes("session-1", update -> {
            updates.add(update);
            latch.countDown();
        });

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(updates).extracting(MinutesUpdateMessage::getStatus)
            .containsExactly(MinutesGenerationStatus.GENERATING, MinutesGenerationStatus.READY);
        assertThat(updates.get(1).getTitle()).isEqualTo("项目例会");
        assertThat(generator.getTranscript()).contains("你好");
        assertThat(generator.getTranscript()).doesNotContain("旧内容");
        service.shutdown();
    }

    @Test
    void returnsErrorWhenNoFinalSubtitleExists() {
        MinutesSessionService service = new MinutesSessionService(new RecordingMinutesGenerator(null), properties());
        List<MinutesUpdateMessage> updates = new ArrayList<>();

        service.generateMinutes("session-1", updates::add);

        assertThat(updates).hasSize(1);
        assertThat(updates.get(0).getStatus()).isEqualTo(MinutesGenerationStatus.ERROR);
        assertThat(updates.get(0).getErrorMessage()).contains("暂无可用于生成纪要");
        service.shutdown();
    }

    @Test
    void returnsErrorWhenGeneratorFails() throws Exception {
        MinutesSessionService service = new MinutesSessionService(transcript -> {
            throw new IllegalStateException("failed");
        }, properties());
        service.cacheFinalSubtitle(subtitle("seg-1", 1, "hello", "你好"));
        CountDownLatch latch = new CountDownLatch(2);
        List<MinutesUpdateMessage> updates = new ArrayList<>();

        service.generateMinutes("session-1", update -> {
            updates.add(update);
            latch.countDown();
        });

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(updates).extracting(MinutesUpdateMessage::getStatus)
            .containsExactly(MinutesGenerationStatus.GENERATING, MinutesGenerationStatus.ERROR);
        service.shutdown();
    }

    private MinutesProperties properties() {
        MinutesProperties properties = new MinutesProperties();
        properties.setMaxTranscriptChars(12000);
        return properties;
    }

    private SubtitleUpdateMessage subtitle(String segmentId, int revision, String source, String translation) {
        return SubtitleUpdateMessage.builder()
            .type("subtitle.update")
            .sessionId("session-1")
            .segmentId(segmentId)
            .revision(revision)
            .source(source)
            .translation(translation)
            .isFinal(true)
            .latencyMs(100L)
            .build();
    }

    private static class RecordingMinutesGenerator implements MinutesGenerator {

        private final MinutesDraft draft;

        private String transcript;

        private RecordingMinutesGenerator(MinutesDraft draft) {
            this.draft = draft;
        }

        @Override
        public MinutesDraft generate(String transcript) {
            this.transcript = transcript;
            return draft;
        }

        private String getTranscript() {
            return transcript;
        }
    }
}
