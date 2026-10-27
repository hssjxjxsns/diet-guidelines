package com.dietguide.service;

import com.dietguide.domain.MealPlanDraft;
import com.dietguide.domain.PlanItem;
import com.dietguide.domain.PlanResult;
import com.dietguide.domain.UserSettings;
import com.dietguide.nutrition.NutritionEngine;
import com.dietguide.nutrition.PlanValidator;
import com.dietguide.nutrition.Targets;
import com.dietguide.support.FakeAiGateway;
import com.dietguide.support.Fixtures;
import com.dietguide.support.MemoryFoodRepository;
import com.dietguide.support.MemoryPlanSessionStore;
import com.dietguide.support.StubPlanningDependencies;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanningServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 27);
    private static final UserSettings SETTINGS =
            new UserSettings(Targets.DEFAULT_DAILY_TARGETS, List.of("清淡"), List.of(), List.of());

    private final ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private final MemoryFoodRepository foods = Fixtures.repository();
    private final PlanValidator validator = new PlanValidator(foods, new NutritionEngine(foods));

    @Test
    void returnsValidatedPlanAndPersistsSession() {
        MealPlanDraft draft = new MealPlanDraft(
                List.of(item("f_rice_001", "米饭", 250),
                        item("f_broccoli_001", "西兰花", 250),
                        item("f_chicken_001", "鸡胸肉", 150)),
                "荤素搭配，蔬菜充足",
                List.of("G1")
        );

        FakeAiGateway ai = new FakeAiGateway().enqueuePlan(draft);
        MemoryPlanSessionStore sessions = new MemoryPlanSessionStore();
        PlanningService service = service(ai, sessions);

        PlanResult result = service.create(new PlanningService.CreatePlanInput("u1", DATE, "lunch"));

        assertFalse(result.fallback());
        assertTrue(result.validation().passed());
        assertEquals(3, result.items().size());
        assertEquals(1, sessions.drafts().size());
    }

    @Test
    void fallsBackToRuleEngineWhenAiKeepsFailingValidation() {
        PlanItem peanut = item("f_peanut_001", "花生", 100);
        MealPlanDraft draft = new MealPlanDraft(List.of(peanut), "加一份花生", List.of());
        FakeAiGateway ai = new FakeAiGateway().enqueuePlan(draft).enqueuePlan(draft).enqueuePlan(draft);

        UserSettings allergic = new UserSettings(
                Targets.DEFAULT_DAILY_TARGETS,
                List.of(),
                List.of("花生"),
                List.of()
        );
        MemoryPlanSessionStore sessions = new MemoryPlanSessionStore();
        PlanningService service = new PlanningService(
                foods,
                validator,
                ai,
                StubPlanningDependencies.guidelineRetriever(),
                sessions,
                StubPlanningDependencies.contextLoader(allergic),
                mapper
        );

        PlanResult result = service.create(new PlanningService.CreatePlanInput("u1", DATE, "lunch"));

        assertTrue(result.fallback());
        assertFalse(result.items().isEmpty());
        assertTrue(result.items().stream().noneMatch(item -> "f_peanut_001".equals(item.foodId())));
    }

    private PlanningService service(FakeAiGateway ai, MemoryPlanSessionStore sessions) {
        return new PlanningService(
                foods,
                validator,
                ai,
                StubPlanningDependencies.guidelineRetriever(),
                sessions,
                StubPlanningDependencies.contextLoader(SETTINGS),
                mapper
        );
    }

    private static PlanItem item(String foodId, String name, double amountG) {
        return new PlanItem(foodId, null, name, amountG, null, List.of(), List.of(), "推荐理由", List.of("G1"));
    }
}
