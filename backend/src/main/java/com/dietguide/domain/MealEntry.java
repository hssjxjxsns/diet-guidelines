package com.dietguide.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MealEntry(
        String id,
        String groupId,
        String userId,
        String mode,
        int revision,
        Integer baseRevision,
        Map<String, Double> totals,
        String valueType,
        List<Item> items,
        String createdAt
) implements Versioned {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(
            String foodId,
            String recipeId,
            String rawName,
            double amountG,
            String valueType,
            Map<String, Double> nutrients,
            DishAnalysis analysis,
            String aiSource,
            String confirmationStatus,
            String confirmedBy,
            String confirmedAt
    ) {

        public Item(
                String foodId,
                String recipeId,
                String rawName,
                double amountG,
                String valueType,
                Map<String, Double> nutrients,
                DishAnalysis analysis
        ) {
            this(foodId, recipeId, rawName, amountG, valueType, nutrients, analysis, null, "confirmed", null, null);
        }
    }
}
