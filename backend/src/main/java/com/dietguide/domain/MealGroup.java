package com.dietguide.domain;

import java.time.LocalDate;

/**
 * 餐次分组。status ∈ draft | logged | abandoned：
 * draft 为可编辑草稿（带 expires_at 过期与 draft_revision 乐观锁），commit 后转 logged。
 */
public record MealGroup(
        String id,
        String userId,
        LocalDate mealDate,
        String mealType,
        int instanceSeq,
        String status,
        int draftRevision,
        String expiresAt,
        String committedAt,
        String createdAt
) {

    public MealGroup(
            String id,
            String userId,
            LocalDate mealDate,
            String mealType,
            int instanceSeq,
            String status,
            String createdAt
    ) {
        this(id, userId, mealDate, mealType, instanceSeq, status, 0, null, null, createdAt);
    }

    public record Draft(String userId, LocalDate mealDate, String mealType, int instanceSeq, String status) {
    }
}
