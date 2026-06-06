package com.ai.translation.assistant.service.realtime;

import com.ai.translation.assistant.domain.websocket.AudioChunkMessage;
import com.ai.translation.assistant.domain.websocket.RealtimeStatusMessage;
import com.ai.translation.assistant.domain.websocket.SubtitleUpdateMessage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RealtimeSessionService {

    private final RealtimeClientFactory realtimeClientFactory;
    private final ConcurrentMap<String, RealtimeClient> realtimeClients = new ConcurrentHashMap<>();

    public void forwardAudioChunk(
        AudioChunkMessage message,
        Consumer<SubtitleUpdateMessage> subtitleSender,
        Consumer<RealtimeStatusMessage> statusSender
    ) {
        RealtimeClient realtimeClient = realtimeClients.computeIfAbsent(
            message.getSessionId(),
            sessionId -> realtimeClientFactory.create(sessionId, subtitleSender::accept, statusSender::accept)
        );

        realtimeClient.sendAudioChunk(message);
    }

    public void sendSentenceEnd(String sessionId) {
        RealtimeClient realtimeClient = realtimeClients.get(sessionId);

        if (realtimeClient != null) {
            realtimeClient.sendSentenceEndSilence();
        }
    }

    public void closeSession(String sessionId) {
        RealtimeClient realtimeClient = realtimeClients.remove(sessionId);

        if (realtimeClient != null) {
            realtimeClient.close();
        }
    }
}
