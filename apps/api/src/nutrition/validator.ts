import type {
  FoodGroup,
  MealType,
  Nutrients,
  PlanItem,
  ReplaceConstraints,
  RuleId,
  RuleSeverity,
  RuleViolation,
  ValidationResult,
  ValidationWarning
} from '../shared/types.ts';
import { NutritionEngine, computeMealRange, addNutrients, emptyNutrients, roundNutrients } from '../nutrition/engine.ts';
import type { FoodRepository } from '../nutrition/repository.ts';
import { MEAL_WEIGHTS, NUTRIENT_TOLERANCE } from '../nutrition/targets.ts';

export interface ValidationConfig {
  tolerance: number;
  minVegetableG: Record<MealType, number>;
  minDistinctFoods: Record<MealType, number>;
  scopeKeywords: string[];
  ruleVersion: string;
}

export const DEFAULT_VALIDATION_CONFIG: ValidationConfig = {
  tolerance: NUTRIENT_TOLERANCE,
  minVegetableG: { breakfast: 50, lunch: 100, dinner: 100, snack: 0 },
  minDistinctFoods: { breakfast: 2, lunch: 3, dinner: 3, snack: 1 },
  scopeKeywords: ['诊断为', '患有', '确诊', '治疗方案'],
  ruleVersion: '2026-10-26.1'
};

export interface ValidationInput {
  items: PlanItem[];
  consumed: Nutrients;
  dailyTargets: Nutrients;
  unloggedMeals: MealType[];
  currentMeal: MealType;
  settings: { allergens: string[]; avoid: string[] };
  replaceConstraints?: ReplaceConstraints;
  explanation?: string;
}

interface ExpandedItem {
  item: PlanItem;
  valid: boolean;
  allergens: string[];
  tags: string[];
  foodGroup: FoodGroup;
  energyKcal?: number;
  proteinG?: number;
  vegetableG: number;
}

export class PlanValidator {
  constructor(
    private readonly foods: FoodRepository,
    private readonly engine: NutritionEngine,
    private readonly config: ValidationConfig = DEFAULT_VALIDATION_CONFIG
  ) {}

  async validate(input: ValidationInput): Promise<ValidationResult> {
    const expanded = await this.expand(input.items);
    const failedRules: RuleViolation[] = [];
    const warnings: ValidationWarning[] = [];

    this.checkAllergens(expanded, input.settings.allergens, failedRules);
    this.checkAvoid(expanded, input.settings.avoid, failedRules);
    this.checkFoodValidity(expanded, failedRules);

    const totals = this.totalNutrients(expanded);
    const range = computeMealRange(
      input.dailyTargets,
      input.consumed,
      input.unloggedMeals,
      input.currentMeal,
      this.config.tolerance
    );

    this.checkNutrientRange(totals, range, failedRules, warnings);
    this.checkStructure(expanded, input.currentMeal, failedRules, warnings);
    this.checkReplaceRequest(expanded, totals, input.replaceConstraints, failedRules);
    this.checkDuplicates(expanded, failedRules, warnings);
    this.checkScope(input.explanation, failedRules);

    return {
      passed: failedRules.every((rule) => rule.severity !== 'hard'),
      failedRules,
      warnings,
      computedRanges: {
        energyKcal: range.rangeKcal,
        proteinG: [range.minProteinG, range.expectedProteinG]
      },
      ruleVersion: this.config.ruleVersion
    };
  }

  private async expand(items: PlanItem[]): Promise<ExpandedItem[]> {
    const expanded: ExpandedItem[] = [];

    for (const item of items) {
      const computed = await this.engine.computeItem(
        {
          foodId: item.foodId,
          recipeId: item.recipeId,
          rawName: item.name,
          amountG: item.amountG
        },
        'lunch'
      );

      const allergens = new Set(item.allergens);
      const tags = new Set(item.tags);
      let foodGroup = item.foodGroup;
      let valid = Boolean(item.foodId || item.recipeId);

      if (item.foodId) {
        const food = await this.foods.findById(item.foodId);
        if (food) {
          valid = true;
          foodGroup = food.category;
          food.allergens.forEach((value) => allergens.add(value));
          food.tags.forEach((value) => tags.add(value));
        }
      }

      if (item.recipeId) {
        const recipe = await this.foods.getRecipe(item.recipeId);
        if (recipe) {
          valid = true;
          recipe.allergens.forEach((value) => allergens.add(value));
          recipe.tags.forEach((value) => tags.add(value));
          for (const ingredient of recipe.ingredients) {
            const food = await this.foods.findById(ingredient.foodId);
            if (!food) continue;
            food.allergens.forEach((value) => allergens.add(value));
            food.tags.forEach((value) => tags.add(value));
          }
        }
      }

      if (!item.foodId && !item.recipeId) {
        const byName = await this.foods.findByName(item.name);
        if (byName) {
          valid = true;
          foodGroup = byName.category;
          byName.allergens.forEach((value) => allergens.add(value));
          byName.tags.forEach((value) => tags.add(value));
        }
      }

      expanded.push({
        item,
        valid,
        allergens: [...allergens],
        tags: [...tags],
        foodGroup,
        energyKcal: computed.perItem.values.energyKcal,
        proteinG: computed.perItem.values.proteinG,
        vegetableG: foodGroup === 'vegetables' ? item.amountG : 0
      });
    }

    return expanded;
  }

