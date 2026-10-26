import type { SupabaseClient } from '@supabase/supabase-js';
import type { Food, Nutrients, Recipe } from '../../shared/types.ts';
import type { FoodRepository } from '../../nutrition/repository.ts';

export class SupabaseFoodRepository implements FoodRepository {
  constructor(private readonly client: SupabaseClient) {}

  async findById(id: string): Promise<Food | null> {
    const { data, error } = await this.client
      .from('nutrition_foods')
      .select('*')
      .eq('id', id)
      .maybeSingle();
    if (error) throw error;
    return data ? mapFood(data) : null;
  }

  async findByName(name: string): Promise<Food | null> {
    const exact = await this.client
      .from('nutrition_foods')
      .select('*')
      .eq('status', 'active')
      .eq('name', name)
      .maybeSingle();
    if (exact.error) throw exact.error;
    if (exact.data) return mapFood(exact.data);

    const alias = await this.client
      .from('nutrition_foods')
      .select('*')
      .eq('status', 'active')
      .contains('aliases', [name])
      .limit(1)
      .maybeSingle();
    if (alias.error) throw alias.error;
    return alias.data ? mapFood(alias.data) : null;
  }

  async search(query: string, limit = 10): Promise<Food[]> {
    const { data, error } = await this.client
      .from('nutrition_foods')
      .select('*')
      .eq('status', 'active')
      .ilike('name', `%${query}%`)
      .limit(limit);
    if (error) throw error;
    if (data && data.length > 0) return data.map(mapFood);
    return this.listActive(limit);
  }

  async listActive(limit = 40): Promise<Food[]> {
    const { data, error } = await this.client
      .from('nutrition_foods')
      .select('*')
      .eq('status', 'active')
      .order('name')
      .limit(limit);
    if (error) throw error;
    return (data ?? []).map(mapFood);
  }

  async getRecipe(id: string): Promise<Recipe | null> {
    const { data, error } = await this.client
      .from('recipes')
      .select('*, recipe_ingredients(food_id, amount_g)')
      .eq('id', id)
      .eq('status', 'active')
      .maybeSingle();
    if (error) throw error;
    if (!data) return null;

    return {
      id: data.id,
      name: data.name,
      servings: Number(data.servings ?? 1),
      yieldG: Number(data.yield_g ?? 0),
      oilG: Number(data.oil_g ?? 0),
      cookingMethod: data.cooking_method ?? '',
      source: data.source ?? '',
      status: data.status,
      ingredients: (data.recipe_ingredients ?? []).map((ingredient: Record<string, unknown>) => ({
        foodId: String(ingredient.food_id),
        amountG: Number(ingredient.amount_g)
      })),
      tags: data.tags ?? [],
      allergens: data.allergens ?? []
    };
  }
}

function mapFood(row: Record<string, any>): Food {
  return {
    id: row.id,
    name: row.name,
    aliases: row.aliases ?? [],
    category: row.category,
    nutrientsPer100g: (row.nutrients_per_100g ?? {}) as Nutrients,
    edibleRatio: Number(row.edible_ratio ?? 1),
    source: row.source ?? '',
    sourceVersion: row.source_version ?? '',
    status: row.status,
    tags: row.tags ?? [],
    allergens: row.allergens ?? [],
    unitConversion: row.unit_conversion ?? undefined
  };
}
