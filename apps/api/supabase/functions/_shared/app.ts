import { createClient } from 'npm:@supabase/supabase-js@2';
import { loadConfig } from '../../../src/config.ts';
import { ZhipuGateway } from '../../../src/ai/gateway.ts';
import { SupabaseFoodRepository } from '../../../src/adapters/supabase/food-repository.ts';
import { SupabaseSettingsRepository } from '../../../src/adapters/supabase/settings-repository.ts';
import { SupabaseGuidelineRetriever } from '../../../src/adapters/supabase/guideline-retriever.ts';
import { SupabasePlanSessionStore } from '../../../src/adapters/supabase/plan-session-store.ts';
import { SupabaseMealGroupStore } from '../../../src/adapters/supabase/meal-group-store.ts';
import { SupabasePeriodReportStore } from '../../../src/adapters/supabase/period-report-store.ts';
import { SupabasePlanningContextLoader } from '../../../src/adapters/supabase/planning-context-loader.ts';
import { NutritionEngine } from '../../../src/nutrition/engine.ts';
import { PlanValidator } from '../../../src/nutrition/validator.ts';
import { PlanningService } from '../../../src/services/planning.ts';
import { MealGroupService } from '../../../src/services/meal-groups.ts';
import { ReportService } from '../../../src/services/reports.ts';
import { DishAnalyzer } from '../../../src/dish/analyzer.ts';

export function buildApp() {
  const config = loadConfig(Deno.env.toObject() as unknown as NodeJS.ProcessEnv);

  const client = createClient(config.supabase.url, config.supabase.serviceRoleKey, {
    auth: { persistSession: false, autoRefreshToken: false }
  });

  const ai = new ZhipuGateway(config.zhipu, (log) => console.log('ai_call', JSON.stringify(log)));
  const foods = new SupabaseFoodRepository(client);
  const engine = new NutritionEngine(foods);
  const validator = new PlanValidator(foods, engine);
  const settings = new SupabaseSettingsRepository(client);
  const contextLoader = new SupabasePlanningContextLoader(client, settings);
  const guidelines = new SupabaseGuidelineRetriever(client, ai);
  const sessions = new SupabasePlanSessionStore(client);

  const planning = new PlanningService({
    foods,
    engine,
    validator,
    ai,
    guidelines,
    sessions,
    contextLoader
  });

  const mealStore = new SupabaseMealGroupStore(client);
  const meals = new MealGroupService(mealStore, engine);
  const reports = new ReportService(new SupabasePeriodReportStore(client), ai);
  const dish = new DishAnalyzer(ai, foods);

  return { client, config, ai, foods, engine, validator, settings, planning, meals, mealStore, reports, dish };
}
