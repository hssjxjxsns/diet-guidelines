import type { AiGateway } from '../ai/gateway.ts';
import { PLAN_SYSTEM_PROMPT, buildPlanUserPrompt } from '../ai/prompts.ts';
import { mealPlanSchema } from '../ai/schemas.ts';
import { buildPlanningContext } from '../context/builder.ts';
import { computeGap } from '../nutrition/engine.ts';
import type { NutritionEngine } from '../nutrition/engine.ts';
import type { FoodRepository } from '../nutrition/repository.ts';
import type { PlanValidator } from '../nutrition/validator.ts';
import type {
  Food,
  FoodGroup,
  GuidelineChunk,
  InferredTrend,
  MealType,
  Nutrients,
  PlanItem,
  PlanResult,
  RecentTrend,
  ReplaceConstraints,
  RuleViolation,
  StructureResult,
  UserSettings,
  ValidationResult
} from '../shared/types.ts';

export interface GuidelineRetriever {
  retrieve(query: string, limit: number): Promise<GuidelineChunk[]>;
}

export interface PlanSessionStore {
  save(input: {
    userId: string;
    mealDate: string;
    mealType: MealType;
    status: 'planning' | 'awaiting_log';
    contextSnapshot: Record<string, unknown>;
    validationResult: ValidationResult;
    items: PlanItem[];
  }): Promise<string>;
}

export interface UserPlanningContext {
  settings: UserSettings;
  dailyTargets: Nutrients;
  consumed: Nutrients;
  unloggedMeals: MealType[];
  structure: StructureResult;
  recentTrends: RecentTrend[];
  inferredTrends: InferredTrend[];
}

export interface PlanningContextLoader {
  load(userId: string, mealDate: string, mealType: MealType): Promise<UserPlanningContext>;
}

export interface PlanningServiceDeps {
  foods: FoodRepository;
  engine: NutritionEngine;
  validator: PlanValidator;
  ai: AiGateway;
  guidelines: GuidelineRetriever;
  sessions: PlanSessionStore;
  contextLoader: PlanningContextLoader;
}

export interface CreatePlanInput {
  userId: string;
  mealDate: string;
  mealType: MealType;
}

export interface RevisePlanInput extends CreatePlanInput {
  currentMenu: PlanItem[];
  replaceConstraints: ReplaceConstraints;
}

const MAX_REGENERATIONS = 2;

export class PlanningService {
  constructor(private readonly deps: PlanningServiceDeps) {}

  async create(input: CreatePlanInput): Promise<PlanResult> {
    return this.generate(input, undefined, undefined);
  }

  async revise(input: RevisePlanInput): Promise<PlanResult> {
    return this.generate(input, input.replaceConstraints, input.currentMenu);
  }

  private async generate(
    input: CreatePlanInput,
    replaceConstraints: ReplaceConstraints | undefined,
    currentMenu: PlanItem[] | undefined
  ): Promise<PlanResult> {
    const userContext = await this.deps.contextLoader.load(
      input.userId,
      input.mealDate,
      input.mealType
    );

    const gap = computeGap({
      targets: userContext.dailyTargets,
      consumed: userContext.consumed,
      unloggedMeals: userContext.unloggedMeals
    });

    const [candidates, guidelines] = await Promise.all([
      this.deps.foods.listActive(40),
      this.deps.guidelines.retrieve(
        `${input.mealType} 膳食结构 ${userContext.settings.preferences.join(' ')}`,
        4
      )
    ]);

    const context = buildPlanningContext({
      mealDate: input.mealDate,
      mealType: input.mealType,
      dailyTargets: userContext.dailyTargets,
      consumed: userContext.consumed,
      remaining: gap.remaining,
      unloggedMeals: userContext.unloggedMeals,
      structure: userContext.structure,
      recentTrends: userContext.recentTrends,
      explicitSettings: userContext.settings,
      inferredTrends: userContext.inferredTrends,
      guidelines,
      candidateFoods: candidates,
      currentMenu
    });

    const generated = await this.generateAndValidate({
      input,
      userContext,
      contextText: context.text,
      replaceConstraints,
      currentMenu,
      candidates
    });

    const sessionId = await this.deps.sessions.save({
      userId: input.userId,
      mealDate: input.mealDate,
      mealType: input.mealType,
      status: 'planning',
      contextSnapshot: context.snapshot,
      validationResult: generated.validation,
      items: generated.items
    });

    return {
      sessionId,
      items: generated.items,
      explanation: generated.explanation,
      citations: generated.citations,
      validation: generated.validation,
      gap,
      fallback: generated.fallback
    };
  }

