package com.ai.translation.assistant.service.realtime;

import com.ai.translation.assistant.domain.websocket.RealtimeStatusMessage;

@FunctionalInterface
public interface RealtimeStatusListener {

    void onRealtimeStatus(RealtimeStatusMessage message);
}
