package com.ai.translation.assistant.service.minutes;

import com.ai.translation.assistant.config.MinutesProperties;
import com.ai.translation.assistant.domain.minutes.MinutesDraft;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
public class MinutesAiClient implements MinutesGenerator {

    private static final String SYSTEM_PROMPT = """
        你是专业会议纪要助手。请根据用户提供的实时字幕转写生成中文会议纪要。
        只返回 JSON 对象，不要返回 Markdown，不要添加解释。
        JSON 字段必须包含：title、summary、keyPoints、decisions、actionItems。
        keyPoints、decisions、actionItems 必须是字符串数组；没有内容时返回空数组。
        """;

    private final MinutesProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public MinutesDraft generate(String transcript) {
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new IllegalStateException("DashScope api key is not configured");
        }

        RestClient restClient = RestClient.builder()
            .baseUrl(properties.getBaseUrl())
            .requestFactory(ClientHttpRequestFactories.withTimeout(Duration.ofMillis(properties.getTimeoutMs())))
            .build();

        ChatCompletionResponse response = restClient.post()
            .uri("/chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Authorization", "Bearer " + properties.getApiKey())
            .body(ChatCompletionRequest.jsonMode(properties.getModel(), SYSTEM_PROMPT, transcript))
            .retrieve()
            .body(ChatCompletionResponse.class);

        String content = extractContent(response);

        return parseDraftContent(content);
    }

    MinutesDraft parseDraftContent(String content) {
        try {
            MinutesDraft draft = objectMapper.readValue(content, MinutesDraft.class);
            return normalizeDraft(draft);
        } catch (Exception exception) {
            return MinutesDraft.builder()
                .title("会议纪要")
                .summary(content)
                .build();
        }
    }

    private String extractContent(ChatCompletionResponse response) {
        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
            throw new IllegalStateException("Minutes generation response is empty");
        }

        ChatChoice choice = response.getChoices().get(0);

        if (choice.getMessage() == null || !StringUtils.hasText(choice.getMessage().getContent())) {
            throw new IllegalStateException("Minutes generation content is empty");
        }

        return choice.getMessage().getContent();
    }

    private MinutesDraft normalizeDraft(MinutesDraft draft) {
        if (!StringUtils.hasText(draft.getTitle())) {
            draft.setTitle("会议纪要");
        }

        if (draft.getSummary() == null) {
            draft.setSummary("");
        }

        if (draft.getKeyPoints() == null) {
            draft.setKeyPoints(List.of());
        }

        if (draft.getDecisions() == null) {
            draft.setDecisions(List.of());
        }

        if (draft.getActionItems() == null) {
            draft.setActionItems(List.of());
        }

        return draft;
    }

    private record ChatCompletionRequest(
        String model,
        List<ChatMessage> messages,
        @JsonProperty("response_format")
        ResponseFormat responseFormat
    ) {

        private static ChatCompletionRequest jsonMode(String model, String systemPrompt, String transcript) {
            return new ChatCompletionRequest(
                model,
                List.of(
                    new ChatMessage("system", systemPrompt),
                    new ChatMessage("user", "请基于以下字幕转写生成会议纪要：\n\n" + transcript)
                ),
                new ResponseFormat("json_object")
            );
        }
    }

    private record ChatMessage(String role, String content) {
    }

    private record ResponseFormat(String type) {
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ChatCompletionResponse {

        private List<ChatChoice> choices;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ChatChoice {

        private ChatMessageResponse message;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ChatMessageResponse {

        private String content;
    }
}
