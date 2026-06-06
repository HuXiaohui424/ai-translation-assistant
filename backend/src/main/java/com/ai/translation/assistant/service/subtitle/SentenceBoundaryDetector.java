package com.ai.translation.assistant.service.subtitle;

import com.ai.translation.assistant.domain.subtitle.SubtitleSegment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class SentenceBoundaryDetector {

    private static final long SILENCE_BOUNDARY_MS = 600;
    public boolean shouldFinishBySilence(long silenceDurationMs) {
        return silenceDurationMs >= SILENCE_BOUNDARY_MS;
    }

    public boolean shouldFinishByModelFinal(boolean modelFinal) {
        return modelFinal;
    }

    public boolean canReviseFinalSegment(SubtitleSegment segment, String source, String translation) {
        return segment != null
            && Boolean.TRUE.equals(segment.getIsFinal())
            && hasTextChanged(segment, source, translation);
    }

    private boolean hasTextChanged(SubtitleSegment segment, String source, String translation) {
        return !normalize(segment.getSource()).equals(normalize(source))
            || !normalize(segment.getTranslation()).equals(normalize(translation));
    }

    private String normalize(String text) {
        return StringUtils.hasText(text) ? text.trim() : "";
    }
}
