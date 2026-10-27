package com.dietguide.domain;

import java.time.LocalDate;
import java.util.List;

public record SubmitMealInput(
        String userId,
        LocalDate mealDate,
        String mealType,
        Integer instanceSeq,
        String mode,
        Integer baseRevision,
        String idempotencyKey,
        List<MealInputItem> items,
        String nutritionDataVersion
) {
}
