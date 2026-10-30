package com.dietguide.adapter;

import com.dietguide.domain.DishAnalysis;
import com.dietguide.domain.MealDraftItem;
import com.dietguide.domain.MealEntry;
import com.dietguide.domain.MealGroup;
import com.dietguide.domain.SubmitMealResult;
import com.dietguide.service.DailyStatsAggregator;
import com.dietguide.service.MealGroupStore;
import com.dietguide.shared.AppException;
import com.dietguide.shared.Nutrients;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class JdbcMealGroupStore implements MealGroupStore {

    private final JdbcTemplate jdbc;
    private final JsonCodec codec;

    public JdbcMealGroupStore(JdbcTemplate jdbc, JsonCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
    }

    private final RowMapper<MealGroup> groupMapper = (resultSet, rowNum) -> new MealGroup(
            resultSet.getString("id"),
            resultSet.getString("user_id"),
            resultSet.getObject("meal_date", LocalDate.class),
            resultSet.getString("meal_type"),
            resultSet.getInt("instance_seq"),
            resultSet.getString("status"),
            resultSet.getInt("draft_revision"),
            text(resultSet.getObject("expires_at", OffsetDateTime.class)),
            text(resultSet.getObject("committed_at", OffsetDateTime.class)),
            text(resultSet.getObject("created_at", OffsetDateTime.class))
    );

    @Override
    public List<MealGroup> listGroups(String userId, LocalDate mealDate) {
        return jdbc.query(
                """
                select id, user_id, meal_date, meal_type, instance_seq, status,
                       draft_revision, expires_at, committed_at, created_at
                from meal_groups where user_id = ? and meal_date = ?
                order by meal_type, instance_seq
                """,
                groupMapper,
                userId,
                mealDate
        );
    }

    @Override
    public MealGroup findGroup(String userId, String groupId) {
        List<MealGroup> rows = jdbc.query(
                """
                select id, user_id, meal_date, meal_type, instance_seq, status,
                       draft_revision, expires_at, committed_at, created_at
                from meal_groups where id = ? and user_id = ?
                """,
                groupMapper,
                groupId,
                userId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public MealGroup createGroup(MealGroup.Draft draft) {
        String groupId = UUID.randomUUID().toString();
        try {
            jdbc.update(
                    """
                    insert into meal_groups (id, user_id, meal_date, meal_type, instance_seq, status, draft_revision, expires_at)
                    values (?, ?, ?, ?, ?, ?, 0, ?)
                    """,
                    groupId,
                    draft.userId(),
                    draft.mealDate(),
                    draft.mealType(),
                    draft.instanceSeq(),
                    draft.status(),
                    OffsetDateTime.now().plusHours(24)
            );
            return findGroup(draft.userId(), groupId);
        } catch (DuplicateKeyException error) {
            MealGroup existing = listGroups(draft.userId(), draft.mealDate()).stream()
                    .filter(group -> group.mealType().equals(draft.mealType())
                            && group.instanceSeq() == draft.instanceSeq())
                    .findFirst()
                    .orElseThrow(() -> error);
            if (!"draft".equals(existing.status())) {
                throw AppException.validation("该餐次已记录或已关闭，请选择其它餐次或使用第二顿/夜宵");
            }
            return existing;
        }
    }

    @Override
    public List<MealEntry> listEntries(String groupId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select id, group_id, user_id, mode, revision, base_revision,
                       totals as totals_json, value_type, created_at
                from meal_entries where group_id = ? order by revision
                """,
                groupId
        );

        List<MealEntry> entries = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String entryId = (String) row.get("id");
            entries.add(new MealEntry(
                    entryId,
                    (String) row.get("group_id"),
                    (String) row.get("user_id"),
                    (String) row.get("mode"),
                    ((Number) row.get("revision")).intValue(),
                    row.get("base_revision") == null ? null : ((Number) row.get("base_revision")).intValue(),
                    codec.nutrients((String) row.get("totals_json")),
                    (String) row.get("value_type"),
                    listItems(entryId),
                    text(row.get("created_at"))
            ));
        }
        return entries;
    }

    @Override
    @Transactional
    public MealEntry insertEntry(MealEntryDraft draft) {
        if ("replace".equals(draft.mode())) {
            jdbc.update(
                    "update meal_entries set is_current = false where group_id = ? and mode = 'replace' and is_current = true",
                    draft.groupId()
            );
        }

        String entryId = UUID.randomUUID().toString();
        OffsetDateTime createdAt = OffsetDateTime.now();

        jdbc.update(
                """
                insert into meal_entries
                  (id, group_id, user_id, mode, revision, base_revision, totals, value_type, is_current, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, true, ?)
                """,
                entryId,
                draft.groupId(),
                draft.userId(),
                draft.mode(),
                draft.revision(),
                draft.baseRevision(),
                codec.write(draft.totals()),
                draft.valueType(),
                createdAt
        );

        for (MealEntry.Item item : draft.items()) {
            jdbc.update(
                    """
                    insert into meal_entry_items
                      (id, entry_id, food_id, recipe_id, raw_name, amount_g, value_type, nutrients, analysis,
                       ai_source, confirmation_status, confirmed_by, confirmed_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID().toString(),
                    entryId,
                    item.foodId(),
                    item.recipeId(),
                    item.rawName(),
                    item.amountG(),
                    item.valueType(),
                    codec.write(item.nutrients()),
                    item.analysis() == null ? null : codec.write(item.analysis()),
                    item.aiSource(),
                    item.confirmationStatus() == null ? "confirmed" : item.confirmationStatus(),
                    item.confirmedBy(),
                    item.confirmedAt() == null ? null : OffsetDateTime.parse(item.confirmedAt())
            );
        }

        return new MealEntry(
                entryId,
                draft.groupId(),
                draft.userId(),
                draft.mode(),
                draft.revision(),
                draft.baseRevision(),
                draft.totals(),
                draft.valueType(),
                draft.items(),
                createdAt.toString()
        );
    }

    @Override
    public SubmitMealResult findIdempotent(String userId, String key, String requestHash) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select request_hash, response as response_json, status
                from idempotency_keys where user_id = ? and key = ?
                """,
                userId,
                key
        );
        if (rows.isEmpty()) {
            return null;
        }

        Map<String, Object> row = rows.get(0);
        if (!requestHash.equals(row.get("request_hash"))) {
            throw AppException.idempotencyConflict();
        }
        if (!"completed".equals(row.get("status"))) {
            return null;
        }
        return codec.read((String) row.get("response_json"), SubmitMealResult.class);
    }

    @Override
    public void saveIdempotent(String userId, String key, String requestHash, SubmitMealResult result) {
        saveIdempotentPayload(userId, key, "meal_entry", requestHash, result);
    }

    @Override
    public <T> T findIdempotentPayload(
            String userId,
            String key,
            String scope,
            String requestHash,
            Class<T> type
    ) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select request_hash, response as response_json, status
                from idempotency_keys where user_id = ? and key = ?
                """,
                userId,
                key
        );
        if (rows.isEmpty()) {
            return null;
        }

        Map<String, Object> row = rows.get(0);
        if (!requestHash.equals(row.get("request_hash"))) {
            throw AppException.idempotencyConflict();
        }
        if (!"completed".equals(row.get("status"))) {
            return null;
        }
        return codec.read((String) row.get("response_json"), type);
    }

    @Override
    public void saveIdempotentPayload(
            String userId,
            String key,
            String scope,
            String requestHash,
            Object response
    ) {
        jdbc.update(
                """
                merge into idempotency_keys (user_id, key, scope, request_hash, response, status)
                key (user_id, key)
                values (?, ?, ?, ?, ?, 'completed')
                """,
                userId,
                key,
                scope,
                requestHash,
                codec.write(response)
        );
    }

    @Override
    public void upsertDailyStats(String userId, LocalDate mealDate, DailyStatsAggregator.DailyStats stats) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("values", stats.totals());
        jdbc.update(
                """
                merge into daily_stats (user_id, stat_date, metrics, structure, value_type, aggregator_version, computed_at)
                key (user_id, stat_date)
                values (?, ?, ?, ?, ?, ?, current_timestamp)
                """,
                userId,
                mealDate,
                codec.write(metrics),
                codec.write(stats.structure()),
                stats.valueType(),
                stats.aggregatorVersion()
        );
    }

    @Override
    public void markPeriodReportsStale(String userId, LocalDate mealDate) {
        jdbc.update(
                """
                update period_reports set status = 'stale'
                where user_id = ? and period_start <= ? and period_end >= ?
                """,
                userId,
                mealDate,
                mealDate
        );
    }

    private RowMapper<MealDraftItem> draftItemMapper() {
        return (resultSet, rowNum) -> new MealDraftItem(
                resultSet.getString("id"),
                resultSet.getString("group_id"),
                resultSet.getString("user_id"),
                resultSet.getString("food_id"),
                resultSet.getString("recipe_id"),
                resultSet.getString("raw_name"),
                resultSet.getDouble("amount_g"),
                resultSet.getString("value_type"),
                codec.read(resultSet.getString("analysis"), DishAnalysis.class),
                resultSet.getString("ai_source"),
                resultSet.getString("confirmation_status"),
                resultSet.getString("confirmed_by"),
                text(resultSet.getObject("confirmed_at", OffsetDateTime.class)),
                text(resultSet.getObject("created_at", OffsetDateTime.class))
        );
    }

    @Override
    public List<MealDraftItem> listDraftItems(String groupId) {
        return jdbc.query(
                """
                select id, group_id, user_id, food_id, recipe_id, raw_name, amount_g, value_type,
                       analysis, ai_source, confirmation_status, confirmed_by, confirmed_at, created_at
                from meal_draft_items where group_id = ?
                order by created_at, id
                """,
                draftItemMapper(),
                groupId
        );
    }

    @Override
    public MealDraftItem insertDraftItem(MealDraftItem item) {
        String id = item.id() == null || item.id().isBlank() ? UUID.randomUUID().toString() : item.id();
        jdbc.update(
                """
                insert into meal_draft_items
                  (id, group_id, user_id, food_id, recipe_id, raw_name, amount_g, value_type,
                   analysis, ai_source, confirmation_status, confirmed_by, confirmed_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                item.groupId(),
                item.userId(),
                item.foodId(),
                item.recipeId(),
                item.rawName(),
                item.amountG(),
                item.valueType() == null ? "measured" : item.valueType(),
                item.analysis() == null ? null : codec.write(item.analysis()),
                item.aiSource(),
                item.confirmationStatus() == null ? "confirmed" : item.confirmationStatus(),
                item.confirmedBy(),
                item.confirmedAt() == null ? null : OffsetDateTime.parse(item.confirmedAt())
        );
        return findDraftItem(id);
    }

    @Override
    public MealDraftItem updateDraftItem(MealDraftItem item) {
        jdbc.update(
                """
                update meal_draft_items set
                  food_id = ?, recipe_id = ?, raw_name = ?, amount_g = ?, value_type = ?,
                  analysis = ?, ai_source = ?, confirmation_status = ?,
                  confirmed_by = ?, confirmed_at = ?, updated_at = current_timestamp
                where id = ? and user_id = ?
                """,
                item.foodId(),
                item.recipeId(),
                item.rawName(),
                item.amountG(),
                item.valueType(),
                item.analysis() == null ? null : codec.write(item.analysis()),
                item.aiSource(),
                item.confirmationStatus(),
                item.confirmedBy(),
                item.confirmedAt() == null ? null : OffsetDateTime.parse(item.confirmedAt()),
                item.id(),
                item.userId()
        );
        return findDraftItem(item.id());
    }

    @Override
    public void deleteDraftItem(String userId, String itemId) {
        jdbc.update("delete from meal_draft_items where id = ? and user_id = ?", itemId, userId);
    }

    @Override
    public int bumpDraftRevision(String userId, String groupId, Integer expectedDraftRevision) {
        int updated;
        if (expectedDraftRevision == null) {
            Integer current = jdbc.queryForObject(
                    "select draft_revision from meal_groups where id = ? and user_id = ?",
                    Integer.class,
                    groupId,
                    userId
            );
            int next = (current == null ? 0 : current) + 1;
            jdbc.update(
                    "update meal_groups set draft_revision = ? where id = ? and user_id = ?",
                    next,
                    groupId,
                    userId
            );
            return next;
        }
        updated = jdbc.update(
                "update meal_groups set draft_revision = draft_revision + 1 "
                        + "where id = ? and user_id = ? and draft_revision = ?",
                groupId,
                userId,
                expectedDraftRevision
        );
        if (updated == 0) {
            Integer current = jdbc.queryForObject(
                    "select draft_revision from meal_groups where id = ? and user_id = ?",
                    Integer.class,
                    groupId,
                    userId
            );
            throw AppException.mealRevisionConflict(current == null ? 0 : current);
        }
        return expectedDraftRevision + 1;
    }

    @Override
    public void markGroupLogged(String userId, String groupId) {
        jdbc.update(
                "update meal_groups set status = 'logged', committed_at = current_timestamp, expires_at = null "
                        + "where id = ? and user_id = ?",
                groupId,
                userId
        );
    }

    @Override
    public void markGroupAbandoned(String groupId) {
        jdbc.update("update meal_groups set status = 'abandoned' where id = ?", groupId);
    }

    @Override
    public void reopenDraft(String userId, String groupId) {
        jdbc.update(
                "update meal_groups set status = 'draft', committed_at = null, expires_at = ? "
                        + "where id = ? and user_id = ?",
                OffsetDateTime.now().plusHours(24),
                groupId,
                userId
        );
    }

    private MealDraftItem findDraftItem(String id) {
        List<MealDraftItem> rows = jdbc.query(
                """
                select id, group_id, user_id, food_id, recipe_id, raw_name, amount_g, value_type,
                       analysis, ai_source, confirmation_status, confirmed_by, confirmed_at, created_at
                from meal_draft_items where id = ?
                """,
                draftItemMapper(),
                id
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<MealEntry.Item> listItems(String entryId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select food_id, recipe_id, raw_name, amount_g, value_type,
                       nutrients as nutrients_json, analysis as analysis_json,
                       ai_source, confirmation_status, confirmed_by, confirmed_at
                from meal_entry_items where entry_id = ? order by id
                """,
                entryId
        );

        List<MealEntry.Item> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            items.add(new MealEntry.Item(
                    (String) row.get("food_id"),
                    (String) row.get("recipe_id"),
                    (String) row.get("raw_name"),
                    ((Number) row.get("amount_g")).doubleValue(),
                    (String) row.get("value_type"),
                    codec.nutrients((String) row.get("nutrients_json")),
                    codec.read((String) row.get("analysis_json"), com.dietguide.domain.DishAnalysis.class),
                    (String) row.get("ai_source"),
                    (String) row.get("confirmation_status"),
                    (String) row.get("confirmed_by"),
                    text(row.get("confirmed_at"))
            ));
        }
        return items;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    static Map<String, Double> emptyNutrients() {
        return Nutrients.empty();
    }
}
