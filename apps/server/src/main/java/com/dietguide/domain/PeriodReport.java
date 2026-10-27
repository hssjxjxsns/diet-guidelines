package com.dietguide.domain;

import java.util.Map;

public record PeriodReport(
        String userId,
        String periodType,
        String periodStart,
        String periodEnd,
        Stats stats,
        String aiSummary,
        String inputHash,
        String status,
        String generatedAt
) {

    public record Stats(int days, Map<String, Double> total, Map<String, Double> average) {
    }
}
