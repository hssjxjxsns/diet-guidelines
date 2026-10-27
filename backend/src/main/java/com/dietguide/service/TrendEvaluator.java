package com.dietguide.service;

import com.dietguide.domain.InferredTrend;

import java.util.List;

public final class TrendEvaluator {

    public record Evidence(
            String metric,
            String conclusion,
            String windowStart,
            String windowEnd,
            int sampleMeals,
            List<Double> deviations,
            int consecutivePeriods
    ) {
    }

    private TrendEvaluator() {
    }

    /**
     * 只有样本量达标且方向一致的窗口才产生候选结论；
     * 连续两个周期同向成立才升级为 verified。
     */
    public static InferredTrend deriveInferredTrend(Evidence input) {
        if (input.sampleMeals() < 10 || input.deviations() == null || input.deviations().isEmpty()) {
            return null;
        }

        long negative = input.deviations().stream().filter(value -> value < 0).count();
        long positive = input.deviations().stream().filter(value -> value > 0).count();
        double consistentRatio = (double) Math.max(negative, positive) / input.deviations().size();
        if (consistentRatio < 0.7) {
            return null;
        }

        double averageDeviation = input.deviations().stream().mapToDouble(Double::doubleValue).average().orElse(0);
        String status = input.consecutivePeriods() >= 2 ? "verified" : "candidate";

        return new InferredTrend(
                input.metric(),
                input.conclusion(),
                status,
                new InferredTrend.Evidence(
                        input.windowStart(),
                        input.windowEnd(),
                        input.sampleMeals(),
                        Math.round(averageDeviation * 10) / 10.0
                )
        );
    }
}
