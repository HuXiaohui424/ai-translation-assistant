package com.ai.translation.assistant.service.realtime;

import com.ai.translation.assistant.config.RealtimeApiProperties;
import com.ai.translation.assistant.service.subtitle.SentenceBoundaryDetector;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RealtimeClientFactory {

    private final RealtimeApiProperties properties;
    private final SentenceBoundaryDetector sentenceBoundaryDetector;

    public RealtimeClient create(
        String sessionId,
        RealtimeSubtitleListener subtitleListener,
        RealtimeStatusListener statusListener
    ) {
        return new RealtimeClient(sessionId, properties, subtitleListener, statusListener, sentenceBoundaryDetector);
    }
}
