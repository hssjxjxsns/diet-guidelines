package com.dietguide.service;

import com.dietguide.ai.AiGateway;
import com.dietguide.ai.Prompts;
import com.dietguide.context.PlanningContextBuilder;
import com.dietguide.domain.Food;
import com.dietguide.domain.GapResult;
import com.dietguide.domain.GuidelineRef;
import com.dietguide.domain.MealPlanDraft;
import com.dietguide.domain.PlanItem;
import com.dietguide.domain.PlanResult;
import com.dietguide.domain.PlanningContext;
import com.dietguide.domain.ReplaceConstraints;
import com.dietguide.domain.ValidationResult;
import com.dietguide.nutrition.FoodRepository;
import com.dietguide.nutrition.NutritionEngine;
import com.dietguide.nutrition.PlanValidator;
import com.dietguide.nutrition.Targets;
import com.dietguide.shared.AppException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PlanningService {

    private static final int MAX_REGENERATIONS = 2;

    public record CreatePlanInput(String userId, LocalDate mealDate, String mealType) {
    }

    public record RevisePlanInput(
            String userId,
            LocalDate mealDate,
            String mealType,
            List<PlanItem> currentMenu,
            ReplaceConstraints replaceConstraints
    ) {
    }

    private final FoodRepository foods;
    private final PlanValidator validator;
    private final AiGateway ai;
    private final GuidelineRetriever guidelines;
    private final PlanSessionStore sessions;
    private final PlanningContextLoader contextLoader;
    private final ObjectMapper mapper;

    public PlanningService(
            FoodRepository foods,
            PlanValidator validator,
            AiGateway ai,
            GuidelineRetriever guidelines,
            PlanSessionStore sessions,
            PlanningContextLoader contextLoader,
            ObjectMapper mapper
    ) {
        this.foods = foods;
        this.validator = validator;
        this.ai = ai;
        this.guidelines = guidelines;
        this.sessions = sessions;
        this.contextLoader = contextLoader;
        this.mapper = mapper;
    }

    public PlanResult create(CreatePlanInput input) {
        return generate(input, null, null);
    }

    public PlanResult revise(RevisePlanInput input) {
        return generate(
                new CreatePlanInput(input.userId(), input.mealDate(), input.mealType()),
                input.replaceConstraints(),
                input.currentMenu()
        );
    }

    private PlanResult generate(
            CreatePlanInput input,
            ReplaceConstraints replaceConstraints,
            List<PlanItem> currentMenu
    ) {
        PlanningContext.UserContext userContext = contextLoader.load(
                input.userId(),
                input.mealDate(),
                input.mealType()
        );

        GapResult gap = NutritionEngine.computeGap(
                userContext.dailyTargets(),
                userContext.consumed(),
                userContext.unloggedMeals(),
                Targets.NUTRIENT_TOLERANCE
        );

        List<Food> candidates = foods.listActive(40);
        String preferences = String.join(" ", userContext.settings().preferences() == null
                ? List.of() : userContext.settings().preferences());
        var retrieved = guidelines.retrieve(input.mealType() + " 膳食结构 " + preferences, 4);

        PlanningContext context = PlanningContextBuilder.build(
                new PlanningContextBuilder.Input(
                        input.mealDate(),
                        input.mealType(),
                        userContext.dailyTargets(),
                        userContext.consumed(),
                        gap.remaining(),
                        userContext.unloggedMeals(),
                        userContext.structure(),
                        userContext.recentTrends(),
                        userContext.settings(),
                        userContext.inferredTrends(),
                        retrieved,
                        candidates,
                        currentMenu
                ),
                mapper
        );

        Generated generated = generateAndValidate(input, userContext, context.text(), replaceConstraints, candidates);

        String sessionId = sessions.save(new PlanSessionStore.PlanSessionDraft(
                input.userId(),
                input.mealDate(),
                input.mealType(),
                "planning",
                context.snapshot(),
                generated.validation(),
                generated.items()
        ));

        return new PlanResult(
                sessionId,
                generated.items(),
                generated.explanation(),
                generated.citations(),
                retrieved.stream()
                        .map(chunk -> new GuidelineRef(
                                chunk.id(),
                                String.valueOf(chunk.metadata().getOrDefault("title", chunk.id())),
                                chunk.score()
                        ))
                        .toList(),
                generated.validation(),
                gap,
                generated.fallback()
        );
    }

    private Generated generateAndValidate(
            CreatePlanInput input,
            PlanningContext.UserContext userContext,
            String contextText,
            ReplaceConstraints replaceConstraints,
            List<Food> candidates
    ) {
        List<ValidationResult.Violation> failedRules = null;
        ValidationResult lastValidation = null;

        for (int attempt = 0; attempt <= MAX_REGENERATIONS; attempt += 1) {
            MealPlanDraft draft = ai.chatJson(
                    new AiGateway.ChatRequest(
                            replaceConstraints != null ? "meal_replace" : "meal_plan",
                            Prompts.PLAN_SYSTEM_PROMPT,
                            Prompts.buildPlanUserPrompt(contextText, failedRules, replaceConstraints, mapper),
                            2400,
                            0.3,
                            null
                    ),
                    MealPlanDraft.class
            );

            List<PlanItem> items = Prompts.normalizeItems(draft.items());
            ValidationResult validation = validator.validate(new PlanValidator.ValidationInput(
                    items,
                    userContext.consumed(),
                    userContext.dailyTargets(),
                    userContext.unloggedMeals(),
                    input.mealType(),
                    userContext.settings(),
                    replaceConstraints,
                    draft.explanation()
            ));

            lastValidation = validation;
            if (validation.passed()) {
                return new Generated(
                        items,
                        draft.explanation() == null ? "" : draft.explanation(),
                        draft.citations() == null ? List.of() : draft.citations(),
                        validation,
                        false
                );
            }

            failedRules = validation.failedRules().stream()
                    .filter(rule -> "hard".equals(rule.severity()))
                    .toList();
        }

        Fallback fallback = buildFallback(input, userContext, candidates, replaceConstraints);
        ValidationResult validation = fallback.validation() != null
                ? fallback.validation()
                : lastValidation != null
                ? lastValidation
                : new ValidationResult(false, List.of(), List.of(),
                new ValidationResult.ComputedRanges(List.of(0.0, 0.0), null, null), "fallback");

        return new Generated(fallback.items(), fallback.explanation(), List.of(), validation, true);
    }

    private Fallback buildFallback(
            CreatePlanInput input,
            PlanningContext.UserContext userContext,
            List<Food> candidates,
            ReplaceConstraints replaceConstraints
    ) {
        List<PlanItem> items = new ArrayList<>();
        String[] groups = {"vegetables", "protein", "grains"};
        double[] amounts = {150, 100, 100};

        for (int index = 0; index < groups.length; index += 1) {
            Food food = pick(candidates, groups[index], replaceConstraints);
            if (food == null) {
                continue;
            }
            items.add(new PlanItem(
                    food.id(),
                    null,
                    food.name(),
                    amounts[index],
                    food.category(),
                    food.tags() == null ? List.of() : food.tags(),
                    food.allergens() == null ? List.of() : food.allergens(),
                    "规则引擎兜底组合：优先保证蔬菜、优质蛋白与主食",
                    List.of()
            ));
        }

        ValidationResult validation = validator.validate(new PlanValidator.ValidationInput(
                items,
                userContext.consumed(),
                userContext.dailyTargets(),
                userContext.unloggedMeals(),
                input.mealType(),
                userContext.settings(),
                replaceConstraints,
                ""
        ));

        return new Fallback(items, "AI 规划未通过校验，已由规则引擎给出结构优先的兜底组合。", validation);
    }

    private static Food pick(List<Food> candidates, String group, ReplaceConstraints constraints) {
        for (Food food : candidates) {
            if (!"active".equals(food.status())) {
                continue;
            }
            if (!group.equals(food.category())) {
                continue;
            }
            List<String> tags = food.tags() == null ? List.of() : food.tags();
            if (constraints != null && Boolean.TRUE.equals(constraints.vegetarian()) && !tags.contains("vegetarian")) {
                continue;
            }
            if (constraints != null && Boolean.TRUE.equals(constraints.noSpicy()) && tags.contains("spicy")) {
                continue;
            }
            return food;
        }
        return null;
    }

    private record Generated(
            List<PlanItem> items,
            String explanation,
            List<String> citations,
            ValidationResult validation,
            boolean fallback
    ) {
    }

    private record Fallback(List<PlanItem> items, String explanation, ValidationResult validation) {
    }
}
