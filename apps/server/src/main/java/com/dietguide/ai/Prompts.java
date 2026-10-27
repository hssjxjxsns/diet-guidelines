package com.dietguide.ai;

import com.dietguide.domain.Food;
import com.dietguide.domain.PlanItem;
import com.dietguide.domain.ReplaceConstraints;
import com.dietguide.domain.ValidationResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Prompts {

    public static final String PLAN_SYSTEM_PROMPT = """
            你是《中国居民膳食指南（2022）》方向的膳食规划助手。你的任务是根据给定的结构化上下文推荐一餐菜品。

            必须遵守：
            1. 只使用上下文中提供的食物候选及其 foodId，或引用已有 recipeId；不得编造食物编号。
            2. 同时满足定量营养约束与膳食结构要求，不能只按能量或蛋白质选菜。
            3. 输出必须是一个 JSON 对象，不要输出 Markdown 或解释性前后缀。
            4. 每道菜必须给出推荐理由 reason，并引用提供的指南片段编号。
            5. 不判断用户是否患有疾病，不给出诊断或治疗方案。
            6. 如果健康数据不足，明确说明依据有限，不做确定性结论。

            输出 JSON 结构：
            {
              "items": [
                {
                  "foodId": "可选，食物库 id",
                  "recipeId": "可选，配方 id",
                  "name": "菜名",
                  "amountG": 150,
                  "foodGroup": "grains|vegetables|fruits|protein|dairy|soy_nuts|oils|other",
                  "tags": ["vegetarian", "spicy"],
                  "allergens": ["花生"],
                  "reason": "推荐理由",
                  "citations": ["G1"]
                }
              ],
              "explanation": "整餐说明，200 字以内",
              "citations": ["G1"]
            }
            """;

    public static final String DISH_ANALYSIS_SYSTEM_PROMPT = """
            你是菜品营养分析助手。根据用户输入或照片，识别菜品并拆解为可核算的结构。

            要求：
            1. 优先匹配给定的食物候选；匹配到则返回 matchedFoodId。
            2. 复合菜品推断原料、用量、用油量、烹饪方式、成品份数，输出 recipeProposal。
            3. 无法拆解时，给出 directEstimate 能量/蛋白质区间，而不是单点精确值。
            4. 必须输出 confidence（0-1）和 basis（识别依据）。
            5. 不判断疾病，不做诊断。
            6. 只输出 JSON 对象。

            输出 JSON 结构：
            {
              "dishName": "菜名",
              "matchedFoodId": "可选",
              "recipeProposal": {
                "ingredients": [{ "name": "猪肋排", "foodId": "可选", "amountG": 120 }],
                "oilG": 15,
                "cookingMethod": "红烧",
                "servings": 1,
                "yieldG": 150
              },
              "directEstimate": { "energyKcal": [300, 420], "proteinG": [12, 18], "confidence": 0.6 },
              "confidence": 0.7,
              "basis": ["识别到糖醋汁", "按常见配方估算用油 15g"],
              "needsUserConfirm": false
            }
            """;

    public static final String PERIOD_SUMMARY_SYSTEM_PROMPT = """
            你是膳食记录总结助手。只根据给定的统计数字写一段 150 字以内的总结，不生成新数字，不做疾病判断，给出一般性膳食建议。只输出 JSON：{"summary":"..."}
            """;

    private Prompts() {
    }

    public static String buildPlanUserPrompt(
            String contextText,
            List<ValidationResult.Violation> failedRules,
            ReplaceConstraints replaceConstraints,
            ObjectMapper mapper
    ) {
        List<String> sections = new ArrayList<>();
        sections.add("以下是规划上下文：");
        sections.add(contextText);

        if (replaceConstraints != null) {
            sections.add("本次替换必须满足的结构化约束：");
            sections.add(toJson(replaceConstraints, mapper));
        }

        if (failedRules != null && !failedRules.isEmpty()) {
            List<Map<String, Object>> simplified = new ArrayList<>();
            for (ValidationResult.Violation rule : failedRules) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("ruleId", rule.ruleId());
                item.put("message", rule.message());
                item.put("offendingItems", rule.offendingItems());
                item.put("expected", rule.expected());
                item.put("actual", rule.actual());
                simplified.add(item);
            }
            sections.add("上一轮校验未通过，请针对以下失败项调整，不要重复同样错误：");
            sections.add(toJson(simplified, mapper));
        }

        sections.add("只输出 JSON 对象。");
        return String.join("\n", sections);
    }

    public static String buildDishUserPrompt(
            String text,
            List<String> hints,
            String imageUrl,
            List<Food> candidates,
            ObjectMapper mapper
    ) {
        List<String> lines = new ArrayList<>();
        lines.add("用户输入：" + (text == null || text.isBlank() ? "（见图片）" : text));
        if (hints != null && !hints.isEmpty()) {
            lines.add("补充信息：" + String.join("；", hints));
        }
        if (imageUrl != null && !imageUrl.isBlank()) {
            lines.add("图片地址：" + imageUrl);
        }
        lines.add("食物候选（只能使用这些 id）：");

        List<Map<String, Object>> options = new ArrayList<>();
        for (Food food : candidates) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("foodId", food.id());
            item.put("name", food.name());
            item.put("aliases", food.aliases());
            item.put("category", food.category());
            item.put("energyKcalPer100g", food.nutrientsPer100g() == null ? null : food.nutrientsPer100g().get("energyKcal"));
            item.put("tags", food.tags());
            item.put("allergens", food.allergens());
            options.add(item);
        }
        lines.add(toJson(options, mapper));
        lines.add("只输出 JSON 对象。");
        return String.join("\n", lines);
    }

    private static String toJson(Object value, ObjectMapper mapper) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("cannot serialize prompt payload", error);
        }
    }

    public static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public static List<PlanItem> normalizeItems(List<PlanItem> items) {
        if (items == null) {
            return List.of();
        }
        List<PlanItem> normalized = new ArrayList<>();
        for (PlanItem item : items) {
            normalized.add(new PlanItem(
                    item.foodId(),
                    item.recipeId(),
                    item.name(),
                    item.amountG(),
                    item.foodGroup(),
                    item.tags() == null ? List.of() : item.tags(),
                    item.allergens() == null ? List.of() : item.allergens(),
                    item.reason(),
                    item.citations() == null ? List.of() : item.citations()
            ));
        }
        return normalized;
    }
}
