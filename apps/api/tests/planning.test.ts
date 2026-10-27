import { describe, expect, it } from 'vitest';
import { NutritionEngine } from '../src/nutrition/engine.ts';
import { PlanValidator } from '../src/nutrition/validator.ts';
import { PlanningService } from '../src/services/planning.ts';
import { evaluateStructure } from '../src/nutrition/structure.ts';
import type { GuidelineChunk } from '../src/shared/types.ts';
import {
  broccoli,
  chicken,
  createFoodRepository,
  defaultSettings,
  egg,
  rice
} from './support/fixtures.ts';
import { FakeAiGateway } from './support/fake-ai.ts';
import { InMemoryPlanSessionStore } from './support/memory-plan-session-store.ts';

const invalidPlan = {
  items: [
    {
      foodId: egg.id,
      name: '鸡蛋',
      amountG: 50,
      foodGroup: 'protein',
      tags: ['vegetarian'],
      allergens: ['蛋']
    }
  ],
  explanation: '',
  citations: []
};

const validPlan = {
  items: [
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
  ],
  explanation: '补充蔬菜与优质蛋白。',
  citations: ['G1']
};

function buildService(responses: unknown[]) {
  const foods = createFoodRepository();
  const engine = new NutritionEngine(foods);
  const validator = new PlanValidator(foods, engine);
  const ai = new FakeAiGateway(responses);
  const sessions = new InMemoryPlanSessionStore();

  const guidelines: GuidelineChunk[] = [
    { id: 'g1', content: '餐餐有蔬菜，保证每天摄入不少于 300g 新鲜蔬菜。', metadata: {} }
  ];

  const service = new PlanningService({
    foods,
    engine,
    validator,
    ai,
    guidelines: { retrieve: async () => guidelines },
    sessions,
    contextLoader: {
      load: async () => ({
        settings: { ...defaultSettings, allergens: ['蛋'] },
        dailyTargets: defaultSettings.dailyTargets,
        consumed: { energyKcal: 1200, proteinG: 45 },
        unloggedMeals: ['breakfast', 'lunch', 'dinner', 'snack'],
        structure: evaluateStructure({}, 'lunch', 0),
        recentTrends: [],
        inferredTrends: []
      })
    }
  });

  return { service, ai, sessions };
}

describe('PlanningService', () => {
  it('校验失败时携带 failed_rules 重生成', async () => {
    const { service, ai, sessions } = buildService([invalidPlan, validPlan]);

    const result = await service.create({
      userId: 'user-1',
      mealDate: '2026-10-26',
      mealType: 'lunch'
    });

    expect(result.validation.passed).toBe(true);
    expect(result.fallback).toBe(false);
    expect(result.items).toHaveLength(3);
    expect(ai.calls).toHaveLength(2);
    expect(ai.calls[1].user).toContain('R01_ALLERGEN');
    expect(sessions.saved).toHaveLength(1);
    expect(sessions.saved[0].contextSnapshot).toHaveProperty('candidateFoodIds');
  });

  it('连续失败后由规则引擎兜底', async () => {
    const { service } = buildService([invalidPlan, invalidPlan, invalidPlan]);

    const result = await service.create({
      userId: 'user-1',
      mealDate: '2026-10-26',
      mealType: 'lunch'
    });

    expect(result.fallback).toBe(true);
    expect(result.items.map((item) => item.name)).toEqual(['西兰花', '鸡胸肉', '米饭']);
  });
});
