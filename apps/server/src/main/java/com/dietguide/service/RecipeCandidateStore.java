package com.dietguide.service;

import com.dietguide.domain.DishAnalysis;

public interface RecipeCandidateStore {

    void save(String userId, DishAnalysis analysis);
}
