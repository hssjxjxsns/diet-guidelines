import { buildApp } from '../_shared/app.ts';
import { errorResponse, json, preflight, requireUser, routeSegments } from '../_shared/http.ts';

Deno.serve(async (request) => {
  if (request.method === 'OPTIONS') return preflight();

  const app = buildApp();
  const auth = await requireUser(request, app.client);
  if (auth instanceof Response) return auth;

  const segments = routeSegments(request, 'plan-sessions');

  try {
    if (request.method === 'POST' && segments.length === 0) {
      const body = await request.json();
      const result = await app.planning.create({
        userId: auth.id,
        mealDate: body.meal_date,
        mealType: body.meal_type
      });
      return json(result);
    }

    if (request.method === 'GET' && segments.length === 1) {
      const { data, error } = await app.client
        .from('plan_sessions')
        .select('*, plan_items(*)')
        .eq('id', segments[0])
        .eq('user_id', auth.id)
        .maybeSingle();
      if (error) throw error;
      if (!data) return json({ code: 'NOT_FOUND', message: 'session not found' }, 404);
      return json(data);
    }

    if (request.method === 'POST' && segments.length === 2 && segments[1] === 'messages') {
      const body = await request.json();
      const { data: session, error } = await app.client
        .from('plan_sessions')
        .select('*')
        .eq('id', segments[0])
        .eq('user_id', auth.id)
        .maybeSingle();
      if (error) throw error;
      if (!session) return json({ code: 'NOT_FOUND', message: 'session not found' }, 404);

      const result = await app.planning.revise({
        userId: auth.id,
        mealDate: session.meal_date,
        mealType: session.meal_type,
        currentMenu: body.current_menu ?? [],
        replaceConstraints: body.constraints ?? {}
      });
      return json(result);
    }

    if (request.method === 'POST' && segments.length === 2 && segments[1] === 'complete') {
      const { error } = await app.client
        .from('plan_sessions')
        .update({ status: 'awaiting_log', updated_at: new Date().toISOString() })
        .eq('id', segments[0])
        .eq('user_id', auth.id);
      if (error) throw error;
      return json({ ok: true });
    }

    return json({ code: 'NOT_FOUND', message: 'route not found' }, 404);
  } catch (error) {
    return errorResponse(error);
  }
});
