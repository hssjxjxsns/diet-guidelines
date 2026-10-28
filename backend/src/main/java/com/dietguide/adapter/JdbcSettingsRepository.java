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
                select daily_targets as targets_json, preferences, allergens, avoid
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
                codec.stringList((String) row.get("preferences")),
                codec.stringList((String) row.get("allergens")),
                codec.stringList((String) row.get("avoid"))
        );
    }

    @Override
    public void saveSettings(String userId, UserSettings settings) {
        jdbc.update(
                """
                merge into user_settings (user_id, daily_targets, preferences, allergens, avoid, updated_at)
                key (user_id)
                values (?, ?, ?, ?, ?, current_timestamp)
                """,
                userId,
                codec.write(settings.dailyTargets() == null ? Map.of() : settings.dailyTargets()),
                codec.write(settings.preferences() == null ? List.of() : settings.preferences()),
                codec.write(settings.allergens() == null ? List.of() : settings.allergens()),
                codec.write(settings.avoid() == null ? List.of() : settings.avoid())
        );
    }
}
