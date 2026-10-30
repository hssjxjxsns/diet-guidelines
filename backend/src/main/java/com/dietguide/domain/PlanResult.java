package com.dietguide.domain;

import java.util.List;

/** source ∈ AI | RULE_FALLBACK：区分 AI 方案与规则兜底方案，前端需据此提示。 */
public record PlanResult(
        String sessionId,
        List<PlanItem> items,
        String explanation,
        List<String> citations,
        List<GuidelineRef> guidelines,
        ValidationResult validation,
        GapResult gap,
        boolean fallback,
        String source
) {
}
