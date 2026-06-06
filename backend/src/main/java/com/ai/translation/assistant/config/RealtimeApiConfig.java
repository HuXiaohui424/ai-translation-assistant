package com.ai.translation.assistant.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RealtimeApiProperties.class)
public class RealtimeApiConfig {
}
