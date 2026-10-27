package com.dietguide.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Food(
        String id,
        String name,
        List<String> aliases,
        String category,
        Map<String, Double> nutrientsPer100g,
        double edibleRatio,
        String source,
        String sourceVersion,
        String status,
        List<String> tags,
        List<String> allergens
) {
}
