import type { SupabaseClient } from '@supabase/supabase-js';
import type { PlanningContextLoader, UserPlanningContext } from '../../services/planning.ts';
import type { InferredTrend, MealType, RecentTrend, StructureResult } from '../../shared/types.ts';
import { evaluateStructure } from '../../nutrition/structure.ts';
import type { SettingsStore } from './settings-repository.ts';

const ALL_MEALS: MealType[] = ['breakfast', 'lunch', 'dinner', 'snack'];

export class SupabasePlanningContextLoader implements PlanningContextLoader {
  constructor(
    private readonly client: SupabaseClient,
    private readonly settingsStore: SettingsStore
  ) {}

  async load(userId: string, mealDate: string, mealType: MealType): Promise<UserPlanningContext> {
    const settings = await this.settingsStore.getSettings(userId);
    if (!settings) throw new Error('user settings not found');

    const [statsResult, groupsResult, trendsResult] = await Promise.all([
      this.client
        .from('daily_stats')
        .select('metrics, structure')
        .eq('user_id', userId)
        .eq('stat_date', mealDate)
        .maybeSingle(),
      this.client
        .from('meal_groups')
        .select('meal_type')
        .eq('user_id', userId)
        .eq('meal_date', mealDate),
      this.client
        .from('inferred_trends')
        .select('*')
        .eq('user_id', userId)
        .neq('status', 'invalidated')
    ]);

    if (statsResult.error) throw statsResult.error;
    if (groupsResult.error) throw groupsResult.error;
    if (trendsResult.error) throw trendsResult.error;

    const consumed = (statsResult.data?.metrics?.values ?? statsResult.data?.metrics ?? {}) as Record<
      string,
      number
    >;
    const structure =
      (statsResult.data?.structure as StructureResult | undefined) ??
      evaluateStructure({}, mealType, 0);

    const loggedTypes = new Set((groupsResult.data ?? []).map((row: any) => row.meal_type));
    const unloggedMeals = ALL_MEALS.filter((meal) => !loggedTypes.has(meal));

    const inferredTrends: InferredTrend[] = (trendsResult.data ?? []).map((row: any) => ({
      metric: row.metric,
      conclusion: row.conclusion,
      status: row.status,
      evidence: row.evidence
    }));

    const recentTrends: RecentTrend[] = inferredTrends
      .filter((trend) => trend.status !== 'invalidated')
      .map((trend) => ({
        metric: trend.metric,
        direction: trend.evidence.deviation < 0 ? 'down' : trend.evidence.deviation > 0 ? 'up' : 'stable',
        summary: trend.conclusion
      }));

    return {
      settings,
      dailyTargets: settings.dailyTargets,
      consumed,
      unloggedMeals,
      structure,
      recentTrends,
      inferredTrends
    };
  }
}
