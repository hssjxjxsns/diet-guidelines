package com.dietguide.domain;

import java.util.List;

public record PlanResult(
        String sessionId,
        List<PlanItem> items,
        String explanation,
        List<String> citations,
        ValidationResult validation,
        GapResult gap,
        boolean fallback
) {
}
