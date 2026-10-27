package com.dietguide.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MealPlanDraft(List<PlanItem> items, String explanation, List<String> citations) {
}
