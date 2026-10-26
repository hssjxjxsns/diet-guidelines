import { describe, expect, it } from 'vitest';
import { NutritionEngine, computeGap } from '../src/nutrition/engine.ts';
import { createFoodRepository, broccoli, chicken, rice, sparerib } from './support/fixtures.ts';

describe('NutritionEngine', () => {
  it('按标准数据换算单一食物', async () => {
    const engine = new NutritionEngine(createFoodRepository());
    const result = await engine.computeMeal(
      [{ foodId: rice.id, rawName: '米饭', amountG: 200 }],
      'lunch'
    );

    expect(result.quantitative.values.energyKcal).toBe(232);
    expect(result.quantitative.values.proteinG).toBe(5.2);
    expect(result.quantitative.values.carbG).toBe(51.8);
    expect(result.valueType).toBe('measured');
  });

  it('按配方与用油量估算复合菜品', async () => {
    const engine = new NutritionEngine(createFoodRepository());
    const result = await engine.computeMeal(
      [{ recipeId: sparerib.id, rawName: '糖醋排骨', amountG: 150 }],
      'lunch'
    );

    expect(result.valueType).toBe('estimated');
    expect(result.quantitative.values.energyKcal).toBe(469);
    expect(result.quantitative.values.proteinG).toBe(20);
    expect(result.quantitative.values.fatG).toBe(42.7);
    expect(result.warnings.length).toBe(0);
  });

  it('无法拆解时使用 AI 区间估算并标注 ai_estimated', async () => {
    const engine = new NutritionEngine(createFoodRepository());
    const result = await engine.computeMeal(
      [
        {
          rawName: '外卖沙拉',
          amountG: 200,
          analysis: {
            dishName: '外卖沙拉',
            directEstimate: { energyKcal: [400, 500], confidence: 0.6 },
            confidence: 0.6,
            basis: ['按常见沙拉估算'],
            needsUserConfirm: true
          }
        }
      ],
      'lunch'
    );

    expect(result.valueType).toBe('ai_estimated');
    expect(result.quantitative.values.energyKcal).toBe(450);
    expect(result.warnings.some((warning) => warning.includes('AI 估算'))).toBe(true);
  });

  it('按餐次权重计算缺口分配，而不是要求每餐补齐', () => {
    const gap = computeGap({
      targets: { energyKcal: 2000 },
      consumed: { energyKcal: 500 },
      unloggedMeals: ['dinner', 'snack']
    });

    expect(gap.remaining.energyKcal).toBe(1500);
    const dinner = gap.allocations.find((item) => item.mealType === 'dinner');
    const snack = gap.allocations.find((item) => item.mealType === 'snack');
    expect(dinner?.expectedKcal).toBe(1125);
    expect(snack?.expectedKcal).toBe(375);
  });

  it('按本餐占比评估膳食结构', async () => {
    const engine = new NutritionEngine(createFoodRepository());
    const result = await engine.computeMeal(
      [
        { foodId: broccoli.id, rawName: '西兰花', amountG: 150 },
        { foodId: rice.id, rawName: '米饭', amountG: 100 },
        { foodId: chicken.id, rawName: '鸡胸肉', amountG: 60 }
      ],
      'lunch'
    );

    const vegetables = result.structure.foodGroups.find((group) => group.group === 'vegetables');
    expect(vegetables?.amountG).toBe(150);
    expect(vegetables?.recommendedRangeG).toEqual([105, 175]);
    expect(vegetables?.status).toBe('adequate');
    expect(result.structure.distinctFoodCount).toBe(3);
  });
});
