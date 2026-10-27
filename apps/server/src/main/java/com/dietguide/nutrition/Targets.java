package com.dietguide.nutrition;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Targets {

    public static final double NUTRIENT_TOLERANCE = 0.2;

    public static final List<String> ALL_MEALS = List.of("breakfast", "lunch", "dinner", "snack");

    public static final Map<String, Double> MEAL_WEIGHTS;

    public static final Map<String, Double> DEFAULT_DAILY_TARGETS;

    static {
        Map<String, Double> weights = new LinkedHashMap<>();
        weights.put("breakfast", 0.25);
        weights.put("lunch", 0.35);
        weights.put("dinner", 0.3);
        weights.put("snack", 0.1);
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
}
