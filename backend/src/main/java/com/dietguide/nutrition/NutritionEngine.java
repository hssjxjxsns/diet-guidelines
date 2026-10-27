package com.dietguide.nutrition;

import com.dietguide.domain.Food;
import com.dietguide.domain.FoodGroupStatus;
import com.dietguide.domain.GapResult;
import com.dietguide.domain.MealAllocation;
import com.dietguide.domain.MealInputItem;
import com.dietguide.domain.NutritionResult;
import com.dietguide.domain.PerItemNutrition;
import com.dietguide.domain.Recipe;
import com.dietguide.domain.StructureResult;
import com.dietguide.shared.Nutrients;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class NutritionEngine {

    private static final Map<String, Double> OIL_PER_100G = Map.of(
            "energyKcal", 900.0,
            "fatG", 100.0
    );

    private final FoodRepository foods;

    public NutritionEngine(FoodRepository foods) {
        this.foods = foods;
    }

    public ComputedItem computeItem(MealInputItem item, String mealType) {
        if (item.recipeId() != null) {
            Recipe recipe = foods.getRecipe(item.recipeId());
            if (recipe != null) {
                return computeRecipeItem(recipe, item);
            }
        }

        if (item.foodId() != null) {
            Food food = foods.findById(item.foodId());
            if (food != null) {
                return computeFoodItem(food, item);
            }
        }

        Food matchedByName = foods.findByName(item.rawName());
        if (matchedByName != null) {
            return computeFoodItem(matchedByName, item);
        }

        if (item.analysis() != null && item.analysis().directEstimate() != null) {
            return computeAiEstimate(item);
        }

        Map<String, Double> groupAmounts = new LinkedHashMap<>();
        groupAmounts.put("other", item.amountG());
        return new ComputedItem(
                new PerItemNutrition(null, null, item.rawName(), item.amountG(), "estimated", Nutrients.empty()),
                groupAmounts,
                List.of(),
                List.of()
        );
    }

    public NutritionResult computeMeal(List<MealInputItem> items, String mealType) {
        List<ComputedItem> computed = new ArrayList<>();
        for (MealInputItem item : items) {
            computed.add(computeItem(item, mealType));
        }

        Map<String, Double> values = Nutrients.empty();
        Map<String, Double> groupAmounts = new LinkedHashMap<>();
        String valueType = "measured";
        List<String> warnings = new ArrayList<>();
        int matchedCount = 0;

        for (ComputedItem item : computed) {
            values = Nutrients.add(values, item.perItem().values());
            for (Map.Entry<String, Double> entry : item.groupAmounts().entrySet()) {
                groupAmounts.merge(entry.getKey(), entry.getValue(), Double::sum);
            }
            valueType = mergeValueType(valueType, item.perItem().valueType());
            if ("ai_estimated".equals(item.perItem().valueType())) {
                warnings.add(item.perItem().rawName() + " 为 AI 估算值，存在误差区间");
            }
            if (item.perItem().values().get("energyKcal") == null) {
                warnings.add(item.perItem().rawName() + " 未匹配到有效食物，暂未计入营养统计");
            } else {
                matchedCount += 1;
            }
        }

        StructureResult structure = StructureEvaluator.evaluateStructure(groupAmounts, mealType, matchedCount);

        List<PerItemNutrition> perItem = computed.stream().map(ComputedItem::perItem).toList();
        return new NutritionResult(
                new NutritionResult.Quantitative(Nutrients.round(values), perItem),
                structure,
                valueType,
                warnings
        );
    }

    public static Map<String, Double> computeFoodNutrients(Food food, double amountG) {
        double factor = (amountG * food.edibleRatio()) / 100;
        return Nutrients.scale(food.nutrientsPer100g(), factor);
    }

    private ComputedItem computeFoodItem(Food food, MealInputItem item) {
        Map<String, Double> values = computeFoodNutrients(food, item.amountG());
        Map<String, Double> groupAmounts = new LinkedHashMap<>();
        groupAmounts.put(food.category(), item.amountG() * food.edibleRatio());
        String valueType = item.valueType() == null ? "measured" : item.valueType();
        return new ComputedItem(
                new PerItemNutrition(food.id(), null, item.rawName(), item.amountG(), valueType,
                        Nutrients.round(values)),
                groupAmounts,
                safeList(food.allergens()),
                safeList(food.tags())
        );
    }

    private ComputedItem computeRecipeItem(Recipe recipe, MealInputItem item) {
        Map<String, Double> recipeTotals = Nutrients.empty();
        Map<String, Double> groupAmounts = new LinkedHashMap<>();
        List<String> allergens = new ArrayList<>(safeList(recipe.allergens()));
        List<String> tags = new ArrayList<>(safeList(recipe.tags()));
        double ingredientWeight = 0;

        for (Recipe.RecipeIngredient ingredient : safeList(recipe.ingredients())) {
            Food food = foods.findById(ingredient.foodId());
            if (food == null) {
                continue;
            }
            recipeTotals = Nutrients.add(recipeTotals, computeFoodNutrients(food, ingredient.amountG()));
            groupAmounts.merge(food.category(), ingredient.amountG() * food.edibleRatio(), Double::sum);
            ingredientWeight += ingredient.amountG() * food.edibleRatio();
            safeList(food.allergens()).forEach(value -> addUnique(allergens, value));
            safeList(food.tags()).forEach(value -> addUnique(tags, value));
        }

        if (recipe.oilG() > 0) {
            recipeTotals = Nutrients.add(recipeTotals, Nutrients.scale(OIL_PER_100G, recipe.oilG() / 100));
            groupAmounts.merge("oils", recipe.oilG(), Double::sum);
            ingredientWeight += recipe.oilG();
        }

        double yieldG = recipe.yieldG() > 0 ? recipe.yieldG() : ingredientWeight > 0 ? ingredientWeight : item.amountG();
        double factor = item.amountG() / yieldG;
        Map<String, Double> values = Nutrients.scale(recipeTotals, factor);

        Map<String, Double> scaledGroups = new LinkedHashMap<>();
        groupAmounts.forEach((group, amount) -> scaledGroups.put(group, amount * factor));

        String valueType = item.valueType() == null ? "estimated" : item.valueType();
        return new ComputedItem(
                new PerItemNutrition(null, recipe.id(), item.rawName(), item.amountG(), valueType,
                        Nutrients.round(values)),
                scaledGroups,
                allergens,
                tags
        );
    }

    private ComputedItem computeAiEstimate(MealInputItem item) {
        DishAnalysisEstimate estimate = new DishAnalysisEstimate(item);
        Map<String, Double> values = Nutrients.empty();
        values.put("energyKcal", estimate.energy());
        if (estimate.protein() != null) {
            values.put("proteinG", estimate.protein());
        }
        Map<String, Double> groupAmounts = new LinkedHashMap<>();
        groupAmounts.put("other", item.amountG());
        return new ComputedItem(
                new PerItemNutrition(null, null, item.rawName(), item.amountG(), "ai_estimated",
                        Nutrients.round(values)),
                groupAmounts,
                List.of(),
                List.of()
        );
    }

    /** 计算某餐的合理能量/蛋白质范围：按剩余目标与剩余餐次权重分配。 */
    public static MealRange computeMealRange(
            Map<String, Double> targets,
            Map<String, Double> consumed,
            List<String> unloggedMeals,
            String currentMeal,
            double tolerance
    ) {
        List<String> meals = new ArrayList<>(unloggedMeals);
        if (!meals.contains(currentMeal)) {
            meals.add(currentMeal);
        }
        double weightsSum = meals.stream().mapToDouble(Targets::mealWeight).sum();
        if (weightsSum == 0) {
            weightsSum = 1;
        }
        double share = Targets.mealWeight(currentMeal) / weightsSum;

        double remainingEnergy = Math.max(Nutrients.value(targets, "energyKcal") - Nutrients.value(consumed, "energyKcal"), 0);
        double remainingProtein = Math.max(Nutrients.value(targets, "proteinG") - Nutrients.value(consumed, "proteinG"), 0);

        double expectedKcal = remainingEnergy * share;
        double expectedProtein = remainingProtein * share;

        return new MealRange(
                Math.round(expectedKcal),
                List.of(
                        (double) Math.round(expectedKcal * (1 - tolerance)),
                        (double) Math.round(expectedKcal * (1 + tolerance))
                ),
                Math.round(expectedProtein * 10) / 10.0,
                Math.round(expectedProtein * 0.8 * 10) / 10.0,
                weightsSum
        );
    }

    public static GapResult computeGap(
            Map<String, Double> targets,
            Map<String, Double> consumed,
            List<String> unloggedMeals,
            double tolerance
    ) {
        Map<String, Double> remaining = Nutrients.empty();
        for (String key : Nutrients.KEYS) {
            Double target = targets.get(key);
            if (target == null) {
                continue;
            }
            remaining.put(key, Math.round((target - consumed.getOrDefault(key, 0.0)) * 10) / 10.0);
        }

        double weightsSum = unloggedMeals.stream().mapToDouble(Targets::mealWeight).sum();
        if (weightsSum == 0) {
            weightsSum = 1;
        }
        double remainingEnergy = Math.max(remaining.getOrDefault("energyKcal", 0.0), 0);

        List<MealAllocation> allocations = new ArrayList<>();
        for (String mealType : unloggedMeals) {
            double weight = Targets.mealWeight(mealType);
            double expected = Math.round((remainingEnergy * weight) / weightsSum);
            allocations.add(new MealAllocation(
                    mealType,
                    weight,
                    expected,
                    List.of(
                            (double) Math.round(expected * (1 - tolerance)),
                            (double) Math.round(expected * (1 + tolerance))
                    )
            ));
        }

        return new GapResult(targets, consumed, remaining, unloggedMeals, allocations);
    }

    public static String mergeValueType(String current, String next) {
        return rank(next) > rank(current) ? next : current;
    }

    public static Map<String, Double> sumEntryTotals(List<Map<String, Double>> totals) {
        Map<String, Double> sum = Nutrients.empty();
        for (Map<String, Double> item : totals) {
            sum = Nutrients.add(sum, item);
        }
        return Nutrients.round(sum);
    }

    private static int rank(String valueType) {
        return switch (valueType == null ? "" : valueType) {
            case "ai_estimated" -> 2;
            case "estimated" -> 1;
            default -> 0;
        };
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static void addUnique(List<String> values, String value) {
        if (value != null && !values.contains(value)) {
            values.add(value);
        }
    }

    public record ComputedItem(
            PerItemNutrition perItem,
            Map<String, Double> groupAmounts,
            List<String> allergens,
            List<String> tags
    ) {
    }

    public record MealRange(
            double expectedKcal,
            List<Double> rangeKcal,
            double expectedProteinG,
            double minProteinG,
            double weightsSum
    ) {
    }

    /** 从 AI 区间估算的中间值取值，避免把区间当成精确值。 */
    private record DishAnalysisEstimate(MealInputItem item) {

        double energy() {
            return midpoint(item.analysis().directEstimate().energyKcal());
        }

        Double protein() {
            List<Double> range = item.analysis().directEstimate().proteinG();
            return range == null ? null : midpoint(range);
        }

        private double midpoint(List<Double> range) {
            if (range == null || range.isEmpty()) {
                return 0;
            }
            double sum = range.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            return sum;
        }
    }
}
