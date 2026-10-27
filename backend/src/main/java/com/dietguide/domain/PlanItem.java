package com.dietguide.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanItem(
        String foodId,
        String recipeId,
        String name,
        double amountG,
        String foodGroup,
        List<String> tags,
        List<String> allergens,
        String reason,
        List<String> citations
) {
}
