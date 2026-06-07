package com.ai.translation.assistant.websocket;

import com.ai.translation.assistant.config.RealtimeApiProperties;
import com.ai.translation.assistant.domain.websocket.AudioChunkMessage;
import com.ai.translation.assistant.domain.websocket.AudioSilenceMessage;
import com.ai.translation.assistant.domain.websocket.MinutesGenerateMessage;
import com.ai.translation.assistant.domain.websocket.RealtimeConnectionStatus;
import com.ai.translation.assistant.domain.websocket.RealtimeStatusMessage;
import com.ai.translation.assistant.domain.websocket.SubtitleUpdateMessage;
import com.ai.translation.assistant.service.minutes.MinutesSessionService;
import com.ai.translation.assistant.service.realtime.RealtimeSessionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.util.Base64;
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

/**
 * 校验并分发客户端 WebSocket 消息，同时维护连接与业务会话的关联关系。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AudioWebSocketHandler extends TextWebSocketHandler {

    private static final String AUDIO_CHUNK_TYPE = "audio.chunk";
    private static final String AUDIO_SILENCE_TYPE = "audio.silence";
    private static final String MINUTES_GENERATE_TYPE = "minutes.generate";

    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final RealtimeApiProperties realtimeApiProperties;
    private final RealtimeSessionService realtimeSessionService;
    private final MinutesSessionService minutesSessionService;

    // WebSocket 连接 ID 与业务会话 ID 的映射，用于断开连接时统一释放资源。
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

        if (MINUTES_GENERATE_TYPE.equals(messageType)) {
            MinutesGenerateMessage minutesGenerateMessage;

            try {
                minutesGenerateMessage = objectMapper.treeToValue(payloadNode, MinutesGenerateMessage.class);
            } catch (IOException exception) {
                session.close(CloseStatus.BAD_DATA.withReason("Invalid json payload"));
                return;
            }

            Set<ConstraintViolation<MinutesGenerateMessage>> violations = validator.validate(minutesGenerateMessage);

            if (!violations.isEmpty()) {
                session.close(CloseStatus.BAD_DATA.withReason("Invalid minutes generate message"));
                return;
            }

            websocketSessionIds.put(session.getId(), minutesGenerateMessage.getSessionId());
            minutesSessionService.generateMinutes(minutesGenerateMessage.getSessionId(), minutesUpdateMessage -> sendMessage(session, minutesUpdateMessage));
            return;
        }

        if (AUDIO_SILENCE_TYPE.equals(messageType)) {
            AudioSilenceMessage silenceMessage;

            try {
                silenceMessage = objectMapper.treeToValue(payloadNode, AudioSilenceMessage.class);
            } catch (IOException exception) {
                session.close(CloseStatus.BAD_DATA.withReason("Invalid json payload"));
                return;
            }

            Set<ConstraintViolation<AudioSilenceMessage>> violations = validator.validate(silenceMessage);

            if (!violations.isEmpty()) {
                session.close(CloseStatus.BAD_DATA.withReason("Invalid audio silence message"));
                return;
            }

            websocketSessionIds.put(session.getId(), silenceMessage.getSessionId());
            realtimeSessionService.handleSilence(silenceMessage, subtitleUpdateMessage -> sendSubtitleUpdate(session, subtitleUpdateMessage));
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

        if (!hasExpectedAudioParameters(audioChunkMessage) || !isValidBase64(audioChunkMessage.getData())) {
            session.close(CloseStatus.BAD_DATA.withReason("Invalid audio chunk message"));
            return;
        }

        websocketSessionIds.put(session.getId(), audioChunkMessage.getSessionId());
        try {
            realtimeSessionService.forwardAudioChunk(
                audioChunkMessage,
                subtitleUpdateMessage -> sendSubtitleUpdate(session, subtitleUpdateMessage),
                statusMessage -> sendMessage(session, statusMessage)
            );
        } catch (RuntimeException exception) {
            // 启动或发送失败后立即淘汰客户端，避免继续复用失效连接。
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

    private boolean hasExpectedAudioParameters(AudioChunkMessage message) {
        return realtimeApiProperties.getSampleRate().equals(message.getSampleRate())
            && realtimeApiProperties.getFormat().equalsIgnoreCase(message.getFormat());
    }

    private boolean isValidBase64(String data) {
        try {
            Base64.getDecoder().decode(data);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = websocketSessionIds.remove(session.getId());

        if (sessionId != null) {
            realtimeSessionService.closeSession(sessionId);
            minutesSessionService.closeSession(sessionId);
        }
    }

    private void sendSubtitleUpdate(WebSocketSession session, SubtitleUpdateMessage subtitleUpdateMessage) {
        sendMessage(session, subtitleUpdateMessage);

        // 纪要只使用最终字幕，避免缓存仍在持续修订的临时结果。
        if (Boolean.TRUE.equals(subtitleUpdateMessage.getIsFinal())) {
            minutesSessionService.cacheFinalSubtitle(subtitleUpdateMessage);
        }
    }

    private void sendMessage(WebSocketSession session, Object outgoingMessage) {
        if (!session.isOpen()) {
            return;
        }

        try {
            // WebSocketSession 不保证并发发送安全，同一连接的写操作需要串行化。
            synchronized (session) {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(outgoingMessage)));
            }
        } catch (IOException exception) {
            // 推送失败时停止后台识别及重连，避免连接断开后继续占用资源。
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
