package com.dietguide.service;

import com.dietguide.domain.PlanItem;
import com.dietguide.domain.ValidationResult;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface PlanSessionStore {

    String save(PlanSessionDraft draft);

    Map<String, Object> find(String userId, String sessionId);

    void complete(String userId, String sessionId);

    record PlanSessionDraft(
            String userId,
            LocalDate mealDate,
            String mealType,
            String status,
            Map<String, Object> contextSnapshot,
            ValidationResult validationResult,
            List<PlanItem> items
    ) {
    }
}
