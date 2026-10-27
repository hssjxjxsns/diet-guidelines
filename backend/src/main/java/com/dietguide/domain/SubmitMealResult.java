package com.dietguide.domain;

import java.util.List;
import java.util.Map;

public record SubmitMealResult(
        MealGroup group,
        MealEntry entry,
        List<MealEntry> effectiveEntries,
        Map<String, Double> dailyTotals
) {
}
