package com.dietguide.service;

import com.dietguide.domain.MealEntry;
import com.dietguide.domain.MealGroup;
import com.dietguide.domain.SubmitMealResult;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface MealGroupStore {

    List<MealGroup> listGroups(String userId, LocalDate mealDate);

    MealGroup findGroup(String userId, String groupId);

    MealGroup createGroup(MealGroup.Draft draft);

    List<MealEntry> listEntries(String groupId);

    MealEntry insertEntry(MealEntryDraft draft);

    SubmitMealResult findIdempotent(String userId, String key, String requestHash);

    void saveIdempotent(String userId, String key, String requestHash, SubmitMealResult result);

    void upsertDailyStats(String userId, LocalDate mealDate, Map<String, Double> totals, String valueType);

    void markPeriodReportsStale(String userId, LocalDate mealDate);

    record MealEntryDraft(
            String groupId,
            String userId,
            String mode,
            int revision,
            Integer baseRevision,
            Map<String, Double> totals,
            String valueType,
            List<MealEntry.Item> items
    ) {
    }
}
