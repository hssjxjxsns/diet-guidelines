package com.dietguide.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** AI 菜品识别的结构化结果。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DishAnalysis(
        String dishName,
        String matchedFoodId,
        RecipeProposal recipeProposal,
        DirectEstimate directEstimate,
        Double confidence,
        List<String> basis,
        Boolean needsUserConfirm
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RecipeProposal(
            List<ProposalIngredient> ingredients,
            Double oilG,
            String cookingMethod,
            Double servings,
            Double yieldG
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProposalIngredient(String name, String foodId, Double amountG) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DirectEstimate(List<Double> energyKcal, List<Double> proteinG, Double confidence) {
    }
}
