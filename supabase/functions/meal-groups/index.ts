import { buildApp } from '../_shared/app.ts';
import { errorResponse, json, preflight, requireUser, routeSegments } from '../_shared/http.ts';

Deno.serve(async (request) => {
  if (request.method === 'OPTIONS') return preflight();

  const app = buildApp();
  const auth = await requireUser(request, app.client);
  if (auth instanceof Response) return auth;

  const segments = routeSegments(request, 'meal-groups');

  try {
    if (request.method === 'GET' && segments.length === 0) {
      const date = new URL(request.url).searchParams.get('date');
      if (!date) return json({ code: 'VALIDATION_FAILED', message: 'date is required' }, 422);
      const day = await app.meals.getDay(auth.id, date);
      return json(day);
    }

    if (request.method === 'POST' && segments.length === 0) {
      const body = await request.json();
      const group = await app.mealStore.createGroup({
        userId: auth.id,
        mealDate: body.meal_date,
        mealType: body.meal_type,
        instanceSeq: body.instance_seq ?? 1,
        status: 'active'
      });
      return json(group);
    }

    const groupId = segments[0];

    if (request.method === 'GET' && groupId) {
      const { data: group, error } = await app.client
        .from('meal_groups')
        .select('*')
        .eq('id', groupId)
        .eq('user_id', auth.id)
        .maybeSingle();
      if (error) throw error;
      if (!group) return json({ code: 'NOT_FOUND', message: 'group not found' }, 404);

      const entries = await app.mealStore.listEntries(groupId);
      return json({ group, entries });
    }

    if (request.method === 'POST' && groupId && segments[1] === 'entries') {
      const body = await request.json();
      const { data: group, error } = await app.client
        .from('meal_groups')
        .select('*')
        .eq('id', groupId)
        .eq('user_id', auth.id)
        .maybeSingle();
      if (error) throw error;
      if (!group) return json({ code: 'NOT_FOUND', message: 'group not found' }, 404);

      const result = await app.meals.submit({
        userId: auth.id,
        mealDate: group.meal_date,
        mealType: group.meal_type,
        instanceSeq: group.instance_seq,
        mode: body.mode,
        baseRevision: body.base_revision,
        idempotencyKey: body.idempotency_key,
        items: body.items ?? [],
        nutritionDataVersion: body.nutrition_data_version ?? 'demo-1'
      });

      const day = await app.meals.getDay(auth.id, group.meal_date);
      const { data: existing } = await app.client
        .from('daily_stats')
        .select('structure')
        .eq('user_id', auth.id)
        .eq('stat_date', group.meal_date)
        .maybeSingle();

      const { error: statsError } = await app.client.from('daily_stats').upsert({
        user_id: auth.id,
        stat_date: group.meal_date,
        metrics: { values: day.dailyTotals },
        structure: existing?.structure ?? {},
        value_type: result.entry.valueType,
        computed_at: new Date().toISOString()
      });
      if (statsError) throw statsError;

      await app.client
        .from('period_reports')
        .update({ status: 'stale' })
        .eq('user_id', auth.id)
        .lte('period_start', group.meal_date)
        .gte('period_end', group.meal_date);

      return json(result);
    }

    return json({ code: 'NOT_FOUND', message: 'route not found' }, 404);
  } catch (error) {
    return errorResponse(error);
  }
});
