package com.dietguide.support;

import com.dietguide.domain.MealDraftItem;
import com.dietguide.domain.MealEntry;
import com.dietguide.domain.MealGroup;
import com.dietguide.domain.SubmitMealResult;
import com.dietguide.service.DailyStatsAggregator;
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
    private final Map<LocalDate, DailyStatsAggregator.DailyStats> dailyStats = new LinkedHashMap<>();
    private final Map<String, List<MealDraftItem>> draftItems = new LinkedHashMap<>();
    private final Map<String, Integer> draftRevisions = new LinkedHashMap<>();

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
                0,
                "draft".equals(draft.status()) ? "2099-01-01T00:00:00Z" : null,
                null,
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
        return findIdempotentPayload(userId, key, "meal_entry", requestHash, SubmitMealResult.class);
    }

    @Override
    public void saveIdempotent(String userId, String key, String requestHash, SubmitMealResult result) {
        saveIdempotentPayload(userId, key, "meal_entry", requestHash, result);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T findIdempotentPayload(
            String userId,
            String key,
            String scope,
            String requestHash,
            Class<T> type
    ) {
        StoredIdempotency stored = idempotency.get(userId + ":" + key);
        if (stored == null) {
            return null;
        }
        if (!stored.requestHash.equals(requestHash)) {
            throw AppException.idempotencyConflict();
        }
        return (T) stored.response;
    }

    @Override
    public void saveIdempotentPayload(
            String userId,
            String key,
            String scope,
            String requestHash,
            Object response
    ) {
        idempotency.put(userId + ":" + key, new StoredIdempotency(requestHash, response));
    }

    @Override
    public void upsertDailyStats(String userId, LocalDate mealDate, DailyStatsAggregator.DailyStats stats) {
        dailyStats.put(mealDate, stats);
    }

    @Override
    public void markPeriodReportsStale(String userId, LocalDate mealDate) {
        // no-op for tests
    }

    @Override
    public List<MealDraftItem> listDraftItems(String groupId) {
        return new ArrayList<>(draftItems.getOrDefault(groupId, List.of()));
    }

    @Override
    public MealDraftItem insertDraftItem(MealDraftItem item) {
        MealDraftItem stored = new MealDraftItem(
                item.id() == null || item.id().isBlank() ? UUID.randomUUID().toString() : item.id(),
                item.groupId(),
                item.userId(),
                item.foodId(),
                item.recipeId(),
                item.rawName(),
                item.amountG(),
                item.valueType() == null ? "measured" : item.valueType(),
                item.analysis(),
                item.aiSource(),
                item.confirmationStatus() == null ? "confirmed" : item.confirmationStatus(),
                item.confirmedBy(),
                item.confirmedAt(),
                item.createdAt() == null ? "2026-10-27T00:00:00Z" : item.createdAt()
        );
        draftItems.computeIfAbsent(stored.groupId(), key -> new ArrayList<>()).add(stored);
        return stored;
    }

    @Override
    public MealDraftItem updateDraftItem(MealDraftItem item) {
        List<MealDraftItem> items = draftItems.getOrDefault(item.groupId(), new ArrayList<>());
        for (int index = 0; index < items.size(); index += 1) {
            if (items.get(index).id().equals(item.id())) {
                items.set(index, item);
                return item;
            }
        }
        return null;
    }

    @Override
    public void deleteDraftItem(String userId, String itemId) {
        draftItems.values().forEach(items ->
                items.removeIf(item -> item.id().equals(itemId) && item.userId().equals(userId)));
    }

    @Override
    public int bumpDraftRevision(String userId, String groupId, Integer expectedDraftRevision) {
        MealGroup group = findGroup(userId, groupId);
        if (group == null) {
            throw AppException.notFound("meal group");
        }
        int current = draftRevisions.getOrDefault(groupId, group.draftRevision());
        if (expectedDraftRevision != null && expectedDraftRevision != current) {
            throw AppException.mealRevisionConflict(current);
        }
        int next = current + 1;
        draftRevisions.put(groupId, next);
        return next;
    }

    @Override
    public void markGroupLogged(String userId, String groupId) {
        for (int index = 0; index < groups.size(); index += 1) {
            MealGroup group = groups.get(index);
            if (group.id().equals(groupId) && group.userId().equals(userId)) {
                groups.set(index, new MealGroup(
                        group.id(), group.userId(), group.mealDate(), group.mealType(),
                        group.instanceSeq(), "logged", group.draftRevision(), null,
                        "2026-10-27T00:00:00Z", group.createdAt()));
            }
        }
    }

    @Override
    public void markGroupAbandoned(String groupId) {
        for (int index = 0; index < groups.size(); index += 1) {
            MealGroup group = groups.get(index);
            if (group.id().equals(groupId)) {
                groups.set(index, new MealGroup(
                        group.id(), group.userId(), group.mealDate(), group.mealType(),
                        group.instanceSeq(), "abandoned", group.draftRevision(), group.expiresAt(),
                        group.committedAt(), group.createdAt()));
            }
        }
    }

    @Override
    public void reopenDraft(String userId, String groupId) {
        for (int index = 0; index < groups.size(); index += 1) {
            MealGroup group = groups.get(index);
            if (group.id().equals(groupId) && group.userId().equals(userId)) {
                groups.set(index, new MealGroup(
                        group.id(), group.userId(), group.mealDate(), group.mealType(),
                        group.instanceSeq(), "draft", group.draftRevision(),
                        "2099-01-01T00:00:00Z", null, group.createdAt()));
            }
        }
    }

    public Map<String, Double> dailyTotals(LocalDate mealDate) {
        DailyStatsAggregator.DailyStats stats = dailyStats.get(mealDate);
        return stats == null ? null : stats.totals();
    }

    public DailyStatsAggregator.DailyStats dailyStats(LocalDate mealDate) {
        return dailyStats.get(mealDate);
    }

    private record StoredIdempotency(String requestHash, Object response) {
    }
}
