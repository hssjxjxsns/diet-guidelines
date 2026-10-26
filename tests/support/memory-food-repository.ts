import type { Food, Recipe } from '../../src/shared/types.ts';
import type { FoodRepository } from '../../src/nutrition/repository.ts';

export class InMemoryFoodRepository implements FoodRepository {
  constructor(
    public readonly foods: Food[],
    public readonly recipes: Recipe[] = []
  ) {}

  async findById(id: string): Promise<Food | null> {
    return this.foods.find((food) => food.id === id) ?? null;
  }

  async findByName(name: string): Promise<Food | null> {
    const normalized = name.trim().toLowerCase();
    return (
      this.foods.find(
        (food) =>
          food.name.toLowerCase() === normalized ||
          food.aliases.some((alias) => alias.toLowerCase() === normalized)
      ) ?? null
    );
  }

  async search(query: string, limit = 10): Promise<Food[]> {
    const normalized = query.trim().toLowerCase();
    return this.foods
      .filter(
        (food) =>
          food.name.toLowerCase().includes(normalized) ||
          food.aliases.some((alias) => alias.toLowerCase().includes(normalized))
      )
      .slice(0, limit);
  }

  async listActive(limit = 40): Promise<Food[]> {
    return this.foods.filter((food) => food.status === 'active').slice(0, limit);
  }

  async getRecipe(id: string): Promise<Recipe | null> {
    return this.recipes.find((recipe) => recipe.id === id) ?? null;
  }
}
