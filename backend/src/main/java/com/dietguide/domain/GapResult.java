package com.dietguide.domain;

import java.util.List;
import java.util.Map;

public record GapResult(
        Map<String, Double> dailyTargets,
        Map<String, Double> consumed,
        Map<String, Double> remaining,
        List<String> unloggedMeals,
        List<MealAllocation> allocations
) {
}
