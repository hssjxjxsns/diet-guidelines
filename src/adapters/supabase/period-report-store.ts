import type { SupabaseClient } from '@supabase/supabase-js';
import type { PeriodReport, PeriodReportStore } from '../../services/reports.ts';

export class SupabasePeriodReportStore implements PeriodReportStore {
  constructor(private readonly client: SupabaseClient) {}

  async get(
    userId: string,
    periodType: 'weekly' | 'monthly',
    periodStart: string
  ): Promise<PeriodReport | null> {
    const { data, error } = await this.client
      .from('period_reports')
      .select('*')
      .eq('user_id', userId)
      .eq('period_type', periodType)
      .eq('period_start', periodStart)
      .maybeSingle();
    if (error) throw error;
    return data ? mapReport(data) : null;
  }

  async save(report: PeriodReport): Promise<void> {
    const { error } = await this.client.from('period_reports').upsert({
      user_id: report.userId,
      period_type: report.periodType,
      period_start: report.periodStart,
      period_end: report.periodEnd,
      stats: report.stats,
      ai_summary: report.aiSummary ?? null,
      status: report.status,
      input_hash: report.inputHash,
      generated_at: report.generatedAt
    });
    if (error) throw error;
  }
}

function mapReport(row: Record<string, any>): PeriodReport {
  return {
    userId: row.user_id,
    periodType: row.period_type,
    periodStart: row.period_start,
    periodEnd: row.period_end,
    stats: row.stats,
    aiSummary: row.ai_summary ?? undefined,
    inputHash: row.input_hash ?? '',
    status: row.status,
    generatedAt: row.generated_at
  };
}
