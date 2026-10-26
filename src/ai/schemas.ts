import { z } from 'zod';

export const foodGroupSchema = z.enum([
  'grains',
  'vegetables',
  'fruits',
  'protein',
  'dairy',
  'soy_nuts',
  'oils',
  'other'
]);

export const dishAnalysisSchema = z.object({
  dishName: z.string().min(1),
  matchedFoodId: z.string().optional(),
  recipeProposal: z
    .object({
      ingredients: z.array(
        z.object({
          name: z.string().min(1),
          foodId: z.string().optional(),
          amountG: z.number().positive()
        })
      ),
      oilG: z.number().nonnegative(),
      cookingMethod: z.string(),
      servings: z.number().positive(),
      yieldG: z.number().positive()
    })
    .optional(),
  directEstimate: z
    .object({
      energyKcal: z.tuple([z.number().nonnegative(), z.number().nonnegative()]),
      proteinG: z.tuple([z.number().nonnegative(), z.number().nonnegative()]).optional(),
      confidence: z.number().min(0).max(1)
    })
    .optional(),
  confidence: z.number().min(0).max(1),
  basis: z.array(z.string()).default([]),
  needsUserConfirm: z.boolean().default(false)
});

export const mealPlanSchema = z.object({
  items: z.array(
    z.object({
      foodId: z.string().optional(),
      recipeId: z.string().optional(),
      name: z.string().min(1),
      amountG: z.number().positive(),
      foodGroup: foodGroupSchema,
      tags: z.array(z.string()).default([]),
      allergens: z.array(z.string()).default([]),
      reason: z.string().optional(),
      citations: z.array(z.string()).default([])
    })
  ),
  explanation: z.string().default(''),
  citations: z.array(z.string()).default([])
});

export const replaceConstraintsSchema = z.object({
  vegetarian: z.boolean().optional(),
  noSpicy: z.boolean().optional(),
  requiredFoodGroups: z.array(foodGroupSchema).optional(),
  minProteinG: z.number().nonnegative().optional(),
  maxEnergyKcal: z.number().nonnegative().optional()
});

export const validationVerdictSchema = z.object({
  items: z.array(z.unknown()),
  explanation: z.string()
});
