package com.ai.translation.assistant.domain.websocket;

import com.fasterxml.jackson.annotation.JsonValue;

public enum MinutesGenerationStatus {

    GENERATING("generating"),
    READY("ready"),
    ERROR("error");

    private final String value;

    MinutesGenerationStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
