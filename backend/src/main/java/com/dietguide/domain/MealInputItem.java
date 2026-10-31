package com.dietguide.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 记录输入项。confirmed 为空时按非 AI 项处理；aiSource 非空表示来自 AI 候选，
 * 提交前必须经用户确认（R09_AI_UNCONFIRMED 硬规则）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MealInputItem(
        String foodId,
        String recipeId,
        String rawName,
        double amountG,
        String valueType,
        DishAnalysis analysis,
        Boolean confirmed,
        String aiSource
) {

    public MealInputItem(
            String foodId,
            String recipeId,
            String rawName,
            double amountG,
            String valueType,
            DishAnalysis analysis
    ) {
        this(foodId, recipeId, rawName, amountG, valueType, analysis, null, null);
    }
}