  private checkAllergens(
    expanded: ExpandedItem[],
    allergens: string[],
    failedRules: RuleViolation[]
  ): void {
    if (allergens.length === 0) return;
    const normalized = allergens.map(normalize);

    for (const entry of expanded) {
      const hits = entry.allergens.filter((allergen) =>
        normalized.some((target) => normalize(allergen).includes(target) || target.includes(normalize(allergen)))
      );
      if (hits.length > 0) {
        failedRules.push({
          ruleId: 'R01_ALLERGEN',
          severity: 'hard',
          message: `${entry.item.name} 命中过敏原：${hits.join('、')}`,
          offendingItems: [entry.item.name],
          expected: allergens,
          actual: entry.allergens
        });
      }
    }
  }

  private checkAvoid(expanded: ExpandedItem[], avoid: string[], failedRules: RuleViolation[]): void {
    if (avoid.length === 0) return;
    const normalized = avoid.map(normalize);

    for (const entry of expanded) {
      const candidates = [entry.item.name, ...entry.tags].map(normalize);
      const hit = candidates.some((candidate) =>
        normalized.some((target) => candidate.includes(target) || target.includes(candidate))
      );
      if (hit) {
        failedRules.push({
          ruleId: 'R02_AVOID',
          severity: 'hard',
          message: `${entry.item.name} 命中忌口食物`,
          offendingItems: [entry.item.name],
          expected: avoid
        });
      }
    }
  }

  private checkFoodValidity(expanded: ExpandedItem[], failedRules: RuleViolation[]): void {
    for (const entry of expanded) {
      if (!entry.valid || entry.energyKcal === undefined) {
        failedRules.push({
          ruleId: 'R03_FOOD_VALID',
          severity: 'hard',
          message: `${entry.item.name} 无法匹配食物库或有效配方`,
          offendingItems: [entry.item.name]
        });
      }
    }
  }

  private checkNutrientRange(
    totals: Nutrients,
    range: ReturnType<typeof computeMealRange>,
    failedRules: RuleViolation[],
    warnings: ValidationWarning[]
  ): void {
    const energy = totals.energyKcal ?? 0;
    if (range.expectedKcal === 0) {
      warnings.push({
        ruleId: 'R04_NUTRIENT_RANGE',
        message: '今日已超出目标，改为结构优先，不再按补差计算'
      });
    } else if (energy < range.rangeKcal[0] || energy > range.rangeKcal[1]) {
      failedRules.push({
        ruleId: 'R04_NUTRIENT_RANGE',
        severity: 'hard',
        message: `本餐能量 ${Math.round(energy)} kcal 超出合理范围 ${range.rangeKcal[0]}–${range.rangeKcal[1]} kcal`,
        offendingItems: [],
        expected: range.rangeKcal,
        actual: Math.round(energy)
      });
    }

    if (range.expectedProteinG > 0 && (totals.proteinG ?? 0) < range.minProteinG) {
      failedRules.push({
        ruleId: 'R04_NUTRIENT_RANGE',
        severity: 'hard',
        message: `本餐蛋白质 ${(totals.proteinG ?? 0).toFixed(1)} g 低于下限 ${range.minProteinG} g`,
        offendingItems: [],
        expected: range.minProteinG,
        actual: roundNutrients(totals).proteinG
      });
    }
  }

  private checkStructure(
    expanded: ExpandedItem[],
    mealType: MealType,
    failedRules: RuleViolation[],
    warnings: ValidationWarning[]
  ): void {
    const vegetableG = expanded.reduce((sum, entry) => sum + entry.vegetableG, 0);
    const distinct = new Set(expanded.map((entry) => entry.item.name)).size;
    const severity: RuleSeverity = mealType === 'lunch' || mealType === 'dinner' ? 'hard' : 'soft';
    const minVegetable = this.config.minVegetableG[mealType];

    if (vegetableG < minVegetable) {
      const violation: RuleViolation = {
        ruleId: 'R05_STRUCTURE',
        severity,
        message: `蔬菜 ${Math.round(vegetableG)} g 低于本餐建议下限 ${minVegetable} g`,
        offendingItems: [],
        expected: minVegetable,
        actual: Math.round(vegetableG)
      };
      if (severity === 'hard') failedRules.push(violation);
      else warnings.push({ ruleId: 'R05_STRUCTURE', message: violation.message });
    }

    if (distinct < this.config.minDistinctFoods[mealType]) {
      warnings.push({
        ruleId: 'R05_STRUCTURE',
        message: `食物种类仅 ${distinct} 种，建议增加不同食物组`
      });
    }
  }

