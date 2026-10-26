import { buildApp } from '../_shared/app.ts';
import { errorResponse, json, preflight, requireUser } from '../_shared/http.ts';

Deno.serve(async (request) => {
  if (request.method === 'OPTIONS') return preflight();

  const app = buildApp();
  const auth = await requireUser(request, app.client);
  if (auth instanceof Response) return auth;

  try {
    if (request.method !== 'POST') {
      return json({ code: 'NOT_FOUND', message: 'route not found' }, 404);
    }

    const body = await request.json();
    if (!body.text && !body.image_url) {
      return json({ code: 'VALIDATION_FAILED', message: 'text or image_url is required' }, 422);
    }

    const analysis = await app.dish.analyze({
      text: body.text,
      imageUrl: body.image_url,
      hints: body.hints
    });

    if (analysis.recipeProposal) {
      const { error } = await app.client.from('recipe_candidates').insert({
        user_id: auth.id,
        dish_name: analysis.dishName,
        proposed_recipe: analysis.recipeProposal,
        confidence: analysis.confidence,
        source: 'ai_inferred',
        status: 'pending_review'
      });
      if (error) throw error;
    }

    return json(analysis);
  } catch (error) {
    return errorResponse(error);
  }
});
