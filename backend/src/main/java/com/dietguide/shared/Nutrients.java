package com.dietguide.shared;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 营养数值容器：key 固定为 8 个 camelCase 指标，缺失表示“未知”。 */
public final class Nutrients {

    public static final List<String> KEYS = List.of(
            "energyKcal",
            "proteinG",
            "fatG",
            "carbG",
            "fiberG",
            "sodiumMg",
            "calciumMg",
            "ironMg"
    );

    private Nutrients() {
    }

    public static Map<String, Double> empty() {
        return new LinkedHashMap<>();
    }

    public static Map<String, Double> add(Map<String, Double> target, Map<String, Double> addition) {
        Map<String, Double> result = new LinkedHashMap<>(target);
        for (String key : KEYS) {
            Double value = addition.get(key);
            if (value == null) {
                continue;
            }
            result.put(key, result.getOrDefault(key, 0.0) + value);
        }
        return result;
    }

    public static Map<String, Double> scale(Map<String, Double> values, double factor) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (String key : KEYS) {
            Double value = values.get(key);
            if (value == null) {
                continue;
            }
            result.put(key, value * factor);
        }
        return result;
    }

    public static Map<String, Double> round(Map<String, Double> values) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (String key : KEYS) {
            Double value = values.get(key);
            if (value == null) {
                continue;
            }
            result.put(key, roundKey(key, value));
        }
        return result;
    }

    public static double value(Map<String, Double> values, String key) {
        return values.getOrDefault(key, 0.0);
    }

    public static Map<String, Double> copy(Map<String, Double> values) {
        return values == null ? empty() : new LinkedHashMap<>(values);
    }

    private static double roundKey(String key, double value) {
        if ("energyKcal".equals(key) || "sodiumMg".equals(key)) {
            return Math.round(value);
        }
        return Math.round(value * 10) / 10.0;
    }
}
