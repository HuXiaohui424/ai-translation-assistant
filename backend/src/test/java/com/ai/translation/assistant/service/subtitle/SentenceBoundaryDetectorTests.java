package com.ai.translation.assistant.service.subtitle;

import com.ai.translation.assistant.domain.subtitle.SubtitleSegment;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SentenceBoundaryDetectorTests {

    private final SentenceBoundaryDetector detector = new SentenceBoundaryDetector();

    @Test
    void allowsFinalSegmentRevisionWithinGraceWindow() {
        SubtitleSegment segment = finalSegment();

        boolean canRevise = detector.canReviseFinalSegment(
            segment,
            "we need to optimize the database",
            "we need to optimize the database",
            2_500L,
            1_000L
        );

        assertThat(canRevise).isTrue();
    }

    @Test
    void rejectsFinalSegmentRevisionWhenTextDoesNotChange() {
        SubtitleSegment segment = finalSegment();

        boolean canRevise = detector.canReviseFinalSegment(
            segment,
            "we need to optimize",
            "we need to optimize",
            2_500L,
            1_000L
        );

        assertThat(canRevise).isFalse();
    }

    @Test
    void rejectsFinalSegmentRevisionOutsideGraceWindow() {
        SubtitleSegment segment = finalSegment();

        boolean canRevise = detector.canReviseFinalSegment(
            segment,
            "we need to optimize the database",
            "we need to optimize the database",
            3_001L,
            1_000L
        );

        assertThat(canRevise).isFalse();
    }

    @Test
    void finishesBySilenceWhenBoundaryIsReached() {
        assertThat(detector.shouldFinishBySilence(699L, 700L)).isFalse();
        assertThat(detector.shouldFinishBySilence(700L, 700L)).isTrue();
    }

    @Test
    void finishesSegmentWhenDurationReachesLimit() {
        SubtitleSegment segment = SubtitleSegment.builder()
            .segmentId("seg-1")
            .source("hello")
            .translation("hello")
            .revision(1)
            .isFinal(false)
            .startedAtMs(1_000L)
            .build();

        boolean shouldFinish = detector.shouldFinishByDuration(segment, 4_000L, 3_000L);

        assertThat(shouldFinish).isTrue();
    }

    @Test
    void keepsSegmentWhenDurationIsBelowLimit() {
        SubtitleSegment segment = SubtitleSegment.builder()
            .segmentId("seg-1")
            .source("hello")
            .translation("hello")
            .revision(1)
            .isFinal(false)
            .startedAtMs(1_000L)
            .build();

        boolean shouldFinish = detector.shouldFinishByDuration(segment, 3_999L, 3_000L);

        assertThat(shouldFinish).isFalse();
    }

    private SubtitleSegment finalSegment() {
        return SubtitleSegment.builder()
            .segmentId("seg-1")
            .source("we need to optimize")
            .translation("we need to optimize")
            .revision(2)
            .isFinal(true)
            .startedAtMs(1_000L)
            .finalizedAtMs(2_000L)
            .build();
    }
}
