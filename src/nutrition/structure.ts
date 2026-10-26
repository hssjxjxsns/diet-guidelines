import type { FoodGroup, FoodGroupStatus, MealType, StructureResult } from '../shared/types.ts';
import { MEAL_WEIGHTS } from './targets.ts';

/** Recommended daily intake ranges in grams (dairy treated as millilitres ≈ grams). */
export const DAILY_STRUCTURE_RANGES: Record<FoodGroup, [number, number]> = {
  grains: [200, 300],
  vegetables: [300, 500],
  fruits: [200, 350],
  protein: [120, 200],
  dairy: [300, 500],
  soy_nuts: [25, 35],
  oils: [25, 30],
  other: [0, 0]
};

export function evaluateStructure(
  amounts: Partial<Record<FoodGroup, number>>,
  mealType: MealType,
  distinctFoodCount: number
): StructureResult {
  const share = MEAL_WEIGHTS[mealType];
  const groups: FoodGroupStatus[] = (Object.keys(DAILY_STRUCTURE_RANGES) as FoodGroup[])
    .filter((group) => group !== 'other')
    .map((group) => {
      const [dailyMin, dailyMax] = DAILY_STRUCTURE_RANGES[group];
      const min = round(dailyMin * share);
      const max = round(dailyMax * share);
      const amount = round(amounts[group] ?? 0);
      return {
        group,
        amountG: amount,
        recommendedRangeG: [min, max],
        status: amount < min ? 'insufficient' : amount > max ? 'excessive' : 'adequate'
      };
    });

  return {
    foodGroups: groups,
    distinctFoodCount,
    diversityNote:
      distinctFoodCount >= 4
        ? '食物种类较丰富'
        : distinctFoodCount >= 2
          ? '食物种类偏少，建议增加蔬菜或全谷物'
          : '食物种类单一，建议增加不同食物组'
  };
}

function round(value: number): number {
  return Math.round(value * 10) / 10;
}
