package com.dietguide.service;

import com.dietguide.ai.AiGateway;
import com.dietguide.ai.Prompts;
import com.dietguide.domain.FoodGroupStatus;
import com.dietguide.domain.MealEntry;
import com.dietguide.domain.PeriodReport;
import com.dietguide.domain.StructureResult;
import com.dietguide.nutrition.NutritionEngine;
import com.dietguide.nutrition.StructureEvaluator;
import com.dietguide.shared.AppException;
import com.dietguide.shared.Hashing;
import com.dietguide.shared.Nutrients;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ReportService {

    public record DailyAggregate(
            String date,
            Map<String, Double> totals,
            String valueType,
            StructureResult structure,
            String aggregatorVersion
    ) {
    }

    public record SummaryResponse(String summary) {
    }

    private final PeriodReportStore store;
    private final DailyStatsStore dailyStats;
    private final SettingsStore settings;
    private final AiGateway ai;
    private final ObjectMapper mapper;

    public ReportService(
            PeriodReportStore store,
            DailyStatsStore dailyStats,
            SettingsStore settings,
            AiGateway ai,
            ObjectMapper mapper
    ) {
        this.store = store;
        this.dailyStats = dailyStats;
        this.settings = settings;
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
        // inputHash 覆盖四版本（硬约束7）：days 内容 + targetsVersion + ruleVersion + guidelineVersion + aggregatorVersion
        List<Map<String, Object>> hashInput = new ArrayList<>();
        for (DailyAggregate day : days) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", day.date());
            row.put("totals", day.totals());
            hashInput.add(row);
        }
        Map<String, String> versions = dailyStats.versions(userId, java.time.LocalDate.parse(periodStart),
                java.time.LocalDate.parse(periodEnd));
        // targetsVersion：用户每日目标内容哈希（改目标即缓存失效）
        versions.put("targetsVersion", Hashing.sha256Hex(
                Hashing.canonicalJson(settings.getSettings(userId).dailyTargets(), mapper)));
        String inputHash = Hashing.sha256Hex(Hashing.canonicalJson(Map.of(
                "days", hashInput,
                "versions", versions
        ), mapper));

        PeriodReport cached = store.get(userId, periodType, periodStart);
        if (cached != null && inputHash.equals(cached.inputHash())) {
            return cached;
        }

        PeriodReport.Stats stats = buildPeriodStats(days);
        String summary = null;
        try {
            summary = summarize(stats);
        } catch (AppException error) {
            // AI 不可用时仍然返回确定性统计结果，不阻塞报表读取（AI 只是解释层）。
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

        // 主指标：食物组份数达标（基于 daily_stats.materialized structure）
        List<PeriodReport.FoodGroupAdherence> foodGroups = buildFoodGroupAdherence(days);

        // 指南符合度：多样性 / 油盐限量 / 钠 / structure 非空率
        Map<String, Object> guideline = buildGuidelineIndicators(days, average);

        return new PeriodReport.Stats(
                days.size(),
                Nutrients.round(total),
                Nutrients.round(average),
                foodGroups,
                guideline
        );
    }

    private static List<PeriodReport.FoodGroupAdherence> buildFoodGroupAdherence(List<DailyAggregate> days) {
        Map<String, List<Double>> amountByGroup = new LinkedHashMap<>();
        Map<String, List<Double>> rangeByGroup = new LinkedHashMap<>();
        Map<String, Integer> adequateDays = new LinkedHashMap<>();
        int daysWithStructure = 0;

        for (DailyAggregate day : days) {
            StructureResult structure = day.structure();
            if (structure == null || structure.foodGroups().isEmpty()) {
                continue;
            }
            daysWithStructure += 1;
            for (FoodGroupStatus group : structure.foodGroups()) {
                amountByGroup.computeIfAbsent(group.group(), key -> new ArrayList<>()).add(group.amountG());
                rangeByGroup.putIfAbsent(group.group(), group.recommendedRangeG());
                if ("adequate".equals(group.status())) {
                    adequateDays.merge(group.group(), 1, Integer::sum);
                }
            }
        }

        List<PeriodReport.FoodGroupAdherence> result = new ArrayList<>();
        for (Map.Entry<String, List<Double>> entry : amountByGroup.entrySet()) {
            String group = entry.getKey();
            List<Double> amounts = entry.getValue();
            double averageG = amounts.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            int adequate = adequateDays.getOrDefault(group, 0);
            double adherenceRate = daysWithStructure == 0 ? 0 : (double) adequate / daysWithStructure;
            List<Double> range = rangeByGroup.get(group);
            String status = adherenceRate >= 0.8 ? "adequate" : averageG < range.get(0) ? "insufficient" : "excessive";
            result.add(new PeriodReport.FoodGroupAdherence(
                    group,
                    Math.round(averageG * 10) / 10.0,
                    range,
                    Math.round(adherenceRate * 100) / 100.0,
                    status
            ));
        }
        return result;
    }

    private static Map<String, Object> buildGuidelineIndicators(
            List<DailyAggregate> days,
            Map<String, Double> averageNutrients
    ) {
        Map<String, Object> guideline = new LinkedHashMap<>();

        int daysWithStructure = 0;
        int diversityOkDays = 0;
        int oilWithinLimitDays = 0;
        for (DailyAggregate day : days) {
            StructureResult structure = day.structure();
            if (structure == null || structure.foodGroups().isEmpty()) {
                continue;
            }
            daysWithStructure += 1;
            if (structure.distinctFoodCount() >= 4) {
                diversityOkDays += 1;
            }
            boolean oilOk = structure.foodGroups().stream()
                    .filter(group -> "oils".equals(group.group()))
                    .findFirst()
                    .map(group -> !"excessive".equals(group.status()))
                    .orElse(true);
            if (oilOk) {
                oilWithinLimitDays += 1;
            }
        }

        guideline.put("structureNonEmptyRate", days.isEmpty()
                ? 0.0
                : Math.round((double) daysWithStructure / days.size() * 100) / 100.0);
        guideline.put("diversityOkDays", diversityOkDays);
        guideline.put("oilWithinLimitDays", oilWithinLimitDays);
        guideline.put("sodiumAverageMg", Nutrients.round(averageNutrients).getOrDefault("sodiumMg", 0.0));
        guideline.put("sodiumWithinLimit",
                Nutrients.round(averageNutrients).getOrDefault("sodiumMg", 0.0) <= 2000.0);
        guideline.put("conclusion", conclusion(days.size(), diversityOkDays, oilWithinLimitDays, daysWithStructure));
        return guideline;
    }

    private static String conclusion(int days, int diversityOkDays, int oilOkDays, int daysWithStructure) {
        if (days == 0 || daysWithStructure == 0) {
            return "记录天数不足，暂无法评估膳食指南符合度";
        }
        StringBuilder text = new StringBuilder();
        text.append("记录 ").append(days).append(" 天；");
        text.append("多样性达标 ").append(diversityOkDays).append(" 天，");
        text.append("油盐限量达标 ").append(oilOkDays).append(" 天，");
        text.append("结构数据完整率 ").append(Math.round((double) daysWithStructure / days * 100)).append("%。");
        return text.toString();
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
