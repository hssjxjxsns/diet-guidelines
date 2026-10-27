package com.dietguide.domain;

import java.util.List;

public record FoodGroupStatus(
        String group,
        double amountG,
        List<Double> recommendedRangeG,
        String status
) {
}
