package com.ai.translation.assistant.service.subtitle;

import com.ai.translation.assistant.domain.subtitle.SubtitleSegment;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SentenceBoundaryDetectorTests {

    private final SentenceBoundaryDetector detector = new SentenceBoundaryDetector();

    @Test
    void allowsFinalSegmentRevisionWithinGraceWindow() {
        SubtitleSegment segment = SubtitleSegment.builder()
            .segmentId("seg-1")
            .source("we need to optimize")
            .translation("我们需要优化")
            .revision(2)
            .isFinal(true)
            .startedAtMs(1_000L)
            .finalizedAtMs(2_000L)
            .build();

        boolean canRevise = detector.canReviseFinalSegment(
            segment,
            "we need to optimize the database",
            "我们需要优化数据库"
        );

        assertThat(canRevise).isTrue();
    }

    @Test
    void rejectsFinalSegmentRevisionWhenTextDoesNotChange() {
        SubtitleSegment segment = SubtitleSegment.builder()
            .segmentId("seg-1")
            .source("we need to optimize")
            .translation("我们需要优化")
            .revision(2)
            .isFinal(true)
            .startedAtMs(1_000L)
            .finalizedAtMs(2_000L)
            .build();

        boolean canRevise = detector.canReviseFinalSegment(
            segment,
            "we need to optimize",
            "我们需要优化"
        );

        assertThat(canRevise).isFalse();
    }
}
