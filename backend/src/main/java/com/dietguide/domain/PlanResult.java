package com.dietguide.domain;

import java.util.List;

public record PlanResult(
        String sessionId,
        List<PlanItem> items,
        String explanation,
        List<String> citations,
        List<GuidelineRef> guidelines,
        ValidationResult validation,
        GapResult gap,
        boolean fallback
) {
}
