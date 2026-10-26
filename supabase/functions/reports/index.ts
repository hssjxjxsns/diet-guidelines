import { buildApp } from '../_shared/app.ts';
import { errorResponse, json, preflight, requireUser, routeSegments } from '../_shared/http.ts';

Deno.serve(async (request) => {
  if (request.method === 'OPTIONS') return preflight();

  const app = buildApp();
  const auth = await requireUser(request, app.client);
  if (auth instanceof Response) return auth;

  const segments = routeSegments(request, 'reports');

  try {
    if (request.method !== 'GET') {
      return json({ code: 'NOT_FOUND', message: 'route not found' }, 404);
    }

    const url = new URL(request.url);
    const periodType = (segments[0] ?? url.searchParams.get('type') ?? 'weekly') as 'weekly' | 'monthly';
    const start = url.searchParams.get('start');
    if (!start || (periodType !== 'weekly' && periodType !== 'monthly')) {
      return json({ code: 'VALIDATION_FAILED', message: 'valid type and start are required' }, 422);
    }

    const startDate = new Date(`${start}T00:00:00Z`);
    const endDate = new Date(startDate);
    if (periodType === 'weekly') {
      endDate.setUTCDate(endDate.getUTCDate() + 6);
    } else {
      endDate.setUTCMonth(endDate.getUTCMonth() + 1, 0);
    }
    const end = endDate.toISOString().slice(0, 10);

    const { data, error } = await app.client
      .from('daily_stats')
      .select('stat_date, metrics, value_type')
      .eq('user_id', auth.id)
      .gte('stat_date', start)
      .lte('stat_date', end)
      .order('stat_date');
    if (error) throw error;

    const report = await app.reports.getOrCreatePeriod({
      userId: auth.id,
      periodType,
      periodStart: start,
      periodEnd: end,
      days: (data ?? []).map((row: Record<string, any>) => ({
        date: row.stat_date,
        totals: row.metrics?.values ?? {},
        valueType: row.value_type ?? 'measured'
      }))
    });

    return json(report);
  } catch (error) {
    return errorResponse(error);
  }
});
