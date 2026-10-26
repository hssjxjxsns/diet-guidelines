import { describe, expect, it } from 'vitest';
import { effectiveEntries, resolveNextRevision } from '../src/domain/meal-version.ts';
import { MealRevisionConflictError } from '../src/shared/errors.ts';
import { MealGroupService } from '../src/services/meal-groups.ts';
import { NutritionEngine } from '../src/nutrition/engine.ts';
import { egg, milk, createFoodRepository, rice } from './support/fixtures.ts';
import { InMemoryMealGroupStore } from './support/memory-meal-store.ts';

const user = 'user-1';
const date = '2026-10-26';

describe('meal version rules', () => {
  it('有效集合 = 最新 replace + 其后所有 append', () => {
    const entries = [
      { revision: 1, mode: 'replace' as const },
      { revision: 2, mode: 'append' as const },
      { revision: 3, mode: 'replace' as const },
      { revision: 4, mode: 'append' as const }
    ];

    expect(effectiveEntries(entries).map((entry) => entry.revision)).toEqual([3, 4]);
  });

  it('没有 replace 时所有 append 有效', () => {
    const entries = [
      { revision: 1, mode: 'append' as const },
      { revision: 2, mode: 'append' as const }
    ];
    expect(effectiveEntries(entries).map((entry) => entry.revision)).toEqual([1, 2]);
  });

  it('replace 的 base_revision 过期时抛出冲突', () => {
    const entries = [{ revision: 1, mode: 'append' as const }];
    expect(() => resolveNextRevision(entries, 'replace', 0)).toThrow(MealRevisionConflictError);
  });
});

describe('MealGroupService', () => {
  it('支持补录、修正与多次加餐，统计只取有效版本', async () => {
    const engine = new NutritionEngine(createFoodRepository());
    const store = new InMemoryMealGroupStore();
    const service = new MealGroupService(store, engine);

    const first = await service.submit({
      userId: user,
      mealDate: date,
      mealType: 'lunch',
      mode: 'append',
      idempotencyKey: 'k1',
      nutritionDataVersion: 'test',
      items: [{ foodId: rice.id, rawName: '米饭', amountG: 200 }]
    });
    expect(first.entry.revision).toBe(1);
    expect(first.dailyTotals.energyKcal).toBe(232);

    const second = await service.submit({
      userId: user,
      mealDate: date,
      mealType: 'lunch',
      mode: 'append',
      idempotencyKey: 'k2',
      nutritionDataVersion: 'test',
      items: [{ foodId: milk.id, rawName: '牛奶', amountG: 300 }]
    });
    expect(second.entry.revision).toBe(2);
    expect(second.effectiveEntries).toHaveLength(2);
    expect(second.dailyTotals.energyKcal).toBe(394);

    const replaced = await service.submit({
      userId: user,
      mealDate: date,
      mealType: 'lunch',
      mode: 'replace',
      baseRevision: 2,
      idempotencyKey: 'k3',
      nutritionDataVersion: 'test',
      items: [{ foodId: rice.id, rawName: '米饭', amountG: 150 }]
    });
    expect(replaced.entry.revision).toBe(3);
    expect(replaced.effectiveEntries).toHaveLength(1);
    expect(replaced.dailyTotals.energyKcal).toBe(174);

    const appended = await service.submit({
      userId: user,
      mealDate: date,
      mealType: 'lunch',
      mode: 'append',
      idempotencyKey: 'k4',
      nutritionDataVersion: 'test',
      items: [{ foodId: egg.id, rawName: '鸡蛋', amountG: 50 }]
    });
    expect(appended.effectiveEntries.map((entry) => entry.revision)).toEqual([3, 4]);
    expect(appended.dailyTotals.energyKcal).toBe(237);

    const duplicated = await service.submit({
      userId: user,
      mealDate: date,
      mealType: 'lunch',
      mode: 'append',
      idempotencyKey: 'k4',
      nutritionDataVersion: 'test',
      items: [{ foodId: egg.id, rawName: '鸡蛋', amountG: 50 }]
    });
    expect(duplicated.entry.id).toBe(appended.entry.id);
    expect(store.entries).toHaveLength(4);

    await expect(
      service.submit({
        userId: user,
        mealDate: date,
        mealType: 'lunch',
        mode: 'replace',
        baseRevision: 1,
        idempotencyKey: 'k5',
        nutritionDataVersion: 'test',
        items: [{ foodId: rice.id, rawName: '米饭', amountG: 100 }]
      })
    ).rejects.toMatchObject({ code: 'MEAL_REVISION_CONFLICT', status: 409 });
  });

  it('同一天允许多次加餐，每次一个实例', async () => {
    const engine = new NutritionEngine(createFoodRepository());
    const store = new InMemoryMealGroupStore();
    const service = new MealGroupService(store, engine);

    await service.submit({
      userId: user,
      mealDate: date,
      mealType: 'snack',
      mode: 'append',
      idempotencyKey: 's1',
      nutritionDataVersion: 'test',
      items: [{ foodId: milk.id, rawName: '牛奶', amountG: 200 }]
    });
    await service.submit({
      userId: user,
      mealDate: date,
      mealType: 'snack',
      mode: 'append',
      idempotencyKey: 's2',
      nutritionDataVersion: 'test',
      items: [{ foodId: egg.id, rawName: '鸡蛋', amountG: 50 }]
    });

    const snacks = store.groups.filter((group) => group.mealType === 'snack');
    expect(snacks.map((group) => group.instanceSeq).sort()).toEqual([1, 2]);
  });
});
