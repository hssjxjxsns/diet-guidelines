package com.dietguide.domain;

import java.util.Map;

public record PerItemNutrition(
        String foodId,
        String recipeId,
        String rawName,
        double amountG,
        String valueType,
        Map<String, Double> values
) {
}