  private async generateAndValidate(params: {
    input: CreatePlanInput;
    userContext: UserPlanningContext;
    contextText: string;
    replaceConstraints?: ReplaceConstraints;
    currentMenu?: PlanItem[];
    candidates: Food[];
  }): Promise<{
    items: PlanItem[];
    explanation: string;
    citations: string[];
    validation: ValidationResult;
    fallback: boolean;
  }> {
    let failedRules: RuleViolation[] | undefined;
    let lastValidation: ValidationResult | undefined;

    for (let attempt = 0; attempt <= MAX_REGENERATIONS; attempt += 1) {
      const draft = await this.deps.ai.chatJson(
        {
          scene: params.replaceConstraints ? 'meal_replace' : 'meal_plan',
          system: PLAN_SYSTEM_PROMPT,
          user: buildPlanUserPrompt({
            contextText: params.contextText,
            failedRules,
            replaceConstraints: params.replaceConstraints
          }),
          maxOutputTokens: 1000,
          temperature: 0.3
        },
        mealPlanSchema
      );

      const items: PlanItem[] = draft.items.map((item) => ({
        ...item,
        tags: item.tags ?? [],
        allergens: item.allergens ?? [],
        citations: item.citations ?? []
      }));
      const validation = await this.deps.validator.validate({
        items,
        consumed: params.userContext.consumed,
        dailyTargets: params.userContext.dailyTargets,
        unloggedMeals: params.userContext.unloggedMeals,
        currentMeal: params.input.mealType,
        settings: params.userContext.settings,
        replaceConstraints: params.replaceConstraints,
        explanation: draft.explanation
      });

      lastValidation = validation;
      if (validation.passed) {
        return {
          items,
          explanation: draft.explanation ?? '',
          citations: draft.citations ?? [],
          validation,
          fallback: false
        };
      }

      failedRules = validation.failedRules.filter((rule) => rule.severity === 'hard');
    }

    const fallback = await this.buildFallback(params);
    const validation =
      fallback.validation ??
      lastValidation ?? {
        passed: false,
        failedRules: [],
        warnings: [],
        computedRanges: { energyKcal: [0, 0] },
        ruleVersion: 'fallback'
      };

    return {
      items: fallback.items,
      explanation: fallback.explanation,
      citations: [],
      validation,
      fallback: true
    };
  }

  private async buildFallback(params: {
    input: CreatePlanInput;
    userContext: UserPlanningContext;
    candidates: Food[];
    replaceConstraints?: ReplaceConstraints;
  }): Promise<{ items: PlanItem[]; explanation: string; validation?: ValidationResult }> {
    const pick = (group: FoodGroup): Food | undefined =>
      params.candidates.find(
        (food) =>
          food.status === 'active' &&
          food.category === group &&
          (!params.replaceConstraints?.vegetarian || food.tags.includes('vegetarian')) &&
          (!params.replaceConstraints?.noSpicy || !food.tags.includes('spicy'))
      );

    const selected = [
      { food: pick('vegetables'), amountG: 150 },
      { food: pick('protein'), amountG: 100 },
      { food: pick('grains'), amountG: 100 }
    ].filter((entry): entry is { food: Food; amountG: number } => Boolean(entry.food));

    const items: PlanItem[] = selected.map((entry) => ({
      foodId: entry.food.id,
      name: entry.food.name,
      amountG: entry.amountG,
      foodGroup: entry.food.category,
      tags: entry.food.tags,
      allergens: entry.food.allergens,
      reason: '规则引擎兜底组合：优先保证蔬菜、优质蛋白与主食',
      citations: []
    }));

    const validation = await this.deps.validator.validate({
      items,
      consumed: params.userContext.consumed,
      dailyTargets: params.userContext.dailyTargets,
      unloggedMeals: params.userContext.unloggedMeals,
      currentMeal: params.input.mealType,
      settings: params.userContext.settings,
      replaceConstraints: params.replaceConstraints,
      explanation: ''
    });

    return {
      items,
      explanation: 'AI 规划未通过校验，已由规则引擎给出结构优先的兜底组合。',
      validation
    };
  }
}
