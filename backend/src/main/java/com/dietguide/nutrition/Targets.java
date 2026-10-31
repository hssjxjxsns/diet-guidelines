package com.dietguide.nutrition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Targets {

    public static final double NUTRIENT_TOLERANCE = 0.2;

    /** 允许的餐次集合：second_main 为第二顿正餐，night_snack 为夜宵（可多实例的是 snack/night_snack）。 */
    public static final List<String> ALL_MEALS = List.of(
            "breakfast", "lunch", "second_main", "dinner", "snack", "night_snack");

    public static final Map<String, Double> MEAL_WEIGHTS;

    public static final Map<String, Double> DEFAULT_DAILY_TARGETS;

    static {
        Map<String, Double> weights = new LinkedHashMap<>();
        weights.put("breakfast", 0.25);
        weights.put("lunch", 0.35);
        weights.put("second_main", 0.35);
        weights.put("dinner", 0.3);
        weights.put("snack", 0.1);
        weights.put("night_snack", 0.1);
        MEAL_WEIGHTS = Map.copyOf(weights);

        Map<String, Double> targets = new LinkedHashMap<>();
        targets.put("energyKcal", 2000.0);
        targets.put("proteinG", 75.0);
        targets.put("fatG", 60.0);
        targets.put("carbG", 275.0);
        targets.put("fiberG", 25.0);
        targets.put("sodiumMg", 2000.0);
        targets.put("calciumMg", 800.0);
        targets.put("ironMg", 15.0);
        DEFAULT_DAILY_TARGETS = java.util.Collections.unmodifiableMap(targets);
    }

    private Targets() {
    }

    public static double mealWeight(String mealType) {
        return MEAL_WEIGHTS.getOrDefault(mealType, 0.0);
    }

    /** 本餐目标 = 全天目标 × 餐次权重；本餐生成以此为基准，全天缺口仅做提示。 */
    public static Map<String, Double> mealTarget(String mealType, Map<String, Double> dailyTargets) {
        double weight = mealWeight(mealType);
        Map<String, Double> result = new LinkedHashMap<>();
        Map<String, Double> source = dailyTargets == null ? DEFAULT_DAILY_TARGETS : dailyTargets;
        source.forEach((nutrient, value) -> result.put(nutrient, value * weight));
        return result;
    }
}
