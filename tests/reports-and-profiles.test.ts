import { describe, expect, it } from 'vitest';
import { buildPeriodStats } from '../src/services/reports.ts';
import { deriveInferredTrend } from '../src/services/profiles.ts';

describe('period statistics', () => {
  it('按天汇总并计算平均值', () => {
    const stats = buildPeriodStats([
      { date: '2026-10-20', totals: { energyKcal: 1900, proteinG: 70 }, valueType: 'measured' },
      { date: '2026-10-21', totals: { energyKcal: 2100, proteinG: 80 }, valueType: 'estimated' }
    ]);

    expect(stats.days).toBe(2);
    expect(stats.total.energyKcal).toBe(4000);
    expect(stats.average.energyKcal).toBe(2000);
    expect(stats.average.proteinG).toBe(75);
  });
});

describe('inferred trends', () => {
  const base = {
    metric: 'vegetables_g' as const,
    conclusion: '蔬菜摄入偏低',
    windowStart: '2026-10-18',
    windowEnd: '2026-10-24',
    sampleMeals: 12,
    deviations: [-100, -80, -120]
  };

  it('样本不足或方向不一致时不产生结论', () => {
    expect(deriveInferredTrend({ ...base, sampleMeals: 5, consecutivePeriods: 2 })).toBeNull();
    expect(
      deriveInferredTrend({ ...base, deviations: [-100, 80, -120], consecutivePeriods: 2 })
    ).toBeNull();
  });

  it('单周期为 candidate，连续两周期升级为 verified', () => {
    expect(deriveInferredTrend({ ...base, consecutivePeriods: 1 })?.status).toBe('candidate');
    expect(deriveInferredTrend({ ...base, consecutivePeriods: 2 })?.status).toBe('verified');
  });
});
