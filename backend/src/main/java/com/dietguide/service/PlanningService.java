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
import com.dietguide.nutrition.StructureEvaluator;
import com.dietguide.nutrition.Targets;
import com.dietguide.shared.AppException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class PlanningService {

    private static final int MAX_REGENERATIONS = 2;

    public record CreatePlanInput(String userId, LocalDate mealDate, String mealType) {
    }

    public record RevisePlanInput(
            String userId,
            String sessionId,
            LocalDate mealDate,
            String mealType,
            List<PlanItem> currentMenu,
            ReplaceConstraints replaceConstraints,
            String userFeedback
    ) {
    }

    private final FoodRepository foods;
    private final RecordValidationService validation;
    private final AiGateway ai;
    private final GuidelineRetriever guidelines;
    private final PlanSessionStore sessions;
    private final PlanningContextLoader contextLoader;
    private final ObjectMapper mapper;

    public PlanningService(
            FoodRepository foods,
            RecordValidationService validation,
            AiGateway ai,
            GuidelineRetriever guidelines,
            PlanSessionStore sessions,
            PlanningContextLoader contextLoader,
            ObjectMapper mapper
    ) {
        this.foods = foods;
        this.validation = validation;
        this.ai = ai;
        this.guidelines = guidelines;
        this.sessions = sessions;
        this.contextLoader = contextLoader;
        this.mapper = mapper;
    }

    public PlanResult create(CreatePlanInput input) {
        GeneratedPlan plan = plan(input, null, null);
        String sessionId = sessions.save(new PlanSessionStore.PlanSessionDraft(
                input.userId(),
                input.mealDate(),
                input.mealType(),
                "planning",
                plan.context().snapshot(),
                plan.generated().validation(),
                plan.generated().items(),
                plan.generated().fallback() ? "RULE_FALLBACK" : "AI"
        ));
        return toResult(sessionId, plan, 1);
    }

    /** 换菜 = 同会话版本化修订：基于 currentMenu 最小改动，生成新 revision（可对比/回滚）。 */
    public PlanResult revise(RevisePlanInput input) {
        Map<String, Object> session = sessions.find(input.userId(), input.sessionId());
        if (session == null) {
            throw AppException.notFound("plan session");
        }
        if ("closed".equals(String.valueOf(session.get("status")))) {
            throw AppException.validation("会话已结束，不能再换菜");
        }

        GeneratedPlan plan = plan(
                new CreatePlanInput(input.userId(), input.mealDate(), input.mealType()),
                input.replaceConstraints(),
                input.currentMenu()
        );
        int revision = sessions.saveRevision(new PlanSessionStore.RevisionDraft(
                input.userId(),
                input.sessionId(),
                sessions.currentRevision(input.userId(), input.sessionId()),
                plan.generated().fallback() ? "RULE_FALLBACK" : "AI",
                plan.generated().items(),
                input.replaceConstraints(),
                input.userFeedback(),
                plan.generated().validation()
        ));
        return toResult(input.sessionId(), plan, revision);
    }

    private PlanResult toResult(String sessionId, GeneratedPlan plan, int revision) {
        Generated generated = plan.generated();
        return new PlanResult(
                sessionId,
                generated.items(),
                generated.explanation(),
                generated.citations(),
                plan.guidelineRefs(),
                generated.validation(),
                plan.gap(),
                generated.fallback(),
                (generated.fallback() ? "RULE_FALLBACK" : "AI") + ":" + revision
        );
    }

    private GeneratedPlan plan(
            CreatePlanInput input,
            ReplaceConstraints replaceConstraints,
            List<PlanItem> currentMenu
    ) {
        PlanningContext.UserContext userContext = contextLoader.load(
                input.userId(),
                input.mealDate(),
                input.mealType()
        );

        // 全天缺口仅作为提示信息（gap 字段），不用于本餐分配
        GapResult gap = NutritionEngine.computeGap(
                userContext.dailyTargets(),
                userContext.consumed(),
                userContext.unloggedMeals(),
                Targets.NUTRIENT_TOLERANCE
        );

        // 本餐目标 = 全天目标 × 餐次权重；本餐生成以此为准
        Map<String, Double> mealTarget = Targets.mealTarget(input.mealType(), userContext.dailyTargets());

        List<Food> candidates = foods.listActive(40);
        String preferences = String.join(" ", userContext.settings().preferences() == null
                ? List.of() : userContext.settings().preferences());
        var retrieved = guidelines.retrieve(input.mealType() + " 膳食结构 " + preferences, 4);

        PlanningContext context = PlanningContextBuilder.build(
                new PlanningContextBuilder.Input(
                        input.mealDate(),
                        input.mealType(),
                        userContext.dailyTargets(),
                        mealTarget,
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

        List<GuidelineRef> guidelineRefs = retrieved.stream()
                .map(chunk -> new GuidelineRef(
                        chunk.id(),
                        String.valueOf(chunk.metadata().getOrDefault("title", chunk.id())),
                        chunk.score()
                ))
                .toList();

        return new GeneratedPlan(generated, context, gap, guidelineRefs);
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
            ValidationResult result = validation.validateForPlan(
                    items,
                    userContext.consumed(),
                    userContext.dailyTargets(),
                    userContext.unloggedMeals(),
                    input.mealType(),
                    userContext.settings(),
                    replaceConstraints,
                    draft.explanation()
            );

            lastValidation = result;
            if (result.passed()) {
                return new Generated(
                        items,
                        draft.explanation() == null ? "" : draft.explanation(),
                        draft.citations() == null ? List.of() : draft.citations(),
                        result,
                        false
                );
            }

            failedRules = result.failedRules().stream()
                    .filter(rule -> "hard".equals(rule.severity()))
                    .toList();
        }

        Fallback fallback = buildFallback(input, userContext, candidates, replaceConstraints);
        ValidationResult result = fallback.validation() != null
                ? fallback.validation()
                : lastValidation != null
                ? lastValidation
                : new ValidationResult(false, List.of(), List.of(),
                new ValidationResult.ComputedRanges(List.of(0.0, 0.0), null, null), "fallback");

        return new Generated(fallback.items(), fallback.explanation(), List.of(), result, true);
    }

    /** 规则兜底：按本餐目标（StructureEvaluator 每日区间 × 餐次权重）挑食物，标 source=RULE_FALLBACK。 */
    private Fallback buildFallback(
            CreatePlanInput input,
            PlanningContext.UserContext userContext,
            List<Food> candidates,
            ReplaceConstraints replaceConstraints
    ) {
        List<PlanItem> items = new ArrayList<>();
        double share = Targets.mealWeight(input.mealType());
        String[] groups = {"vegetables", "protein", "grains"};
        for (String group : groups) {
            var range = StructureEvaluator.DAILY_STRUCTURE_RANGES.get(group);
            double amount = range == null ? 100 : Math.round((range.get(0) + range.get(1)) / 2 * share);
            Food food = pick(candidates, group, replaceConstraints);
            if (food == null) {
                continue;
            }
            items.add(new PlanItem(
                    food.id(),
                    null,
                    food.name(),
                    amount,
                    food.category(),
                    food.tags() == null ? List.of() : food.tags(),
                    food.allergens() == null ? List.of() : food.allergens(),
                    "规则引擎兜底组合：按本餐目标优先保证蔬菜、优质蛋白与主食（source=RULE_FALLBACK）",
                    List.of()
            ));
        }

        ValidationResult result = validation.validateForPlan(
                items,
                userContext.consumed(),
                userContext.dailyTargets(),
                userContext.unloggedMeals(),
                input.mealType(),
                userContext.settings(),
                replaceConstraints,
                ""
        );

        return new Fallback(
                items,
                "AI 规划未通过校验，已由规则引擎按本餐目标给出结构优先的兜底组合。",
                result
        );
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

    private record GeneratedPlan(
            Generated generated,
            PlanningContext context,
            GapResult gap,
            List<GuidelineRef> guidelineRefs
    ) {
    }

    private record Fallback(List<PlanItem> items, String explanation, ValidationResult validation) {
    }
}
