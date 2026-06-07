package com.ai.translation.assistant.service.subtitle;

import com.ai.translation.assistant.domain.subtitle.SubtitleSegment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 集中定义字幕片段结束和最终结果修订的判定规则。
 */
@Component
public class SentenceBoundaryDetector {

    private static final long SILENCE_BOUNDARY_MS = 700;
    private static final long FINAL_REVISION_GRACE_MS = 1_000;

    public boolean shouldFinishBySilence(long silenceDurationMs) {
        return silenceDurationMs >= SILENCE_BOUNDARY_MS;
    }

    public boolean shouldFinishBySilence(long silenceDurationMs, long boundaryMs) {
        return silenceDurationMs >= boundaryMs;
    }

    public boolean shouldFinishByModelFinal(boolean modelFinal) {
        return modelFinal;
    }

    public boolean shouldFinishByDuration(SubtitleSegment segment, long currentTimeMs, long maxDurationMs) {
        return segment != null
            && segment.getStartedAtMs() != null
            && currentTimeMs - segment.getStartedAtMs() >= maxDurationMs;
    }

    public boolean canReviseFinalSegment(SubtitleSegment segment, String source, String translation) {
        return canReviseFinalSegment(segment, source, translation, System.currentTimeMillis(), FINAL_REVISION_GRACE_MS);
    }

    public boolean canReviseFinalSegment(
        SubtitleSegment segment,
        String source,
        String translation,
        long currentTimeMs,
        long revisionGraceMs
    ) {
        return segment != null
            && Boolean.TRUE.equals(segment.getIsFinal())
            && isWithinRevisionGraceWindow(segment, currentTimeMs, revisionGraceMs)
            && hasTextChanged(segment, source, translation);
    }

    private boolean isWithinRevisionGraceWindow(SubtitleSegment segment, long currentTimeMs, long revisionGraceMs) {
        if (segment.getFinalizedAtMs() == null) {
            return true;
        }

        return currentTimeMs - segment.getFinalizedAtMs() <= revisionGraceMs;
    }

    private boolean hasTextChanged(SubtitleSegment segment, String source, String translation) {
        return !normalize(segment.getSource()).equals(normalize(source))
            || !normalize(segment.getTranslation()).equals(normalize(translation));
    }

    private String normalize(String text) {
        return StringUtils.hasText(text) ? text.trim() : "";
    }
}
