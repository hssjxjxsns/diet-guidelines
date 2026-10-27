package com.dietguide.web;

import com.dietguide.domain.PeriodReport;
import com.dietguide.service.DailyStatsStore;
import com.dietguide.service.ReportService;
import com.dietguide.shared.AppException;
import com.dietguide.web.security.CurrentUserId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

@RestController
@RequestMapping("/reports")
public class ReportController {

    private final ReportService reports;
    private final DailyStatsStore dailyStats;

    public ReportController(ReportService reports, DailyStatsStore dailyStats) {
        this.reports = reports;
        this.dailyStats = dailyStats;
    }

    @GetMapping("/{periodType}")
    public PeriodReport report(
            @CurrentUserId String userId,
            @PathVariable String periodType,
            @RequestParam(required = false) String start
    ) {
        if (!"weekly".equals(periodType) && !"monthly".equals(periodType)) {
            throw AppException.validation("valid type and start are required");
        }
        if (start == null || start.isBlank()) {
            throw AppException.validation("valid type and start are required");
        }

        LocalDate startDate;
        try {
            startDate = LocalDate.parse(start);
        } catch (DateTimeParseException error) {
            throw AppException.validation("start must be YYYY-MM-DD");
        }

        LocalDate endDate = "weekly".equals(periodType)
                ? startDate.plusDays(6)
                : startDate.withDayOfMonth(startDate.lengthOfMonth());

        return reports.getOrCreatePeriod(
                userId,
                periodType,
                startDate.toString(),
                endDate.toString(),
                dailyStats.list(userId, startDate, endDate)
        );
    }
}
