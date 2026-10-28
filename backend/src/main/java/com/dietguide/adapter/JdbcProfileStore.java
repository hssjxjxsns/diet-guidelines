package com.dietguide.adapter;

import com.dietguide.domain.InferredTrend;
import com.dietguide.domain.UserSettings;
import com.dietguide.service.ProfileStore;
import com.dietguide.service.SettingsStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class JdbcProfileStore implements ProfileStore {

    private final JdbcTemplate jdbc;
    private final SettingsStore settingsStore;
    private final JsonCodec codec;

    public JdbcProfileStore(JdbcTemplate jdbc, SettingsStore settingsStore, JsonCodec codec) {
        this.jdbc = jdbc;
        this.settingsStore = settingsStore;
        this.codec = codec;
    }

    @Override
    public UserSettings getSettings(String userId) {
        return settingsStore.getSettings(userId);
    }

    @Override
    public void saveSettings(String userId, UserSettings settings) {
        settingsStore.saveSettings(userId, settings);
    }

    @Override
    public List<InferredTrend> listTrends(String userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select metric, conclusion, status, evidence as evidence_json
                from inferred_trends where user_id = ?
                """,
                userId
        );

        List<InferredTrend> trends = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            trends.add(new InferredTrend(
                    String.valueOf(row.get("metric")),
                    String.valueOf(row.get("conclusion")),
                    String.valueOf(row.get("status")),
                    codec.read((String) row.get("evidence_json"), InferredTrend.Evidence.class)
            ));
        }
        return trends;
    }

    @Override
    public void saveTrend(String userId, InferredTrend trend) {
        jdbc.update(
                """
                merge into inferred_trends
                  (id, user_id, metric, conclusion, status, evidence, window_start, window_end, sample_meals, last_verified)
                key (user_id, metric)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)
                """,
                UUID.randomUUID().toString(),
                userId,
                trend.metric(),
                trend.conclusion(),
                trend.status(),
                codec.write(trend.evidence()),
                trend.evidence() == null ? null : trend.evidence().windowStart(),
                trend.evidence() == null ? null : trend.evidence().windowEnd(),
                trend.evidence() == null ? 0 : trend.evidence().sampleMeals()
        );
    }

    @Override
    public void invalidateTrend(String userId, String metric) {
        jdbc.update(
                """
                update inferred_trends set status = 'invalidated', invalidated_at = current_timestamp
                where user_id = ? and metric = ?
                """,
                userId,
                metric
        );
    }
}
