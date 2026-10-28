package com.dietguide.adapter;

import com.dietguide.domain.InferredTrend;
import com.dietguide.domain.PlanningContext;
import com.dietguide.domain.RecentTrend;
import com.dietguide.domain.StructureResult;
import com.dietguide.domain.UserSettings;
import com.dietguide.nutrition.StructureEvaluator;
import com.dietguide.nutrition.Targets;
import com.dietguide.service.PlanningContextLoader;
import com.dietguide.service.SettingsStore;
import com.dietguide.shared.Nutrients;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class JdbcPlanningContextLoader implements PlanningContextLoader {

    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final SettingsStore settingsStore;
    private final JsonCodec codec;
    private final ObjectMapper mapper;

    public JdbcPlanningContextLoader(
            org.springframework.jdbc.core.JdbcTemplate jdbc,
            SettingsStore settingsStore,
            JsonCodec codec,
            ObjectMapper mapper
    ) {
        this.jdbc = jdbc;
        this.settingsStore = settingsStore;
        this.codec = codec;
        this.mapper = mapper;
    }

    @Override
    public PlanningContext.UserContext load(String userId, LocalDate mealDate, String mealType) {
        UserSettings settings = settingsStore.getSettings(userId);

        List<Map<String, Object>> statsRows = jdbc.queryForList(
                """
                select metrics as metrics_json, structure as structure_json
                from daily_stats where user_id = ? and stat_date = ?
                """,
                userId,
                mealDate
        );

        Map<String, Double> consumed = Nutrients.empty();
        StructureResult structure = StructureEvaluator.evaluateStructure(Map.of(), mealType, 0);
        if (!statsRows.isEmpty()) {
            Map<String, Object> row = statsRows.get(0);
            consumed = parseConsumed((String) row.get("metrics_json"));
            StructureResult stored = codec.read((String) row.get("structure_json"), StructureResult.class);
            if (stored != null && stored.foodGroups() != null && !stored.foodGroups().isEmpty()) {
                structure = stored;
            }
        }

        List<Map<String, Object>> groupRows = jdbc.queryForList(
                "select meal_type from meal_groups where user_id = ? and meal_date = ?",
                userId,
                mealDate
        );
        Set<String> loggedTypes = new LinkedHashSet<>();
        groupRows.forEach(row -> loggedTypes.add(String.valueOf(row.get("meal_type"))));
        List<String> unloggedMeals = Targets.ALL_MEALS.stream()
                .filter(meal -> !loggedTypes.contains(meal))
                .toList();

        List<Map<String, Object>> trendRows = jdbc.queryForList(
                """
                select metric, conclusion, status, evidence as evidence_json
                from inferred_trends where user_id = ? and status <> 'invalidated'
                """,
                userId
        );

        List<InferredTrend> inferredTrends = new ArrayList<>();
        for (Map<String, Object> row : trendRows) {
            inferredTrends.add(new InferredTrend(
                    String.valueOf(row.get("metric")),
                    String.valueOf(row.get("conclusion")),
                    String.valueOf(row.get("status")),
                    codec.read((String) row.get("evidence_json"), InferredTrend.Evidence.class)
            ));
        }

        List<RecentTrend> recentTrends = inferredTrends.stream()
                .map(trend -> new RecentTrend(
                        trend.metric(),
                        trend.evidence() == null || trend.evidence().deviation() == 0
                                ? "stable"
                                : trend.evidence().deviation() < 0 ? "down" : "up",
                        trend.conclusion()
                ))
                .toList();

        return new PlanningContext.UserContext(
                settings,
                settings.dailyTargets(),
                consumed,
                unloggedMeals,
                structure,
                recentTrends,
                inferredTrends
        );
    }

    private Map<String, Double> parseConsumed(String metricsJson) {
        if (metricsJson == null || metricsJson.isBlank()) {
            return Nutrients.empty();
        }
        try {
            JsonNode root = mapper.readTree(metricsJson);
            JsonNode values = root.has("values") ? root.get("values") : root;
            Map<String, Double> result = new LinkedHashMap<>();
            for (String key : Nutrients.KEYS) {
                JsonNode value = values.get(key);
                if (value != null && value.isNumber()) {
                    result.put(key, value.asDouble());
                }
            }
            return result;
        } catch (Exception error) {
            return Nutrients.empty();
        }
    }
}
