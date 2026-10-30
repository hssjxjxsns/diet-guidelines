package com.dietguide.context;

import com.dietguide.domain.Food;
import com.dietguide.domain.GuidelineChunk;
import com.dietguide.domain.InferredTrend;
import com.dietguide.domain.PlanItem;
import com.dietguide.domain.PlanningContext;
import com.dietguide.domain.RecentTrend;
import com.dietguide.domain.StructureResult;
import com.dietguide.domain.UserSettings;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PlanningContextBuilder {

    public record Input(
            LocalDate mealDate,
            String mealType,
            Map<String, Double> dailyTargets,
            Map<String, Double> mealTarget,
            Map<String, Double> consumed,
            Map<String, Double> remaining,
            List<String> unloggedMeals,
            StructureResult structure,
            List<RecentTrend> recentTrends,
            UserSettings settings,
            List<InferredTrend> inferredTrends,
            List<GuidelineChunk> guidelines,
            List<Food> candidateFoods,
            List<PlanItem> currentMenu
    ) {
    }

    private PlanningContextBuilder() {
    }

    public static PlanningContext build(Input input, ObjectMapper mapper) {
        List<InferredTrend> verifiedTrends = input.inferredTrends() == null
                ? List.of()
                : input.inferredTrends().stream().filter(trend -> "verified".equals(trend.status())).toList();

        Map<String, Object> settingsSnapshot = new LinkedHashMap<>();
        settingsSnapshot.put("preferences", input.settings().preferences());
        settingsSnapshot.put("allergens", input.settings().allergens());
        settingsSnapshot.put("avoid", input.settings().avoid());

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("mealDate", input.mealDate().toString());
        snapshot.put("mealType", input.mealType());
        snapshot.put("dailyTargets", input.dailyTargets());
        snapshot.put("mealTarget", input.mealTarget());
        snapshot.put("consumed", input.consumed());
        snapshot.put("remaining", input.remaining());
        snapshot.put("unloggedMeals", input.unloggedMeals());
        snapshot.put("structure", input.structure());
        snapshot.put("recentTrends", input.recentTrends());
        snapshot.put("settings", settingsSnapshot);
        snapshot.put("inferredTrends", verifiedTrends);
        snapshot.put("guidelineIds", input.guidelines().stream().map(GuidelineChunk::id).toList());
        snapshot.put("candidateFoodIds", input.candidateFoods().stream().map(Food::id).toList());

        List<Object> structureRows = new ArrayList<>();
        for (var group : input.structure().foodGroups()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("group", group.group());
            row.put("amountG", group.amountG());
            row.put("recommendedRangeG", group.recommendedRangeG());
            row.put("status", group.status());
            structureRows.add(row);
        }

        List<Object> foodRows = new ArrayList<>();
        for (Food food : input.candidateFoods()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("foodId", food.id());
            row.put("name", food.name());
            row.put("category", food.category());
            row.put("energyKcalPer100g", food.nutrientsPer100g() == null
                    ? null
                    : food.nutrientsPer100g().get("energyKcal"));
            row.put("tags", food.tags());
            row.put("allergens", food.allergens());
            foodRows.add(row);
        }

        List<Object> guidelineRows = new ArrayList<>();
        for (int index = 0; index < input.guidelines().size(); index += 1) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", "G" + (index + 1));
            row.put("content", input.guidelines().get(index).content());
            guidelineRows.add(row);
        }

        List<String> lines = new ArrayList<>();
        lines.add("规划日期：" + input.mealDate() + "，当前餐次：" + input.mealType());
        lines.add("本餐目标（本餐设计以此为准）：" + json(input.mealTarget(), mapper));
        lines.add("每日目标：" + json(input.dailyTargets(), mapper));
        lines.add("今日已摄入：" + json(input.consumed(), mapper));
        lines.add("今日剩余（仅作全天缺口提示，不用于本餐分配）：" + json(input.remaining(), mapper));
        lines.add("尚未记录的餐次：" + (input.unloggedMeals().isEmpty() ? "无" : String.join("、", input.unloggedMeals())));
        lines.add("膳食结构达成情况：" + json(structureRows, mapper));
        lines.add("近期趋势：" + (input.recentTrends() == null || input.recentTrends().isEmpty()
                ? "无" : json(input.recentTrends(), mapper)));
        lines.add("长期已验证趋势：" + (verifiedTrends.isEmpty() ? "无" : json(verifiedTrends, mapper)));
        lines.add("忌口与过敏原：" + json(settingsSnapshot, mapper));
        lines.add("候选食物（只能使用这些 foodId）：" + json(foodRows, mapper));
        lines.add("指南片段：" + json(guidelineRows, mapper));

        if (input.currentMenu() != null && !input.currentMenu().isEmpty()) {
            List<Object> menuRows = new ArrayList<>();
            for (PlanItem item : input.currentMenu()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", item.name());
                row.put("foodId", item.foodId());
                row.put("recipeId", item.recipeId());
                row.put("amountG", item.amountG());
                row.put("foodGroup", item.foodGroup());
                menuRows.add(row);
            }
            lines.add("当前餐单：" + json(menuRows, mapper));
        }

        String text = String.join("\n", lines);
        return new PlanningContext(text, (int) Math.ceil(text.length() / 1.8), snapshot);
    }

    private static String json(Object value, ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("cannot serialize planning context", error);
        }
    }
}
