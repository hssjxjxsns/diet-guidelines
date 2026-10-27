package com.dietguide.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MealInputItem(
        String foodId,
        String recipeId,
        String rawName,
        double amountG,
        String valueType,
        DishAnalysis analysis
) {
}
