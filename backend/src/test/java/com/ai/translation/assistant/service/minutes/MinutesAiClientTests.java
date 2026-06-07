package com.ai.translation.assistant.service.minutes;

import com.ai.translation.assistant.config.MinutesProperties;
import com.ai.translation.assistant.domain.minutes.MinutesDraft;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MinutesAiClientTests {

    @Test
    void parsesJsonDraftContent() {
        MinutesAiClient client = new MinutesAiClient(new MinutesProperties(), new ObjectMapper());

        MinutesDraft draft = client.parseDraftContent("""
            {
              "title": "产品周会",
              "summary": "讨论了版本计划。",
              "keyPoints": ["确认需求范围"],
              "decisions": ["周五发布"],
              "actionItems": ["补充验收用例"]
            }
            """);

        assertThat(draft.getTitle()).isEqualTo("产品周会");
        assertThat(draft.getKeyPoints()).containsExactly("确认需求范围");
        assertThat(draft.getDecisions()).containsExactly("周五发布");
        assertThat(draft.getActionItems()).containsExactly("补充验收用例");
    }

    @Test
    void fallsBackToSummaryWhenContentIsNotJson() {
        MinutesAiClient client = new MinutesAiClient(new MinutesProperties(), new ObjectMapper());

        MinutesDraft draft = client.parseDraftContent("这是一段普通文本摘要。");

        assertThat(draft.getTitle()).isEqualTo("会议纪要");
        assertThat(draft.getSummary()).isEqualTo("这是一段普通文本摘要。");
        assertThat(draft.getKeyPoints()).isEmpty();
    }
}
