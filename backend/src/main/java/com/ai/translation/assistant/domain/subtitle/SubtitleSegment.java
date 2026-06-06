package com.ai.translation.assistant.domain.subtitle;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SubtitleSegment {

    private String segmentId;

    private String source;

    private String translation;

    private Integer revision;

    private Boolean isFinal;
}
