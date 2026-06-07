package com.ai.translation.assistant.websocket;

import com.ai.translation.assistant.domain.websocket.AudioChunkMessage;
import com.ai.translation.assistant.domain.websocket.AudioSilenceMessage;
import com.ai.translation.assistant.domain.websocket.RealtimeConnectionStatus;
import com.ai.translation.assistant.domain.websocket.RealtimeStatusMessage;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
@RequiredArgsConstructor
@Slf4j
public class AudioWebSocketHandler extends TextWebSocketHandler {

    private static final String AUDIO_CHUNK_TYPE = "audio.chunk";
    private static final String AUDIO_SILENCE_TYPE = "audio.silence";

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

        if (AUDIO_SILENCE_TYPE.equals(messageType)) {
            websocketSessionIds.put(session.getId(), sessionId);
            AudioSilenceMessage silenceMessage = objectMapper.treeToValue(payloadNode, AudioSilenceMessage.class);
            Set<ConstraintViolation<AudioSilenceMessage>> violations = validator.validate(silenceMessage);

            if (!violations.isEmpty()) {
                session.close(CloseStatus.BAD_DATA.withReason("Invalid audio silence message"));
                return;
            }

            realtimeSessionService.handleSilence(silenceMessage, subtitleUpdateMessage -> sendMessage(session, subtitleUpdateMessage));
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
            realtimeSessionService.forwardAudioChunk(
                audioChunkMessage,
                subtitleUpdateMessage -> sendMessage(session, subtitleUpdateMessage),
                statusMessage -> sendMessage(session, statusMessage)
            );
        } catch (RuntimeException exception) {
            log.warn("Realtime service failed, sessionId={}", audioChunkMessage.getSessionId(), exception);
            realtimeSessionService.closeSession(audioChunkMessage.getSessionId());
            sendMessage(session, RealtimeStatusMessage.builder()
                .type("realtime.status")
                .sessionId(audioChunkMessage.getSessionId())
                .status(RealtimeConnectionStatus.ERROR)
                .message(buildRealtimeErrorMessage(exception))
                .build());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = websocketSessionIds.remove(session.getId());

        if (sessionId != null) {
            realtimeSessionService.closeSession(sessionId);
        }
    }

    private void sendMessage(WebSocketSession session, Object outgoingMessage) {
        if (!session.isOpen()) {
            return;
        }

        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(outgoingMessage)));
            }
        } catch (IOException exception) {
            websocketSessionIds.computeIfPresent(session.getId(), (websocketSessionId, realtimeSessionId) -> {
                realtimeSessionService.closeSession(realtimeSessionId);
                return null;
            });
        }
    }

    private String buildRealtimeErrorMessage(RuntimeException exception) {
        if (exception instanceof IllegalStateException) {
            return "后端未配置 DASHSCOPE_API_KEY，实时识别未启动";
        }

        return "实时识别服务不可用，请检查 API Key、网络或模型配置";
    }
}
