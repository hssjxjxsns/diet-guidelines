package com.dietguide.domain;

import java.util.List;
import java.util.Map;

public record PlanningContext(String text, int estimatedTokens, Map<String, Object> snapshot) {

    public record UserContext(
            UserSettings settings,
            Map<String, Double> dailyTargets,
            Map<String, Double> consumed,
            List<String> unloggedMeals,
            StructureResult structure,
            List<RecentTrend> recentTrends,
            List<InferredTrend> inferredTrends
    ) {
    }
}
