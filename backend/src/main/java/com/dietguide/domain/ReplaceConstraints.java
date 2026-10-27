package com.dietguide.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ReplaceConstraints(
        Boolean vegetarian,
        Boolean noSpicy,
        List<String> requiredFoodGroups,
        Double minProteinG,
        Double maxEnergyKcal
) {
}
