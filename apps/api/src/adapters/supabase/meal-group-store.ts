import type { SupabaseClient } from '@supabase/supabase-js';
import type { MealGroupStore } from '../../services/meal-groups.ts';
import { IdempotencyConflictError } from '../../shared/errors.ts';
import type {
  MealEntry,
  MealGroup,
  SubmitMealResult
} from '../../shared/types.ts';

export class SupabaseMealGroupStore implements MealGroupStore {
  constructor(private readonly client: SupabaseClient) {}

  async listGroups(userId: string, mealDate: string): Promise<MealGroup[]> {
    const { data, error } = await this.client
      .from('meal_groups')
      .select('*')
      .eq('user_id', userId)
      .eq('meal_date', mealDate)
      .order('meal_type')
      .order('instance_seq');
    if (error) throw error;
    return (data ?? []).map(mapGroup);
  }

  async createGroup(input: Omit<MealGroup, 'id' | 'createdAt'>): Promise<MealGroup> {
    const { data, error } = await this.client
      .from('meal_groups')
      .insert({
        user_id: input.userId,
        meal_date: input.mealDate,
        meal_type: input.mealType,
        instance_seq: input.instanceSeq,
        status: input.status
      })
      .select('*')
      .single();

    if (error) {
      if (error.code === '23505') {
        const existing = await this.listGroups(input.userId, input.mealDate);
        const found = existing.find(
          (group) =>
            group.mealType === input.mealType && group.instanceSeq === input.instanceSeq
        );
        if (found) return found;
      }
      throw error;
    }

    return mapGroup(data);
  }

  async listEntries(groupId: string): Promise<MealEntry[]> {
    const { data, error } = await this.client
      .from('meal_entries')
      .select('*')
      .eq('group_id', groupId)
      .order('revision');
    if (error) throw error;
    if (!data || data.length === 0) return [];

    const { data: items, error: itemsError } = await this.client
      .from('meal_entry_items')
      .select('*')
      .in('entry_id', data.map((entry: Record<string, unknown>) => entry.id));
    if (itemsError) throw itemsError;

    return data.map((entry: Record<string, any>) =>
      mapEntry(entry, (items ?? []).filter((item: Record<string, any>) => item.entry_id === entry.id))
    );
  }

  async insertEntry(input: Omit<MealEntry, 'id' | 'createdAt'>): Promise<MealEntry> {
    if (input.mode === 'replace') {
      const { error: resetError } = await this.client
        .from('meal_entries')
        .update({ is_current: false })
        .eq('group_id', input.groupId)
        .eq('mode', 'replace')
        .eq('is_current', true);
      if (resetError) throw resetError;
    }

    const { data, error } = await this.client
      .from('meal_entries')
      .insert({
        group_id: input.groupId,
        user_id: input.userId,
        mode: input.mode,
        revision: input.revision,
        base_revision: input.baseRevision ?? null,
        totals: input.totals,
        value_type: input.valueType,
        is_current: true
      })
      .select('*')
      .single();
    if (error) throw error;

    if (input.items.length > 0) {
      const { error: itemsError } = await this.client.from('meal_entry_items').insert(
        input.items.map((item) => ({
          entry_id: data.id,
          food_id: item.foodId ?? null,
          recipe_id: item.recipeId ?? null,
          raw_name: item.rawName,
          amount_g: item.amountG,
          value_type: item.valueType,
          nutrients: item.nutrients,
          analysis: item.analysis ?? null
        }))
      );

      if (itemsError) {
        await this.client.from('meal_entries').delete().eq('id', data.id);
        throw itemsError;
      }
    }

    const items = input.items.map((item) => ({ ...item }));
    return mapEntry(data, items);
  }

  async findIdempotent(
    userId: string,
    key: string,
    requestHash: string
  ): Promise<SubmitMealResult | null> {
    const { data, error } = await this.client
      .from('idempotency_keys')
      .select('request_hash, response, status')
      .eq('user_id', userId)
      .eq('key', key)
      .maybeSingle();
    if (error) throw error;
    if (!data) return null;
    if (data.request_hash !== requestHash) throw new IdempotencyConflictError();
    return data.status === 'completed' ? (data.response as SubmitMealResult) : null;
  }

  async saveIdempotent(
    userId: string,
    key: string,
    requestHash: string,
    result: SubmitMealResult
  ): Promise<void> {
    const { error } = await this.client.from('idempotency_keys').upsert({
      user_id: userId,
      key,
      scope: 'meal_entry',
      request_hash: requestHash,
      response: result,
      status: 'completed'
    });
    if (error) throw error;
  }
}

function mapGroup(row: Record<string, any>): MealGroup {
  return {
    id: row.id,
    userId: row.user_id,
    mealDate: row.meal_date,
    mealType: row.meal_type,
    instanceSeq: Number(row.instance_seq ?? 1),
    status: row.status,
    createdAt: row.created_at
  };
}

function mapEntry(row: Record<string, any>, items: Array<Record<string, any>>): MealEntry {
  return {
    id: row.id,
    groupId: row.group_id,
    userId: row.user_id,
    mode: row.mode,
    revision: Number(row.revision),
    baseRevision: row.base_revision ?? undefined,
    totals: row.totals ?? {},
    valueType: row.value_type,
    createdAt: row.created_at,
    items: items.map((item) => ({
      foodId: item.food_id ?? undefined,
      recipeId: item.recipe_id ?? undefined,
      rawName: item.raw_name,
      amountG: Number(item.amount_g),
      valueType: item.value_type,
      nutrients: item.nutrients ?? {},
      analysis: item.analysis ?? undefined
    }))
  };
}
