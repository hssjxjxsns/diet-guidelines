package com.dietguide.service;

import com.dietguide.domain.PlanningContext;

import java.time.LocalDate;

public interface PlanningContextLoader {

    PlanningContext.UserContext load(String userId, LocalDate mealDate, String mealType);
}
