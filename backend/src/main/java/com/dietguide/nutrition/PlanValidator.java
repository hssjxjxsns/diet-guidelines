package com.dietguide.nutrition;

import com.dietguide.domain.PlanItem;
import com.dietguide.nutrition.FoodRepository;
import com.dietguide.domain.ReplaceConstraints;
import com.dietguide.domain.UserSettings;
import com.dietguide.domain.ValidationResult;

import java.util.List;
import java.util.Map;

/**
 * 配餐侧校验入口：薄壳，仅做 PlanItem → RuleItem 归一化，
 * 全部规则执行委托给共享 {@link RuleEngine}（与记录侧同一实例/规则表）。
 */
public class PlanValidator {

    public record ValidationInput(
            List<PlanItem> items,
            Map<String, Double> consumed,
            Map<String, Double> dailyTargets,
            List<String> unloggedMeals,
            String currentMeal,
            UserSettings settings,
            ReplaceConstraints replaceConstraints,
            String explanation
    ) {
    }

    private final RuleEngine engine;

    public PlanValidator(FoodRepository foods, NutritionEngine nutritionEngine) {
        this(new RuleEngine(foods, nutritionEngine));
    }

    public PlanValidator(RuleEngine engine) {
        this.engine = engine;
    }

    public ValidationResult validate(ValidationInput input) {
        List<RuleEngine.RuleItem> items = new java.util.ArrayList<>();
        for (PlanItem item : input.items() == null ? List.<PlanItem>of() : input.items()) {
            items.add(new RuleEngine.RuleItem(
                    item.name(),
                    item.foodId(),
                    item.recipeId(),
                    item.amountG(),
                    item.tags(),
                    item.allergens(),
                    item.foodGroup(),
                    null,
                    null,
                    null
            ));
        }

        return engine.runChecks(new RuleEngine.CheckInput(
                items,
                input.consumed(),
                input.dailyTargets(),
                input.unloggedMeals(),
                input.currentMeal(),
                input.settings(),
                input.replaceConstraints(),
                input.explanation()
        ));
    }
}
