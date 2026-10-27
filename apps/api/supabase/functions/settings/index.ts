import { buildApp } from '../_shared/app.ts';
import { errorResponse, json, preflight, requireUser } from '../_shared/http.ts';

Deno.serve(async (request) => {
  if (request.method === 'OPTIONS') return preflight();

  const app = buildApp();
  const auth = await requireUser(request, app.client);
  if (auth instanceof Response) return auth;

  try {
    if (request.method === 'GET') {
      const settings = await app.settings.getSettings(auth.id);
      return json(settings);
    }

    if (request.method === 'PUT') {
      const body = await request.json();
      await app.settings.saveSettings(auth.id, {
        dailyTargets: body.daily_targets ?? body.dailyTargets ?? {},
        preferences: body.preferences ?? [],
        allergens: body.allergens ?? [],
        avoid: body.avoid ?? []
      });
      return json({ ok: true });
    }

    return json({ code: 'NOT_FOUND', message: 'route not found' }, 404);
  } catch (error) {
    return errorResponse(error);
  }
});
