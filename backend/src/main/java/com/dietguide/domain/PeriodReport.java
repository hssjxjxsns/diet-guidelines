package com.dietguide.domain;

import java.util.List;
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

    /** 报表主指标：食物组份数达标（膳食指南结构），营养 total/average 为辅。 */
    public record FoodGroupAdherence(
            String group,
            double averageG,
            List<Double> recommendedRangeG,
            double adherenceRate,
            String status
    ) {
    }

    /**
     * days=记录天数；total/average=营养合计/日均（辅指标）；
     * foodGroups=食物组达标（主指标）；guideline=指南符合度结论（多样性/油盐/钠/structure 非空率）。
     */
    public record Stats(
            int days,
            Map<String, Double> total,
            Map<String, Double> average,
            List<FoodGroupAdherence> foodGroups,
            Map<String, Object> guideline
    ) {
    }
}
