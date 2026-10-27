package com.dietguide.domain;

import java.util.List;
import java.util.Map;

public record NutritionResult(
        Quantitative quantitative,
        StructureResult structure,
        String valueType,
        List<String> warnings
) {

    public record Quantitative(Map<String, Double> values, List<PerItemNutrition> perItem) {
    }
}
