package com.ai.translation.assistant.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "aliyun.realtime")
public class RealtimeApiProperties {

    private String apiKey;

    @NotBlank
    private String model = "gummy-realtime-v1";

    @NotBlank
    private String sourceLanguage = "auto";

    @NotBlank
    private String targetLanguage = "zh";

    @NotNull
    @Min(8000)
    private Integer sampleRate = 16000;

    @NotBlank
    private String format = "pcm";

    @NotNull
    @Min(0)
    private Integer maxEndSilence = 700;

    @NotNull
    @Min(0)
    private Long silenceBoundaryMs = 700L;

    @NotNull
    @Min(500)
    private Long maxSegmentDurationMs = 3000L;

    @NotNull
    @Min(0)
    private Long finalSegmentRevisionGraceMs = 1000L;

    @NotNull
    @Min(0)
    private Integer reconnectMaxAttempts = 5;

    @NotNull
    @Min(100)
    private Long reconnectInitialDelayMs = 1000L;

    @NotNull
    @Min(100)
    private Long reconnectMaxDelayMs = 10000L;
}
