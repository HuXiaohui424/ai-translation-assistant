package com.ai.translation.assistant.domain.minutes;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MinutesDraft {

    private String title;

    private String summary;

    @Builder.Default
    private List<String> keyPoints = new ArrayList<>();

    @Builder.Default
    private List<String> decisions = new ArrayList<>();

    @Builder.Default
    private List<String> actionItems = new ArrayList<>();
}
