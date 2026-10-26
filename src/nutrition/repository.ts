import type { Food, Recipe } from '../shared/types.ts';

export interface FoodRepository {
  findById(id: string): Promise<Food | null>;
  findByName(name: string): Promise<Food | null>;
  search(query: string, limit?: number): Promise<Food[]>;
  listActive(limit?: number): Promise<Food[]>;
  getRecipe(id: string): Promise<Recipe | null>;
}
