package com.ai.translation.assistant.service.realtime;

import com.ai.translation.assistant.domain.websocket.SubtitleUpdateMessage;

@FunctionalInterface
public interface RealtimeSubtitleListener {

    void onSubtitleUpdate(SubtitleUpdateMessage message);
}
