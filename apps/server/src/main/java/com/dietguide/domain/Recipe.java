package com.dietguide.domain;

import java.util.List;

public record Recipe(
        String id,
        String name,
        double servings,
        double yieldG,
        double oilG,
        String cookingMethod,
        String source,
        String status,
        List<RecipeIngredient> ingredients,
        List<String> tags,
        List<String> allergens
) {

    public record RecipeIngredient(String foodId, double amountG) {
    }
}
