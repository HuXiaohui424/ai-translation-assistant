package com.ai.translation.assistant.domain.websocket;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SubtitleUpdateMessage {

    private String type;

    private String sessionId;

    private String segmentId;

    private Integer revision;

    private String source;

    private String translation;

    private Boolean isFinal;

    private Long latencyMs;
}
