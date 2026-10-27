package com.dietguide.nutrition;

import com.dietguide.domain.FoodGroupStatus;
import com.dietguide.domain.StructureResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class StructureEvaluator {

    /** 每日推荐摄入量（克），奶类按毫升≈克处理。 */
    public static final Map<String, List<Double>> DAILY_STRUCTURE_RANGES;

    static {
        Map<String, List<Double>> ranges = new LinkedHashMap<>();
        ranges.put("grains", List.of(200.0, 300.0));
        ranges.put("vegetables", List.of(300.0, 500.0));
        ranges.put("fruits", List.of(200.0, 350.0));
        ranges.put("protein", List.of(120.0, 200.0));
        ranges.put("dairy", List.of(300.0, 500.0));
        ranges.put("soy_nuts", List.of(25.0, 35.0));
        ranges.put("oils", List.of(25.0, 30.0));
        ranges.put("other", List.of(0.0, 0.0));
        DAILY_STRUCTURE_RANGES = java.util.Collections.unmodifiableMap(ranges);
    }

    private StructureEvaluator() {
    }

    public static StructureResult evaluateStructure(
            Map<String, Double> amounts,
            String mealType,
            int distinctFoodCount
    ) {
        double share = Targets.mealWeight(mealType);
        List<FoodGroupStatus> groups = new ArrayList<>();

        for (Map.Entry<String, List<Double>> entry : DAILY_STRUCTURE_RANGES.entrySet()) {
            String group = entry.getKey();
            if ("other".equals(group)) {
                continue;
            }
            double min = round(entry.getValue().get(0) * share);
            double max = round(entry.getValue().get(1) * share);
            double amount = round(amounts.getOrDefault(group, 0.0));
            String status = amount < min ? "insufficient" : amount > max ? "excessive" : "adequate";
            groups.add(new FoodGroupStatus(group, amount, List.of(min, max), status));
        }

        String diversityNote = distinctFoodCount >= 4
                ? "食物种类较丰富"
                : distinctFoodCount >= 2
                ? "食物种类偏少，建议增加蔬菜或全谷物"
                : "食物种类单一，建议增加不同食物组";

        return new StructureResult(groups, distinctFoodCount, diversityNote);
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
