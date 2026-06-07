package com.ai.translation.assistant.domain.websocket;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class MinutesGenerateMessage {

    @NotBlank
    private String type;

    @NotBlank
    private String sessionId;
}
