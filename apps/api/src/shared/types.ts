export const NUTRIENT_KEYS = [
  'energyKcal',
  'proteinG',
  'fatG',
  'carbG',
  'fiberG',
  'sodiumMg',
  'calciumMg',
  'ironMg'
] as const;

export type NutrientKey = (typeof NUTRIENT_KEYS)[number];
export type Nutrients = Partial<Record<NutrientKey, number>>;

export type MealType = 'breakfast' | 'lunch' | 'dinner' | 'snack';

export type ValueType = 'measured' | 'estimated' | 'ai_estimated';

export type FoodGroup =
  | 'grains'
  | 'vegetables'
  | 'fruits'
  | 'protein'
  | 'dairy'
  | 'soy_nuts'
  | 'oils'
  | 'other';

export type FoodStatus = 'active' | 'deprecated' | 'pending_review';

export interface Food {
  id: string;
  name: string;
  aliases: string[];
  category: FoodGroup;
  nutrientsPer100g: Nutrients;
  edibleRatio: number;
  source: string;
  sourceVersion: string;
  status: FoodStatus;
  tags: string[];
  allergens: string[];
  unitConversion?: Record<string, number>;
}

export interface RecipeIngredient {
  foodId: string;
  amountG: number;
}

export interface Recipe {
  id: string;
  name: string;
  servings: number;
  yieldG: number;
  oilG: number;
  cookingMethod: string;
  source: string;
  status: FoodStatus;
  ingredients: RecipeIngredient[];
  tags: string[];
  allergens: string[];
}

export interface MealInputItem {
  foodId?: string;
  recipeId?: string;
  rawName: string;
  amountG: number;
  valueType?: ValueType;
  analysis?: DishAnalysis;
}

export interface MealInput {
  userId: string;
  mealDate: string;
  mealType: MealType;
  items: MealInputItem[];
  nutritionDataVersion: string;
}

export interface PerItemNutrition {
  foodId?: string;
  recipeId?: string;
  rawName: string;
  amountG: number;
  valueType: ValueType;
  values: Nutrients;
}

export interface FoodGroupStatus {
  group: FoodGroup;
  amountG: number;
  recommendedRangeG: [number, number];
  status: 'insufficient' | 'adequate' | 'excessive';
}

export interface StructureResult {
  foodGroups: FoodGroupStatus[];
  distinctFoodCount: number;
  diversityNote: string;
}

export interface NutritionResult {
  quantitative: {
    values: Nutrients;
    perItem: PerItemNutrition[];
  };
  structure: StructureResult;
  valueType: ValueType;
  warnings: string[];
}

export interface MealAllocation {
  mealType: MealType;
  weight: number;
  expectedKcal: number;
  rangeKcal: [number, number];
}

export interface GapResult {
  dailyTargets: Nutrients;
  consumed: Nutrients;
  remaining: Nutrients;
  unloggedMeals: MealType[];
  allocations: MealAllocation[];
}

export interface DishAnalysis {
  dishName: string;
  matchedFoodId?: string;
  recipeProposal?: {
    ingredients: Array<{ name: string; foodId?: string; amountG: number }>;
    oilG: number;
    cookingMethod: string;
    servings: number;
    yieldG: number;
  };
  directEstimate?: {
    energyKcal: [number, number];
    proteinG?: [number, number];
    confidence: number;
  };
  confidence: number;
  basis: string[];
  needsUserConfirm: boolean;
}

export interface PlanItem {
  foodId?: string;
  recipeId?: string;
  name: string;
  amountG: number;
  foodGroup: FoodGroup;
  tags: string[];
  allergens: string[];
  reason?: string;
  citations?: string[];
}

export interface PlanDraft {
  items: PlanItem[];
  explanation: string;
  citations: string[];
}

export type RuleId =
  | 'R01_ALLERGEN'
  | 'R02_AVOID'
  | 'R03_FOOD_VALID'
  | 'R04_NUTRIENT_RANGE'
  | 'R05_STRUCTURE'
  | 'R06_REPLACE_REQUEST'
  | 'R07_DUPLICATE'
  | 'R08_SCOPE';

export type RuleSeverity = 'hard' | 'soft';

export interface RuleViolation {
  ruleId: RuleId;
  severity: RuleSeverity;
  message: string;
  offendingItems: string[];
  expected?: unknown;
  actual?: unknown;
}

export interface ValidationWarning {
  ruleId: RuleId;
  message: string;
}

export interface ValidationResult {
  passed: boolean;
  failedRules: RuleViolation[];
  warnings: ValidationWarning[];
  computedRanges: {
    energyKcal: [number, number];
    proteinG?: [number, number];
    sodiumMg?: [number, number];
  };
  ruleVersion: string;
}

export interface ReplaceConstraints {
  vegetarian?: boolean;
  noSpicy?: boolean;
  requiredFoodGroups?: FoodGroup[];
  minProteinG?: number;
  maxEnergyKcal?: number;
}

export interface MealGroupKey {
  userId: string;
  mealDate: string;
  mealType: MealType;
  instanceSeq: number;
}

export interface MealGroup extends MealGroupKey {
  id: string;
  status: 'active' | 'closed';
  createdAt: string;
}

export type MealEntryMode = 'append' | 'replace';

export interface MealEntryItem {
  foodId?: string;
  recipeId?: string;
  rawName: string;
  amountG: number;
  valueType: ValueType;
  nutrients: Nutrients;
  analysis?: DishAnalysis;
}

export interface MealEntry {
  id: string;
  groupId: string;
  userId: string;
  mode: MealEntryMode;
  revision: number;
  baseRevision?: number;
  totals: Nutrients;
  valueType: ValueType;
  items: MealEntryItem[];
  createdAt: string;
}

export interface SubmitMealInput {
  userId: string;
  mealDate: string;
  mealType: MealType;
  instanceSeq?: number;
  mode: MealEntryMode;
  baseRevision?: number;
  idempotencyKey: string;
  items: MealInputItem[];
  nutritionDataVersion: string;
}

export interface SubmitMealResult {
  group: MealGroup;
  entry: MealEntry;
  effectiveEntries: MealEntry[];
  dailyTotals: Nutrients;
}

export interface UserSettings {
  dailyTargets: Nutrients;
  preferences: string[];
  allergens: string[];
  avoid: string[];
}

export type TrendStatus = 'candidate' | 'verified' | 'invalidated';

export interface InferredTrend {
  metric: NutrientKey | 'vegetables_g';
  conclusion: string;
  status: TrendStatus;
  evidence: {
    windowStart: string;
    windowEnd: string;
    sampleMeals: number;
    deviation: number;
  };
}

export interface GuidelineChunk {
  id: string;
  content: string;
  metadata: Record<string, unknown>;
  score?: number;
}

export interface RecentTrend {
  metric: string;
  direction: 'up' | 'down' | 'stable';
  summary: string;
}

export interface PlanSession {
  id: string;
  userId: string;
  mealDate: string;
  mealType: MealType;
  status: 'planning' | 'awaiting_log' | 'logged' | 'analyzed' | 'abandoned';
  contextSnapshot: Record<string, unknown>;
  validationResult?: ValidationResult;
  createdAt: string;
}

export interface PlanResult {
  sessionId: string;
  items: PlanItem[];
  explanation: string;
  citations: string[];
  validation: ValidationResult;
  gap: GapResult;
  fallback: boolean;
}
