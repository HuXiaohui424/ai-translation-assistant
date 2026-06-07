package com.ai.translation.assistant.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "aliyun.minutes")
public class MinutesProperties {

    private String apiKey;

    @NotBlank
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    @NotBlank
    private String model = "qwen-plus";

    @NotNull
    @Min(1000)
    private Integer maxTranscriptChars = 12000;

    @NotNull
    @Min(1000)
    private Integer timeoutMs = 30000;
}
