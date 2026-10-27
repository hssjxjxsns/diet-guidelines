import type { MealType, Nutrients } from '../shared/types.ts';

export const DEFAULT_DAILY_TARGETS: Nutrients = {
  energyKcal: 2000,
  proteinG: 75,
  fatG: 60,
  carbG: 275,
  fiberG: 25,
  sodiumMg: 2000,
  calciumMg: 800,
  ironMg: 15
};

export const MEAL_WEIGHTS: Record<MealType, number> = {
  breakfast: 0.25,
  lunch: 0.35,
  dinner: 0.3,
  snack: 0.1
};

export const NUTRIENT_TOLERANCE = 0.2;

export function mealWeight(mealType: MealType): number {
  return MEAL_WEIGHTS[mealType];
}
