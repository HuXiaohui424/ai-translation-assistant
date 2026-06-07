package com.ai.translation.assistant.service.realtime;

import com.ai.translation.assistant.config.RealtimeApiProperties;
import com.ai.translation.assistant.domain.subtitle.SubtitleSegment;
import com.ai.translation.assistant.domain.websocket.AudioSilenceMessage;
import com.ai.translation.assistant.domain.websocket.SubtitleUpdateMessage;
import com.ai.translation.assistant.service.subtitle.SentenceBoundaryDetector;
import java.lang.reflect.Field;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RealtimeClientTests {

    @Test
    void finalizesActiveSegmentWhenSilenceBoundaryIsReached() throws Exception {
        RealtimeClient client = createClient();
        SubtitleSegment activeSegment = SubtitleSegment.builder()
            .segmentId("seg-1")
            .source("hello")
            .translation("hello")
            .revision(3)
            .isFinal(false)
            .startedAtMs(System.currentTimeMillis() - 500L)
            .build();
        setField(client, "activeSegment", activeSegment);

        Optional<SubtitleUpdateMessage> update = client.handleSilence(silenceMessage(700L));

        assertThat(update).isPresent();
        assertThat(update.get().getSegmentId()).isEqualTo("seg-1");
        assertThat(update.get().getRevision()).isEqualTo(4);
        assertThat(update.get().getIsFinal()).isTrue();
        assertThat(activeSegment.getFinalizedAtMs()).isNotNull();
    }

    @Test
    void keepsActiveSegmentOpenWhenSilenceBoundaryIsNotReached() throws Exception {
        RealtimeClient client = createClient();
        SubtitleSegment activeSegment = SubtitleSegment.builder()
            .segmentId("seg-1")
            .source("hello")
            .translation("hello")
            .revision(3)
            .isFinal(false)
            .startedAtMs(System.currentTimeMillis() - 500L)
            .build();
        setField(client, "activeSegment", activeSegment);

        Optional<SubtitleUpdateMessage> update = client.handleSilence(silenceMessage(699L));

        assertThat(update).isEmpty();
        assertThat(activeSegment.getIsFinal()).isFalse();
        assertThat(activeSegment.getRevision()).isEqualTo(3);
    }

    private RealtimeClient createClient() {
        RealtimeApiProperties properties = new RealtimeApiProperties();
        properties.setSilenceBoundaryMs(700L);
        properties.setFinalSegmentRevisionGraceMs(1000L);

        return new RealtimeClient(
            "session-1",
            properties,
            ignored -> {
            },
            ignored -> {
            },
            new SentenceBoundaryDetector()
        );
    }

    private AudioSilenceMessage silenceMessage(long durationMs) {
        AudioSilenceMessage message = new AudioSilenceMessage();
        message.setType("audio.silence");
        message.setSessionId("session-1");
        message.setTimestamp(System.currentTimeMillis());
        message.setDurationMs(durationMs);
        return message;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
