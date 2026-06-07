package com.ai.translation.assistant.domain.websocket;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

@Data
public class AudioSilenceMessage {

    @NotBlank
    private String type;

    @NotBlank
    private String sessionId;

    @NotNull
    private Long timestamp;

    @PositiveOrZero
    private Long durationMs;
}
