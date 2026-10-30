package com.dietguide.web;

import com.dietguide.domain.UserSettings;
import com.dietguide.nutrition.Targets;
import com.dietguide.service.SettingsStore;
import com.dietguide.shared.AppException;
import com.dietguide.web.security.CurrentUserId;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 设置字段级更新（PATCH）：仅覆盖请求中出现的字段，避免"只改目标却清空过敏原"的业务事故。
 * 过敏原不是普通偏好：变更需显式确认（confirm_allergen_change=true），否则 422。
 */
@RestController
@RequestMapping("/settings")
public class SettingsController {

    public record SettingsPatchRequest(
            @JsonProperty("daily_targets") @JsonAlias("dailyTargets") Map<String, Double> dailyTargets,
            List<String> preferences,
            List<String> allergens,
            List<String> avoid,
            @JsonProperty("confirm_allergen_change")
            @JsonAlias("confirmAllergenChange") Boolean confirmAllergenChange
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

    @PatchMapping
    public UserSettings update(@CurrentUserId String userId, @RequestBody SettingsPatchRequest request) {
        UserSettings current = settingsStore.getSettings(userId);

        // 过敏原变更需显式确认
        if (request.allergens() != null && !sameList(current.allergens(), request.allergens())
                && !Boolean.TRUE.equals(request.confirmAllergenChange())) {
            throw AppException.validation(
                    "过敏原变更需显式确认（confirm_allergen_change=true）",
                    Map.of(
                            "current", current.allergens() == null ? List.of() : current.allergens(),
                            "requested", request.allergens()
                    )
            );
        }

        Map<String, Double> dailyTargets = new LinkedHashMap<>(Targets.DEFAULT_DAILY_TARGETS);
        if (current.dailyTargets() != null) {
            dailyTargets.putAll(current.dailyTargets());
        }
        if (request.dailyTargets() != null) {
            dailyTargets.putAll(request.dailyTargets());
        }

        UserSettings updated = new UserSettings(
                dailyTargets,
                request.preferences() != null ? List.copyOf(new LinkedHashSet<>(request.preferences()))
                        : current.preferences(),
                request.allergens() != null ? List.copyOf(new LinkedHashSet<>(request.allergens()))
                        : current.allergens(),
                request.avoid() != null ? List.copyOf(new LinkedHashSet<>(request.avoid()))
                        : current.avoid()
        );
        settingsStore.saveSettings(userId, updated);
        return updated;
    }

    private static boolean sameList(List<String> a, List<String> b) {
        return Objects.equals(
                new LinkedHashSet<>(a == null ? List.<String>of() : a),
                new LinkedHashSet<>(b == null ? List.<String>of() : b)
        );
    }
}
