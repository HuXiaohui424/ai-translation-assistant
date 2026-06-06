package com.ai.translation.assistant.websocket;

import com.ai.translation.assistant.domain.websocket.AudioChunkMessage;
import com.ai.translation.assistant.domain.websocket.SubtitleUpdateMessage;
import com.ai.translation.assistant.service.realtime.RealtimeSessionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
@RequiredArgsConstructor
public class AudioWebSocketHandler extends TextWebSocketHandler {

    private static final String AUDIO_CHUNK_TYPE = "audio.chunk";
    private static final String AUDIO_SENTENCE_END_TYPE = "audio.sentence_end";

    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final RealtimeSessionService realtimeSessionService;
    private final ConcurrentMap<String, String> websocketSessionIds = new ConcurrentHashMap<>();

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        JsonNode payloadNode;

        try {
            payloadNode = objectMapper.readTree(message.getPayload());
        } catch (IOException exception) {
            session.close(CloseStatus.BAD_DATA.withReason("Invalid json payload"));
            return;
        }

        String messageType = payloadNode.path("type").asText();
        String sessionId = payloadNode.path("sessionId").asText();

        if (AUDIO_SENTENCE_END_TYPE.equals(messageType)) {
            websocketSessionIds.put(session.getId(), sessionId);
            realtimeSessionService.sendSentenceEnd(sessionId);
            return;
        }

        if (!AUDIO_CHUNK_TYPE.equals(messageType)) {
            session.close(CloseStatus.BAD_DATA.withReason("Unsupported message type"));
            return;
        }

        AudioChunkMessage audioChunkMessage;

        try {
            audioChunkMessage = objectMapper.treeToValue(payloadNode, AudioChunkMessage.class);
        } catch (IOException exception) {
            session.close(CloseStatus.BAD_DATA.withReason("Invalid json payload"));
            return;
        }

        Set<ConstraintViolation<AudioChunkMessage>> violations = validator.validate(audioChunkMessage);

        if (!violations.isEmpty()) {
            session.close(CloseStatus.BAD_DATA.withReason("Invalid audio chunk message"));
            return;
        }

        websocketSessionIds.put(session.getId(), audioChunkMessage.getSessionId());
        try {
            realtimeSessionService.forwardAudioChunk(audioChunkMessage, subtitleUpdateMessage -> sendSubtitleUpdate(session, subtitleUpdateMessage));
        } catch (RuntimeException exception) {
            realtimeSessionService.closeSession(audioChunkMessage.getSessionId());
            session.close(CloseStatus.SERVER_ERROR.withReason("Realtime service unavailable"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = websocketSessionIds.remove(session.getId());

        if (sessionId != null) {
            realtimeSessionService.closeSession(sessionId);
        }
    }

    private void sendSubtitleUpdate(WebSocketSession session, SubtitleUpdateMessage subtitleUpdateMessage) {
        if (!session.isOpen()) {
            realtimeSessionService.closeSession(subtitleUpdateMessage.getSessionId());
            return;
        }

        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(subtitleUpdateMessage)));
            }
        } catch (IOException exception) {
            realtimeSessionService.closeSession(subtitleUpdateMessage.getSessionId());
        }
    }
}
