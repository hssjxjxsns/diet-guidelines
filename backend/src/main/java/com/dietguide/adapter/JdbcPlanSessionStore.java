package com.dietguide.adapter;

import com.dietguide.domain.PlanItem;
import com.dietguide.domain.ReplaceConstraints;
import com.dietguide.domain.ValidationResult;
import com.dietguide.service.PlanSessionStore;
import org.springframework.jdbc.core.JdbcTemplate;
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
public class JdbcPlanSessionStore implements PlanSessionStore {

    private final JdbcTemplate jdbc;
    private final JsonCodec codec;

    public JdbcPlanSessionStore(JdbcTemplate jdbc, JsonCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
    }

    @Override
    @Transactional
    public String save(PlanSessionDraft draft) {
        String sessionId = UUID.randomUUID().toString();
        jdbc.update(
                """
                insert into plan_sessions (id, user_id, meal_date, meal_type, status, context_snapshot, validation_result, current_revision)
                values (?, ?, ?, ?, ?, ?, ?, 1)
                """,
                sessionId,
                draft.userId(),
                draft.mealDate(),
                draft.mealType(),
                draft.status(),
                codec.write(draft.contextSnapshot()),
                draft.validationResult() == null ? null : codec.write(draft.validationResult())
        );

        insertCurrentItems(sessionId, draft.items());
        insertRevision(sessionId, 1, null, draft.source() == null ? "AI" : draft.source(),
                draft.items(), null, null, draft.validationResult());
        return sessionId;
    }

