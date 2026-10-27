package com.dietguide.service;

import com.dietguide.domain.PeriodReport;

public interface PeriodReportStore {

    PeriodReport get(String userId, String periodType, String periodStart);

    void save(PeriodReport report);
}
