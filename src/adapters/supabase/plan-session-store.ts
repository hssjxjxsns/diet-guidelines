import type { SupabaseClient } from '@supabase/supabase-js';
import type { PlanItem } from '../../shared/types.ts';
import type { PlanSessionStore } from '../../services/planning.ts';

export class SupabasePlanSessionStore implements PlanSessionStore {
  constructor(private readonly client: SupabaseClient) {}

  async save(input: {
    userId: string;
    mealDate: string;
    mealType: string;
    status: 'planning' | 'awaiting_log';
    contextSnapshot: Record<string, unknown>;
    validationResult: unknown;
    items: PlanItem[];
  }): Promise<string> {
    const { data: session, error } = await this.client
      .from('plan_sessions')
      .insert({
        user_id: input.userId,
        meal_date: input.mealDate,
        meal_type: input.mealType,
        status: input.status,
        context_snapshot: input.contextSnapshot,
        validation_result: input.validationResult
      })
      .select('id')
      .single();
    if (error) throw error;

    if (input.items.length > 0) {
      const { error: itemsError } = await this.client.from('plan_items').insert(
        input.items.map((item) => ({
          session_id: session.id,
          food_id: item.foodId ?? null,
          recipe_id: item.recipeId ?? null,
          name: item.name,
          amount_g: item.amountG,
          food_group: item.foodGroup,
          tags: item.tags,
          allergens: item.allergens,
          reason: item.reason ?? null,
          citations: item.citations ?? [],
          rejected: false
        }))
      );
      if (itemsError) throw itemsError;
    }

    return session.id as string;
  }
}