  private checkReplaceRequest(
    expanded: ExpandedItem[],
    totals: Nutrients,
    constraints: ReplaceConstraints | undefined,
    failedRules: RuleViolation[]
  ): void {
    if (!constraints) return;

    if (constraints.vegetarian) {
      const animalTags = ['meat', 'poultry', 'fish', 'seafood', '肉', '鱼', '禽'];
      const offenders = expanded.filter((entry) =>
        entry.tags.some((tag) => animalTags.some((animal) => normalize(tag).includes(normalize(animal))))
      );
      if (offenders.length > 0) {
        failedRules.push({
          ruleId: 'R06_REPLACE_REQUEST',
          severity: 'hard',
          message: '替换要求为素菜，但仍包含动物性食材',
          offendingItems: offenders.map((entry) => entry.item.name)
        });
      }
    }

    if (constraints.noSpicy) {
      const offenders = expanded.filter((entry) => entry.tags.some((tag) => normalize(tag) === 'spicy'));
      if (offenders.length > 0) {
        failedRules.push({
          ruleId: 'R06_REPLACE_REQUEST',
          severity: 'hard',
          message: '替换要求为不辣，但仍包含辣味菜品',
          offendingItems: offenders.map((entry) => entry.item.name)
        });
      }
    }

    for (const group of constraints.requiredFoodGroups ?? []) {
      const amount = expanded
        .filter((entry) => entry.foodGroup === group)
        .reduce((sum, entry) => sum + entry.item.amountG, 0);
      if (amount <= 0) {
        failedRules.push({
          ruleId: 'R06_REPLACE_REQUEST',
          severity: 'hard',
          message: `替换后缺少要求的食物组：${group}`,
          offendingItems: []
        });
      }
    }

    if (constraints.minProteinG !== undefined && (totals.proteinG ?? 0) < constraints.minProteinG) {
      failedRules.push({
        ruleId: 'R06_REPLACE_REQUEST',
        severity: 'hard',
        message: `替换后蛋白质 ${(totals.proteinG ?? 0).toFixed(1)} g 低于要求 ${constraints.minProteinG} g`,
        offendingItems: []
      });
    }

    if (constraints.maxEnergyKcal !== undefined && (totals.energyKcal ?? 0) > constraints.maxEnergyKcal) {
      failedRules.push({
        ruleId: 'R06_REPLACE_REQUEST',
        severity: 'hard',
        message: `替换后能量 ${Math.round(totals.energyKcal ?? 0)} kcal 超过上限 ${constraints.maxEnergyKcal} kcal`,
        offendingItems: []
      });
    }
  }

  private checkDuplicates(
    expanded: ExpandedItem[],
    failedRules: RuleViolation[],
    warnings: ValidationWarning[]
  ): void {
    const seen = new Map<string, number>();
    for (const entry of expanded) {
      const key = normalize(entry.item.name);
      seen.set(key, (seen.get(key) ?? 0) + 1);
    }
    for (const [name, count] of seen) {
      if (count > 1) {
        warnings.push({ ruleId: 'R07_DUPLICATE', message: `${name} 在同一餐中重复出现 ${count} 次` });
      }
    }
    if (failedRules.length === 0 && seen.size === 0) {
      warnings.push({ ruleId: 'R07_DUPLICATE', message: '未提供任何菜品' });
    }
  }

  private checkScope(explanation: string | undefined, failedRules: RuleViolation[]): void {
    if (!explanation) return;
    const hit = this.config.scopeKeywords.find((keyword) => explanation.includes(keyword));
    if (hit) {
      failedRules.push({
        ruleId: 'R08_SCOPE',
        severity: 'hard',
        message: `说明中出现疾病判断用语「${hit}」，请改为一般膳食建议`,
        offendingItems: []
      });
    }
  }

  private totalNutrients(expanded: ExpandedItem[]): Nutrients {
    return roundNutrients(
      expanded.reduce(
        (totals, entry) =>
          addNutrients(totals, {
            energyKcal: entry.energyKcal,
            proteinG: entry.proteinG
          }),
        emptyNutrients()
      )
    );
  }
}

function normalize(value: string): string {
  return value.trim().toLowerCase();
}
