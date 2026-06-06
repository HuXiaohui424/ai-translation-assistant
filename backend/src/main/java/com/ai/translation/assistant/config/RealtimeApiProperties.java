package com.ai.translation.assistant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "aliyun.realtime")
public class RealtimeApiProperties {

    private String apiKey;

    private String model = "gummy-realtime-v1";

    private String sourceLanguage = "auto";

    private String targetLanguage = "zh";

    private Integer sampleRate = 16000;

    private String format = "pcm";

    private Integer maxEndSilence = 700;
}
