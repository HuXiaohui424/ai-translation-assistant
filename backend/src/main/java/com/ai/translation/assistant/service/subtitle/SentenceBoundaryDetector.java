package com.ai.translation.assistant.service.subtitle;

import com.ai.translation.assistant.domain.subtitle.SubtitleSegment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class SentenceBoundaryDetector {

    private static final long SILENCE_BOUNDARY_MS = 600;
    private static final long MAX_SEGMENT_DURATION_MS = 10_000;
    private static final long FINAL_REVISION_GRACE_MS = 2_500;
    private static final String TERMINAL_PUNCTUATION_PATTERN = ".*[.!?。！？]+[\"')\\]}]*$";

    public boolean shouldFinishBySilence(long silenceDurationMs) {
        return silenceDurationMs >= SILENCE_BOUNDARY_MS;
    }

    public boolean shouldFinishByModelFinal(boolean modelFinal) {
        return modelFinal;
    }

    public boolean shouldFinishByDuration(SubtitleSegment segment, long currentTimeMs) {
        return segment != null
            && !Boolean.TRUE.equals(segment.getIsFinal())
            && currentTimeMs - segment.getStartedAtMs() >= MAX_SEGMENT_DURATION_MS;
    }

    public boolean shouldFinishByPunctuation(String source, String translation) {
        return endsWithTerminalPunctuation(source) || endsWithTerminalPunctuation(translation);
    }

    public boolean canReviseFinalSegment(SubtitleSegment segment, String source, String translation, long currentTimeMs) {
        return segment != null
            && Boolean.TRUE.equals(segment.getIsFinal())
            && hasTextChanged(segment, source, translation)
            && segment.getFinalizedAtMs() != null
            && currentTimeMs - segment.getFinalizedAtMs() <= FINAL_REVISION_GRACE_MS;
    }

    private boolean endsWithTerminalPunctuation(String text) {
        return StringUtils.hasText(text) && text.trim().matches(TERMINAL_PUNCTUATION_PATTERN);
    }

    private boolean hasTextChanged(SubtitleSegment segment, String source, String translation) {
        return !normalize(segment.getSource()).equals(normalize(source))
            || !normalize(segment.getTranslation()).equals(normalize(translation));
    }

    private String normalize(String text) {
        return StringUtils.hasText(text) ? text.trim() : "";
    }
}
