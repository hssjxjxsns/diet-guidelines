package com.dietguide.adapter;

import com.dietguide.domain.PeriodReport;
import com.dietguide.service.PeriodReportStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class JdbcPeriodReportStore implements PeriodReportStore {

    private final JdbcTemplate jdbc;
    private final JsonCodec codec;

    public JdbcPeriodReportStore(JdbcTemplate jdbc, JsonCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
    }

    @Override
    public PeriodReport get(String userId, String periodType, String periodStart) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select user_id, period_type, period_start, period_end, stats as stats_json,
                       ai_summary, input_hash, status, generated_at
                from period_reports where user_id = ? and period_type = ? and period_start = cast(? as date)
                """,
                userId,
                periodType,
                periodStart
        );
        if (rows.isEmpty()) {
            return null;
        }

        Map<String, Object> row = rows.get(0);
        return new PeriodReport(
                (String) row.get("user_id"),
                (String) row.get("period_type"),
                text(row.get("period_start")),
                text(row.get("period_end")),
                codec.read((String) row.get("stats_json"), PeriodReport.Stats.class),
                (String) row.get("ai_summary"),
                (String) row.get("input_hash"),
                (String) row.get("status"),
                text(row.get("generated_at"))
        );
    }

    @Override
    public void save(PeriodReport report) {
        jdbc.update(
                """
                merge into period_reports
                  (id, user_id, period_type, period_start, period_end, stats, ai_summary, status, input_hash, generated_at)
                key (user_id, period_type, period_start)
                values (?, ?, ?, cast(? as date), cast(? as date), ?, ?, ?, ?, current_timestamp)
                """,
                UUID.randomUUID().toString(),
                report.userId(),
                report.periodType(),
                report.periodStart(),
                report.periodEnd(),
                codec.write(report.stats()),
                report.aiSummary(),
                report.status(),
                report.inputHash()
        );
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof OffsetDateTime timestamp) {
            return timestamp.toString();
        }
        return value.toString();
    }
}
