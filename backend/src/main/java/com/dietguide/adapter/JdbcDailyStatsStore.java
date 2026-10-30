package com.dietguide.adapter;

import com.dietguide.domain.StructureResult;
import com.dietguide.service.DailyStatsStore;
import com.dietguide.service.ReportService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class JdbcDailyStatsStore implements DailyStatsStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcDailyStatsStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public List<ReportService.DailyAggregate> list(String userId, LocalDate start, LocalDate end) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select stat_date, metrics as metrics_json, structure as structure_json,
                       value_type, aggregator_version
                from daily_stats where user_id = ? and stat_date between ? and ?
                order by stat_date
                """,
                userId,
                start,
                end
        );

        List<ReportService.DailyAggregate> days = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            days.add(new ReportService.DailyAggregate(
                    String.valueOf(row.get("stat_date")),
                    parseTotals((String) row.get("metrics_json")),
                    String.valueOf(row.get("value_type")),
                    readStructure((String) row.get("structure_json")),
                    (String) row.get("aggregator_version")
            ));
        }
        return days;
    }

    @Override
    public Map<String, String> versions(String userId, LocalDate start, LocalDate end) {
        Map<String, String> versions = new LinkedHashMap<>();

        // aggregatorVersion：区间内 daily_stats 的聚合口径版本（任一天口径变更即失效）
        List<Map<String, Object>> aggregatorRows = jdbc.queryForList(
                "select distinct aggregator_version from daily_stats "
                        + "where user_id = ? and stat_date between ? and ? order by aggregator_version",
                userId,
                start,
                end
        );
        versions.put("aggregatorVersion", aggregatorRows.stream()
                .map(row -> String.valueOf(row.get("aggregator_version")))
                .reduce((a, b) -> a + "," + b)
                .orElse("none"));

        // ruleVersion：规则表当前版本
        List<String> ruleRows = jdbc.queryForList(
                "select max(version) from validation_rules", String.class);
        versions.put("ruleVersion", ruleRows.isEmpty() || ruleRows.get(0) == null ? "none" : ruleRows.get(0));

        // guidelineVersion：指南语料内容哈希指纹（语料/向量变更即失效）
        List<Map<String, Object>> guidelineRows = jdbc.queryForList(
                "select count(*) as chunk_count, coalesce(max(content_hash), '-') as hash_tag from guideline_chunks");
        if (guidelineRows.isEmpty()) {
            versions.put("guidelineVersion", "none");
        } else {
            versions.put("guidelineVersion", guidelineRows.get(0).get("chunk_count")
                    + ":" + guidelineRows.get(0).get("hash_tag"));
        }

        // targetsVersion：由读取方（ReportService）以 targets 内容哈希填充
        versions.put("targetsVersion", "");
        return versions;
    }

    private StructureResult readStructure(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return null;
        }
        try {
            return mapper.readValue(json, StructureResult.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Map<String, Double> parseTotals(String json) {
        Map<String, Double> totals = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return totals;
        }
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode values = root.has("values") ? root.get("values") : root;
            values.fields().forEachRemaining(entry -> {
                if (entry.getValue().isNumber()) {
                    totals.put(entry.getKey(), entry.getValue().asDouble());
                }
            });
        } catch (Exception ignored) {
            return totals;
        }
        return totals;
    }
}
