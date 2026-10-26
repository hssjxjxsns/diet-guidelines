import type { InferredTrend, TrendStatus, UserSettings } from '../shared/types.ts';

export interface TrendEvidenceInput {
  metric: InferredTrend['metric'];
  conclusion: string;
  windowStart: string;
  windowEnd: string;
  sampleMeals: number;
  deviations: number[];
  consecutivePeriods: number;
}

/**
 * 只有样本量达标且方向一致的窗口才产生候选结论；
 * 连续两个周期同向成立才升级为 verified。
 */
export function deriveInferredTrend(input: TrendEvidenceInput): InferredTrend | null {
  if (input.sampleMeals < 10 || input.deviations.length === 0) return null;

  const negative = input.deviations.filter((value) => value < 0).length;
  const positive = input.deviations.filter((value) => value > 0).length;
  const consistentRatio = Math.max(negative, positive) / input.deviations.length;
  if (consistentRatio < 0.7) return null;

  const averageDeviation =
    input.deviations.reduce((sum, value) => sum + value, 0) / input.deviations.length;

  const status: TrendStatus = input.consecutivePeriods >= 2 ? 'verified' : 'candidate';

  return {
    metric: input.metric,
    conclusion: input.conclusion,
    status,
    evidence: {
      windowStart: input.windowStart,
      windowEnd: input.windowEnd,
      sampleMeals: input.sampleMeals,
      deviation: Math.round(averageDeviation * 10) / 10
    }
  };
}

export interface ProfileStore {
  getSettings(userId: string): Promise<UserSettings | null>;
  saveSettings(userId: string, settings: UserSettings): Promise<void>;
  listTrends(userId: string): Promise<InferredTrend[]>;
  saveTrend(userId: string, trend: InferredTrend): Promise<void>;
  invalidateTrend(userId: string, metric: string): Promise<void>;
}

export class ProfileService {
  constructor(private readonly store: ProfileStore) {}

  async getSettings(userId: string): Promise<UserSettings | null> {
    return this.store.getSettings(userId);
  }

  /** 用户明确设置：只能通过用户操作写入，AI 流程不得调用此方法。 */
  async updateSettings(userId: string, settings: UserSettings): Promise<void> {
    await this.store.saveSettings(userId, settings);
  }

  /**
   * 刷新推断趋势：新结论写入；不再成立的旧结论置为 invalidated。
   */
  async refreshTrends(userId: string, next: Array<InferredTrend | null>, checkedMetrics: string[]): Promise<void> {
    const existing = await this.store.listTrends(userId);
    const nextValid = next.filter((trend): trend is InferredTrend => trend !== null);

    for (const trend of nextValid) {
      await this.store.saveTrend(userId, trend);
    }

    for (const metric of checkedMetrics) {
      const stillValid = nextValid.some((trend) => trend.metric === metric);
      const existed = existing.some((trend) => trend.metric === metric && trend.status !== 'invalidated');
      if (!stillValid && existed) {
        await this.store.invalidateTrend(userId, metric);
      }
    }
  }
}
