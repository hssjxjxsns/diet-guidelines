package com.dietguide.service;

import com.dietguide.domain.MealDraftItem;
import com.dietguide.domain.MealGroup;
import com.dietguide.domain.MealInputItem;
import com.dietguide.domain.SubmitMealInput;
import com.dietguide.domain.SubmitMealResult;
import com.dietguide.domain.UserSettings;
import com.dietguide.nutrition.NutritionEngine;
import com.dietguide.nutrition.RuleEngine;
import com.dietguide.shared.AppException;
import com.dietguide.support.Fixtures;
import com.dietguide.support.MemoryFoodRepository;
import com.dietguide.support.MemoryMealGroupStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MealGroupServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 27);
    private static final UserSettings EMPTY_SETTINGS =
            new UserSettings(com.dietguide.nutrition.Targets.DEFAULT_DAILY_TARGETS, List.of(), List.of(), List.of());

    private final ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private final MemoryFoodRepository foods = Fixtures.repository();
    private final MemoryMealGroupStore store = new MemoryMealGroupStore();
    private final NutritionEngine engine = new NutritionEngine(foods);
    private final MealGroupService service = new MealGroupService(
            store,
            engine,
            new DailyStatsAggregator(engine),
            new RecordValidationService(new RuleEngine(foods, engine)),
            new SettingsStore() {
                @Override
                public UserSettings getSettings(String userId) {
                    return EMPTY_SETTINGS;
                }

                @Override
                public void saveSettings(String userId, UserSettings value) {
                }
            },
            mapper);

    @Test
    void appendsThenReplacesAndKeepsOnlyReplaceEffective() {
        SubmitMealResult first = service.submit(input("append", null, "key-1", item("f_rice_001", "米饭", 200)));
        assertEquals(1, first.entry().revision());
        assertEquals(232.0, first.dailyTotals().get("energyKcal"), 0.001);

        SubmitMealResult second = service.submit(input("replace", 1, "key-2", item("f_rice_001", "米饭", 100)));
        assertEquals(2, second.entry().revision());
        assertEquals(1, second.effectiveEntries().size());
        assertEquals(116.0, second.dailyTotals().get("energyKcal"), 0.001);
    }

    @Test
    void rejectsStaleBaseRevision() {
        service.submit(input("append", null, "key-1", item("f_rice_001", "米饭", 200)));

        AppException error = assertThrows(
                AppException.class,
                () -> service.submit(input("replace", 0, "key-2", item("f_rice_001", "米饭", 100)))
        );

        assertEquals("MEAL_REVISION_CONFLICT", error.code());
    }

    @Test
    void replaysCachedResultForSameIdempotencyKey() {
        SubmitMealResult first = service.submit(input("append", null, "key-x", item("f_rice_001", "米饭", 200)));
        SubmitMealResult replay = service.submit(input("append", null, "key-x", item("f_rice_001", "米饭", 200)));

        assertEquals(first.entry().id(), replay.entry().id());
        assertEquals(1, replay.entry().revision());
    }

    private static SubmitMealInput input(String mode, Integer baseRevision, String key, MealInputItem item) {
        return new SubmitMealInput("u1", DATE, "lunch", 1, mode, baseRevision, key, List.of(item), "test-1");
    }

    private static MealInputItem item(String foodId, String name, double amountG) {
        return new MealInputItem(foodId, null, name, amountG, null, null);
    }
}
