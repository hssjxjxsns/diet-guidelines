package com.dietguide.service;

import com.dietguide.domain.UserSettings;

public interface SettingsStore {

    UserSettings getSettings(String userId);

    void saveSettings(String userId, UserSettings settings);
}
