package com.dietguide.adapter;

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
                select stat_date, metrics::text as metrics_json, value_type
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
                    String.valueOf(row.get("value_type"))
            ));
        }
        return days;
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
