package com.dietguide.service;

import java.time.LocalDate;
import java.util.List;

public interface DailyStatsStore {

    List<ReportService.DailyAggregate> list(String userId, LocalDate start, LocalDate end);
}
