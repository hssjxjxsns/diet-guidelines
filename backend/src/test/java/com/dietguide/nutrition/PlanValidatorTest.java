package com.dietguide.nutrition;

import com.dietguide.domain.PlanItem;
import com.dietguide.domain.UserSettings;
import com.dietguide.domain.ValidationResult;
import com.dietguide.support.Fixtures;
import com.dietguide.support.MemoryFoodRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanValidatorTest {

    private final MemoryFoodRepository foods = Fixtures.repository();
    private final NutritionEngine engine = new NutritionEngine(foods);
    private final PlanValidator validator = new PlanValidator(foods, engine);

    private static final UserSettings EMPTY_SETTINGS =
            new UserSettings(Targets.DEFAULT_DAILY_TARGETS, List.of(), List.of(), List.of());

    @Test
    void balancedLunchPassesAllHardRules() {
        ValidationResult result = validate(
                List.of(item("f_rice_001", "米饭", 250, "grains"),
                        item("f_broccoli_001", "西兰花", 250, "vegetables"),
                        item("f_chicken_001", "鸡胸肉", 150, "protein")),
                EMPTY_SETTINGS,
                ""
        );

        assertTrue(result.passed(), () -> String.valueOf(result.failedRules()));
    }

    @Test
    void flagsAllergenAsHardRule() {
        ValidationResult result = validate(
                List.of(item("f_peanut_001", "花生", 100, "soy_nuts")),
                new UserSettings(Targets.DEFAULT_DAILY_TARGETS, List.of(), List.of("花生"), List.of()),
                ""
        );

        assertFalse(result.passed());
        assertTrue(result.failedRules().stream().anyMatch(rule -> "R01_ALLERGEN".equals(rule.ruleId())));
    }

    @Test
    void flagsInsufficientVegetablesForLunch() {
        ValidationResult result = validate(
                List.of(item("f_rice_001", "米饭", 300, "grains")),
                EMPTY_SETTINGS,
                ""
        );

        // 规则表（2026-10-30）：R05 结构为软规则 —— 只提示不阻断，passed 仍为 true
        assertTrue(result.passed());
        assertTrue(result.warnings().stream()
                .anyMatch(warning -> "R05_STRUCTURE".equals(warning.ruleId())));
    }

    @Test
    void flagsMedicalScopeLanguage() {
        ValidationResult result = validate(
                List.of(item("f_rice_001", "米饭", 250, "grains"),
                        item("f_broccoli_001", "西兰花", 250, "vegetables"),
                        item("f_chicken_001", "鸡胸肉", 150, "protein")),
                EMPTY_SETTINGS,
                "根据你的情况，诊断为糖尿病，需要严格控制饮食。"
        );

        assertFalse(result.passed());
        assertTrue(result.failedRules().stream().anyMatch(rule -> "R08_SCOPE".equals(rule.ruleId())));
    }

    private ValidationResult validate(List<PlanItem> items, UserSettings settings, String explanation) {
        return validator.validate(new PlanValidator.ValidationInput(
                items,
                java.util.Map.of(),
                settings.dailyTargets(),
                Targets.ALL_MEALS,
                "lunch",
                settings,
                null,
                explanation
        ));
    }

    private static PlanItem item(String foodId, String name, double amountG, String group) {
        return new PlanItem(foodId, null, name, amountG, group, List.of(), List.of(), null, List.of());
    }
}
