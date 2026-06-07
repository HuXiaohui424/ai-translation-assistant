package com.ai.translation.assistant.domain.websocket;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum RealtimeConnectionStatus {

    CONNECTING("connecting"),
    CONNECTED("connected"),
    COMPLETED("completed"),
    ERROR("error");

    @JsonValue
    private final String value;
}
