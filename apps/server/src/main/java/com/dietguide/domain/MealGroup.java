package com.dietguide.domain;

import java.time.LocalDate;

public record MealGroup(
        String id,
        String userId,
        LocalDate mealDate,
        String mealType,
        int instanceSeq,
        String status,
        String createdAt
) {

    public record Draft(String userId, LocalDate mealDate, String mealType, int instanceSeq, String status) {
    }
}
