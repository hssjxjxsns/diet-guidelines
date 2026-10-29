package com.dietguide.service;

import com.dietguide.ai.AiGateway;
import com.dietguide.ai.Prompts;
import com.dietguide.domain.MealEntry;
import com.dietguide.domain.PeriodReport;
import com.dietguide.nutrition.NutritionEngine;
import com.dietguide.shared.AppException;
import com.dietguide.shared.Hashing;
import com.dietguide.shared.Nutrients;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ReportService {

    public record DailyAggregate(String date, Map<String, Double> totals, String valueType) {
    }

    public record SummaryResponse(String summary) {
    }

    private final PeriodReportStore store;
    private final AiGateway ai;
    private final ObjectMapper mapper;

    public ReportService(PeriodReportStore store, AiGateway ai, ObjectMapper mapper) {
        this.store = store;
        this.ai = ai;
        this.mapper = mapper;
    }

    public PeriodReport getOrCreatePeriod(
            String userId,
            String periodType,
            String periodStart,
            String periodEnd,
            List<DailyAggregate> days
    ) {
        List<Map<String, Object>> hashInput = days.stream()
                .map(day -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("date", day.date());
                    row.put("totals", day.totals());
                    return row;
                })
                .collect(Collectors.toList());
        String inputHash = Hashing.sha256Hex(Hashing.canonicalJson(hashInput, mapper));

        PeriodReport cached = store.get(userId, periodType, periodStart);
        if (cached != null && inputHash.equals(cached.inputHash())) {
            return cached;
        }

        PeriodReport.Stats stats = buildPeriodStats(days);
        String summary = null;
        try {
            summary = summarize(stats);
        } catch (AppException error) {
            // AI 不可用时仍然返回确定性统计结果，不阻塞报表读取。
            summary = null;
        }

        PeriodReport report = new PeriodReport(
                userId,
                periodType,
                periodStart,
                periodEnd,
                stats,
                summary,
                inputHash,
                "fresh",
                Instant.now().toString()
        );

        store.save(report);
        return report;
    }

    public static PeriodReport.Stats buildPeriodStats(List<DailyAggregate> days) {
        Map<String, Double> total = Nutrients.empty();
        for (DailyAggregate day : days) {
            total = Nutrients.add(total, day.totals());
        }

        Map<String, Double> average = Nutrients.empty();
        if (!days.isEmpty()) {
            for (String key : Nutrients.KEYS) {
                Double value = total.get(key);
                if (value == null) {
                    continue;
                }
                average.put(key, value / days.size());
            }
        }

        return new PeriodReport.Stats(days.size(), Nutrients.round(total), Nutrients.round(average));
    }

    public static Map<String, Double> aggregateEntries(List<MealEntry> entries) {
        List<Map<String, Double>> totals = new ArrayList<>();
        entries.forEach(entry -> totals.add(entry.totals()));
        return NutritionEngine.sumEntryTotals(totals);
    }

    private String summarize(PeriodReport.Stats stats) {
        try {
            String payload = mapper.writeValueAsString(stats);
            SummaryResponse response = ai.chatJson(
                    new AiGateway.ChatRequest(
                            "period_summary",
                            Prompts.PERIOD_SUMMARY_SYSTEM_PROMPT,
                            payload,
                            900,
                            0.4,
                            null
                    ),
                    SummaryResponse.class
            );
            return response == null ? null : response.summary();
        } catch (AppException error) {
            throw error;
        } catch (Exception error) {
            return null;
        }
    }
}
