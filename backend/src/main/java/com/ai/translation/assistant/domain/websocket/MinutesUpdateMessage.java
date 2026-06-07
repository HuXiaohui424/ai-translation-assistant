package com.ai.translation.assistant.domain.websocket;

import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MinutesUpdateMessage {

    private String type;

    private String sessionId;

    private Integer revision;

    private MinutesGenerationStatus status;

    private String title;

    private String summary;

    @Builder.Default
    private List<String> keyPoints = new ArrayList<>();

    @Builder.Default
    private List<String> decisions = new ArrayList<>();

    @Builder.Default
    private List<String> actionItems = new ArrayList<>();

    private Long updatedAtMs;

    private String errorMessage;
}
