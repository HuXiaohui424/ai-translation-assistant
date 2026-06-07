package com.ai.translation.assistant.domain.minutes;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CachedSubtitleSegment {

    private String segmentId;

    private Integer revision;

    private String source;

    private String translation;

    private Long updatedAtMs;
}
