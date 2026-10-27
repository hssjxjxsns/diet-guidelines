package com.dietguide.nutrition;

import com.dietguide.domain.Food;
import com.dietguide.domain.FoodGroupStatus;
import com.dietguide.domain.PlanItem;
import com.dietguide.domain.Recipe;
import com.dietguide.domain.ReplaceConstraints;
import com.dietguide.domain.UserSettings;
import com.dietguide.domain.ValidationResult;
import com.dietguide.shared.Nutrients;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class PlanValidator {

    public record ValidationConfig(
            double tolerance,
            Map<String, Double> minVegetableG,
            Map<String, Double> minDistinctFoods,
            List<String> scopeKeywords,
            String ruleVersion
    ) {

        public static ValidationConfig defaults() {
            Map<String, Double> minVegetable = new LinkedHashMap<>();
            minVegetable.put("breakfast", 50.0);
            minVegetable.put("lunch", 100.0);
            minVegetable.put("dinner", 100.0);
            minVegetable.put("snack", 0.0);

            Map<String, Double> minDistinct = new LinkedHashMap<>();
            minDistinct.put("breakfast", 2.0);
            minDistinct.put("lunch", 3.0);
            minDistinct.put("dinner", 3.0);
            minDistinct.put("snack", 1.0);

            return new ValidationConfig(
                    Targets.NUTRIENT_TOLERANCE,
                    Map.copyOf(minVegetable),
                    Map.copyOf(minDistinct),
                    List.of("诊断为", "患有", "确诊", "治疗方案"),
                    "2026-10-26.1"
            );
        }
    }

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

    private record ExpandedItem(
            PlanItem item,
            boolean valid,
            List<String> allergens,
            List<String> tags,
            String foodGroup,
            Double energyKcal,
            Double proteinG,
            double vegetableG
    ) {
    }

    private final FoodRepository foods;
    private final NutritionEngine engine;
    private final ValidationConfig config;

    public PlanValidator(FoodRepository foods, NutritionEngine engine) {
        this(foods, engine, ValidationConfig.defaults());
    }

    public PlanValidator(FoodRepository foods, NutritionEngine engine, ValidationConfig config) {
        this.foods = foods;
        this.engine = engine;
        this.config = config;
    }

    public ValidationResult validate(ValidationInput input) {
        List<ExpandedItem> expanded = expand(input.items());
        List<ValidationResult.Violation> failedRules = new ArrayList<>();
        List<ValidationResult.Warning> warnings = new ArrayList<>();

        List<String> allergens = input.settings() == null ? List.of() : safeList(input.settings().allergens());
        List<String> avoid = input.settings() == null ? List.of() : safeList(input.settings().avoid());

        checkAllergens(expanded, allergens, failedRules);
        checkAvoid(expanded, avoid, failedRules);
        checkFoodValidity(expanded, failedRules);

        Map<String, Double> totals = totalNutrients(expanded);
        NutritionEngine.MealRange range = NutritionEngine.computeMealRange(
                input.dailyTargets(),
                input.consumed(),
                input.unloggedMeals(),
                input.currentMeal(),
                config.tolerance()
        );

        checkNutrientRange(totals, range, failedRules, warnings);
        checkStructure(expanded, input.currentMeal(), failedRules, warnings);
        checkReplaceRequest(expanded, totals, input.replaceConstraints(), failedRules);
        checkDuplicates(expanded, failedRules, warnings);
        checkScope(input.explanation(), failedRules);

        boolean passed = failedRules.stream().noneMatch(rule -> "hard".equals(rule.severity()));

        return new ValidationResult(
                passed,
                failedRules,
                warnings,
                new ValidationResult.ComputedRanges(
                        range.rangeKcal(),
                        List.of(range.minProteinG(), range.expectedProteinG()),
                        null
                ),
                config.ruleVersion()
        );
    }

    private List<ExpandedItem> expand(List<PlanItem> items) {
        List<ExpandedItem> expanded = new ArrayList<>();

        for (PlanItem item : safeList(items)) {
            NutritionEngine.ComputedItem computed = engine.computeItem(
                    new com.dietguide.domain.MealInputItem(
                            item.foodId(),
                            item.recipeId(),
                            item.name(),
                            item.amountG(),
                            null,
                            null
                    ),
                    "lunch"
            );

            Set<String> allergens = new LinkedHashSet<>(safeList(item.allergens()));
            Set<String> tags = new LinkedHashSet<>(safeList(item.tags()));
            String foodGroup = item.foodGroup();
            boolean valid = item.foodId() != null || item.recipeId() != null;

            if (item.foodId() != null) {
                Food food = foods.findById(item.foodId());
                if (food != null) {
                    valid = true;
                    foodGroup = food.category();
                    safeList(food.allergens()).forEach(allergens::add);
                    safeList(food.tags()).forEach(tags::add);
                }
            }

            if (item.recipeId() != null) {
                Recipe recipe = foods.getRecipe(item.recipeId());
                if (recipe != null) {
                    valid = true;
                    safeList(recipe.allergens()).forEach(allergens::add);
                    safeList(recipe.tags()).forEach(tags::add);
                    for (Recipe.RecipeIngredient ingredient : safeList(recipe.ingredients())) {
                        Food food = foods.findById(ingredient.foodId());
                        if (food == null) {
                            continue;
                        }
                        safeList(food.allergens()).forEach(allergens::add);
                        safeList(food.tags()).forEach(tags::add);
                    }
                }
            }

            if (item.foodId() == null && item.recipeId() == null) {
                Food byName = foods.findByName(item.name());
                if (byName != null) {
                    valid = true;
                    foodGroup = byName.category();
                    safeList(byName.allergens()).forEach(allergens::add);
                    safeList(byName.tags()).forEach(tags::add);
                }
            }

            expanded.add(new ExpandedItem(
                    item,
                    valid,
                    List.copyOf(allergens),
                    List.copyOf(tags),
                    foodGroup,
                    computed.perItem().values().get("energyKcal"),
                    computed.perItem().values().get("proteinG"),
                    "vegetables".equals(foodGroup) ? item.amountG() : 0
            ));
        }

        return expanded;
    }

    private void checkAllergens(
            List<ExpandedItem> expanded,
            List<String> allergens,
            List<ValidationResult.Violation> failedRules
    ) {
        if (allergens.isEmpty()) {
            return;
        }
        List<String> normalized = allergens.stream().map(PlanValidator::normalize).toList();

        for (ExpandedItem entry : expanded) {
            List<String> hits = entry.allergens().stream()
                    .filter(allergen -> normalized.stream().anyMatch(target ->
                            normalize(allergen).contains(target) || target.contains(normalize(allergen))))
                    .toList();
            if (!hits.isEmpty()) {
                failedRules.add(new ValidationResult.Violation(
                        "R01_ALLERGEN",
                        "hard",
                        entry.item().name() + " 命中过敏原：" + String.join("、", hits),
                        List.of(entry.item().name()),
                        allergens,
                        entry.allergens()
                ));
            }
        }
    }

    private void checkAvoid(
            List<ExpandedItem> expanded,
            List<String> avoid,
            List<ValidationResult.Violation> failedRules
    ) {
        if (avoid.isEmpty()) {
            return;
        }
        List<String> normalized = avoid.stream().map(PlanValidator::normalize).toList();

        for (ExpandedItem entry : expanded) {
            List<String> candidates = new ArrayList<>();
            candidates.add(normalize(entry.item().name()));
            entry.tags().forEach(tag -> candidates.add(normalize(tag)));

            boolean hit = candidates.stream().anyMatch(candidate ->
                    normalized.stream().anyMatch(target ->
                            candidate.contains(target) || target.contains(candidate)));
            if (hit) {
                failedRules.add(new ValidationResult.Violation(
                        "R02_AVOID",
                        "hard",
                        entry.item().name() + " 命中忌口食物",
                        List.of(entry.item().name()),
                        avoid,
                        null
                ));
            }
        }
    }

    private void checkFoodValidity(List<ExpandedItem> expanded, List<ValidationResult.Violation> failedRules) {
        for (ExpandedItem entry : expanded) {
            if (!entry.valid() || entry.energyKcal() == null) {
                failedRules.add(new ValidationResult.Violation(
                        "R03_FOOD_VALID",
                        "hard",
                        entry.item().name() + " 无法匹配食物库或有效配方",
                        List.of(entry.item().name()),
                        null,
                        null
                ));
            }
        }
    }

    private void checkNutrientRange(
            Map<String, Double> totals,
            NutritionEngine.MealRange range,
            List<ValidationResult.Violation> failedRules,
            List<ValidationResult.Warning> warnings
    ) {
        double energy = Nutrients.value(totals, "energyKcal");

        if (range.expectedKcal() == 0) {
            warnings.add(new ValidationResult.Warning(
                    "R04_NUTRIENT_RANGE",
                    "今日已超出目标，改为结构优先，不再按补差计算"
            ));
        } else if (energy < range.rangeKcal().get(0) || energy > range.rangeKcal().get(1)) {
            failedRules.add(new ValidationResult.Violation(
                    "R04_NUTRIENT_RANGE",
                    "hard",
                    String.format(Locale.ROOT, "本餐能量 %d kcal 超出合理范围 %d–%d kcal",
                            Math.round(energy), Math.round(range.rangeKcal().get(0)), Math.round(range.rangeKcal().get(1))),
                    List.of(),
                    range.rangeKcal(),
                    Math.round(energy)
            ));
        }

        double protein = Nutrients.value(totals, "proteinG");
        if (range.expectedProteinG() > 0 && protein < range.minProteinG()) {
            failedRules.add(new ValidationResult.Violation(
                    "R04_NUTRIENT_RANGE",
                    "hard",
                    String.format(Locale.ROOT, "本餐蛋白质 %.1f g 低于下限 %.1f g", protein, range.minProteinG()),
                    List.of(),
                    range.minProteinG(),
                    Nutrients.round(totals).get("proteinG")
            ));
        }
    }

    private void checkStructure(
            List<ExpandedItem> expanded,
            String mealType,
            List<ValidationResult.Violation> failedRules,
            List<ValidationResult.Warning> warnings
    ) {
        double vegetableG = expanded.stream().mapToDouble(ExpandedItem::vegetableG).sum();
        Set<String> names = new HashSet<>();
        expanded.forEach(entry -> names.add(entry.item().name()));
        int distinct = names.size();

        String severity = "lunch".equals(mealType) || "dinner".equals(mealType) ? "hard" : "soft";
        double minVegetable = config.minVegetableG().getOrDefault(mealType, 0.0);

        if (vegetableG < minVegetable) {
            String message = String.format(Locale.ROOT, "蔬菜 %d g 低于本餐建议下限 %d g",
                    Math.round(vegetableG), Math.round(minVegetable));
            if ("hard".equals(severity)) {
                failedRules.add(new ValidationResult.Violation(
                        "R05_STRUCTURE", severity, message, List.of(), Math.round(minVegetable), Math.round(vegetableG)
                ));
            } else {
                warnings.add(new ValidationResult.Warning("R05_STRUCTURE", message));
            }
        }

        double minDistinct = config.minDistinctFoods().getOrDefault(mealType, 1.0);
        if (distinct < minDistinct) {
            warnings.add(new ValidationResult.Warning(
                    "R05_STRUCTURE",
                    "食物种类仅 " + distinct + " 种，建议增加不同食物组"
            ));
        }
    }

    private void checkReplaceRequest(
            List<ExpandedItem> expanded,
            Map<String, Double> totals,
            ReplaceConstraints constraints,
            List<ValidationResult.Violation> failedRules
    ) {
        if (constraints == null) {
            return;
        }

        if (Boolean.TRUE.equals(constraints.vegetarian())) {
            List<String> animalTags = List.of("meat", "poultry", "fish", "seafood", "肉", "鱼", "禽");
            List<String> offenders = expanded.stream()
                    .filter(entry -> entry.tags().stream().anyMatch(tag ->
                            animalTags.stream().anyMatch(animal -> normalize(tag).contains(normalize(animal)))))
                    .map(entry -> entry.item().name())
                    .toList();
            if (!offenders.isEmpty()) {
                failedRules.add(new ValidationResult.Violation(
                        "R06_REPLACE_REQUEST",
                        "hard",
                        "替换要求为素菜，但仍包含动物性食材",
                        offenders,
                        null,
                        null
                ));
            }
        }

        if (Boolean.TRUE.equals(constraints.noSpicy())) {
            List<String> offenders = expanded.stream()
                    .filter(entry -> entry.tags().stream().anyMatch(tag -> "spicy".equals(normalize(tag))))
                    .map(entry -> entry.item().name())
                    .toList();
            if (!offenders.isEmpty()) {
                failedRules.add(new ValidationResult.Violation(
                        "R06_REPLACE_REQUEST",
                        "hard",
                        "替换要求为不辣，但仍包含辣味菜品",
                        offenders,
                        null,
                        null
                ));
            }
        }

        for (String group : safeList(constraints.requiredFoodGroups())) {
            double amount = expanded.stream()
                    .filter(entry -> group.equals(entry.foodGroup()))
                    .mapToDouble(entry -> entry.item().amountG())
                    .sum();
            if (amount <= 0) {
                failedRules.add(new ValidationResult.Violation(
                        "R06_REPLACE_REQUEST",
                        "hard",
                        "替换后缺少要求的食物组：" + group,
                        List.of(),
                        null,
                        null
                ));
            }
        }

        double protein = Nutrients.value(totals, "proteinG");
        if (constraints.minProteinG() != null && protein < constraints.minProteinG()) {
            failedRules.add(new ValidationResult.Violation(
                    "R06_REPLACE_REQUEST",
                    "hard",
                    String.format(Locale.ROOT, "替换后蛋白质 %.1f g 低于要求 %.1f g",
                            protein, constraints.minProteinG()),
                    List.of(),
                    constraints.minProteinG(),
                    protein
            ));
        }

        double energy = Nutrients.value(totals, "energyKcal");
        if (constraints.maxEnergyKcal() != null && energy > constraints.maxEnergyKcal()) {
            failedRules.add(new ValidationResult.Violation(
                    "R06_REPLACE_REQUEST",
                    "hard",
                    String.format(Locale.ROOT, "替换后能量 %d kcal 超过上限 %.0f kcal",
                            Math.round(energy), constraints.maxEnergyKcal()),
                    List.of(),
                    constraints.maxEnergyKcal(),
                    Math.round(energy)
            ));
        }
    }

    private void checkDuplicates(
            List<ExpandedItem> expanded,
            List<ValidationResult.Violation> failedRules,
            List<ValidationResult.Warning> warnings
    ) {
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (ExpandedItem entry : expanded) {
            seen.merge(normalize(entry.item().name()), 1, Integer::sum);
        }
        seen.forEach((name, count) -> {
            if (count > 1) {
                warnings.add(new ValidationResult.Warning(
                        "R07_DUPLICATE",
                        name + " 在同一餐中重复出现 " + count + " 次"
                ));
            }
        });
        if (failedRules.isEmpty() && seen.isEmpty()) {
            warnings.add(new ValidationResult.Warning("R07_DUPLICATE", "未提供任何菜品"));
        }
    }

    private void checkScope(String explanation, List<ValidationResult.Violation> failedRules) {
        if (explanation == null) {
            return;
        }
        for (String keyword : config.scopeKeywords()) {
            if (explanation.contains(keyword)) {
                failedRules.add(new ValidationResult.Violation(
                        "R08_SCOPE",
                        "hard",
                        "说明中出现疾病判断用语「" + keyword + "」，请改为一般膳食建议",
                        List.of(),
                        null,
                        null
                ));
                return;
            }
        }
    }

    private Map<String, Double> totalNutrients(List<ExpandedItem> expanded) {
        Map<String, Double> totals = Nutrients.empty();
        for (ExpandedItem entry : expanded) {
            Map<String, Double> addition = Nutrients.empty();
            if (entry.energyKcal() != null) {
                addition.put("energyKcal", entry.energyKcal());
            }
            if (entry.proteinG() != null) {
                addition.put("proteinG", entry.proteinG());
            }
            totals = Nutrients.add(totals, addition);
        }
        return Nutrients.round(totals);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }
}
