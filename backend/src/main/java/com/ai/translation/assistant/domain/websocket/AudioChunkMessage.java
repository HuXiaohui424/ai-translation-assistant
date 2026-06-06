package com.ai.translation.assistant.domain.websocket;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class AudioChunkMessage {

    @NotBlank
    private String type;

    @NotBlank
    private String sessionId;

    @NotNull
    private Long timestamp;

    @Positive
    private Integer sampleRate;

    @NotBlank
    private String format;

    @NotBlank
    private String data;
}
