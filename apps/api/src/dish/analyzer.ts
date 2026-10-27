import type { AiGateway } from '../ai/gateway.ts';
import { DISH_ANALYSIS_SYSTEM_PROMPT, buildDishUserPrompt } from '../ai/prompts.ts';
import { dishAnalysisSchema } from '../ai/schemas.ts';
import type { FoodRepository } from '../nutrition/repository.ts';
import type { DishAnalysis } from '../shared/types.ts';

export interface DishAnalyzeInput {
  text?: string;
  imageUrl?: string;
  hints?: string[];
}

export class DishAnalyzer {
  constructor(
    private readonly ai: AiGateway,
    private readonly foods: FoodRepository
  ) {}

  async analyze(input: DishAnalyzeInput): Promise<DishAnalysis> {
    const query = input.text?.trim() || '';
    const candidates = query
      ? await this.foods.search(query, 12)
      : await this.foods.listActive(12);

    const analysis = await this.ai.chatJson(
      {
        scene: 'food_parse',
        system: DISH_ANALYSIS_SYSTEM_PROMPT,
        user: buildDishUserPrompt({ ...input, candidates }),
        maxOutputTokens: 600,
        temperature: 0.2,
        images: input.imageUrl ? [input.imageUrl] : undefined
      },
      dishAnalysisSchema
    );

    if (analysis.matchedFoodId) {
      const matched = await this.foods.findById(analysis.matchedFoodId);
      if (!matched) {
        analysis.matchedFoodId = undefined;
        (analysis.basis ??= []).push('AI 返回的食物编号不在食物库中，已忽略');
      }
    }

    return {
      dishName: analysis.dishName,
      matchedFoodId: analysis.matchedFoodId,
      recipeProposal: analysis.recipeProposal,
      directEstimate: analysis.directEstimate,
      confidence: analysis.confidence,
      basis: analysis.basis ?? [],
      needsUserConfirm: analysis.confidence < 0.7
    };
  }
}
