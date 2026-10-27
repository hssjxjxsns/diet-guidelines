package com.dietguide.domain;

import java.util.List;
import java.util.Map;

public record MealAllocation(
        String mealType,
        double weight,
        double expectedKcal,
        List<Double> rangeKcal
) {
}
