package com.dietguide.support;

import com.dietguide.domain.Food;
import com.dietguide.domain.Recipe;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Fixtures {

    private Fixtures() {
    }

    public static Food rice() {
        return food("f_rice_001", "米饭", "grains", 116, 2.6, 25.9, 1.0, List.of("vegetarian"), List.of());
    }

    public static Food broccoli() {
        return food("f_broccoli_001", "西兰花", "vegetables", 34, 2.8, 6.6, 1.0, List.of("vegetarian"), List.of());
    }

    public static Food chicken() {
        return food("f_chicken_001", "鸡胸肉", "protein", 133, 24.6, 0, 1.0, List.of("meat"), List.of());
    }

    public static Food egg() {
        return food("f_egg_001", "鸡蛋", "protein", 144, 13.3, 2.8, 0.88, List.of("vegetarian"), List.of("蛋"));
    }

    public static Food peanut() {
        return food("f_peanut_001", "花生", "soy_nuts", 574, 24.8, 21.7, 1.0, List.of("vegetarian"), List.of("花生"));
    }

    public static Recipe tomatoEgg() {
        return new Recipe(
                "r_tomato_egg_001",
                "番茄炒蛋",
                1,
                220,
                10,
                "炒",
                "测试",
                "active",
                List.of(new Recipe.RecipeIngredient("f_egg_001", 120)),
                List.of("vegetarian"),
                List.of("蛋")
        );
    }

    public static MemoryFoodRepository repository() {
        return new MemoryFoodRepository()
                .add(rice())
                .add(broccoli())
                .add(chicken())
                .add(egg())
                .add(peanut())
                .add(tomatoEgg());
    }

    private static Food food(
            String id,
            String name,
            String category,
            double energy,
            double protein,
            double carb,
            double edibleRatio,
            List<String> tags,
            List<String> allergens
    ) {
        Map<String, Double> nutrients = new LinkedHashMap<>();
        nutrients.put("energyKcal", energy);
        nutrients.put("proteinG", protein);
        nutrients.put("fatG", 1.0);
        nutrients.put("carbG", carb);
        nutrients.put("fiberG", 1.0);
        nutrients.put("sodiumMg", 10.0);
        nutrients.put("calciumMg", 20.0);
        nutrients.put("ironMg", 1.0);

        return new Food(id, name, List.of(), category, nutrients, edibleRatio, "测试", "test-1",
                "active", tags, allergens);
    }
}
