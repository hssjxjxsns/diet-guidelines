package com.dietguide.service;

import com.dietguide.domain.MealEntry;
import com.dietguide.domain.MealGroup;
import com.dietguide.domain.MealDraftItem;
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

    /** 通用幂等存取（scope 区分作用域：meal_entry / plan_accept 等），响应按 type 反序列化。 */
    <T> T findIdempotentPayload(String userId, String key, String scope, String requestHash, Class<T> type);

    void saveIdempotentPayload(String userId, String key, String scope, String requestHash, Object response);

    /** 物化日统计：metrics + structure + aggregator_version，唯一写路径（DailyStatsAggregator 产出）。 */
    void upsertDailyStats(String userId, LocalDate mealDate, DailyStatsAggregator.DailyStats stats);

    void markPeriodReportsStale(String userId, LocalDate mealDate);

    List<MealDraftItem> listDraftItems(String groupId);

    MealDraftItem insertDraftItem(MealDraftItem item);

    MealDraftItem updateDraftItem(MealDraftItem item);

    void deleteDraftItem(String userId, String itemId);

    /** 乐观锁递增草稿版本，返回新版本；version 不匹配时抛 MEAL_REVISION_CONFLICT。 */
    int bumpDraftRevision(String userId, String groupId, Integer expectedDraftRevision);

    void markGroupLogged(String userId, String groupId);

    void markGroupAbandoned(String groupId);

    /** 重开已记录组为草稿（修正场景）：保留版本链，commit 时在该组产生新 revision。 */
    void reopenDraft(String userId, String groupId);

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
