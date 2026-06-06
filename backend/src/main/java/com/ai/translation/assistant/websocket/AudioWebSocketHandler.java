package com.ai.translation.assistant.websocket;

import com.ai.translation.assistant.domain.websocket.AudioChunkMessage;
import com.ai.translation.assistant.domain.websocket.SubtitleUpdateMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
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
    private static final String SUBTITLE_UPDATE_TYPE = "subtitle.update";
    private static final String[] MOCK_SOURCE_TEXTS = {
        "we need to optimize the database query",
        "the floating subtitle window receives updates in real time",
        "each segment keeps the newest revision from the backend",
        "the websocket protocol is ready for audio streaming"
    };
    private static final String[] MOCK_TRANSLATION_TEXTS = {
        "我们需要优化数据库查询",
        "悬浮字幕窗会实时接收更新",
        "每个字幕片段都会保留后端返回的最新版本",
        "WebSocket 通信协议已准备好承载音频流"
    };

    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final AtomicInteger sequence = new AtomicInteger();

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        AudioChunkMessage audioChunkMessage;

        try {
            audioChunkMessage = objectMapper.readValue(message.getPayload(), AudioChunkMessage.class);
        } catch (IOException exception) {
            session.close(CloseStatus.BAD_DATA.withReason("Invalid json payload"));
            return;
        }

        Set<ConstraintViolation<AudioChunkMessage>> violations = validator.validate(audioChunkMessage);

        if (!violations.isEmpty() || !AUDIO_CHUNK_TYPE.equals(audioChunkMessage.getType())) {
            session.close(CloseStatus.BAD_DATA.withReason("Invalid audio chunk message"));
            return;
        }

        int currentSequence = sequence.incrementAndGet();
        int mockIndex = Math.floorMod(currentSequence - 1, MOCK_SOURCE_TEXTS.length);
        int segmentNumber = Math.floorMod(currentSequence - 1, 2) + 1;

        SubtitleUpdateMessage subtitleUpdateMessage = SubtitleUpdateMessage.builder()
            .type(SUBTITLE_UPDATE_TYPE)
            .sessionId(audioChunkMessage.getSessionId())
            .segmentId("seg-" + segmentNumber)
            .revision(currentSequence)
            .source(MOCK_SOURCE_TEXTS[mockIndex])
            .translation(MOCK_TRANSLATION_TEXTS[mockIndex])
            .isFinal(currentSequence % 4 == 0)
            .latencyMs(Duration.ofMillis(Math.max(0, System.currentTimeMillis() - audioChunkMessage.getTimestamp())).toMillis())
            .build();

        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(subtitleUpdateMessage)));
    }
}