    @Override
    public Map<String, Object> find(String userId, String sessionId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select id, user_id, meal_date, meal_type, status, context_snapshot as context_json,
                       validation_result as validation_json, current_revision, accepted_group_id,
                       created_at, updated_at
                from plan_sessions where id = ? and user_id = ?
                """,
                sessionId,
                userId
        );
        if (rows.isEmpty()) {
            return null;
        }

        Map<String, Object> row = rows.get(0);
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("id", row.get("id"));
        session.put("user_id", row.get("user_id"));
        session.put("meal_date", text(row.get("meal_date")));
        session.put("meal_type", row.get("meal_type"));
        session.put("status", row.get("status"));
        session.put("current_revision", row.get("current_revision"));
        session.put("accepted_group_id", row.get("accepted_group_id"));
        session.put("context_snapshot", codec.readTree((String) row.get("context_json")));
        session.put("validation_result", codec.readTree((String) row.get("validation_json")));
        session.put("created_at", text(row.get("created_at")));
        session.put("updated_at", text(row.get("updated_at")));
        session.put("plan_items", listItems(sessionId));
        return session;
    }

    @Override
    public void complete(String userId, String sessionId) {
        jdbc.update(
                "update plan_sessions set status = 'closed', updated_at = current_timestamp where id = ? and user_id = ?",
                sessionId,
                userId
        );
    }

    @Override
    public List<PlanItem> currentItems(String userId, String sessionId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select food_id, recipe_id, name, amount_g, food_group, tags, allergens, reason, citations
                from plan_items where session_id = ? order by id
                """,
                sessionId
        );
        List<PlanItem> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            items.add(new PlanItem(
                    (String) row.get("food_id"),
                    (String) row.get("recipe_id"),
                    (String) row.get("name"),
                    ((Number) row.get("amount_g")).doubleValue(),
                    (String) row.get("food_group"),
                    codec.stringList((String) row.get("tags")),
                    codec.stringList((String) row.get("allergens")),
                    (String) row.get("reason"),
                    codec.stringList((String) row.get("citations"))
            ));
        }
        return items;
    }

    @Override
    public int currentRevision(String userId, String sessionId) {
        List<Integer> rows = jdbc.queryForList(
                "select current_revision from plan_sessions where id = ? and user_id = ?",
                Integer.class,
                sessionId,
                userId
        );
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    @Override
    @Transactional
    public int saveRevision(RevisionDraft draft) {
        int nextRevision = currentRevision(draft.userId(), draft.sessionId()) + 1;
        jdbc.update(
                "update plan_sessions set current_revision = ?, updated_at = current_timestamp where id = ? and user_id = ?",
                nextRevision,
                draft.sessionId(),
                draft.userId()
        );
        insertRevision(
                draft.sessionId(),
                nextRevision,
                draft.baseRevision(),
                draft.source() == null ? "AI" : draft.source(),
                draft.items(),
                draft.constraints(),
                draft.userFeedback(),
                draft.validationResult()
        );
        jdbc.update("delete from plan_items where session_id = ?", draft.sessionId());
        insertCurrentItems(draft.sessionId(), draft.items());
        return nextRevision;
    }

    @Override
    public void markAccepting(String userId, String sessionId) {
        jdbc.update(
                "update plan_sessions set status = 'accepting', updated_at = current_timestamp "
                        + "where id = ? and user_id = ? and status in ('planning', 'failed')",
                sessionId,
                userId
        );
    }

    @Override
    public void markLogged(String userId, String sessionId, String groupId) {
        jdbc.update(
                "update plan_sessions set status = 'logged', accepted_group_id = ?, updated_at = current_timestamp "
                        + "where id = ? and user_id = ?",
                groupId,
                sessionId,
                userId
        );
    }

    @Override
    public void markFailed(String userId, String sessionId) {
        jdbc.update(
                "update plan_sessions set status = 'failed', updated_at = current_timestamp where id = ? and user_id = ?",
                sessionId,
                userId
        );
    }

    private void insertCurrentItems(String sessionId, List<PlanItem> items) {
        for (PlanItem item : items == null ? List.<PlanItem>of() : items) {
            jdbc.update(
                    """
                    insert into plan_items
                      (id, session_id, food_id, recipe_id, name, amount_g, food_group, tags, allergens, reason, citations, rejected)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, false)
                    """,
                    UUID.randomUUID().toString(),
                    sessionId,
                    item.foodId(),
                    item.recipeId(),
                    item.name(),
                    item.amountG(),
                    item.foodGroup(),
                    codec.write(item.tags() == null ? List.of() : item.tags()),
                    codec.write(item.allergens() == null ? List.of() : item.allergens()),
                    item.reason(),
                    codec.write(item.citations() == null ? List.of() : item.citations())
            );
        }
    }

    private void insertRevision(
            String sessionId,
            int revision,
            Integer baseRevision,
            String source,
            List<PlanItem> items,
            ReplaceConstraints constraints,
            String userFeedback,
            ValidationResult validationResult
    ) {
        jdbc.update(
                """
                insert into plan_revisions
                  (id, session_id, revision, base_revision, source, items, replace_constraints, user_feedback, validation_result)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(),
                sessionId,
                revision,
                baseRevision,
                source,
                codec.write(items == null ? List.of() : items),
                constraints == null ? null : codec.write(constraints),
                userFeedback,
                validationResult == null ? null : codec.write(validationResult)
        );
    }

    private List<Map<String, Object>> listItems(String sessionId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select id, session_id, food_id, recipe_id, name, amount_g, food_group,
                       tags, allergens, reason, citations as citations_json, rejected
                from plan_items where session_id = ? order by id
                """,
                sessionId
        );

        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.get("id"));
            item.put("session_id", row.get("session_id"));
            item.put("food_id", row.get("food_id"));
            item.put("recipe_id", row.get("recipe_id"));
            item.put("name", row.get("name"));
            item.put("amount_g", row.get("amount_g"));
            item.put("food_group", row.get("food_group"));
            item.put("tags", codec.stringList((String) row.get("tags")));
            item.put("allergens", codec.stringList((String) row.get("allergens")));
            item.put("reason", row.get("reason"));
            item.put("citations", codec.readTree((String) row.get("citations_json")));
            item.put("rejected", row.get("rejected"));
            items.add(item);
        }
        return items;
    }

    private static String text(Object value) {
        if (value instanceof LocalDate date) {
            return date.toString();
        }
        if (value instanceof OffsetDateTime timestamp) {
            return timestamp.toString();
        }
        return value == null ? null : value.toString();
    }
}
