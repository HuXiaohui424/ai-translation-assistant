package com.ai.translation.assistant.service.minutes;

import java.time.Duration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

final class ClientHttpRequestFactories {

    private ClientHttpRequestFactories() {
    }

    static ClientHttpRequestFactory withTimeout(Duration timeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);

        return requestFactory;
    }
}
