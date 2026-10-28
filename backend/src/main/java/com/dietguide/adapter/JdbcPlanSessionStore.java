package com.dietguide.adapter;

import com.dietguide.domain.PlanItem;
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
                insert into plan_sessions (id, user_id, meal_date, meal_type, status, context_snapshot, validation_result)
                values (?, ?, ?, ?, ?, ?, ?)
                """,
                sessionId,
                draft.userId(),
                draft.mealDate(),
                draft.mealType(),
                draft.status(),
                codec.write(draft.contextSnapshot()),
                draft.validationResult() == null ? null : codec.write(draft.validationResult())
        );

        for (PlanItem item : draft.items()) {
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

        return sessionId;
    }

    @Override
    public Map<String, Object> find(String userId, String sessionId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select id, user_id, meal_date, meal_type, status, context_snapshot as context_json,
                       validation_result as validation_json, created_at, updated_at
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
                "update plan_sessions set status = 'awaiting_log', updated_at = current_timestamp where id = ? and user_id = ?",
                sessionId,
                userId
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
