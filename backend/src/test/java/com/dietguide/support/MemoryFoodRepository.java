package com.dietguide.support;

import com.dietguide.domain.Food;
import com.dietguide.domain.Recipe;
import com.dietguide.nutrition.FoodRepository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MemoryFoodRepository implements FoodRepository {

    private final Map<String, Food> foods = new LinkedHashMap<>();
    private final Map<String, Recipe> recipes = new LinkedHashMap<>();

    public MemoryFoodRepository add(Food food) {
        foods.put(food.id(), food);
        return this;
    }

    public MemoryFoodRepository add(Recipe recipe) {
        recipes.put(recipe.id(), recipe);
        return this;
    }

    @Override
    public Food findById(String id) {
        return foods.get(id);
    }

    @Override
    public Food findByName(String name) {
        return foods.values().stream()
                .filter(food -> food.name().equals(name) || food.aliases().contains(name))
                .findFirst()
                .orElse(null);
    }

    @Override
    public List<Food> search(String query, int limit) {
        List<Food> result = new ArrayList<>();
        for (Food food : foods.values()) {
            if (food.name().contains(query)) {
                result.add(food);
            }
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    @Override
    public List<Food> listActive(int limit) {
        return foods.values().stream()
                .filter(food -> "active".equals(food.status()))
                .limit(limit)
                .toList();
    }

    @Override
    public Recipe getRecipe(String id) {
        return recipes.get(id);
    }
}
