import type {
  Food,
  FoodGroup,
  GapResult,
  MealAllocation,
  MealInputItem,
  MealType,
  NutrientKey,
  Nutrients,
  NutritionResult,
  PerItemNutrition,
  Recipe,
  StructureResult,
  ValueType
} from '../shared/types.ts';
import { NUTRIENT_KEYS } from '../shared/types.ts';
import type { FoodRepository } from './repository.ts';
import { evaluateStructure } from './structure.ts';
import { MEAL_WEIGHTS, NUTRIENT_TOLERANCE } from './targets.ts';

const OIL_PER_100G: Nutrients = { energyKcal: 900, fatG: 100 };

export function emptyNutrients(): Nutrients {
  return {};
}

export function addNutrients(target: Nutrients, addition: Nutrients): Nutrients {
  const result: Nutrients = { ...target };
  for (const key of NUTRIENT_KEYS) {
    const value = addition[key];
    if (value === undefined) continue;
    result[key] = (result[key] ?? 0) + value;
  }
  return result;
}

export function scaleNutrients(values: Nutrients, factor: number): Nutrients {
  const result: Nutrients = {};
  for (const key of NUTRIENT_KEYS) {
    const value = values[key];
    if (value === undefined) continue;
    result[key] = value * factor;
  }
  return result;
}

export function roundNutrients(values: Nutrients): Nutrients {
  const result: Nutrients = {};
  for (const key of NUTRIENT_KEYS) {
    const value = values[key];
    if (value === undefined) continue;
    result[key] = key === 'energyKcal' || key === 'sodiumMg' ? Math.round(value) : Math.round(value * 10) / 10;
  }
  return result;
}

export function computeFoodNutrients(food: Food, amountG: number): Nutrients {
  const factor = (amountG * food.edibleRatio) / 100;
  return scaleNutrients(food.nutrientsPer100g, factor);
}

export interface ComputedItem {
  perItem: PerItemNutrition;
  groupAmounts: Partial<Record<FoodGroup, number>>;
  allergens: string[];
  tags: string[];
}

export interface MealRange {
  expectedKcal: number;
  rangeKcal: [number, number];
  expectedProteinG: number;
  minProteinG: number;
  weightsSum: number;
}

/**
 * 计算某餐的合理能量/蛋白质范围。
 * 不要求每餐达到每日目标，而是按剩余目标与剩余餐次权重分配。
 */
export function computeMealRange(
  targets: Nutrients,
  consumed: Nutrients,
  unloggedMeals: MealType[],
  currentMeal: MealType,
  tolerance = NUTRIENT_TOLERANCE
): MealRange {
  const meals = unloggedMeals.includes(currentMeal) ? unloggedMeals : [...unloggedMeals, currentMeal];
  const weightsSum = meals.reduce((sum, meal) => sum + (MEAL_WEIGHTS[meal] ?? 0), 0) || 1;
  const currentWeight = MEAL_WEIGHTS[currentMeal] ?? 0;
  const share = currentWeight / weightsSum;

  const remainingEnergy = Math.max((targets.energyKcal ?? 0) - (consumed.energyKcal ?? 0), 0);
  const remainingProtein = Math.max((targets.proteinG ?? 0) - (consumed.proteinG ?? 0), 0);

  const expectedKcal = remainingEnergy * share;
  const expectedProteinG = remainingProtein * share;

  return {
    expectedKcal: Math.round(expectedKcal),
    rangeKcal: [
      Math.round(expectedKcal * (1 - tolerance)),
      Math.round(expectedKcal * (1 + tolerance))
    ],
    expectedProteinG: Math.round(expectedProteinG * 10) / 10,
    minProteinG: Math.round(expectedProteinG * 0.8 * 10) / 10,
    weightsSum
  };
}

export function computeGap(params: {
  targets: Nutrients;
  consumed: Nutrients;
  unloggedMeals: MealType[];
  tolerance?: number;
}): GapResult {
  const { targets, consumed, unloggedMeals } = params;
  const tolerance = params.tolerance ?? NUTRIENT_TOLERANCE;

  const remaining: Nutrients = {};
  for (const key of NUTRIENT_KEYS) {
    const target = targets[key];
    if (target === undefined) continue;
    remaining[key] = Math.round((target - (consumed[key] ?? 0)) * 10) / 10;
  }

  const weightsSum = unloggedMeals.reduce((sum, meal) => sum + (MEAL_WEIGHTS[meal] ?? 0), 0) || 1;
  const remainingEnergy = Math.max(remaining.energyKcal ?? 0, 0);

  const allocations: MealAllocation[] = unloggedMeals.map((mealType) => {
    const weight = MEAL_WEIGHTS[mealType] ?? 0;
    const expected = Math.round((remainingEnergy * weight) / weightsSum);
    return {
      mealType,
      weight,
      expectedKcal: expected,
      rangeKcal: [Math.round(expected * (1 - tolerance)), Math.round(expected * (1 + tolerance))]
    };
  });

  return { dailyTargets: targets, consumed, remaining, unloggedMeals, allocations };
}

export class NutritionEngine {
  constructor(private readonly foods: FoodRepository) {}

