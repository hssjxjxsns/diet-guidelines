package com.dietguide.domain;

public record InferredTrend(String metric, String conclusion, String status, Evidence evidence) {

    public record Evidence(String windowStart, String windowEnd, int sampleMeals, double deviation) {
    }
}
