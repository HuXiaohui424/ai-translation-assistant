package com.ai.translation.assistant.service.realtime;

import com.ai.translation.assistant.config.RealtimeApiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RealtimeClientFactory {

    private final RealtimeApiProperties properties;

    public RealtimeClient create(String sessionId, RealtimeSubtitleListener listener) {
        return new RealtimeClient(sessionId, properties, listener);
    }
}
