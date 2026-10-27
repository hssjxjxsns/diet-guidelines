package com.dietguide.adapter;

import com.dietguide.domain.UserSettings;
import com.dietguide.nutrition.Targets;
import com.dietguide.service.SettingsStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class JdbcSettingsRepository implements SettingsStore {

    private final JdbcTemplate jdbc;
    private final JsonCodec codec;

    public JdbcSettingsRepository(JdbcTemplate jdbc, JsonCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
    }

    @Override
    public UserSettings getSettings(String userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select daily_targets::text as targets_json, preferences, allergens, avoid
                from user_settings where user_id = ?
                """,
                userId
        );

        Map<String, Double> dailyTargets = new LinkedHashMap<>(Targets.DEFAULT_DAILY_TARGETS);
        if (rows.isEmpty()) {
            return new UserSettings(dailyTargets, List.of(), List.of(), List.of());
        }

        Map<String, Object> row = rows.get(0);
        dailyTargets.putAll(codec.nutrients((String) row.get("targets_json")));

        return new UserSettings(
                dailyTargets,
                strings(row.get("preferences")),
                strings(row.get("allergens")),
                strings(row.get("avoid"))
        );
    }

    @Override
    public void saveSettings(String userId, UserSettings settings) {
        jdbc.update(
                """
                insert into user_settings (user_id, daily_targets, preferences, allergens, avoid, updated_at)
                values (?, cast(? as jsonb), ?, ?, ?, now())
                on conflict (user_id) do update set
                  daily_targets = excluded.daily_targets,
                  preferences = excluded.preferences,
                  allergens = excluded.allergens,
                  avoid = excluded.avoid,
                  updated_at = now()
                """,
                userId,
                codec.write(settings.dailyTargets() == null ? Map.of() : settings.dailyTargets()),
                sqlArray(settings.preferences()),
                sqlArray(settings.allergens()),
                sqlArray(settings.avoid())
        );
    }

    static List<String> strings(Object value) {
        if (value instanceof java.sql.Array sqlArray) {
            try {
                Object array = sqlArray.getArray();
                if (array instanceof String[] values) {
                    return List.of(values);
                }
                if (array instanceof Object[] values) {
                    return java.util.Arrays.stream(values).map(String::valueOf).toList();
                }
            } catch (Exception ignored) {
                return List.of();
            }
        }
        if (value instanceof String[] values) {
            return List.of(values);
        }
        return List.of();
    }

    /** PostgreSQL text[] 需要显式类型转换。 */
    static String sqlArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "{}";
        }
        StringBuilder builder = new StringBuilder("{");
        for (int index = 0; index < values.size(); index += 1) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append('"').append(values.get(index).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return builder.append('}').toString();
    }
}
