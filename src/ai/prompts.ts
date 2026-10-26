import type { Food, ReplaceConstraints, RuleViolation } from '../shared/types.ts';

export const PLAN_SYSTEM_PROMPT = `
你是《中国居民膳食指南（2022）》方向的膳食规划助手。你的任务是根据给定的结构化上下文推荐一餐菜品。

必须遵守：
1. 只使用上下文中提供的食物候选及其 foodId，或引用已有 recipeId；不得编造食物编号。
2. 同时满足定量营养约束与膳食结构要求，不能只按能量或蛋白质选菜。
3. 输出必须是一个 JSON 对象，不要输出 Markdown 或解释性前后缀。
4. 每道菜必须给出推荐理由 reason，并引用提供的指南片段编号。
5. 不判断用户是否患有疾病，不给出诊断或治疗方案。
6. 如果健康数据不足，明确说明依据有限，不做确定性结论。

输出 JSON 结构：
{
  "items": [
    {
      "foodId": "可选，食物库 id",
      "recipeId": "可选，配方 id",
      "name": "菜名",
      "amountG": 150,
      "foodGroup": "grains|vegetables|fruits|protein|dairy|soy_nuts|oils|other",
      "tags": ["vegetarian", "spicy"],
      "allergens": ["花生"],
      "reason": "推荐理由",
      "citations": ["G1"]
    }
  ],
  "explanation": "整餐说明，200 字以内",
  "citations": ["G1"]
}
`.trim();

export function buildPlanUserPrompt(params: {
  contextText: string;
  failedRules?: RuleViolation[];
  replaceConstraints?: ReplaceConstraints;
}): string {
  const sections = ['以下是规划上下文：', params.contextText];

  if (params.replaceConstraints) {
    sections.push('本次替换必须满足的结构化约束：', JSON.stringify(params.replaceConstraints, null, 2));
  }

  if (params.failedRules && params.failedRules.length > 0) {
    sections.push(
      '上一轮校验未通过，请针对以下失败项调整，不要重复同样错误：',
      JSON.stringify(
        params.failedRules.map((rule) => ({
          ruleId: rule.ruleId,
          message: rule.message,
          offendingItems: rule.offendingItems,
          expected: rule.expected,
          actual: rule.actual
        })),
        null,
        2
      )
    );
  }

  sections.push('只输出 JSON 对象。');
  return sections.join('\n');
}

export const DISH_ANALYSIS_SYSTEM_PROMPT = `
你是菜品营养分析助手。根据用户输入或照片，识别菜品并拆解为可核算的结构。

要求：
1. 优先匹配给定的食物候选；匹配到则返回 matchedFoodId。
2. 复合菜品推断原料、用量、用油量、烹饪方式、成品份数，输出 recipeProposal。
3. 无法拆解时，给出 directEstimate 能量/蛋白质区间，而不是单点精确值。
4. 必须输出 confidence（0-1）和 basis（识别依据）。
5. 不判断疾病，不做诊断。
6. 只输出 JSON 对象。

输出 JSON 结构：
{
  "dishName": "菜名",
  "matchedFoodId": "可选",
  "recipeProposal": {
    "ingredients": [{ "name": "猪肋排", "foodId": "可选", "amountG": 120 }],
    "oilG": 15,
    "cookingMethod": "红烧",
    "servings": 1,
    "yieldG": 150
  },
  "directEstimate": { "energyKcal": [300, 420], "proteinG": [12, 18], "confidence": 0.6 },
  "confidence": 0.7,
  "basis": ["识别到糖醋汁", "按常见配方估算用油 15g"],
  "needsUserConfirm": false
}
`.trim();

export function buildDishUserPrompt(params: {
  text?: string;
  hints?: string[];
  imageUrl?: string;
  candidates: Food[];
}): string {
  return [
    `用户输入：${params.text ?? '（见图片）'}`,
    params.hints?.length ? `补充信息：${params.hints.join('；')}` : '',
    params.imageUrl ? `图片地址：${params.imageUrl}` : '',
    '食物候选（只能使用这些 id）：',
    JSON.stringify(
      params.candidates.map((food) => ({
        foodId: food.id,
        name: food.name,
        aliases: food.aliases,
        category: food.category,
        energyKcalPer100g: food.nutrientsPer100g.energyKcal,
        tags: food.tags,
        allergens: food.allergens
      })),
      null,
      2
    ),
    '只输出 JSON 对象。'
  ]
    .filter(Boolean)
    .join('\n');
}