  async computeItem(item: MealInputItem, mealType: MealType): Promise<ComputedItem> {
    if (item.recipeId) {
      const recipe = await this.foods.getRecipe(item.recipeId);
      if (recipe) return this.computeRecipeItem(recipe, item);
    }

    if (item.foodId) {
      const food = await this.foods.findById(item.foodId);
      if (food) return this.computeFoodItem(food, item);
    }

    const matchedByName = await this.foods.findByName(item.rawName);
    if (matchedByName) return this.computeFoodItem(matchedByName, item);

    if (item.analysis?.directEstimate) {
      return this.computeAiEstimate(item, mealType);
    }

    return {
      perItem: {
        rawName: item.rawName,
        amountG: item.amountG,
        valueType: 'estimated',
        values: {}
      },
      groupAmounts: { other: item.amountG },
      allergens: [],
      tags: []
    };
  }

  async computeMeal(items: MealInputItem[], mealType: MealType): Promise<NutritionResult> {
    const computed: ComputedItem[] = [];
    for (const item of items) {
      computed.push(await this.computeItem(item, mealType));
    }

    let values = emptyNutrients();
    const groupAmounts: Partial<Record<FoodGroup, number>> = {};
    let valueType: ValueType = 'measured';
    const warnings: string[] = [];

    for (const item of computed) {
      values = addNutrients(values, item.perItem.values);
      for (const [group, amount] of Object.entries(item.groupAmounts) as Array<[FoodGroup, number]>) {
        groupAmounts[group] = (groupAmounts[group] ?? 0) + amount;
      }
      valueType = mergeValueType(valueType, item.perItem.valueType);
      if (item.perItem.valueType === 'ai_estimated') {
        warnings.push(`${item.perItem.rawName} 为 AI 估算值，存在误差区间`);
      }
      if (item.perItem.values.energyKcal === undefined) {
        warnings.push(`${item.perItem.rawName} 未匹配到有效食物，暂未计入营养统计`);
      }
    }

    const structure: StructureResult = evaluateStructure(
      groupAmounts,
      mealType,
      computed.filter((item) => item.perItem.values.energyKcal !== undefined).length
    );

    return {
      quantitative: { values: roundNutrients(values), perItem: computed.map((item) => item.perItem) },
      structure,
      valueType,
      warnings
    };
  }

  private computeFoodItem(food: Food, item: MealInputItem): ComputedItem {
    const values = computeFoodNutrients(food, item.amountG);
    return {
      perItem: {
        foodId: food.id,
        rawName: item.rawName,
        amountG: item.amountG,
        valueType: item.valueType ?? 'measured',
        values: roundNutrients(values)
      },
      groupAmounts: { [food.category]: item.amountG * food.edibleRatio },
      allergens: food.allergens,
      tags: food.tags
    };
  }

  private async computeRecipeItem(recipe: Recipe, item: MealInputItem): Promise<ComputedItem> {
    let recipeTotals = emptyNutrients();
    const groupAmounts: Partial<Record<FoodGroup, number>> = {};
    const allergens = new Set(recipe.allergens);
    const tags = new Set(recipe.tags);
    let ingredientWeight = 0;

    for (const ingredient of recipe.ingredients) {
      const food = await this.foods.findById(ingredient.foodId);
      if (!food) continue;
      recipeTotals = addNutrients(recipeTotals, computeFoodNutrients(food, ingredient.amountG));
      groupAmounts[food.category] = (groupAmounts[food.category] ?? 0) + ingredient.amountG * food.edibleRatio;
      ingredientWeight += ingredient.amountG * food.edibleRatio;
      for (const allergen of food.allergens) allergens.add(allergen);
      for (const tag of food.tags) tags.add(tag);
    }

    if (recipe.oilG > 0) {
      recipeTotals = addNutrients(recipeTotals, scaleNutrients(OIL_PER_100G, recipe.oilG / 100));
      groupAmounts.oils = (groupAmounts.oils ?? 0) + recipe.oilG;
      ingredientWeight += recipe.oilG;
    }

    const yieldG = recipe.yieldG > 0 ? recipe.yieldG : ingredientWeight || item.amountG;
    const factor = item.amountG / yieldG;
    const values = scaleNutrients(recipeTotals, factor);

    return {
      perItem: {
        recipeId: recipe.id,
        rawName: item.rawName,
        amountG: item.amountG,
        valueType: item.valueType ?? 'estimated',
        values: roundNutrients(values)
      },
      groupAmounts: Object.fromEntries(
        Object.entries(groupAmounts).map(([group, amount]) => [group, amount * factor])
      ) as Partial<Record<FoodGroup, number>>,
      allergens: [...allergens],
      tags: [...tags]
    };
  }

  private computeAiEstimate(item: MealInputItem, _mealType: MealType): ComputedItem {
    const estimate = item.analysis!.directEstimate!;
    const energy = midpoint(estimate.energyKcal);
    const protein = estimate.proteinG ? midpoint(estimate.proteinG) : undefined;
    const values: Nutrients = { energyKcal: energy };
    if (protein !== undefined) values.proteinG = protein;

    return {
      perItem: {
        rawName: item.rawName,
        amountG: item.amountG,
        valueType: 'ai_estimated',
        values: roundNutrients(values)
      },
      groupAmounts: { other: item.amountG },
      allergens: [],
      tags: []
    };
  }
}

export function mergeValueType(current: ValueType, next: ValueType): ValueType {
  const rank: Record<ValueType, number> = { measured: 0, estimated: 1, ai_estimated: 2 };
  return rank[next] > rank[current] ? next : current;
}

function midpoint(range: [number, number]): number {
  return (range[0] + range[1]) / 2;
}

export function sumEntryTotals(totals: Nutrients[]): Nutrients {
  return roundNutrients(totals.reduce((acc, item) => addNutrients(acc, item), emptyNutrients()));
}
