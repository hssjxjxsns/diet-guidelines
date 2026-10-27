package com.dietguide.domain;

import java.util.List;
import java.util.Map;

public record UserSettings(
        Map<String, Double> dailyTargets,
        List<String> preferences,
        List<String> allergens,
        List<String> avoid
) {
}
