package com.dietguide.web;

import com.dietguide.domain.UserSettings;
import com.dietguide.nutrition.Targets;
import com.dietguide.service.SettingsStore;
import com.dietguide.web.security.CurrentUserId;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/settings")
public class SettingsController {

    public record SettingsRequest(
            @JsonProperty("daily_targets") @JsonAlias("dailyTargets") Map<String, Double> dailyTargets,
            List<String> preferences,
            List<String> allergens,
            List<String> avoid
    ) {
    }

    private final SettingsStore settingsStore;

    public SettingsController(SettingsStore settingsStore) {
        this.settingsStore = settingsStore;
    }

    @GetMapping
    public UserSettings get(@CurrentUserId String userId) {
        return settingsStore.getSettings(userId);
    }

    @PutMapping
    public Map<String, Object> update(@CurrentUserId String userId, @RequestBody SettingsRequest request) {
        Map<String, Double> dailyTargets = new LinkedHashMap<>(Targets.DEFAULT_DAILY_TARGETS);
        if (request.dailyTargets() != null) {
            dailyTargets.putAll(request.dailyTargets());
        }

        settingsStore.saveSettings(userId, new UserSettings(
                dailyTargets,
                request.preferences() == null ? List.of() : request.preferences(),
                request.allergens() == null ? List.of() : request.allergens(),
                request.avoid() == null ? List.of() : request.avoid()
        ));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        return payload;
    }
}
