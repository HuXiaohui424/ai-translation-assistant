package com.ai.translation.assistant.domain.websocket;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RealtimeStatusMessage {

    private String type;

    private String sessionId;

    private RealtimeConnectionStatus status;

    private String message;
}
