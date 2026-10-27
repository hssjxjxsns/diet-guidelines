package com.dietguide.support;

import com.dietguide.domain.MealEntry;
import com.dietguide.domain.MealGroup;
import com.dietguide.domain.SubmitMealResult;
import com.dietguide.service.MealGroupStore;
import com.dietguide.shared.AppException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MemoryMealGroupStore implements MealGroupStore {

    private final List<MealGroup> groups = new ArrayList<>();
    private final Map<String, List<MealEntry>> entries = new LinkedHashMap<>();
    private final Map<String, StoredIdempotency> idempotency = new LinkedHashMap<>();
    private final Map<LocalDate, Map<String, Double>> dailyStats = new LinkedHashMap<>();

    @Override
    public List<MealGroup> listGroups(String userId, LocalDate mealDate) {
        return groups.stream()
                .filter(group -> group.userId().equals(userId) && group.mealDate().equals(mealDate))
                .toList();
    }

    @Override
    public MealGroup findGroup(String userId, String groupId) {
        return groups.stream()
                .filter(group -> group.id().equals(groupId) && group.userId().equals(userId))
                .findFirst()
                .orElse(null);
    }

    @Override
    public MealGroup createGroup(MealGroup.Draft draft) {
        MealGroup group = new MealGroup(
                UUID.randomUUID().toString(),
                draft.userId(),
                draft.mealDate(),
                draft.mealType(),
                draft.instanceSeq(),
                draft.status(),
                "2026-10-27T00:00:00Z"
        );
        groups.add(group);
        return group;
    }

    @Override
    public List<MealEntry> listEntries(String groupId) {
        return new ArrayList<>(entries.getOrDefault(groupId, List.of()));
    }

    @Override
    public MealEntry insertEntry(MealEntryDraft draft) {
        MealEntry entry = new MealEntry(
                UUID.randomUUID().toString(),
                draft.groupId(),
                draft.userId(),
                draft.mode(),
                draft.revision(),
                draft.baseRevision(),
                draft.totals(),
                draft.valueType(),
                draft.items(),
                "2026-10-27T00:00:00Z"
        );
        entries.computeIfAbsent(draft.groupId(), key -> new ArrayList<>()).add(entry);
        return entry;
    }

    @Override
    public SubmitMealResult findIdempotent(String userId, String key, String requestHash) {
        StoredIdempotency stored = idempotency.get(userId + ":" + key);
        if (stored == null) {
            return null;
        }
        if (!stored.requestHash.equals(requestHash)) {
            throw AppException.idempotencyConflict();
        }
        return stored.result;
    }

    @Override
    public void saveIdempotent(String userId, String key, String requestHash, SubmitMealResult result) {
        idempotency.put(userId + ":" + key, new StoredIdempotency(requestHash, result));
    }

    @Override
    public void upsertDailyStats(String userId, LocalDate mealDate, Map<String, Double> totals, String valueType) {
        dailyStats.put(mealDate, totals);
    }

    @Override
    public void markPeriodReportsStale(String userId, LocalDate mealDate) {
        // no-op for tests
    }

    public Map<String, Double> dailyTotals(LocalDate mealDate) {
        return dailyStats.get(mealDate);
    }

    private record StoredIdempotency(String requestHash, SubmitMealResult result) {
    }
}
