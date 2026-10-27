package com.dietguide.nutrition;

import com.dietguide.domain.MealInputItem;
import com.dietguide.domain.NutritionResult;
import com.dietguide.support.Fixtures;
import com.dietguide.support.MemoryFoodRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NutritionEngineTest {

    private final MemoryFoodRepository foods = Fixtures.repository();
    private final NutritionEngine engine = new NutritionEngine(foods);

    @Test
    void computesFoodNutrientsWithEdibleRatio() {
        Map<String, Double> values = NutritionEngine.computeFoodNutrients(Fixtures.egg(), 100);

        assertEquals(126.72, values.get("energyKcal"), 0.001);
        assertEquals(11.704, values.get("proteinG"), 0.001);
    }

    @Test
    void sumsMatchedItemsAndWarnsOnUnmatchedName() {
        NutritionResult result = engine.computeMeal(List.of(
                new MealInputItem(null, null, "米饭", 200, null, null),
                new MealInputItem(null, null, "神秘菜", 100, null, null)
        ), "lunch");

        assertEquals(232.0, result.quantitative().values().get("energyKcal"), 0.001);
        assertEquals(2, result.quantitative().perItem().size());
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("神秘菜"));
    }

    @Test
    void scalesRecipeNutritionByServedWeight() {
        NutritionResult result = engine.computeMeal(
                List.of(new MealInputItem(null, "r_tomato_egg_001", "番茄炒蛋", 110, null, null)),
                "lunch"
        );

        assertEquals(121.0, result.quantitative().values().get("energyKcal"), 0.5);
        assertEquals("estimated", result.valueType());
    }
}
