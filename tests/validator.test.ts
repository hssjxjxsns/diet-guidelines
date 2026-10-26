import { describe, expect, it } from 'vitest';
import { NutritionEngine } from '../src/nutrition/engine.ts';
import { PlanValidator } from '../src/nutrition/validator.ts';
import type { PlanItem } from '../src/shared/types.ts';
import {
  broccoli,
  chicken,
  createFoodRepository,
  defaultSettings,
  egg,
  rice
} from './support/fixtures.ts';

const validItems: PlanItem[] = [
  {
    foodId: rice.id,
    name: '米饭',
    amountG: 100,
    foodGroup: 'grains',
    tags: ['vegetarian'],
    allergens: []
  },
  {
    foodId: broccoli.id,
    name: '西兰花',
    amountG: 150,
    foodGroup: 'vegetables',
    tags: ['vegetarian'],
    allergens: []
  },
  {
    foodId: chicken.id,
    name: '鸡胸肉',
    amountG: 60,
    foodGroup: 'protein',
    tags: ['meat'],
    allergens: []
  }
];

function createValidator() {
  const foods = createFoodRepository();
  const engine = new NutritionEngine(foods);
  return { foods, engine, validator: new PlanValidator(foods, engine) };
}

describe('PlanValidator', () => {
  it('通过结构合理、能量落在动态范围内的一餐', async () => {
    const { validator } = createValidator();
    const result = await validator.validate({
      items: validItems,
      consumed: { energyKcal: 1200, proteinG: 45 },
      dailyTargets: defaultSettings.dailyTargets,
      unloggedMeals: ['breakfast', 'lunch', 'dinner', 'snack'],
      currentMeal: 'lunch',
      settings: { allergens: [], avoid: [] }
    });

    expect(result.passed).toBe(true);
    expect(result.computedRanges.energyKcal).toEqual([224, 336]);
    expect(result.failedRules).toHaveLength(0);
  });

  it('过敏原硬性拦截', async () => {
    const { validator } = createValidator();
    const items: PlanItem[] = [
      ...validItems,
      {
        foodId: egg.id,
        name: '鸡蛋',
        amountG: 50,
        foodGroup: 'protein',
        tags: ['vegetarian'],
        allergens: ['蛋']
      }
    ];

    const result = await validator.validate({
      items,
      consumed: { energyKcal: 1200, proteinG: 45 },
      dailyTargets: defaultSettings.dailyTargets,
      unloggedMeals: ['breakfast', 'lunch', 'dinner', 'snack'],
      currentMeal: 'lunch',
      settings: { allergens: ['蛋'], avoid: [] }
    });

    expect(result.passed).toBe(false);
    expect(result.failedRules.some((rule) => rule.ruleId === 'R01_ALLERGEN')).toBe(true);
  });

  it('替换为素菜时拦截动物性食材', async () => {
    const { validator } = createValidator();
    const result = await validator.validate({
      items: validItems,
      consumed: { energyKcal: 1200, proteinG: 45 },
      dailyTargets: defaultSettings.dailyTargets,
      unloggedMeals: ['breakfast', 'lunch', 'dinner', 'snack'],
      currentMeal: 'lunch',
      settings: { allergens: [], avoid: [] },
      replaceConstraints: { vegetarian: true }
    });

    expect(result.failedRules.some((rule) => rule.ruleId === 'R06_REPLACE_REQUEST')).toBe(true);
  });

  it('拦截疾病诊断式表述', async () => {
    const { validator } = createValidator();
    const result = await validator.validate({
      items: validItems,
      consumed: { energyKcal: 1200, proteinG: 45 },
      dailyTargets: defaultSettings.dailyTargets,
      unloggedMeals: ['breakfast', 'lunch', 'dinner', 'snack'],
      currentMeal: 'lunch',
      settings: { allergens: [], avoid: [] },
      explanation: '根据记录可以诊断为糖尿病。'
    });

    expect(result.failedRules.some((rule) => rule.ruleId === 'R08_SCOPE')).toBe(true);
  });

  it('已超出每日目标时不再按补差强制要求', async () => {
    const { validator } = createValidator();
    const result = await validator.validate({
      items: validItems,
      consumed: { energyKcal: 2500, proteinG: 90 },
      dailyTargets: defaultSettings.dailyTargets,
      unloggedMeals: ['dinner'],
      currentMeal: 'dinner',
      settings: { allergens: [], avoid: [] }
    });

    expect(result.failedRules.some((rule) => rule.ruleId === 'R04_NUTRIENT_RANGE')).toBe(false);
    expect(result.warnings.some((warning) => warning.ruleId === 'R04_NUTRIENT_RANGE')).toBe(true);
  });
});
