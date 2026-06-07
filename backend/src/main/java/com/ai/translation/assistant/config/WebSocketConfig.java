package com.ai.translation.assistant.config;

import com.ai.translation.assistant.websocket.AudioWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final AudioWebSocketHandler audioWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 桌面端通过单一端点发送音频、静音边界和纪要生成指令。
        registry.addHandler(audioWebSocketHandler, "/ws/audio")
            .setAllowedOrigins("*");
    }
}
