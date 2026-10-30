package com.dietguide.service;

import com.dietguide.domain.DishAnalysis;

public interface RecipeCandidateStore {

    /** 保存 AI 配方候选（status=pending_review），返回候选 id（用于草稿项 aiSource 溯源）。 */
    String save(String userId, DishAnalysis analysis);
}
