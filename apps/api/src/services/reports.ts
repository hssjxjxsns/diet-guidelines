import { z } from 'zod';
import type { AiGateway } from '../ai/gateway.ts';
import { addNutrients, emptyNutrients, roundNutrients, sumEntryTotals } from '../nutrition/engine.ts';
import type { MealEntry, Nutrients, ValueType } from '../shared/types.ts';
import { NUTRIENT_KEYS } from '../shared/types.ts';
import { sha256Hex, stableStringify } from '../shared/hash.ts';

export interface DailyAggregate {
  date: string;
  totals: Nutrients;
  valueType: ValueType;
}

export interface PeriodStats {
  days: number;
  total: Nutrients;
  average: Nutrients;
}

export interface PeriodReport {
  userId: string;
  periodType: 'weekly' | 'monthly';
  periodStart: string;
  periodEnd: string;
  stats: PeriodStats;
  aiSummary?: string;
  inputHash: string;
  status: 'fresh' | 'stale';
  generatedAt: string;
}

export interface PeriodReportStore {
  get(userId: string, periodType: 'weekly' | 'monthly', periodStart: string): Promise<PeriodReport | null>;
  save(report: PeriodReport): Promise<void>;
}

const summarySchema = z.object({ summary: z.string() });

export function buildPeriodStats(days: DailyAggregate[]): PeriodStats {
  const total = days.reduce((acc, day) => addNutrients(acc, day.totals), emptyNutrients());
  const average = days.length === 0 ? {} : scaleBy(total, 1 / days.length);
  return { days: days.length, total: roundNutrients(total), average: roundNutrients(average) };
}

export function aggregateEntries(entries: MealEntry[]): Nutrients {
  return sumEntryTotals(entries.map((entry) => entry.totals));
}

export class ReportService {
  constructor(
    private readonly store: PeriodReportStore,
    private readonly ai?: AiGateway
  ) {}

  async getOrCreatePeriod(params: {
    userId: string;
    periodType: 'weekly' | 'monthly';
    periodStart: string;
    periodEnd: string;
    days: DailyAggregate[];
  }): Promise<PeriodReport> {
    const inputHash = await sha256Hex(stableStringify(params.days.map((day) => ({ date: day.date, totals: day.totals }))));
    const cached = await this.store.get(params.userId, params.periodType, params.periodStart);
    if (cached && cached.inputHash === inputHash) return cached;

    const stats = buildPeriodStats(params.days);
    const report: PeriodReport = {
      userId: params.userId,
      periodType: params.periodType,
      periodStart: params.periodStart,
      periodEnd: params.periodEnd,
      stats,
      inputHash,
      status: 'fresh',
      generatedAt: new Date().toISOString()
    };

    if (this.ai) {
      report.aiSummary = await this.summarize(stats);
    }

    await this.store.save(report);
    return report;
  }

  private async summarize(stats: PeriodStats): Promise<string> {
    const result = await this.ai!.chatJson(
      {
        scene: 'period_summary',
        system:
          '你是膳食记录总结助手。只根据给定的统计数字写一段 150 字以内的总结，不生成新数字，不做疾病判断，给出一般性膳食建议。只输出 JSON：{"summary":"..."}',
        user: JSON.stringify(stats),
        maxOutputTokens: 400,
        temperature: 0.4
      },
      summarySchema
    );
    return result.summary;
  }
}

function scaleBy(values: Nutrients, factor: number): Nutrients {
  const result: Nutrients = {};
  for (const key of NUTRIENT_KEYS) {
    const value = values[key];
    if (value === undefined) continue;
    result[key] = value * factor;
  }
  return result;
}
