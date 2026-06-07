package com.ai.translation.assistant.service.minutes;

import com.ai.translation.assistant.domain.minutes.MinutesDraft;

public interface MinutesGenerator {

    MinutesDraft generate(String transcript);
}
