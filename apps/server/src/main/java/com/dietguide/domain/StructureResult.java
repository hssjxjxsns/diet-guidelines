package com.dietguide.domain;

import java.util.List;

public record StructureResult(
        List<FoodGroupStatus> foodGroups,
        int distinctFoodCount,
        String diversityNote
) {
}
