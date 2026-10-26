import type {
  Food,
  GuidelineChunk,
  InferredTrend,
  MealType,
  Nutrients,
  PlanItem,
  RecentTrend,
  StructureResult,
  UserSettings
} from '../shared/types.ts';

export interface PlanningContextInput {
  mealDate: string;
  mealType: MealType;
  dailyTargets: Nutrients;
  consumed: Nutrients;
  remaining: Nutrients;
  unloggedMeals: MealType[];
  structure: StructureResult;
  recentTrends: RecentTrend[];
  explicitSettings: UserSettings;
  inferredTrends: InferredTrend[];
  guidelines: GuidelineChunk[];
  candidateFoods: Food[];
  currentMenu?: PlanItem[];
}

export interface PlanningContext {
  text: string;
  estimatedTokens: number;
  snapshot: Record<string, unknown>;
}

export function buildPlanningContext(input: PlanningContextInput): PlanningContext {
  const verifiedTrends = input.inferredTrends.filter((trend) => trend.status === 'verified');

  const snapshot = {
    mealDate: input.mealDate,
    mealType: input.mealType,
    dailyTargets: input.dailyTargets,
    consumed: input.consumed,
    remaining: input.remaining,
    unloggedMeals: input.unloggedMeals,
    structure: input.structure,
    recentTrends: input.recentTrends,
    settings: {
      preferences: input.explicitSettings.preferences,
      allergens: input.explicitSettings.allergens,
      avoid: input.explicitSettings.avoid
    },
    inferredTrends: verifiedTrends,
    guidelineIds: input.guidelines.map((chunk) => chunk.id),
    candidateFoodIds: input.candidateFoods.map((food) => food.id)
  };

  const lines: string[] = [
    `规划日期：${input.mealDate}，当前餐次：${input.mealType}`,
    `每日目标：${JSON.stringify(input.dailyTargets)}`,
    `今日已摄入：${JSON.stringify(input.consumed)}`,
    `今日剩余：${JSON.stringify(input.remaining)}`,
    `尚未记录的餐次：${input.unloggedMeals.join('、') || '无'}`,
    `膳食结构达成情况：${JSON.stringify(
      input.structure.foodGroups.map((group) => ({
        group: group.group,
        amountG: group.amountG,
        recommendedRangeG: group.recommendedRangeG,
        status: group.status
      }))
    )}`,
    `近期趋势：${input.recentTrends.length > 0 ? JSON.stringify(input.recentTrends) : '无'}`,
    `长期已验证趋势：${verifiedTrends.length > 0 ? JSON.stringify(verifiedTrends) : '无'}`,
    `忌口与过敏原：${JSON.stringify({
      allergens: input.explicitSettings.allergens,
      avoid: input.explicitSettings.avoid,
      preferences: input.explicitSettings.preferences
    })}`,
    `候选食物（只能使用这些 foodId）：${JSON.stringify(
      input.candidateFoods.map((food) => ({
        foodId: food.id,
        name: food.name,
        category: food.category,
        energyKcalPer100g: food.nutrientsPer100g.energyKcal,
        tags: food.tags,
        allergens: food.allergens
      }))
    )}`,
    `指南片段：${JSON.stringify(
      input.guidelines.map((chunk, index) => ({ id: `G${index + 1}`, content: chunk.content }))
    )}`
  ];

  if (input.currentMenu && input.currentMenu.length > 0) {
    lines.push(
      `当前餐单：${JSON.stringify(
        input.currentMenu.map((item) => ({
          name: item.name,
          foodId: item.foodId,
          recipeId: item.recipeId,
          amountG: item.amountG,
          foodGroup: item.foodGroup
        }))
      )}`
    );
  }

  const text = lines.join('\n');
  return {
    text,
    estimatedTokens: Math.ceil(text.length / 1.8),
    snapshot
  };
}
