package com.dietguide.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface DailyStatsStore {

    List<ReportService.DailyAggregate> list(String userId, LocalDate start, LocalDate end);

    /**
     * 报表缓存失效指纹的四个版本来源（缺一不可）：
     * targetsVersion / ruleVersion / guidelineVersion / aggregatorVersion。
     */
    Map<String, String> versions(String userId, LocalDate start, LocalDate end);
}
