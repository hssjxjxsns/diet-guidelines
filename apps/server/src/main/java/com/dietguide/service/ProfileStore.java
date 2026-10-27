package com.dietguide.service;

import com.dietguide.domain.InferredTrend;
import com.dietguide.domain.UserSettings;

import java.util.List;

public interface ProfileStore {

    UserSettings getSettings(String userId);

    void saveSettings(String userId, UserSettings settings);

    List<InferredTrend> listTrends(String userId);

    void saveTrend(String userId, InferredTrend trend);

    void invalidateTrend(String userId, String metric);
}
