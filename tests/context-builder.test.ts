import { describe, expect, it } from 'vitest';
import { buildPlanningContext } from '../src/context/builder.ts';
import { evaluateStructure } from '../src/nutrition/structure.ts';
import { broccoli, chicken, defaultSettings, rice } from './support/fixtures.ts';

describe('buildPlanningContext', () => {
  it('只提供结构化摘要，不包含原始流水账', () => {
    const context = buildPlanningContext({
      mealDate: '2026-10-26',
      mealType: 'lunch',
      dailyTargets: defaultSettings.dailyTargets,
      consumed: { energyKcal: 600, proteinG: 30 },
      remaining: { energyKcal: 1400, proteinG: 45 },
      unloggedMeals: ['lunch', 'dinner'],
      structure: evaluateStructure({ vegetables: 80 }, 'lunch', 2),
      recentTrends: [{ metric: 'vegetables_g', direction: 'down', summary: '近三天蔬菜偏少' }],
      explicitSettings: {
        ...defaultSettings,
        allergens: ['花生'],
        avoid: ['香菜']
      },
      inferredTrends: [
        {
          metric: 'vegetables_g',
          conclusion: '蔬菜摄入偏低',
          status: 'verified',
          evidence: {
            windowStart: '2026-10-18',
            windowEnd: '2026-10-24',
            sampleMeals: 12,
            deviation: -120
          }
        }
      ],
      guidelines: [{ id: 'g1', content: '餐餐有蔬菜，保证每天摄入不少于 300g 新鲜蔬菜。', metadata: {} }],
      candidateFoods: [rice, broccoli, chicken]
    });

    expect(context.text).toContain('花生');
    expect(context.text).toContain('蔬菜摄入偏低');
    expect(context.text).toContain('餐餐有蔬菜');
    expect(context.text).not.toContain('meal_logs');
    expect(context.estimatedTokens).toBeGreaterThan(0);
    expect(context.snapshot.candidateFoodIds).toEqual(['f_rice_001', 'f_broccoli_001', 'f_chicken_001']);
  });
});
