import type { Food, Recipe, UserSettings } from '../../src/shared/types.ts';
import { InMemoryFoodRepository } from './memory-food-repository.ts';

export const rice: Food = {
  id: 'f_rice_001',
  name: '米饭',
  aliases: ['白米饭'],
  category: 'grains',
  nutrientsPer100g: {
    energyKcal: 116,
    proteinG: 2.6,
    fatG: 0.3,
    carbG: 25.9,
    fiberG: 0.3,
    sodiumMg: 2.4,
    calciumMg: 7,
    ironMg: 0.3
  },
  edibleRatio: 1,
  source: 'fixture',
  sourceVersion: 'test',
  status: 'active',
  tags: ['vegetarian'],
  allergens: []
};

export const broccoli: Food = {
  id: 'f_broccoli_001',
  name: '西兰花',
  aliases: ['绿花菜'],
  category: 'vegetables',
  nutrientsPer100g: {
    energyKcal: 34,
    proteinG: 2.8,
    fatG: 0.6,
    carbG: 6.6,
    fiberG: 1.6,
    sodiumMg: 18,
    calciumMg: 67,
    ironMg: 1
  },
  edibleRatio: 1,
  source: 'fixture',
  sourceVersion: 'test',
  status: 'active',
  tags: ['vegetarian'],
  allergens: []
};

export const chicken: Food = {
  id: 'f_chicken_001',
  name: '鸡胸肉',
  aliases: ['鸡胸'],
  category: 'protein',
  nutrientsPer100g: {
    energyKcal: 133,
    proteinG: 24.6,
    fatG: 2.5,
    carbG: 0,
    fiberG: 0,
    sodiumMg: 45,
    calciumMg: 3,
    ironMg: 0.6
  },
  edibleRatio: 1,
  source: 'fixture',
  sourceVersion: 'test',
  status: 'active',
  tags: ['meat'],
  allergens: []
};

export const egg: Food = {
  id: 'f_egg_001',
  name: '鸡蛋',
  aliases: ['蛋'],
  category: 'protein',
  nutrientsPer100g: {
    energyKcal: 144,
    proteinG: 13.3,
    fatG: 8.8,
    carbG: 2.8,
    fiberG: 0,
    sodiumMg: 131,
    calciumMg: 56,
    ironMg: 2
  },
  edibleRatio: 0.88,
  source: 'fixture',
  sourceVersion: 'test',
  status: 'active',
  tags: ['vegetarian'],
  allergens: ['蛋']
};

export const oil: Food = {
  id: 'f_oil_001',
  name: '植物油',
  aliases: [],
  category: 'oils',
  nutrientsPer100g: { energyKcal: 899, fatG: 99.9 },
  edibleRatio: 1,
  source: 'fixture',
  sourceVersion: 'test',
  status: 'active',
  tags: ['vegetarian'],
  allergens: []
};

export const milk: Food = {
  id: 'f_milk_001',
  name: '牛奶',
  aliases: [],
  category: 'dairy',
  nutrientsPer100g: { energyKcal: 54, proteinG: 3, fatG: 3.2, carbG: 3.4, calciumMg: 104 },
  edibleRatio: 1,
  source: 'fixture',
  sourceVersion: 'test',
  status: 'active',
  tags: ['vegetarian'],
  allergens: ['奶']
};

export const tomatoEgg: Recipe = {
  id: 'r_tomato_egg_001',
  name: '番茄炒蛋',
  servings: 1,
  yieldG: 220,
  oilG: 10,
  cookingMethod: '炒',
  source: 'fixture',
  status: 'active',
  ingredients: [{ foodId: egg.id, amountG: 120 }],
  tags: ['vegetarian'],
  allergens: ['蛋']
};

export const sparerib: Recipe = {
  id: 'r_sparerib_001',
  name: '糖醋排骨',
  servings: 1,
  yieldG: 150,
  oilG: 15,
  cookingMethod: '红烧',
  source: 'fixture',
  status: 'active',
  ingredients: [{ foodId: 'f_pork_rib_001', amountG: 120 }],
  tags: ['meat'],
  allergens: []
};

export const porkRib: Food = {
  id: 'f_pork_rib_001',
  name: '猪肋排',
  aliases: ['排骨'],
  category: 'protein',
  nutrientsPer100g: {
    energyKcal: 278,
    proteinG: 16.7,
    fatG: 23.1,
    carbG: 0.7,
    fiberG: 0,
    sodiumMg: 62,
    calciumMg: 14,
    ironMg: 1.4
  },
  edibleRatio: 1,
  source: 'fixture',
  sourceVersion: 'test',
  status: 'active',
  tags: ['meat'],
  allergens: []
};

export const defaultSettings: UserSettings = {
  dailyTargets: {
    energyKcal: 2000,
    proteinG: 75,
    fatG: 60,
    carbG: 275,
    fiberG: 25,
    sodiumMg: 2000,
    calciumMg: 800,
    ironMg: 15
  },
  preferences: ['清淡'],
  allergens: [],
  avoid: []
};

export function createFoodRepository(): InMemoryFoodRepository {
  return new InMemoryFoodRepository(
    [rice, broccoli, chicken, egg, oil, milk, porkRib],
    [sparerib, tomatoEgg]
  );
}
