package com.dietguide.domain;

import java.util.List;

public record ValidationResult(
        boolean passed,
        List<Violation> failedRules,
        List<Warning> warnings,
        ComputedRanges computedRanges,
        String ruleVersion
) {

    public record Violation(
            String ruleId,
            String severity,
            String message,
            List<String> offendingItems,
            Object expected,
            Object actual
    ) {
    }

    public record Warning(String ruleId, String message) {
    }

    public record ComputedRanges(
            List<Double> energyKcal,
            List<Double> proteinG,
            List<Double> sodiumMg
    ) {
    }
}
